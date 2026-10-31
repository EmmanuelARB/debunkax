package app.debunkax.ui.listen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.debunkax.ui.common.speakerLabel
import app.debunkax.session.ActiveAlert
import app.debunkax.ui.common.AiDisclaimer
import app.debunkax.ui.common.VerdictChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertOverlay(alert: ActiveAlert, onDismiss: () -> Unit, onSeeMore: (Long) -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDismiss()
            true
        },
    )

    // No full-screen scrim: it would swallow the first tap on "Arrêter". Taps elsewhere dismiss the Alert through
    // dismissOnUnhandledTap on the screen.
    Box(Modifier.fillMaxSize()) {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            backgroundContent = {},
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(16.dp)) {
                    VerdictChip(alert.result.verdict)
                    Spacer(Modifier.height(8.dp))
                    Text(speakerLabel(alert.speaker), style = MaterialTheme.typography.titleMedium)
                    Text("« ${alert.claimText} »", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(alert.result.context, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { onSeeMore(alert.claimId) }) { Text("En savoir plus") }
                    Spacer(Modifier.height(4.dp))
                    AiDisclaimer()
                }
            }
        }
    }
}
