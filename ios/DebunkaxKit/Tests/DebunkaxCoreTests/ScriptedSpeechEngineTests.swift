import Synchronization
import XCTest
@testable import DebunkaxCore

final class ScriptedSpeechEngineTests: XCTestCase {

    /// Virtual time: a sleeper that only adds the requested delay to a counter.
    private final class VirtualTime: Sendable {
        private let elapsed = Mutex<TimeInterval>(0)
        var seconds: TimeInterval { elapsed.withLock { $0 } }
        var sleeper: Sleeper { { [self] delay in elapsed.withLock { $0 += delay } } }
    }

    private struct Recorded {
        let segment: TranscriptSegment
        let atSeconds: TimeInterval
    }

    func testReplaysEachLineAsAPartialThenAFinalFinalsSpacedByTheInterval() async throws {
        let interval: TimeInterval = 4
        let lines = [
            "Le chômage recule ce trimestre selon l'INSEE",
            "Le déficit public dépasse le seuil européen",
        ]
        let time = VirtualTime()
        let engine = ScriptedSpeechEngine(lines: lines, interval: interval, sleep: time.sleeper)

        var recorded: [Recorded] = []
        for try await segment in engine.transcribe() {
            recorded.append(Recorded(segment: segment, atSeconds: time.seconds))
        }

        XCTAssertEqual(recorded.count, 4)

        // First line: partial then final, final lands exactly one interval in.
        XCTAssertFalse(recorded[0].segment.isFinal)
        XCTAssertEqual(recorded[0].segment.text, String(lines[0].prefix(lines[0].count / 2)))
        XCTAssertTrue(recorded[1].segment.isFinal)
        XCTAssertEqual(recorded[1].segment.text, lines[0])
        XCTAssertEqual(recorded[1].atSeconds, interval, accuracy: 1e-9)
        XCTAssertLessThan(recorded[0].atSeconds, recorded[1].atSeconds)

        // Second line: another full interval later, same partial/final shape.
        XCTAssertFalse(recorded[2].segment.isFinal)
        XCTAssertEqual(recorded[2].segment.text, String(lines[1].prefix(lines[1].count / 2)))
        XCTAssertTrue(recorded[3].segment.isFinal)
        XCTAssertEqual(recorded[3].segment.text, lines[1])
        XCTAssertEqual(recorded[3].atSeconds, 2 * interval, accuracy: 1e-9)
        XCTAssertLessThan(recorded[2].atSeconds, recorded[3].atSeconds)
    }

    func testAnEmptyScriptCompletesWithoutEmittingAnything() async throws {
        let engine = ScriptedSpeechEngine(lines: [], interval: 1, sleep: VirtualTime().sleeper)

        var recorded: [TranscriptSegment] = []
        for try await segment in engine.transcribe() { recorded.append(segment) }

        XCTAssertTrue(recorded.isEmpty)
    }

    func testThePartialLeadIsAFifthOfTheIntervalAtLeastOneMillisecond() {
        XCTAssertEqual(ScriptedSpeechEngine.partialLead(for: 4), 0.8, accuracy: 1e-12)
        XCTAssertEqual(ScriptedSpeechEngine.partialLead(for: 0), 0.001, accuracy: 1e-12)
    }
}
