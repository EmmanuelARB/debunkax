import Foundation

/// Shared HTTP-error-to-`CheckFailure` mapping for the check clients.
public enum CheckErrors {

    /// Pulls the human-readable message out of an OpenRouter `{"error": {"message": ...}}` body.
    public static func extractMessage(_ body: String) -> String {
        if let object = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any],
            let error = object["error"] as? [String: Any],
            let message = error["message"] as? String
        {
            return message
        }
        return String(body.prefix(200))
    }

    public static func fromHttp(code: Int, message: String) -> CheckFailure {
        let lower = message.lowercased()
        let searchDisabled = lower.contains("web search")
            && (lower.contains("disab") || lower.contains("not allow") || lower.contains("not enabled") || lower.contains("not support"))
        if searchDisabled { return .searchDisabled }
        switch code {
        case 401, 403: return .invalidKey
        case 402: return .noCredit
        case 429: return .rateLimited
        default: return .unexpected(message.isBlank ? "Erreur HTTP \(code)" : message)
        }
    }
}
