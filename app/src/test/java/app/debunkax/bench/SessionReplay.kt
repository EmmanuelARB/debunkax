package app.debunkax.bench

import app.debunkax.check.Models
import app.debunkax.check.OpenRouterCheckClient
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckPrompts
import app.debunkax.domain.HeardClaim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
private data class RecordedSession(val source: String, val claims: List<RecordedClaim>)

@Serializable
private data class RecordedClaim(
    val heard: String,
    val checked: String? = null,
    val verdict: String,
    val context: String? = null,
    val alert_judgment: String? = null,
)

/**
 * Re-checks the Claims that raised an Alert in the recorded real sessions (`spikes/verdicts/sessions/`), with
 * DeepSeek V4.1 Flash and live search, and prints what the model answered and what the app now keeps. The
 * surrounding transcript was never saved, so each Claim is checked on its checked text alone.
 *
 * These sessions are a held-out set: use this to judge a change made for a general reason, never to tune on them.
 * Paid: `./gradlew testDebugUnitTest --tests '*SessionReplay*' -Pbench` (about $0.01 per Claim).
 */
class SessionReplay {
    private val json = Json { ignoreUnknownKeys = true }
    private val alertVerdicts = setOf("false", "misleading", "lacks_context")

    @Test
    fun `re-check the Alerts of recorded real sessions`() {
        val key = System.getProperty("debunkax.openRouterKey")?.takeIf { it.isNotBlank() }
        assumeTrue("Paid API calls: run with -Pbench to opt in.", System.getProperty("debunkax.bench") == "true")
        assumeTrue("Needs the OpenRouter developer key in local.properties.", key != null)
        val found = listOf(File("spikes/verdicts/sessions"), File("../spikes/verdicts/sessions")).firstOrNull { it.exists() }
        assumeTrue("No recorded sessions: spikes/verdicts/sessions/ is kept locally, not in git.", found != null)
        val dir = found!!

        val lines = mutableListOf("session\told\tjudgment\tmodel_said\tevidence\tkept\tclaim\tcontext")
        for (file in dir.listFiles { f -> f.extension == "json" }!!.sorted()) {
            val session = json.decodeFromString<RecordedSession>(file.readText())
            for (claim in session.claims.filter { it.verdict in alertVerdicts }) {
                var raw = ""
                val http = OkHttpClient.Builder().addInterceptor(lastReply { raw = it }).build()
                val client = OpenRouterCheckClient(key!!, Models.OPENROUTER_DEEPSEEK_V4_1_FLASH, http)
                val text = claim.checked ?: claim.heard
                val outcome = runCatching { runBlocking { client.check(HeardClaim(text, Instant.now(), "")) } }
                val reply = runCatching { json.parseToJsonElement(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).jsonObject }.getOrNull()
                val kept = outcome.fold(
                    { if (it is CheckOutcome.Checked) CheckPrompts.wireName(it.result.verdict) else "not_a_claim" },
                    { "error: ${it.message}" },
                )
                val context = (outcome.getOrNull() as? CheckOutcome.Checked)?.result?.context.orEmpty()
                val row = listOf(
                    file.nameWithoutExtension, claim.verdict, claim.alert_judgment ?: "",
                    reply?.get("verdict")?.jsonPrimitive?.contentOrNull ?: "", reply?.get("evidence")?.jsonPrimitive?.contentOrNull ?: "",
                    kept, text, context,
                ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
                println(row)
                lines += row
            }
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        dir.resolveSibling("results").resolve("sessions-$stamp.tsv").writeText(lines.joinToString("\n") + "\n")
    }

    /** Hands over the assistant text of each reply, so the last one is the searched Check's raw answer. */
    private fun lastReply(onText: (String) -> Unit) = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        runCatching {
            val body = json.parseToJsonElement(response.peekBody(4_000_000).string()).jsonObject
            body["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull()?.let(onText)
        response
    }
}
