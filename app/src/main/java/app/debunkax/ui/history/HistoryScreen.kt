package app.debunkax.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.debunkax.container
import app.debunkax.domain.ClaimRecord
import app.debunkax.domain.Verdict
import app.debunkax.ui.common.formatSessionHeader
import app.debunkax.ui.common.formatTime
import app.debunkax.ui.common.verdictLabel
import app.debunkax.ui.common.VerdictChip
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenClaim: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val viewModel: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(container) } })

    val filter by viewModel.filterState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val pendingMessage by viewModel.pendingCheckMessage.collectAsStateWithLifecycle()
    val showClearConfirm by viewModel.showClearConfirm.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pendingMessage) {
        pendingMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumePendingCheckMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Rechercher") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Verdict.entries.forEach { verdict ->
                    FilterChip(
                        selected = verdict in filter.verdicts,
                        onClick = { viewModel.toggleVerdict(verdict) },
                        label = { Text(verdictLabel(verdict)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = viewModel::checkPending) { Text("Vérifier les affirmations en attente") }
                TextButton(onClick = viewModel::requestClear) { Text("Effacer l'historique") }
            }

            Spacer(Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucune affirmation vérifiée pour l'instant.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    sessions.forEach { session ->
                        item(key = "header-${session.sessionId}") {
                            Text(
                                formatSessionHeader(session.startedAt, session.endedAt),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(session.claims, key = { it.id }) { claim ->
                            ClaimRow(claim, onClick = { onOpenClaim(claim.id) })
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelClear,
            title = { Text("Effacer l'historique ?") },
            text = { Text("Toutes les affirmations enregistrées seront supprimées définitivement.") },
            confirmButton = { TextButton(onClick = viewModel::confirmClear) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = viewModel::cancelClear) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ClaimRow(claim: ClaimRecord, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(claim.heardAt), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        Spacer(Modifier.width(8.dp))
        if (claim.result != null) {
            VerdictChip(claim.result.verdict)
        } else {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Pas encore vérifiée",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            claim.speaker?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            Text(
                claim.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
