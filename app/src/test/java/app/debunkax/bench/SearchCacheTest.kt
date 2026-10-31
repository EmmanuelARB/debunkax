package app.debunkax.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private val searched = """
        {"model":"m","messages":[{"role":"system","content":"Consignes"},{"role":"user","content":"Affirmation"}],
         "plugins":[{"id":"web","engine":"exa","max_results":2}]}
    """.trimIndent()

    private val response = """
        {"choices":[{"message":{"role":"assistant","content":"{}","annotations":[
          {"type":"url_citation","url_citation":{"url":"https://www.insee.fr/a","title":"Insee A","content":"Texte de la page"}}
        ]}}]}
    """.trimIndent()

    private fun post(cache: SearchCache, body: String) {
        val http = OkHttpClient.Builder().addInterceptor(cache.interceptor("entree-1")).build()
        http.newCall(
            Request.Builder().url(server.url("/")).post(body.toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()
    }

    @Test
    fun `saves the pages of a live search, then replays them without the search plugin`() {
        val file = tmp.root.resolve("cache.json")
        server.enqueue(MockResponse().setBody(response))
        post(SearchCache(file), searched)
        assertTrue("plugins" in server.takeRequest().body.readUtf8())
        assertTrue(file.readText().contains("https://www.insee.fr/a"))

        val reloaded = SearchCache(file)
        server.enqueue(MockResponse().setBody(response))
        post(reloaded, searched)
        val replayed = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("plugins" in replayed)
        val system = replayed.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(system.startsWith("Consignes"))
        assertTrue(system.contains("https://www.insee.fr/a") && system.contains("Texte de la page"))
        assertEquals(1, reloaded.hits)
    }

    @Test
    fun `leaves requests without a search untouched`() {
        val file = tmp.root.resolve("cache.json")
        server.enqueue(MockResponse().setBody(response))
        val plain = """{"model":"m","messages":[{"role":"user","content":"x"}]}"""
        post(SearchCache(file), plain)
        assertEquals(plain, server.takeRequest().body.readUtf8())
        assertFalse(file.exists())
    }
}
