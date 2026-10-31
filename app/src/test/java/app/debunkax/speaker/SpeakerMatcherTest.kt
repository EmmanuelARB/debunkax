package app.debunkax.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerMatcherTest {
    private val speakers = listOf(
        EnrolledSpeaker("A", listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))),
        EnrolledSpeaker("B", listOf(floatArrayOf(0f, 1f, 0f))),
    )

    @Test fun namesTheClosestWhenClearlyAhead() {
        assertEquals(SpeakerGuess.Named("A", 1f), SpeakerMatcher.guess(floatArrayOf(0f, 0f, 2f), speakers))
    }

    @Test fun unknownBelowThreshold() {
        val guess = SpeakerMatcher.guess(floatArrayOf(1f, 0.2f, 1f), speakers, threshold = 0.9f)
        assertTrue(guess is SpeakerGuess.Unknown && guess.closest == "A")
    }

    @Test fun unknownWhenTwoPeopleAreTooClose() {
        val guess = SpeakerMatcher.guess(floatArrayOf(1f, 0.95f, 0f), speakers, threshold = 0.5f)
        assertTrue(guess is SpeakerGuess.Unknown)
    }
}
