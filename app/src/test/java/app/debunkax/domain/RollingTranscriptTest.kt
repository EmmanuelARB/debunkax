package app.debunkax.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingTranscriptTest {

    @Test
    fun `segments older than 30s are pruned`() {
        val transcript = RollingTranscript()
        val start = Instant.parse("2000-01-01T00:00:00Z")
        transcript.append("bonjour", start)

        val text = transcript.textAt(start.plusSeconds(31))

        assertEquals("", text)
    }

    @Test
    fun `segment just outside the 30s window is pruned, one just inside is kept`() {
        val transcript = RollingTranscript()
        val start = Instant.parse("2000-01-01T00:00:00Z")
        transcript.append("vieux", start)
        transcript.append("recent", start.plusMillis(1))

        val text = transcript.textAt(start.plusSeconds(30).plusMillis(1))

        assertEquals("recent", text)
    }

    @Test
    fun `blank text is ignored`() {
        val transcript = RollingTranscript()
        val start = Instant.parse("2000-01-01T00:00:00Z")
        transcript.append("bonjour", start)
        transcript.append("", start.plusSeconds(1))
        transcript.append("   ", start.plusSeconds(2))
        transcript.append("le monde", start.plusSeconds(3))

        val text = transcript.textAt(start.plusSeconds(3))

        assertEquals("bonjour le monde", text)
    }

    @Test
    fun `text joins in order`() {
        val transcript = RollingTranscript()
        val start = Instant.parse("2000-01-01T00:00:00Z")
        transcript.append("un", start)
        transcript.append("deux", start.plusSeconds(1))
        transcript.append("trois", start.plusSeconds(2))

        val text = transcript.textAt(start.plusSeconds(2))

        assertEquals("un deux trois", text)
    }

    @Test
    fun `clear removes all segments`() {
        val transcript = RollingTranscript()
        val start = Instant.parse("2000-01-01T00:00:00Z")
        transcript.append("bonjour", start)

        transcript.clear()

        assertEquals("", transcript.textAt(start))
    }
}
