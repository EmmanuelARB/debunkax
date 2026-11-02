import Synchronization
import XCTest
@testable import DebunkaxCore

final class SpeakerTrackerTests: XCTestCase {

    /// Names Marine Le Pen for every window and records how many samples each window had.
    private final class FakeIdentifier: SpeakerEmbeddingIdentifier {
        let speakers = [
            EnrolledSpeaker(name: "Marine Le Pen", prints: [[1, 0]]),
            EnrolledSpeaker(name: "Ruth Elkrief", prints: [[0, 1]], role: .journalist),
        ]
        private let windows = Mutex<[Int]>([])
        var windowSizes: [Int] { windows.withLock { $0 } }

        func identify(_ samples: [Float], threshold: Float) -> SpeakerGuess {
            windows.withLock { $0.append(samples.count) }
            return .named(name: "Marine Le Pen", score: 0.8)
        }
    }

    private final class TapHolder: Sendable {
        private let tap = Mutex<AudioChunkTap?>(nil)
        var isSet: Bool { tap.withLock { $0 != nil } }
        func set(_ newTap: AudioChunkTap?) { tap.withLock { $0 = newTap } }
        func deliver(_ chunk: [Int16]) { tap.withLock { $0 }?(chunk) }
    }

    private func waitForWindows(_ identifier: FakeIdentifier, count: Int) async {
        let deadline = Date().addingTimeInterval(5)
        while identifier.windowSizes.count < count && Date() < deadline {
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
    }

    func testWindowsOfFiveSecondsEveryTwoAndAHalfAndTheSpeakerIsNamed() async {
        let clock = TestClock()
        let holder = TapHolder()
        let identifier = FakeIdentifier()
        let tracker = SpeakerTracker(
            setTap: { holder.set($0) }, identifier: { identifier }, now: clock.reader)

        tracker.start()
        XCTAssertTrue(holder.isSet)
        // 7.5 s of audio in 100 ms chunks: the first window fills at 5 s, the second at 7.5 s.
        let chunk = [Int16](repeating: 1000, count: 1600)
        for _ in 0..<49 { holder.deliver(chunk) }
        clock.advance(5)
        holder.deliver(chunk)
        await waitForWindows(identifier, count: 1)  // windows are conflated, so let the worker take the first
        for _ in 50..<74 { holder.deliver(chunk) }
        clock.advance(2.5)
        holder.deliver(chunk)
        await waitForWindows(identifier, count: 2)

        XCTAssertEqual(identifier.windowSizes, [80000, 80000])
        // The second window ends at t0 + 7.5 s: it covers 2.5 s to 7.5 s.
        let end = testStart.addingTimeInterval(7.5)
        for _ in 0..<500 where tracker.speakerBetween(from: end.addingTimeInterval(-1), to: end) == nil {
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
        XCTAssertEqual(tracker.speakerBetween(from: end.addingTimeInterval(-1), to: end), "Marine Le Pen")

        tracker.stop()
        XCTAssertFalse(holder.isSet)
        XCTAssertNil(tracker.speakerBetween(from: end.addingTimeInterval(-1), to: end))
    }

    func testNoIdentifierMeansEverySpeakerStaysUnknown() async {
        let holder = TapHolder()
        let tracker = SpeakerTracker(setTap: { holder.set($0) }, identifier: { nil })

        tracker.start()
        holder.deliver([Int16](repeating: 1000, count: 80000))
        try? await Task.sleep(nanoseconds: 30_000_000)

        XCTAssertNil(tracker.speakerBetween(from: testStart, to: Date()))
        tracker.stop()
    }

    func testRmsAndNormalisationToTheEnrolmentLevel() {
        XCTAssertEqual(SpeakerAudio.rms([0.5, -0.5, 0.5, -0.5]), 0.5, accuracy: 1e-6)
        XCTAssertNil(SpeakerAudio.normalizedForIdentification([Float](repeating: 0.0001, count: 100)))
        let loud = SpeakerAudio.normalizedForIdentification([Float](repeating: 0.005, count: 100))
        XCTAssertEqual(SpeakerAudio.rms(loud ?? []), 0.05, accuracy: 1e-5)
    }
}
