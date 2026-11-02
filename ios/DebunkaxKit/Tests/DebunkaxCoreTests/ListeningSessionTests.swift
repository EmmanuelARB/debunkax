import XCTest
import Synchronization
@testable import DebunkaxCore

@MainActor
final class ListeningSessionTests: XCTestCase {

    private struct Rig {
        let speech = FakeSpeechEngine()
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let clock = TestClock()
    }

    private func makeSession(
        _ rig: Rig, detector: ClaimDetector, noClient: Bool = false, checkLimit: Int? = nil,
        modelRetryDelay: TimeInterval = 5, sleep: @escaping Sleeper = { _ in }, speakers: SpeakerRecognition? = nil
    ) -> ListeningSession {
        let resolved: CheckClient? = noClient ? nil : rig.client
        return ListeningSession(
            speech: rig.speech, detector: detector, history: rig.history, checkClient: resolved, checkLimit: checkLimit,
            now: rig.clock.reader, modelRetryDelay: modelRetryDelay, sleep: sleep, speakers: speakers)
    }

    /// Starts the session and waits until it is actually listening (the first `transcribe()` call).
    private func startListening(_ session: ListeningSession, _ rig: Rig, attempts: Int = 1) async {
        session.start()
        await waitUntil("transcribe() called") { rig.speech.attemptCount >= attempts }
    }

