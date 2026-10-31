package app.debunkax.check

import app.debunkax.domain.CheckClient
import app.debunkax.domain.KeyVault
import app.debunkax.domain.Settings
import okhttp3.OkHttpClient

/** Builds the [CheckClient] matching the user's [Settings] and stored key, or null if either is missing. */
object CheckClientFactory {
    suspend fun create(settings: Settings, keyVault: KeyVault, http: OkHttpClient): CheckClient? {
        val provider = settings.provider ?: return null
        val modelId = settings.modelId ?: return null
        val model = Models.find(provider, modelId) ?: return null
        val apiKey = keyVault.get(provider)?.takeIf { it.isNotBlank() } ?: return null

        return OpenRouterCheckClient(apiKey, model, http)
    }
}
