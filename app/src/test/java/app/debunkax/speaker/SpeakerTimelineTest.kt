package app.debunkax.speaker

import app.debunkax.domain.SentenceTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SpeakerTimelineTest {
    private val t0 = Instant.parse("2000-01-01T20:00:00Z")
    private fun at(s: Long) = t0.plusSeconds(s)
    private fun named(n: String) = SpeakerGuess.Named(n, 0.7f)
    private val unsure = SpeakerGuess.Unknown("Marine Le Pen", 0.4f)

    private fun timeline(vararg guesses: Pair<Long, SpeakerGuess>) =
        SpeakerTimeline().apply { guesses.forEach { (s, g) -> add(at(s), g) } }

    @Test fun namesTheOnlyLeaderHeardDuringTheSentence() {
        val t = timeline(5L to named("Marine Le Pen"), 10L to named("Marine Le Pen"), 15L to SpeakerGuess.Silence)
        assertEquals("Marine Le Pen", t.speakerBetween(at(4), at(12)))
    }

    @Test fun unknownWhenTwoLeadersAreHeard() {
        val t = timeline(5L to named("Marine Le Pen"), 10L to named("Gabriel Attal"))
        assertNull(t.speakerBetween(at(4), at(12)))
    }

    @Test fun unknownWhenUnsureWindowsAreAsManyAsNamedOnes() {
        val t = timeline(5L to named("Marine Le Pen"), 10L to unsure)
        assertNull(t.speakerBetween(at(4), at(12)))
    }

    @Test fun unknownWhenNothingWasHeardThen() {
        val t = timeline(5L to named("Marine Le Pen"))
        assertNull(t.speakerBetween(at(30), at(40)))
    }

    @Test fun ignoresWindowsOutsideTheSentence() {
        val t = timeline(5L to named("Gabriel Attal"), 20L to named("Marine Le Pen"), 22L to named("Marine Le Pen"))
        assertEquals("Marine Le Pen", t.speakerBetween(at(17), at(22)))
    }

    private val journalists = setOf("Ruth Elkrief")
    private fun SpeakerTimeline.addressee(before: Long, claimant: String = "Marine Le Pen") =
        addresseeBefore(at(before), claimant) { it in journalists }

    @Test fun addresseeIsThePoliticianHeardBeforeTheClaimant() {
        val t = timeline(60L to named("Gabriel Attal"), 70L to named("Marine Le Pen"))
        assertEquals("Gabriel Attal", t.addressee(66))
    }

    @Test fun addresseeSkipsJournalistsAndTheClaimant() {
        val t = timeline(45L to named("Gabriel Attal"), 55L to named("Ruth Elkrief"), 70L to named("Marine Le Pen"))
        assertEquals("Gabriel Attal", t.addressee(66))
    }

    @Test fun addresseeIsNullWhenOnlyJournalistsSpokeBefore() {
        val t = timeline(60L to named("Ruth Elkrief"), 70L to named("Marine Le Pen"))
        assertNull(t.addressee(66))
    }

    @Test fun addresseeSearchStopsThirtySecondsBack() {
        val t = timeline(25L to named("Gabriel Attal"), 70L to named("Marine Le Pen"))
        assertNull(t.addressee(66)) // Attal spoke 40 s earlier
        val near = timeline(40L to named("Gabriel Attal"), 70L to named("Marine Le Pen"))
        assertEquals("Gabriel Attal", near.addressee(66)) // 26 s earlier
    }

    @Test fun addresseeUsesTheMostRecentPoliticianAndTheDoubtRule() {
        val t = timeline(45L to named("Bruno Retailleau"), 55L to named("Gabriel Attal"), 60L to named("Bruno Retailleau"))
        assertEquals("Bruno Retailleau", t.addressee(66)) // the most recent window that names one person
        val doubt = timeline(50L to named("Gabriel Attal"), 58L to named("Gabriel Attal"), 60L to unsure)
        assertEquals("Gabriel Attal", doubt.addressee(66))
    }

    @Test fun sentencesArePlacedBackFromTheSegmentEnd() {
        val spans = SentenceTiming.spans(listOf("a".repeat(150), "b".repeat(15)), at(100))
        assertEquals(at(98) to at(100), spans[1]) // 1 s of text, but at least 2 s
        assertEquals(at(88) to at(98), spans[0])
        assertEquals(Duration.ofSeconds(12), Duration.between(spans[0].first, spans[1].second))
    }
}
