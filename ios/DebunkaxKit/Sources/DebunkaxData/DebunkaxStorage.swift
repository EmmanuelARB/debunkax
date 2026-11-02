import Foundation

/// Where the app keeps its small on-device files: `Application Support/Debunkax`, excluded from
/// backups (the History, the settings and the speech model never leave the phone).
public enum DebunkaxStorage {
    /// The directory, created if needed and marked `isExcludedFromBackup`.
    public static func directory() throws -> URL {
        let fm = FileManager.default
        let base = try fm.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var dir = base.appendingPathComponent("Debunkax", isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try dir.setResourceValues(values)
        return dir
    }
}
