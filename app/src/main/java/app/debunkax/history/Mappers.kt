package app.debunkax.history

import app.debunkax.domain.CheckResult
import app.debunkax.domain.ClaimRecord
import app.debunkax.domain.Explanation
import app.debunkax.domain.Source
import app.debunkax.domain.Verdict
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * All mapping between the Room schema (app.debunkax.history) and the domain types
 * (app.debunkax.domain.History / Claims) lives in this file.
 */

@Serializable
private data class SourceJson(val title: String, val url: String)

private val sourceListSerializer = ListSerializer(SourceJson.serializer())

internal fun List<Source>.toJsonOrNull(): String? =
    if (isEmpty()) null else Json.encodeToString(sourceListSerializer, map { SourceJson(it.title, it.url) })

internal fun String?.toSources(): List<Source> =
    if (isNullOrEmpty()) {
        emptyList()
    } else {
        Json.decodeFromString(sourceListSerializer, this).map { Source(it.title, it.url) }
    }

internal fun ClaimEntity.toDomain(): ClaimRecord =
    ClaimRecord(
        id = id,
        sessionId = sessionId,
        text = text,
        heardAt = Instant.ofEpochMilli(heardAt),
        result = verdict?.let { name ->
            CheckResult(
                verdict = Verdict.valueOf(name),
                context = context.orEmpty(),
                sources = sources.toSources(),
                checkedText = checkedText,
            )
        },
        explanation = explanationText?.let { text ->
            Explanation(text = text, sources = explanationSources.toSources())
        },
        speaker = speaker,
    )

internal fun newClaimEntity(sessionId: Long, text: String, heardAt: Instant, result: CheckResult?, speaker: String? = null): ClaimEntity =
    ClaimEntity(
        sessionId = sessionId,
        text = text,
        heardAt = heardAt.toEpochMilli(),
        verdict = result?.verdict?.name,
        context = result?.context,
        sources = result?.sources?.toJsonOrNull(),
        explanationText = null,
        explanationSources = null,
        checkedText = result?.checkedText,
        speaker = speaker,
    )
