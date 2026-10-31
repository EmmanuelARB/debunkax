package app.debunkax.keys

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.debunkax.domain.KeyVault
import app.debunkax.domain.Provider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Base64

private const val MASTER_KEY_URI = "android-keystore://app.debunkax.keys.master"
private const val KEYSET_NAME = "app_debunkax_api_keys_keyset"
private const val PREF_FILE_NAME = "app_debunkax_keyset_prefs"

private val Context.keyVaultDataStore by preferencesDataStore(name = "api_keys")

/**
 * [KeyVault] backed by a Tink AEAD whose keyset is itself wrapped by an Android Keystore master
 * key (so the keyset file is useless off-device), with the encrypted API keys stored in DataStore
 * preferences rather than raw SharedPreferences. Keys are never logged. Needs Android; left
 * untested (see the check/keys tests for what is unit-testable here: none - Pkce covers the pure
 * crypto used by OpenRouterAuth instead).
 */
class TinkKeyVault(private val context: Context) : KeyVault {

    init {
        AeadConfig.register()
    }

    private val aead: Aead by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override suspend fun get(provider: Provider): String? = withContext(Dispatchers.IO) {
        val encoded = context.keyVaultDataStore.data.first()[dataStoreKey(provider)] ?: return@withContext null
        val ciphertext = Base64.getDecoder().decode(encoded)
        String(aead.decrypt(ciphertext, associatedData(provider)), Charsets.UTF_8)
    }

    override suspend fun put(provider: Provider, key: String) {
        withContext(Dispatchers.IO) {
            val ciphertext = aead.encrypt(key.toByteArray(Charsets.UTF_8), associatedData(provider))
            val encoded = Base64.getEncoder().encodeToString(ciphertext)
            context.keyVaultDataStore.edit { it[dataStoreKey(provider)] = encoded }
        }
    }

    override suspend fun remove(provider: Provider) {
        withContext(Dispatchers.IO) {
            context.keyVaultDataStore.edit { it.remove(dataStoreKey(provider)) }
        }
    }

    private fun dataStoreKey(provider: Provider) = stringPreferencesKey("api_key_${provider.name.lowercase()}")

    private fun associatedData(provider: Provider) = provider.name.toByteArray(Charsets.UTF_8)
}
