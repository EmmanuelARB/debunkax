import SwiftUI
import UIKit

@main
struct DebunkaxApp: App {
    @State private var container: AppContainer
    @State private var listen: ListenViewModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let container = AppContainer()
        _container = State(initialValue: container)
        _listen = State(initialValue: ListenViewModel(container: container))
    }

    var body: some Scene {
        WindowGroup {
            RootView(listen: listen)
                .environment(container)
                .tint(Palette.primary)
                .task { await container.launch() }
                // The screen stays on only while listening.
                .onChange(of: listen.state.listening, initial: true) { _, listening in
                    UIApplication.shared.isIdleTimerDisabled = listening
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // Mic sessions listen only in the foreground (AGENTS.md): going to the background stops them, like
            // Android's onStop. A brief .inactive (Control Center, a notification banner) keeps the session.
            if phase == .background { listen.stop() }
        }
    }
}
