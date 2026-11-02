import DebunkaxCore
import Foundation

/// `SettingsRepository` stored as a small JSON file (provider, model, Check limit). No stored limit
/// means unlimited Checks. The file lives in the backup-excluded app directory.
public final class FileSettingsRepository: SettingsRepository, @unchecked Sendable {
    private struct Stored: Codable {
        var provider: String?
        var modelId: String?
        var checkLimit: Int?
    }

    private let fileURL: URL
    private let lock = NSLock()
    private var stored: Stored
    private var observers: [UUID: AsyncStream<Settings>.Continuation] = [:]

    /// Reads `fileURL` if it exists; a missing or unreadable file means no settings yet.
    public init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
            let decoded = try? JSONDecoder().decode(Stored.self, from: data)
        {
            stored = decoded
        } else {
            stored = Stored()
        }
    }

    /// `Application Support/Debunkax/settings.json` (the directory is excluded from backups).
    public static func defaultLocation() throws -> URL {
        try DebunkaxStorage.directory().appendingPathComponent("settings.json")
    }

    private static func settings(_ s: Stored) -> Settings {
        Settings(
            provider: s.provider.flatMap { Provider(rawValue: $0) }, modelId: s.modelId, checkLimit: s.checkLimit)
    }

    public var settings: AsyncStream<Settings> {
        AsyncStream(Settings.self, bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID()
            lock.lock()
            observers[id] = continuation
            continuation.yield(Self.settings(stored))
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.observers[id] = nil
                self.lock.unlock()
            }
        }
    }

    public func setProvider(_ provider: Provider, modelId: String) async throws {
        try edit {
            $0.provider = provider.rawValue
            $0.modelId = modelId
        }
    }

    public func clearProvider() async throws {
        try edit {
            $0.provider = nil
            $0.modelId = nil
        }
    }

    public func setCheckLimit(_ limit: Int?) async throws {
        try edit { $0.checkLimit = limit }
    }

    private func edit(_ change: (inout Stored) -> Void) throws {
        lock.lock()
        defer { lock.unlock() }
        var next = stored
        change(&next)
        let data = try JSONEncoder().encode(next)
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: fileURL, options: .atomic)
        stored = next
        let value = Self.settings(next)
        for continuation in observers.values { continuation.yield(value) }
    }
}
