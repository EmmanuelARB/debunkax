import XCTest
@testable import DebunkaxCore

final class SpeakerMatcherTests: XCTestCase {
    private let speakers = [
        EnrolledSpeaker(name: "A", prints: [[1, 0, 0], [0, 0, 1]]),
        EnrolledSpeaker(name: "B", prints: [[0, 1, 0]]),
    ]

    func testNamesTheClosestWhenClearlyAhead() {
        XCTAssertEqual(SpeakerMatcher.guess(embedding: [0, 0, 2], speakers: speakers), .named(name: "A", score: 1))
    }

    func testUnknownBelowThreshold() {
        let guess = SpeakerMatcher.guess(embedding: [1, 0.2, 1], speakers: speakers, threshold: 0.9)
        guard case .unknown(let closest, _) = guess else { return XCTFail("expected unknown, got \(guess)") }
        XCTAssertEqual(closest, "A")
    }

    func testUnknownWhenTwoPeopleAreTooClose() {
        let guess = SpeakerMatcher.guess(embedding: [1, 0.95, 0], speakers: speakers, threshold: 0.5)
        guard case .unknown = guess else { return XCTFail("expected unknown, got \(guess)") }
    }

    func testExplainsWhyTheGuessCameOutSo() {
        var lines: [String] = []
        _ = SpeakerMatcher.guess(embedding: [0, 0, 2], speakers: speakers) { lines.append($0) }
        XCTAssertEqual(lines, ["1.00 A vs 0.00 B -> ok"])
    }

    func testNormalizedGivesAUnitVector() {
        let v = SpeakerMatcher.normalized([3, 4])
        XCTAssertEqual(v[0], 0.6, accuracy: 1e-6)
        XCTAssertEqual(v[1], 0.8, accuracy: 1e-6)
    }
}
