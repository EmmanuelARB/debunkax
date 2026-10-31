package app.debunkax.speech

import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.TranscriptSegment
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * [SpeechEngine] backed by sherpa-onnx's streaming zipformer French model
 * (`sherpa-onnx-streaming-zipformer-fr-2023-04-14`), run fully on-device. This is the fallback
 * engine, used once ML Kit reports [SpeechFailure.Unsupported] on a device (see
 * [FallbackSpeechEngine]).
 *
 * The model is not bundled in the APK; while [model] has not finished downloading and verifying
 * it, [transcribe] throws [SpeechFailure.ModelDownloading], which the session already retries.
 *
 * Streams 100 ms chunks from [audioSource] (the microphone by default; a debug-only WAV file for
 * "Démo audio réelle") through sherpa-onnx's endpoint detector: partial results are emitted as
 * `isFinal=false`, and each endpoint emits a final segment before the recognizer stream is reset.
 * Never keeps audio beyond the current chunk, and never logs recognized text.
 */
class SherpaSpeechEngine(
    private val model: SherpaModelManager,
    private val audioSource: AudioSource = MicAudioSource(),
) : SpeechEngine {

    override fun transcribe(): Flow<TranscriptSegment> = flow {
        if (!model.isReady()) {
            model.startDownloadIfNeeded()
            throw SpeechFailure.ModelDownloading()
        }
        try {
            val recognizer = newRecognizer()
            try {
                runSession(recognizer)
            } finally {
                withContext(NonCancellable) { recognizer.release() }
            }
        } catch (failure: SpeechFailure) {
            throw failure
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SpeechFailure.Engine("Erreur du moteur de reconnaissance vocale sherpa-onnx.")
        }
    }

    private fun newRecognizer(): OnlineRecognizer {
        val paths = model.paths()
        val config = OnlineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SHERPA_SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.encoder,
                    decoder = paths.decoder,
                    joiner = paths.joiner,
                ),
                tokens = paths.tokens,
                modelType = "zipformer",
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(),
            enableEndpoint = true,
        )
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private suspend fun FlowCollector<TranscriptSegment>.runSession(recognizer: OnlineRecognizer) {
        val stream = recognizer.createStream()
        emit(TranscriptSegment("", isFinal = false, at = Instant.now()))
        var lastPartial = ""
        try {
            audioSource.chunks().collect { chunk ->
                if (!currentCoroutineContext().isActive) return@collect
                val samples = FloatArray(chunk.size) { chunk[it] / 32768.0f }
                stream.acceptWaveform(samples, SHERPA_SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)

                val isEndpoint = recognizer.isEndpoint(stream)
                val text = recognizer.getResult(stream).text

                if (isEndpoint) {
                    recognizer.reset(stream)
                    if (text.isNotBlank()) emit(TranscriptSegment(text, isFinal = true, at = Instant.now()))
                    lastPartial = ""
                } else if (text.isNotBlank() && text != lastPartial) {
                    lastPartial = text
                    emit(TranscriptSegment(text, isFinal = false, at = Instant.now()))
                }
            }
        } finally {
            withContext(NonCancellable) { stream.release() }
        }
    }
}
