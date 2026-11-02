import DebunkaxCore
import XCTest

@testable import DebunkaxData

/// Exercises `SQLiteHistoryRepository` against a real in-memory database.
final class SQLiteHistoryRepositoryTests: XCTestCase {
    private var repository: SQLiteHistoryRepository!

    override func setUpWithError() throws {
        repository = try SQLiteHistoryRepository.inMemory()
    }

    private func ms(_ value: Int) -> Date { Date(timeIntervalSince1970: Double(value) / 1000) }

    private func first<T: Sendable>(_ stream: AsyncStream<T>) async -> T {
        var iterator = stream.makeAsyncIterator()
        return await iterator.next()!
    }

    func testAddAndReadAClaimRoundTripsSourcesAndExplanation() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        let result = CheckResult(
            verdict: .false, context: "Contexte de verification",
            sources: [Source(title: "AFP Factuel", url: "https://factuel.afp.com/1")])
        let claimId = try await repository.addClaim(
            sessionId: sessionId, text: "Le chomage a double", heardAt: ms(2_000), result: result)
        try await repository.setExplanation(
            claimId: claimId,
            explanation: Explanation(text: "Explication detaillee", sources: [Source(title: "INSEE", url: "https://insee.fr/x")]))

        let claim = await first(repository.claim(id: claimId))
        XCTAssertEqual(claim?.text, "Le chomage a double")
        XCTAssertEqual(claim?.sessionId, sessionId)
        XCTAssertEqual(claim?.result?.verdict, .false)
        XCTAssertEqual(claim?.result?.context, "Contexte de verification")
        XCTAssertEqual(claim?.result?.sources, [Source(title: "AFP Factuel", url: "https://factuel.afp.com/1")])
        XCTAssertEqual(claim?.explanation?.text, "Explication detaillee")
        XCTAssertEqual(claim?.explanation?.sources, [Source(title: "INSEE", url: "https://insee.fr/x")])
        XCTAssertEqual(claim?.heardAt, ms(2_000))
    }

    func testPendingClaimBecomesCheckedAfterSetResult() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        let claimId = try await repository.addClaim(
            sessionId: sessionId, text: "Une affirmation", heardAt: ms(2_000), result: nil)

        let pendingBefore = try await repository.pendingClaims()
        XCTAssertEqual(pendingBefore.count, 1)
        XCTAssertNil(pendingBefore.first?.result)

        try await repository.setResult(claimId: claimId, result: CheckResult(verdict: .accurate, context: "Confirme", sources: []))

        let pendingAfter = try await repository.pendingClaims()
        XCTAssertTrue(pendingAfter.isEmpty)
        let claim = await first(repository.claim(id: claimId))
        XCTAssertEqual(claim?.result?.verdict, .accurate)
    }

    func testTheRewrittenClaimIsKeptAndShownInsteadOfTheTranscribedSentence() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        let heard = "l'isf c'est 5 milliards qui avait été supprimé"
        let rewritten = "L'ISF rapportait 5 milliards d'euros avant sa suppression."
        let addedId = try await repository.addClaim(
            sessionId: sessionId, text: heard, heardAt: ms(2_000),
            result: CheckResult(verdict: .accurate, context: "x", sources: [], checkedText: rewritten))
        let pendingId = try await repository.addClaim(
            sessionId: sessionId, text: heard, heardAt: ms(3_000), result: nil)
        try await repository.setResult(
            claimId: pendingId, result: CheckResult(verdict: .accurate, context: "x", sources: [], checkedText: rewritten))

        for id in [addedId, pendingId] {
            let claim = await first(repository.claim(id: id))!
            XCTAssertEqual(claim.text, heard)
            XCTAssertEqual(claim.checkedText, rewritten)
        }
    }

    func testTextFilterIsCaseAndAccentInsensitive() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        _ = try await repository.addClaim(
            sessionId: sessionId, text: "Le Président a été réélu", heardAt: ms(2_000), result: nil)
        _ = try await repository.addClaim(
            sessionId: sessionId, text: "Autre affirmation sans rapport", heardAt: ms(3_000), result: nil)

        let filtered = await first(repository.sessions(filter: HistoryFilter(query: "president")))

        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.claims.count, 1)
        XCTAssertEqual(filtered.first?.claims.first?.text, "Le Président a été réélu")
    }

    func testVerdictFilterKeepsOnlyMatchingClaims() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        _ = try await repository.addClaim(
            sessionId: sessionId, text: "Affirmation fausse", heardAt: ms(2_000),
            result: CheckResult(verdict: .false, context: "ctx", sources: []))
        _ = try await repository.addClaim(
            sessionId: sessionId, text: "Affirmation vraie", heardAt: ms(3_000),
            result: CheckResult(verdict: .accurate, context: "ctx", sources: []))
        _ = try await repository.addClaim(
            sessionId: sessionId, text: "Affirmation en attente", heardAt: ms(4_000), result: nil)

        let filtered = await first(repository.sessions(filter: HistoryFilter(verdicts: [.false])))

        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.claims.count, 1)
        XCTAssertEqual(filtered.first?.claims.first?.result?.verdict, .false)
    }

    func testUnfilteredSessionsIncludeEmptySessionsFilteredDropsThem() async throws {
        let emptySessionId = try await repository.startSession(at: ms(1_000))
        let otherSessionId = try await repository.startSession(at: ms(5_000))
        _ = try await repository.addClaim(
            sessionId: otherSessionId, text: "Une affirmation", heardAt: ms(6_000), result: nil)

        let unfiltered = await first(repository.sessions(filter: HistoryFilter()))
        XCTAssertEqual(unfiltered.count, 2)
        XCTAssertTrue(unfiltered.contains { $0.sessionId == emptySessionId && $0.claims.isEmpty })

        let filtered = await first(repository.sessions(filter: HistoryFilter(query: "affirmation")))
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.sessionId, otherSessionId)
    }

    func testSessionsAndTheirClaimsAreNewestFirst() async throws {
        let firstSession = try await repository.startSession(at: ms(1_000))
        let secondSession = try await repository.startSession(at: ms(2_000))
        _ = try await repository.addClaim(sessionId: firstSession, text: "entendu en second", heardAt: ms(500), result: nil)
        _ = try await repository.addClaim(sessionId: firstSession, text: "entendu en premier", heardAt: ms(100), result: nil)

        let sessions = await first(repository.sessions(filter: HistoryFilter()))

        XCTAssertEqual(sessions.map(\.sessionId), [secondSession, firstSession])
        let firstSessionHistory = sessions.first { $0.sessionId == firstSession }!
        XCTAssertEqual(firstSessionHistory.claims.map(\.text), ["entendu en second", "entendu en premier"])
    }

    func testDeletingASessionCascadesToItsClaims() async throws {
        let keptSessionId = try await repository.startSession(at: ms(1_000))
        let removedSessionId = try await repository.startSession(at: ms(2_000))
        _ = try await repository.addClaim(sessionId: keptSessionId, text: "Claim conservee", heardAt: ms(1_500), result: nil)
        let removedClaimId = try await repository.addClaim(
            sessionId: removedSessionId, text: "Claim supprimee", heardAt: ms(2_500), result: nil)

        try repository.deleteSession(sessionId: removedSessionId)

        let removed = await first(repository.claim(id: removedClaimId))
        XCTAssertNil(removed)
        let remaining = await first(repository.sessions(filter: HistoryFilter()))
        XCTAssertEqual(remaining.map(\.sessionId), [keptSessionId])
    }

    func testUnfinishedSessionsEndAtTheirLastClaimOrTheirStartWhenEmpty() async throws {
        let withClaims = try await repository.startSession(at: ms(1_000))
        _ = try await repository.addClaim(sessionId: withClaims, text: "Premiere", heardAt: ms(1_500), result: nil)
        _ = try await repository.addClaim(sessionId: withClaims, text: "Derniere", heardAt: ms(4_000), result: nil)
        let empty = try await repository.startSession(at: ms(5_000))
        let current = try await repository.startSession(at: ms(9_000))

        try await repository.closeUnfinishedSessions(before: ms(8_000))

        let sessions = await first(repository.sessions(filter: HistoryFilter()))
        let ended = Dictionary(uniqueKeysWithValues: sessions.map { ($0.sessionId, $0.endedAt) })
        XCTAssertEqual(ended[withClaims]!, ms(4_000))
        XCTAssertEqual(ended[empty]!, ms(5_000))
        XCTAssertNil(ended[current]!)
    }

    func testEndSessionSetsEndedAt() async throws {
        let id = try await repository.startSession(at: ms(1_000))
        try await repository.endSession(sessionId: id, at: ms(3_000))
        let sessions = await first(repository.sessions(filter: HistoryFilter()))
        XCTAssertEqual(sessions.first?.endedAt, ms(3_000))
    }

    func testDeleteClaimRemovesIt() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        let id = try await repository.addClaim(sessionId: sessionId, text: "A", heardAt: ms(2_000), result: nil)
        try await repository.deleteClaim(claimId: id)
        let claim = await first(repository.claim(id: id))
        XCTAssertNil(claim)
    }

    func testClearAllDeletesSessionsAndClaims() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        _ = try await repository.addClaim(sessionId: sessionId, text: "Une affirmation", heardAt: ms(2_000), result: nil)
        _ = try await repository.addClaim(
            sessionId: sessionId, text: "Autre affirmation", heardAt: ms(3_000),
            result: CheckResult(verdict: .misleading, context: "ctx", sources: []))

        try await repository.clearAll()

        let sessions = await first(repository.sessions(filter: HistoryFilter()))
        XCTAssertTrue(sessions.isEmpty)
        let pending = try await repository.pendingClaims()
        XCTAssertTrue(pending.isEmpty)
    }

    func testTheSpeakerRoundTripsAndStaysNilWhenUnknown() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        let named = try await repository.addClaim(
            sessionId: sessionId, text: "Nous avons baisse les impots", heardAt: ms(2_000), result: nil,
            speaker: "Marine Le Pen")
        let unknown = try await repository.addClaim(
            sessionId: sessionId, text: "Le chomage a double", heardAt: ms(3_000), result: nil)

        let namedClaim = await first(repository.claim(id: named))
        let unknownClaim = await first(repository.claim(id: unknown))
        XCTAssertEqual(namedClaim?.speaker, "Marine Le Pen")
        XCTAssertNil(unknownClaim?.speaker)
    }

    // Beyond the Room tests: the observation contract the app relies on.

    func testObservationEmitsTheCurrentValueThenAgainAfterEveryWrite() async throws {
        let sessionId = try await repository.startSession(at: ms(1_000))
        var iterator = repository.sessions(filter: HistoryFilter()).makeAsyncIterator()
        let initial = await iterator.next()
        XCTAssertEqual(initial?.first?.claims.count, 0)

        _ = try await repository.addClaim(sessionId: sessionId, text: "Nouvelle", heardAt: ms(2_000), result: nil)
        let updated = await iterator.next()
        XCTAssertEqual(updated?.first?.claims.map(\.text), ["Nouvelle"])
    }

    func testTheDatabaseFilePersistsAcrossOpens() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("history-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let id: Int64
        do {
            let repo = try SQLiteHistoryRepository(fileURL: url)
            let sessionId = try await repo.startSession(at: ms(1_000))
            id = try await repo.addClaim(sessionId: sessionId, text: "Persistée", heardAt: ms(2_000), result: nil)
        }
        let reopened = try SQLiteHistoryRepository(fileURL: url)
        let claim = await first(reopened.claim(id: id))
        XCTAssertEqual(claim?.text, "Persistée")
    }
}
