import XCTest
@testable import DebunkaxCore

final class SpeakerTimelineTests: XCTestCase {
    private func at(_ seconds: TimeInterval) -> Date { testStart.addingTimeInterval(seconds) }
    private func named(_ name: String) -> SpeakerGuess { .named(name: name, score: 0.7) }
    private let unsure = SpeakerGuess.unknown(closest: "Marine Le Pen", score: 0.4)

    private func timeline(_ guesses: (TimeInterval, SpeakerGuess)...) -> SpeakerTimeline {
        let timeline = SpeakerTimeline()
        for (seconds, guess) in guesses { timeline.add(end: at(seconds), guess: guess) }
        return timeline
    }

    func testNamesTheOnlyLeaderHeardDuringTheSentence() {
        let t = timeline((5, named("Marine Le Pen")), (10, named("Marine Le Pen")), (15, .silence))
        XCTAssertEqual(t.speakerBetween(from: at(4), to: at(12)), "Marine Le Pen")
    }

    func testUnknownWhenTwoLeadersAreHeard() {
        let t = timeline((5, named("Marine Le Pen")), (10, named("Gabriel Attal")))
        XCTAssertNil(t.speakerBetween(from: at(4), to: at(12)))
    }

    func testUnknownWhenUnsureWindowsAreAsManyAsNamedOnes() {
        let t = timeline((5, named("Marine Le Pen")), (10, unsure))
        XCTAssertNil(t.speakerBetween(from: at(4), to: at(12)))
    }

    func testUnknownWhenNothingWasHeardThen() {
        let t = timeline((5, named("Marine Le Pen")))
        XCTAssertNil(t.speakerBetween(from: at(30), to: at(40)))
    }

    func testIgnoresWindowsOutsideTheSentence() {
        let t = timeline((5, named("Gabriel Attal")), (20, named("Marine Le Pen")), (22, named("Marine Le Pen")))
        XCTAssertEqual(t.speakerBetween(from: at(17), to: at(22)), "Marine Le Pen")
    }

    func testForgetsGuessesOlderThanTwoMinutes() {
        let t = timeline((5, named("Gabriel Attal")), (200, named("Marine Le Pen")))
        XCTAssertNil(t.speakerBetween(from: at(0), to: at(10)))
    }

    func testClearForgetsEverything() {
        let t = timeline((5, named("Marine Le Pen")))
        t.clear()
        XCTAssertNil(t.speakerBetween(from: at(0), to: at(10)))
    }

    private let journalists: Set<String> = ["Ruth Elkrief"]

    private func addressee(_ t: SpeakerTimeline, before: TimeInterval, claimant: String = "Marine Le Pen") -> String? {
        t.addresseeBefore(at(before), claimant: claimant) { [journalists] in journalists.contains($0) }
    }

    func testAddresseeIsThePoliticianHeardBeforeTheClaimant() {
        let t = timeline((60, named("Gabriel Attal")), (70, named("Marine Le Pen")))
        XCTAssertEqual(addressee(t, before: 66), "Gabriel Attal")
    }

    func testAddresseeSkipsJournalistsAndTheClaimant() {
        let t = timeline((45, named("Gabriel Attal")), (55, named("Ruth Elkrief")), (70, named("Marine Le Pen")))
        XCTAssertEqual(addressee(t, before: 66), "Gabriel Attal")
    }

    func testAddresseeIsNilWhenOnlyJournalistsSpokeBefore() {
        let t = timeline((60, named("Ruth Elkrief")), (70, named("Marine Le Pen")))
        XCTAssertNil(addressee(t, before: 66))
    }

    func testAddresseeSearchStopsThirtySecondsBack() {
        let t = timeline((25, named("Gabriel Attal")), (70, named("Marine Le Pen")))
        XCTAssertNil(addressee(t, before: 66))  // Attal spoke 40 s earlier
        let near = timeline((40, named("Gabriel Attal")), (70, named("Marine Le Pen")))
        XCTAssertEqual(addressee(near, before: 66), "Gabriel Attal")  // 26 s earlier
    }

    func testAddresseeUsesTheMostRecentPoliticianAndTheDoubtRule() {
        let t = timeline((45, named("Bruno Retailleau")), (55, named("Gabriel Attal")), (60, named("Bruno Retailleau")))
        XCTAssertEqual(addressee(t, before: 66), "Bruno Retailleau")  // the most recent window that names one person
        let doubt = timeline((50, named("Gabriel Attal")), (58, named("Gabriel Attal")), (60, unsure))
        XCTAssertEqual(addressee(doubt, before: 66), "Gabriel Attal")
    }

    func testSentencesArePlacedBackFromTheSegmentEnd() {
        let spans = SentenceTiming.spans([String(repeating: "a", count: 150), String(repeating: "b", count: 15)], endAt: at(100))
        XCTAssertEqual(spans[1].start.epochMillis, at(98).epochMillis)  // 1 s of text, but at least 2 s
        XCTAssertEqual(spans[1].end.epochMillis, at(100).epochMillis)
        XCTAssertEqual(spans[0].start.epochMillis, at(88).epochMillis)
        XCTAssertEqual(spans[0].end.epochMillis, at(98).epochMillis)
        XCTAssertEqual(spans[1].end.timeIntervalSince(spans[0].start), 12, accuracy: 0.0005)
    }
}
