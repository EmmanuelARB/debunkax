package app.debunkax.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.debunkax.domain.Provider
import app.debunkax.domain.Settings
import app.debunkax.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val PROVIDER_KEY = stringPreferencesKey("provider")
private val MODEL_ID_KEY = stringPreferencesKey("model_id")
private val CHECK_LIMIT_KEY = intPreferencesKey("check_limit")

/** [SettingsRepository] backed by Preferences DataStore. No stored limit means unlimited Checks. */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val settings: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        val provider = prefs[PROVIDER_KEY]?.let { name -> Provider.entries.firstOrNull { it.name == name } }
        Settings(
            provider = provider,
            modelId = prefs[MODEL_ID_KEY],
            checkLimit = prefs[CHECK_LIMIT_KEY],
        )
    }

    override suspend fun setProvider(provider: Provider, modelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[PROVIDER_KEY] = provider.name
            prefs[MODEL_ID_KEY] = modelId
        }
    }

    override suspend fun clearProvider() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(PROVIDER_KEY)
            prefs.remove(MODEL_ID_KEY)
        }
    }

    override suspend fun setCheckLimit(limit: Int?) {
        context.settingsDataStore.edit { prefs ->
            if (limit == null) prefs.remove(CHECK_LIMIT_KEY) else prefs[CHECK_LIMIT_KEY] = limit
        }
    }
}
