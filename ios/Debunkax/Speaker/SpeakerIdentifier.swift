import DebunkaxCore
import Foundation

/// Tells which enrolled leader is speaking in a few seconds of 16 kHz audio (ADR 0003), through sherpa-onnx's
/// speaker embedding extractor and the bundled `titanet_small.onnx`, matched against the bundled `speakers/prints.json`
/// (both copied from the Android assets by spikes/speakers/export_prints.py). Audio stays in memory.
final class SpeakerIdentifier: SpeakerEmbeddingIdentifier, @unchecked Sendable {
    let speakers: [EnrolledSpeaker]
    private let extractor: SherpaOnnxSpeakerEmbeddingExtractorWrapper
    /// The extractor is not documented as thread-safe; one window is embedded at a time.
    private let lock = NSLock()

    private static let directory = "speakers"
    private static let printsName = "prints"

    private init(extractor: SherpaOnnxSpeakerEmbeddingExtractorWrapper, speakers: [EnrolledSpeaker]) {
        self.extractor = extractor
        self.speakers = speakers
    }

    func identify(_ samples: [Float], threshold: Float) -> SpeakerGuess {
        // The model scores quiet audio lower, and a TV across the room is quiet: bring the window to the enrolment level.
        guard !samples.isEmpty, let normalized = SpeakerAudio.normalizedForIdentification(samples) else {
            return .silence
        }
        let embedding: [Float] = lock.withLock {
            let stream = extractor.createStream()
            stream.acceptWaveform(samples: normalized, sampleRate: SpeakerAudio.sampleRate)
            stream.inputFinished()
            return extractor.compute(stream: stream)
        }
        guard !embedding.isEmpty else { return .unknown(closest: "-", score: -1) }
        return SpeakerMatcher.guess(embedding: embedding, speakers: speakers, threshold: threshold)
    }

    // MARK: - Loading

    /// Whether this build bundles the voice prints (the model file is checked by `load`).
    static func available(bundle: Bundle = .main) -> Bool {
        printsURL(bundle) != nil
    }

    /// The identifier, or nil when the prints or the model are missing or invalid. Loads the model: call off the main thread.
    static func loadIfAvailable(bundle: Bundle = .main) -> SpeakerIdentifier? {
        try? load(bundle: bundle)
    }

    struct LoadError: Error {
        let detail: String
    }

    static func load(bundle: Bundle = .main) throws -> SpeakerIdentifier {
        guard let printsURL = printsURL(bundle) else { throw LoadError(detail: "prints.json absent") }
        let (modelName, speakers) = try parsePrints(Data(contentsOf: printsURL))
        guard let modelURL = resource(bundle, name: (modelName as NSString).deletingPathExtension,
                                      ext: (modelName as NSString).pathExtension)
        else { throw LoadError(detail: "modèle de voix absent") }

        let extractor: SherpaOnnxSpeakerEmbeddingExtractorWrapper = autoreleasepool {
            var config = sherpaOnnxSpeakerEmbeddingExtractorConfig(model: modelURL.path, numThreads: 2)
            return SherpaOnnxSpeakerEmbeddingExtractorWrapper(config: &config)
        }
        guard extractor.impl != nil else { throw LoadError(detail: "modèle de voix illisible") }
        return SpeakerIdentifier(extractor: extractor, speakers: speakers)
    }

    /// `{"model": "titanet_small.onnx", "people": [{"name", "role"?, "prints": [[Float]]}]}`; prints are normalised.
    static func parsePrints(_ data: Data) throws -> (model: String, speakers: [EnrolledSpeaker]) {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let model = root["model"] as? String,
            let people = root["people"] as? [[String: Any]]
        else { throw LoadError(detail: "prints.json invalide") }
        let speakers: [EnrolledSpeaker] = try people.map { person in
            guard let name = person["name"] as? String, let rawPrints = person["prints"] as? [[Any]] else {
                throw LoadError(detail: "prints.json invalide")
            }
            let prints = rawPrints.map { vector in
                SpeakerMatcher.normalized(vector.map { ($0 as? NSNumber)?.floatValue ?? 0 })
            }
            let role: SpeakerRole = (person["role"] as? String) == "journalist" ? .journalist : .politician
            return EnrolledSpeaker(name: name, prints: prints, role: role)
        }
        return (model, speakers)
    }

    // The resources may be copied as a `speakers` folder or flattened into the bundle root, depending on project.yml.
    private static func printsURL(_ bundle: Bundle) -> URL? {
        resource(bundle, name: printsName, ext: "json")
    }

    private static func resource(_ bundle: Bundle, name: String, ext: String) -> URL? {
        bundle.url(forResource: name, withExtension: ext, subdirectory: directory)
            ?? bundle.url(forResource: name, withExtension: ext)
    }
}
