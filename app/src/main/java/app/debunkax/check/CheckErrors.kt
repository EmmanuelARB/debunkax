package app.debunkax.check

import app.debunkax.domain.CheckFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared HTTP-error-to-[CheckFailure] mapping for the check clients. */
internal object CheckErrors {

    /** Pulls the human-readable message out of an OpenRouter `{"error": {"message": ...}}` body. */
    fun extractMessage(body: String): String =
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: body.take(200)
        } catch (e: Exception) {
            body.take(200)
        }

    fun fromHttp(code: Int, message: String): CheckFailure {
        val lower = message.lowercase()
        val searchDisabled = lower.contains("web search") &&
            (
                lower.contains("disab") ||
                    lower.contains("not allow") ||
                    lower.contains("not enabled") ||
                    lower.contains("not support")
                )
        return when {
            searchDisabled -> CheckFailure.SearchDisabled()
            code == 401 || code == 403 -> CheckFailure.InvalidKey()
            code == 402 -> CheckFailure.NoCredit()
            code == 429 -> CheckFailure.RateLimited()
            else -> CheckFailure.Unexpected(message.ifBlank { "Erreur HTTP $code" })
        }
    }
}
