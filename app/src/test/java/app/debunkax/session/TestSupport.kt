@file:OptIn(ExperimentalCoroutinesApi::class)

package app.debunkax.session

import app.debunkax.domain.CheckClient
import app.debunkax.domain.CheckFailure
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckResult
import app.debunkax.domain.ClaimRecord
import app.debunkax.domain.Explanation
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.HistoryFilter
import app.debunkax.domain.HistoryRepository
import app.debunkax.domain.SessionHistory
import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.Spend
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.TranscriptSegment
import app.debunkax.domain.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Arbitrary fixed origin; virtual time from the test scheduler is added on top of it. */
val TEST_BASE_INSTANT: Instant = Instant.parse("2000-01-01T20:00:00Z")

/** A [Clock] whose [instant] moves exactly with the test scheduler's virtual time. */
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val base: Instant = TEST_BASE_INSTANT,
) : Clock() {
    override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

/** The current virtual instant, on the same basis as [VirtualClock]. Use to stamp [TranscriptSegment]s. */
fun TestScope.now(base: Instant = TEST_BASE_INSTANT): Instant = base.plusMillis(testScheduler.currentTime)

fun result(verdict: Verdict, context: String = "Contexte factuel.") =
    CheckResult(verdict, context, emptyList())

/** A [SpeechEngine] whose [transcribe] flow is fed manually, one [Channel] per call (so retries get a fresh flow). */
class FakeSpeechEngine : SpeechEngine {
    private val channels = mutableListOf<Channel<TranscriptSegment>>()

    val attemptCount: Int get() = channels.size

    override fun transcribe(): Flow<TranscriptSegment> {
        val channel = Channel<TranscriptSegment>(Channel.UNLIMITED)
        channels += channel
        return channel.receiveAsFlow()
    }

    suspend fun emitFinal(text: String, at: Instant) = current().send(TranscriptSegment(text, isFinal = true, at = at))
    suspend fun emitPartial(text: String, at: Instant) = current().send(TranscriptSegment(text, isFinal = false, at = at))
    /** The audio source ended normally (e.g. the app-audio consent token was revoked). */
    fun end() {
        current().close()
    }
    fun fail(failure: SpeechFailure) {
        current().close(failure)
    }

    private fun current(): Channel<TranscriptSegment> =
        channels.lastOrNull() ?: error("transcribe() was not called yet - advance the dispatcher first")
}

/** A scriptable [CheckClient]: enqueue one response per expected call, in order. */
class FakeCheckClient : CheckClient {
    private val scripted = ArrayDeque<suspend () -> CheckOutcome>()
    val checkedClaims = mutableListOf<HeardClaim>()
    val explainCalls = mutableListOf<Pair<String, CheckResult>>()
    var explanationToReturn: Explanation = Explanation("Explication.", emptyList())
    override var spent: Spend? = null

    fun enqueueChecked(result: CheckResult) = scripted.addLast { CheckOutcome.Checked(result) }
    fun enqueueNotAClaim() = scripted.addLast { CheckOutcome.NotAClaim }
    fun enqueueFailure(failure: CheckFailure) = scripted.addLast { throw failure }
    fun enqueueDelayedChecked(delayMillis: Long, result: CheckResult) =
        scripted.addLast {
            delay(delayMillis)
            CheckOutcome.Checked(result)
        }

    override suspend fun check(claim: HeardClaim): CheckOutcome {
        checkedClaims += claim
        val next = scripted.removeFirstOrNull() ?: error("FakeCheckClient: no scripted response left for ${claim.text}")
        return next()
    }

    override suspend fun explain(claimText: String, result: CheckResult): Explanation {
        explainCalls += claimText to result
        return explanationToReturn
    }
}

/** An in-memory [HistoryRepository]. [sessions] and [claim] (the Flow-returning queries) aren't needed by these tests. */
class FakeHistoryRepository : HistoryRepository {
    data class SessionRecord(val id: Long, val startedAt: Instant, var endedAt: Instant? = null)

    val sessionRecords = mutableListOf<SessionRecord>()
    val claims = LinkedHashMap<Long, ClaimRecord>()
    val setExplanationCalls = mutableListOf<Pair<Long, Explanation>>()

    private var nextSessionId = 1L
    private var nextClaimId = 1L

    override suspend fun startSession(at: Instant): Long {
        val id = nextSessionId++
        sessionRecords += SessionRecord(id, at)
        return id
    }

    override suspend fun closeUnfinishedSessions(before: Instant) = Unit

    override suspend fun endSession(sessionId: Long, at: Instant) {
        sessionRecords.first { it.id == sessionId }.endedAt = at
    }

    override suspend fun addClaim(sessionId: Long, text: String, heardAt: Instant, result: CheckResult?, speaker: String?): Long {
        val id = nextClaimId++
        claims[id] = ClaimRecord(id, sessionId, text, heardAt, result, null, speaker)
        return id
    }

    override suspend fun setResult(claimId: Long, result: CheckResult) {
        claims[claimId] = claims.getValue(claimId).copy(result = result)
    }

    override suspend fun setExplanation(claimId: Long, explanation: Explanation) {
        setExplanationCalls += claimId to explanation
        claims[claimId] = claims.getValue(claimId).copy(explanation = explanation)
    }

    override suspend fun deleteClaim(claimId: Long) {
        claims.remove(claimId)
    }

    override suspend fun pendingClaims(): List<ClaimRecord> = claims.values.filter { it.result == null }.toList()

    override fun sessions(filter: HistoryFilter): Flow<List<SessionHistory>> =
        throw UnsupportedOperationException("not needed by these tests")

    override fun claim(claimId: Long): Flow<ClaimRecord?> =
        throw UnsupportedOperationException("not needed by these tests")

    override suspend fun clearAll() {
        sessionRecords.clear()
        claims.clear()
    }
}

/** Names [speaker] for every sentence once started. */
class FakeSpeakerRecognition(var speaker: String?, var addressee: String? = null) : app.debunkax.domain.SpeakerRecognition {
    var started = false
    /** Every addressee lookup: the instant searched back from, and the claimant. */
    val addresseeCalls = mutableListOf<Pair<Instant, String>>()
    override fun addresseeBefore(before: Instant, claimant: String): String? {
        addresseeCalls += before to claimant
        return addressee
    }
    override fun start() { started = true }
    override fun stop() { started = false }
    override fun speakerBetween(from: Instant, to: Instant): String? = speaker.takeIf { started }
}

/** Answers for fixed time windows, from latest to earliest; null outside every window. */
class ScriptedSpeakerRecognition(vararg windows: Triple<Instant, Instant, String?>) : app.debunkax.domain.SpeakerRecognition {
    private val windows = windows.toList()
    override fun start() {}
    override fun stop() {}
    override fun speakerBetween(from: Instant, to: Instant): String? =
        windows.lastOrNull { it.first < to && it.second > from }?.third
}
