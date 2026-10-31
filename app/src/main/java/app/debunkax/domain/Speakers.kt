package app.debunkax.domain

import java.time.Duration
import java.time.Instant

/** Recognises Speakers by voice during a Listening Session. Keeps names in memory only, never audio. */
interface SpeakerRecognition {
    fun start()
    fun stop()

    /** The Speaker heard between [from] and [to], or null when unknown or in doubt. */
    fun speakerBetween(from: Instant, to: Instant): String?

    /**
     * The last politician other than [claimant] heard in the transcript window before [before], or
     * null. Journalists are skipped: they ask questions, they are rarely the person a « vous » is aimed at.
     */
    fun addresseeBefore(before: Instant, claimant: String): String? = null
}

/** Whether a sentence speaks to someone: « tu », « vous » and their possessives and object forms. */
object SecondPerson {
    private val WORDS = Regex("(?<![\\p{L}\\p{N}])(?:tu|te|toi|ton|ta|tes|vous|votre|vos|t(?=['’]))(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun isIn(sentence: String): Boolean = WORDS.containsMatchIn(sentence)
}

/**
 * When each sentence of a final segment was probably said. Engines only time the segment's end, and
 * ML Kit can finalise a minute of speech at once, so each sentence is placed back by its length.
 */
object SentenceTiming {
    /** French TV speech runs at about 15 characters a second. */
    private const val CHARS_PER_SECOND = 15.0
    private val MIN = Duration.ofSeconds(2)

    fun spans(sentences: List<String>, endAt: Instant): List<Pair<Instant, Instant>> {
        var end = endAt
        return sentences.asReversed().map { sentence ->
            val start = end - maxOf(MIN, Duration.ofMillis((sentence.length / CHARS_PER_SECOND * 1000).toLong()))
            (start to end).also { end = start }
        }.asReversed()
    }
}
