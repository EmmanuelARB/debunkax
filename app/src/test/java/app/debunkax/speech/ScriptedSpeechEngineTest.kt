package app.debunkax.speech

import app.debunkax.domain.TranscriptSegment
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptedSpeechEngineTest {

    private data class Recorded(val segment: TranscriptSegment, val atMillis: Long)

    @Test
    fun `replays each line as a partial then a final, finals spaced by the interval`() = runTest {
        val interval = Duration.ofSeconds(4)
        val lines = listOf(
            "Le chômage recule ce trimestre selon l'INSEE",
            "Le déficit public dépasse le seuil européen",
        )
        val engine = ScriptedSpeechEngine(lines, interval)

        val recorded = mutableListOf<Recorded>()
        val job = launch {
            engine.transcribe().collect { recorded.add(Recorded(it, currentTime)) }
        }
        advanceUntilIdle()
        job.join()

        assertEquals(4, recorded.size)

        // First line: partial then final, final lands exactly one interval in.
        assertFalse(recorded[0].segment.isFinal)
        assertEquals(lines[0].take(lines[0].length / 2), recorded[0].segment.text)
        assertTrue(recorded[1].segment.isFinal)
        assertEquals(lines[0], recorded[1].segment.text)
        assertEquals(interval.toMillis(), recorded[1].atMillis)
        assertTrue(recorded[0].atMillis < recorded[1].atMillis)

        // Second line: another full interval later, same partial/final shape.
        assertFalse(recorded[2].segment.isFinal)
        assertEquals(lines[1].take(lines[1].length / 2), recorded[2].segment.text)
        assertTrue(recorded[3].segment.isFinal)
        assertEquals(lines[1], recorded[3].segment.text)
        assertEquals(2 * interval.toMillis(), recorded[3].atMillis)
        assertTrue(recorded[2].atMillis < recorded[3].atMillis)
    }

    @Test
    fun `an empty script completes without emitting anything`() = runTest {
        val engine = ScriptedSpeechEngine(emptyList(), Duration.ofSeconds(1))

        val recorded = mutableListOf<TranscriptSegment>()
        val job = launch { engine.transcribe().collect { recorded.add(it) } }
        advanceUntilIdle()
        job.join()

        assertTrue(recorded.isEmpty())
    }
}
