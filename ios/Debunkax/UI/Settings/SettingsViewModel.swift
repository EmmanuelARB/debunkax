import DebunkaxCore
import Observation

@MainActor @Observable
final class SettingsViewModel {
    private let container: AppContainer

    private(set) var settings = Settings(provider: nil, modelId: nil, checkLimit: nil)
    private(set) var openRouterKeyStored = false
    var message: String?
    private(set) var signingIn = false

    #if DEBUG
    var demoModeEnabled: Bool
    var realAudioDemoModeEnabled: Bool
    #endif

    @ObservationIgnored private var observation: Task<Void, Never>?

    init(container: AppContainer) {
        self.container = container
        #if DEBUG
        demoModeEnabled = container.demoMode
        realAudioDemoModeEnabled = container.realAudioDemoMode
        #endif
    }

    func startObserving() {
        refreshKeyStatus()
        guard observation == nil else { return }
        let stream = container.settings.settings
        observation = Task { [weak self] in
            for await value in stream {
                if Task.isCancelled { return }
                self?.settings = value
            }
        }
    }

    func stopObserving() {
        observation?.cancel()
        observation = nil
    }

    func refreshKeyStatus() {
        Task {
            openRouterKeyStored = (try? await container.keyVault.get(.openRouter)) != nil
        }
    }

    func selectModel(_ model: ProviderModel) {
        Task { try? await container.settings.setProvider(model.provider, modelId: model.id) }
    }

    /// OpenRouter's OAuth PKCE flow in a system web session; the key goes to the Keychain, the default model is selected.
    func connectOpenRouter() {
        if signingIn { return }
        signingIn = true
        Task {
            defer { signingIn = false }
            do {
                let key = try await container.openRouterAuth.signIn()
                try await container.keyVault.put(.openRouter, key: key)
                try await container.settings.setProvider(.openRouter, modelId: Models.default.id)
                message = "Connexion OpenRouter réussie."
            } catch is CancellationError {
                // The user closed the sign-in sheet: nothing to report.
            } catch {
                message = "Échec de la connexion OpenRouter."
            }
            refreshKeyStatus()
        }
    }

    /// Deletes the stored OpenRouter key and forgets the provider, so no Check can be sent until reconnecting.
    func disconnectOpenRouter() {
        Task {
            try? await container.keyVault.remove(.openRouter)
            try? await container.settings.clearProvider()
            refreshKeyStatus()
        }
    }

    func setCheckLimit(_ limit: Int?) {
        Task { try? await container.settings.setCheckLimit(limit) }
    }

    #if DEBUG
    /// Called when a toggle changes; the two demo modes exclude each other.
    func demoModeChanged(_ enabled: Bool) {
        container.setDemoMode(enabled)
        if enabled { realAudioDemoModeEnabled = false }
    }

    func realAudioDemoModeChanged(_ enabled: Bool) {
        container.setRealAudioDemoMode(enabled)
        if enabled { demoModeEnabled = false }
    }
    #endif

    func openRouterModels() -> [ProviderModel] {
        Models.all.filter { $0.provider == .openRouter }
    }
}
