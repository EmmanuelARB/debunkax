@file:OptIn(ExperimentalCoroutinesApi::class)

package app.debunkax.session

import app.debunkax.domain.Spend
import app.debunkax.domain.CheckFailure
import app.debunkax.domain.ClaimDetector
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ListeningSessionTest {

    @Test
    fun `final Claim is checked with preceding context, FALSE saves and alerts, alert stays until dismissed or replaced`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "Le chomage a baisse de dix pour cent en 2020."
        val detector = ClaimDetector { it == claimText }
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal("Contexte dit juste avant.", now())
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        val checked = client.checkedClaims.single()
        assertEquals(claimText, checked.text)
        assertEquals("Contexte dit juste avant.", checked.surroundingTranscript)

        val saved = history.claims.values.single()
        assertEquals(claimText, saved.text)
        assertEquals(Verdict.FALSE, saved.result?.verdict)

        val alert = session.state.value.alert
        assertNotNull(alert)
        assertEquals(claimText, alert!!.claimText)

        advanceTimeBy(600_000)
        runCurrent()
        assertNotNull(session.state.value.alert)
    }

    @Test
    fun `dismissAlert clears the alert immediately`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "La dette publique depasse mille milliards d euros depuis 2019."
        val detector = ClaimDetector { it == claimText }
        client.enqueueChecked(result(Verdict.MISLEADING))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()
        assertNotNull(session.state.value.alert)

        session.dismissAlert()
        assertNull(session.state.value.alert)

        // The cancelled auto-clear job must not resurrect the alert (or anyone else's) later.
        advanceTimeBy(9_000)
        runCurrent()
        assertNull(session.state.value.alert)
    }

    @Test
    fun `a newer Alert replaces the current one and stays on screen`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val textA = "Le PIB a recule de deux pour cent au premier trimestre."
        val textB = "Le nombre de chomeurs a double en dix ans selon l etude."
        val detector = ClaimDetector { it == textA || it == textB }
        client.enqueueChecked(result(Verdict.FALSE))
        client.enqueueChecked(result(Verdict.LACKS_CONTEXT))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(textA, now())
        runCurrent()
        assertEquals(textA, session.state.value.alert?.claimText)

        advanceTimeBy(3_000)
        runCurrent()
        speech.emitFinal(textB, now())
        runCurrent()
        assertEquals(textB, session.state.value.alert?.claimText)

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(textB, session.state.value.alert?.claimText)
    }

    @Test
    fun `ACCURATE and UNVERIFIABLE save without alert, NotAClaim saves nothing`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val textAccurate = "Le taux de chomage est de sept pour cent selon l Insee."
        val textUnverifiable = "Le trafic aerien a augmente de vingt pour cent cet ete."
        val textNotClaim = "Quel beau temps aujourd hui n est ce pas."
        val detector = ClaimDetector { it == textAccurate || it == textUnverifiable || it == textNotClaim }
        client.enqueueChecked(result(Verdict.ACCURATE))
        client.enqueueChecked(result(Verdict.UNVERIFIABLE))
        client.enqueueNotAClaim()
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(textAccurate, now())
        runCurrent()
        assertNull(session.state.value.alert)
        speech.emitFinal(textUnverifiable, now())
        runCurrent()
        assertNull(session.state.value.alert)
        speech.emitFinal(textNotClaim, now())
        runCurrent()
        assertNull(session.state.value.alert)

        assertEquals(2, history.claims.size)
        assertTrue(history.claims.values.any { it.text == textAccurate && it.result?.verdict == Verdict.ACCURATE })
        assertTrue(history.claims.values.any { it.text == textUnverifiable && it.result?.verdict == Verdict.UNVERIFIABLE })
        assertTrue(history.claims.values.none { it.text == textNotClaim })
    }

    @Test
    fun `a result ready more than 15s after the Claim was heard saves without an alert`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "La production industrielle a chute de quinze pour cent en un an."
        val detector = ClaimDetector { it == claimText }
        client.enqueueDelayedChecked(16_000, result(Verdict.FALSE))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        // The Check is still in flight: nothing saved or alerted yet.
        assertTrue(history.claims.isEmpty())
        assertNull(session.state.value.alert)

        advanceTimeBy(16_100)
        runCurrent()

        assertEquals(Verdict.FALSE, history.claims.values.single().result?.verdict)
        assertNull(session.state.value.alert)
    }

    @Test
    fun `a repeated Claim reuses the Verdict without a new Check, and still alerts`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "Le deficit public depasse cinq pour cent du pib cette annee."
        val detector = ClaimDetector { it == claimText }
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()
        assertEquals(1, client.checkedClaims.size)
        assertNotNull(session.state.value.alert)

        session.dismissAlert()
        assertNull(session.state.value.alert)

        speech.emitFinal(claimText, now())
        runCurrent()

        assertEquals(1, client.checkedClaims.size) // no new Check
        assertEquals(2, history.claims.size) // both occurrences saved
        assertTrue(history.claims.values.all { it.result?.verdict == Verdict.FALSE })
        assertNotNull(session.state.value.alert) // still alerts
    }

    @Test
    fun `no CheckClient saves a Pending Claim and state shows NoProvider`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val claimText = "L inflation a atteint six pour cent l an dernier."
        val detector = ClaimDetector { it == claimText }
        val session = ListeningSession(speech, detector, history, null, null, backgroundScope, VirtualClock(testScheduler))

        assertEquals(ChecksPaused.NoProvider, session.state.value.checksPaused)

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        val pending = history.claims.values.single()
        assertEquals(claimText, pending.text)
        assertNull(pending.result)
        assertEquals(ChecksPaused.NoProvider, session.state.value.checksPaused)
        assertNull(session.state.value.alert)
    }

    @Test
    fun `budget limit 1, second distinct Claim becomes Pending with BudgetReached`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val textA = "Le nombre de faillites a augmente de dix pour cent cette annee."
        val textB = "La natalite a recule de cinq pour cent depuis 2015."
        val detector = ClaimDetector { it == textA || it == textB }
        client.enqueueChecked(result(Verdict.ACCURATE))
        val session = ListeningSession(speech, detector, history, client, 1, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(textA, now())
        runCurrent()

        assertEquals(1, session.state.value.checksUsed)
        assertEquals(1, client.checkedClaims.size)

        speech.emitFinal(textB, now())
        runCurrent()

        assertEquals(1, client.checkedClaims.size) // budget stopped the second Check
        assertEquals(1, session.state.value.checksUsed)
        val paused = session.state.value.checksPaused as? ChecksPaused.Failed
        assertNotNull(paused)
        assertTrue(paused!!.failure is CheckFailure.BudgetReached)

        val pendingB = history.claims.values.single { it.text == textB }
        assertNull(pendingB.result)
    }

    @Test
    fun `CheckFailure Offline becomes a Pending Claim and pauses checks, a later success clears it`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val textA = "Le prix de l energie a triple en trois ans selon le rapport."
        val textB = "Le taux de natalite est au plus bas depuis 1945."
        val detector = ClaimDetector { it == textA || it == textB }
        client.enqueueFailure(CheckFailure.Offline())
        client.enqueueChecked(result(Verdict.ACCURATE))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(textA, now())
        runCurrent()

        val pendingA = history.claims.values.single { it.text == textA }
        assertNull(pendingA.result)
        val paused = session.state.value.checksPaused as? ChecksPaused.Failed
        assertNotNull(paused)
        assertTrue(paused!!.failure is CheckFailure.Offline)

        speech.emitFinal(textB, now())
        runCurrent()

        assertEquals(Verdict.ACCURATE, history.claims.values.single { it.text == textB }.result?.verdict)
        assertNull(session.state.value.checksPaused)
    }

    @Test
    fun `a punctuated final is split into sentences, each filtered and checked on its own`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "L'ISF rapportait 5 milliards d'euros."
        val detector = ClaimDetector { it == claimText }
        client.enqueueChecked(result(Verdict.MISLEADING))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal("Il faut réintroduire l'impôt. $claimText Et moi je l'assume !", now())
        runCurrent()

        val checked = client.checkedClaims.single()
        assertEquals(claimText, checked.text)
        assertEquals("Il faut réintroduire l'impôt.", checked.surroundingTranscript)
    }

    @Test
    fun `partial segments update liveText and never trigger a Check`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val detector = ClaimDetector { true } // would treat anything as a Claim, if it were ever reached
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitPartial("Le chomage a", now())
        runCurrent()
        assertEquals("Le chomage a", session.state.value.liveText)

        speech.emitPartial("Le chomage a baisse", now())
        runCurrent()
        assertEquals("Le chomage a baisse", session.state.value.liveText)

        assertTrue(client.checkedClaims.isEmpty())
        assertTrue(history.claims.isEmpty())
        assertNull(session.state.value.alert)
    }

    @Test
    fun `SpeechFailure ModelDownloading retries listening after the delay`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val detector = ClaimDetector { false }
        val retryDelay = Duration.ofSeconds(5)
        val session =
            ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler), retryDelay)

        session.start()
        runCurrent()
        assertEquals(1, speech.attemptCount)

        speech.fail(SpeechFailure.ModelDownloading())
        runCurrent()

        assertTrue(session.state.value.speechProblem is SpeechFailure.ModelDownloading)
        assertTrue(session.state.value.listening)
        assertEquals(1, speech.attemptCount) // no retry yet

        advanceTimeBy(retryDelay.toMillis())
        runCurrent()

        assertEquals(2, speech.attemptCount) // retried: transcribe() called again

        speech.emitFinal("Bonjour a tous ce soir.", now())
        runCurrent()

        assertNull(session.state.value.speechProblem)
        assertTrue(session.state.value.listening)
    }

    @Test
    fun `SpeechFailure Unsupported stops listening without retrying`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val detector = ClaimDetector { false }
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.fail(SpeechFailure.Unsupported("Micro indisponible"))
        runCurrent()

        assertFalse(session.state.value.listening)
        assertTrue(session.state.value.speechProblem is SpeechFailure.Unsupported)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, speech.attemptCount) // never retried
    }

    @Test
    fun `when the audio source ends, the session stops itself and closes its History row`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val session = ListeningSession(speech, ClaimDetector { false }, history, null, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal("Une phrase pas vraiment une affirmation.", now())
        runCurrent()

        speech.end() // e.g. the app-audio consent token was revoked from Quick Settings
        runCurrent()

        assertFalse(session.state.value.listening)
        assertTrue("the History session row must be closed", history.sessionRecords.single().endedAt != null)
        assertEquals("", session.state.value.liveText)
    }

    @Test
    fun `stop ends the session in History and clears liveText and alert`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "Le budget de la defense augmente de vingt pour cent cette annee."
        val detector = ClaimDetector { it == claimText }
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(speech, detector, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()
        assertNotNull(session.state.value.alert)
        assertTrue(session.state.value.liveText.isNotEmpty())

        session.stop()

        assertFalse(session.state.value.listening)
        assertEquals("", session.state.value.liveText)
        assertNull(session.state.value.alert)
        val savedSession = history.sessionRecords.single()
        assertNotNull(savedSession.endedAt)

        // No leftover alert-clearing job should fire later and disturb the stopped state.
        advanceTimeBy(9_000)
        runCurrent()
        assertNull(session.state.value.alert)
    }

    @Test
    fun `shows when the session started and what its Checks cost, even for sentences that aren't Claims`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val text = "Le nombre de faillites a augmente de dix pour cent cette annee."
        client.enqueueNotAClaim()
        client.spent = Spend(0.0004, estimated = false)
        val session = ListeningSession(speech, ClaimDetector { true }, history, client, null, backgroundScope, VirtualClock(testScheduler))

        session.start()
        runCurrent()
        assertEquals(now(), session.state.value.startedAt)
        speech.emitFinal(text, now())
        runCurrent()

        assertEquals(Spend(0.0004, estimated = false), session.state.value.spent)
    }

    @Test
    fun `the recognised Speaker goes into the Check, the History and the Alert, and stops with the session`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val speakers = FakeSpeakerRecognition("Marine Le Pen")
        val claimText = "Nous avons baisse les impots de dix milliards."
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(
            speech, ClaimDetector { it == claimText }, history, client, null, backgroundScope, VirtualClock(testScheduler),
            speakers = speakers,
        )

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        assertEquals("Marine Le Pen", client.checkedClaims.single().speaker)
        assertEquals("Marine Le Pen", history.claims.values.single().speaker)
        assertEquals("Marine Le Pen", session.state.value.alert?.speaker)
        session.stop()
        assertFalse(speakers.started)
    }

    @Test
    fun `an unknown Speaker stays unknown everywhere`() = runTest {
        val speech = FakeSpeechEngine()
        val history = FakeHistoryRepository()
        val client = FakeCheckClient()
        val claimText = "Le chomage a baisse de dix pour cent en 2020."
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(
            speech, ClaimDetector { it == claimText }, history, client, null, backgroundScope, VirtualClock(testScheduler),
            speakers = FakeSpeakerRecognition(null),
        )

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        assertNull(client.checkedClaims.single().speaker)
        assertNull(history.claims.values.single().speaker)
        assertNull(session.state.value.alert!!.speaker)
    }

    @Test
    fun `a second-person Claim by a recognised Speaker is checked with the addressee found by the speaker layer`() = runTest {
        val speech = FakeSpeechEngine()
        val client = FakeCheckClient()
        val claimText = "Vous avez vote la loi de 2024."
        val speakers = FakeSpeakerRecognition("Marine Le Pen", addressee = "Gabriel Attal")
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(
            speech, ClaimDetector { it == claimText }, FakeHistoryRepository(), client, null, backgroundScope, VirtualClock(testScheduler),
            speakers = speakers,
        )

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        val checked = client.checkedClaims.single()
        assertEquals("Marine Le Pen", checked.speaker)
        assertEquals("Gabriel Attal", checked.addressedSpeaker)
        assertEquals("Marine Le Pen", speakers.addresseeCalls.single().second)
    }

    @Test
    fun `no addressee is looked up for a sentence in the first person`() = runTest {
        val speech = FakeSpeechEngine()
        val client = FakeCheckClient()
        val claimText = "J'ai baisse les impots de 10 milliards."
        val speakers = FakeSpeakerRecognition("Marine Le Pen", addressee = "Gabriel Attal")
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(
            speech, ClaimDetector { it == claimText }, FakeHistoryRepository(), client, null, backgroundScope, VirtualClock(testScheduler),
            speakers = speakers,
        )

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        assertNull(client.checkedClaims.single().addressedSpeaker)
        assertTrue(speakers.addresseeCalls.isEmpty())
    }

    @Test
    fun `no addressee is looked up when the claimant is unknown`() = runTest {
        val speech = FakeSpeechEngine()
        val client = FakeCheckClient()
        val claimText = "Vous avez vote la loi de 2024."
        val speakers = FakeSpeakerRecognition(null, addressee = "Gabriel Attal")
        client.enqueueChecked(result(Verdict.FALSE))
        val session = ListeningSession(
            speech, ClaimDetector { it == claimText }, FakeHistoryRepository(), client, null, backgroundScope, VirtualClock(testScheduler),
            speakers = speakers,
        )

        session.start()
        runCurrent()
        speech.emitFinal(claimText, now())
        runCurrent()

        assertNull(client.checkedClaims.single().speaker)
        assertNull(client.checkedClaims.single().addressedSpeaker)
        assertTrue(speakers.addresseeCalls.isEmpty())
    }

    @Test
    fun `the live transcript shows who is speaking, and forgets it at stop`() = runTest {
        val speech = FakeSpeechEngine()
        val speakers = FakeSpeakerRecognition("Marine Le Pen")
        val session = ListeningSession(
            speech, ClaimDetector { false }, FakeHistoryRepository(), FakeCheckClient(), null, backgroundScope,
            VirtualClock(testScheduler), speakers = speakers,
        )

        session.start()
        runCurrent()
        speech.emitPartial("Nous avons", now())
        runCurrent()
        assertEquals("Marine Le Pen", session.state.value.liveSpeaker)

        speakers.speaker = null
        speech.emitPartial("Nous avons baisse", now())
        runCurrent()
        assertNull(session.state.value.liveSpeaker)

        speakers.speaker = "Marine Le Pen"
        speech.emitPartial("Nous avons baisse les impots", now())
        runCurrent()
        session.stop()
        assertNull(session.state.value.liveSpeaker)
    }
}
