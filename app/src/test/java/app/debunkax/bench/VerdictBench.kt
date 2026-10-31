package app.debunkax.bench

import app.debunkax.check.Models
import app.debunkax.check.OpenRouterCheckClient
import app.debunkax.domain.CheckFailure
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckPrompts
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.Provider
import app.debunkax.domain.ProviderModel
import app.debunkax.domain.TrustedSources
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Compares every model in [Models] on Verdict accuracy, JSON validity, latency and cost against
 * the golden set in `spikes/verdicts/golden.json`.
 *
 * Skipped unless `OPENROUTER_API_KEY` is set (see [VerdictBench]), so a
 * normal `./gradlew testDebugUnitTest` never hits the network. See `spikes/verdicts/README.md` for
 * how to run it, including a Gradle-free path through [main].
 */
class VerdictBench {

    @Test
    fun `run the Verdict benchmark against every configured model`() {
        val openRouterKey = (System.getenv("OPENROUTER_API_KEY") ?: System.getProperty("debunkax.openRouterKey"))?.takeIf { it.isNotBlank() }
        assumeTrue("Paid API calls: run with -Pbench (or BENCH=1) to opt in.", System.getProperty("debunkax.bench") == "true" || System.getenv("BENCH") == "1")
        assumeTrue(
            "Set OPENROUTER_API_KEY (or the local.properties developer key) to run the Verdict benchmark.",
            openRouterKey != null,
        )
        runBlocking { VerdictBenchRunner.run(openRouterKey) }
    }
}

/** Gradle-free entry point: `OPENROUTER_API_KEY=... kotlin ...` or run from an IDE. See the README. */
fun main() {
    val openRouterKey = (System.getenv("OPENROUTER_API_KEY") ?: System.getProperty("debunkax.openRouterKey"))?.takeIf { it.isNotBlank() }
    if (openRouterKey == null) {
        println("Set OPENROUTER_API_KEY before running the Verdict benchmark.")
        return
    }
    runBlocking { VerdictBenchRunner.run(openRouterKey) }
}

@Serializable
data class GoldenEntry(
    val id: String,
    val claim: String,
    val claim_original: String? = null,
    val context: String? = null,
    val expected: String,
    val mapping_note: String? = null,
    val source_url: String? = null,
    val outlet: String? = null,
    val published: String? = null,
    val topic: String? = null,
    val political_side: String? = null,
)

/** One model x one golden entry. */
private data class BenchRow(
    val model: ProviderModel,
    val entry: GoldenEntry,
    val predicted: String,
    val status: String, // "ok", "api_error", "parse_failure", "other_error"
    val errorMessage: String?,
    val latencyMs: Long?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val searchRequests: Int?,
    val estimatedCostUsd: Double?,
    val sources: List<String>,
    val allSourcesTrusted: Boolean,
    val checkedText: String? = null,
    val verdictContext: String? = null,
    val servedBy: String = "",
)

/**
 * Candidates for the model comparison, picked from OpenRouter's most-popular leaderboard
 * (openrouter.ai/rankings): bench-only, not offered to users. All run through
 * the forced Exa engine like every model the app offers (ADR 0001).
 */
private val BENCH_CANDIDATES: List<ProviderModel> = listOf(
    ProviderModel(Provider.OPENROUTER, "z-ai/glm-5.3-flash", "GLM 5.3 Flash", "exa"),
    ProviderModel(Provider.OPENROUTER, "xiaomi/mimo-v2.6-flash", "MiMo V2.6 Flash", "exa"),
    ProviderModel(Provider.OPENROUTER, "deepseek/deepseek-v4-flash-0731", "DeepSeek V4 Flash 0731", "exa"),
    ProviderModel(Provider.OPENROUTER, "openai/gpt-6-luna", "GPT-6 Luna", "exa"),
)

/** Per-model x per-token-use price, checked against the OpenRouter pricing pages. */
private data class ModelPrice(val inputPerMTok: Double, val outputPerMTok: Double, val searchPerRequest: Double)

private object Prices {
    // DeepSeek V4.1 Flash: openrouter.ai/deepseek/deepseek-v4.1-flash: $0.099 input / $0.60 output
    // per MTok; the forced Exa engine costs $0.007 per request (openrouter.ai/docs/features/web-search).
    // Candidate prices from the live /api/v1/models endpoint.
    private const val EXA_SEARCH = 0.007

