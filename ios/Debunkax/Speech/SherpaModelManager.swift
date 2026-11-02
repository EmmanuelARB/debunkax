import CryptoKit
import DebunkaxData
import Foundation
import Synchronization

/// One file expected in the extracted model directory, with the checksum it must match.
private struct ModelFile {
    let name: String
    let sha256: String
    let size: Int64
}

/// The sherpa-onnx `sherpa-onnx-streaming-zipformer-fr-2023-04-14` model: int8 encoder and joiner, fp32 decoder,
/// tokens (the same combination measured in spikes/stt/RESULTS.md, and the same files as on Android). It is not
/// bundled in the app; `SherpaModelManager` downloads it from the official k2-fsa/sherpa-onnx GitHub release on
/// first use. That download is a plain GET for a public file and sends no user data.
private enum SherpaModelFiles {
    static let releaseURL = URL(
        string: "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
            + "sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2")!
    static let archiveRoot = "sherpa-onnx-streaming-zipformer-fr-2023-04-14"

    static let encoder = ModelFile(
        name: "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
        sha256: "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e",
        size: 126_655_903)
    static let joiner = ModelFile(
        name: "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
        sha256: "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711",
        size: 259_572)
    static let decoder = ModelFile(
        name: "decoder-epoch-29-avg-9-with-averaged-model.onnx",
        sha256: "3ef2840b684440e26382af87b2174d25732a9f113f1965cd8abba7ffc5d033fb",
        size: 2_092_272)
    static let tokens = ModelFile(
        name: "tokens.txt",
        sha256: "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31",
        size: 4_819)
    static let all = [encoder, joiner, decoder, tokens]

    static func archiveEntryName(_ file: ModelFile) -> String { "\(archiveRoot)/\(file.name)" }
}

/// Local, absolute paths to the four model files sherpa-onnx's online recognizer needs.
struct SherpaModelPaths: Sendable {
    let encoder: String
    let joiner: String
    let decoder: String
    let tokens: String
}

enum ModelDownloadProgress: Hashable, Sendable {
    case idle
    case downloading(bytesRead: Int64, totalBytes: Int64)
    case extracting
    case ready
    case failed(String)
}

/// Downloads, verifies and exposes the sherpa-onnx French model in `Application Support/Debunkax/sherpa-model`
/// (excluded from backup). One download attempt runs to completion on a task of its own, whether or not anyone
/// observes it. Never touches audio or transcripts: this only ever moves model weights.
final class SherpaModelManager: Sendable {
    private struct State {
        var progress = ModelDownloadProgress.idle
        var running = false
        var observers: [UUID: AsyncStream<ModelDownloadProgress>.Continuation] = [:]
    }

    private let modelDir: URL
    private let archiveFile: URL
    private let state = Mutex<State>(State())

    /// The standard location inside `DebunkaxStorage.directory()`.
    static func standard() throws -> SherpaModelManager {
        try SherpaModelManager(baseDirectory: DebunkaxStorage.directory())
    }

    init(baseDirectory: URL) {
        modelDir = baseDirectory.appendingPathComponent("sherpa-model", isDirectory: true)
        archiveFile = baseDirectory.appendingPathComponent("sherpa-model-download.tar.bz2")
    }

    /// The latest progress. For updates, iterate `progressUpdates()`.
    var progress: ModelDownloadProgress { state.withLock { $0.progress } }

