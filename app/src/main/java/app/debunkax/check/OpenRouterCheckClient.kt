package app.debunkax.check

import app.debunkax.domain.CheckClient
import app.debunkax.domain.CheckFailure
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckPrompts
import app.debunkax.domain.CheckResult
import app.debunkax.domain.Explanation
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.ProviderModel
import app.debunkax.domain.Spend
import app.debunkax.domain.TrustedSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * [CheckClient] backed by OpenRouter's chat completions API, using the `web` plugin restricted to
 * Trusted Sources and per-request zero data retention (ADR 0001). Every model uses the Exa engine: native
 * search runs only on the model makers' own endpoints, and none of those accepts zero data retention.
 */
class OpenRouterCheckClient(
    private val apiKey: String,
    private val model: ProviderModel,
    http: OkHttpClient,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val today: () -> LocalDate = { LocalDate.now() },
    /**
     * For endpoints that refuse to disable reasoning but accept an effort level (e.g. GLM 5.3):
     * when set, sends `reasoning: {effort = ...}` instead of `reasoning: {enabled = false}`.
     */
    private val reasoningEffort: String? = null,
) : CheckClient {

    private val http = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val counter = SpendCounter(estimated = false)

    override val spent: Spend get() = counter.spend

    override suspend fun check(claim: HeardClaim): CheckOutcome {
        // Most transcript sentences aren't Claims, and the Exa search costs far more than the model, so a
        // search-free pass sorts them first. It also rewrites the Claim into a clear sentence.
        val rewritten = CheckPrompts.parseTriage(
            send(system = CheckPrompts.triageSystem, user = CheckPrompts.triageUser(claim), maxResults = null),
        ) ?: return CheckOutcome.NotAClaim

        // OpenRouter builds the Exa search query from the last user message, so the transcript goes in the
        // system message: only the rewritten Claim reaches Exa, and the search stays on the Claim's topic.
        val text = send(
            system = CheckPrompts.checkSystem(today()) + "\n\n" + CheckPrompts.checkSurroundings(claim),
            user = CheckPrompts.claimLine(rewritten),
            maxResults = 2,
        )
        return when (val outcome = CheckPrompts.parseCheck(text)) {
            is CheckOutcome.Checked -> CheckOutcome.Checked(outcome.result.copy(checkedText = rewritten))
            CheckOutcome.NotAClaim -> outcome
        }
    }

    override suspend fun explain(claimText: String, result: CheckResult): Explanation {
        val text = send(
            system = CheckPrompts.explainSystem(today()),
            user = CheckPrompts.explainUser(claimText, result),
            maxResults = 4,
        )
        return CheckPrompts.parseExplanation(text)
    }

    /** Searches Trusted Sources when [maxResults] is set; without it, the request makes no web search. */
    private suspend fun send(system: String, user: String, maxResults: Int?): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model.id)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            if (maxResults != null) {
                putJsonArray("plugins") {
                    addJsonObject {
                        put("id", "web")
                        model.openRouterEngine?.let { put("engine", it) }
                        put("max_results", maxResults)
                        putJsonArray("include_domains") {
                            TrustedSources.domains.forEach { add(it) }
                        }
                        putJsonArray("exclude_domains") {
                            TrustedSources.excludedSubdomains.forEach { add(it) }
                        }
                    }
                }
            }
            // Extended reasoning made DeepSeek write up to 3,600 output tokens for one Check; with it off, answers on live
            // samples were as good, faster and far cheaper. A few endpoints force reasoning on; for those the
            // lowest effort level is the only option.
            put("reasoning", buildJsonObject {
                if (reasoningEffort != null) put("effort", reasoningEffort) else put("enabled", false)
            })
            // The same Claim with the same sources should get the same Verdict; at the default temperature, about 10 of
            // 46 bench Verdicts changed between two replays of identical search results.
            put("temperature", 0)
            put(
                "provider",
                buildJsonObject {
                    // Per-request zero data retention; see openrouter.ai/docs/guides/features/zdr.
                    put("zdr", true)
                },
            )
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw CheckFailure.Offline()
        }

        response.use {
            val bodyText = it.body.string()
            if (!it.isSuccessful) {
                throw CheckErrors.fromHttp(it.code, CheckErrors.extractMessage(bodyText))
            }
            extractAssistantText(bodyText)
        }
    }

    private fun extractAssistantText(bodyText: String): String {
        val root = Json.parseToJsonElement(bodyText).jsonObject
        // OpenRouter reports what each request cost, search included.
        root["usage"]?.jsonObject?.get("cost")?.jsonPrimitive?.doubleOrNull?.let(counter::add)
        val choices = root["choices"]?.jsonArray ?: return ""
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return ""
        return message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
