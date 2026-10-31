package app.debunkax.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/** Dark, low-contrast scheme used while a Listening Session is on screen, to avoid lighting up the room. */
val SessionColorScheme = darkColorScheme(
    background = Color(0xFF0E0E0E),
    surface = Color(0xFF0E0E0E),
    surfaceVariant = Color(0xFF1B1B1B),
    onBackground = Color(0xFF757575),
    onSurface = Color(0xFF757575),
    onSurfaceVariant = Color(0xFF6B6B6B),
    primary = Color(0xFFB0B0B0),
    onPrimary = Color(0xFF0E0E0E),
)

@Composable
fun DebunkaxTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val verdictColors = if (darkTheme) DarkVerdictColors else LightVerdictColors
    CompositionLocalProvider(LocalVerdictColors provides verdictColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
