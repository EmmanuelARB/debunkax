import AVFoundation
import DebunkaxCore
import Observation
import SwiftUI

/// Drives the Écoute screen. Owned by the app (not the screen), so the scene-phase rule can stop the session
/// wherever the user is.
@MainActor @Observable
final class ListenViewModel {
    private let container: AppContainer
    /// The latest session, kept after it stops so its final state (paused banner, speech problem) stays on screen.
    private var current: ListeningSession?
    private var starting = false
    private var stopRequested = false

    /// The Alert whose ding has already played; survives leaving and re-entering the Écoute screen.
    var lastDingedAlertId: Int64?

    /// Shown under the button when the microphone is refused.
    var showPermissionRationale = false
    /// Whether the refusal can only be undone in the system settings (iOS never asks twice).
    var permissionDenied = false
    /// Progress of the one-time sherpa-onnx model download, while it runs.
    var modelProgress: ModelDownloadProgress = .idle

    init(container: AppContainer) {
        self.container = container
    }

    var state: SessionState { current?.state ?? SessionState() }

    /// True from the tap until the session has ended.
    var isActive: Bool { starting || (current?.state.listening ?? false) }

    // MARK: - Start / stop

    /// The big button: asks for the microphone if needed, then starts.
    func toggle() {
        if state.listening || starting {
            stop()
        } else {
            Task { await startAfterPermission() }
        }
    }

    private func startAfterPermission() async {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            showPermissionRationale = false
            start()
        case .undetermined:
            let granted = await AVAudioApplication.requestRecordPermission()
            permissionDenied = !granted
            showPermissionRationale = !granted
            if granted { start() }
        case .denied:
            permissionDenied = true
            showPermissionRationale = true
        @unknown default:
            showPermissionRationale = true
        }
    }

    func start() {
        if starting || (current?.state.listening ?? false) { return }
        starting = true
        stopRequested = false
        Task {
            let session = await container.newSession()
            current = session
            session.start()
            starting = false
            // The app left the foreground while the session was being set up.
            if stopRequested { stop() }
        }
    }

    func stop() {
        if starting { stopRequested = true }
        guard let session = current, session.state.listening else { return }
        Task { await session.stop() }
    }

    func dismissAlert() {
        current?.dismissAlert()
    }

    // MARK: - Model download

    /// Follows the sherpa-onnx model download for as long as the calling task lives.
    func followModelDownload() async {
        for await progress in container.sherpaModel.progressUpdates() {
            modelProgress = progress
        }
    }
}
