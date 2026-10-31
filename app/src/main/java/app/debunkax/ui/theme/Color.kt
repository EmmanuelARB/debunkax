package app.debunkax.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import app.debunkax.domain.Verdict

data class VerdictColorPair(val container: Color, val onContainer: Color)

class VerdictColors(
    private val falseColors: VerdictColorPair,
    private val misleading: VerdictColorPair,
    private val lacksContext: VerdictColorPair,
    private val unverifiable: VerdictColorPair,
    private val accurate: VerdictColorPair,
) {
    fun forVerdict(verdict: Verdict): VerdictColorPair = when (verdict) {
        Verdict.FALSE -> falseColors
        Verdict.MISLEADING -> misleading
        Verdict.LACKS_CONTEXT -> lacksContext
        Verdict.UNVERIFIABLE -> unverifiable
        Verdict.ACCURATE -> accurate
    }
}

/** Container/on-container pairs, sized for AA contrast, matching Material 3's tonal chip pattern. */
val LightVerdictColors = VerdictColors(
    falseColors = VerdictColorPair(Color(0xFFF9DEDC), Color(0xFF601410)),
    misleading = VerdictColorPair(Color(0xFFFFE0B2), Color(0xFF7A4100)),
    lacksContext = VerdictColorPair(Color(0xFFFFF3C4), Color(0xFF6B5300)),
    unverifiable = VerdictColorPair(Color(0xFFE0E0E0), Color(0xFF424242)),
    accurate = VerdictColorPair(Color(0xFFC8E6C9), Color(0xFF1B5E20)),
)

val DarkVerdictColors = VerdictColors(
    falseColors = VerdictColorPair(Color(0xFF601410), Color(0xFFF2B8B5)),
    misleading = VerdictColorPair(Color(0xFF5C3600), Color(0xFFFFCC80)),
    lacksContext = VerdictColorPair(Color(0xFF4A3B00), Color(0xFFFFE082)),
    unverifiable = VerdictColorPair(Color(0xFF424242), Color(0xFFE0E0E0)),
    accurate = VerdictColorPair(Color(0xFF1B5E20), Color(0xFFA5D6A7)),
)

val LocalVerdictColors = compositionLocalOf { LightVerdictColors }
