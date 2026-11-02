import DebunkaxCore
import DebunkaxData
import Foundation
import Observation
import Synchronization

/// Composition root (manual dependency wiring), port of `AppContainer.kt`. Debug-only switches ("Mode démo",
/// "Démo audio réelle") and the developer key exist only in DEBUG builds.
@MainActor @Observable
final class AppContainer {
    let transport: any HTTPTransport
    let settings: any SettingsRepository
    let keyVault: any KeyVault
    let history: any HistoryRepository
    let openRouterAuth: OpenRouterAuth
    let sherpaModel: SherpaModelManager
    /// TF-IDF + logistic regression Claim filter (spikes/claims/RESULTS.md), loaded once from its bundled resource.
    let claimDetector: any ClaimDetector

    /// The microphone, shared by speech-to-text and speaker recognition (ADR 0003).
    private let mic: TappedAudioSource
    /// Apple's SpeechAnalyzer until it reports a device unsupported, then sherpa-onnx for the rest of the process.
    private let micSpeechEngine: any SpeechEngine
    /// Loaded on first use and kept for the process: the model takes a moment to load.
    private let identifierCache = SpeakerIdentifierCache()
    @ObservationIgnored private var launched = false

    #if DEBUG
    /// Replays real debate transcripts instead of the microphone; debug builds only.
    @ObservationIgnored private(set) var demoMode = false

    /// Replays `Documents/demo.wav` at real-time pace instead of the microphone, through SpeechAnalyzer, or
    /// sherpa-onnx where it is unsupported (simulators). Debug builds only; mutually exclusive with `demoMode`.
    @ObservationIgnored private(set) var realAudioDemoMode = false

    func setDemoMode(_ enabled: Bool) {
        demoMode = enabled
        if enabled { realAudioDemoMode = false }
    }

    func setRealAudioDemoMode(_ enabled: Bool) {
        realAudioDemoMode = enabled
        if enabled { demoMode = false }
    }
    #endif

    init() {
        let directory = (try? DebunkaxStorage.directory())
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("Debunkax", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        transport = URLSessionTransport()
        settings = FileSettingsRepository(
            fileURL: (try? FileSettingsRepository.defaultLocation()) ?? directory.appendingPathComponent("settings.json"))
        keyVault = KeychainKeyVault()
        do {
            history = try SQLiteHistoryRepository(
                fileURL: (try? SQLiteHistoryRepository.defaultLocation()) ?? directory.appendingPathComponent("history.sqlite"))
        } catch {
            fatalError("Impossible d'ouvrir l'historique : \(error.localizedDescription)")
        }
        openRouterAuth = OpenRouterAuth()
        let sherpa = (try? SherpaModelManager.standard()) ?? SherpaModelManager(baseDirectory: directory)
        sherpaModel = sherpa

        guard let modelURL = Bundle.main.url(forResource: "claim_model", withExtension: "json"),
            let detector = try? TfidfClaimDetector(contentsOf: modelURL)
        else { fatalError("claim_model.json absent ou invalide") }
        claimDetector = detector

        let mic = TappedAudioSource(MicAudioSource())
        self.mic = mic
        micSpeechEngine = FallbackSpeechEngine(
            primary: SpeechAnalyzerEngine(audio: mic), fallback: SherpaSpeechEngine(model: sherpa, audio: mic))
    }

    // MARK: - Use cases

    var pendingChecker: PendingClaimChecker { PendingClaimChecker(history: history) }
    var explanations: ExplanationLoader { ExplanationLoader(history: history) }

    /// The speech engine, and the audio it listens to when speaker recognition can hear it too.
    private func speechEngine() -> (engine: any SpeechEngine, audio: TappedAudioSource?) {
        #if DEBUG
        if realAudioDemoMode {
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let demo = TappedAudioSource(FileAudioSource(url: documents.appendingPathComponent("demo.wav")))
            let engine = FallbackSpeechEngine(
                primary: SpeechAnalyzerEngine(audio: demo), fallback: SherpaSpeechEngine(model: sherpaModel, audio: demo))
            return (engine, demo)
        }
        if demoMode {
            return (ScriptedSpeechEngine(lines: Array(repeating: DemoScript.lines, count: 50).flatMap { $0 }, interval: 6), nil)
        }
        #endif
        return (micSpeechEngine, mic)
    }

    /// The current settings (the first value of the settings stream).
    func currentSettings() async -> Settings {
        for await value in settings.settings { return value }
        return Settings(provider: nil, modelId: nil, checkLimit: nil)
    }

    /// The Check client for the current settings and stored key; nil when no provider is usable.
    func checkClient() async -> (any CheckClient)? {
        let current = await currentSettings()
        if let client = try? await CheckClientFactory.create(settings: current, keyVault: keyVault, transport: transport) {
            return client
        }
        #if DEBUG
        if demoMode || realAudioDemoMode { return DemoCheckClient() }
        #endif
        return nil
    }

    /// A fresh Listening Session for the current settings, speech engine and provider.
    func newSession() async -> ListeningSession {
        let (speech, audio) = speechEngine()
        let speakers: SpeakerTracker? = audio.map { audio in
            let cache = identifierCache
            return SpeakerTracker(
                setTap: { tap in audio.setTap(tap) },
                identifier: { cache.identifier() })
        }
        let client = await checkClient()
        let limit = (await currentSettings()).checkLimit
        return ListeningSession(
            speech: speech, detector: claimDetector, history: history, checkClient: client, checkLimit: limit,
            speakers: speakers)
    }

    /// The speaker identifier shared with the "Qui parle ?" test screen; nil when this build has no voice prints.
    nonisolated func speakerIdentifier() -> SpeakerIdentifier? { identifierCache.identifier() }

    /// Called once at launch: ends the Listening Sessions an earlier launch never stopped (app killed).
    func launch() async {
        if launched { return }
        launched = true
        let startedAt = Date()
        try? await history.closeUnfinishedSessions(before: startedAt)
        #if DEBUG
        await seedDeveloperKey()
        #endif
    }

    #if DEBUG
    /// Debug builds only: stores the developer key (`OPENROUTER_DEBUG_KEY` in Info.plist, from the untracked
    /// `Debug.local.xcconfig`) when none is saved yet. Always empty in Release.
    private func seedDeveloperKey() async {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "OPENROUTER_DEBUG_KEY") as? String else { return }
        let key = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // An undefined build setting would leave the literal "$(…)" in the plist.
        guard !key.isEmpty, !key.hasPrefix("$(") else { return }
        if (try? await keyVault.get(.openRouter)) == nil { try? await keyVault.put(.openRouter, key: key) }
        if await currentSettings().provider == nil {
            try? await settings.setProvider(.openRouter, modelId: Models.default.id)
        }
    }
    #endif
}

