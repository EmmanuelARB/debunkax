import Foundation

public enum SpeakerRole: Hashable, Sendable {
    case politician
    case journalist
}

/// One enrolled person and their voice prints: unit vectors, one per clean clip (rally, studio…).
public struct EnrolledSpeaker: Hashable, Sendable {
    public var name: String
    public var prints: [[Float]]
    public var role: SpeakerRole

    public init(name: String, prints: [[Float]], role: SpeakerRole = .politician) {
        self.name = name
        self.prints = prints
        self.role = role
    }
}

public enum SpeakerGuess: Hashable, Sendable {
    case silence
    case named(name: String, score: Float)
    /// Nobody is close enough, or two people are too close to tell apart.
    case unknown(closest: String, score: Float)
}

/// Open-set matching, as in spikes/speakers/live.py: a person scores the best cosine over their
/// prints, and is named only above `threshold` and ahead of the runner-up by `margin`.
public enum SpeakerMatcher {
    public static let defaultThreshold: Float = 0.45
    public static let margin: Float = 0.05

    /// `explain` receives why the guess came out so, for debug logs.
    public static func guess(
        embedding: [Float],
        speakers: [EnrolledSpeaker],
        threshold: Float = defaultThreshold,
        explain: (String) -> Void = { _ in }
    ) -> SpeakerGuess {
        let unit = normalized(embedding)
        // Stable descending sort: ties keep the enrolment order, like Kotlin's sortedByDescending.
        var ranked: [(index: Int, name: String, score: Float)] = []
        for (index, speaker) in speakers.enumerated() {
            var bestScore: Float = -.infinity
            for vector in speaker.prints { bestScore = max(bestScore, dot(vector, unit)) }
            ranked.append((index: index, name: speaker.name, score: bestScore))
        }
        ranked.sort { $0.score != $1.score ? $0.score > $1.score : $0.index < $1.index }
        guard let best = ranked.first else { return .unknown(closest: "-", score: -1) }
        let top = best.score
        let runner = ranked.count > 1 ? ranked[1].name : "-"
        let second: Float = ranked.count > 1 ? ranked[1].score : -1
        let reason: String
        if top < threshold {
            reason = "under \(format(threshold))"
        } else if top - second < margin {
            reason = "margin \(format(top - second)) < \(format(margin))"
        } else {
            reason = "ok"
        }
        explain("\(format(top)) \(best.name) vs \(format(second)) \(runner) -> \(reason)")
        return reason != "ok" ? .unknown(closest: best.name, score: top) : .named(name: best.name, score: top)
    }

    public static func normalized(_ v: [Float]) -> [Float] {
        let norm = dot(v, v).squareRoot()
        return v.map { $0 / norm }
    }

    private static func dot(_ a: [Float], _ b: [Float]) -> Float {
        var sum: Float = 0
        for i in a.indices { sum += a[i] * b[i] }
        return sum
    }

    private static func format(_ x: Float) -> String { String(format: "%.2f", Double(x)) }
}
