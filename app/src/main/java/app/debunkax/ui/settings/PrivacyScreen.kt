package app.debunkax.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confidentialité") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Aucun son ni aucune transcription n'est jamais enregistré : l'audio est traité en direct puis " +
                    "immédiatement écarté. Seules les affirmations détectées et leurs verdicts sont conservés " +
                    "sur le téléphone, dans l'Historique.\n\n" +
                    "L'orateur est reconnu à sa voix directement sur le téléphone, en la comparant aux voix " +
                    "de 62 personnalités publiques (responsables politiques et journalistes), enregistrées à partir " +
                    "d'interventions publiques. " +
                    "Dans le doute, il reste « non identifié ».\n\n" +
                    "Pour chaque vérification, seuls l'affirmation, son orateur s'il est reconnu et environ les " +
                    "30 dernières secondes du contexte entendu sont envoyés à OpenRouter, avec votre propre clé.\n\n" +
                    "Debunkax n'a pas de serveur : rien ne transite par un serveur Debunkax.\n\n" +
                    "Vous pouvez effacer l'historique à tout moment depuis l'écran Historique.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
