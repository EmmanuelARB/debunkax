import Foundation

public struct ClaimRecord: Hashable, Sendable {
    public var id: Int64
    public var sessionId: Int64
    public var text: String
    public var heardAt: Date
    /// Nil while the Claim is a Pending Claim.
    public var result: CheckResult?
    public var explanation: Explanation?
    /// Recognised by voice when heard; nil when unknown.
    public var speaker: String?

    public init(
        id: Int64, sessionId: Int64, text: String, heardAt: Date,
        result: CheckResult?, explanation: Explanation?, speaker: String? = nil
    ) {
        self.id = id
        self.sessionId = sessionId
        self.text = text
        self.heardAt = heardAt
        self.result = result
        self.explanation = explanation
        self.speaker = speaker
    }

    /// The form this Claim was checked as: the clear rewrite, else the sentence as transcribed. The quote shown is `text`.
    public var checkedText: String { result?.checkedText ?? text }
}

public struct SessionHistory: Hashable, Sendable {
    public var sessionId: Int64
    public var startedAt: Date
    public var endedAt: Date?
    public var claims: [ClaimRecord]

    public init(sessionId: Int64, startedAt: Date, endedAt: Date?, claims: [ClaimRecord]) {
        self.sessionId = sessionId
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.claims = claims
    }
}

public struct HistoryFilter: Hashable, Sendable {
    public var query: String
    public var verdicts: Set<Verdict>

    public init(query: String = "", verdicts: Set<Verdict> = []) {
        self.query = query
        self.verdicts = verdicts
    }
}

/// The only persisted data. Never store audio or transcripts here, only Claim text.
public protocol HistoryRepository: Sendable {
    func startSession(at: Date) async throws -> Int64
    func endSession(sessionId: Int64, at: Date) async throws

    /// Ends sessions started before `before` that were never stopped (app killed), at their last Claim or their start.
    func closeUnfinishedSessions(before: Date) async throws

    func addClaim(sessionId: Int64, text: String, heardAt: Date, result: CheckResult?, speaker: String?) async throws -> Int64
    func setResult(claimId: Int64, result: CheckResult) async throws
    func setExplanation(claimId: Int64, explanation: Explanation) async throws
    func deleteClaim(claimId: Int64) async throws

    func pendingClaims() async throws -> [ClaimRecord]

    /// Newest session first, and newest claim first within each; sessions with no matching claim are omitted when a filter is active.
    func sessions(filter: HistoryFilter) -> AsyncStream<[SessionHistory]>
    func claim(id: Int64) -> AsyncStream<ClaimRecord?>

    func clearAll() async throws
}

extension HistoryRepository {
    public func addClaim(sessionId: Int64, text: String, heardAt: Date, result: CheckResult?) async throws -> Int64 {
        try await addClaim(sessionId: sessionId, text: text, heardAt: heardAt, result: result, speaker: nil)
    }
}
