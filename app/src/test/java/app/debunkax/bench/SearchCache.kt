package app.debunkax.bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File

@Serializable
data class CachedPage(val url: String, val title: String = "", val content: String = "")

/**
 * Search results saved per golden entry, so prompt changes are compared on the same pages. Exa returns different
 * pages from run to run, which moved false Alerts from 3/20 to 6/20 between two nearly identical prompts
 * (spikes/verdicts/RESULTS.md), and the search is most of a Check's cost.
 */
class SearchCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val pages: MutableMap<String, List<CachedPage>> =
        if (file.exists()) json.decodeFromString<Map<String, List<CachedPage>>>(file.readText()).toMutableMap() else mutableMapOf()

    var hits = 0
        private set
    var misses = 0
        private set

    fun interceptor(entryId: String): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
        val payload = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (payload == null || "plugins" !in payload) return@Interceptor chain.proceed(request)

        val cached = synchronized(this) { pages[entryId] }
        if (cached != null) {
            hits++
            val replayed = withResults(payload, cached)
            return@Interceptor chain.proceed(
                request.newBuilder().post(replayed.toString().toRequestBody("application/json".toMediaType())).build(),
            )
        }

        misses++
        val response = chain.proceed(request)
        val found = runCatching { annotations(response.peekBody(4_000_000).string()) }.getOrNull()
        if (response.isSuccessful && found != null) {
            synchronized(this) {
                pages[entryId] = found
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString<Map<String, List<CachedPage>>>(pages.toSortedMap()))
            }
        }
        response
    }

    private fun annotations(text: String): List<CachedPage> {
        val message = Json.parseToJsonElement(text).jsonObject.getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
        val list = message["annotations"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { it.jsonObject["url_citation"]?.jsonObject }.map { c ->
            CachedPage(
                url = c["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = c["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                content = c["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /** Drops the search plugin and hands the model the saved pages, much as OpenRouter's web plugin does. */
    private fun withResults(payload: JsonObject, found: List<CachedPage>): JsonObject {
        val results = buildString {
            append("A web search was conducted. Incorporate the following web search results into your response.\n")
            found.forEachIndexed { i, page ->
                append("\n[${i + 1}] ${page.title}\nURL: ${page.url}\n${page.content}\n")
            }
            if (found.isEmpty()) append("\n(no results)\n")
        }
        val messages = payload.getValue("messages").jsonArray.map { it.jsonObject }
        val withSearch = messages.map { m ->
            if (m["role"]?.jsonPrimitive?.content == "system") {
                JsonObject(m + ("content" to JsonPrimitive(m.getValue("content").jsonPrimitive.content + "\n\n" + results)))
            } else {
                m
            }
        }
        return JsonObject(payload - "plugins" + ("messages" to JsonArray(withSearch)))
    }
}
