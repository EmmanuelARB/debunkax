package app.debunkax.domain

import java.io.File
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Parity with the Python reference model (spikes/claims/exports/tfidf_lr_export.json, scored by
 * spikes/claims/make_parity_fixture.py into app/src/test/resources/claim_parity.json): every
 * sentence's Kotlin score must match Python's within 1e-6, and the Claim/non-Claim decision
 * (score >= threshold) must match exactly.
 *
 * The input mirrors real speech-to-text output: uppercase, no punctuation, spelled-out numbers.
 */
class TfidfClaimDetectorTest {

    companion object {
        private const val MAX_ABS_DIFF = 1e-6
        private val json = Json { ignoreUnknownKeys = true }

        private lateinit var detector: TfidfClaimDetector

        @BeforeClass
        @JvmStatic
        fun loadDetector() {
            val modelJson = File("src/main/assets/claim_model.json").readText()
            detector = TfidfClaimDetector.fromJson(modelJson)
        }
    }

    @Serializable
    private data class ParityCase(val sentence: String, val probability: Double)

    @Serializable
    private data class ParityFixture(val threshold: Double, val cases: List<ParityCase>)

    private fun loadFixture(): ParityFixture {
        val classLoader = requireNotNull(javaClass.classLoader) { "no class loader available" }
        val stream = requireNotNull(classLoader.getResourceAsStream("claim_parity.json")) {
            "claim_parity.json not found on the test classpath"
        }
        val text = stream.bufferedReader().use { it.readText() }
        return json.decodeFromString(ParityFixture.serializer(), text)
    }

    @Test
    fun `every fixture sentence's Kotlin score matches Python's within 1e-6, decisions match exactly`() {
        val fixture = loadFixture()
        assertTrue("fixture should have a healthy number of cases", fixture.cases.size >= 100)

        var maxDiff = 0.0
        val mismatchedDecisions = mutableListOf<String>()

        for (case in fixture.cases) {
            val kotlinScore = detector.score(case.sentence)
            val diff = abs(kotlinScore - case.probability)
            maxDiff = maxOf(maxDiff, diff)
            assertTrue(
                "score mismatch for \"${case.sentence}\": kotlin=$kotlinScore python=${case.probability} diff=$diff",
                diff <= MAX_ABS_DIFF,
            )

            val pythonDecision = case.probability >= fixture.threshold
            val kotlinDecision = detector.isClaim(case.sentence)
            if (pythonDecision != kotlinDecision) mismatchedDecisions += case.sentence
        }

        assertTrue("decision mismatches: $mismatchedDecisions", mismatchedDecisions.isEmpty())
        assertTrue("max abs diff should stay well under the tolerance, was $maxDiff", maxDiff <= MAX_ABS_DIFF)
    }

    @Test
    fun `narco trafic operations figure is a claim`() {
        assertTrue(
            detector.isClaim(
                "LES OPERATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTE VINGT QUATRE POURCENT ENTRE " +
                    "DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS L'ENSEMBLE DES ALPES MARITIMES"
            )
        )
    }

    @Test
    fun `debt has exploded is a claim`() {
        assertTrue(detector.isClaim("LA DETTE PUBLIQUE A EXPLOSE SOUS CE GOUVERNEMENT ET DEPASSE TROIS MILLE MILLIARDS D'EUROS"))
    }

    @Test
    fun `courtesy address is not a claim`() {
        assertFalse(detector.isClaim("MERCI MADAME LA PRESIDENTE MONSIEUR LE DEPUTE"))
    }

    @Test
    fun `admonition is not a claim`() {
        assertFalse(detector.isClaim("VOUS DEVRIEZ AVOIR HONTE"))
    }

    @Test
    fun `score is deterministic and within probability bounds`() {
        val sentence = "LE CHOMAGE A BAISSE DE VINGT POURCENT EN FRANCE DEPUIS DEUX MILLE VINGT"
        val first = detector.score(sentence)
        val second = detector.score(sentence)
        assertEquals(first, second, 0.0)
        assertTrue(first in 0.0..1.0)
    }
}
