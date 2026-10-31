package app.debunkax.speech

import app.debunkax.domain.SpeechEngine
import app.debunkax.domain.SpeechFailure
import app.debunkax.domain.TranscriptSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [SpeechEngine] that uses [primary] (ML Kit) as long as it works, and switches to [fallback]
 * (sherpa-onnx) once [primary] reports [SpeechFailure.Unsupported] on this device. The switch is
 * permanent for the rest of the process: an unsupported ML Kit model does not become supported
 * mid-run, so there is no point retrying it every session.
 */
class FallbackSpeechEngine(
    private val primary: SpeechEngine,
    private val fallback: SpeechEngine,
) : SpeechEngine {

    @Volatile
    private var useFallback = false

    override fun transcribe(): Flow<TranscriptSegment> = flow {
        if (useFallback) {
            fallback.transcribe().collect { emit(it) }
            return@flow
        }
        try {
            primary.transcribe().collect { emit(it) }
        } catch (e: SpeechFailure.Unsupported) {
            useFallback = true
            fallback.transcribe().collect { emit(it) }
        }
    }
}
