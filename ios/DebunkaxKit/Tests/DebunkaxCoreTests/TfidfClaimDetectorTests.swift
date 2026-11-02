import XCTest
@testable import DebunkaxCore

/// Parity with the Python reference model: every fixture sentence's Swift score must match Python's within 1e-6,
/// and the Claim/non-Claim decision (score >= threshold) must match exactly. The model and the fixture are the
/// Android project's (`app/src/main/assets/claim_model.json`, `app/src/test/resources/claim_parity.json`).
final class TfidfClaimDetectorTests: XCTestCase {
    private static let maxAbsDiff = 1e-6

    private struct ParityCase: Decodable {
        let sentence: String
        let probability: Double
    }

    private struct ParityFixture: Decodable {
        let threshold: Double
        let cases: [ParityCase]
    }

    private func loadDetector() throws -> TfidfClaimDetector {
        try TfidfClaimDetector(contentsOf: RepoFiles.url("app/src/main/assets/claim_model.json"))
    }

    private func loadFixture() throws -> ParityFixture {
        let data = try Data(contentsOf: RepoFiles.url("app/src/test/resources/claim_parity.json"))
        return try JSONDecoder().decode(ParityFixture.self, from: data)
    }

    func testEveryFixtureSentencesScoreMatchesPythonsWithin1e6DecisionsMatchExactly() throws {
        let detector = try loadDetector()
        let fixture = try loadFixture()
        XCTAssertGreaterThanOrEqual(fixture.cases.count, 100, "fixture should have a healthy number of cases")

        var maxDiff = 0.0
        var mismatchedDecisions: [String] = []

        for c in fixture.cases {
            let score = detector.score(c.sentence)
            let diff = abs(score - c.probability)
            maxDiff = max(maxDiff, diff)
            XCTAssertLessThanOrEqual(diff, Self.maxAbsDiff, "score mismatch for \"\(c.sentence)\": swift=\(score) python=\(c.probability) diff=\(diff)")

            let pythonDecision = c.probability >= fixture.threshold
            if pythonDecision != detector.isClaim(c.sentence) { mismatchedDecisions.append(c.sentence) }
        }

        XCTAssertTrue(mismatchedDecisions.isEmpty, "decision mismatches: \(mismatchedDecisions)")
        XCTAssertLessThanOrEqual(maxDiff, Self.maxAbsDiff, "max abs diff should stay well under the tolerance, was \(maxDiff)")
    }

    func testNarcoTraficOperationsFigureIsAClaim() throws {
        let detector = try loadDetector()
        XCTAssertTrue(
            detector.isClaim(
                "LES OPERATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTE VINGT QUATRE POURCENT ENTRE "
                    + "DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS L'ENSEMBLE DES ALPES MARITIMES"))
    }

    func testDebtHasExplodedIsAClaim() throws {
        let detector = try loadDetector()
        XCTAssertTrue(detector.isClaim("LA DETTE PUBLIQUE A EXPLOSE SOUS CE GOUVERNEMENT ET DEPASSE TROIS MILLE MILLIARDS D'EUROS"))
    }

    func testCourtesyAddressIsNotAClaim() throws {
        let detector = try loadDetector()
        XCTAssertFalse(detector.isClaim("MERCI MADAME LA PRESIDENTE MONSIEUR LE DEPUTE"))
    }

    func testAdmonitionIsNotAClaim() throws {
        let detector = try loadDetector()
        XCTAssertFalse(detector.isClaim("VOUS DEVRIEZ AVOIR HONTE"))
    }

    func testScoreIsDeterministicAndWithinProbabilityBounds() throws {
        let detector = try loadDetector()
        let sentence = "LE CHOMAGE A BAISSE DE VINGT POURCENT EN FRANCE DEPUIS DEUX MILLE VINGT"
        let first = detector.score(sentence)
        let second = detector.score(sentence)
        XCTAssertEqual(first, second, accuracy: 0.0)
        XCTAssertTrue((0.0...1.0).contains(first))
    }
}
