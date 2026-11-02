import Foundation

public struct TranscriptSegment: Hashable, Sendable {
    public var text: String
    public var isFinal: Bool
    public var at: Date

    public init(text: String, isFinal: Bool, at: Date) {
        self.text = text
        self.isFinal = isFinal
        self.at = at
    }
}

public enum SpeechFailure: Error, LocalizedError, Hashable, Sendable {
    case unsupported(String)
    case modelDownloading
    case engine(String)

    public var message: String {
        switch self {
        case .unsupported(let detail): detail
        case .modelDownloading: "Téléchargement du modèle de reconnaissance vocale en cours"
        case .engine(let detail): detail
        }
    }

    public var errorDescription: String? { message }
}

/// Streams French speech from the microphone. Iterating starts listening; cancelling the iteration stops it. Audio is never kept.
/// Emits an empty partial segment once actually listening, so callers can clear model-download notices.
public protocol SpeechEngine: Sendable {
    func transcribe() -> AsyncThrowingStream<TranscriptSegment, Error>
}

/// Splits a final segment into sentences, so each one is filtered and checked on its own. Apple's and ML Kit's
/// engines punctuate and can finalise a minute of speech at once; sherpa-onnx doesn't punctuate, so its segments stay whole.
public enum Sentences {
    private static let boundary = Rx(#"(?<=[.!?…])[ \t\n\x0B\f\r]+"#)

    public static func split(_ text: String) -> [String] {
        let ns = text as NSString
        var pieces: [String] = []
        var last = 0
        for match in boundary.regex.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            pieces.append(ns.substring(with: NSRange(location: last, length: match.range.location - last)))
            last = match.range.location + match.range.length
        }
        pieces.append(ns.substring(from: last))
        return pieces
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
