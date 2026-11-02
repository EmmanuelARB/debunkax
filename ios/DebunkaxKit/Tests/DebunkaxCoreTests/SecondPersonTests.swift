import XCTest
@testable import DebunkaxCore

final class SecondPersonTests: XCTestCase {
    func testSpotsTheSecondPersonWholeWordsAnyCase() {
        for s in ["Vous avez voté cette loi.", "Tu mens.", "C'est votre bilan.", "Je te dis que non.", "Avec toi et tes amis.", "Ton budget, ta réforme, vos chiffres."] {
            XCTAssertTrue(SecondPerson.isIn(s), s)
        }
    }

    func testSpotsTheElidedForm() {
        XCTAssertTrue(SecondPerson.isIn("Quand t'as voté la loi"))
        XCTAssertTrue(SecondPerson.isIn("Quand t’as voté la loi"))
    }

    func testIgnoresWordsThatOnlyContainIt() {
        for s in ["Tout le total des votes a baissé.", "Il a voté la motion, et alors ?", "Le taux est de 7 %.", "J'ai baissé les impôts."] {
            XCTAssertFalse(SecondPerson.isIn(s), s)
        }
    }
}
