package app.debunkax.ui.listen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.media.projection.MediaProjectionManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.debunkax.container
import app.debunkax.session.ListeningSource
import app.debunkax.session.PlaybackCaptureService
import app.debunkax.domain.CheckFailure
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.Spend
import app.debunkax.session.ChecksPaused
import app.debunkax.ui.common.findActivity
import app.debunkax.ui.common.speakerLabel
import app.debunkax.ui.theme.SessionColorScheme
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(onOpenHistory: () -> Unit, onOpenSettings: () -> Unit, onOpenClaim: (Long) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val viewModel: ListenViewModel = viewModel(factory = viewModelFactory { initializer { ListenViewModel(container) } })
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showPermissionRationale by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var appPlaybackNote by remember { mutableStateOf<String?>(null) }
    val sourceLabel by viewModel.sourceLabel.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showPermissionRationale = false
            showSourceDialog = true
        } else {
            showPermissionRationale = true
        }
    }

    // System consent for app-audio capture (MediaProjection): the mediaProjection foreground
    // service must be running before the dialog; the session starts on approval.
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val projection = context.getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(result.resultCode, data)
            if (projection != null) {
                viewModel.start(ListeningSource.AppPlayback(projection))
            } else {
                PlaybackCaptureService.stop(context)
                appPlaybackNote = "La capture de l'audio des apps n'a pas pu être démarrée."
            }
        } else {
            PlaybackCaptureService.stop(context)
            appPlaybackNote = "Consentement refusé : sans lui, Debunkax ne peut pas écouter l'audio des apps."
        }
    }

    fun startAppPlayback() {
        appPlaybackNote = null
        PlaybackCaptureService.start(context)
        consentLauncher.launch(
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
        )
    }

    fun onStartClick() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            showPermissionRationale = false
            showSourceDialog = true
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    KeepScreenOn(active = state.listening)
    DingOnNewAlert(
        alertId = state.alert?.claimId,
        alreadyDinged = viewModel.lastDingedAlertId,
        onDing = { viewModel.lastDingedAlertId = it },
    )

    val screenContent: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Debunkax") },
                    actions = {
                        IconButton(onClick = onOpenHistory) {
                            Icon(Icons.Default.History, contentDescription = "Historique")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Réglages")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).dismissOnUnhandledTap(state.alert?.claimId, viewModel::dismissAlert)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        state.checksPaused?.let {
                            ChecksPausedBanner(it, onOpenSettings)
                            Spacer(Modifier.height(12.dp))
                        }
                        state.speechProblem?.let {
                            SpeechProblemBanner(it)
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    ButtonAboveText(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        button = {
                            BigListenButton(
                                listening = state.listening,                                onClick = { if (state.listening) viewModel.stop() else onStartClick() },
                            )
                        },
                        below = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (state.listening && sourceLabel != null) {
                                    Text(
                                        text = "Source : $sourceLabel",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                if (state.listening && state.liveText.isNotBlank()) {
                                    Text(
                                        text = speakerLabel(state.liveSpeaker),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (state.liveSpeaker != null) 0.9f else 0.5f),
                                    )
                                    Text(
                                        text = state.liveText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }

                                val counterText = if (state.checkLimit != null) {
                                    "Vérifications : ${state.checksUsed} / ${state.checkLimit}"
                                } else {
                                    "Vérifications : ${state.checksUsed}"
                                }
                                Text(counterText, style = MaterialTheme.typography.labelMedium)
                                state.startedAt?.let { startedAt ->
                                    Text(
                                        sessionTimeAndCost(startedAt, state.spent),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        },
                    )

                    if (showPermissionRationale) {
                        Text(
                            "Le microphone est nécessaire pour transcrire la parole entendue et détecter les " +
                                "affirmations à vérifier. Aucun son n'est jamais enregistré ni conservé.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    appPlaybackNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                state.alert?.let { alert ->
                    key(alert.claimId) {
                        AlertOverlay(alert = alert, onDismiss = viewModel::dismissAlert, onSeeMore = onOpenClaim)
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("Quelle source audio ?") },
            text = {
                Text(
                    "Microphone : ce qui se passe dans la pièce (TV, débat en direct).\n\n" +
                        "Audio des apps : la lecture des apps de ce téléphone (YouTube, France.tv Pluzz…), " +
                        "avec le consentement système et l'icône d'enregistrement qui va avec.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    appPlaybackNote = null
                    showSourceDialog = false
                    viewModel.start()
                }) { Text("Microphone") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSourceDialog = false
                    startAppPlayback()
                }) { Text("Audio des apps") }
            },
        )
    }

    if (state.listening) {
        MaterialTheme(colorScheme = SessionColorScheme, content = screenContent)
    } else {
        screenContent()
    }
}

/**
 * Calls [onDismiss] on a tap that nothing under it handled, while an Alert ([alertId]) is showing. It watches the
 * final pass without consuming, so the tap still reaches the button: stopping never takes two taps.
 */
private fun Modifier.dismissOnUnhandledTap(alertId: Long?, onDismiss: () -> Unit): Modifier =
    if (alertId == null) {
        this
    } else {
        pointerInput(alertId) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Final)
                if (waitForUpOrCancellation(PointerEventPass.Final) != null) onDismiss()
            }
        }
    }

