package app.debunkax.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * TF-IDF (word 1-2grams + char 3-5grams) + logistic regression Claim filter, trained on labelled
 * French TV-debate subtitles (see `spikes/claims/RESULTS.md`). Pure Kotlin/JVM, no Android
 * dependency, so it is exercised directly from JVM unit tests.
 *
 * Reproduces scikit-learn's `TfidfVectorizer` + `LogisticRegression` scoring exactly: see
 * `spikes/claims/train_tfidf.py` and `exports/tfidf_lr_export.json` (its "preprocessing" and
 * "scoring" fields) for the reference computation this ports. In short: normalize the sentence
 * the same way as [RepeatedClaimMatcher.normalize], compute a smooth, sublinear (`1 + ln(tf)`)
 * TF-IDF vector per block (word n-grams, char n-grams), L2-normalize each block separately,
 * concatenate word-then-char, dot with the logistic regression's coefficients plus intercept,
 * then the logistic function, compared against the trained threshold.
 */
class TfidfClaimDetector private constructor(
    private val wordBlock: NgramBlock,
    private val charBlock: NgramBlock,
    private val coef: DoubleArray,
    private val intercept: Double,
    private val threshold: Double,
) : ClaimDetector {

    override fun isClaim(sentence: String): Boolean = score(sentence) >= threshold

    /** Claim probability in [0, 1]; exposed for tests. */
    fun score(sentence: String): Double {
        val text = RepeatedClaimMatcher.normalize(sentence)
        var dot = intercept
        for ((index, value) in wordBlock.tfidf(text)) dot += value * coef[index]
        val charOffset = wordBlock.vocabSize
        for ((index, value) in charBlock.tfidf(text)) dot += value * coef[charOffset + index]
        return 1.0 / (1.0 + exp(-dot))
    }

    /** One of the two sub-vectorizers (word or char), with its own vocabulary and IDF weights. */
    private class NgramBlock(
        private val vocab: Map<String, Int>,
        private val idf: DoubleArray,
        private val minN: Int,
        private val maxN: Int,
        private val isChar: Boolean,
    ) {
        val vocabSize: Int get() = idf.size

        /** Sparse, L2-normalized TF-IDF vector as (vocab index -> weight) for in-vocabulary n-grams. */
        fun tfidf(text: String): Map<Int, Double> {
            val counts = HashMap<Int, Int>()
            for (gram in ngrams(text)) {
                val index = vocab[gram] ?: continue
                counts[index] = (counts[index] ?: 0) + 1
            }
            if (counts.isEmpty()) return emptyMap()
            val weights = HashMap<Int, Double>(counts.size)
            var normSquared = 0.0
            for ((index, count) in counts) {
                val weight = (1.0 + ln(count.toDouble())) * idf[index]
                weights[index] = weight
                normSquared += weight * weight
            }
            if (normSquared == 0.0) return emptyMap()
            val norm = sqrt(normSquared)
            for (entry in weights.entries) entry.setValue(entry.value / norm)
            return weights
        }

        private fun ngrams(text: String): List<String> = if (isChar) charNgrams(text) else wordNgrams(text)

        /** Port of sklearn's `CountVectorizer._word_ngrams` for `analyzer="word"`. */
        private fun wordNgrams(text: String): List<String> {
            val tokens = text.split(' ').filter { it.isNotEmpty() }
            if (maxN == 1) return tokens
            val n = tokens.size
            val result = if (minN == 1) ArrayList(tokens) else ArrayList()
            val start = if (minN == 1) 2 else minN
            for (k in start..minOf(maxN, n)) {
                for (i in 0..(n - k)) {
                    result.add(tokens.subList(i, i + k).joinToString(" "))
                }
            }
            return result
        }

        /** Port of sklearn's `CountVectorizer._char_ngrams` for `analyzer="char"` (not `char_wb`: no word-boundary padding). */
        private fun charNgrams(text: String): List<String> {
            val len = text.length
            val result = ArrayList<String>()
            val start: Int
            if (minN == 1) {
                for (c in text) result.add(c.toString())
                start = 2
            } else {
                start = minN
            }
            for (n in start..minOf(maxN, len)) {
                for (i in 0..(len - n)) {
                    result.add(text.substring(i, i + n))
                }
            }
            return result
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(source: String): TfidfClaimDetector {
            val export = json.decodeFromString<TfidfExportJson>(source)
            val word = NgramBlock(
                vocab = export.word.vocab,
                idf = export.word.idf.toDoubleArray(),
                minN = export.word.ngram_range[0],
                maxN = export.word.ngram_range[1],
                isChar = false,
            )
            val char = NgramBlock(
                vocab = export.char.vocab,
                idf = export.char.idf.toDoubleArray(),
                minN = export.char.ngram_range[0],
                maxN = export.char.ngram_range[1],
                isChar = true,
            )
            return TfidfClaimDetector(
                wordBlock = word,
                charBlock = char,
                coef = export.coef.toDoubleArray(),
                intercept = export.intercept,
                threshold = export.threshold,
            )
        }
    }
}

@Serializable
private data class NgramBlockJson(
    val ngram_range: List<Int>,
    val vocab: Map<String, Int>,
    val idf: List<Double>,
)

@Serializable
private data class TfidfExportJson(
    val word: NgramBlockJson,
    val char: NgramBlockJson,
    val coef: List<Double>,
    val intercept: Double,
    val threshold: Double,
)
