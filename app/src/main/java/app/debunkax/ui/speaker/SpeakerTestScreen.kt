package app.debunkax.ui.speaker

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.debunkax.speaker.SpeakerGuess
import app.debunkax.ui.common.findActivity
import java.util.Locale

/** Debug-only "Qui parle ?" screen: live speaker identification through the microphone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakerTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val viewModel: SpeakerTestViewModel = viewModel(factory = viewModelFactory { initializer { SpeakerTestViewModel(app) } })
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start()
    }
    val activity = context.findActivity()
    DisposableEffect(state.running) {
        val window = activity?.window
        if (state.running) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qui parle ?") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Test de reconnaissance des voix de ${state.speakerCount} responsables politiques. " +
                    "Rien n'est enregistré ni envoyé. Arrêtez l'écoute principale avant.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text(headline(state.current), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(detail(state.current), style = MaterialTheme.typography.bodyMedium)
            state.level?.let { level ->
                Text(
                    if (level == 0f) "Micro muet : il est peut-être utilisé par l'écoute principale."
                    else "Niveau du micro : ${"%.4f".format(Locale.FRANCE, level)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Seuil : ${"%.2f".format(Locale.FRANCE, state.threshold)}")
            Slider(value = state.threshold, onValueChange = viewModel::setThreshold, valueRange = 0.4f..0.9f)

            Button(
                onClick = {
                    when {
                        state.running -> viewModel.stop()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> viewModel.start()
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.running) "Arrêter" else "Écouter") }

            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun headline(guess: SpeakerGuess?): String = when (guess) {
    null -> "—"
    SpeakerGuess.Silence -> "(silence)"
    is SpeakerGuess.Named -> guess.name
    is SpeakerGuess.Unknown -> "Inconnu"
}

private fun detail(guess: SpeakerGuess?): String = when (guess) {
    is SpeakerGuess.Named -> "score ${score(guess.score)}"
    is SpeakerGuess.Unknown -> "plus proche : ${guess.closest} (${score(guess.score)})"
    else -> ""
}

internal fun score(s: Float) = "%.2f".format(Locale.FRANCE, s)
