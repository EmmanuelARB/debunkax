import AVFoundation
import DebunkaxCore
import Foundation
import Speech
import Synchronization

/// `SpeechEngine` backed by Apple's `SpeechAnalyzer` + `SpeechTranscriber` (iOS 26), in French (fr-FR), on device,
/// listening to `audio` (the microphone, or the debug demo file).
///
/// Contract, as `MlKitSpeechEngine` on Android: a missing locale or an unsupported device throws
/// `SpeechFailure.unsupported` (so `FallbackSpeechEngine` switches to sherpa-onnx); missing assets start a download
/// that outlives the session and throw `SpeechFailure.modelDownloading` (the session retries); once actually
/// listening an empty partial is emitted so callers can clear the model-download notice.
///
/// Partial (volatile) results come out as `isFinal == false`, finalized results as `isFinal == true`.
/// Never logs or persists recognized text or audio: the audio is converted chunk by chunk and dropped.
final class SpeechAnalyzerEngine: SpeechEngine {
    private let audio: any AudioSource
    private let locale: Locale
    private let now: @Sendable () -> Date
    private let downloading = Mutex<Bool>(false)

    init(audio: any AudioSource, locale: Locale = Locale(identifier: "fr-FR"), now: @escaping @Sendable () -> Date = { Date() }) {
        self.audio = audio
        self.locale = locale
        self.now = now
    }

    func transcribe() -> AsyncThrowingStream<TranscriptSegment, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await self.run(continuation)
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private static let unsupportedMessage =
        "Cet appareil ne prend pas en charge la reconnaissance vocale française sur l'appareil."

    private static func makeTranscriber(_ locale: Locale) -> SpeechTranscriber {
        SpeechTranscriber(
            locale: locale, transcriptionOptions: [], reportingOptions: [.volatileResults], attributeOptions: [])
    }

    private func run(_ continuation: AsyncThrowingStream<TranscriptSegment, Error>.Continuation) async throws {
        guard SpeechTranscriber.isAvailable else {
            throw SpeechFailure.unsupported(Self.unsupportedMessage)
        }
        let wanted = locale.identifier(.bcp47)
        guard let supported = await SpeechTranscriber.supportedLocales.first(where: { $0.identifier(.bcp47) == wanted })
        else {
            throw SpeechFailure.unsupported(Self.unsupportedMessage)
        }

        let transcriber = Self.makeTranscriber(supported)
        switch await AssetInventory.status(forModules: [transcriber]) {
        case .installed:
            break
        case .supported, .downloading:
            startDownload(supported)
            throw SpeechFailure.modelDownloading
        case .unsupported:
            throw SpeechFailure.unsupported(Self.unsupportedMessage)
        @unknown default:
            throw SpeechFailure.engine("Statut de disponibilité du modèle de reconnaissance vocale inconnu.")
        }

        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            throw SpeechFailure.unsupported(Self.unsupportedMessage)
        }
        let converter = PCMChunkConverter(target: format)
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let (inputSequence, inputBuilder) = AsyncStream<AnalyzerInput>.makeStream()

        do {
            try await analyzer.start(inputSequence: inputSequence)
            continuation.yield(TranscriptSegment(text: "", isFinal: false, at: now()))

            let clock = now
            let source = audio
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask {
                    for try await result in transcriber.results {
                        let text = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
                        if text.isEmpty { continue }
                        continuation.yield(TranscriptSegment(text: text, isFinal: result.isFinal, at: clock()))
                    }
                }
                group.addTask {
                    for try await chunk in source.chunks() {
                        try Task.checkCancellation()
                        if let buffer = converter.convert(chunk) {
                            inputBuilder.yield(AnalyzerInput(buffer: buffer))
                        }
                    }
                    inputBuilder.finish()
                    try await analyzer.finalizeAndFinishThroughEndOfInput()
                }
                try await group.waitForAll()
            }
        } catch {
            inputBuilder.finish()
            await analyzer.cancelAndFinishNow()
            if error is CancellationError || error is SpeechFailure { throw error }
            throw SpeechFailure.engine("Erreur du moteur de reconnaissance vocale.")
        }
    }

    /// Downloads the French assets on a task of its own, so leaving the session screen can't cancel it. The next
    /// `transcribe()` call re-checks the status and retries.
    private func startDownload(_ locale: Locale) {
        let start = downloading.withLock { running -> Bool in
            if running { return false }
            running = true
            return true
        }
        guard start else { return }
        Task.detached { [self] in
            do {
                let transcriber = Self.makeTranscriber(locale)
                if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                    try await request.downloadAndInstall()
                }
            } catch {
                // Retried by the next session start.
            }
            downloading.withLock { $0 = false }
        }
    }
}

/// Turns 16 kHz mono `Int16` chunks into `AVAudioPCMBuffer`s in the analyzer's preferred format. Used from one task only.
private final class PCMChunkConverter: @unchecked Sendable {
    private let source: AVAudioFormat
    private let target: AVAudioFormat
    private let converter: AVAudioConverter?

    init(target: AVAudioFormat) {
        self.target = target
        // The force-unwrap cannot fail: standard PCM parameters.
        let source = AVAudioFormat(
            commonFormat: .pcmFormatInt16, sampleRate: Double(AudioChunking.sampleRate), channels: 1, interleaved: true)!
        self.source = source
        self.converter = source == target ? nil : AVAudioConverter(from: source, to: target)
    }

    func convert(_ chunk: [Int16]) -> AVAudioPCMBuffer? {
        guard !chunk.isEmpty,
            let input = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: AVAudioFrameCount(chunk.count)),
            let channel = input.int16ChannelData
        else { return nil }
        chunk.withUnsafeBufferPointer { channel[0].update(from: $0.baseAddress!, count: chunk.count) }
        input.frameLength = AVAudioFrameCount(chunk.count)
        guard let converter else { return input }

        let ratio = target.sampleRate / source.sampleRate
        let capacity = AVAudioFrameCount((Double(chunk.count) * ratio).rounded(.up)) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
        let feeder = OneShotInput(input)
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, outStatus in feeder.take(outStatus) }
        guard status != .error, error == nil, output.frameLength > 0 else { return nil }
        return output
    }
}
