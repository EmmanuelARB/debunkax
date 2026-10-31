package app.debunkax.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.debunkax.AppContainer
import app.debunkax.domain.CheckResult
import app.debunkax.domain.ClaimRecord
import app.debunkax.domain.Explanation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ExplanationUiState {
    data object Idle : ExplanationUiState
    data object Loading : ExplanationUiState
    data class Loaded(val explanation: Explanation) : ExplanationUiState
    data class Error(val message: String) : ExplanationUiState
}

class ClaimDetailViewModel(private val container: AppContainer, private val claimId: Long) : ViewModel() {
    val claim: StateFlow<ClaimRecord?> = container.history.claim(claimId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _explanationState = MutableStateFlow<ExplanationUiState>(ExplanationUiState.Idle)
    val explanationState: StateFlow<ExplanationUiState> = _explanationState.asStateFlow()

    private var requested = false

    fun loadExplanationIfNeeded() {
        val current = claim.value ?: return
        val result = current.result ?: return
        val existing = current.explanation
        if (existing != null) {
            _explanationState.value = ExplanationUiState.Loaded(existing)
            return
        }
        if (requested) return
        requested = true
        loadExplanation(current.checkedText, result)
    }

    fun retry() {
        val current = claim.value ?: return
        val result = current.result ?: return
        loadExplanation(current.checkedText, result)
    }

    private fun loadExplanation(text: String, result: CheckResult) {
        _explanationState.value = ExplanationUiState.Loading
        viewModelScope.launch {
            val client = container.checkClient()
            if (client == null) {
                _explanationState.value = ExplanationUiState.Error("Aucun fournisseur configuré.")
                return@launch
            }
            try {
                val explanation = container.explanations.load(claimId, text, result, claim.value?.explanation, client)
                _explanationState.value = ExplanationUiState.Loaded(explanation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _explanationState.value = ExplanationUiState.Error("Impossible de charger l'explication.")
            }
        }
    }
}