    /// Yields the current progress, then every change, until the iteration is cancelled.
    func progressUpdates() -> AsyncStream<ModelDownloadProgress> {
        let (stream, continuation) = AsyncStream<ModelDownloadProgress>.makeStream(of: ModelDownloadProgress.self, bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        state.withLock { s in
            s.observers[id] = continuation
            continuation.yield(s.progress)
        }
        continuation.onTermination = { [weak self] _ in
            self?.state.withLock { _ = $0.observers.removeValue(forKey: id) }
        }
        return stream
    }

    /// Cheap check (file existence and size only), so it is safe to call before every session.
    func isReady() -> Bool {
        SherpaModelFiles.all.allSatisfy { Self.matchesSize(modelDir.appendingPathComponent($0.name), $0) }
    }

    func paths() -> SherpaModelPaths {
        SherpaModelPaths(
            encoder: modelDir.appendingPathComponent(SherpaModelFiles.encoder.name).path,
            joiner: modelDir.appendingPathComponent(SherpaModelFiles.joiner.name).path,
            decoder: modelDir.appendingPathComponent(SherpaModelFiles.decoder.name).path,
            tokens: modelDir.appendingPathComponent(SherpaModelFiles.tokens.name).path)
    }

    func startDownloadIfNeeded() {
        let start = state.withLock { s -> Bool in
            if s.running { return false }
            s.running = true
            return true
        }
        guard start else { return }
        Task.detached(priority: .utility) { [self] in
            do {
                try await downloadAndExtract()
                publish(.ready)
            } catch is CancellationError {
                publish(.idle)
            } catch let failure as DownloadFailure {
                publish(.failed(failure.detail))
            } catch let failure as TarBz2.Failure {
                // A bad archive is not resumable: start over next time.
                try? FileManager.default.removeItem(at: archiveFile)
                publish(.failed(failure.detail))
            } catch {
                // The next isReady()/startDownloadIfNeeded() call (from the session's retry loop) tries again.
                publish(.failed("Échec du téléchargement du modèle"))
            }
            state.withLock { $0.running = false }
        }
    }

    private func publish(_ progress: ModelDownloadProgress) {
        state.withLock { s in
            s.progress = progress
            for continuation in s.observers.values { continuation.yield(progress) }
        }
    }

    private func downloadAndExtract() async throws {
        let fm = FileManager.default
        try fm.createDirectory(at: modelDir, withIntermediateDirectories: true)
        var dir = modelDir
        var flag = URLResourceValues()
        flag.isExcludedFromBackup = true
        try? dir.setResourceValues(flag)

        try await download()
        publish(.extracting)
        try extract()
        try? fm.removeItem(at: archiveFile)
        if !isReady() {
            throw DownloadFailure(detail: "Fichiers du modèle sherpa-onnx invalides après extraction")
        }
    }

    private func download() async throws {
        let resumeFrom = Self.fileSize(archiveFile) ?? 0
        let downloader = RangeDownloader(
            archive: archiveFile, resumeFrom: resumeFrom,
            onProgress: { [self] read, total in publish(.downloading(bytesRead: read, totalBytes: total)) })
        try await downloader.run(url: SherpaModelFiles.releaseURL)
        var archive = archiveFile
        var flag = URLResourceValues()
        flag.isExcludedFromBackup = true
        try? archive.setResourceValues(flag)
    }

    private func extract() throws {
        let wanted = Dictionary(uniqueKeysWithValues: SherpaModelFiles.all.map { (SherpaModelFiles.archiveEntryName($0), $0) })
        try TarBz2.extract(archive: archiveFile) { entry in
            // Some tars prefix entries with "./".
            let key = entry.hasPrefix("./") ? String(entry.dropFirst(2)) : entry
            guard let file = wanted[key] else { return nil }
            return self.modelDir.appendingPathComponent(file.name + ".part")
        }
        for file in SherpaModelFiles.all {
            let partial = modelDir.appendingPathComponent(file.name + ".part")
            let final = modelDir.appendingPathComponent(file.name)
            guard FileManager.default.fileExists(atPath: partial.path) else { continue }
            if Self.matchesSha256(partial, file) {
                try? FileManager.default.removeItem(at: final)
                try FileManager.default.moveItem(at: partial, to: final)
            } else {
                try? FileManager.default.removeItem(at: partial)
            }
        }
    }

    private static func fileSize(_ url: URL) -> Int64? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return nil }
        return (attributes[.size] as? NSNumber)?.int64Value
    }

    private static func matchesSize(_ url: URL, _ expected: ModelFile) -> Bool {
        fileSize(url) == expected.size
    }

    private static func matchesSha256(_ url: URL, _ expected: ModelFile) -> Bool {
        guard matchesSize(url, expected), let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try? handle.read(upToCount: 64 * 1024), !data.isEmpty {
            hasher.update(data: data)
        }
        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return hex == expected.sha256
    }
}