    func testFinalClaimIsCheckedWithPrecedingContextFalseSavesAndAlertsAlertStaysUntilDismissedOrReplaced() async {
        let rig = Rig()
        let claimText = "Le chomage a baisse de dix pour cent en 2020."
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText })

        await startListening(session, rig)
        rig.speech.emitFinal("Contexte dit juste avant.", at: rig.clock.now())
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("alert") { session.state.alert != nil }

        let checked = rig.client.checkedClaims[0]
        XCTAssertEqual(rig.client.checkedClaims.count, 1)
        XCTAssertEqual(checked.text, claimText)
        XCTAssertEqual(checked.surroundingTranscript, "Contexte dit juste avant.")

        XCTAssertEqual(rig.history.claims.count, 1)
        let saved = rig.history.claims[0]
        XCTAssertEqual(saved.text, claimText)
        XCTAssertEqual(saved.result?.verdict, .false)
        XCTAssertEqual(session.state.alert?.claimText, claimText)

        rig.clock.advance(600)
        await settle()
        XCTAssertNotNil(session.state.alert)
    }

    func testDismissAlertClearsTheAlertImmediately() async {
        let rig = Rig()
        let claimText = "La dette publique depasse mille milliards d euros depuis 2019."
        rig.client.enqueueChecked(makeResult(.misleading))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText })

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("alert") { session.state.alert != nil }

        session.dismissAlert()
        XCTAssertNil(session.state.alert)

        rig.clock.advance(9)
        await settle()
        XCTAssertNil(session.state.alert)
    }

    func testANewerAlertReplacesTheCurrentOneAndStaysOnScreen() async {
        let rig = Rig()
        let textA = "Le PIB a recule de deux pour cent au premier trimestre."
        let textB = "Le nombre de chomeurs a double en dix ans selon l etude."
        rig.client.enqueueChecked(makeResult(.false))
        rig.client.enqueueChecked(makeResult(.lacksContext))
        let session = makeSession(rig, detector: ClosureDetector { $0 == textA || $0 == textB })

        await startListening(session, rig)
        rig.speech.emitFinal(textA, at: rig.clock.now())
        await waitUntil("first alert") { session.state.alert?.claimText == textA }

        rig.clock.advance(3)
        rig.speech.emitFinal(textB, at: rig.clock.now())
        await waitUntil("second alert") { session.state.alert?.claimText == textB }

        rig.clock.advance(600)
        await settle()
        XCTAssertEqual(session.state.alert?.claimText, textB)
    }

    func testAccurateAndUnverifiableSaveWithoutAlertNotAClaimSavesNothing() async {
        let rig = Rig()
        let textAccurate = "Le taux de chomage est de sept pour cent selon l Insee."
        let textUnverifiable = "Le trafic aerien a augmente de vingt pour cent cet ete."
        let textNotClaim = "Quel beau temps aujourd hui n est ce pas."
        rig.client.enqueueChecked(makeResult(.accurate))
        rig.client.enqueueChecked(makeResult(.unverifiable))
        rig.client.enqueueNotAClaim()
        let session = makeSession(
            rig, detector: ClosureDetector { $0 == textAccurate || $0 == textUnverifiable || $0 == textNotClaim })

        await startListening(session, rig)
        rig.speech.emitFinal(textAccurate, at: rig.clock.now())
        await waitUntil("first claim saved") { rig.history.claims.count == 1 }
        XCTAssertNil(session.state.alert)
        rig.speech.emitFinal(textUnverifiable, at: rig.clock.now())
        await waitUntil("second claim saved") { rig.history.claims.count == 2 }
        XCTAssertNil(session.state.alert)
        rig.speech.emitFinal(textNotClaim, at: rig.clock.now())
        await waitUntil("third Check") { rig.client.checkedClaims.count == 3 }
        await settle()
        XCTAssertNil(session.state.alert)

        XCTAssertEqual(rig.history.claims.count, 2)
        XCTAssertTrue(rig.history.claims.contains { $0.text == textAccurate && $0.result?.verdict == .accurate })
        XCTAssertTrue(rig.history.claims.contains { $0.text == textUnverifiable && $0.result?.verdict == .unverifiable })
        XCTAssertTrue(rig.history.claims.allSatisfy { $0.text != textNotClaim })
    }

    func testAResultReadyMoreThan15sAfterTheClaimWasHeardSavesWithoutAnAlert() async {
        let rig = Rig()
        let claimText = "La production industrielle a chute de quinze pour cent en un an."
        let gate = AsyncGate()
        rig.client.enqueueDelayedChecked(gate: gate, clock: rig.clock, seconds: 16, makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText })

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("Check started") { rig.client.checkedClaims.count == 1 }
        await settle()

        // The Check is still in flight: nothing saved or alerted yet.
        XCTAssertTrue(rig.history.claims.isEmpty)
        XCTAssertNil(session.state.alert)

        gate.open()
        await waitUntil("claim saved") { rig.history.claims.count == 1 }
        await settle()

        XCTAssertEqual(rig.history.claims[0].result?.verdict, .false)
        XCTAssertNil(session.state.alert)
    }

    func testARepeatedClaimReusesTheVerdictWithoutANewCheckAndStillAlerts() async {
        let rig = Rig()
        let claimText = "Le deficit public depasse cinq pour cent du pib cette annee."
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText })

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("alert") { session.state.alert != nil }
        XCTAssertEqual(rig.client.checkedClaims.count, 1)

        session.dismissAlert()
        XCTAssertNil(session.state.alert)

        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("second alert") { session.state.alert != nil }

        XCTAssertEqual(rig.client.checkedClaims.count, 1)  // no new Check
        XCTAssertEqual(rig.history.claims.count, 2)  // both occurrences saved
        XCTAssertTrue(rig.history.claims.allSatisfy { $0.result?.verdict == .false })
    }

    func testNoCheckClientSavesAPendingClaimAndStateShowsNoProvider() async {
        let rig = Rig()
        let claimText = "L inflation a atteint six pour cent l an dernier."
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText }, noClient: true)

        XCTAssertEqual(session.state.checksPaused, .noProvider)

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("pending claim saved") { rig.history.claims.count == 1 }

        let pending = rig.history.claims[0]
        XCTAssertEqual(pending.text, claimText)
        XCTAssertNil(pending.result)
        XCTAssertEqual(session.state.checksPaused, .noProvider)
        XCTAssertNil(session.state.alert)
    }

    func testBudgetLimit1SecondDistinctClaimBecomesPendingWithBudgetReached() async {
        let rig = Rig()
        let textA = "Le nombre de faillites a augmente de dix pour cent cette annee."
        let textB = "La natalite a recule de cinq pour cent depuis 2015."
        rig.client.enqueueChecked(makeResult(.accurate))
        let session = makeSession(rig, detector: ClosureDetector { $0 == textA || $0 == textB }, checkLimit: 1)

        await startListening(session, rig)
        rig.speech.emitFinal(textA, at: rig.clock.now())
        await waitUntil("first claim saved") { rig.history.claims.count == 1 }

        XCTAssertEqual(session.state.checksUsed, 1)
        XCTAssertEqual(rig.client.checkedClaims.count, 1)

        rig.speech.emitFinal(textB, at: rig.clock.now())
        await waitUntil("budget pause") { session.state.checksPaused != nil }
        await waitUntil("pending claim saved") { rig.history.claims.count == 2 }

        XCTAssertEqual(rig.client.checkedClaims.count, 1)  // budget stopped the second Check
        XCTAssertEqual(session.state.checksUsed, 1)
        XCTAssertEqual(session.state.checksPaused, .failed(.budgetReached))

        let pendingB = rig.history.claims.first { $0.text == textB }
        XCTAssertNotNil(pendingB)
        XCTAssertNil(pendingB?.result)
    }

    func testCheckFailureOfflineBecomesAPendingClaimAndPausesChecksALaterSuccessClearsIt() async {
        let rig = Rig()
        let textA = "Le prix de l energie a triple en trois ans selon le rapport."
        let textB = "Le taux de natalite est au plus bas depuis 1945."
        rig.client.enqueueFailure(.offline)
        rig.client.enqueueChecked(makeResult(.accurate))
        let session = makeSession(rig, detector: ClosureDetector { $0 == textA || $0 == textB })

        await startListening(session, rig)
        rig.speech.emitFinal(textA, at: rig.clock.now())
        await waitUntil("checks paused") { session.state.checksPaused != nil }
        await waitUntil("pending claim saved") { rig.history.claims.count == 1 }

        let pendingA = rig.history.claims.first { $0.text == textA }
        XCTAssertNil(pendingA?.result)
        XCTAssertEqual(session.state.checksPaused, .failed(.offline))

        rig.speech.emitFinal(textB, at: rig.clock.now())
        await waitUntil("second claim saved") { rig.history.claims.count == 2 }
        await waitUntil("checks resumed") { session.state.checksPaused == nil }

        XCTAssertEqual(rig.history.claims.first { $0.text == textB }?.result?.verdict, .accurate)
    }

    func testAPunctuatedFinalIsSplitIntoSentencesEachFilteredAndCheckedOnItsOwn() async {
        let rig = Rig()
        let claimText = "L'ISF rapportait 5 milliards d'euros."
        rig.client.enqueueChecked(makeResult(.misleading))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText })

        await startListening(session, rig)
        rig.speech.emitFinal("Il faut réintroduire l'impôt. \(claimText) Et moi je l'assume !", at: rig.clock.now())
        await waitUntil("Check") { rig.client.checkedClaims.count == 1 }
        await settle()

        XCTAssertEqual(rig.client.checkedClaims.count, 1)
        let checked = rig.client.checkedClaims[0]
        XCTAssertEqual(checked.text, claimText)
        XCTAssertEqual(checked.surroundingTranscript, "Il faut réintroduire l'impôt.")
    }

    func testPartialSegmentsUpdateLiveTextAndNeverTriggerACheck() async {
        let rig = Rig()
        let session = makeSession(rig, detector: ClosureDetector { _ in true })  // would treat anything as a Claim

        await startListening(session, rig)
        rig.speech.emitPartial("Le chomage a", at: rig.clock.now())
        await waitUntil("first partial") { session.state.liveText == "Le chomage a" }

        rig.speech.emitPartial("Le chomage a baisse", at: rig.clock.now())
        await waitUntil("second partial") { session.state.liveText == "Le chomage a baisse" }
        await settle()

        XCTAssertTrue(rig.client.checkedClaims.isEmpty)
        XCTAssertTrue(rig.history.claims.isEmpty)
        XCTAssertNil(session.state.alert)
    }

    func testSpeechFailureModelDownloadingRetriesListeningAfterTheDelay() async {
        let rig = Rig()
        let retryDelay: TimeInterval = 5
        let gate = AsyncGate()
        let sleeps = SleepLog()
        let session = makeSession(
            rig, detector: ClosureDetector { _ in false }, modelRetryDelay: retryDelay,
            sleep: { seconds in
                sleeps.add(seconds)
                await gate.wait()
            })

        await startListening(session, rig)
        XCTAssertEqual(rig.speech.attemptCount, 1)

        rig.speech.fail(.modelDownloading)
        await waitUntil("model-downloading notice") { session.state.speechProblem == .modelDownloading }
        await waitUntil("retry delay requested") { sleeps.values == [retryDelay] }

        XCTAssertTrue(session.state.listening)
        XCTAssertEqual(rig.speech.attemptCount, 1)  // no retry yet

        gate.open()  // the delay elapses
        await waitUntil("second transcribe() call") { rig.speech.attemptCount == 2 }

        rig.speech.emitFinal("Bonjour a tous ce soir.", at: rig.clock.now())
        await waitUntil("notice cleared") { session.state.speechProblem == nil }
        XCTAssertTrue(session.state.listening)
        await session.stop()
    }

    func testSpeechFailureUnsupportedStopsListeningWithoutRetrying() async {
        let rig = Rig()
        let session = makeSession(rig, detector: ClosureDetector { _ in false })

        await startListening(session, rig)
        rig.speech.fail(.unsupported("Micro indisponible"))
        await waitUntil("listening stopped") { !session.state.listening }

        XCTAssertEqual(session.state.speechProblem, .unsupported("Micro indisponible"))

        rig.clock.advance(60)
        await settle()
        XCTAssertEqual(rig.speech.attemptCount, 1)  // never retried
    }

    func testWhenTheAudioSourceEndsTheSessionStopsItselfAndClosesItsHistoryRow() async {
        let rig = Rig()
        let session = makeSession(rig, detector: ClosureDetector { _ in false }, noClient: true)

        await startListening(session, rig)
        rig.speech.emitFinal("Une phrase pas vraiment une affirmation.", at: rig.clock.now())
        await waitUntil("live text") { !session.state.liveText.isEmpty }

        rig.speech.end()  // e.g. the audio consent was revoked
        await waitUntil("listening stopped") { !session.state.listening }
        await waitUntil("History row closed") { rig.history.sessionRecords.first?.endedAt != nil }

        XCTAssertEqual(rig.history.sessionRecords.count, 1)
        XCTAssertEqual(session.state.liveText, "")
    }

    func testStopEndsTheSessionInHistoryAndClearsLiveTextAndAlert() async {
        let rig = Rig()
        let claimText = "Le budget de la defense augmente de vingt pour cent cette annee."
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText })

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("alert") { session.state.alert != nil }
        XCTAssertFalse(session.state.liveText.isEmpty)

        await session.stop()

        XCTAssertFalse(session.state.listening)
        XCTAssertEqual(session.state.liveText, "")
        XCTAssertNil(session.state.alert)
        XCTAssertEqual(rig.history.sessionRecords.count, 1)
        XCTAssertNotNil(rig.history.sessionRecords[0].endedAt)

        rig.clock.advance(9)
        await settle()
        XCTAssertNil(session.state.alert)
    }

    func testShowsWhenTheSessionStartedAndWhatItsChecksCostEvenForSentencesThatArentClaims() async {
        let rig = Rig()
        rig.client.enqueueNotAClaim()
        rig.client.spent = Spend(usd: 0.0004, estimated: false)
        let session = makeSession(rig, detector: ClosureDetector { _ in true })

        await startListening(session, rig)
        XCTAssertEqual(session.state.startedAt, rig.clock.now())
        rig.speech.emitFinal("Le nombre de faillites a augmente de dix pour cent cette annee.", at: rig.clock.now())
        await waitUntil("spend shown") { session.state.spent == Spend(usd: 0.0004, estimated: false) }
    }

    func testTheRecognisedSpeakerGoesIntoTheCheckTheHistoryAndTheAlertAndStopsWithTheSession() async {
        let rig = Rig()
        let speakers = FakeSpeakerRecognition(speaker: "Marine Le Pen")
        let claimText = "Nous avons baisse les impots de dix milliards."
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText }, speakers: speakers)

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("alert") { session.state.alert != nil }

        XCTAssertEqual(rig.client.checkedClaims[0].speaker, "Marine Le Pen")
        XCTAssertEqual(rig.history.claims[0].speaker, "Marine Le Pen")
        XCTAssertEqual(session.state.alert?.speaker, "Marine Le Pen")
        await session.stop()
        XCTAssertFalse(speakers.started)
    }

    func testAnUnknownSpeakerStaysUnknownEverywhere() async {
        let rig = Rig()
        let claimText = "Le chomage a baisse de dix pour cent en 2020."
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(
            rig, detector: ClosureDetector { $0 == claimText }, speakers: FakeSpeakerRecognition(speaker: nil))

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("alert") { session.state.alert != nil }

        XCTAssertNil(rig.client.checkedClaims[0].speaker)
        XCTAssertNil(rig.history.claims[0].speaker)
        XCTAssertNil(session.state.alert?.speaker)
    }

    func testASecondPersonClaimByARecognisedSpeakerIsCheckedWithTheAddresseeFoundByTheSpeakerLayer() async {
        let rig = Rig()
        let claimText = "Vous avez vote la loi de 2024."
        let speakers = FakeSpeakerRecognition(speaker: "Marine Le Pen", addressee: "Gabriel Attal")
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText }, speakers: speakers)

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("Check") { rig.client.checkedClaims.count == 1 }

        let checked = rig.client.checkedClaims[0]
        XCTAssertEqual(checked.speaker, "Marine Le Pen")
        XCTAssertEqual(checked.addressedSpeaker, "Gabriel Attal")
        XCTAssertEqual(speakers.addresseeCalls.count, 1)
        XCTAssertEqual(speakers.addresseeCalls[0].claimant, "Marine Le Pen")
    }

    func testNoAddresseeIsLookedUpForASentenceInTheFirstPerson() async {
        let rig = Rig()
        let claimText = "J'ai baisse les impots de 10 milliards."
        let speakers = FakeSpeakerRecognition(speaker: "Marine Le Pen", addressee: "Gabriel Attal")
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText }, speakers: speakers)

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("Check") { rig.client.checkedClaims.count == 1 }

        XCTAssertNil(rig.client.checkedClaims[0].addressedSpeaker)
        XCTAssertTrue(speakers.addresseeCalls.isEmpty)
    }

    func testNoAddresseeIsLookedUpWhenTheClaimantIsUnknown() async {
        let rig = Rig()
        let claimText = "Vous avez vote la loi de 2024."
        let speakers = FakeSpeakerRecognition(speaker: nil, addressee: "Gabriel Attal")
        rig.client.enqueueChecked(makeResult(.false))
        let session = makeSession(rig, detector: ClosureDetector { $0 == claimText }, speakers: speakers)

        await startListening(session, rig)
        rig.speech.emitFinal(claimText, at: rig.clock.now())
        await waitUntil("Check") { rig.client.checkedClaims.count == 1 }

        XCTAssertNil(rig.client.checkedClaims[0].speaker)
        XCTAssertNil(rig.client.checkedClaims[0].addressedSpeaker)
        XCTAssertTrue(speakers.addresseeCalls.isEmpty)
    }

    func testTheLiveTranscriptShowsWhoIsSpeakingAndForgetsItAtStop() async {
        let rig = Rig()
        let speakers = FakeSpeakerRecognition(speaker: "Marine Le Pen")
        let session = makeSession(rig, detector: ClosureDetector { _ in false }, speakers: speakers)

        await startListening(session, rig)
        rig.speech.emitPartial("Nous avons", at: rig.clock.now())
        await waitUntil("speaker shown") { session.state.liveSpeaker == "Marine Le Pen" }

        speakers.speaker = nil
        rig.speech.emitPartial("Nous avons baisse", at: rig.clock.now())
        await waitUntil("speaker forgotten") { session.state.liveText == "Nous avons baisse" }
        XCTAssertNil(session.state.liveSpeaker)

        speakers.speaker = "Marine Le Pen"
        rig.speech.emitPartial("Nous avons baisse les impots", at: rig.clock.now())
        await waitUntil("speaker shown again") { session.state.liveSpeaker == "Marine Le Pen" }
        await session.stop()
        XCTAssertNil(session.state.liveSpeaker)
    }
}

/// Records the durations a `Sleeper` was asked for.
final class SleepLog: Sendable {
    private let log = Mutex<[TimeInterval]>([])
    var values: [TimeInterval] { log.withLock { $0 } }
    func add(_ seconds: TimeInterval) { log.withLock { $0.append(seconds) } }
}
