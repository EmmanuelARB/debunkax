import Foundation

/// Builds the `CheckClient` matching the user's `Settings` and stored key, or nil if either is missing.
public enum CheckClientFactory {
    public static func create(
        settings: Settings, keyVault: any KeyVault, transport: any HTTPTransport
    ) async throws -> (any CheckClient)? {
        guard let provider = settings.provider,
            let modelId = settings.modelId,
            let model = Models.find(provider, modelId),
            let apiKey = try await keyVault.get(provider), !apiKey.isBlank
        else { return nil }

        return OpenRouterCheckClient(apiKey: apiKey, model: model, transport: transport)
    }
}
