package app.debunkax.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.debunkax.domain.Verdict
import app.debunkax.ui.theme.LocalVerdictColors

fun verdictLabel(verdict: Verdict): String = when (verdict) {
    Verdict.FALSE -> "Faux"
    Verdict.MISLEADING -> "Trompeur"
    Verdict.LACKS_CONTEXT -> "Manque de contexte"
    Verdict.UNVERIFIABLE -> "Invérifiable"
    Verdict.ACCURATE -> "Exact"
}

@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier) {
    val colors = LocalVerdictColors.current.forVerdict(verdict)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Text(
            text = verdictLabel(verdict),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Who said a Claim, as shown to the user: the recognised Speaker, or unknown (ADR 0003). */
fun speakerLabel(speaker: String?): String = speaker ?: "Orateur non identifié"
