package app.debunkax.testing

import app.debunkax.domain.CheckClient
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckResult
import app.debunkax.domain.ClaimRecord
import app.debunkax.domain.Explanation
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.HistoryFilter
import app.debunkax.domain.HistoryRepository
import app.debunkax.domain.SessionHistory
import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.TranscriptSegment
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * A [SpeechEngine] a test drives by hand: nothing is emitted until [emitFinal]/[emitPartial] are called,
 * so a test controls exactly when a segment reaches the [app.debunkax.session.ListeningSession].
 */
class FakeSpeechEngine : SpeechEngine {
    private val channels = mutableListOf<Channel<TranscriptSegment>>()

    /** How many times [transcribe] was collected; a test waits for this before emitting. */
    val attemptCount: Int get() = channels.size

    override fun transcribe(): Flow<TranscriptSegment> {
        val channel = Channel<TranscriptSegment>(Channel.UNLIMITED)
        channels += channel
        return channel.receiveAsFlow()
    }

    suspend fun emitFinal(text: String, at: Instant = Instant.now()) =
        current().send(TranscriptSegment(text, isFinal = true, at = at))

    suspend fun emitPartial(text: String, at: Instant = Instant.now()) =
        current().send(TranscriptSegment(text, isFinal = false, at = at))

    fun fail(failure: SpeechFailure) = current().close(failure)

    private fun current(): Channel<TranscriptSegment> =
        channels.lastOrNull() ?: error("transcribe() was not collected yet")
}

/** A [CheckClient] whose outcomes for `check`/`explain` a test chooses ahead of time. */
class FakeCheckClient : CheckClient {
    private val scripted = ArrayDeque<suspend () -> CheckOutcome>()
    val checkedClaims = mutableListOf<HeardClaim>()
    var explanationToReturn: Explanation = Explanation("Explication de test.", emptyList())

    fun enqueueChecked(result: CheckResult) = scripted.addLast { CheckOutcome.Checked(result) }
    fun enqueueNotAClaim() = scripted.addLast { CheckOutcome.NotAClaim }

    override suspend fun check(claim: HeardClaim): CheckOutcome {
        checkedClaims += claim
        val next = scripted.removeFirstOrNull() ?: error("FakeCheckClient: no scripted response left for ${claim.text}")
        return next()
    }

    override suspend fun explain(claimText: String, result: CheckResult): Explanation = explanationToReturn
}

/**
 * An in-memory [HistoryRepository] for instrumented UI tests: no Room, no disk, cleared between tests
 * simply by creating a new instance. Supports the real query/search/filter semantics the UI exercises.
 */
class InMemoryHistoryRepository : HistoryRepository {
    private data class SessionEntry(val id: Long, val startedAt: Instant, val endedAt: Instant?, val claims: List<ClaimRecord>)

    private var nextSessionId = 1L
    private var nextClaimId = 1L
    private val sessionsFlow = MutableStateFlow<List<SessionEntry>>(emptyList())

    override suspend fun startSession(at: Instant): Long {
        val id = nextSessionId++
        sessionsFlow.update { it + SessionEntry(id, at, null, emptyList()) }
        return id
    }

    override suspend fun closeUnfinishedSessions(before: Instant) = Unit

    override suspend fun endSession(sessionId: Long, at: Instant) {
        sessionsFlow.update { list -> list.map { if (it.id == sessionId) it.copy(endedAt = at) else it } }
    }

    override suspend fun addClaim(sessionId: Long, text: String, heardAt: Instant, result: CheckResult?, speaker: String?): Long {
        val id = nextClaimId++
        val record = ClaimRecord(id, sessionId, text, heardAt, result, null, speaker)
        sessionsFlow.update { list -> list.map { if (it.id == sessionId) it.copy(claims = it.claims + record) else it } }
        return id
    }

    override suspend fun setResult(claimId: Long, result: CheckResult) {
        sessionsFlow.update { list ->
            list.map { s -> s.copy(claims = s.claims.map { c -> if (c.id == claimId) c.copy(result = result) else c }) }
        }
    }

    override suspend fun setExplanation(claimId: Long, explanation: Explanation) {
        sessionsFlow.update { list ->
            list.map { s -> s.copy(claims = s.claims.map { c -> if (c.id == claimId) c.copy(explanation = explanation) else c }) }
        }
    }

    override suspend fun deleteClaim(claimId: Long) {
        sessionsFlow.update { list -> list.map { s -> s.copy(claims = s.claims.filterNot { it.id == claimId }) } }
    }

    override suspend fun pendingClaims(): List<ClaimRecord> =
        sessionsFlow.value.flatMap { it.claims }.filter { it.result == null }

    override fun sessions(filter: HistoryFilter): Flow<List<SessionHistory>> = sessionsFlow.map { list ->
        val filterActive = filter.query.isNotBlank() || filter.verdicts.isNotEmpty()
        list.asReversed().mapNotNull { s ->
            val claims = s.claims.filter { c ->
                (filter.query.isBlank() || c.text.contains(filter.query, ignoreCase = true)) &&
                    (filter.verdicts.isEmpty() || (c.result != null && c.result.verdict in filter.verdicts))
            }
            if (filterActive && claims.isEmpty()) null else SessionHistory(s.id, s.startedAt, s.endedAt, claims)
        }
    }

    override fun claim(claimId: Long): Flow<ClaimRecord?> = sessionsFlow.map { list ->
        list.flatMap { it.claims }.find { it.id == claimId }
    }

    override suspend fun clearAll() {
        sessionsFlow.value = emptyList()
    }
}
