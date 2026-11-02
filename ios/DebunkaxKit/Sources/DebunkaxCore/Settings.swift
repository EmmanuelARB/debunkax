import Foundation

public enum Provider: String, CaseIterable, Codable, Sendable {
    case openRouter = "OPENROUTER"
}

/// A model offered to users. Only combinations whose web search honours the Trusted Source
/// allowlist belong here (ADR 0001). `openRouterEngine` forces OpenRouter's search engine when set.
public struct ProviderModel: Hashable, Sendable {
    public var provider: Provider
    public var id: String
    public var label: String
    public var openRouterEngine: String?

    public init(provider: Provider, id: String, label: String, openRouterEngine: String? = nil) {
        self.provider = provider
        self.id = id
        self.label = label
        self.openRouterEngine = openRouterEngine
    }
}

public struct Settings: Hashable, Sendable {
    public var provider: Provider?
    public var modelId: String?
    /// Maximum Checks per Listening Session; nil means unlimited.
    public var checkLimit: Int?

    public init(provider: Provider?, modelId: String?, checkLimit: Int?) {
        self.provider = provider
        self.modelId = modelId
        self.checkLimit = checkLimit
    }
}

public protocol SettingsRepository: Sendable {
    var settings: AsyncStream<Settings> { get }
    func setProvider(_ provider: Provider, modelId: String) async throws
    /// Forgets the configured provider and model (e.g. on disconnect); the stored key is the KeyVault's business.
    func clearProvider() async throws
    func setCheckLimit(_ limit: Int?) async throws
}

/// API keys, kept in the Keychain (this device only).
public protocol KeyVault: Sendable {
    func get(_ provider: Provider) async throws -> String?
    func put(_ provider: Provider, key: String) async throws
    func remove(_ provider: Provider) async throws
}
