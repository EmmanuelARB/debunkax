package app.debunkax.keys

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val CALLBACK_URL = "debunkax://oauth/openrouter"

// Verified against https://openrouter.ai/docs/use-cases/oauth-pkce.
private const val AUTH_URL = "https://openrouter.ai/auth"
private const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"

private val Context.openRouterOAuthDataStore by preferencesDataStore(name = "openrouter_oauth")
private val PENDING_VERIFIER_KEY = stringPreferencesKey("pending_code_verifier")

/**
 * OpenRouter's OAuth PKCE flow (ADR 0001 - bring your own key). The pending code_verifier is kept
 * in DataStore so it survives the process dying while the system browser is in front; it is
 * cleared once [exchange] succeeds. Needs Android (Uri, DataStore); left untested here - the pure
 * PKCE math it relies on is covered by [Pkce] and its test.
 */
class OpenRouterAuth(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
    private val authUrl: String = AUTH_URL,
    private val exchangeUrl: String = EXCHANGE_URL,
) {

    /** Generates a fresh code_verifier, persists it, and returns the authorization page to open. */
    suspend fun authorizationUri(): Uri {
        val verifier = Pkce.generateVerifier()
        withContext(Dispatchers.IO) {
            context.openRouterOAuthDataStore.edit { it[PENDING_VERIFIER_KEY] = verifier }
        }
        val challenge = Pkce.challenge(verifier)
        return Uri.parse(authUrl).buildUpon()
            .appendQueryParameter("callback_url", CALLBACK_URL)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    /** Exchanges the callback's `code` for a long-lived OpenRouter API key. */
    suspend fun exchange(code: String): String = withContext(Dispatchers.IO) {
        val verifier = context.openRouterOAuthDataStore.data.first()[PENDING_VERIFIER_KEY]
            ?: error("Aucun code_verifier OpenRouter en attente")

        val payload = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }

        val request = Request.Builder()
            .url(exchangeUrl)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("Pas de connexion", e)
        }

        val bodyText = response.use {
            val text = it.body.string()
            check(it.isSuccessful) { "Échange OAuth OpenRouter échoué (${it.code})" }
            text
        }

        context.openRouterOAuthDataStore.edit { it.remove(PENDING_VERIFIER_KEY) }

        Json.parseToJsonElement(bodyText).jsonObject.getValue("key").jsonPrimitive.content
    }
}
