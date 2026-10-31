package app.debunkax.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CheckPromptsTest {

    @Test
    fun `parseCheck reads plain JSON`() {
        val raw = """
            {"is_claim": true, "verdict": "false", "evidence": "Le taux est de 7,4 %.", "context": "Contredit par l'INSEE.",
             "sources": [{"title": "INSEE", "url": "https://insee.fr/x"}]}
        """.trimIndent()

        val outcome = CheckPrompts.parseCheck(raw) as CheckOutcome.Checked

        assertEquals(Verdict.FALSE, outcome.result.verdict)
        assertEquals("Contredit par l'INSEE.", outcome.result.context)
        assertEquals(listOf(Source("INSEE", "https://insee.fr/x")), outcome.result.sources)
    }

    @Test
    fun `parseCheck reads JSON wrapped in markdown fences with surrounding prose`() {
        val raw = """
            Voici le résultat de la vérification :
            ```json
            {"is_claim": true, "verdict": "misleading", "evidence": "Hors outre-mer.", "context": "Trompeur.", "sources": []}
            ```
            N'hésitez pas à demander plus de détails.
        """.trimIndent()

        val outcome = CheckPrompts.parseCheck(raw) as CheckOutcome.Checked

        assertEquals(Verdict.MISLEADING, outcome.result.verdict)
    }

    @Test
    fun `an Alert Verdict with no source evidence becomes unverifiable`() {
        for (verdict in listOf("false", "misleading", "lacks_context")) {
            val raw = """{"is_claim": true, "verdict": "$verdict", "evidence": " ", "context": "Aucune source ne le confirme.", "sources": []}"""

            val outcome = CheckPrompts.parseCheck(raw) as CheckOutcome.Checked

            assertEquals(Verdict.UNVERIFIABLE, outcome.result.verdict)
        }
        val accurate = CheckPrompts.parseCheck("""{"is_claim": true, "verdict": "accurate", "context": "Confirmé.", "sources": []}""")
        assertEquals(Verdict.ACCURATE, (accurate as CheckOutcome.Checked).result.verdict)
    }

    @Test
    fun `parseCheck with is_claim false gives NotAClaim`() {
        val outcome = CheckPrompts.parseCheck("""{"is_claim": false}""")

        assertEquals(CheckOutcome.NotAClaim, outcome)
    }

    @Test
    fun `parseCheck with an unknown verdict throws Unexpected`() {
        val raw = """{"is_claim": true, "verdict": "bogus", "context": "x", "sources": []}"""

        try {
            CheckPrompts.parseCheck(raw)
            fail("expected CheckFailure.Unexpected")
        } catch (e: CheckFailure.Unexpected) {
            // expected
        }
    }

    @Test
    fun `parseCheck with no JSON throws`() {
        try {
            CheckPrompts.parseCheck("Désolé, je ne peux pas répondre.")
            fail("expected CheckFailure.Unexpected")
        } catch (e: CheckFailure.Unexpected) {
            // expected
        }
    }

    @Test
    fun `a source without a title falls back to its URL`() {
        val raw = """
            {"is_claim": true, "verdict": "accurate", "context": "x",
             "sources": [{"url": "https://afp.fr/y"}]}
        """.trimIndent()

        val outcome = CheckPrompts.parseCheck(raw) as CheckOutcome.Checked

        assertEquals(listOf(Source("https://afp.fr/y", "https://afp.fr/y")), outcome.result.sources)
    }

    @Test
    fun `parseTriage returns the rewritten Claim, or null when it isn't one`() {
        assertEquals(
            "L'ISF rapportait 5 milliards d'euros.",
            CheckPrompts.parseTriage("""```json\n{"is_claim": true, "claim": " L'ISF rapportait 5 milliards d'euros. "}\n```"""),
        )
        assertNull(CheckPrompts.parseTriage("""{"is_claim": false}"""))
        assertNull(CheckPrompts.parseTriage("""{"is_claim": true, "claim": ""}"""))
    }

    @Test
    fun `parseExplanation reads text and sources`() {
        val raw = """
            {"text": "Explication détaillée.", "sources": [{"title": "Les Décodeurs", "url": "https://lemonde.fr/z"}]}
        """.trimIndent()

        val explanation = CheckPrompts.parseExplanation(raw)

        assertEquals("Explication détaillée.", explanation.text)
        assertEquals(listOf(Source("Les Décodeurs", "https://lemonde.fr/z")), explanation.sources)
    }

    @Test
    fun `wireName round-trips every verdict`() {
        for (verdict in Verdict.entries) {
            val wire = CheckPrompts.wireName(verdict)
            val recovered = Verdict.entries.first { CheckPrompts.wireName(it) == wire }
            assertEquals(verdict, recovered)
        }
    }

    @Test
    fun `checkSystem contains the given date`() {
        val today = LocalDate.of(2026, 9, 23)

        val system = CheckPrompts.checkSystem(today)

        assertTrue(system.contains(today.toString()))
    }

    @Test
    fun `the Speaker is given to triage and Check only when recognised`() {
        val known = HeardClaim("Nous avons baissé les impôts.", java.time.Instant.EPOCH, "Avant.", speaker = "Marine Le Pen")
        val unknown = known.copy(speaker = null)
        for (prompt in listOf(CheckPrompts::triageUser, CheckPrompts::checkUser, CheckPrompts::checkSurroundings)) {
            assertTrue(prompt(known).startsWith("Orateur (reconnu à sa voix) : Marine Le Pen"))
            assertFalse(prompt(unknown).contains("Orateur"))
        }
    }

    @Test
    fun `the addressee is given to triage and Check only when known, and the rules name « tu » and « vous »`() {
        val addressed = HeardClaim(
            "Vous avez vote la loi.", java.time.Instant.EPOCH, "Avant.",
            speaker = "Marine Le Pen", addressedSpeaker = "Ruth Elkrief",
        )
        val unaddressed = addressed.copy(addressedSpeaker = null)
        for (prompt in listOf(CheckPrompts::triageUser, CheckPrompts::checkUser, CheckPrompts::checkSurroundings)) {
            assertTrue(prompt(addressed).contains("S'adresse probablement à (dernier autre responsable politique entendu, reconnu à sa voix) : Ruth Elkrief"))
            assertFalse(prompt(unaddressed).contains("S'adresse"))
        }
        val system = CheckPrompts.checkSystem(LocalDate.of(2026, 9, 26))
        assertTrue(system.contains("« S'adresse probablement à »"))
        assertTrue(system.contains("un interlocuteur qui reste incertain, le verdict est \"unverifiable\""))
        assertTrue(CheckPrompts.triageSystem.contains("« S'adresse probablement à »"))
        // Guessing the Speaker stays forbidden, and the « tu » / « vous » clause is a separate sentence.
        assertTrue(CheckPrompts.triageSystem.contains("Sinon, ne devine jamais qui parle et laisse ces pronoms tels quels. Remplace « tu » et « vous »"))
    }

    @Test
    fun `the Check rules discount articles that only report the Speaker's words, and opinion pieces`() {
        val system = CheckPrompts.checkSystem(LocalDate.of(2026, 9, 29))

        assertTrue(system.contains("Un article qui ne fait que rapporter les propos de l'orateur ne confirme pas l'affirmation"))
        assertTrue(system.contains("Les tribunes, éditoriaux et chroniques sont des opinions, pas des sources factuelles."))
    }

    @Test
    fun `a Speaker talking about themselves is checked against their own record`() {
        val system = CheckPrompts.checkSystem(java.time.LocalDate.of(2026, 9, 26))

        assertTrue(system.contains("déclarations, votes et actes passés"))
    }
}
