package app.debunkax.history

import app.debunkax.domain.CheckResult
import app.debunkax.domain.ClaimRecord
import app.debunkax.domain.Explanation
import app.debunkax.domain.HistoryFilter
import app.debunkax.domain.HistoryRepository
import app.debunkax.domain.RepeatedClaimMatcher
import app.debunkax.domain.SessionHistory
import app.debunkax.domain.Verdict
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [HistoryRepository]. Only Claim text, Verdict data and timestamps ever reach the schema. */
class RoomHistoryRepository(private val dao: HistoryDao) : HistoryRepository {

    override suspend fun startSession(at: Instant): Long =
        dao.insertSession(SessionEntity(startedAt = at.toEpochMilli(), endedAt = null))

    override suspend fun endSession(sessionId: Long, at: Instant) {
        dao.endSession(sessionId, at.toEpochMilli())
    }

    override suspend fun closeUnfinishedSessions(before: Instant) {
        dao.closeUnfinishedSessions(before.toEpochMilli())
    }

    override suspend fun addClaim(sessionId: Long, text: String, heardAt: Instant, result: CheckResult?, speaker: String?): Long =
        dao.insertClaim(newClaimEntity(sessionId, text, heardAt, result, speaker))

    override suspend fun setResult(claimId: Long, result: CheckResult) {
        dao.updateResult(claimId, result.verdict.name, result.context, result.sources.toJsonOrNull(), result.checkedText)
    }

    override suspend fun setExplanation(claimId: Long, explanation: Explanation) {
        dao.updateExplanation(claimId, explanation.text, explanation.sources.toJsonOrNull())
    }

    override suspend fun deleteClaim(claimId: Long) {
        dao.deleteClaimById(claimId)
    }

    override suspend fun pendingClaims(): List<ClaimRecord> = dao.pendingClaims().map { it.toDomain() }

    override fun sessions(filter: HistoryFilter): Flow<List<SessionHistory>> =
        dao.sessionsWithClaims().map { it.applyFilter(filter) }

    override fun claim(claimId: Long): Flow<ClaimRecord?> = dao.claimFlow(claimId).map { it?.toDomain() }

    override suspend fun clearAll() {
        // Deleting sessions cascades the claims that reference them; claims never outlive their session.
        dao.deleteAllSessions()
        dao.deleteAllClaims()
    }
}

private fun List<SessionWithClaims>.applyFilter(filter: HistoryFilter): List<SessionHistory> {
    val normalizedQuery = RepeatedClaimMatcher.normalize(filter.query)
    val isFiltering = normalizedQuery.isNotEmpty() || filter.verdicts.isNotEmpty()

    return mapNotNull { sessionWithClaims ->
        val claims = sessionWithClaims.claims
            .sortedByDescending { it.heardAt }
            .filter { claim ->
                val matchesText = normalizedQuery.isEmpty() ||
                    RepeatedClaimMatcher.normalize(claim.text).contains(normalizedQuery)
                val matchesVerdict = filter.verdicts.isEmpty() ||
                    (claim.verdict != null && Verdict.valueOf(claim.verdict) in filter.verdicts)
                matchesText && matchesVerdict
            }
            .map { it.toDomain() }

        if (isFiltering && claims.isEmpty()) {
            null
        } else {
            SessionHistory(
                sessionId = sessionWithClaims.session.id,
                startedAt = Instant.ofEpochMilli(sessionWithClaims.session.startedAt),
                endedAt = sessionWithClaims.session.endedAt?.let(Instant::ofEpochMilli),
                claims = claims,
            )
        }
    }
}
