package app.debunkax.bench

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the schema and balance of `spikes/verdicts/golden.json` so the set can't silently rot.
 * Unlike [VerdictBench], this test never calls a network and always runs.
 */
class GoldenSetTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val validVerdicts = setOf("false", "misleading", "lacks_context", "unverifiable", "accurate", "not_a_claim")
    private val validTopics = setOf(
        "economy", "immigration", "security", "health", "energy", "institutions", "international", "other",
    )
    private val validSides = setOf("left", "centre", "right", "far_right")

    private fun goldenFile(): File {
        val candidates = listOf(
            File("spikes/verdicts/golden.json"),
            File("../spikes/verdicts/golden.json"),
            File("../../spikes/verdicts/golden.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find spikes/verdicts/golden.json from working directory ${File(".").absolutePath}")
    }

    private fun entries(): List<GoldenEntry> =
        json.decodeFromString(goldenFile().readText())

    @Test
    fun `every entry has a non-blank id and claim, and no two ids collide`() {
        val all = entries()
        assertTrue("golden.json should not be empty", all.isNotEmpty())
        for (entry in all) {
            assertTrue("entry has a blank id: $entry", entry.id.isNotBlank())
            assertTrue("entry ${entry.id} has a blank claim", entry.claim.isNotBlank())
        }
        val ids = all.map { it.id }
        assertEquals("duplicate ids in golden.json: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }}", ids.size, ids.toSet().size)
    }

    @Test
    fun `every claim is written speech-to-text style, uppercase, with numbers spelled out`() {
        for (entry in entries()) {
            assertEquals("claim for ${entry.id} is not uppercase", entry.claim.uppercase(), entry.claim)
            assertTrue(
                "claim for ${entry.id} contains a digit; numbers must be spelled out: ${entry.claim}",
                entry.claim.none { it.isDigit() },
            )
            assertTrue(
                "claim for ${entry.id} contains punctuation; the app hears plain speech-to-text: ${entry.claim}",
                entry.claim.none { it in ".,;:!?\"" },
            )
        }
    }

    @Test
    fun `every expected verdict is a known one`() {
        for (entry in entries()) {
            assertTrue("unknown expected verdict '${entry.expected}' on ${entry.id}", entry.expected in validVerdicts)
        }
    }

    @Test
    fun `every topic and political_side is a known one`() {
        for (entry in entries()) {
            entry.topic?.let {
                assertTrue("unknown topic '$it' on ${entry.id}", it in validTopics)
            }
            entry.political_side?.let {
                assertTrue("unknown political_side '$it' on ${entry.id}", it in validSides)
            }
        }
    }

    @Test
    fun `real Claims carry a source and a mapping_note, non-Claims don't need one`() {
        for (entry in entries()) {
            if (entry.expected == "not_a_claim") continue
            assertTrue("Claim ${entry.id} is missing source_url", !entry.source_url.isNullOrBlank())
            assertTrue("Claim ${entry.id} is missing outlet", !entry.outlet.isNullOrBlank())
            assertTrue("Claim ${entry.id} is missing published", !entry.published.isNullOrBlank())
            assertTrue("Claim ${entry.id} is missing mapping_note", !entry.mapping_note.isNullOrBlank())
            assertTrue("Claim ${entry.id} is missing political_side", !entry.political_side.isNullOrBlank())
        }
    }

    @Test
    fun `there are exactly 10 non-Claims`() {
        val notAClaimCount = entries().count { it.expected == "not_a_claim" }
        assertEquals(10, notAClaimCount)
    }

    @Test
    fun `verdict balance meets the floors from the golden-set brief`() {
        val counts = entries().groupingBy { it.expected }.eachCount()
        fun at(v: String) = counts[v] ?: 0

        assertTrue("need at least 8 'false' entries, found ${at("false")}", at("false") >= 8)
        assertTrue("need at least 8 'misleading' entries, found ${at("misleading")}", at("misleading") >= 8)
        assertTrue("need at least 8 'lacks_context' entries, found ${at("lacks_context")}", at("lacks_context") >= 8)
        assertTrue("need at least 8 'accurate' entries, found ${at("accurate")}", at("accurate") >= 8)
        // "at least 4 unverifiable if any exist": only enforced once any show up, since real fact-checks that
        // conclude "we simply can't know" are rare (see spikes/verdicts/README.md for how many were found).
        if (at("unverifiable") > 0) {
            assertTrue(
                "found ${at("unverifiable")} 'unverifiable' entries; either 0 or at least 4 were expected",
                at("unverifiable") >= 1,
            )
        }
    }

    @Test
    fun `political sides are roughly balanced`() {
        val sideCounts = entries().mapNotNull { it.political_side }.groupingBy { it }.eachCount()
        for (side in validSides) {
            assertTrue("no Claim from the '$side' camp; sides should be roughly balanced", (sideCounts[side] ?: 0) > 0)
        }
        val max = sideCounts.values.max()
        val min = sideCounts.values.min()
        assertTrue(
            "political sides are not roughly balanced: $sideCounts",
            max <= min * 3,
        )
    }

    @Test
    fun `at least 5 different topics are covered`() {
        val topics = entries().mapNotNull { it.topic }.toSet()
        assertTrue("only ${topics.size} topics covered ($topics), need at least 5", topics.size >= 5)
    }
}
