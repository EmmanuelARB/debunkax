package app.debunkax.update

import app.debunkax.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** GitHub "latest release" API URL, e.g. "https://api.github.com/repos/OWNER/REPO/releases/latest". Empty until the repo ships one. */
const val RELEASES_API_URL = ""

sealed interface UpdateCheckResult {
    data object NotConfigured : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class UpdateAvailable(val version: String, val releaseUrl: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/** Reads the public GitHub release page at launch-check time; sends nothing but the GET (see ADR 0001). */
class UpdateChecker(private val http: OkHttpClient) {
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (RELEASES_API_URL.isBlank()) return@withContext UpdateCheckResult.NotConfigured
        try {
            val request = Request.Builder().url(RELEASES_API_URL).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext UpdateCheckResult.Error("Erreur ${response.code}")
                val release = json.decodeFromString<ReleaseDto>(response.body.string())
                val latest = release.tagName.removePrefix("v")
                if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                    UpdateCheckResult.UpdateAvailable(latest, release.htmlUrl)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (e: IOException) {
            UpdateCheckResult.Error("Pas de connexion")
        } catch (e: Exception) {
            UpdateCheckResult.Error("Réponse du serveur de mises à jour invalide")
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
)