    val byModel: Map<ProviderModel, ModelPrice> = mapOf(
        Models.OPENROUTER_DEEPSEEK_V4_1_FLASH to ModelPrice(0.30, 1.20, EXA_SEARCH),
        BENCH_CANDIDATES[0] to ModelPrice(0.04, 0.50, EXA_SEARCH),
        BENCH_CANDIDATES[1] to ModelPrice(0.14, 0.28, EXA_SEARCH),
        BENCH_CANDIDATES[2] to ModelPrice(0.02, 0.32, EXA_SEARCH),
        BENCH_CANDIDATES[3] to ModelPrice(0.10, 0.50, EXA_SEARCH),
    )
}

object VerdictBenchRunner {

    private val json = Json { ignoreUnknownKeys = true }
    private const val DELAY_BETWEEN_CALLS_MS = 400L

    suspend fun run(openRouterKey: String?) {
        val goldenFile = resolveGoldenFile()
        // Every entry (Claims and non-Claims) is benched: a good model must also say "not_a_claim" correctly.
        val entries = json.decodeFromString<List<GoldenEntry>>(goldenFile.readText())
        val resultsDir = (goldenFile.absoluteFile.parentFile ?: error("golden file has no parent directory"))
            .resolve("results")
            .apply { mkdirs() }
        // -PbenchCache=<name> reuses the search results saved in results/<name>.json (see [SearchCache]).
        val cache = System.getProperty("debunkax.benchCache").orEmpty().takeIf { it.isNotBlank() }
            ?.let { SearchCache(resultsDir.resolve("$it.json")) }
        // -PbenchProvider=<slug> sends every OpenRouter request to that provider only: providers run different
        // quantizations, and routing changes between runs.
        val pinnedProvider = System.getProperty("debunkax.benchProvider").orEmpty().takeIf { it.isNotBlank() }
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())

        // -PbenchModels=deepseek/deepseek-v4.1-flash,… limits the run to those model ids.
        val only = System.getProperty("debunkax.benchModels").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val models = (Models.all + BENCH_CANDIDATES).filter {
            openRouterKey != null && (only.isEmpty() || it.id in only)
        }
        if (models.isEmpty()) {
            println("No model has a matching API key set; nothing to run.")
            return
        }

        println("Running the Verdict benchmark on ${entries.size} golden entries x ${models.size} models...")
        // -PbenchParallelism=N runs up to N models at once (their entries stay sequential). Models hit
        // different providers, so this only overlaps network wait; default 1 keeps the old behaviour.
        val parallelism = System.getProperty("debunkax.benchParallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val rows = mutableListOf<BenchRow>()
        coroutineScope {
            val gate = Semaphore(parallelism)
            models.map { model ->
                launch {
                    gate.withPermit {
                        println("== ${model.label} (${model.provider}) ==")
                        for ((index, entry) in entries.withIndex()) {
                            val row = runOne(model, entry, openRouterKey, cache, pinnedProvider)
                            rows += row
                            println(
                                "[${index + 1}/${entries.size}] ${model.id} ${entry.id}: expected=${entry.expected} " +
                                    "predicted=${row.predicted} status=${row.status} latency=${row.latencyMs}ms",
                            )
                            delay(DELAY_BETWEEN_CALLS_MS)
                        }
                    }
                }
            }
        }

        cache?.let { println("Search cache: ${it.hits} reused, ${it.misses} searched live") }
        val csvFile = resultsDir.resolve("$timestamp.csv")
        writeCsv(csvFile, rows)
        println("Wrote ${csvFile.path}")

        for (model in models) {
            val modelRows = rows.filter { it.model == model }
            val summaryFile = resultsDir.resolve("$timestamp-${slug(model)}.md")
            summaryFile.writeText(renderSummary(model, modelRows))
            println("Wrote ${summaryFile.path}")
        }
    }

