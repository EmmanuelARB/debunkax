import Synchronization
import XCTest
@testable import DebunkaxCore

/// Stands in for MockWebServer: replays queued responses in order and records every request.
final class StubTransport: HTTPTransport {
    enum Reply {
        case http(status: Int, body: String)
        case failure(Error)
    }

    private struct State {
        var replies: [Reply] = []
        var requests: [URLRequest] = []
    }

    private let state = Mutex(State())

    var requests: [URLRequest] { state.withLock { $0.requests } }
    var requestCount: Int { requests.count }

    func enqueue(_ reply: Reply) { state.withLock { $0.replies.append(reply) } }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let reply = state.withLock { s -> Reply? in
            s.requests.append(request)
            return s.replies.isEmpty ? nil : s.replies.removeFirst()
        }
        switch reply {
        case .http(let status, let body):
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
            return (Data(body.utf8), response)
        case .failure(let error):
            throw error
        case nil:
            throw URLError(.cannotConnectToHost)
        }
    }
}

final class OpenRouterCheckClientTests: XCTestCase {
    private let model = Models.openRouterDeepSeekV41Flash
    private let transport = StubTransport()

    // 2026-09-23 at noon UTC, in any time zone the isoDay is the 23rd or the 24th at worst; the prompt text isn't asserted.
    private let today = Date(timeIntervalSince1970: 1_790_164_800)

    private func makeClient(_ model: ProviderModel? = nil) -> OpenRouterCheckClient {
        OpenRouterCheckClient(
            apiKey: "sk-or-test-key", model: model ?? self.model, transport: transport,
            baseUrl: "https://openrouter.test/api/v1", today: { [today] in today })
    }

    private let claim = HeardClaim(
        text: "Le chômage a baissé de 10% cette année.",
        heardAt: testStart,
        surroundingTranscript: "Et sur l'emploi, on voit que...")

    private let rewritten = "Le chômage a baissé de 10 % en France cette année."

