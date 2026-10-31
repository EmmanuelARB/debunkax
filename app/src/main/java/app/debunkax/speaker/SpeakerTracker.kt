package app.debunkax.speaker

import app.debunkax.domain.SpeakerRecognition
import app.debunkax.speech.TappedAudioSource
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Recognises Speakers in the audio [tap] hands to speech-to-text: 5 s windows every 2.5 s, as in
 * spikes/speakers/live.py. Only the current window is kept; recognition runs off the audio thread
 * and skips windows when it falls behind rather than queueing audio.
 */
class SpeakerTracker(
    private val tap: TappedAudioSource,
    /** Called once per session off the main thread; null when the model isn't in this build. */
    private val identifier: () -> SpeakerIdentifier?,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val threshold: Float = SpeakerMatcher.DEFAULT_THRESHOLD,
) : SpeakerRecognition {
    private val timeline = SpeakerTimeline()
    @Volatile private var journalists: Set<String> = emptySet()
    private val window = FloatArray(WINDOW)
    private var filled = 0
    private var pending: Channel<Pair<Instant, FloatArray>>? = null
    private var worker: Job? = null

    @Synchronized
    override fun start() {
        if (worker != null) return
        val channel = Channel<Pair<Instant, FloatArray>>(Channel.CONFLATED)
        pending = channel
        filled = 0
        worker = scope.launch(Dispatchers.Default) {
            // Loads the model off the main thread; without it every Speaker stays unknown.
            val id = identifier() ?: return@launch
            journalists = id.speakers.filter { it.role == SpeakerRole.JOURNALIST }.map { it.name }.toSet()
            for ((end, samples) in channel) timeline.add(end, id.identify(samples, threshold))
        }
        tap.tap = ::feed
    }

    @Synchronized
    override fun stop() {
        tap.tap = null
        pending?.close()
        pending = null
        worker?.cancel()
        worker = null
        filled = 0
        timeline.clear()
    }

    override fun speakerBetween(from: Instant, to: Instant): String? = timeline.speakerBetween(from, to)

    override fun addresseeBefore(before: Instant, claimant: String): String? =
        timeline.addresseeBefore(before, claimant) { it in journalists }

    private fun feed(chunk: ShortArray) {
        val channel = pending ?: return
        for (s in chunk) {
            window[filled++] = s / 32768f
            if (filled == WINDOW) {
                channel.trySend(clock.instant() to window.copyOf())
                window.copyInto(window, 0, HOP, WINDOW)
                filled = WINDOW - HOP
            }
        }
    }

    private companion object {
        const val WINDOW = SpeakerIdentifier.SAMPLE_RATE * 5
        const val HOP = SpeakerIdentifier.SAMPLE_RATE * 5 / 2
    }
}
