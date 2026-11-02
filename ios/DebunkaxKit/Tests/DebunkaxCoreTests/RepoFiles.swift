import Foundation

/// Locates files of the Android project (shared assets and fixtures) by walking up from this source file.
enum RepoFiles {
    static func url(_ relativePath: String, file: String = #filePath) throws -> URL {
        var dir = URL(fileURLWithPath: file).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent(relativePath)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw NSError(domain: "RepoFiles", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(relativePath) not found above \(file)"])
    }
}

/// 2000-01-01T00:00:00Z, the instant the Kotlin tests start from.
let testStart = Date(timeIntervalSince1970: 946_684_800)
