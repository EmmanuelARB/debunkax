import Synchronization
import XCTest
@testable import DebunkaxCore

final class FallbackSpeechEngineTests: XCTestCase {

    private func segment(_ text: String) -> TranscriptSegment {
        TranscriptSegment(text: text, isFinal: true, at: Date(timeIntervalSince1970: 0))
    }

    /// An engine whose stream runs `behavior` once per `transcribe()` call.
    private final class FakeEngine: SpeechEngine {
        typealias Continuation = AsyncThrowingStream<TranscriptSegment, Error>.Continuation

        private let behavior: @Sendable (Continuation) -> Void
        private let started = Mutex(0)

        init(_ behavior: @escaping @Sendable (Continuation) -> Void) { self.behavior = behavior }

        var timesStarted: Int { started.withLock { $0 } }

        func transcribe() -> AsyncThrowingStream<TranscriptSegment, Error> {
            started.withLock { $0 += 1 }
            let behavior = behavior
            return AsyncThrowingStream { behavior($0) }
        }
    }

    private func collect(_ engine: SpeechEngine) async throws -> [TranscriptSegment] {
        var recorded: [TranscriptSegment] = []
        for try await s in engine.transcribe() { recorded.append(s) }
        return recorded
    }

    func testStaysOnThePrimaryEngineWhileItWorks() async throws {
        let seg = segment("PRIMAIRE")
        let primary = FakeEngine { c in
            c.yield(seg)
            c.finish()
        }
        let fallbackSeg = segment("SECOURS")
        let fallback = FakeEngine { c in
            c.yield(fallbackSeg)
            c.finish()
        }
        let engine = FallbackSpeechEngine(primary: primary, fallback: fallback)

        let recorded = try await collect(engine)

        XCTAssertEqual(recorded, [segment("PRIMAIRE")])
        XCTAssertEqual(primary.timesStarted, 1)
        XCTAssertEqual(fallback.timesStarted, 0)
    }

    func testSwitchesToTheFallbackOnceThePrimaryReportsUnsupportedAndStaysSwitched() async throws {
        let primary = FakeEngine { $0.finish(throwing: SpeechFailure.unsupported("pas pris en charge")) }
        let seg = segment("SECOURS")
        let fallback = FakeEngine { c in
            c.yield(seg)
            c.finish()
        }
        let engine = FallbackSpeechEngine(primary: primary, fallback: fallback)

        let firstRun = try await collect(engine)
        XCTAssertEqual(firstRun, [segment("SECOURS")])
        XCTAssertEqual(primary.timesStarted, 1)
        XCTAssertEqual(fallback.timesStarted, 1)

        // A second session (e.g. after the user stops and restarts listening) never retries the primary.
        let secondRun = try await collect(engine)
        XCTAssertEqual(secondRun, [segment("SECOURS")])
        XCTAssertEqual(primary.timesStarted, 1)
        XCTAssertEqual(fallback.timesStarted, 2)
    }

    func testANonUnsupportedFailureFromThePrimaryIsNotSwallowedAndTheFallbackNeverStarts() async {
        let primary = FakeEngine { $0.finish(throwing: SpeechFailure.engine("erreur moteur")) }
        let seg = segment("SECOURS")
        let fallback = FakeEngine { c in
            c.yield(seg)
            c.finish()
        }
        let engine = FallbackSpeechEngine(primary: primary, fallback: fallback)

        do {
            _ = try await collect(engine)
            XCTFail("expected a SpeechFailure")
        } catch let failure as SpeechFailure {
            XCTAssertEqual(failure, .engine("erreur moteur"))
        } catch {
            XCTFail("unexpected error \(error)")
        }
        XCTAssertEqual(fallback.timesStarted, 0)
    }

    func testSegmentsEmittedBeforeTheUnsupportedFailureAreKept() async throws {
        let before = segment("AVANT")
        let primary = FakeEngine { c in
            c.yield(before)
            c.finish(throwing: SpeechFailure.unsupported("non"))
        }
        let after = segment("APRES")
        let fallback = FakeEngine { c in
            c.yield(after)
            c.finish()
        }

        let recorded = try await collect(FallbackSpeechEngine(primary: primary, fallback: fallback))

        XCTAssertEqual(recorded, [segment("AVANT"), segment("APRES")])
    }
}
