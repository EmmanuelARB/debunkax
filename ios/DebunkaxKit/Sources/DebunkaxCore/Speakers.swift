import Foundation

/// Recognises Speakers by voice during a Listening Session. Keeps names in memory only, never audio.
public protocol SpeakerRecognition: Sendable {
    func start()
    func stop()

    /// The Speaker heard between `from` and `to`, or nil when unknown or in doubt.
    func speakerBetween(from: Date, to: Date) -> String?

    /// The last politician other than `claimant` heard in the transcript window before `before`, or
    /// nil. Journalists are skipped: they ask questions, they are rarely the person a « vous » is aimed at.
    func addresseeBefore(_ before: Date, claimant: String) -> String?
}

extension SpeakerRecognition {
    public func addresseeBefore(_ before: Date, claimant: String) -> String? { nil }
}

/// Whether a sentence speaks to someone: « tu », « vous » and their possessives and object forms.
public enum SecondPerson {
    private static let words = Rx(
        #"(?<![\p{L}\p{N}])(?:tu|te|toi|ton|ta|tes|vous|votre|vos|t(?=['’]))(?![\p{L}\p{N}])"#,
        caseInsensitive: true)

    public static func isIn(_ sentence: String) -> Bool { words.contains(in: sentence) }
}

/// When each sentence of a final segment was probably said. Engines only time the segment's end, and
/// ML Kit can finalise a minute of speech at once, so each sentence is placed back by its length.
public enum SentenceTiming {
    /// French TV speech runs at about 15 characters a second.
    private static let charsPerSecond = 15.0
    private static let minMillis: Int64 = 2000

    public static func spans(_ sentences: [String], endAt: Date) -> [(start: Date, end: Date)] {
        var end = endAt
        var spans: [(start: Date, end: Date)] = []
        for sentence in sentences.reversed() {
            let millis = max(minMillis, Int64(Double(sentence.utf16.count) / charsPerSecond * 1000))
            let start = end.addingTimeInterval(-Double(millis) / 1000)
            spans.append((start: start, end: end))
            end = start
        }
        return spans.reversed()
    }
}
