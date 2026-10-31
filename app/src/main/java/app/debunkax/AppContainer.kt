package app.debunkax

import android.app.Application
import android.content.Context
import app.debunkax.check.CheckClientFactory
import app.debunkax.check.Models
import app.debunkax.domain.Provider
import app.debunkax.domain.CheckClient
import app.debunkax.domain.CheckOutcome
import app.debunkax.domain.CheckResult
import app.debunkax.domain.Explanation
import app.debunkax.domain.HeardClaim
import app.debunkax.domain.Source
import app.debunkax.domain.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.debunkax.domain.HistoryRepository
import app.debunkax.domain.KeyVault
import app.debunkax.domain.SettingsRepository
import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.TfidfClaimDetector
import app.debunkax.history.DebunkaxDatabase
import app.debunkax.history.RoomHistoryRepository
import app.debunkax.keys.OpenRouterAuth
import app.debunkax.keys.TinkKeyVault
import app.debunkax.session.ExplanationLoader
import app.debunkax.session.ListeningSession
import app.debunkax.session.ListeningSource
import app.debunkax.session.PendingClaimChecker
import app.debunkax.settings.DataStoreSettingsRepository
import app.debunkax.speech.FallbackSpeechEngine
import app.debunkax.speech.FileAudioSource
import app.debunkax.speech.MicAudioSource
import app.debunkax.speech.PlaybackCaptureAudioSource
import app.debunkax.speech.TappedAudioSource
import app.debunkax.speaker.SpeakerIdentifier
import app.debunkax.speaker.SpeakerTracker
import app.debunkax.speech.MlKitSpeechEngine
import app.debunkax.speech.ScriptedSpeechEngine
import app.debunkax.speech.SherpaModelManager
import app.debunkax.speech.SherpaSpeechEngine
import app.debunkax.update.UpdateChecker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.time.Duration

class DebunkaxApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val startedAt = java.time.Instant.now()
        container.appScope.launch { container.history.closeUnfinishedSessions(before = startedAt) }
        if (BuildConfig.DEBUG) container.appScope.launch { container.seedDeveloperKeys() }
    }
}

val Context.container: AppContainer get() = (applicationContext as DebunkaxApplication).container

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob())
    val appContext: Context get() = context
    val http = OkHttpClient()
    val settings: SettingsRepository = DataStoreSettingsRepository(context)
    val keyVault: KeyVault = TinkKeyVault(context)
    val openRouterAuth = OpenRouterAuth(context, http)
    val updateChecker = UpdateChecker(http)

    private val realHistory by lazy { RoomHistoryRepository(DebunkaxDatabase.build(context).historyDao()) }
    private val sherpaModel by lazy { SherpaModelManager(context, http, appScope) }

    /** TF-IDF + logistic regression Claim filter (see spikes/claims/RESULTS.md); loaded once from its bundled asset. */
    private val claimDetector by lazy {
        val json = context.assets.open("claim_model.json").bufferedReader().use { it.readText() }
        TfidfClaimDetector.fromJson(json)
    }

    /** The microphone, shared by speech-to-text and speaker recognition (ADR 0003). */
    private val mic by lazy { TappedAudioSource(MicAudioSource()) }

    /** ML Kit until it reports a device unsupported, then sherpa-onnx for the rest of the process. */
    private val fallbackSpeechEngine by lazy {
        FallbackSpeechEngine(MlKitSpeechEngine(appScope, mic), SherpaSpeechEngine(sherpaModel, mic))
    }

    /** Loaded on first use and kept for the process: the model takes a moment to load. */
    private val speakerIdentifier by lazy {
        if (SpeakerIdentifier.available(context.assets)) runCatching { SpeakerIdentifier.load(context.assets) }.getOrNull() else null
    }

    /** Replays real debate transcripts instead of the microphone; debug builds only, for emulators. */
    var demoMode: Boolean = false
        set(value) {
            field = value
            if (value) realAudioDemoMode = false
        }

    /**
     * Replays `demo.wav` (a real recording the developer pushes into the app's files dir) at
     * real-time pace instead of the microphone, through ML Kit, or sherpa-onnx where ML Kit is
     * unsupported (emulators). Debug builds only; mutually exclusive with [demoMode].
     */
    var realAudioDemoMode: Boolean = false
        set(value) {
            field = value
            if (value) demoMode = false
        }

    /** Test-only injection point for instrumented UI tests; ignored outside debug builds. */
    var testOverrides: TestOverrides? = null

    val history: HistoryRepository
        get() = (if (BuildConfig.DEBUG) testOverrides?.history else null) ?: realHistory

    val pendingChecker: PendingClaimChecker get() = PendingClaimChecker(history)
    val explanations: ExplanationLoader get() = ExplanationLoader(history)

    /** The speech engine, and the audio it listens to when speaker recognition can hear it too. */
    private fun speechEngine(source: ListeningSource): Pair<SpeechEngine, TappedAudioSource?> = when {
        BuildConfig.DEBUG && testOverrides?.speechEngine != null -> testOverrides!!.speechEngine!! to null
        BuildConfig.DEBUG && realAudioDemoMode -> TappedAudioSource(FileAudioSource(File(context.filesDir, "demo.wav"))).let { demo ->
            FallbackSpeechEngine(MlKitSpeechEngine(appScope, demo), SherpaSpeechEngine(sherpaModel, demo)) to demo
        }
        BuildConfig.DEBUG && demoMode ->
            ScriptedSpeechEngine(List(50) { DemoScript.lines }.flatten(), Duration.ofSeconds(6)) to null
        source is ListeningSource.AppPlayback -> {
            // The same engine chain as the microphone, fed with the apps' audio: the capture
            // produces the identical 16 kHz mono stream, so nothing downstream changes.
            val audio = TappedAudioSource(PlaybackCaptureAudioSource(source.projection))
            FallbackSpeechEngine(MlKitSpeechEngine(appScope, audio), SherpaSpeechEngine(sherpaModel, audio)) to audio
        }
        else -> fallbackSpeechEngine to mic
    }

    suspend fun checkClient(): CheckClient? {
        if (BuildConfig.DEBUG) testOverrides?.checkClient?.let { return it.client }
        return CheckClientFactory.create(settings.settings.first(), keyVault, http)
            ?: if (BuildConfig.DEBUG && (demoMode || realAudioDemoMode)) DemoCheckClient() else null
    }

    /** Debug builds only: stores the developer keys from local.properties when none is saved yet. */
    suspend fun seedDeveloperKeys() {
        if (!BuildConfig.DEBUG) return
        val seeds = listOf(
            Provider.OPENROUTER to BuildConfig.DEV_OPENROUTER_KEY,
        ).filter { it.second.isNotBlank() }
        for ((provider, key) in seeds) if (keyVault.get(provider) == null) keyVault.put(provider, key)
        if (settings.settings.first().provider == null) {
            seeds.firstOrNull()?.let { (provider, _) -> settings.setProvider(provider, Models.all.first { it.provider == provider }.id) }
        }
    }

    suspend fun newSession(scope: CoroutineScope, source: ListeningSource = ListeningSource.Microphone): ListeningSession {
        val (speech, audio) = speechEngine(source)
        return ListeningSession(
            speech = speech,
            speakers = audio?.let { SpeakerTracker(it, { speakerIdentifier }, scope) },
            detector = claimDetector,
            history = history,
            checkClient = checkClient(),
            checkLimit = settings.settings.first().checkLimit,
            scope = scope,
        )
    }
}

