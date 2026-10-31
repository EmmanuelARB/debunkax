package app.debunkax.session

import app.debunkax.domain.AlertPolicy
import app.debunkax.domain.CheckBudget
import app.debunkax.domain.CheckClient
import app.debunkax.domain.CheckFailure
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckResult
import app.debunkax.domain.ClaimDetector
import app.debunkax.domain.Explanation
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.HistoryRepository
import app.debunkax.domain.RepeatedClaimMatcher
import app.debunkax.domain.RollingTranscript
import app.debunkax.domain.SecondPerson
import app.debunkax.domain.SentenceTiming
import app.debunkax.domain.Sentences
import app.debunkax.domain.SpeakerRecognition
import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.Spend
import app.debunkax.domain.SpeechFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** [speaker] is null when unknown. */
data class ActiveAlert(val claimId: Long, val claimText: String, val result: CheckResult, val speaker: String? = null)

sealed interface ChecksPaused {
    data object NoProvider : ChecksPaused
    data class Failed(val failure: CheckFailure) : ChecksPaused
}

data class SessionState(
    val listening: Boolean = false,
    val startedAt: Instant? = null,
    val spent: Spend? = null,
    val liveText: String = "",
    /** Who is speaking in the last few seconds, for the live transcript; null when unknown or in doubt. */
    val liveSpeaker: String? = null,
    val alert: ActiveAlert? = null,
    val checksUsed: Int = 0,
    val checkLimit: Int? = null,
    val checksPaused: ChecksPaused? = null,
    val speechProblem: SpeechFailure? = null,
)

/**
 * One Listening Session: speech → transcript window → claim filter → Check → Alert and History.
 * The transcript lives only in memory and is dropped at [stop].
 */
