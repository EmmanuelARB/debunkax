import XCTest
@testable import DebunkaxCore

final class PendingClaimCheckerTests: XCTestCase {

    func testCheckAllSetsResultsForPendingClaimsCheckedOnTheirOwnText() async throws {
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let sessionId = try await history.startSession(at: testStart)
        let idA = try await history.addClaim(sessionId: sessionId, text: "Le chomage a baisse de dix pour cent.", heardAt: testStart, result: nil)
        let idB = try await history.addClaim(sessionId: sessionId, text: "La dette a augmente de cinq points.", heardAt: testStart, result: nil)
        client.enqueueChecked(makeResult(.accurate))
        client.enqueueChecked(makeResult(.false))

        let report = try await PendingClaimChecker(history: history).checkAll(client)

        XCTAssertEqual(report.checked, 2)
        XCTAssertEqual(report.stillPending, 0)
        XCTAssertNil(report.failure)
        XCTAssertEqual(history.claimRecord(idA)?.result?.verdict, .accurate)
        XCTAssertEqual(history.claimRecord(idB)?.result?.verdict, .false)
        let stillPending = try await history.pendingClaims()
        XCTAssertTrue(stillPending.isEmpty)
        // The surrounding transcript is gone by the time a Pending Claim is re-checked.
        XCTAssertTrue(client.checkedClaims.allSatisfy { $0.surroundingTranscript.isEmpty })
    }

    func testCheckAllDeletesNotAClaimEntries() async throws {
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let sessionId = try await history.startSession(at: testStart)
        let id = try await history.addClaim(sessionId: sessionId, text: "Quel beau temps aujourd hui.", heardAt: testStart, result: nil)
        client.enqueueNotAClaim()

        let report = try await PendingClaimChecker(history: history).checkAll(client)

        XCTAssertEqual(report.checked, 1)
        XCTAssertNil(history.claimRecord(id))
    }

    func testCheckAllStopsAtTheFirstOfflineFailureLeavingTheRestPending() async throws {
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let sessionId = try await history.startSession(at: testStart)
        let idA = try await history.addClaim(sessionId: sessionId, text: "Premiere affirmation en attente.", heardAt: testStart, result: nil)
        let idB = try await history.addClaim(sessionId: sessionId, text: "Deuxieme affirmation en attente.", heardAt: testStart, result: nil)
        let idC = try await history.addClaim(sessionId: sessionId, text: "Troisieme affirmation en attente.", heardAt: testStart, result: nil)
        client.enqueueFailure(.offline)
        client.enqueueChecked(makeResult(.accurate))
        client.enqueueChecked(makeResult(.accurate))

        let report = try await PendingClaimChecker(history: history).checkAll(client)

        XCTAssertEqual(report.checked, 0)
        XCTAssertEqual(report.stillPending, 3)
        XCTAssertEqual(report.failure, .offline)
        XCTAssertEqual(client.checkedClaims.count, 1)
        XCTAssertNil(history.claimRecord(idA)?.result)
        XCTAssertNil(history.claimRecord(idB)?.result)
        XCTAssertNil(history.claimRecord(idC)?.result)
    }

    func testCheckAllKeepsGoingAfterARateLimitedClaim() async throws {
        let history = FakeHistoryRepository()
        let client = FakeCheckClient()
        let sessionId = try await history.startSession(at: testStart)
        let idA = try await history.addClaim(sessionId: sessionId, text: "Premiere affirmation en attente.", heardAt: testStart, result: nil)
        let idB = try await history.addClaim(sessionId: sessionId, text: "Deuxieme affirmation en attente.", heardAt: testStart, result: nil)
        client.enqueueFailure(.rateLimited)
        client.enqueueChecked(makeResult(.accurate))

        let report = try await PendingClaimChecker(history: history).checkAll(client)

        XCTAssertEqual(report.checked, 1)
        XCTAssertEqual(report.stillPending, 1)
        XCTAssertEqual(report.failure, .rateLimited)
        XCTAssertNil(history.claimRecord(idA)?.result)
        XCTAssertEqual(history.claimRecord(idB)?.result?.verdict, .accurate)
    }
}
