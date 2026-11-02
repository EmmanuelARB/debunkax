import AuthenticationServices
import CryptoKit
import DebunkaxCore
import Foundation

#if canImport(UIKit)
import UIKit
#endif

/// OpenRouter's OAuth PKCE flow (ADR 0001 - bring your own key).
///
/// Unlike Android, the pending code_verifier is only kept in memory: `ASWebAuthenticationSession`
/// hands the callback back to this very call, in-process, so there is no app relaunch to survive
/// and nothing secret needs to touch the disk. It goes out of scope once `signIn()` returns.
final class OpenRouterAuth {
    // Verified against https://openrouter.ai/docs/use-cases/oauth-pkce.
    static let callbackURL = "debunkax://oauth/openrouter"
    static let callbackScheme = "debunkax"
    static let authURL = "https://openrouter.ai/auth"
    static let exchangeURL = "https://openrouter.ai/api/v1/auth/keys"

    private let session: URLSession
    private let authURL: String
    private let exchangeURL: String
    private let presentation = PresentationContext()

    init(
        session: URLSession = .shared,
        authURL: String = OpenRouterAuth.authURL,
        exchangeURL: String = OpenRouterAuth.exchangeURL
    ) {
        self.session = session
        self.authURL = authURL
        self.exchangeURL = exchangeURL
    }

    /// Opens the authorization page in an ephemeral web session and returns the long-lived OpenRouter API key.
    @MainActor
    func signIn() async throws -> String {
        let verifier = Pkce.generateVerifier()
        let url = try authorizationURL(verifier: verifier)
        let callback = try await authenticate(url)
        guard let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "code" })?.value, !code.isEmpty
        else {
            throw OpenRouterAuthError.message("Réponse OpenRouter sans code")
        }
        return try await exchange(code: code, verifier: verifier)
    }

    func authorizationURL(verifier: String) throws -> URL {
        guard var components = URLComponents(string: authURL) else {
            throw OpenRouterAuthError.message("Adresse OpenRouter invalide")
        }
        components.queryItems = [
            URLQueryItem(name: "callback_url", value: Self.callbackURL),
            URLQueryItem(name: "code_challenge", value: Pkce.challenge(verifier)),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
        ]
        guard let url = components.url else {
            throw OpenRouterAuthError.message("Adresse OpenRouter invalide")
        }
        return url
    }

    @MainActor
    private func authenticate(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let webSession = ASWebAuthenticationSession(
                url: url, callbackURLScheme: Self.callbackScheme
            ) { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: CancellationError())
                } else {
                    continuation.resume(throwing: error ?? OpenRouterAuthError.message("Connexion OpenRouter échouée"))
                }
            }
            webSession.prefersEphemeralWebBrowserSession = true
            webSession.presentationContextProvider = presentation
            if !webSession.start() {
                continuation.resume(throwing: OpenRouterAuthError.message("Connexion OpenRouter impossible"))
            }
        }
    }

    /// Exchanges the callback's `code` for a long-lived OpenRouter API key.
    func exchange(code: String, verifier: String) async throws -> String {
        guard let url = URL(string: exchangeURL) else {
            throw OpenRouterAuthError.message("Adresse OpenRouter invalide")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "code": code,
            "code_verifier": verifier,
            "code_challenge_method": "S256",
        ])

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw OpenRouterAuthError.message("Pas de connexion")
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw OpenRouterAuthError.message("Échange OAuth OpenRouter échoué (\(status))")
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let key = object["key"] as? String
        else {
            throw OpenRouterAuthError.message("Échange OAuth OpenRouter échoué (\(status))")
        }
        return key
    }
}

enum OpenRouterAuthError: Error, LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let text): text
        }
    }
}

private final class PresentationContext: NSObject, ASWebAuthenticationPresentationContextProviding {
    @MainActor
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        #if canImport(UIKit)
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
        return window ?? ASPresentationAnchor()
        #else
        return ASPresentationAnchor()
        #endif
    }
}