class ListeningSession(
    private val speech: SpeechEngine,
    private val detector: ClaimDetector,
    private val history: HistoryRepository,
    private val checkClient: CheckClient?,
    checkLimit: Int?,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val modelRetryDelay: Duration = Duration.ofSeconds(5),
    private val speakers: SpeakerRecognition? = null,
) {
    private val _state = MutableStateFlow(
        SessionState(checkLimit = checkLimit, checksPaused = if (checkClient == null) ChecksPaused.NoProvider else null)
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val transcript = RollingTranscript()
    private val repeats = RepeatedClaimMatcher()
    private val budget = CheckBudget(checkLimit)
    private val concurrentChecks = Semaphore(2)

    private var sessionId: Long? = null
    private var listenJob: Job? = null

    fun start() {
        if (listenJob != null) return
        val startedAt = clock.instant()
        _state.update { it.copy(listening = true, startedAt = startedAt, speechProblem = null) }
        speakers?.start()
        listenJob = scope.launch {
            val id = history.startSession(clock.instant())
            sessionId = id
            listen(id)
        }
    }

    suspend fun stop() {
        val job = listenJob ?: return
        listenJob = null
        job.cancel()
        speakers?.stop()
        transcript.clear()
        repeats.clear()
        _state.update { it.copy(listening = false, startedAt = null, liveText = "", liveSpeaker = null, alert = null) }
        sessionId?.let { history.endSession(it, clock.instant()) }
        sessionId = null
    }

    fun dismissAlert() {
        _state.update { it.copy(alert = null) }
    }

    private suspend fun listen(sessionId: Long) {
        while (true) {
            try {
                speech.transcribe().collect { segment ->
                    val now = clock.instant()
                    val liveSpeaker = speakers?.speakerBetween(now - LIVE_SPEAKER_WINDOW, now)
                    _state.update { it.copy(liveText = segment.text, liveSpeaker = liveSpeaker, speechProblem = null) }
                    if (!segment.isFinal) return@collect
                    val sentences = Sentences.split(segment.text)
                    for ((sentence, span) in sentences.zip(SentenceTiming.spans(sentences, segment.at))) {
                        val context = transcript.textAt(segment.at)
                        transcript.append(sentence, segment.at)
                        if (detector.isClaim(sentence)) {
                            val speaker = speakers?.speakerBetween(span.first, span.second)
                            // Only a recognised claimant, so the addressee can't be the claimant themselves.
                            val addressee = if (speaker != null && SecondPerson.isIn(sentence)) speakers?.addresseeBefore(span.first, speaker) else null
                            val claim = HeardClaim(sentence, segment.at, context, speaker, addressee)
                            scope.launch { handleClaim(sessionId, claim) }
                        }
                    }
                }
                break // The audio source ended (e.g. the app-audio consent was revoked).
            } catch (e: SpeechFailure.ModelDownloading) {
                _state.update { it.copy(speechProblem = e) }
                delay(modelRetryDelay.toMillis())
            } catch (e: SpeechFailure) {
                _state.update { it.copy(speechProblem = e) }
                endOnAudioEnd(sessionId)
                return
            }
        }
        endOnAudioEnd(sessionId)
    }

    /**
     * Same cleanup as [stop] for when the audio ends on its own: the listen job finishes instead
     * of being cancelled, so it must not cancel itself — it only clears state and closes the
     * History row. Guarded against a concurrent [stop].
     */
    private suspend fun endOnAudioEnd(sessionId: Long) {
        if (this.sessionId != sessionId) return // already stopped
        speakers?.stop()
        transcript.clear()
        repeats.clear()
        _state.update { it.copy(listening = false, startedAt = null, liveText = "", liveSpeaker = null, alert = null) }
        history.endSession(sessionId, clock.instant())
        this.sessionId = null
    }

    private suspend fun handleClaim(sessionId: Long, claim: HeardClaim) {
        repeats.find(claim.text, claim.speaker)?.let { known ->
            val id = history.addClaim(sessionId, claim.text, claim.heardAt, known, claim.speaker)
            maybeAlert(id, claim, known)
            return
        }
        val client = checkClient
        if (client == null) {
            history.addClaim(sessionId, claim.text, claim.heardAt, null, claim.speaker)
            return
        }
        if (!budget.tryConsume()) {
            history.addClaim(sessionId, claim.text, claim.heardAt, null, claim.speaker)
            _state.update { it.copy(checksPaused = ChecksPaused.Failed(CheckFailure.BudgetReached())) }
            return
        }
        _state.update { it.copy(checksUsed = budget.used) }
        try {
            when (val outcome = concurrentChecks.withPermit { client.check(claim) }) {
                CheckOutcome.NotAClaim -> Unit
                is CheckOutcome.Checked -> {
                    repeats.remember(claim.text, outcome.result, claim.speaker)
                    val id = history.addClaim(sessionId, claim.text, claim.heardAt, outcome.result, claim.speaker)
                    _state.update { if (it.checksPaused is ChecksPaused.Failed) it.copy(checksPaused = null) else it }
                    maybeAlert(id, claim, outcome.result)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CheckFailure) {
            history.addClaim(sessionId, claim.text, claim.heardAt, null, claim.speaker)
            _state.update { it.copy(checksPaused = ChecksPaused.Failed(e)) }
        } finally {
            _state.update { it.copy(spent = client.spent) }
        }
    }

    private fun maybeAlert(claimId: Long, claim: HeardClaim, result: CheckResult) {
        // A Check that finishes after the session ended is still saved, but must not raise an Alert on the idle screen.
        if (!_state.value.listening) return
        if (!AlertPolicy.shouldAlert(result.verdict, claim.heardAt, clock.instant())) return
        // Stays until dismissed or replaced by a newer Alert, so there's time to read it.
        // The quote is the sentence as heard; the rewrite only feeds the Check and the detail screen.
        _state.update { it.copy(alert = ActiveAlert(claimId, claim.text, result, claim.speaker)) }
    }

    private companion object {
        /** Two recognition windows: enough to follow a change of Speaker within a few seconds. */
        val LIVE_SPEAKER_WINDOW: Duration = Duration.ofSeconds(5)
    }
}

/** [failure] is why re-checking stopped or last failed; null when every attempt succeeded. */
data class PendingCheckReport(val checked: Int, val stillPending: Int, val failure: CheckFailure?)

/** Checks Pending Claims once possible. Their surrounding transcript is gone, so they are checked on their own text. */
class PendingClaimChecker(private val history: HistoryRepository) {
    suspend fun checkAll(client: CheckClient): PendingCheckReport {
        var checked = 0
        var failure: CheckFailure? = null
        for (pending in history.pendingClaims()) {
            try {
                when (val outcome = client.check(HeardClaim(pending.text, pending.heardAt, surroundingTranscript = "", speaker = pending.speaker))) {
                    CheckOutcome.NotAClaim -> history.deleteClaim(pending.id)
                    is CheckOutcome.Checked -> history.setResult(pending.id, outcome.result)
                }
                checked++
            } catch (e: CheckFailure) {
                failure = e
                if (e is CheckFailure.Offline || e is CheckFailure.InvalidKey || e is CheckFailure.NoCredit || e is CheckFailure.SearchDisabled) break
            }
        }
        return PendingCheckReport(checked, history.pendingClaims().size, failure)
    }
}

/** Loads a Claim's Explanation, generating and saving it once so reopening is free. */
class ExplanationLoader(private val history: HistoryRepository) {
    suspend fun load(claimId: Long, claimText: String, result: CheckResult, existing: Explanation?, client: CheckClient): Explanation =
        existing ?: client.explain(claimText, result).also { history.setExplanation(claimId, it) }
}
