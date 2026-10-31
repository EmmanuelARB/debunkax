package app.debunkax.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    // RFC 7636 Appendix B test vector.
    @Test
    fun `challenge matches the RFC 7636 test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, Pkce.challenge(verifier))
    }

    @Test
    fun `generated verifiers are unique and produce a valid-looking challenge`() {
        val a = Pkce.generateVerifier()
        val b = Pkce.generateVerifier()

        assertTrue(a.isNotBlank())
        assertTrue(a != b)
        // Unreserved URL-safe charset only (RFC 7636 section 4.1): no padding, no '+', '/'.
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })

        val challenge = Pkce.challenge(a)
        assertTrue(challenge.isNotBlank())
        assertTrue(challenge.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
