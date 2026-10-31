package app.debunkax.check

import app.debunkax.domain.CheckFailure
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckPrompts
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.Provider
import app.debunkax.domain.ProviderModel
import app.debunkax.domain.TrustedSources
import app.debunkax.domain.Verdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.builtins.serializer
import app.debunkax.domain.Spend

class OpenRouterCheckClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val model = Models.OPENROUTER_DEEPSEEK_V4_1_FLASH

    private fun client(model: ProviderModel = this.model) = OpenRouterCheckClient(
        apiKey = "sk-or-test-key",
        model = model,
        http = OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        today = { LocalDate.of(2026, 9, 23) },
    )

    private val claim = HeardClaim(
        text = "Le chômage a baissé de 10% cette année.",
        heardAt = Instant.parse("2000-01-01T10:00:00Z"),
        surroundingTranscript = "Et sur l'emploi, on voit que...",
    )

    private fun successBody(assistantContent: String): MockResponse {
        val payload = buildJsonObject {
            put("id", "gen-01")
            putJsonArray("choices") {
                addJsonObject {
                    put("finish_reason", "stop")
                    put(
                        "message",
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", assistantContent)
                        },
                    )
                }
            }
        }
        return MockResponse().setResponseCode(200).setBody(payload.toString())
    }

    private val rewritten = "Le chômage a baissé de 10 % en France cette année."

    /** The search-free first pass that every Check starts with. */
    private fun enqueueTriage(isClaim: Boolean = true) =
        server.enqueue(successBody(if (isClaim) """{"is_claim": true, "claim": "$rewritten"}""" else """{"is_claim": false}"""))

    /** Skips the pre-check request and returns the JSON body of the searched Check request. */
    private fun searchedCheckBody(): JsonObject {
        server.takeRequest()
        return Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    @Test
    fun `parses a successful checked verdict`() = runTest {
        enqueueTriage()
        server.enqueue(
            successBody(
                """{"is_claim": true, "verdict": "misleading", "evidence": "Hausse limitée à un secteur.", "context": "Techniquement vrai mais trompeur.", "sources": [{"title": "AFP Factuel", "url": "https://factuel.afp.com/x"}]}""",
            ),
        )

        val outcome = client().check(claim)

        require(outcome is CheckOutcome.Checked)
        assertEquals(Verdict.MISLEADING, outcome.result.verdict)
        assertEquals("Techniquement vrai mais trompeur.", outcome.result.context)
        assertEquals("https://factuel.afp.com/x", outcome.result.sources[0].url)
        assertEquals(rewritten, outcome.result.checkedText)
    }

    @Test
    fun `check request carries auth header, model, include_domains, forced engine, zdr and max_results 2`() = runTest {
        enqueueTriage()
        server.enqueue(successBody("""{"is_claim": false}"""))

        client().check(claim)

        server.takeRequest()
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-or-test-key", recorded.getHeader("Authorization"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(model.id, body.getValue("model").jsonPrimitive.content)

        val plugin = body.getValue("plugins").jsonArray[0].jsonObject
        assertEquals("web", plugin.getValue("id").jsonPrimitive.content)
        assertEquals("exa", plugin.getValue("engine").jsonPrimitive.content)
        assertEquals(2, plugin.getValue("max_results").jsonPrimitive.int)
        val includeDomains = plugin.getValue("include_domains").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(TrustedSources.domains, includeDomains)
        val excludeDomains = plugin.getValue("exclude_domains").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("*.assemblee-nationale.fr", "*.senat.fr"), excludeDomains)

        val provider = body.getValue("provider").jsonObject
        assertEquals(true, provider.getValue("zdr").jsonPrimitive.boolean)
        assertEquals(false, body.getValue("reasoning").jsonObject.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test
    fun `a reply that is not the expected JSON is a CheckFailure, not a crash`() = runTest {
        server.enqueue(successBody("Désolé, je ne peux pas répondre."))
        try {
            client().check(claim)
            org.junit.Assert.fail("expected a CheckFailure")
        } catch (e: CheckFailure.Unexpected) {
            // expected
        }

        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway</html>"))
        try {
            client().check(claim)
            org.junit.Assert.fail("expected a CheckFailure")
        } catch (e: CheckFailure.Unexpected) {
            // expected
        }
    }

    @Test
    fun `a sentence the pre-check rejects costs one search-free request and is not a Claim`() = runTest {
        enqueueTriage(isClaim = false)

        val outcome = client().check(claim)

        assertEquals(CheckOutcome.NotAClaim, outcome)
        assertEquals(1, server.requestCount)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(!body.containsKey("plugins"))
        assertEquals(true, body.getValue("provider").jsonObject.getValue("zdr").jsonPrimitive.boolean)
    }

    @Test
    fun `explain request uses max_results 4`() = runTest {
        server.enqueue(successBody("""{"text": "Explication détaillée.", "sources": []}"""))

        client().explain(
            "Le chômage a baissé de 10%.",
            app.debunkax.domain.CheckResult(Verdict.FALSE, "ctx", emptyList()),
        )

        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val plugin = body.getValue("plugins").jsonArray[0].jsonObject
        assertEquals(4, plugin.getValue("max_results").jsonPrimitive.int)
    }

    @Test
    fun `every OpenRouter model forces the Exa engine restricted to Trusted Sources`() = runTest {
        for (m in Models.all.filter { it.provider == Provider.OPENROUTER }) {
            enqueueTriage()
            server.enqueue(successBody("""{"is_claim": false}"""))

            client(m).check(claim)

            val body = searchedCheckBody()
            assertEquals(m.id, body.getValue("model").jsonPrimitive.content)
            val plugin = body.getValue("plugins").jsonArray[0].jsonObject
            assertEquals("exa", plugin.getValue("engine").jsonPrimitive.content)
            assertEquals(TrustedSources.domains, plugin.getValue("include_domains").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(true, body.getValue("provider").jsonObject.getValue("zdr").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `only the rewritten Claim is in the last user message, which OpenRouter turns into the Exa query`() = runTest {
        enqueueTriage()
        server.enqueue(successBody("""{"is_claim": false}"""))

        client().check(claim)

        val messages = searchedCheckBody()
            .getValue("messages").jsonArray.map { it.jsonObject }
        val system = messages.first { it.getValue("role").jsonPrimitive.content == "system" }.getValue("content").jsonPrimitive.content
        val lastUser = messages.last { it.getValue("role").jsonPrimitive.content == "user" }.getValue("content").jsonPrimitive.content
        assertEquals(CheckPrompts.claimLine(rewritten), lastUser)
        assertTrue(system.contains(claim.text))
        assertTrue(system.contains(claim.surroundingTranscript))
    }

    @Test
    fun `does not force an engine when the model has none`() = runTest {
        val noEngineModel = model.copy(openRouterEngine = null)
        enqueueTriage()
        server.enqueue(successBody("""{"is_claim": false}"""))

        client(noEngineModel).check(claim)

        val plugin = searchedCheckBody()
            .getValue("plugins").jsonArray[0].jsonObject
        assertTrue(!plugin.containsKey("engine"))
    }

    @Test
    fun `maps 401 to InvalidKey`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": {"message": "No auth credentials found"}}"""))
        try {
            client().check(claim)
            assertTrue("expected CheckFailure", false)
        } catch (e: CheckFailure.InvalidKey) {
            // expected
        }
    }

    @Test
    fun `maps 402 to NoCredit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error": {"message": "Insufficient credits"}}"""))
        try {
            client().check(claim)
            assertTrue("expected CheckFailure", false)
        } catch (e: CheckFailure.NoCredit) {
            // expected
        }
    }

    @Test
    fun `maps 429 to RateLimited`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error": {"message": "Rate limit exceeded"}}"""))
        try {
            client().check(claim)
            assertTrue("expected CheckFailure", false)
        } catch (e: CheckFailure.RateLimited) {
            // expected
        }
    }

    @Test
    fun `maps a web search disabled message to SearchDisabled`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error": {"message": "Web search is disabled for this account"}}""",
            ),
        )
        try {
            client().check(claim)
            assertTrue("expected CheckFailure", false)
        } catch (e: CheckFailure.SearchDisabled) {
            // expected
        }
    }

    @Test
    fun `maps other errors to Unexpected`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": {"message": "internal error"}}"""))
        try {
            client().check(claim)
            assertTrue("expected CheckFailure", false)
        } catch (e: CheckFailure.Unexpected) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test
    fun `maps a network failure to Offline`() = runTest {
        server.shutdown()
        try {
            client().check(claim)
            assertTrue("expected CheckFailure", false)
        } catch (e: CheckFailure.Offline) {
            // expected
        }
    }

    @Test
    fun `adds up the cost OpenRouter reports for each request`() = runTest {
        fun costing(usd: Double, content: String) = MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), content)}}}],"usage":{"cost":$usd}}""",
        )
        server.enqueue(costing(0.0002, """{"is_claim": true, "claim": "$rewritten"}"""))
        server.enqueue(costing(0.0081, """{"is_claim": false}"""))
        val client = client()

        client.check(claim)

        assertEquals(Spend(0.0083, estimated = false).usd, client.spent.usd, 1e-9)
        assertFalse(client.spent.estimated)
    }
}
