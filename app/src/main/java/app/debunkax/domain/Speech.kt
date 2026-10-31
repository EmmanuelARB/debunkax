package app.debunkax.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class TranscriptSegment(val text: String, val isFinal: Boolean, val at: Instant)

sealed class SpeechFailure(message: String) : Exception(message) {
    class Unsupported(detail: String) : SpeechFailure(detail)
    class ModelDownloading : SpeechFailure("Téléchargement du modèle de reconnaissance vocale en cours")
    class Engine(detail: String) : SpeechFailure(detail)
}

/**
 * Streams French speech from the microphone. Collection starts listening; cancellation stops it. Audio is never kept.
 * Emits an empty partial segment once actually listening, so callers can clear model-download notices.
 */
interface SpeechEngine {
    fun transcribe(): Flow<TranscriptSegment>
}

/**
 * Splits a final segment into sentences, so each one is filtered and checked on its own. ML Kit punctuates and
 * can finalise a whole minute of speech at once; sherpa-onnx doesn't punctuate, so its segments stay whole.
 */
object Sentences {
    private val boundary = Regex("""(?<=[.!?…])\s+""")

    fun split(text: String): List<String> = text.split(boundary).map { it.trim() }.filter { it.isNotEmpty() }
}
