package app.debunkax.speech

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Reads the audio played by this device's apps in the same 100 ms, 16 kHz, mono, 16-bit chunks as
 * [MicAudioSource], so the rest of the pipeline (speech, Claim filter, speaker recognition) is
 * unchanged. Nothing is buffered beyond the current chunk and nothing is persisted.
 *
 * The flow completes when the MediaProjection consent token is revoked (Quick Settings), which
 * ends the Listening Session cleanly.
 *
 * Preconditions, enforced elsewhere: the `mediaProjection` foreground service is running
 * (mandatory since Android 14 before any MediaProjection), RECORD_AUDIO is granted, and the user
 * approved the system consent dialog.
 */
class PlaybackCaptureAudioSource(private val projection: MediaProjection) : AudioSource {

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted by the UI layer before collecting.
    override fun chunks(): Flow<ShortArray> = flow {
        val revoked = CompletableDeferred<Unit>()
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    revoked.complete(Unit)
                }
            },
            null,
        )
        val record = AudioRecord.Builder()
            // No setAudioSource(): it is mutually exclusive with the playback-capture config, and
            // the source is implicit when a capture config is set.
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SHERPA_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setAudioPlaybackCaptureConfig(
                // A matching rule is required (the framework refuses an empty one), and media
                // playback is all we can capture from other apps and all we want to.
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "La capture de l'audio des apps n'a pas pu être initialisée"
        }
        record.startRecording()
        try {
            val buffer = ShortArray(SHERPA_CHUNK_SAMPLES)
            while (currentCoroutineContext().isActive && !revoked.isCompleted) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
