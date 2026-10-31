package app.debunkax.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.TranscriptSegment
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource as MlKitAudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * [SpeechEngine] backed by ML Kit GenAI Speech Recognition, in French (fr-FR), basic mode,
 * listening on the device microphone, or on [audio] when set (debug "Démo audio réelle" only).
 *
 * Precondition: the caller (UI layer) must already hold the RECORD_AUDIO permission before
 * collecting [transcribe]; this class never requests it.
 *
 * Never logs or persists recognized text or audio: only structural signals (error codes,
 * download progress byte counts) ever leave [transcribe] as thrown [SpeechFailure]s.
 */
class MlKitSpeechEngine(
    private val appScope: CoroutineScope,
    private val audio: AudioSource? = null,
) : SpeechEngine {

    // App-scoped engine: one callback thread for the process lifetime.
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var downloadJob: Job? = null

    private fun newRecognizer(): SpeechRecognizer = SpeechRecognition.getClient(
        speechRecognizerOptions {
            locale = Locale.FRANCE
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
            executor = callbackExecutor
        }
    )

    override fun transcribe(): Flow<TranscriptSegment> = flow {
        val recognizer = newRecognizer()
        try {
            ensureModelReady(recognizer)
            emit(TranscriptSegment("", isFinal = false, at = Instant.now()))
            coroutineScope {
                // One real-time feed for the whole flow, so the file keeps playing across recognition sessions.
                val feed = audio?.let { source ->
                    Channel<ShortArray>(Channel.RENDEZVOUS).also { channel ->
                        launch { source.chunks().collect { channel.send(it) } }
                    }
                }
                // Keep listening continuously: a session ends on its own after a silence, a time
                // limit or an end of utterance; as long as the flow is still collected, start a
                // fresh session right away.
                while (currentCoroutineContext().isActive) {
                    runOneSession(recognizer, this, feed)
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { recognizer.stopRecognition() }
                recognizer.close()
            }
        }
    }

    private suspend fun ensureModelReady(recognizer: SpeechRecognizer) {
        val status = try {
            recognizer.checkStatus()
        } catch (e: GenAiException) {
            throw SpeechFailure.Engine(
                "Impossible de vérifier la disponibilité du modèle de reconnaissance vocale (code ${e.errorCode})."
            )
        }
        when (status) {
            FeatureStatus.AVAILABLE -> return
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                startDownload()
                throw SpeechFailure.ModelDownloading()
            }
            FeatureStatus.UNAVAILABLE -> throw SpeechFailure.Unsupported(
                "Cet appareil ne prend pas en charge la reconnaissance vocale française sur l'appareil."
            )
            else -> throw SpeechFailure.Engine(
                "Statut de disponibilité du modèle de reconnaissance vocale inconnu."
            )
        }
    }

    /** Collects the download to completion on its own client, so leaving the session screen can't cancel it. */
    @Synchronized
    private fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = appScope.launch {
            val downloader = newRecognizer()
            try {
                downloader.download().collect { }
            } catch (_: GenAiException) {
                // The next transcribe() call re-checks the status and retries.
            } finally {
                downloader.close()
            }
        }
    }

    private suspend fun FlowCollector<TranscriptSegment>.runOneSession(
        recognizer: SpeechRecognizer,
        scope: CoroutineScope,
        feed: ReceiveChannel<ShortArray>?,
    ) {
        // With a feed, ML Kit reads 16 kHz mono 16-bit PCM from a pipe that a writer fills at real-time pace.
        val pipe = feed?.let { ParcelFileDescriptor.createPipe() }
        val writer = pipe?.let { (_, writeEnd) ->
            scope.launch(Dispatchers.IO) {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        for (chunk in feed) out.write(chunk.toLittleEndianBytes())
                    }
                }
            }
        }
        val source = pipe?.let { MlKitAudioSource.fromPfd(it[0]) } ?: MlKitAudioSource.fromMic()
        val request = speechRecognizerRequest { audioSource = source }
        try {
            recognizer.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse ->
                        emit(TranscriptSegment(response.text, isFinal = false, at = Instant.now()))

                    is SpeechRecognizerResponse.FinalTextResponse ->
                        emit(TranscriptSegment(response.text, isFinal = true, at = Instant.now()))

                    is SpeechRecognizerResponse.CompletedResponse -> Unit // session ended on its own; the outer loop restarts it

                    is SpeechRecognizerResponse.ErrorResponse ->
                        throw SpeechFailure.Engine(
                            "Erreur du moteur de reconnaissance vocale (code ${response.e.errorCode})."
                        )
                }
            }
        } catch (failure: SpeechFailure) {
            throw failure
        } catch (e: GenAiException) {
            throw SpeechFailure.Engine("Erreur du moteur de reconnaissance vocale (code ${e.errorCode}).")
        } finally {
            writer?.cancel()
            // Closing the read end also unblocks a writer stuck on a full pipe.
            pipe?.get(0)?.close()
        }
    }

    private fun ShortArray.toLittleEndianBytes(): ByteArray = ByteArray(size * 2).also { bytes ->
        forEachIndexed { i, sample ->
            bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }
}
