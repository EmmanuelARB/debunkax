package app.debunkax.speaker

import java.time.Duration
import java.time.Instant

/**
 * The Speaker guesses of the last couple of minutes, each for the [window] of audio ending at its
 * time. Holds names and scores only, never audio, and lives in memory for one Listening Session.
 */
class SpeakerTimeline(
    private val window: Duration = Duration.ofSeconds(5),
    private val keep: Duration = Duration.ofMinutes(2),
) {
    private val guesses = ArrayDeque<Pair<Instant, SpeakerGuess>>()

    companion object {
        /** The transcript window: what a « vous » can still refer to. */
        val ADDRESSEE_LOOKBACK: Duration = Duration.ofSeconds(30)
    }

    @Synchronized
    fun add(end: Instant, guess: SpeakerGuess) {
        guesses.addLast(end to guess)
        while (guesses.first().first < end - keep) guesses.removeFirst()
    }

    /**
     * Who spoke between [from] and [to], or null when in doubt: nobody heard, two leaders named,
     * or as many unsure windows as named ones. A wrong name is worse than none.
     */
    @Synchronized
    fun speakerBetween(from: Instant, to: Instant): String? {
        val heard = guesses
            .filter { (end, _) -> end > from && end - window < to }
            .map { it.second }
            .filter { it !is SpeakerGuess.Silence }
        val named = heard.filterIsInstance<SpeakerGuess.Named>()
        val name = named.map { it.name }.distinct().singleOrNull() ?: return null
        return name.takeIf { named.size * 2 > heard.size }
    }

    /**
     * The last named person other than [claimant] and the [isJournalist] ones in the [ADDRESSEE_LOOKBACK]
     * before [before], searched in [window] steps from the most recent, each with the [speakerBetween] doubt rule.
     */
    fun addresseeBefore(before: Instant, claimant: String, isJournalist: (String) -> Boolean): String? {
        var end = before
        val oldest = before - ADDRESSEE_LOOKBACK
        while (end > oldest) {
            speakerBetween(end - window, end)?.let { if (it != claimant && !isJournalist(it)) return it }
            end -= window
        }
        return null
    }

    @Synchronized
    fun clear() = guesses.clear()
}
