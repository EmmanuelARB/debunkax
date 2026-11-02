import Foundation

/// `SpeechEngine` that replays a fixed script instead of listening to the microphone.
///
/// For each line, in order, it emits one partial segment (the first half of the line) shortly
/// before emitting the line in full as a final segment. Finals are spaced `interval` apart.
///
/// For development without a microphone, and for tests.
public final class ScriptedSpeechEngine: SpeechEngine {
    private let lines: [String]
    private let interval: TimeInterval
    private let now: @Sendable () -> Date
    private let sleep: Sleeper

    public init(
        lines: [String], interval: TimeInterval,
        now: @escaping @Sendable () -> Date = { Date() },
        sleep: @escaping Sleeper = Sleepers.real
    ) {
        self.lines = lines
        self.interval = interval
        self.now = now
        self.sleep = sleep
    }

    public func transcribe() -> AsyncThrowingStream<TranscriptSegment, Error> {
        let lines = lines, interval = interval, now = now, sleep = sleep
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let partialLead = Self.partialLead(for: interval)
                    for line in lines {
                        try await sleep(interval - partialLead)
                        continuation.yield(TranscriptSegment(text: Self.firstHalf(of: line), isFinal: false, at: now()))
                        try await sleep(partialLead)
                        continuation.yield(TranscriptSegment(text: line, isFinal: true, at: now()))
                    }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// How long before each final its partial is emitted: a fifth of the interval, at least 1 ms.
    static func partialLead(for interval: TimeInterval) -> TimeInterval {
        let fifth = interval / 5
        return fifth <= 0 ? 0.001 : fifth
    }

    /// Kotlin's `line.take(line.length / 2)`, counted in UTF-16 units like `String.length`.
    static func firstHalf(of line: String) -> String {
        (line as NSString).substring(to: line.utf16.count / 2)
    }
}
