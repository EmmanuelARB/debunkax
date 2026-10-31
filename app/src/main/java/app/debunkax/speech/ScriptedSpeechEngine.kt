package app.debunkax.speech

import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.TranscriptSegment
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [SpeechEngine] that replays a fixed script instead of listening to the microphone.
 *
 * For each line, in order, it emits one partial segment (the first half of the line) shortly
 * before emitting the line in full as a final segment. Finals are spaced [interval] apart.
 *
 * For development without a microphone, and for tests.
 */
class ScriptedSpeechEngine(
    private val lines: List<String>,
    private val interval: Duration,
) : SpeechEngine {

    override fun transcribe(): Flow<TranscriptSegment> = flow {
        val partialLead = partialLeadFor(interval)
        for (line in lines) {
            delay((interval - partialLead).toMillis())
            emit(TranscriptSegment(line.take(line.length / 2), isFinal = false, at = Instant.now()))
            delay(partialLead.toMillis())
            emit(TranscriptSegment(line, isFinal = true, at = Instant.now()))
        }
    }

    private companion object {
        /** How long before each final its partial is emitted: a fifth of the interval, at least 1 ms. */
        fun partialLeadFor(interval: Duration): Duration {
            val fifth = interval.dividedBy(5)
            return if (fifth.isZero) Duration.ofMillis(1) else fifth
        }
    }
}
