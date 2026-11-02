import DebunkaxCore
import Foundation

/// `SpeechEngine` backed by sherpa-onnx's streaming zipformer French model (`sherpa-onnx-streaming-zipformer-fr-2023-04-14`),
/// run fully on device through the official `SherpaOnnx.swift` wrapper (vendored by scripts/build-sherpa.sh). This is
/// the fallback engine, used once `SpeechAnalyzerEngine` reports `SpeechFailure.unsupported` (see `FallbackSpeechEngine`).
///
/// The model is not bundled; while `model` has not finished downloading and verifying, `transcribe()` throws
/// `SpeechFailure.modelDownloading`, which the session already retries.
///
/// Streams 100 ms chunks from `audio` through sherpa-onnx's endpoint detector: partial results are emitted as
/// `isFinal == false`, and each endpoint emits a final segment before the recognizer stream is reset. Never keeps
/// audio beyond the current chunk, and never logs recognized text.
final class SherpaSpeechEngine: SpeechEngine {
    private let model: SherpaModelManager
    private let audio: any AudioSource
    private let now: @Sendable () -> Date

    init(model: SherpaModelManager, audio: any AudioSource, now: @escaping @Sendable () -> Date = { Date() }) {
        self.model = model
        self.audio = audio
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
                } catch let failure as SpeechFailure {
                    continuation.finish(throwing: failure)
                } catch {
                    continuation.finish(throwing: SpeechFailure.engine("Erreur du moteur de reconnaissance vocale sherpa-onnx."))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func run(_ continuation: AsyncThrowingStream<TranscriptSegment, Error>.Continuation) async throws {
        guard model.isReady() else {
            model.startDownloadIfNeeded()
            throw SpeechFailure.modelDownloading
        }
        let recognizer = newRecognizer(model.paths())
        continuation.yield(TranscriptSegment(text: "", isFinal: false, at: now()))
        var lastPartial = ""
        for try await chunk in audio.chunks() {
            if Task.isCancelled { break }
            let samples = chunk.map { Float($0) / 32768.0 }
            recognizer.acceptWaveform(samples: samples, sampleRate: AudioChunking.sampleRate)
            while recognizer.isReady() { recognizer.decode() }

            let isEndpoint = recognizer.isEndpoint()
            let text = recognizer.getResult().text

            if isEndpoint {
                recognizer.reset()
                if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    continuation.yield(TranscriptSegment(text: text, isFinal: true, at: now()))
                }
                lastPartial = ""
            } else if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text != lastPartial {
                lastPartial = text
                continuation.yield(TranscriptSegment(text: text, isFinal: false, at: now()))
            }
        }
        // The recognizer (and its stream) is released when it goes out of scope.
    }

    /// Same values as the Kotlin engine: 80-dim features at 16 kHz, zipformer transducer, 2 threads, greedy search,
    /// endpoint rules 2.4 s / 1.4 s / 20 s (sherpa-onnx's Kotlin defaults; the Swift wrapper's rule 3 default is 30).
    private func newRecognizer(_ paths: SherpaModelPaths) -> SherpaOnnxRecognizer {
        // The wrapper hands C pointers into autoreleased NSStrings; the pool outlives the (copying) C constructor call.
        autoreleasepool {
            let transducer = sherpaOnnxOnlineTransducerModelConfig(
                encoder: paths.encoder, decoder: paths.decoder, joiner: paths.joiner)
            let modelConfig = sherpaOnnxOnlineModelConfig(
                tokens: paths.tokens, transducer: transducer, numThreads: 2, modelType: "zipformer")
            var config = sherpaOnnxOnlineRecognizerConfig(
                featConfig: sherpaOnnxFeatureConfig(sampleRate: AudioChunking.sampleRate, featureDim: 80),
                modelConfig: modelConfig,
                enableEndpoint: true,
                rule1MinTrailingSilence: 2.4,
                rule2MinTrailingSilence: 1.4,
                rule3MinUtteranceLength: 20,
                decodingMethod: "greedy_search")
            return SherpaOnnxRecognizer(config: &config)
        }
    }
}
