package app.debunkax.domain

import java.text.Normalizer
import java.time.Duration
import java.time.Instant

object AlertPolicy {
    val maxDelay: Duration = Duration.ofSeconds(15)

    fun shouldAlert(verdict: Verdict, heardAt: Instant, readyAt: Instant): Boolean =
        verdict.raisesAlert && Duration.between(heardAt, readyAt) <= maxDelay
}

/** Counts Checks within one Listening Session; [limit] null means unlimited. */
class CheckBudget(private val limit: Int?) {
    var used: Int = 0
        private set

    val exhausted: Boolean get() = limit != null && used >= limit

    @Synchronized
    fun tryConsume(): Boolean {
        if (exhausted) return false
        used++
        return true
    }
}

/**
 * Reuses a Verdict when a Claim repeats within the same Listening Session.
 * Politicians repeat figures with slightly different wording, hence token similarity rather than equality.
 */
class RepeatedClaimMatcher(private val threshold: Double = 0.8) {
    private val known = mutableListOf<Pair<Set<String>, CheckResult>>()

    @Synchronized
    fun find(text: String): CheckResult? {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return null
        return known.firstOrNull { (other, _) -> jaccard(tokens, other) >= threshold }?.second
    }

    @Synchronized
    fun remember(text: String, result: CheckResult) {
        val tokens = tokens(text)
        if (tokens.isNotEmpty()) known += tokens to result
    }

    @Synchronized
    fun clear() = known.clear()

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        a.intersect(b).size.toDouble() / a.union(b).size

    companion object {
        fun tokens(text: String): Set<String> =
            normalize(text).split(' ').filter { it.length > 1 || it.any(Char::isDigit) }.toSet()

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[’']"), " ")
                .replace(Regex("(?<=\\d)[\\s.](?=\\d{3}\\b)"), "")
                .replace(Regex("[^\\p{L}\\p{N}%,]+"), " ")
                .replace(Regex("(?<!\\d),|,(?!\\d)"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
    }
}
