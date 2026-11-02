import Foundation
import Synchronization

/// The Speaker guesses of the last couple of minutes, each for the `window` of audio ending at its
/// time. Holds names and scores only, never audio, and lives in memory for one Listening Session.
public final class SpeakerTimeline: Sendable {
    /// The transcript window: what a « vous » can still refer to.
    public static let addresseeLookback: TimeInterval = 30

    private struct Entry: Sendable {
        let endMillis: Int64
        let guess: SpeakerGuess
    }

    private let windowMillis: Int64
    private let keepMillis: Int64
    private let guesses = Mutex<[Entry]>([])

    public init(window: TimeInterval = 5, keep: TimeInterval = 120) {
        windowMillis = Int64((window * 1000).rounded())
        keepMillis = Int64((keep * 1000).rounded())
    }

    public func add(end: Date, guess: SpeakerGuess) {
        let endMillis = end.epochMillis
        guesses.withLock { list in
            list.append(Entry(endMillis: endMillis, guess: guess))
            while let first = list.first, first.endMillis < endMillis - keepMillis {
                list.removeFirst()
            }
        }
    }

    /// Who spoke between `from` and `to`, or nil when in doubt: nobody heard, two leaders named,
    /// or as many unsure windows as named ones. A wrong name is worse than none.
    public func speakerBetween(from: Date, to: Date) -> String? {
        speakerBetween(fromMillis: from.epochMillis, toMillis: to.epochMillis)
    }

    private func speakerBetween(fromMillis: Int64, toMillis: Int64) -> String? {
        let heard = guesses.withLock { list in
            list.filter { $0.endMillis > fromMillis && $0.endMillis - windowMillis < toMillis }
                .map(\.guess)
                .filter { $0 != .silence }
        }
        var names: [String] = []
        var namedCount = 0
        for guess in heard {
            if case .named(let name, _) = guess {
                namedCount += 1
                if !names.contains(name) { names.append(name) }
            }
        }
        guard names.count == 1, let name = names.first else { return nil }
        return namedCount * 2 > heard.count ? name : nil
    }

    /// The last named person other than `claimant` and the `isJournalist` ones in the `addresseeLookback`
    /// before `before`, searched in `window` steps from the most recent, each with the `speakerBetween` doubt rule.
    public func addresseeBefore(_ before: Date, claimant: String, isJournalist: (String) -> Bool) -> String? {
        var end = before.epochMillis
        let oldest = end - Int64(Self.addresseeLookback * 1000)
        while end > oldest {
            if let name = speakerBetween(fromMillis: end - windowMillis, toMillis: end), name != claimant,
                !isJournalist(name)
            {
                return name
            }
            end -= windowMillis
        }
        return nil
    }

    public func clear() {
        guesses.withLock { $0.removeAll() }
    }
}
