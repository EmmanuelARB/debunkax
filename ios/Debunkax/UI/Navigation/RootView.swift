import DebunkaxCore
import SwiftUI

enum Route: Hashable {
    case history
    case settings
    case trustedSources
    case privacy
    case speakerTest
    case claim(Int64)
}

/// The navigation stack of the app, starting at the Écoute screen.
struct RootView: View {
    @Environment(AppContainer.self) private var container
    let listen: ListenViewModel

    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            ListenView(
                model: listen,
                onOpenHistory: { path.append(.history) },
                onOpenSettings: { path.append(.settings) },
                onOpenClaim: { path.append(.claim($0)) })
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                }
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .history:
            HistoryScreen(container: container) { path.append(.claim($0)) }
        case .settings:
            SettingsScreen(
                container: container,
                onOpenTrustedSources: { path.append(.trustedSources) },
                onOpenPrivacy: { path.append(.privacy) },
                onOpenSpeakerTest: { path.append(.speakerTest) })
        case .trustedSources:
            TrustedSourcesView()
        case .privacy:
            PrivacyView()
        case .speakerTest:
            SpeakerTestScreen(container: container)
        case .claim(let id):
            ClaimDetailScreen(container: container, claimId: id)
        }
    }
}

// Each screen owns its view model for as long as it is on the stack.

private struct HistoryScreen: View {
    @State private var model: HistoryViewModel
    let onOpenClaim: (Int64) -> Void

    init(container: AppContainer, onOpenClaim: @escaping (Int64) -> Void) {
        _model = State(initialValue: HistoryViewModel(container: container))
        self.onOpenClaim = onOpenClaim
    }

    var body: some View { HistoryView(model: model, onOpenClaim: onOpenClaim) }
}

private struct SettingsScreen: View {
    @State private var model: SettingsViewModel
    let onOpenTrustedSources: () -> Void
    let onOpenPrivacy: () -> Void
    let onOpenSpeakerTest: () -> Void

    init(
        container: AppContainer, onOpenTrustedSources: @escaping () -> Void, onOpenPrivacy: @escaping () -> Void,
        onOpenSpeakerTest: @escaping () -> Void
    ) {
        _model = State(initialValue: SettingsViewModel(container: container))
        self.onOpenTrustedSources = onOpenTrustedSources
        self.onOpenPrivacy = onOpenPrivacy
        self.onOpenSpeakerTest = onOpenSpeakerTest
    }

    var body: some View {
        SettingsView(
            model: model, onOpenTrustedSources: onOpenTrustedSources, onOpenPrivacy: onOpenPrivacy,
            onOpenSpeakerTest: onOpenSpeakerTest)
    }
}

private struct SpeakerTestScreen: View {
    @State private var model: SpeakerTestViewModel

    init(container: AppContainer) {
        _model = State(initialValue: SpeakerTestViewModel(container: container))
    }

    var body: some View { SpeakerTestView(model: model) }
}

private struct ClaimDetailScreen: View {
    @State private var model: ClaimDetailViewModel

    init(container: AppContainer, claimId: Int64) {
        _model = State(initialValue: ClaimDetailViewModel(container: container, claimId: claimId))
    }

    var body: some View { ClaimDetailView(model: model) }
}
