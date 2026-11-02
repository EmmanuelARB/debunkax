import AVFoundation
import DebunkaxCore
import Foundation
import Synchronization

/// 16 kHz, mono, 16-bit PCM samples in fixed-size real-time chunks (100 ms = 1600 samples). Never persisted.
protocol AudioSource: Sendable {
    func chunks() -> AsyncThrowingStream<[Int16], Error>
}

enum AudioChunking {
    static let sampleRate = 16000
    static let chunkMillis = 100
    static let chunkSamples = sampleRate * chunkMillis / 1000
}

/// Hands every chunk of `inner` to the tap too, so speaker recognition hears what speech-to-text hears.
final class TappedAudioSource: AudioSource {
    private let inner: any AudioSource
    private let tap = Mutex<AudioChunkTap?>(nil)

    init(_ inner: any AudioSource) {
        self.inner = inner
    }

    /// Installs (or, with nil, removes) the tap. Usable directly as `SpeakerTracker`'s `setTap` argument.
    @Sendable
    func setTap(_ newTap: AudioChunkTap?) {
        tap.withLock { $0 = newTap }
    }

    func chunks() -> AsyncThrowingStream<[Int16], Error> {
        let inner = self.inner
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await chunk in inner.chunks() {
                        let current = self.tap.withLock { $0 }
                        current?(chunk)
                        continuation.yield(chunk)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

// MARK: - Microphone

/// Reads from the device microphone in 100 ms chunks. Precondition: the caller (UI layer) already holds the
/// microphone permission before iterating `chunks()`; this class never requests it. Audio is converted and
/// handed off chunk by chunk; nothing is buffered beyond the current partial chunk, and nothing is written to disk.
struct MicAudioSource: AudioSource {
    init() {}

    func chunks() -> AsyncThrowingStream<[Int16], Error> {
        AsyncThrowingStream { continuation in
            let capture = MicCapture(continuation: continuation)
            continuation.onTermination = { _ in capture.stop() }
            do {
                try capture.start()
            } catch {
                continuation.finish(throwing: error)
                capture.stop()
            }
        }
    }
}

/// One microphone capture, from `start()` to `stop()`. Accessed from the audio render thread (the tap),
/// notification callbacks and the stream's termination, so all mutable state sits behind `lock`.
private final class MicCapture: @unchecked Sendable {
    private let continuation: AsyncThrowingStream<[Int16], Error>.Continuation
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var converter: AVAudioConverter?
    private var outputFormat: AVAudioFormat?
    private var pending: [Int16] = []
    private var observers: [NSObjectProtocol] = []
    private var stopped = false
    private var tapInstalled = false

    init(continuation: AsyncThrowingStream<[Int16], Error>.Continuation) {
        self.continuation = continuation
        pending.reserveCapacity(AudioChunking.chunkSamples * 2)
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: [])
            try session.setActive(true)
        } catch {
            throw SpeechFailure.engine("Impossible d'activer le microphone.")
        }

        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
            let outFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16, sampleRate: Double(AudioChunking.sampleRate), channels: 1, interleaved: true),
            let converter = AVAudioConverter(from: inputFormat, to: outFormat)
        else {
            throw SpeechFailure.engine("Le microphone n'est pas disponible.")
        }
        lock.withLock {
            self.converter = converter
            self.outputFormat = outFormat
        }

        let center = NotificationCenter.default
        let interruption = center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: nil
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                AVAudioSession.InterruptionType(rawValue: raw) == .began
            else { return }
            self?.fail("L'enregistrement a été interrompu (appel, alarme ou autre application). Relancez l'écoute.")
        }
        let configChange = center.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil
        ) { [weak self] _ in
            self?.fail("Le microphone a changé (branchement ou débranchement). Relancez l'écoute.")
        }
        lock.withLock { observers = [interruption, configChange] }

        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            self?.handle(buffer)
        }
        lock.withLock { tapInstalled = true }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            throw SpeechFailure.engine("Impossible de démarrer le microphone.")
        }
    }

    func stop() {
        let (wasStopped, tapWasInstalled, toRemove) = lock.withLock { () -> (Bool, Bool, [NSObjectProtocol]) in
            let snapshot = (stopped, tapInstalled, observers)
            stopped = true
            tapInstalled = false
            observers = []
            pending.removeAll()
            return snapshot
        }
        if wasStopped { return }
        for observer in toRemove { NotificationCenter.default.removeObserver(observer) }
        if tapWasInstalled { engine.inputNode.removeTap(onBus: 0) }
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func fail(_ message: String) {
        continuation.finish(throwing: SpeechFailure.engine(message))
        stop()
    }

    private func handle(_ buffer: AVAudioPCMBuffer) {
        let (converter, format, isStopped) = lock.withLock { (self.converter, self.outputFormat, self.stopped) }
        guard !isStopped, let converter, let format else { return }

        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }

        let feeder = OneShotInput(buffer)
        var conversionError: NSError?
        let status = converter.convert(to: out, error: &conversionError) { _, outStatus in
            feeder.take(outStatus)
        }
        guard status != .error, conversionError == nil, let channel = out.int16ChannelData else { return }
        let produced = Int(out.frameLength)
        guard produced > 0 else { return }

        var ready: [[Int16]] = []
        lock.withLock {
            pending.append(contentsOf: UnsafeBufferPointer(start: channel[0], count: produced))
            var offset = 0
            while pending.count - offset >= AudioChunking.chunkSamples {
                ready.append(Array(pending[offset..<(offset + AudioChunking.chunkSamples)]))
                offset += AudioChunking.chunkSamples
            }
            if offset > 0 { pending.removeSubrange(0..<offset) }
        }
        for chunk in ready { continuation.yield(chunk) }
    }
}

