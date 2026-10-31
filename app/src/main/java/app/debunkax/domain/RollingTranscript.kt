package app.debunkax.domain

import java.time.Duration
import java.time.Instant

/** In-memory only: the last [window] of final transcript segments, used as context for a Check. */
class RollingTranscript(private val window: Duration = Duration.ofSeconds(30)) {
    private data class Segment(val text: String, val at: Instant)

    private val segments = ArrayDeque<Segment>()

    @Synchronized
    fun append(text: String, at: Instant) {
        if (text.isBlank()) return
        segments.addLast(Segment(text.trim(), at))
        prune(at)
    }

    @Synchronized
    fun textAt(now: Instant): String {
        prune(now)
        return segments.joinToString(" ") { it.text }
    }

    @Synchronized
    fun clear() = segments.clear()

    private fun prune(now: Instant) {
        val cutoff = now.minus(window)
        while (segments.isNotEmpty() && segments.first().at.isBefore(cutoff)) segments.removeFirst()
    }
}
