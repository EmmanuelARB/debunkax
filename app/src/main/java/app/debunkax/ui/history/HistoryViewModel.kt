package app.debunkax.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.debunkax.AppContainer
import app.debunkax.domain.HistoryFilter
import app.debunkax.domain.SessionHistory
import app.debunkax.domain.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilter())
    val filterState: StateFlow<HistoryFilter> = filter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessions: StateFlow<List<SessionHistory>> = filter
        .flatMapLatest { container.history.sessions(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingCheckMessage = MutableStateFlow<String?>(null)
    val pendingCheckMessage: StateFlow<String?> = _pendingCheckMessage.asStateFlow()

    private val _showClearConfirm = MutableStateFlow(false)
    val showClearConfirm: StateFlow<Boolean> = _showClearConfirm.asStateFlow()

    fun setQuery(query: String) {
        filter.update { it.copy(query = query) }
    }

    fun toggleVerdict(verdict: Verdict) {
        filter.update {
            val verdicts = if (verdict in it.verdicts) it.verdicts - verdict else it.verdicts + verdict
            it.copy(verdicts = verdicts)
        }
    }

    fun checkPending() {
        viewModelScope.launch {
            val client = container.checkClient()
            if (client == null) {
                _pendingCheckMessage.value = "Aucun fournisseur configuré."
                return@launch
            }
            val report = container.pendingChecker.checkAll(client)
            val remaining = if (report.stillPending > 0) " ${report.stillPending} encore en attente." else ""
            _pendingCheckMessage.value = when {
                report.failure != null ->
                    "Vérification interrompue : ${report.failure.message}. ${report.checked} vérifiée(s).$remaining"
                report.checked == 0 && report.stillPending == 0 -> "Aucune affirmation en attente."
                else -> "${report.checked} affirmation(s) vérifiée(s).$remaining"
            }
        }
    }

    fun consumePendingCheckMessage() {
        _pendingCheckMessage.value = null
    }

    fun requestClear() {
        _showClearConfirm.value = true
    }

    fun cancelClear() {
        _showClearConfirm.value = false
    }

    fun confirmClear() {
        _showClearConfirm.value = false
        viewModelScope.launch { container.history.clearAll() }
    }
}
