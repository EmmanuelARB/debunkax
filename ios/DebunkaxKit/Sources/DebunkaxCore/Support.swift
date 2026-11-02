import Foundation

/// A compiled regular expression (ICU, so lookbehinds and \p{…} work), shared across threads.
struct Rx: @unchecked Sendable {
    let regex: NSRegularExpression

    init(_ pattern: String, caseInsensitive: Bool = false) {
        // The patterns are constants: a failure here is a programming error.
        regex = try! NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }

    func replacing(in text: String, with template: String) -> String {
        regex.stringByReplacingMatches(
            in: text, options: [], range: NSRange(location: 0, length: text.utf16.count), withTemplate: template)
    }

    func contains(in text: String) -> Bool {
        regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: text.utf16.count)) != nil
    }
}

extension Date {
    /// Whole milliseconds since 1970, so comparisons behave like the exact instants of the Kotlin code.
    var epochMillis: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
}

extension String {
    /// Kotlin's `isBlank`: empty or only whitespace.
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
