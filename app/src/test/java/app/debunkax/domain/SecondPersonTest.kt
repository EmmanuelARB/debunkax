package app.debunkax.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondPersonTest {
    @Test fun spotsTheSecondPersonWholeWordsAnyCase() {
        for (s in listOf("Vous avez voté cette loi.", "Tu mens.", "C'est votre bilan.", "Je te dis que non.", "Avec toi et tes amis.", "Ton budget, ta réforme, vos chiffres.")) {
            assertTrue(s, SecondPerson.isIn(s))
        }
    }

    @Test fun spotsTheElidedForm() {
        assertTrue(SecondPerson.isIn("Quand t'as voté la loi"))
        assertTrue(SecondPerson.isIn("Quand t’as voté la loi"))
    }

    @Test fun ignoresWordsThatOnlyContainIt() {
        for (s in listOf("Tout le total des votes a baissé.", "Il a voté la motion, et alors ?", "Le taux est de 7 %.", "J'ai baissé les impôts.")) {
            assertFalse(s, SecondPerson.isIn(s))
        }
    }
}
