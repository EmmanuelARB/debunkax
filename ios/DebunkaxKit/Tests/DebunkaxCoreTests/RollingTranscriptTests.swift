import XCTest
@testable import DebunkaxCore

final class RollingTranscriptTests: XCTestCase {
    private let start = testStart

    func testSegmentsOlderThan30sArePruned() {
        let transcript = RollingTranscript()
        transcript.append("bonjour", at: start)

        let text = transcript.textAt(start.addingTimeInterval(31))

        XCTAssertEqual(text, "")
    }

    func testSegmentJustOutsideThe30sWindowIsPrunedOneJustInsideIsKept() {
        let transcript = RollingTranscript()
        transcript.append("vieux", at: start)
        transcript.append("recent", at: start.addingTimeInterval(0.001))

        let text = transcript.textAt(start.addingTimeInterval(30.001))

        XCTAssertEqual(text, "recent")
    }

    func testBlankTextIsIgnored() {
        let transcript = RollingTranscript()
        transcript.append("bonjour", at: start)
        transcript.append("", at: start.addingTimeInterval(1))
        transcript.append("   ", at: start.addingTimeInterval(2))
        transcript.append("le monde", at: start.addingTimeInterval(3))

        let text = transcript.textAt(start.addingTimeInterval(3))

        XCTAssertEqual(text, "bonjour le monde")
    }

    func testTextJoinsInOrder() {
        let transcript = RollingTranscript()
        transcript.append("un", at: start)
        transcript.append("deux", at: start.addingTimeInterval(1))
        transcript.append("trois", at: start.addingTimeInterval(2))

        let text = transcript.textAt(start.addingTimeInterval(2))

        XCTAssertEqual(text, "un deux trois")
    }

    func testClearRemovesAllSegments() {
        let transcript = RollingTranscript()
        transcript.append("bonjour", at: start)

        transcript.clear()

        XCTAssertEqual(transcript.textAt(start), "")
    }
}
