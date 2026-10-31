package app.debunkax.domain

import kotlinx.coroutines.flow.Flow

enum class Provider { OPENROUTER }

/**
 * A model offered to users. Only combinations whose web search honours the Trusted Source
 * allowlist belong here (ADR 0001). [openRouterEngine] forces OpenRouter's search engine when set.
 */
data class ProviderModel(
    val provider: Provider,
    val id: String,
    val label: String,
    val openRouterEngine: String? = null,
)

data class Settings(
    val provider: Provider?,
    val modelId: String?,
    /** Maximum Checks per Listening Session; null means unlimited. */
    val checkLimit: Int?,
)

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setProvider(provider: Provider, modelId: String)
    suspend fun setCheckLimit(limit: Int?)
}

/** API keys, encrypted at rest with a key held in Android Keystore. */
interface KeyVault {
    suspend fun get(provider: Provider): String?
    suspend fun put(provider: Provider, key: String)
    suspend fun remove(provider: Provider)
}
