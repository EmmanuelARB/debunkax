package app.debunkax.ui.settings

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.debunkax.BuildConfig
import app.debunkax.container
import app.debunkax.ui.common.LifecycleResumeEffect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTrustedSources: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSpeakerTest: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val openRouterKeyStored by viewModel.openRouterKeyStored.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LifecycleResumeEffect(onResume = viewModel::refreshKeyStatus)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ProviderSection(
                settings = settings,
                openRouterKeyStored = openRouterKeyStored,
                openRouterModels = viewModel.openRouterModels(),
                onSelectModel = viewModel::selectModel,
                onConnectOpenRouter = {
                    scope.launch {
                        val uri = container.openRouterAuth.authorizationUri()
                        CustomTabsIntent.Builder().build().launchUrl(context, uri)
                    }
                },
            )

            CheckLimitSection(checkLimit = settings.checkLimit, onSetCheckLimit = viewModel::setCheckLimit)

            Column {
                TextButton(onClick = onOpenTrustedSources) { Text("Sources de confiance") }
                TextButton(onClick = onOpenPrivacy) { Text("Confidentialité") }
            }

            UpdateSection(updateResult = updateResult, onCheckForUpdate = viewModel::checkForUpdate)

            if (BuildConfig.DEBUG) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mode démo", modifier = Modifier.weight(1f))
                    Switch(checked = viewModel.demoModeEnabled, onCheckedChange = viewModel::setDemoMode)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Démo audio réelle", modifier = Modifier.weight(1f))
                    Switch(checked = viewModel.realAudioDemoModeEnabled, onCheckedChange = viewModel::setRealAudioDemoMode)
                }
                TextButton(onClick = onOpenSpeakerTest) { Text("Qui parle ? (test)") }
            }
        }
    }
}
