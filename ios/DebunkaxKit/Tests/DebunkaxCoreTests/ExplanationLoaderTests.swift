import XCTest
@testable import DebunkaxCore

final class ExplanationLoaderTests: XCTestCase {

    func testReturnsAnExistingExplanationWithoutCallingTheClient() async throws {
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let sessionId = try await history.startSession(at: testStart)
        let claimResult = makeResult(.false)
        let claimId = try await history.addClaim(
            sessionId: sessionId, text: "Une affirmation deja verifiee.", heardAt: testStart, result: claimResult)
        let existing = Explanation(text: "Explication deja produite.", sources: [Source(title: "AFP Factuel", url: "https://factuel.afp.com/x")])

        let loaded = try await ExplanationLoader(history: history).load(
            claimId: claimId, claimText: "Une affirmation deja verifiee.", result: claimResult, existing: existing, client: client)

        XCTAssertEqual(loaded, existing)
        XCTAssertTrue(client.explainCalls.isEmpty)
        XCTAssertTrue(history.setExplanationCalls.isEmpty)
    }

    func testProducesAndSavesTheExplanationWhenNoneExists() async throws {
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let sessionId = try await history.startSession(at: testStart)
        let claimResult = makeResult(.misleading)
        let claimText = "Une affirmation a expliquer."
        let claimId = try await history.addClaim(sessionId: sessionId, text: claimText, heardAt: testStart, result: claimResult)
        let produced = Explanation(text: "Explication produite.", sources: [Source(title: "Les Decodeurs", url: "https://lemonde.fr/les-decodeurs/x")])
        client.explanationToReturn = produced

        let loaded = try await ExplanationLoader(history: history).load(
            claimId: claimId, claimText: claimText, result: claimResult, existing: nil, client: client)

        XCTAssertEqual(loaded, produced)
        XCTAssertEqual(client.explainCalls.count, 1)
        XCTAssertEqual(client.explainCalls[0].claimText, claimText)
        XCTAssertEqual(client.explainCalls[0].result, claimResult)
        XCTAssertEqual(history.claimRecord(claimId)?.explanation, produced)
        XCTAssertEqual(history.setExplanationCalls.count, 1)
        XCTAssertEqual(history.setExplanationCalls[0].claimId, claimId)
        XCTAssertEqual(history.setExplanationCalls[0].explanation, produced)
    }
}
