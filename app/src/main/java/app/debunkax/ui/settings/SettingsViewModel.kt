package app.debunkax.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.debunkax.AppContainer
import app.debunkax.check.Models
import app.debunkax.domain.Provider
import app.debunkax.domain.ProviderModel
import app.debunkax.domain.Settings
import app.debunkax.update.UpdateCheckResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings(null, null, null))

    private val _openRouterKeyStored = MutableStateFlow(false)
    val openRouterKeyStored: StateFlow<Boolean> = _openRouterKeyStored.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _updateResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateResult: StateFlow<UpdateCheckResult?> = _updateResult.asStateFlow()

    var demoModeEnabled by mutableStateOf(container.demoMode)
        private set

    var realAudioDemoModeEnabled by mutableStateOf(container.realAudioDemoMode)
        private set

    init {
        refreshKeyStatus()
    }

    fun refreshKeyStatus() {
        viewModelScope.launch {
            _openRouterKeyStored.value = container.keyVault.get(Provider.OPENROUTER) != null
        }
    }

    fun selectModel(model: ProviderModel) {
        viewModelScope.launch { container.settings.setProvider(model.provider, model.id) }
    }

    fun setCheckLimit(limit: Int?) {
        viewModelScope.launch { container.settings.setCheckLimit(limit) }
    }

    fun setDemoMode(enabled: Boolean) {
        container.demoMode = enabled
        demoModeEnabled = enabled
        if (enabled) realAudioDemoModeEnabled = false
    }

    fun setRealAudioDemoMode(enabled: Boolean) {
        container.realAudioDemoMode = enabled
        realAudioDemoModeEnabled = enabled
        if (enabled) demoModeEnabled = false
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun checkForUpdate() {
        viewModelScope.launch { _updateResult.value = container.updateChecker.check() }
    }

    fun consumeUpdateResult() {
        _updateResult.value = null
    }

    fun openRouterModels(): List<ProviderModel> = Models.all.filter { it.provider == Provider.OPENROUTER }
}
