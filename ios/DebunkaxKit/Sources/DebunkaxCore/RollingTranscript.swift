import Foundation
import Synchronization

/// In-memory only: the last `window` of final transcript segments, used as context for a Check.
public final class RollingTranscript: Sendable {
    private struct Segment {
        let text: String
        let at: Date
    }

    private let window: TimeInterval
    private let segments = Mutex<[Segment]>([])

    public init(window: TimeInterval = 30) {
        self.window = window
    }

    public func append(_ text: String, at: Date) {
        if text.isBlank { return }
        let segment = Segment(text: text.trimmingCharacters(in: .whitespacesAndNewlines), at: at)
        segments.withLock {
            $0.append(segment)
            prune(&$0, now: at)
        }
    }

    public func textAt(_ now: Date) -> String {
        segments.withLock {
            prune(&$0, now: now)
            return $0.map(\.text).joined(separator: " ")
        }
    }

    public func clear() {
        segments.withLock { $0.removeAll() }
    }

    private func prune(_ segments: inout [Segment], now: Date) {
        let cutoff = now.epochMillis - Int64((window * 1000).rounded())
        while let first = segments.first, first.at.epochMillis < cutoff {
            segments.removeFirst()
        }
    }
}
