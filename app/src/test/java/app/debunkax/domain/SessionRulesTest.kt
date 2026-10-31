package app.debunkax.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    private val heardAt: Instant = Instant.parse("2000-01-01T00:00:00Z")

    @Test
    fun `FALSE MISLEADING and LACKS_CONTEXT alert, UNVERIFIABLE and ACCURATE never do`() {
        val readyAt = heardAt.plusSeconds(1)

        assertTrue(AlertPolicy.shouldAlert(Verdict.FALSE, heardAt, readyAt))
        assertTrue(AlertPolicy.shouldAlert(Verdict.MISLEADING, heardAt, readyAt))
        assertTrue(AlertPolicy.shouldAlert(Verdict.LACKS_CONTEXT, heardAt, readyAt))
        assertFalse(AlertPolicy.shouldAlert(Verdict.UNVERIFIABLE, heardAt, readyAt))
        assertFalse(AlertPolicy.shouldAlert(Verdict.ACCURATE, heardAt, readyAt))
    }

    @Test
    fun `exactly 15s still alerts`() {
        val readyAt = heardAt.plusSeconds(15)

        assertTrue(AlertPolicy.shouldAlert(Verdict.FALSE, heardAt, readyAt))
    }

    @Test
    fun `15point001s does not alert`() {
        val readyAt = heardAt.plusMillis(15_001)

        assertFalse(AlertPolicy.shouldAlert(Verdict.FALSE, heardAt, readyAt))
    }
}

class CheckBudgetTest {

    @Test
    fun `null limit is unlimited`() {
        val budget = CheckBudget(null)

        repeat(50) {
            assertTrue(budget.tryConsume())
        }
        assertFalse(budget.exhausted)
    }

    @Test
    fun `limit of 2 allows exactly 2`() {
        val budget = CheckBudget(2)

        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
        assertTrue(budget.exhausted)
        assertEquals(2, budget.used)
    }
}

class RepeatedClaimMatcherTest {

    private val result = CheckResult(Verdict.FALSE, "contexte", emptyList())

    @Test
    fun `normalization equates thousands separator variants`() {
        assertEquals(
            RepeatedClaimMatcher.normalize("il y a 1 000 personnes"),
            RepeatedClaimMatcher.normalize("il y a 1000 personnes"),
        )
    }

    @Test
    fun `normalization strips accents`() {
        assertEquals(
            RepeatedClaimMatcher.normalize("le chômage a augmenté"),
            RepeatedClaimMatcher.normalize("le chomage a augmente"),
        )
    }

    @Test
    fun `normalization equates curly and straight apostrophes`() {
        assertEquals(
            RepeatedClaimMatcher.normalize("aujourd’hui il pleut"),
            RepeatedClaimMatcher.normalize("aujourd'hui il pleut"),
        )
    }

    @Test
    fun `normalization is case-insensitive`() {
        assertEquals(
            RepeatedClaimMatcher.normalize("LE CHÔMAGE A BAISSÉ"),
            RepeatedClaimMatcher.normalize("le chômage a baissé"),
        )
    }

    @Test
    fun `a reworded repeat of a claim reuses the verdict`() {
        val matcher = RepeatedClaimMatcher()
        matcher.remember("le chômage a baissé de 10 % depuis 2017", result)

        val found = matcher.find("depuis 2017 le chômage a baissé de 10 %")

        assertEquals(result, found)
    }

    @Test
    fun `a different figure does not match`() {
        val matcher = RepeatedClaimMatcher()
        matcher.remember("le chômage a baissé de 10 % depuis 2017", result)

        val found = matcher.find("le chômage a baissé de 20 % depuis 2017")

        assertEquals(null, found)
    }

    @Test
    fun `clear forgets everything`() {
        val matcher = RepeatedClaimMatcher()
        matcher.remember("le chômage a baissé de 10 % depuis 2017", result)

        matcher.clear()

        assertEquals(null, matcher.find("le chômage a baissé de 10 % depuis 2017"))
    }
}