    private suspend fun runOne(
        model: ProviderModel,
        entry: GoldenEntry,
        openRouterKey: String?,
        cache: SearchCache?,
        pinnedProvider: String?,
    ): BenchRow {
        // An OpenRouter Check makes two requests (search-free pre-check, then the searched Check): keep every usage.
        val usages = mutableListOf<JsonObject?>()
        val providers = mutableListOf<String>()
        val http = OkHttpClient.Builder()
            .apply { if (cache != null) addInterceptor(cache.interceptor(entry.id)) }
            .apply { if (pinnedProvider != null) addInterceptor(pinProvider(pinnedProvider)) }
            .addInterceptor(UsageCapturingInterceptor(onProvider = { providers += it }) { usages += it })
            .build()

        val client = OpenRouterCheckClient(
            apiKey = openRouterKey!!,
            model = model,
            http = http,
            // Bench-only: some endpoints (GLM 5.3) refuse reasoning:enabled=false but accept an effort level.
            reasoningEffort = System.getProperty("debunkax.benchReasoningEffort")?.takeIf { it.isNotEmpty() },
        )

        val claim = HeardClaim(
            text = entry.claim,
            heardAt = Instant.now(),
            surroundingTranscript = entry.context.orEmpty(),
        )

        val start = System.nanoTime()
        return try {
            val outcome = client.check(claim)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val usage = usages.map(::parseUsage).reduceOrNull(Usage::plus) ?: Usage(null, null, null)
            // OpenRouter reports what each request actually cost, search included; prefer it over the estimate.
            val reported = usages.map { it?.get("cost")?.jsonPrimitive?.doubleOrNull }
            val cost = if (reported.isNotEmpty() && reported.all { it != null }) {
                reported.sumOf { it!! }
            } else {
                estimateCost(usage, Prices.byModel[model])
            }

            val predicted: String
            val sources: List<String>
            val result = (outcome as? CheckOutcome.Checked)?.result
            when (outcome) {
                is CheckOutcome.NotAClaim -> {
                    predicted = "not_a_claim"
                    sources = emptyList()
                }
                is CheckOutcome.Checked -> {
                    predicted = CheckPrompts.wireName(outcome.result.verdict)
                    sources = outcome.result.sources.map { it.url }
                }
            }
            BenchRow(
                model = model,
                entry = entry,
                predicted = predicted,
                status = "ok",
                errorMessage = null,
                latencyMs = latencyMs,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                searchRequests = usage.searchRequests,
                estimatedCostUsd = cost,
                sources = sources,
                allSourcesTrusted = sources.all { isTrustedUrl(it) },
                checkedText = result?.checkedText,
                verdictContext = result?.context,
                servedBy = providers.distinct().joinToString(";"),
            )
        } catch (e: CheckFailure) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val message = e.message.orEmpty()
            val status = if (e is CheckFailure.Unexpected && looksLikeParseFailure(message)) "parse_failure" else "api_error"
            BenchRow(
                model = model,
                entry = entry,
                predicted = "ERROR",
                status = status,
                errorMessage = message,
                latencyMs = latencyMs,
                inputTokens = null,
                outputTokens = null,
                searchRequests = null,
                estimatedCostUsd = null,
                sources = emptyList(),
                allSourcesTrusted = true,
            )
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            BenchRow(
                model = model,
                entry = entry,
                predicted = "ERROR",
                status = "other_error",
                errorMessage = e.message ?: e.toString(),
                latencyMs = latencyMs,
                inputTokens = null,
                outputTokens = null,
                searchRequests = null,
                estimatedCostUsd = null,
                sources = emptyList(),
                allSourcesTrusted = true,
            )
        }
    }

    private fun looksLikeParseFailure(message: String): Boolean =
        message.contains("JSON", ignoreCase = true) || message.contains("Verdict inconnu", ignoreCase = true)

    // --- usage / cost -----------------------------------------------------------------------

    private data class Usage(val inputTokens: Int?, val outputTokens: Int?, val searchRequests: Int?) {
        operator fun plus(other: Usage) = Usage(
            sum(inputTokens, other.inputTokens),
            sum(outputTokens, other.outputTokens),
            sum(searchRequests, other.searchRequests),
        )

        private fun sum(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
    }

    /** Reads OpenRouter's OpenAI-compatible usage shape (`prompt_tokens`/`completion_tokens`). Web search
     * counts are not reported by the Exa engine, so [Usage.searchRequests] is always null here and the cost
     * estimate falls back to assuming one search per successful Check (documented in the summary). */
    private fun parseUsage(usage: JsonObject?): Usage {
        if (usage == null) return Usage(null, null, null)
        val input = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
        val output = usage["completion_tokens"]?.jsonPrimitive?.intOrNull
        return Usage(input, output, null)
    }

    private fun estimateCost(usage: Usage, price: ModelPrice?): Double? {
        if (price == null) return null
        val input = usage.inputTokens ?: return null
        val output = usage.outputTokens ?: return null
        val searches = usage.searchRequests ?: 1 // best-effort fallback, see [parseUsage]
        return (input / 1_000_000.0) * price.inputPerMTok +
            (output / 1_000_000.0) * price.outputPerMTok +
            searches * price.searchPerRequest
    }

    /** Adds `order` and `allow_fallbacks: false` to the request's provider block, keeping its ZDR flag. */
    private fun pinProvider(slug: String) = Interceptor { chain ->
        val request = chain.request()
        val text = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
        val payload = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return@Interceptor chain.proceed(request)
        val provider = payload["provider"]?.jsonObject ?: JsonObject(emptyMap())
        val pinned = JsonObject(
            provider + ("order" to JsonArray(listOf(JsonPrimitive(slug)))) + ("allow_fallbacks" to JsonPrimitive(false)),
        )
        val body = JsonObject(payload + ("provider" to pinned)).toString()
        val pinnedRequest = request.newBuilder().post(body.toRequestBody("application/json".toMediaType())).build()
        // A single provider rate-limits a bench run; wait and retry rather than record an error.
        var response = chain.proceed(pinnedRequest)
        var attempt = 0
        while (response.code == 429 && attempt < 6) {
            response.close()
            Thread.sleep(5_000L * (attempt + 1))
            attempt++
            response = chain.proceed(pinnedRequest)
        }
        response
    }

    /** [Response.peekBody] reads the body without consuming the stream the real client still needs. */
    private class UsageCapturingInterceptor(
        private val onProvider: (String) -> Unit = {},
        private val onUsage: (JsonObject?) -> Unit,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val usage = try {
                val text = response.peekBody(2_000_000).string()
                val body = Json.parseToJsonElement(text).jsonObject
                body["provider"]?.jsonPrimitive?.contentOrNull?.let(onProvider)
                body["usage"]?.jsonObject
            } catch (e: Exception) {
                null
            }
            onUsage(usage)
            return response
        }
    }

    // --- trusted-source check -----------------------------------------------------------------

    /** Subdomains count, as Exa's include_domains matches them, except where a Trusted Source excludes them. */
    private fun isTrustedUrl(url: String): Boolean {
        val normalized = url.substringAfter("://").lowercase()
        val host = normalized.substringBefore('/').substringBefore('?').substringBefore(':')
        val path = normalized.removePrefix(host)
        return TrustedSources.all.any { source ->
            val d = source.domain.lowercase()
            val dHost = d.substringBefore('/')
            val dPath = d.removePrefix(dHost)
            val hostOk = host == dHost || host == "www.$dHost" || (source.subdomains && host.endsWith(".$dHost"))
            hostOk && (dPath.isEmpty() || path == dPath || path.startsWith("$dPath/"))
        }
    }

    // --- output ---------------------------------------------------------------------------------

    private fun slug(model: ProviderModel): String =
        "${model.provider.name.lowercase()}-${model.id}".replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-')

    private fun writeCsv(file: File, rows: List<BenchRow>) {
        val header = listOf(
            "model", "provider", "entry_id", "topic", "political_side", "expected", "predicted",
            "exact_match", "alert_match", "status", "error_message", "latency_ms", "input_tokens",
            "output_tokens", "search_requests", "estimated_cost_usd", "sources", "all_sources_trusted",
            "checked_text", "verdict_context", "served_by",
        )
        val lines = mutableListOf(header.joinToString(",") { csvEscape(it) })
        for (row in rows) {
            val exactMatch = row.predicted == row.entry.expected
            val alertMatch = toAlertClass(row.predicted) == toAlertClass(row.entry.expected)
            lines += listOf(
                row.model.label, row.model.provider.name, row.entry.id, row.entry.topic.orEmpty(),
                row.entry.political_side.orEmpty(), row.entry.expected, row.predicted,
                exactMatch.toString(), alertMatch.toString(), row.status, row.errorMessage.orEmpty(),
                row.latencyMs?.toString().orEmpty(), row.inputTokens?.toString().orEmpty(),
                row.outputTokens?.toString().orEmpty(), row.searchRequests?.toString().orEmpty(),
                row.estimatedCostUsd?.toString().orEmpty(), row.sources.joinToString(";"),
                row.allSourcesTrusted.toString(), row.checkedText.orEmpty(), row.verdictContext.orEmpty(), row.servedBy,
            ).joinToString(",") { csvEscape(it) }
        }
        file.writeText(lines.joinToString("\n"))
    }

    private fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun toAlertClass(label: String): String = when (label) {
        "false", "misleading", "lacks_context" -> "alert"
        else -> label
    }

    private fun renderSummary(model: ProviderModel, rows: List<BenchRow>): String {
        val total = rows.size
        val ok = rows.filter { it.status == "ok" }
        val exactMatches = ok.count { it.predicted == it.entry.expected }
        val alertMatches = ok.count { toAlertClass(it.predicted) == toAlertClass(it.entry.expected) }
        val notAClaimPredicted = ok.filter { it.predicted == "not_a_claim" }
        val notAClaimPrecision = if (notAClaimPredicted.isEmpty()) {
            null
        } else {
            notAClaimPredicted.count { it.entry.expected == "not_a_claim" }.toDouble() / notAClaimPredicted.size
        }
        val parseFailures = rows.count { it.status == "parse_failure" }
        val citationsRows = ok.filter { it.sources.isNotEmpty() }
        val outOfAllowlist = citationsRows.count { !it.allSourcesTrusted }
        val latencies = ok.mapNotNull { it.latencyMs }.sorted()
        val costs = ok.mapNotNull { it.estimatedCostUsd }
        val costPer100 = if (costs.isNotEmpty()) (costs.average() * 100) else null

        fun pct(n: Int, d: Int): String = if (d == 0) "n/a" else "%.1f%%".format(100.0 * n / d)
        fun percentile(sorted: List<Long>, p: Double): Long? {
            if (sorted.isEmpty()) return null
            val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        return buildString {
            appendLine("# Verdict bench – ${model.label} (${model.provider})")
            appendLine()
            appendLine("- Golden entries run: $total (successful: ${ok.size}, errors: ${total - ok.size})")
            appendLine("- Exact-match accuracy: ${pct(exactMatches, ok.size)} (${exactMatches}/${ok.size})")
            appendLine(
                "- Accuracy with false/misleading/lacks_context merged into \"alert\": " +
                    "${pct(alertMatches, ok.size)} (${alertMatches}/${ok.size})",
            )
            appendLine(
                "- Not-a-claim precision: " +
                    (notAClaimPrecision?.let { "%.1f%%".format(it * 100) } ?: "n/a (model never predicted not_a_claim)") +
                    " (${notAClaimPredicted.count { it.entry.expected == "not_a_claim" }}/${notAClaimPredicted.size} predictions correct)",
            )
            appendLine("- JSON/parse failure rate: ${pct(parseFailures, total)} ($parseFailures/$total)")
            appendLine(
                "- Out-of-allowlist citation rate: ${pct(outOfAllowlist, citationsRows.size)} " +
                    "($outOfAllowlist/${citationsRows.size} answers with sources cited at least one non-Trusted-Source domain)",
            )
            appendLine("- Median latency: ${percentile(latencies, 0.5) ?: "n/a"} ms")
            appendLine("- P90 latency: ${percentile(latencies, 0.9) ?: "n/a"} ms")
            appendLine(
                "- Estimated cost per 100 Checks: " +
                    (costPer100?.let { "\$%.2f".format(it) } ?: "n/a (no token usage reported)"),
            )
            appendLine()
            appendLine("Errors by status: " + rows.groupingBy { it.status }.eachCount().entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
    }
}

private fun resolveGoldenFile(): File {
    val override = System.getProperty("BENCH_GOLDEN") ?: System.getenv("BENCH_GOLDEN")
    if (override != null) return File(override)
    val candidates = listOf(
        File("spikes/verdicts/golden.json"),
        File("../spikes/verdicts/golden.json"),
        File("../../spikes/verdicts/golden.json"),
    )
    return candidates.firstOrNull { it.exists() }
        ?: error(
            "Could not find spikes/verdicts/golden.json from working directory ${File(".").absolutePath}; " +
                "set BENCH_GOLDEN to an explicit path.",
        )
}
