import Foundation

/// `CheckClient` backed by OpenRouter's chat completions API, using the `web` plugin restricted to
/// Trusted Sources and per-request zero data retention (ADR 0001). Every model uses the Exa engine: native
/// search runs only on the model makers' own endpoints, and none of those accepts zero data retention.
public final class OpenRouterCheckClient: CheckClient {
    private static let unreadable = "Réponse illisible du fournisseur"

    private let apiKey: String
    private let model: ProviderModel
    private let transport: any HTTPTransport
    private let baseUrl: String
    private let today: @Sendable () -> Date
    /// For endpoints that refuse to disable reasoning but accept an effort level (e.g. GLM 5.3):
    /// when set, sends `reasoning: {effort: ...}` instead of `reasoning: {enabled: false}`.
    private let reasoningEffort: String?
    private let counter = SpendCounter(estimated: false)

    public init(
        apiKey: String,
        model: ProviderModel,
        transport: any HTTPTransport,
        baseUrl: String = "https://openrouter.ai/api/v1",
        today: @escaping @Sendable () -> Date = { Date() },
        reasoningEffort: String? = nil
    ) {
        self.apiKey = apiKey
        self.model = model
        self.transport = transport
        self.baseUrl = baseUrl
        self.today = today
        self.reasoningEffort = reasoningEffort
    }

    public var spent: Spend? { counter.spend }

    public func check(_ claim: HeardClaim) async throws -> CheckOutcome {
        // Most transcript sentences aren't Claims, and the Exa search costs far more than the model, so a
        // search-free pass sorts them first. It also rewrites the Claim into a clear sentence.
        let triage = try await send(
            system: CheckPrompts.triageSystem, user: CheckPrompts.triageUser(claim), maxResults: nil)
        guard let rewritten = try parsing({ try CheckPrompts.parseTriage(triage) }) else {
            return .notAClaim
        }

        // OpenRouter builds the Exa search query from the last user message, so the transcript goes in the
        // system message: only the rewritten Claim reaches Exa, and the search stays on the Claim's topic.
        let text = try await send(
            system: CheckPrompts.checkSystem(today: today()) + "\n\n" + CheckPrompts.checkSurroundings(claim),
            user: CheckPrompts.claimLine(rewritten),
            maxResults: 2)
        switch try parsing({ try CheckPrompts.parseCheck(text) }) {
        case .checked(var result):
            result.checkedText = rewritten
            return .checked(result)
        case .notAClaim:
            return .notAClaim
        }
    }

    public func explain(claimText: String, result: CheckResult) async throws -> Explanation {
        let text = try await send(
            system: CheckPrompts.explainSystem(today: today()),
            user: CheckPrompts.explainUser(claimText: claimText, result: result),
            maxResults: 4)
        return try parsing { try CheckPrompts.parseExplanation(text) }
    }

    /// A model reply that isn't the JSON we asked for is a `CheckFailure`, never a crash.
    private func parsing<T>(_ block: () throws -> T) throws -> T {
        do {
            return try block()
        } catch is DecodingError {
            throw CheckFailure.unexpected(Self.unreadable)
        }
    }

    /// Searches Trusted Sources when `maxResults` is set; without it, the request makes no web search.
    private func send(system: String, user: String, maxResults: Int?) async throws -> String {
        var payload: [String: Any] = [
            "model": model.id,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": user],
            ],
        ]
        if let maxResults {
            var plugin: [String: Any] = ["id": "web"]
            if let engine = model.openRouterEngine { plugin["engine"] = engine }
            plugin["max_results"] = maxResults
            plugin["include_domains"] = TrustedSources.domains
            plugin["exclude_domains"] = TrustedSources.excludedSubdomains
            payload["plugins"] = [plugin]
        }
        // Extended reasoning made DeepSeek write up to 3,600 output tokens for one Check; with it off, answers on live
        // samples were as good, faster and far cheaper. A few endpoints force reasoning on; for those the
        // lowest effort level is the only option.
        if let reasoningEffort {
            payload["reasoning"] = ["effort": reasoningEffort]
        } else {
            payload["reasoning"] = ["enabled": false]
        }
        // The same Claim with the same sources should get the same Verdict; at the default temperature, about 10 of
        // 46 bench Verdicts changed between two replays of identical search results.
        payload["temperature"] = 0
        // Per-request zero data retention; see openrouter.ai/docs/guides/features/zdr.
        payload["provider"] = ["zdr": true]

        guard let url = URL(string: "\(baseUrl)/chat/completions"),
            let body = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys, .withoutEscapingSlashes])
        else { throw CheckFailure.unexpected("Requête invalide") }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = URLSessionTransport.timeout
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        let reply: (Data, HTTPURLResponse)
        do {
            reply = try await transport.send(request)
        } catch let failure as CheckFailure {
            throw failure
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw CheckFailure.offline
        }
        let (data, response) = reply

        let bodyText = String(decoding: data, as: UTF8.self)
        if !(200...299).contains(response.statusCode) {
            throw CheckErrors.fromHttp(code: response.statusCode, message: CheckErrors.extractMessage(bodyText))
        }
        return try extractAssistantText(data)
    }

    private func extractAssistantText(_ data: Data) throws -> String {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw CheckFailure.unexpected(Self.unreadable)
        }
        // OpenRouter reports what each request cost, search included.
        if let usage = root["usage"] as? [String: Any], let cost = usage["cost"] as? NSNumber,
            CFGetTypeID(cost) != CFBooleanGetTypeID()
        {
            counter.add(cost.doubleValue)
        }
        guard let choices = root["choices"] as? [Any],
            let first = choices.first as? [String: Any],
            let message = first["message"] as? [String: Any]
        else { return "" }
        return message["content"] as? String ?? ""
    }
}
