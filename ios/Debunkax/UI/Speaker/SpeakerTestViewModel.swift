import AVFoundation
import DebunkaxCore
import Observation

/// 5 s windows every 2.5 s, as in spikes/speakers/live.py. Only the current window is kept in memory.
@MainActor @Observable
final class SpeakerTestViewModel {
    private let container: AppContainer

    private(set) var running = false
    private(set) var speakerCount = 0
    var threshold: Float = SpeakerMatcher.defaultThreshold
    private(set) var current: SpeakerGuess?
    /// RMS of the last window, 0..1: shows whether the microphone hears anything at all.
    private(set) var level: Float?
    /// Newest first: one line each time the guess changes.
    private(set) var log: [String] = []
    private(set) var error: String?

    @ObservationIgnored private var identifier: SpeakerIdentifier?
    @ObservationIgnored private var job: Task<Void, Never>?
    @ObservationIgnored private var loading: Task<Void, Never>?

    private nonisolated static let window = SpeakerAudio.sampleRate * 5
    private nonisolated static let hop = SpeakerAudio.sampleRate * 5 / 2

    init(container: AppContainer) {
        self.container = container
    }

    /// Loads the voice model off the main thread.
    func load() {
        guard loading == nil, identifier == nil else { return }
        loading = Task {
            let loaded = await Task.detached { [container] in container.speakerIdentifier() }.value
            if let loaded {
                identifier = loaded
                speakerCount = loaded.speakers.count
            } else {
                error = "Empreintes vocales absentes de cette version"
            }
            loading = nil
        }
    }

    /// Asks for the microphone if needed, then starts.
    func toggle() {
        if running {
            stop()
            return
        }
        Task {
            switch AVAudioApplication.shared.recordPermission {
            case .granted: start()
            case .undetermined:
                if await AVAudioApplication.requestRecordPermission() { start() }
            default: break
            }
        }
    }

    func start() {
        guard let identifier, job == nil else { return }
        running = true
        log = []
        current = nil
        let startedAt = Date()
        job = Task.detached { [weak self] in
            var window = [Float](repeating: 0, count: Self.window)
            var filled = 0
            var lastKey: String?
            do {
                for try await chunk in MicAudioSource().chunks() {
                    for sample in chunk {
                        window[filled] = Float(sample) / 32768
                        filled += 1
                        if filled == Self.window {
                            let level = SpeakerAudio.rms(window)
                            guard let threshold = await self?.threshold else { return }
                            let guess = identifier.identify(window, threshold: threshold)
                            let key: String
                            switch guess {
                            case .named(let name, _): key = name
                            case .unknown: key = "?"
                            case .silence: key = "-"
                            }
                            let line: String? =
                                key != lastKey
                                ? "\(Self.clock(Date().timeIntervalSince(startedAt)))  \(Self.describe(guess))" : nil
                            lastKey = key
                            await self?.apply(guess: guess, level: level, line: line)
                            let tail = Array(window[Self.hop..<Self.window])
                            window.replaceSubrange(0..<tail.count, with: tail)
                            filled = Self.window - Self.hop
                        }
                    }
                }
            } catch is CancellationError {
            } catch {
                await self?.fail(error.localizedDescription)
            }
            await self?.finished()
        }
    }

    func stop() {
        job?.cancel()
        job = nil
        running = false
    }

    private func apply(guess: SpeakerGuess, level: Float, line: String?) {
        current = guess
        self.level = level
        if let line { log = Array(([line] + log).prefix(200)) }
    }

    private func fail(_ message: String) { error = message }

    private func finished() {
        running = false
        job = nil
    }

    // MARK: - Text

    private nonisolated static func describe(_ guess: SpeakerGuess) -> String {
        switch guess {
        case .named(let name, let value): "\(name) (\(formatScore(value)))"
        case .unknown(let closest, let value): "inconnu (plus proche : \(closest) \(formatScore(value)))"
        case .silence: "(silence)"
        }
    }

    private nonisolated static func clock(_ seconds: TimeInterval) -> String {
        let s = Int(seconds)
        return String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
}

func formatScore(_ s: Float) -> String {
    String(format: "%.2f", locale: Locale(identifier: "fr_FR"), s)
}
