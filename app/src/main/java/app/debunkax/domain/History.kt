package app.debunkax.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class ClaimRecord(
    val id: Long,
    val sessionId: Long,
    val text: String,
    val heardAt: Instant,
    /** Null while the Claim is a Pending Claim. */
    val result: CheckResult?,
    val explanation: Explanation?,
    /** Recognised by voice when heard; null when unknown. */
    val speaker: String? = null,
) {
    /** The form this Claim was checked as: the clear rewrite, else the sentence as transcribed. The quote shown is [text]. */
    val checkedText: String get() = result?.checkedText ?: text
}

data class SessionHistory(
    val sessionId: Long,
    val startedAt: Instant,
    val endedAt: Instant?,
    val claims: List<ClaimRecord>,
)

data class HistoryFilter(val query: String = "", val verdicts: Set<Verdict> = emptySet())

/** The only persisted data. Never store audio or transcripts here, only Claim text. */
interface HistoryRepository {
    suspend fun startSession(at: Instant): Long
    suspend fun endSession(sessionId: Long, at: Instant)

    /** Ends sessions started before [before] that were never stopped (app killed), at their last Claim or their start. */
    suspend fun closeUnfinishedSessions(before: Instant)

    suspend fun addClaim(sessionId: Long, text: String, heardAt: Instant, result: CheckResult?, speaker: String? = null): Long
    suspend fun setResult(claimId: Long, result: CheckResult)
    suspend fun setExplanation(claimId: Long, explanation: Explanation)
    suspend fun deleteClaim(claimId: Long)

    suspend fun pendingClaims(): List<ClaimRecord>

    /** Newest session first, and newest claim first within each; sessions with no matching claim are omitted when a filter is active. */
    fun sessions(filter: HistoryFilter): Flow<List<SessionHistory>>
    fun claim(claimId: Long): Flow<ClaimRecord?>

    suspend fun clearAll()
}
