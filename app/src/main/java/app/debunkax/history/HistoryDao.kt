package app.debunkax.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endedAt: Long)

    @Query(
        "UPDATE sessions SET endedAt = COALESCE((SELECT MAX(heardAt) FROM claims WHERE claims.sessionId = sessions.id), startedAt) " +
            "WHERE endedAt IS NULL AND startedAt < :before"
    )
    suspend fun closeUnfinishedSessions(before: Long)

    @Insert
    suspend fun insertClaim(claim: ClaimEntity): Long

    @Query("UPDATE claims SET verdict = :verdict, context = :context, sources = :sources, checkedText = :checkedText WHERE id = :claimId")
    suspend fun updateResult(claimId: Long, verdict: String, context: String, sources: String?, checkedText: String?)

    @Query("UPDATE claims SET explanationText = :text, explanationSources = :sources WHERE id = :claimId")
    suspend fun updateExplanation(claimId: Long, text: String, sources: String?)

    @Query("DELETE FROM claims WHERE id = :claimId")
    suspend fun deleteClaimById(claimId: Long)

    @Query("SELECT * FROM claims WHERE verdict IS NULL")
    suspend fun pendingClaims(): List<ClaimEntity>

    @Transaction
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun sessionsWithClaims(): Flow<List<SessionWithClaims>>

    @Query("SELECT * FROM claims WHERE id = :claimId")
    fun claimFlow(claimId: Long): Flow<ClaimEntity?>

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    /** Deletes one session; its claims cascade-delete via the foreign key. */
    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM claims")
    suspend fun deleteAllClaims()
}
