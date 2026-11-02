import XCTest
@testable import DebunkaxCore

final class CheckPromptsTests: XCTestCase {

    private func checked(_ outcome: CheckOutcome, file: StaticString = #filePath, line: UInt = #line) -> CheckResult {
        guard case .checked(let result) = outcome else {
            XCTFail("expected .checked, got \(outcome)", file: file, line: line)
            return CheckResult(verdict: .unverifiable, context: "", sources: [])
        }
        return result
    }

    private func day(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 12))!
    }

    private let utc = TimeZone(identifier: "UTC")!

    func testParseCheckReadsPlainJSON() throws {
        let raw = """
            {"is_claim": true, "verdict": "false", "evidence": "Le taux est de 7,4 %.", "context": "Contredit par l'INSEE.",
             "sources": [{"title": "INSEE", "url": "https://insee.fr/x"}]}
            """

        let result = checked(try CheckPrompts.parseCheck(raw))

        XCTAssertEqual(result.verdict, .false)
        XCTAssertEqual(result.context, "Contredit par l'INSEE.")
        XCTAssertEqual(result.sources, [Source(title: "INSEE", url: "https://insee.fr/x")])
    }

    func testParseCheckReadsJSONWrappedInMarkdownFencesWithSurroundingProse() throws {
        let raw = """
            Voici le résultat de la vérification :
            ```json
            {"is_claim": true, "verdict": "misleading", "evidence": "Hors outre-mer.", "context": "Trompeur.", "sources": [{"title": "INSEE", "url": "https://www.insee.fr/z"}]}
            ```
            N'hésitez pas à demander plus de détails.
            """

        let result = checked(try CheckPrompts.parseCheck(raw))

        XCTAssertEqual(result.verdict, .misleading)
    }

    func testSourcesOutsideTheTrustedSourcesAreDroppedAndLeaveAnAlertUngrounded() throws {
        let raw = """
            {"is_claim": true, "verdict": "false", "evidence": "Chiffre autre.", "context": "x",
            "sources": [{"title": "Blog", "url": "https://blog.example.com/a"}, {"title": "faux", "url": "https://insee.fr.evil.com/a"}]}
            """

        let result = checked(try CheckPrompts.parseCheck(raw))

        XCTAssertEqual(result.verdict, .unverifiable)
        XCTAssertTrue(result.sources.isEmpty)
    }

    func testIsTrustedHonoursSubdomainAndPathRules() {
        XCTAssertTrue(TrustedSources.isTrusted("https://www.assemblee-nationale.fr/x"))
        XCTAssertFalse(TrustedSources.isTrusted("https://groupe-communiste.assemblee-nationale.fr/x"))
        XCTAssertTrue(TrustedSources.isTrusted("https://ec.europa.eu/eurostat/data"))
        XCTAssertFalse(TrustedSources.isTrusted("https://ec.europa.eu/other"))
        XCTAssertTrue(TrustedSources.isTrusted("https://dares.travail-emploi.gouv.fr/a"))
        XCTAssertFalse(TrustedSources.isTrusted("not a url"))
    }

    func testAnAlertVerdictWithNoSourceEvidenceBecomesUnverifiable() throws {
        for verdict in ["false", "misleading", "lacks_context"] {
            let raw = #"{"is_claim": true, "verdict": "\#(verdict)", "evidence": " ", "context": "Aucune source ne le confirme.", "sources": []}"#

            let result = checked(try CheckPrompts.parseCheck(raw))

            XCTAssertEqual(result.verdict, .unverifiable)
        }
        let accurate = try CheckPrompts.parseCheck(#"{"is_claim": true, "verdict": "accurate", "context": "Confirmé.", "sources": []}"#)
        XCTAssertEqual(checked(accurate).verdict, .accurate)
    }

    func testParseCheckWithIsClaimFalseGivesNotAClaim() throws {
        let outcome = try CheckPrompts.parseCheck(#"{"is_claim": false}"#)

        XCTAssertEqual(outcome, .notAClaim)
    }

    func testParseCheckWithAnUnknownVerdictThrowsUnexpected() {
        let raw = #"{"is_claim": true, "verdict": "bogus", "context": "x", "sources": []}"#

        XCTAssertThrowsError(try CheckPrompts.parseCheck(raw)) { error in
            guard case CheckFailure.unexpected = error else { return XCTFail("expected CheckFailure.unexpected, got \(error)") }
        }
    }

    func testParseCheckWithNoJSONThrows() {
        XCTAssertThrowsError(try CheckPrompts.parseCheck("Désolé, je ne peux pas répondre.")) { error in
            guard case CheckFailure.unexpected = error else { return XCTFail("expected CheckFailure.unexpected, got \(error)") }
        }
    }

    func testASourceWithoutATitleFallsBackToItsURL() throws {
        let raw = """
            {"is_claim": true, "verdict": "accurate", "context": "x",
             "sources": [{"url": "https://afp.com/y"}]}
            """

        let result = checked(try CheckPrompts.parseCheck(raw))

        XCTAssertEqual(result.sources, [Source(title: "https://afp.com/y", url: "https://afp.com/y")])
    }

    func testParseTriageReturnsTheRewrittenClaimOrNilWhenItIsntOne() throws {
        // As in the Kotlin test, the raw string keeps a literal backslash-n between the fence and the JSON.
        XCTAssertEqual(
            try CheckPrompts.parseTriage(#"```json\n{"is_claim": true, "claim": " L'ISF rapportait 5 milliards d'euros. "}\n```"#),
            "L'ISF rapportait 5 milliards d'euros.")
        XCTAssertNil(try CheckPrompts.parseTriage(#"{"is_claim": false}"#))
        XCTAssertNil(try CheckPrompts.parseTriage(#"{"is_claim": true, "claim": ""}"#))
    }

    func testParseExplanationReadsTextAndSources() throws {
        let raw = """
            {"text": "Explication détaillée.", "sources": [{"title": "Les Décodeurs", "url": "https://lemonde.fr/z"}]}
            """

        let explanation = try CheckPrompts.parseExplanation(raw)

        XCTAssertEqual(explanation.text, "Explication détaillée.")
        XCTAssertEqual(explanation.sources, [Source(title: "Les Décodeurs", url: "https://lemonde.fr/z")])
    }

    func testWireNameRoundTripsEveryVerdict() {
        for verdict in Verdict.allCases {
            let wire = CheckPrompts.wireName(verdict)
            let recovered = Verdict.allCases.first { CheckPrompts.wireName($0) == wire }
            XCTAssertEqual(verdict, recovered)
        }
    }

    func testCheckSystemContainsTheGivenDate() {
        let system = CheckPrompts.checkSystem(today: day(2026, 9, 23), timeZone: utc)

        XCTAssertTrue(system.contains("2026-09-23"))
    }

    func testTheSpeakerIsGivenToTriageAndCheckOnlyWhenRecognised() {
        let known = HeardClaim(text: "Nous avons baissé les impôts.", heardAt: Date(timeIntervalSince1970: 0), surroundingTranscript: "Avant.", speaker: "Marine Le Pen")
        var unknown = known
        unknown.speaker = nil
        for prompt in [CheckPrompts.triageUser, CheckPrompts.checkUser, CheckPrompts.checkSurroundings] {
            XCTAssertTrue(prompt(known).hasPrefix("Orateur (reconnu à sa voix) : Marine Le Pen"))
            XCTAssertFalse(prompt(unknown).contains("Orateur"))
        }
    }

    func testTheAddresseeIsGivenToTriageAndCheckOnlyWhenKnownAndTheRulesNameTuAndVous() {
        let addressed = HeardClaim(
            text: "Vous avez vote la loi.", heardAt: Date(timeIntervalSince1970: 0), surroundingTranscript: "Avant.",
            speaker: "Marine Le Pen", addressedSpeaker: "Ruth Elkrief")
        var unaddressed = addressed
        unaddressed.addressedSpeaker = nil
        for prompt in [CheckPrompts.triageUser, CheckPrompts.checkUser, CheckPrompts.checkSurroundings] {
            XCTAssertTrue(prompt(addressed).contains("S'adresse probablement à (dernier autre responsable politique entendu, reconnu à sa voix) : Ruth Elkrief"))
            XCTAssertFalse(prompt(unaddressed).contains("S'adresse"))
        }
        let system = CheckPrompts.checkSystem(today: day(2026, 9, 26), timeZone: utc)
        XCTAssertTrue(system.contains("« S'adresse probablement à »"))
        XCTAssertTrue(system.contains("un interlocuteur qui reste incertain, le verdict est \"unverifiable\""))
        XCTAssertTrue(CheckPrompts.triageSystem.contains("« S'adresse probablement à »"))
        // Guessing the Speaker stays forbidden, and the « tu » / « vous » clause is a separate sentence.
        XCTAssertTrue(CheckPrompts.triageSystem.contains("Sinon, ne devine jamais qui parle et laisse ces pronoms tels quels. Remplace « tu » et « vous »"))
    }

    func testTheCheckRulesDiscountArticlesThatOnlyReportTheSpeakersWordsAndOpinionPieces() {
        let system = CheckPrompts.checkSystem(today: day(2026, 9, 29), timeZone: utc)

        XCTAssertTrue(system.contains("Un article qui ne fait que rapporter les propos de l'orateur ne confirme pas l'affirmation"))
        XCTAssertTrue(system.contains("Les tribunes, éditoriaux et chroniques sont des opinions, pas des sources factuelles."))
    }

    func testASpeakerTalkingAboutThemselvesIsCheckedAgainstTheirOwnRecord() {
        let system = CheckPrompts.checkSystem(today: day(2026, 9, 26), timeZone: utc)

        XCTAssertTrue(system.contains("déclarations, votes et actes passés"))
    }
}
