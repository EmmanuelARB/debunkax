package app.debunkax.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AiDisclaimer(modifier: Modifier = Modifier) {
    Text(
        text = "Vérification générée par IA, peut contenir des erreurs",
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.7f),
        modifier = modifier,
    )
}
