package app.debunkax.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.debunkax.ui.common.speakerLabel
import app.debunkax.container
import app.debunkax.ui.common.AiDisclaimer
import app.debunkax.ui.common.VerdictChip
import app.debunkax.ui.common.formatFullDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaimDetailScreen(claimId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val viewModel: ClaimDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { ClaimDetailViewModel(container, claimId) } },
    )
    val claim by viewModel.claim.collectAsStateWithLifecycle()
    val explanationState by viewModel.explanationState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(claim) {
        if (claim != null) viewModel.loadExplanationIfNeeded()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Détail de l'affirmation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        val current = claim
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(speakerLabel(current.speaker), style = MaterialTheme.typography.titleMedium)
                Text("« ${current.text} »", style = MaterialTheme.typography.headlineSmall)
                if (current.checkedText != current.text) {
                    Spacer(Modifier.height(8.dp))
                    Text("Vérifiée sous la forme : « ${current.checkedText} »", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(formatFullDateTime(current.heardAt), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))

                val result = current.result
                if (result == null) {
                    Text(
                        "Cette affirmation n'a pas encore été vérifiée. Elle le sera dès que possible.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    VerdictChip(result.verdict)
                    Spacer(Modifier.height(8.dp))
                    Text(result.context, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))

                    if (result.sources.isNotEmpty()) {
                        Text("Sources", style = MaterialTheme.typography.titleSmall)
                        result.sources.forEach { source ->
                            Text(
                                source.title,
                                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { uriHandler.openUri(source.url) }.padding(vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    Text("Explication", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    when (val explanation = explanationState) {
                        ExplanationUiState.Idle -> Unit
                        ExplanationUiState.Loading -> CircularProgressIndicator(Modifier.padding(vertical = 8.dp))
                        is ExplanationUiState.Error -> {
                            Text(explanation.message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = viewModel::retry) { Text("Réessayer") }
                        }
                        is ExplanationUiState.Loaded -> {
                            Text(explanation.explanation.text, style = MaterialTheme.typography.bodyMedium)
                            explanation.explanation.sources.forEach { source ->
                                Text(
                                    source.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { uriHandler.openUri(source.url) }.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    AiDisclaimer()
                }
            }
        }
    }
}
