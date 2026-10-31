package app.debunkax.session

import app.debunkax.domain.Explanation
import app.debunkax.domain.Source
import app.debunkax.domain.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExplanationLoaderTest {

    @Test
    fun `returns an existing Explanation without calling the client`() = runTest {
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val sessionId = history.startSession(Instant.EPOCH)
        val claimResult = result(Verdict.FALSE)
        val claimId = history.addClaim(sessionId, "Une affirmation deja verifiee.", Instant.EPOCH, claimResult)
        val existing = Explanation("Explication deja generee.", listOf(Source("AFP Factuel", "https://factuel.afp.com/x")))

        val loader = ExplanationLoader(history)
        val loaded = loader.load(claimId, "Une affirmation deja verifiee.", claimResult, existing, client)

        assertEquals(existing, loaded)
        assertTrue(client.explainCalls.isEmpty())
        assertTrue(history.setExplanationCalls.isEmpty())
    }

    @Test
    fun `generates and saves the Explanation when none exists`() = runTest {
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val sessionId = history.startSession(Instant.EPOCH)
        val claimResult = result(Verdict.MISLEADING)
        val claimText = "Une affirmation a expliquer."
        val claimId = history.addClaim(sessionId, claimText, Instant.EPOCH, claimResult)
        val generated = Explanation("Explication generee.", listOf(Source("Les Decodeurs", "https://lemonde.fr/les-decodeurs/x")))
        client.explanationToReturn = generated

        val loader = ExplanationLoader(history)
        val loaded = loader.load(claimId, claimText, claimResult, null, client)

        assertEquals(generated, loaded)
        assertEquals(listOf(claimText to claimResult), client.explainCalls)
        assertEquals(generated, history.claims.getValue(claimId).explanation)
        assertEquals(listOf(claimId to generated), history.setExplanationCalls)
    }
}
