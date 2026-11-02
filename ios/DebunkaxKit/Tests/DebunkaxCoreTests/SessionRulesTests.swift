import XCTest
@testable import DebunkaxCore

final class AlertPolicyTests: XCTestCase {
    private let heardAt = testStart

    func testFalseMisleadingAndLacksContextAlertUnverifiableAndAccurateNeverDo() {
        let readyAt = heardAt.addingTimeInterval(1)

        XCTAssertTrue(AlertPolicy.shouldAlert(verdict: .false, heardAt: heardAt, readyAt: readyAt))
        XCTAssertTrue(AlertPolicy.shouldAlert(verdict: .misleading, heardAt: heardAt, readyAt: readyAt))
        XCTAssertTrue(AlertPolicy.shouldAlert(verdict: .lacksContext, heardAt: heardAt, readyAt: readyAt))
        XCTAssertFalse(AlertPolicy.shouldAlert(verdict: .unverifiable, heardAt: heardAt, readyAt: readyAt))
        XCTAssertFalse(AlertPolicy.shouldAlert(verdict: .accurate, heardAt: heardAt, readyAt: readyAt))
    }

    func testExactly15sStillAlerts() {
        let readyAt = heardAt.addingTimeInterval(15)

        XCTAssertTrue(AlertPolicy.shouldAlert(verdict: .false, heardAt: heardAt, readyAt: readyAt))
    }

    func test15point001sDoesNotAlert() {
        let readyAt = heardAt.addingTimeInterval(15.001)

        XCTAssertFalse(AlertPolicy.shouldAlert(verdict: .false, heardAt: heardAt, readyAt: readyAt))
    }
}

final class CheckBudgetTests: XCTestCase {
    func testNilLimitIsUnlimited() {
        let budget = CheckBudget(limit: nil)

        for _ in 0..<50 {
            XCTAssertTrue(budget.tryConsume())
        }
        XCTAssertFalse(budget.exhausted)
    }

    func testLimitOf2AllowsExactly2() {
        let budget = CheckBudget(limit: 2)

        XCTAssertTrue(budget.tryConsume())
        XCTAssertTrue(budget.tryConsume())
        XCTAssertFalse(budget.tryConsume())
        XCTAssertTrue(budget.exhausted)
        XCTAssertEqual(budget.used, 2)
    }
}

final class RepeatedClaimMatcherTests: XCTestCase {
    private let result = CheckResult(verdict: .false, context: "contexte", sources: [])

    func testNormalizationEquatesThousandsSeparatorVariants() {
        XCTAssertEqual(
            RepeatedClaimMatcher.normalize("il y a 1 000 personnes"),
            RepeatedClaimMatcher.normalize("il y a 1000 personnes"))
    }

    func testNormalizationStripsAccents() {
        XCTAssertEqual(
            RepeatedClaimMatcher.normalize("le chômage a augmenté"),
            RepeatedClaimMatcher.normalize("le chomage a augmente"))
    }

    func testNormalizationEquatesCurlyAndStraightApostrophes() {
        XCTAssertEqual(
            RepeatedClaimMatcher.normalize("aujourd’hui il pleut"),
            RepeatedClaimMatcher.normalize("aujourd'hui il pleut"))
    }

    func testNormalizationIsCaseInsensitive() {
        XCTAssertEqual(
            RepeatedClaimMatcher.normalize("LE CHÔMAGE A BAISSÉ"),
            RepeatedClaimMatcher.normalize("le chômage a baissé"))
    }

    func testAReWordedRepeatOfAClaimReusesTheVerdict() {
        let matcher = RepeatedClaimMatcher()
        matcher.remember("le chômage a baissé de 10 % depuis 2017", result: result)

        let found = matcher.find("depuis 2017 le chômage a baissé de 10 %")

        XCTAssertEqual(found, result)
    }

    func testADifferentFigureDoesNotMatch() {
        let matcher = RepeatedClaimMatcher()
        matcher.remember("le chômage a baissé de 10 % depuis 2017", result: result)

        let found = matcher.find("le chômage a baissé de 20 % depuis 2017")

        XCTAssertNil(found)
    }

    func testARepeatByAnotherSpeakerDoesNotReuseTheVerdict() {
        let matcher = RepeatedClaimMatcher()
        matcher.remember("je n'ai jamais voté ce texte", result: result, speaker: "Le Pen")

        XCTAssertNil(matcher.find("je n'ai jamais voté ce texte", speaker: "Attal"))
        XCTAssertEqual(matcher.find("je n'ai jamais voté ce texte", speaker: "Le Pen"), result)
    }

    func testClearForgetsEverything() {
        let matcher = RepeatedClaimMatcher()
        matcher.remember("le chômage a baissé de 10 % depuis 2017", result: result)

        matcher.clear()

        XCTAssertNil(matcher.find("le chômage a baissé de 10 % depuis 2017"))
    }
}
