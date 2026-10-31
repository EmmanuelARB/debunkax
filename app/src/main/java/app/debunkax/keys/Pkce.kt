package app.debunkax.keys

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 PKCE: a random code_verifier and its S256 code_challenge, used by [OpenRouterAuth] for
 * the OpenRouter OAuth flow (ADR 0001 - bring your own key).
 */
object Pkce {
    private val secureRandom = SecureRandom()

    /** A 43-128 char unreserved-character verifier, per RFC 7636 section 4.1 (we use 64 random bytes). */
    fun generateVerifier(): String {
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        return base64Url(bytes)
    }

    /** BASE64URL-ENCODE(SHA256(ASCII(code_verifier))), per RFC 7636 section 4.2. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