/**
 * Puts the button's centre at [BUTTON_CENTER] of the height, with [below] under it. The button only moves up when
 * [below] would otherwise run off the bottom, so it stays put as the live transcript grows.
 */
@Composable
private fun ButtonAboveText(button: @Composable () -> Unit, below: @Composable () -> Unit, modifier: Modifier) {
    Layout(contents = listOf(button, below), modifier = modifier) { (buttonPart, belowPart), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placedButton = buttonPart.single().measure(loose)
        val placedBelow = belowPart.single().measure(loose)
        val gap = 24.dp.roundToPx()
        val height = constraints.maxHeight
        val top = ((height * BUTTON_CENTER).toInt() - placedButton.height / 2)
            .coerceAtMost(height - placedBelow.height - gap - placedButton.height)
            .coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            placedButton.place((constraints.maxWidth - placedButton.width) / 2, top)
            placedBelow.place((constraints.maxWidth - placedBelow.width) / 2, top + placedButton.height + gap)
        }
    }
}

private const val BUTTON_CENTER = 0.6f

/** Elapsed time, ticking every second, then what the session's Checks have cost so far. */
@Composable
private fun sessionTimeAndCost(startedAt: Instant, spent: Spend?): String {
    val now by produceState(Instant.now(), startedAt) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val seconds = Duration.between(startedAt, now).seconds.coerceAtLeast(0)
    val time = if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
    val cost = spent?.let { (if (it.estimated) "≈ " else "") + String.format(Locale.FRANCE, "%.2f $", it.usd) }
    return if (cost == null) time else "$time · $cost"
}

/** Keeps the screen on during a session. Brightness stays the user's own: dimming and relighting it didn't work well. */
@Composable
private fun KeepScreenOn(active: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(active) {
        val window = activity?.window
        if (active && window != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Plays the phone's notification sound once per new Alert, following its volume and silent mode.
 * The "already dinged" id lives in the ViewModel, so leaving and re-entering the screen
 * (Historique, Réglages) does not play the sound again for the same Alert. */
@Composable
private fun DingOnNewAlert(alertId: Long?, alreadyDinged: Long?, onDing: (Long) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(alertId, alreadyDinged) {
        if (alertId == null || alertId == alreadyDinged) return@LaunchedEffect
        onDing(alertId)
        runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }
}

@Composable
private fun BigListenButton(listening: Boolean, onClick: () -> Unit) {
    val containerColor = if (listening) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary
    val contentColor = if (listening) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.size(180.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (listening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (listening) "Arrêter" else "Démarrer l'écoute",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun ChecksPausedBanner(paused: ChecksPaused, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(checksPausedMessage(paused), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (paused is ChecksPaused.NoProvider) {
                TextButton(onClick = onOpenSettings) { Text("Réglages") }
            }
        }
    }
}

private fun checksPausedMessage(paused: ChecksPaused): String = when (paused) {
    ChecksPaused.NoProvider -> "Aucun fournisseur configuré."
    is ChecksPaused.Failed -> when (paused.failure) {
        is CheckFailure.Offline ->
            "Pas de connexion : les affirmations sont gardées et seront vérifiées plus tard."
        is CheckFailure.InvalidKey ->
            "La clé du fournisseur est invalide : les affirmations sont gardées et seront vérifiées plus tard."
        is CheckFailure.NoCredit ->
            "Crédit épuisé chez le fournisseur : les affirmations sont gardées et seront vérifiées plus tard."
        is CheckFailure.SearchDisabled ->
            "La recherche web est désactivée sur ce compte : les affirmations sont gardées et seront vérifiées plus tard."
        is CheckFailure.RateLimited ->
            "Trop de requêtes envoyées au fournisseur : les affirmations sont gardées et seront vérifiées plus tard."
        is CheckFailure.BudgetReached ->
            "Limite de vérifications de la session atteinte : les affirmations sont gardées et seront vérifiées plus tard."
        is CheckFailure.Unexpected ->
            "Une erreur est survenue avec le fournisseur : les affirmations sont gardées et seront vérifiées plus tard."
    }
}

@Composable
private fun SpeechProblemBanner(problem: SpeechFailure) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(
            speechProblemMessage(problem),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun speechProblemMessage(problem: SpeechFailure): String = when (problem) {
    is SpeechFailure.ModelDownloading -> "Téléchargement du modèle de reconnaissance vocale en cours…"
    is SpeechFailure.Unsupported -> "La reconnaissance vocale n'est pas prise en charge sur cet appareil."
    is SpeechFailure.Engine -> "Une erreur est survenue avec la reconnaissance vocale. Réessayez de démarrer l'écoute."
}
