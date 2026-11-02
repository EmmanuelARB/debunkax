import Foundation
import Synchronization

/// `SpeechEngine` that uses `primary` (Apple's SpeechAnalyzer) as long as it works, and switches to `fallback`
/// (sherpa-onnx) once `primary` reports `SpeechFailure.unsupported` on this device. The switch is
/// permanent for the rest of the process: an unsupported model does not become supported
/// mid-run, so there is no point retrying it every session.
public final class FallbackSpeechEngine: SpeechEngine {
    private let primary: any SpeechEngine
    private let fallback: any SpeechEngine
    private let useFallback = Mutex<Bool>(false)

    public init(primary: any SpeechEngine, fallback: any SpeechEngine) {
        self.primary = primary
        self.fallback = fallback
    }

    public func transcribe() -> AsyncThrowingStream<TranscriptSegment, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    if self.useFallback.withLock({ $0 }) {
                        try await Self.forward(self.fallback, to: continuation)
                    } else {
                        do {
                            try await Self.forward(self.primary, to: continuation)
                        } catch let failure as SpeechFailure {
                            guard case .unsupported = failure else { throw failure }
                            self.useFallback.withLock { $0 = true }
                            try await Self.forward(self.fallback, to: continuation)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private static func forward(
        _ engine: any SpeechEngine, to continuation: AsyncThrowingStream<TranscriptSegment, Error>.Continuation
    ) async throws {
        for try await segment in engine.transcribe() {
            continuation.yield(segment)
        }
    }
}
