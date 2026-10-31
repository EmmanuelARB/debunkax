package app.debunkax.speech

import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.TranscriptSegment
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FallbackSpeechEngineTest {

    private fun segment(text: String) = TranscriptSegment(text, isFinal = true, at = Instant.EPOCH)

    private class FakeEngine(private val behavior: suspend kotlinx.coroutines.flow.FlowCollector<TranscriptSegment>.() -> Unit) :
        SpeechEngine {
        var timesStarted = 0
        override fun transcribe(): Flow<TranscriptSegment> = flow {
            timesStarted++
            behavior()
        }
    }

    @Test
    fun `stays on the primary engine while it works`() = runTest {
        val primary = FakeEngine { emit(segment("PRIMAIRE")) }
        val fallback = FakeEngine { emit(segment("SECOURS")) }
        val engine = FallbackSpeechEngine(primary, fallback)

        val recorded = mutableListOf<TranscriptSegment>()
        val job = launch { engine.transcribe().collect { recorded.add(it) } }
        advanceUntilIdle()
        job.join()

        assertEquals(listOf(segment("PRIMAIRE")), recorded)
        assertEquals(1, primary.timesStarted)
        assertEquals(0, fallback.timesStarted)
    }

    @Test
    fun `switches to the fallback once the primary reports Unsupported, and stays switched`() = runTest {
        val primary = FakeEngine { throw SpeechFailure.Unsupported("pas pris en charge") }
        val fallback = FakeEngine { emit(segment("SECOURS")) }
        val engine = FallbackSpeechEngine(primary, fallback)

        val firstRun = mutableListOf<TranscriptSegment>()
        var job = launch { engine.transcribe().collect { firstRun.add(it) } }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(segment("SECOURS")), firstRun)
        assertEquals(1, primary.timesStarted)
        assertEquals(1, fallback.timesStarted)

        // A second session (e.g. after the user stops and restarts listening) never retries the primary.
        val secondRun = mutableListOf<TranscriptSegment>()
        job = launch { engine.transcribe().collect { secondRun.add(it) } }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(segment("SECOURS")), secondRun)
        assertEquals(1, primary.timesStarted)
        assertEquals(2, fallback.timesStarted)
    }

    @Test
    fun `a non-Unsupported failure from the primary is not swallowed and the fallback never starts`() = runTest {
        val primary = FakeEngine { throw SpeechFailure.Engine("erreur moteur") }
        val fallback = FakeEngine { emit(segment("SECOURS")) }
        val engine = FallbackSpeechEngine(primary, fallback)

        var thrown: Throwable? = null
        val job = launch {
            try {
                engine.transcribe().collect {}
            } catch (e: Throwable) {
                thrown = e
            }
        }
        advanceUntilIdle()
        job.join()

        assertTrue(thrown is SpeechFailure.Engine)
        assertEquals(0, fallback.timesStarted)
    }
}