/** Test-only overrides read by [AppContainer.testOverrides] in debug builds only. */
data class TestOverrides(
    val history: HistoryRepository? = null,
    val speechEngine: SpeechEngine? = null,
    val checkClient: CheckClientOverride? = null,
)

/** Wraps the [CheckClient] override so tests can distinguish "no provider" (null client) from "not overridden". */
class CheckClientOverride(val client: CheckClient?)

/** Excerpts of the sherpa-onnx transcripts in spikes/stt/RESULTS.md: uppercase, unpunctuated, as real engines deliver them. */
private object DemoScript {
    val lines = listOf(
        "MERCI MADAME LA PRÉSIDENTE MONSIEUR LE DÉPUTÉ",
        "LES OPÉRATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTÉ VINGT QUATRE POURCENT ENTRE DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS L'ENSEMBLE DES ALPES MARITIMES",
        "VOUS DEVRIEZ AVOIR HONTE",
        "LES MINEURS NON ACCOMPAGNÉS IL Y EN A AUJOURD'HUI DEUX MILLE",
        "QUI CHACUN COÛTE QUARANTE MILLE EUROS",
        "JE PENSE QU'IL FAUT RENFORCER LA POLICE",
        "NICE EST LA VILLE DE FRANCE DANS LAQUELLE IL Y A LE PLUS DE CAMÉRAS DE VIDÉOSURVEILLANCE PAR HABITANT",
        "LES OPÉRATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTÉ DE VINGT QUATRE POURCENT ENTRE DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS LES ALPES MARITIMES",
    )
}

/** Debug-only stand-in when no provider is configured: fictitious, clearly labelled Verdicts to exercise the UI. */
private class DemoCheckClient : CheckClient {
    private var next = 0
    private val verdicts = listOf(Verdict.FALSE, Verdict.ACCURATE, Verdict.MISLEADING, Verdict.LACKS_CONTEXT, Verdict.UNVERIFIABLE)
    private val source = listOf(Source("INSEE (démo)", "https://www.insee.fr"))

    override suspend fun check(claim: HeardClaim): CheckOutcome {
        delay(2_000)
        val verdict = verdicts[next++ % verdicts.size]
        return CheckOutcome.Checked(CheckResult(verdict, "[Démo] Verdict fictif, aucune vérification réelle.", source))
    }

    override suspend fun explain(claimText: String, result: CheckResult): Explanation {
        delay(2_000)
        return Explanation("[Démo] Explication fictive générée sans fournisseur, pour tester l'interface.", source)
    }
}