/// Hands one buffer to an `AVAudioConverter` input block, then reports that no more data is available now.
final class OneShotInput: @unchecked Sendable {
    private let buffer: AVAudioPCMBuffer
    private var supplied = false

    init(_ buffer: AVAudioPCMBuffer) {
        self.buffer = buffer
    }

    func take(_ status: UnsafeMutablePointer<AVAudioConverterInputStatus>) -> AVAudioBuffer? {
        if supplied {
            status.pointee = .noDataNow
            return nil
        }
        supplied = true
        status.pointee = .haveData
        return buffer
    }
}

// MARK: - Debug demo file

/// Debug-only `AudioSource` that replays a 16 kHz mono 16-bit PCM WAV file at real-time pace instead of the
/// microphone, looping back to the start once it ends so a demo session never goes silent. Used by
/// "Démo audio réelle" in Réglages.
struct FileAudioSource: AudioSource {
    let url: URL

    init(url: URL) {
        self.url = url
    }

    func chunks() -> AsyncThrowingStream<[Int16], Error> {
        let url = self.url
        return AsyncThrowingStream { continuation in
            let task = Task.detached {
                do {
                    try await Self.replay(url, into: continuation)
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

    private struct WavInfo {
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset: UInt64 = 0
        var dataSize: UInt64 = 0
    }

    private static func replay(_ url: URL, into continuation: AsyncThrowingStream<[Int16], Error>.Continuation) async throws {
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw SpeechFailure.engine("Fichier audio de démonstration introuvable : \(url.lastPathComponent)")
        }
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            throw SpeechFailure.engine("Fichier audio de démonstration illisible : \(url.lastPathComponent)")
        }
        defer { try? handle.close() }

        let wav = try readHeader(handle)
        guard wav.sampleRate == AudioChunking.sampleRate, wav.channels == 1, wav.bitsPerSample == 16 else {
            throw SpeechFailure.engine("Le fichier audio de démonstration doit être en 16 kHz mono 16 bits")
        }
        guard wav.dataSize > 0 else {
            throw SpeechFailure.engine("Le fichier audio de démonstration est vide")
        }

        let chunkBytes = AudioChunking.chunkSamples * 2
        while !Task.isCancelled {
            try handle.seek(toOffset: wav.dataOffset)
            var position: UInt64 = 0
            while !Task.isCancelled && position < wav.dataSize {
                let toRead = Int(min(UInt64(chunkBytes), wav.dataSize - position))
                guard let data = try handle.read(upToCount: toRead), !data.isEmpty else { break }
                position += UInt64(data.count)
                let sampleCount = data.count / 2
                if sampleCount == 0 { break }
                var samples = [Int16](repeating: 0, count: sampleCount)
                data.withUnsafeBytes { raw in
                    for i in 0..<sampleCount {
                        let lo = UInt16(raw[i * 2])
                        let hi = UInt16(raw[i * 2 + 1])
                        samples[i] = Int16(bitPattern: (hi << 8) | lo)
                    }
                }
                continuation.yield(samples)
                try await Task.sleep(for: .milliseconds(sampleCount * 1000 / AudioChunking.sampleRate))
            }
        }
    }

    private static func readHeader(_ handle: FileHandle) throws -> WavInfo {
        func invalid(_ detail: String) -> SpeechFailure { .engine(detail) }
        guard let riff = try handle.read(upToCount: 12), riff.count == 12,
            String(decoding: riff[riff.startIndex..<riff.startIndex + 4], as: UTF8.self) == "RIFF",
            String(decoding: riff[riff.startIndex + 8..<riff.startIndex + 12], as: UTF8.self) == "WAVE"
        else {
            throw invalid("Fichier audio de démonstration invalide (pas un WAV)")
        }
        var info = WavInfo()
        var foundData = false
        while let header = try handle.read(upToCount: 8), header.count == 8 {
            let bytes = [UInt8](header)
            let id = String(decoding: bytes[0..<4], as: UTF8.self)
            let size = Int(leInt(bytes, 4))
            if id == "fmt " {
                guard size >= 16, let fmt = try handle.read(upToCount: size), fmt.count == size else {
                    throw invalid("Fichier audio de démonstration invalide (chunk 'fmt ' tronqué)")
                }
                let f = [UInt8](fmt)
                info.channels = Int(leShort(f, 2))
                info.sampleRate = Int(leInt(f, 4))
                info.bitsPerSample = Int(leShort(f, 14))
            } else if id == "data" {
                info.dataOffset = try handle.offset()
                info.dataSize = UInt64(max(0, size))
                foundData = true
                break
            } else {
                try handle.seek(toOffset: try handle.offset() + UInt64(max(0, size)))
            }
        }
        guard foundData else {
            throw invalid("Chunk 'data' manquant dans le fichier audio de démonstration")
        }
        return info
    }

    private static func leShort(_ b: [UInt8], _ off: Int) -> UInt16 {
        UInt16(b[off]) | (UInt16(b[off + 1]) << 8)
    }

    private static func leInt(_ b: [UInt8], _ off: Int) -> UInt32 {
        UInt32(b[off]) | (UInt32(b[off + 1]) << 8) | (UInt32(b[off + 2]) << 16) | (UInt32(b[off + 3]) << 24)
    }
}
