import CryptoKit
import Foundation

/// RFC 7636 PKCE: a random code_verifier and its S256 code_challenge, used for the OpenRouter OAuth
/// flow (ADR 0001 - bring your own key).
public enum Pkce {
    /// A 43-128 char unreserved-character verifier, per RFC 7636 section 4.1 (we use 64 random bytes).
    public static func generateVerifier() -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<64).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return base64Url(Data(bytes))
    }

    /// BASE64URL-ENCODE(SHA256(ASCII(code_verifier))), per RFC 7636 section 4.2.
    public static func challenge(_ verifier: String) -> String {
        base64Url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    private static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