    private func jsonString(_ string: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [string], options: [])
        let array = String(decoding: data, as: UTF8.self)
        return String(array.dropFirst().dropLast())
    }

    private func enqueueSuccess(_ assistantContent: String, cost: Double? = nil) {
        let usage = cost.map { ",\"usage\":{\"cost\":\($0)}" } ?? ""
        let body =
            "{\"id\":\"gen-01\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\(jsonString(assistantContent))}}]\(usage)}"
        transport.enqueue(.http(status: 200, body: body))
    }

    /// The search-free first pass that every Check starts with.
    private func enqueueTriage(isClaim: Bool = true, cost: Double? = nil) {
        enqueueSuccess(isClaim ? "{\"is_claim\": true, \"claim\": \"\(rewritten)\"}" : "{\"is_claim\": false}", cost: cost)
    }

    private func jsonBody(of request: URLRequest) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any] ?? [:]
    }

    /// Skips the pre-check request and returns the JSON body of the searched Check request.
    private func searchedCheckBody() -> [String: Any] {
        XCTAssertGreaterThanOrEqual(transport.requests.count, 2)
        return jsonBody(of: transport.requests[1])
    }

    private func webPlugin(_ body: [String: Any]) -> [String: Any] {
        ((body["plugins"] as? [Any])?.first as? [String: Any]) ?? [:]
    }

    private func assertFailure(
        _ expected: CheckFailure, file: StaticString = #filePath, line: UInt = #line, _ work: () async throws -> Void
    ) async {
        do {
            try await work()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch let failure as CheckFailure {
            XCTAssertEqual(failure, expected, file: file, line: line)
        } catch {
            XCTFail("expected a CheckFailure, got \(error)", file: file, line: line)
        }
    }

    func testParsesASuccessfulCheckedVerdict() async throws {
        enqueueTriage()
        enqueueSuccess(
            "{\"is_claim\": true, \"verdict\": \"misleading\", \"evidence\": \"Hausse limitée à un secteur.\", \"context\": \"Techniquement vrai mais trompeur.\", \"sources\": [{\"title\": \"AFP Factuel\", \"url\": \"https://factuel.afp.com/x\"}]}"
        )

        let outcome = try await makeClient().check(claim)

        guard case .checked(let result) = outcome else { return XCTFail("expected a checked outcome, got \(outcome)") }
        XCTAssertEqual(result.verdict, .misleading)
        XCTAssertEqual(result.context, "Techniquement vrai mais trompeur.")
        XCTAssertEqual(result.sources[0].url, "https://factuel.afp.com/x")
        XCTAssertEqual(result.checkedText, rewritten)
    }

    func testCheckRequestCarriesAuthHeaderModelIncludeDomainsForcedEngineZdrAndMaxResults2() async throws {
        enqueueTriage()
        enqueueSuccess("{\"is_claim\": false}")

        _ = try await makeClient().check(claim)

        let recorded = transport.requests[1]
        XCTAssertEqual(recorded.httpMethod, "POST")
        XCTAssertEqual(recorded.url?.absoluteString, "https://openrouter.test/api/v1/chat/completions")
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Authorization"), "Bearer sk-or-test-key")

        let body = jsonBody(of: recorded)
        XCTAssertEqual(body["model"] as? String, model.id)

        let plugin = webPlugin(body)
        XCTAssertEqual(plugin["id"] as? String, "web")
        XCTAssertEqual(plugin["engine"] as? String, "exa")
        XCTAssertEqual(plugin["max_results"] as? Int, 2)
        XCTAssertEqual(plugin["include_domains"] as? [String], TrustedSources.domains)
        XCTAssertEqual(plugin["exclude_domains"] as? [String], ["*.assemblee-nationale.fr", "*.senat.fr"])

        XCTAssertEqual((body["provider"] as? [String: Any])?["zdr"] as? Bool, true)
        XCTAssertEqual((body["reasoning"] as? [String: Any])?["enabled"] as? Bool, false)
        XCTAssertEqual(body["temperature"] as? Int, 0)
    }

    func testAReplyThatIsNotTheExpectedJSONIsACheckFailureNotACrash() async {
        enqueueSuccess("Désolé, je ne peux pas répondre.")
        await assertFailure(.unexpected("Réponse sans JSON")) { _ = try await makeClient().check(claim) }

        transport.enqueue(.http(status: 200, body: "<html>gateway</html>"))
        await assertFailure(.unexpected("Réponse illisible du fournisseur")) { _ = try await makeClient().check(claim) }
    }

    func testJSONOfTheWrongShapeIsAnUnreadableReplyFailure() async {
        enqueueSuccess("{\"is_claim\": \"peut-être\"}")
        await assertFailure(.unexpected("Réponse illisible du fournisseur")) { _ = try await makeClient().check(claim) }
    }

    func testASentenceThePreCheckRejectsCostsOneSearchFreeRequestAndIsNotAClaim() async throws {
        enqueueTriage(isClaim: false)

        let outcome = try await makeClient().check(claim)

        XCTAssertEqual(outcome, .notAClaim)
        XCTAssertEqual(transport.requestCount, 1)
        let body = jsonBody(of: transport.requests[0])
        XCTAssertNil(body["plugins"])
        XCTAssertEqual((body["provider"] as? [String: Any])?["zdr"] as? Bool, true)
    }

    func testExplainRequestUsesMaxResults4() async throws {
        enqueueSuccess("{\"text\": \"Explication détaillée.\", \"sources\": []}")

        let explanation = try await makeClient().explain(
            claimText: "Le chômage a baissé de 10%.", result: CheckResult(verdict: .false, context: "ctx", sources: []))

        XCTAssertEqual(explanation.text, "Explication détaillée.")
        XCTAssertEqual(webPlugin(jsonBody(of: transport.requests[0]))["max_results"] as? Int, 4)
    }

    func testEveryOpenRouterModelForcesTheExaEngineRestrictedToTrustedSources() async throws {
        for m in Models.all.filter({ $0.provider == .openRouter }) {
            let transport = StubTransport()
            let client = OpenRouterCheckClient(
                apiKey: "sk-or-test-key", model: m, transport: transport, baseUrl: "https://openrouter.test/api/v1",
                today: { [today] in today })
            transport.enqueue(.http(status: 200, body: wrapped("{\"is_claim\": true, \"claim\": \"\(rewritten)\"}")))
            transport.enqueue(.http(status: 200, body: wrapped("{\"is_claim\": false}")))

            _ = try await client.check(claim)

            let body = jsonBody(of: transport.requests[1])
            XCTAssertEqual(body["model"] as? String, m.id)
            let plugin = webPlugin(body)
            XCTAssertEqual(plugin["engine"] as? String, "exa")
            XCTAssertEqual(plugin["include_domains"] as? [String], TrustedSources.domains)
            XCTAssertEqual((body["provider"] as? [String: Any])?["zdr"] as? Bool, true)
        }
    }

    private func wrapped(_ content: String) -> String {
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\(jsonString(content))}}]}"
    }

    func testOnlyTheRewrittenClaimIsInTheLastUserMessageWhichOpenRouterTurnsIntoTheExaQuery() async throws {
        enqueueTriage()
        enqueueSuccess("{\"is_claim\": false}")

        _ = try await makeClient().check(claim)

        let messages = (searchedCheckBody()["messages"] as? [[String: Any]]) ?? []
        let system = messages.first { $0["role"] as? String == "system" }?["content"] as? String ?? ""
        let lastUser = messages.last { $0["role"] as? String == "user" }?["content"] as? String
        XCTAssertEqual(lastUser, CheckPrompts.claimLine(rewritten))
        XCTAssertTrue(system.contains(claim.text))
        XCTAssertTrue(system.contains(claim.surroundingTranscript))
    }

    func testDoesNotForceAnEngineWhenTheModelHasNone() async throws {
        var noEngineModel = model
        noEngineModel.openRouterEngine = nil
        enqueueTriage()
        enqueueSuccess("{\"is_claim\": false}")

        _ = try await makeClient(noEngineModel).check(claim)

        XCTAssertNil(webPlugin(searchedCheckBody())["engine"])
    }

    func testSendsReasoningEffortInsteadOfDisablingReasoningWhenConfigured() async throws {
        let client = OpenRouterCheckClient(
            apiKey: "k", model: model, transport: transport, baseUrl: "https://openrouter.test/api/v1",
            today: { [today] in today }, reasoningEffort: "low")
        enqueueTriage(isClaim: false)

        _ = try await client.check(claim)

        let reasoning = jsonBody(of: transport.requests[0])["reasoning"] as? [String: Any]
        XCTAssertEqual(reasoning?["effort"] as? String, "low")
        XCTAssertNil(reasoning?["enabled"])
    }

    func testMaps401ToInvalidKey() async {
        transport.enqueue(.http(status: 401, body: "{\"error\": {\"message\": \"No auth credentials found\"}}"))
        await assertFailure(.invalidKey) { _ = try await makeClient().check(claim) }
    }

    func testMaps402ToNoCredit() async {
        transport.enqueue(.http(status: 402, body: "{\"error\": {\"message\": \"Insufficient credits\"}}"))
        await assertFailure(.noCredit) { _ = try await makeClient().check(claim) }
    }

    func testMaps429ToRateLimited() async {
        transport.enqueue(.http(status: 429, body: "{\"error\": {\"message\": \"Rate limit exceeded\"}}"))
        await assertFailure(.rateLimited) { _ = try await makeClient().check(claim) }
    }

    func testMapsAWebSearchDisabledMessageToSearchDisabled() async {
        transport.enqueue(.http(status: 403, body: "{\"error\": {\"message\": \"Web search is disabled for this account\"}}"))
        await assertFailure(.searchDisabled) { _ = try await makeClient().check(claim) }
    }

    func testMapsOtherErrorsToUnexpected() async {
        transport.enqueue(.http(status: 500, body: "{\"error\": {\"message\": \"internal error\"}}"))
        await assertFailure(.unexpected("internal error")) { _ = try await makeClient().check(claim) }
    }

    func testMapsANetworkFailureToOffline() async {
        transport.enqueue(.failure(URLError(.notConnectedToInternet)))
        await assertFailure(.offline) { _ = try await makeClient().check(claim) }
    }

    func testAddsUpTheCostOpenRouterReportsForEachRequest() async throws {
        enqueueTriage(cost: 0.0002)
        enqueueSuccess("{\"is_claim\": false}", cost: 0.0081)
        let client = makeClient()

        _ = try await client.check(claim)

        XCTAssertEqual(client.spent?.usd ?? -1, 0.0083, accuracy: 1e-9)
        XCTAssertEqual(client.spent?.estimated, false)
    }

    func testFactoryBuildsAClientOnlyWithProviderModelAndKey() async throws {
        struct Vault: KeyVault {
            let key: String?
            func get(_ provider: Provider) async throws -> String? { key }
            func put(_ provider: Provider, key: String) async throws {}
            func remove(_ provider: Provider) async throws {}
        }
        let full = Settings(provider: .openRouter, modelId: model.id, checkLimit: nil)

        let built = try await CheckClientFactory.create(settings: full, keyVault: Vault(key: "sk"), transport: transport)
        XCTAssertNotNil(built)
        let noKey = try await CheckClientFactory.create(settings: full, keyVault: Vault(key: nil), transport: transport)
        XCTAssertNil(noKey)
        let blankKey = try await CheckClientFactory.create(settings: full, keyVault: Vault(key: "  "), transport: transport)
        XCTAssertNil(blankKey)
        let noProvider = try await CheckClientFactory.create(
            settings: Settings(provider: nil, modelId: nil, checkLimit: nil), keyVault: Vault(key: "sk"), transport: transport)
        XCTAssertNil(noProvider)
        let unknownModel = try await CheckClientFactory.create(
            settings: Settings(provider: .openRouter, modelId: "nope/none", checkLimit: nil), keyVault: Vault(key: "sk"),
            transport: transport)
        XCTAssertNil(unknownModel)
    }
}