private struct DownloadFailure: Error {
    let detail: String
}

/// A GET of one file that appends to `archive`, resuming from `resumeFrom` bytes with a `Range` header. Uses a
/// delegate so 130 MB streams through in network-sized pieces instead of byte by byte.
private final class RangeDownloader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let archive: URL
    private let resumeFrom: Int64
    private let onProgress: @Sendable (Int64, Int64) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var session: URLSession?
    private var handle: FileHandle?
    private var failure: Error?
    private var alreadyComplete = false
    private var received: Int64 = 0
    private var total: Int64 = -1
    private var lastReported: Int64 = 0

    init(archive: URL, resumeFrom: Int64, onProgress: @escaping @Sendable (Int64, Int64) -> Void) {
        self.archive = archive
        self.resumeFrom = resumeFrom
        self.onProgress = onProgress
    }

    func run(url: URL) async throws {
        var request = URLRequest(url: url)
        if resumeFrom > 0 { request.setValue("bytes=\(resumeFrom)-", forHTTPHeaderField: "Range") }
        let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
        lock.withLock { self.session = session }
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: request)
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                lock.withLock { continuation = cont }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            fail(DownloadFailure(detail: "Téléchargement du modèle échoué"))
            completionHandler(.cancel)
            return
        }
        if http.statusCode == 416 && resumeFrom > 0 {
            // The partial file already holds the whole archive.
            lock.withLock { alreadyComplete = true }
            completionHandler(.cancel)
            return
        }
        guard (200..<300).contains(http.statusCode) else {
            fail(DownloadFailure(detail: "Téléchargement du modèle échoué (code \(http.statusCode))"))
            completionHandler(.cancel)
            return
        }
        let resuming = http.statusCode == 206 && resumeFrom > 0
        let already: Int64 = resuming ? resumeFrom : 0
        do {
            if !resuming {
                FileManager.default.createFile(atPath: archive.path, contents: nil)
            }
            let file = try FileHandle(forWritingTo: archive)
            if resuming { try file.seekToEnd() } else { try file.truncate(atOffset: 0) }
            lock.withLock {
                handle = file
                received = already
                lastReported = already
                total = http.expectedContentLength >= 0 ? http.expectedContentLength + already : -1
            }
            completionHandler(.allow)
        } catch {
            fail(DownloadFailure(detail: "Téléchargement du modèle échoué : écriture impossible"))
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let (file, read, size, report) = lock.withLock { () -> (FileHandle?, Int64, Int64, Bool) in
            received += Int64(data.count)
            let report = received - lastReported >= 256 * 1024
            if report { lastReported = received }
            return (handle, received, total, report)
        }
        do {
            try file?.write(contentsOf: data)
        } catch {
            fail(DownloadFailure(detail: "Téléchargement du modèle échoué : écriture impossible"))
            dataTask.cancel()
            return
        }
        if report { onProgress(read, size) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let (cont, file, stored, complete) = lock.withLock { () -> (CheckedContinuation<Void, Error>?, FileHandle?, Error?, Bool) in
            let snapshot = (continuation, handle, failure, alreadyComplete)
            continuation = nil
            handle = nil
            return snapshot
        }
        try? file?.close()
        if let stored {
            cont?.resume(throwing: stored)
        } else if complete {
            cont?.resume()
        } else if let error {
            if (error as? URLError)?.code == .cancelled { cont?.resume(throwing: CancellationError()) } else { cont?.resume(throwing: error) }
        } else {
            cont?.resume()
        }
    }

    private func fail(_ error: Error) {
        lock.withLock { if failure == nil { failure = error } }
    }
}
