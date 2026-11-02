import Foundation

public enum Verdict: String, CaseIterable, Codable, Sendable {
    case `false` = "FALSE"
    case misleading = "MISLEADING"
    case lacksContext = "LACKS_CONTEXT"
    case unverifiable = "UNVERIFIABLE"
    case accurate = "ACCURATE"

    public var raisesAlert: Bool {
        switch self {
        case .false, .misleading, .lacksContext: true
        case .unverifiable, .accurate: false
        }
    }
}

public struct Source: Hashable, Codable, Sendable {
    public var title: String
    public var url: String

    public init(title: String, url: String) {
        self.title = title
        self.url = url
    }
}

/// A Claim as heard live. Holds the surrounding transcript, so it must never be persisted.
public struct HeardClaim: Hashable, Sendable {
    public var text: String
    public var heardAt: Date
    public var surroundingTranscript: String
    /// Who said it, recognised by voice; nil when unknown (ADR 0003).
    public var speaker: String?
    /// Who a « tu » / « vous » refers to: the most recent other Speaker; nil when unknown.
    public var addressedSpeaker: String?

    public init(
        text: String, heardAt: Date, surroundingTranscript: String,
        speaker: String? = nil, addressedSpeaker: String? = nil
    ) {
        self.text = text
        self.heardAt = heardAt
        self.surroundingTranscript = surroundingTranscript
        self.speaker = speaker
        self.addressedSpeaker = addressedSpeaker
    }
}

public struct CheckResult: Hashable, Codable, Sendable {
    public var verdict: Verdict
    public var context: String
    public var sources: [Source]
    /// The Claim as it was checked, rewritten from the heard sentence into a clear one; nil when checked as heard.
    public var checkedText: String?

    public init(verdict: Verdict, context: String, sources: [Source], checkedText: String? = nil) {
        self.verdict = verdict
        self.context = context
        self.sources = sources
        self.checkedText = checkedText
    }
}

public enum CheckOutcome: Hashable, Sendable {
    case checked(CheckResult)
    /// The provider judged the sentence not to be a checkable factual assertion after all.
    case notAClaim
}

public struct Explanation: Hashable, Codable, Sendable {
    public var text: String
    public var sources: [Source]

    public init(text: String, sources: [Source]) {
        self.text = text
        self.sources = sources
    }
}

public enum CheckFailure: Error, LocalizedError, Hashable, Sendable {
    case offline
    case invalidKey
    case noCredit
    case searchDisabled
    case rateLimited
    case budgetReached
    case unexpected(String)

    public var message: String {
        switch self {
        case .offline: "Pas de connexion"
        case .invalidKey: "Clé API invalide"
        case .noCredit: "Crédit épuisé chez le fournisseur"
        case .searchDisabled: "La recherche web est désactivée sur ce compte"
        case .rateLimited: "Trop de requêtes, réessai plus tard"
        case .budgetReached: "Limite de vérifications de la session atteinte"
        case .unexpected(let detail): detail
        }
    }

    public var errorDescription: String? { message }
}

public protocol CheckClient: Sendable {
    func check(_ claim: HeardClaim) async throws -> CheckOutcome
    func explain(claimText: String, result: CheckResult) async throws -> Explanation

    /// What this client's requests have cost so far, or nil when it can't tell.
    var spent: Spend? { get }
}

extension CheckClient {
    public var spent: Spend? { nil }
}

/// `estimated` when worked out from token counts and list prices rather than reported by the provider.
public struct Spend: Hashable, Sendable {
    public var usd: Double
    public var estimated: Bool

    public init(usd: Double, estimated: Bool) {
        self.usd = usd
        self.estimated = estimated
    }
}
