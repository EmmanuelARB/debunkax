import Foundation
import Synchronization

/// Tells which enrolled leader is speaking in a few seconds of 16 kHz audio (ADR 0003). Implemented on the
/// platform side (sherpa-onnx's speaker embedding extractor); the matching and thresholds live in `SpeakerMatcher`.
public protocol SpeakerEmbeddingIdentifier: Sendable {
    var speakers: [EnrolledSpeaker] { get }
    func identify(_ samples: [Float], threshold: Float) -> SpeakerGuess
}

/// The audio-level rules every identifier shares (they are in `SpeakerIdentifier.kt` on Android).
public enum SpeakerAudio {
    public static let sampleRate = 16000
    /// A TV across the room through the microphone is far quieter than the spike's files.
    public static let silenceRMS: Float = 0.0005
    public static let targetRMS: Float = 0.05

    public static func rms(_ samples: [Float]) -> Float {
        var sum = 0.0
        for x in samples { sum += Double(x) * Double(x) }
        return Float((sum / Double(samples.count)).squareRoot())
    }

    /// The window brought to the enrolment clips' level (the model scores quiet audio lower: 0.77 at 0.05 RMS,
    /// 0.66 at 0.0025, 0.38 at 0.0005 on the same clip), or nil when it is silence.
    public static func normalizedForIdentification(_ samples: [Float]) -> [Float]? {
        let level = rms(samples)
        if level < silenceRMS { return nil }
        let gain = targetRMS / level
        return samples.map { min(1, max(-1, $0 * gain)) }
    }
}

/// Where the platform's audio source hands out 16 kHz mono chunks (100 ms, `Int16`).
public typealias AudioChunkTap = @Sendable ([Int16]) -> Void

/// Recognises Speakers in the audio the tap hands to speech-to-text: 5 s windows every 2.5 s, as in
/// spikes/speakers/live.py. Only the current window is kept; recognition runs off the audio thread
/// and skips windows when it falls behind rather than queueing audio.
public final class SpeakerTracker: SpeakerRecognition {
    private static let windowSize = SpeakerAudio.sampleRate * 5
    private static let hopSize = SpeakerAudio.sampleRate * 5 / 2

    private struct Window: Sendable {
        let end: Date
        let samples: [Float]
    }

    private struct State {
        var running = false
        var window = [Float](repeating: 0, count: SpeakerTracker.windowSize)
        var filled = 0
        var pending: AsyncStream<Window>.Continuation?
        var worker: Task<Void, Never>?
        var journalists: Set<String> = []
    }

    private let setTap: @Sendable (AudioChunkTap?) -> Void
    private let identifier: @Sendable () -> (any SpeakerEmbeddingIdentifier)?
    private let now: @Sendable () -> Date
    private let threshold: Float
    private let timeline = SpeakerTimeline()
    private let state = Mutex<State>(State())

    /// - Parameters:
    ///   - setTap: installs (or, with nil, removes) the callback that receives each audio chunk; the audio source
    ///     calls it from its own thread. Equivalent to assigning `TappedAudioSource.tap`.
    ///   - identifier: called once per session off the main thread; nil when the model isn't in this build.
    public init(
        setTap: @escaping @Sendable (AudioChunkTap?) -> Void,
        identifier: @escaping @Sendable () -> (any SpeakerEmbeddingIdentifier)?,
        now: @escaping @Sendable () -> Date = { Date() },
        threshold: Float = SpeakerMatcher.defaultThreshold
    ) {
        self.setTap = setTap
        self.identifier = identifier
        self.now = now
        self.threshold = threshold
    }

    public func start() {
        let (stream, continuation) = AsyncStream<Window>.makeStream(of: Window.self, bufferingPolicy: .bufferingNewest(1))
        let proceed = state.withLock { s -> Bool in
            if s.running { return false }
            s.running = true
            s.filled = 0
            s.pending = continuation
            return true
        }
        guard proceed else {
            continuation.finish()
            return
        }
        let worker = Task.detached(priority: .utility) { [self] in
            // Loads the model off the main thread; without it every Speaker stays unknown.
            guard let id = identifier() else { return }
            let journalists = Set(id.speakers.filter { $0.role == .journalist }.map(\.name))
            state.withLock { $0.journalists = journalists }
            for await window in stream {
                if Task.isCancelled { break }
                timeline.add(end: window.end, guess: id.identify(window.samples, threshold: threshold))
            }
        }
        state.withLock { $0.worker = worker }
        setTap { [self] chunk in feed(chunk) }
    }

    public func stop() {
        setTap(nil)
        let (continuation, worker) = state.withLock { s -> (AsyncStream<Window>.Continuation?, Task<Void, Never>?) in
            let taken = (s.pending, s.worker)
            s.pending = nil
            s.worker = nil
            s.running = false
            s.filled = 0
            return taken
        }
        continuation?.finish()
        worker?.cancel()
        timeline.clear()
    }

    public func speakerBetween(from: Date, to: Date) -> String? {
        timeline.speakerBetween(from: from, to: to)
    }

    public func addresseeBefore(_ before: Date, claimant: String) -> String? {
        let journalists = state.withLock { $0.journalists }
        return timeline.addresseeBefore(before, claimant: claimant) { journalists.contains($0) }
    }

    /// Takes one audio chunk from the tap; ignored unless started. Cheap enough for the audio thread.
    public func feed(_ chunk: [Int16]) {
        state.withLock { s in
            guard let pending = s.pending else { return }
            for sample in chunk {
                s.window[s.filled] = Float(sample) / 32768
                s.filled += 1
                if s.filled == Self.windowSize {
                    pending.yield(Window(end: now(), samples: s.window))
                    let tail = Array(s.window[Self.hopSize..<Self.windowSize])
                    s.window.replaceSubrange(0..<tail.count, with: tail)
                    s.filled = Self.windowSize - Self.hopSize
                }
            }
        }
    }
}
