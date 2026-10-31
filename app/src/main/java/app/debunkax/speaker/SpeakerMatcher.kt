package app.debunkax.speaker

import kotlin.math.sqrt

enum class SpeakerRole { POLITICIAN, JOURNALIST }

/** One enrolled person and their voice prints: unit vectors, one per clean clip (rally, studio…). */
data class EnrolledSpeaker(val name: String, val prints: List<FloatArray>, val role: SpeakerRole = SpeakerRole.POLITICIAN)

sealed interface SpeakerGuess {
    data object Silence : SpeakerGuess
    data class Named(val name: String, val score: Float) : SpeakerGuess
    /** Nobody is close enough, or two people are too close to tell apart. */
    data class Unknown(val closest: String, val score: Float) : SpeakerGuess
}

/**
 * Open-set matching, as in spikes/speakers/live.py: a person scores the best cosine over their
 * prints, and is named only above [threshold] and ahead of the runner-up by [margin].
 */
object SpeakerMatcher {
    const val DEFAULT_THRESHOLD = 0.45f
    const val MARGIN = 0.05f

    /** [explain] receives why the guess came out so, for debug logs. */
    fun guess(
        embedding: FloatArray,
        speakers: List<EnrolledSpeaker>,
        threshold: Float = DEFAULT_THRESHOLD,
        explain: (String) -> Unit = {},
    ): SpeakerGuess {
        val unit = normalized(embedding)
        val ranked = speakers
            .map { s -> s.name to s.prints.maxOf { dot(it, unit) } }
            .sortedByDescending { it.second }
        val (best, top) = ranked.first()
        val (runner, second) = ranked.getOrNull(1) ?: ("-" to -1f)
        val reason = when {
            top < threshold -> "under ${"%.2f".format(threshold)}"
            top - second < MARGIN -> "margin ${"%.2f".format(top - second)} < ${"%.2f".format(MARGIN)}"
            else -> "ok"
        }
        explain("${"%.2f".format(top)} $best vs ${"%.2f".format(second)} $runner -> $reason")
        return if (reason != "ok") SpeakerGuess.Unknown(best, top) else SpeakerGuess.Named(best, top)
    }

    fun normalized(v: FloatArray): FloatArray {
        val norm = sqrt(dot(v, v))
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