/// Loads the speaker identifier once, on first use, off the main thread (the caller is the tracker's worker).
final class SpeakerIdentifierCache: Sendable {
    private struct Slot {
        var loaded = false
        var identifier: SpeakerIdentifier?
    }

    private let slot = Mutex<Slot>(Slot())

    func identifier() -> SpeakerIdentifier? {
        slot.withLock { slot in
            if !slot.loaded {
                slot.identifier = SpeakerIdentifier.loadIfAvailable()
                slot.loaded = true
            }
            return slot.identifier
        }
    }
}

#if DEBUG
/// Excerpts of the sherpa-onnx transcripts in spikes/stt/RESULTS.md: uppercase, unpunctuated, as real engines deliver them.
private enum DemoScript {
    static let lines = [
        "MERCI MADAME LA PRÉSIDENTE MONSIEUR LE DÉPUTÉ",
        "LES OPÉRATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTÉ VINGT QUATRE POURCENT ENTRE DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS L'ENSEMBLE DES ALPES MARITIMES",
        "VOUS DEVRIEZ AVOIR HONTE",
        "LES MINEURS NON ACCOMPAGNÉS IL Y EN A AUJOURD'HUI DEUX MILLE",
        "QUI CHACUN COÛTE QUARANTE MILLE EUROS",
        "JE PENSE QU'IL FAUT RENFORCER LA POLICE",
        "NICE EST LA VILLE DE FRANCE DANS LAQUELLE IL Y A LE PLUS DE CAMÉRAS DE VIDÉOSURVEILLANCE PAR HABITANT",
        "LES OPÉRATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTÉ DE VINGT QUATRE POURCENT ENTRE DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS LES ALPES MARITIMES",
    ]
}

/// Debug-only stand-in when no provider is configured: fictitious, clearly labelled Verdicts to exercise the UI.
private final class DemoCheckClient: CheckClient {
    private let next = Mutex<Int>(0)
    private let verdicts: [Verdict] = [.false, .accurate, .misleading, .lacksContext, .unverifiable]
    private let source = [Source(title: "INSEE (démo)", url: "https://www.insee.fr")]

    func check(_ claim: HeardClaim) async throws -> CheckOutcome {
        try await Task.sleep(for: .seconds(2))
        let index = next.withLock { value -> Int in
            defer { value += 1 }
            return value
        }
        let verdict = verdicts[index % verdicts.count]
        return .checked(
            CheckResult(verdict: verdict, context: "[Démo] Verdict fictif, aucune vérification réelle.", sources: source))
    }

    func explain(claimText: String, result: CheckResult) async throws -> Explanation {
        try await Task.sleep(for: .seconds(2))
        return Explanation(
            text: "[Démo] Explication fictive générée sans fournisseur, pour tester l'interface.", sources: source)
    }
}
#endif
