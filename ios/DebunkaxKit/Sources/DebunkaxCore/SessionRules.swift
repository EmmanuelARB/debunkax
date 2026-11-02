import Foundation
import Synchronization

public enum AlertPolicy {
    public static let maxDelay: TimeInterval = 15

    public static func shouldAlert(verdict: Verdict, heardAt: Date, readyAt: Date) -> Bool {
        verdict.raisesAlert && readyAt.epochMillis - heardAt.epochMillis <= Int64(maxDelay * 1000)
    }
}

/// Counts Checks within one Listening Session; `limit` nil means unlimited.
public final class CheckBudget: Sendable {
    private let limit: Int?
    private let usedCount = Mutex<Int>(0)

    public init(limit: Int?) {
        self.limit = limit
    }

    public var used: Int { usedCount.withLock { $0 } }

    public var exhausted: Bool {
        guard let limit else { return false }
        return used >= limit
    }

    public func tryConsume() -> Bool {
        usedCount.withLock { used in
            if let limit, used >= limit { return false }
            used += 1
            return true
        }
    }
}

/// Reuses a Verdict when a Claim repeats within the same Listening Session.
/// Politicians repeat figures with slightly different wording, hence token similarity rather than equality.
public final class RepeatedClaimMatcher: Sendable {
    private struct Known {
        let tokens: Set<String>
        let speaker: String?
        let result: CheckResult
    }

    private let threshold: Double
    private let known = Mutex<[Known]>([])

    public init(threshold: Double = 0.8) {
        self.threshold = threshold
    }

    /// A repeat only counts for the same `speaker`: "je n'ai jamais voté X" depends on who says it.
    public func find(_ text: String, speaker: String? = nil) -> CheckResult? {
        let tokens = Self.tokens(text)
        if tokens.isEmpty { return nil }
        return known.withLock { list in
            list.first { $0.speaker == speaker && Self.jaccard(tokens, $0.tokens) >= threshold }?.result
        }
    }

    public func remember(_ text: String, result: CheckResult, speaker: String? = nil) {
        let tokens = Self.tokens(text)
        if !tokens.isEmpty {
            known.withLock { $0.append(Known(tokens: tokens, speaker: speaker, result: result)) }
        }
    }

    public func clear() {
        known.withLock { $0.removeAll() }
    }

    private static func jaccard(_ a: Set<String>, _ b: Set<String>) -> Double {
        Double(a.intersection(b).count) / Double(a.union(b).count)
    }

    public static func tokens(_ text: String) -> Set<String> {
        Set(
            normalize(text).split(separator: " ").map(String.init).filter { token in
                token.utf16.count > 1
                    || token.unicodeScalars.contains { $0.properties.numericType == .decimal }
            })
    }

    private static let combiningMarks = Rx(#"\p{Mn}+"#)
    private static let apostrophes = Rx("[’']")
    private static let thousands = Rx(#"(?<=[0-9])[ \t\n\x0B\f\r.](?=[0-9]{3}\b)"#)
    private static let nonWord = Rx(#"[^\p{L}\p{N}%,]+"#)
    private static let strayCommas = Rx(#"(?<![0-9]),|,(?![0-9])"#)
    private static let spaces = Rx(#"[ \t\n\x0B\f\r]+"#)

    public static func normalize(_ text: String) -> String {
        var s = text.lowercased().decomposedStringWithCanonicalMapping
        s = combiningMarks.replacing(in: s, with: "")
        s = apostrophes.replacing(in: s, with: " ")
        s = thousands.replacing(in: s, with: "")
        s = nonWord.replacing(in: s, with: " ")
        s = strayCommas.replacing(in: s, with: " ")
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return spaces.replacing(in: s, with: " ")
    }
}
