package app.debunkax.history

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Room schema for the History. Only Claim text, Verdict data (verdict, context, sources,
 * explanation) and timestamps are stored here. Never add a transcript or audio column.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
)

@Entity(
    tableName = "claims",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ClaimEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val heardAt: Long,
    /** Verdict enum name, null while the Claim is a Pending Claim. */
    val verdict: String?,
    val context: String?,
    /** JSON-encoded List<Source>, null when there is none. */
    val sources: String?,
    val explanationText: String?,
    /** JSON-encoded List<Source> for the Explanation, null when there is none. */
    val explanationSources: String?,
    /** The Claim as it was checked, when rewritten from [text]; see CheckResult.checkedText. Added in version 2. */
    val checkedText: String? = null,
    /** The Speaker recognised by voice, null when unknown (ADR 0003). Added in version 3. */
    val speaker: String? = null,
)

data class SessionWithClaims(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val claims: List<ClaimEntity>,
)
