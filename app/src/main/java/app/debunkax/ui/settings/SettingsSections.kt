package app.debunkax.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.debunkax.BuildConfig
import app.debunkax.domain.Provider
import app.debunkax.domain.ProviderModel
import app.debunkax.domain.Settings
import app.debunkax.update.UpdateCheckResult

@Composable
fun ProviderSection(
    settings: Settings,
    openRouterKeyStored: Boolean,
    openRouterModels: List<ProviderModel>,
    onSelectModel: (ProviderModel) -> Unit,
    onConnectOpenRouter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Fournisseur", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenRouter", style = MaterialTheme.typography.titleSmall)
                Text(if (openRouterKeyStored) "Connecté à OpenRouter." else "Non connecté.")
                Button(onClick = onConnectOpenRouter) { Text("Se connecter avec OpenRouter") }
                ModelPicker(
                    label = "Modèle OpenRouter",
                    models = openRouterModels,
                    selectedId = settings.modelId.takeIf { settings.provider == Provider.OPENROUTER },
                    onSelect = onSelectModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(label: String, models: List<ProviderModel>, selectedId: String?, onSelect: (ProviderModel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = models.firstOrNull { it.id == selectedId }?.label ?: "Choisir un modèle"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(text = { Text(model.label) }, onClick = { onSelect(model); expanded = false })
            }
        }
    }
}

@Composable
fun CheckLimitSection(checkLimit: Int?, onSetCheckLimit: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Limite de vérifications par session", style = MaterialTheme.typography.titleMedium)
        var showCustomDialog by remember { mutableStateOf(false) }
        val presets = listOf(null, 20, 50, 100)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                FilterChip(
                    selected = checkLimit == preset,
                    onClick = { onSetCheckLimit(preset) },
                    label = { Text(preset?.toString() ?: "Illimité") },
                )
            }
            FilterChip(
                selected = checkLimit != null && checkLimit !in presets.filterNotNull(),
                onClick = { showCustomDialog = true },
                label = { Text("Personnalisé") },
            )
        }
        if (showCustomDialog) {
            var value by remember { mutableStateOf(checkLimit?.toString() ?: "") }
            AlertDialog(
                onDismissRequest = { showCustomDialog = false },
                title = { Text("Limite personnalisée") },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.filter(Char::isDigit) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        value.toIntOrNull()?.let(onSetCheckLimit)
                        showCustomDialog = false
                    }) { Text("Valider") }
                },
                dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text("Annuler") } },
            )
        }
    }
}

@Composable
fun UpdateSection(updateResult: UpdateCheckResult?, onCheckForUpdate: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCheckForUpdate) { Text("Rechercher une mise à jour") }
        when (updateResult) {
            null -> Unit
            UpdateCheckResult.NotConfigured -> Text("La recherche de mise à jour n'est pas encore configurée.")
            UpdateCheckResult.UpToDate -> Text("Vous utilisez déjà la dernière version.")
            is UpdateCheckResult.UpdateAvailable -> {
                Text("Une nouvelle version (${updateResult.version}) est disponible.")
                TextButton(onClick = { uriHandler.openUri(updateResult.releaseUrl) }) { Text("Ouvrir la page de la version") }
            }
            is UpdateCheckResult.Error -> Text("Erreur : ${updateResult.message}")
        }
    }
}
