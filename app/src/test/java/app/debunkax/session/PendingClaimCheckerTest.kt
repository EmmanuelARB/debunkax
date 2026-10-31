package app.debunkax.session

import app.debunkax.domain.CheckFailure
import app.debunkax.domain.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PendingClaimCheckerTest {

    @Test
    fun `checkAll sets results for Pending Claims, checked on their own text`() = runTest {
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val sessionId = history.startSession(Instant.EPOCH)
        val idA = history.addClaim(sessionId, "Le chomage a baisse de dix pour cent.", Instant.EPOCH, null)
        val idB = history.addClaim(sessionId, "La dette a augmente de cinq points.", Instant.EPOCH, null)
        client.enqueueChecked(result(Verdict.ACCURATE))
        client.enqueueChecked(result(Verdict.FALSE))

        val checker = PendingClaimChecker(history)
        val report = checker.checkAll(client)

        assertEquals(2, report.checked)
        assertEquals(0, report.stillPending)
        assertNull(report.failure)
        assertEquals(Verdict.ACCURATE, history.claims.getValue(idA).result?.verdict)
        assertEquals(Verdict.FALSE, history.claims.getValue(idB).result?.verdict)
        assertTrue(history.pendingClaims().isEmpty())
        // The surrounding transcript is gone by the time a Pending Claim is re-checked.
        assertTrue(client.checkedClaims.all { it.surroundingTranscript.isEmpty() })
    }

    @Test
    fun `checkAll deletes NotAClaim entries`() = runTest {
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val sessionId = history.startSession(Instant.EPOCH)
        val id = history.addClaim(sessionId, "Quel beau temps aujourd hui.", Instant.EPOCH, null)
        client.enqueueNotAClaim()

        val checker = PendingClaimChecker(history)
        val report = checker.checkAll(client)

        assertEquals(1, report.checked)
        assertFalse(history.claims.containsKey(id))
    }

    @Test
    fun `checkAll stops at the first Offline failure, leaving the rest Pending`() = runTest {
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val sessionId = history.startSession(Instant.EPOCH)
        val idA = history.addClaim(sessionId, "Premiere affirmation en attente.", Instant.EPOCH, null)
        val idB = history.addClaim(sessionId, "Deuxieme affirmation en attente.", Instant.EPOCH, null)
        val idC = history.addClaim(sessionId, "Troisieme affirmation en attente.", Instant.EPOCH, null)
        client.enqueueFailure(CheckFailure.Offline())
        client.enqueueChecked(result(Verdict.ACCURATE))
        client.enqueueChecked(result(Verdict.ACCURATE))

        val checker = PendingClaimChecker(history)
        val report = checker.checkAll(client)

        assertEquals(0, report.checked)
        assertEquals(3, report.stillPending)
        assertTrue(report.failure is CheckFailure.Offline)
        assertEquals(1, client.checkedClaims.size)
        assertNull(history.claims.getValue(idA).result)
        assertNull(history.claims.getValue(idB).result)
        assertNull(history.claims.getValue(idC).result)
    }
}
