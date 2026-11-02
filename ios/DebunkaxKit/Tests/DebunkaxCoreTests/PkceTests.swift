import XCTest
@testable import DebunkaxCore

final class PkceTests: XCTestCase {
    private func isUrlSafe(_ s: String) -> Bool {
        s.unicodeScalars.allSatisfy { ($0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains($0))) || $0 == "-" || $0 == "_" }
    }

    // RFC 7636 Appendix B test vector.
    func testChallengeMatchesTheRFC7636TestVector() {
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        let expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        XCTAssertEqual(Pkce.challenge(verifier), expectedChallenge)
    }

    func testVerifiersAreUniqueAndProduceAValidLookingChallenge() {
        let a = Pkce.generateVerifier()
        let b = Pkce.generateVerifier()

        XCTAssertFalse(a.isBlank)
        XCTAssertNotEqual(a, b)
        // Unreserved URL-safe charset only (RFC 7636 section 4.1): no padding, no '+', '/'.
        XCTAssertTrue(isUrlSafe(a))

        let challenge = Pkce.challenge(a)
        XCTAssertFalse(challenge.isBlank)
        XCTAssertTrue(isUrlSafe(challenge))
    }
}
