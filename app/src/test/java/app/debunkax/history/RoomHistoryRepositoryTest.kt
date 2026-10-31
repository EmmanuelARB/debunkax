package app.debunkax.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.debunkax.domain.CheckResult
import app.debunkax.domain.Explanation
import app.debunkax.domain.HistoryFilter
import app.debunkax.domain.Source
import app.debunkax.domain.Verdict
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises [RoomHistoryRepository] against a real in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomHistoryRepositoryTest {

    private lateinit var db: DebunkaxDatabase
    private lateinit var repository: RoomHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DebunkaxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHistoryRepository(db.historyDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `add and read a claim round trips sources and explanation`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        val result = CheckResult(
            verdict = Verdict.FALSE,
            context = "Contexte de verification",
            sources = listOf(Source("AFP Factuel", "https://factuel.afp.com/1")),
        )
        val claimId = repository.addClaim(sessionId, "Le chomage a double", Instant.ofEpochMilli(2_000), result)
        repository.setExplanation(
            claimId,
            Explanation("Explication detaillee", listOf(Source("INSEE", "https://insee.fr/x"))),
        )

        val claim = repository.claim(claimId).first()
        assertEquals("Le chomage a double", claim?.text)
        assertEquals(sessionId, claim?.sessionId)
        assertEquals(Verdict.FALSE, claim?.result?.verdict)
        assertEquals("Contexte de verification", claim?.result?.context)
        assertEquals(listOf(Source("AFP Factuel", "https://factuel.afp.com/1")), claim?.result?.sources)
        assertEquals("Explication detaillee", claim?.explanation?.text)
        assertEquals(listOf(Source("INSEE", "https://insee.fr/x")), claim?.explanation?.sources)
    }

    @Test
    fun `pending claim becomes checked after setResult`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        val claimId = repository.addClaim(sessionId, "Une affirmation", Instant.ofEpochMilli(2_000), null)

        val pendingBefore = repository.pendingClaims()
        assertEquals(1, pendingBefore.size)
        assertNull(pendingBefore.first().result)

        repository.setResult(claimId, CheckResult(Verdict.ACCURATE, "Confirme", emptyList()))

        assertTrue(repository.pendingClaims().isEmpty())
        val claim = repository.claim(claimId).first()
        assertEquals(Verdict.ACCURATE, claim?.result?.verdict)
    }

    @Test
    fun `the rewritten Claim is kept and shown instead of the transcribed sentence`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        val heard = "l'isf c'est 5 milliards qui avait été supprimé"
        val rewritten = "L'ISF rapportait 5 milliards d'euros avant sa suppression."
        val addedId = repository.addClaim(sessionId, heard, Instant.ofEpochMilli(2_000), CheckResult(Verdict.ACCURATE, "x", emptyList(), rewritten))
        val pendingId = repository.addClaim(sessionId, heard, Instant.ofEpochMilli(3_000), null)
        repository.setResult(pendingId, CheckResult(Verdict.ACCURATE, "x", emptyList(), rewritten))

        for (id in listOf(addedId, pendingId)) {
            val claim = repository.claim(id).first()!!
            assertEquals(heard, claim.text)
            assertEquals(rewritten, claim.checkedText)
        }
    }

    @Test
    fun `text filter is case and accent insensitive`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        repository.addClaim(sessionId, "Le President a ete reelu", Instant.ofEpochMilli(2_000), null)
        repository.addClaim(sessionId, "Autre affirmation sans rapport", Instant.ofEpochMilli(3_000), null)

        val filtered = repository.sessions(HistoryFilter(query = "president")).first()

        assertEquals(1, filtered.size)
        assertEquals(1, filtered.first().claims.size)
        assertEquals("Le President a ete reelu", filtered.first().claims.first().text)
    }

    @Test
    fun `verdict filter keeps only matching claims`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        repository.addClaim(
            sessionId,
            "Affirmation fausse",
            Instant.ofEpochMilli(2_000),
            CheckResult(Verdict.FALSE, "ctx", emptyList()),
        )
        repository.addClaim(
            sessionId,
            "Affirmation vraie",
            Instant.ofEpochMilli(3_000),
            CheckResult(Verdict.ACCURATE, "ctx", emptyList()),
        )

        val filtered = repository.sessions(HistoryFilter(verdicts = setOf(Verdict.FALSE))).first()

        assertEquals(1, filtered.size)
        assertEquals(1, filtered.first().claims.size)
        assertEquals(Verdict.FALSE, filtered.first().claims.first().result?.verdict)
    }

    @Test
    fun `unfiltered sessions include empty sessions, filtered drops them`() = runTest {
        val emptySessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        val otherSessionId = repository.startSession(Instant.ofEpochMilli(5_000))
        repository.addClaim(otherSessionId, "Une affirmation", Instant.ofEpochMilli(6_000), null)

        val unfiltered = repository.sessions(HistoryFilter()).first()
        assertEquals(2, unfiltered.size)
        assertTrue(unfiltered.any { it.sessionId == emptySessionId && it.claims.isEmpty() })

        val filtered = repository.sessions(HistoryFilter(query = "affirmation")).first()
        assertEquals(1, filtered.size)
        assertEquals(otherSessionId, filtered.first().sessionId)
    }

    @Test
    fun `sessions and their claims are newest first`() = runTest {
        val firstSession = repository.startSession(Instant.ofEpochMilli(1_000))
        val secondSession = repository.startSession(Instant.ofEpochMilli(2_000))
        repository.addClaim(firstSession, "entendu en second", Instant.ofEpochMilli(500), null)
        repository.addClaim(firstSession, "entendu en premier", Instant.ofEpochMilli(100), null)

        val sessions = repository.sessions(HistoryFilter()).first()

        assertEquals(listOf(secondSession, firstSession), sessions.map { it.sessionId })
        val firstSessionHistory = sessions.first { it.sessionId == firstSession }
        assertEquals(listOf("entendu en second", "entendu en premier"), firstSessionHistory.claims.map { it.text })
    }

    @Test
    fun `deleting a session cascades to its claims`() = runTest {
        val keptSessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        val removedSessionId = repository.startSession(Instant.ofEpochMilli(2_000))
        repository.addClaim(keptSessionId, "Claim conservee", Instant.ofEpochMilli(1_500), null)
        val removedClaimId =
            repository.addClaim(removedSessionId, "Claim supprimee", Instant.ofEpochMilli(2_500), null)

        db.historyDao().deleteSession(removedSessionId)

        assertNull(repository.claim(removedClaimId).first())
        val remaining = repository.sessions(HistoryFilter()).first()
        assertEquals(listOf(keptSessionId), remaining.map { it.sessionId })
    }

    @Test
    fun `unfinished sessions end at their last claim, or their start when empty`() = runTest {
        val withClaims = repository.startSession(Instant.ofEpochMilli(1_000))
        repository.addClaim(withClaims, "Premiere", Instant.ofEpochMilli(1_500), null)
        repository.addClaim(withClaims, "Derniere", Instant.ofEpochMilli(4_000), null)
        val empty = repository.startSession(Instant.ofEpochMilli(5_000))
        val current = repository.startSession(Instant.ofEpochMilli(9_000))

        repository.closeUnfinishedSessions(before = Instant.ofEpochMilli(8_000))

        val ended = repository.sessions(HistoryFilter()).first().associate { it.sessionId to it.endedAt }
        assertEquals(Instant.ofEpochMilli(4_000), ended[withClaims])
        assertEquals(Instant.ofEpochMilli(5_000), ended[empty])
        assertNull(ended[current])
    }

    @Test
    fun `clearAll deletes sessions and claims`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        repository.addClaim(sessionId, "Une affirmation", Instant.ofEpochMilli(2_000), null)
        repository.addClaim(
            sessionId,
            "Autre affirmation",
            Instant.ofEpochMilli(3_000),
            CheckResult(Verdict.MISLEADING, "ctx", emptyList()),
        )

        repository.clearAll()

        assertTrue(repository.sessions(HistoryFilter()).first().isEmpty())
        assertTrue(repository.pendingClaims().isEmpty())
    }

    @Test
    fun `the Speaker round trips, and stays null when unknown`() = runTest {
        val sessionId = repository.startSession(Instant.ofEpochMilli(1_000))
        val named = repository.addClaim(sessionId, "Nous avons baisse les impots", Instant.ofEpochMilli(2_000), null, "Marine Le Pen")
        val unknown = repository.addClaim(sessionId, "Le chomage a double", Instant.ofEpochMilli(3_000), null)

        assertEquals("Marine Le Pen", repository.claim(named).first()?.speaker)
        assertEquals(null, repository.claim(unknown).first()?.speaker)
    }
}
