package app.debunkax.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive

/** 16 kHz, mono, 16-bit PCM samples in fixed-size real-time chunks. Never persisted. */
interface AudioSource {
    fun chunks(): Flow<ShortArray>
}

/** Hands every chunk of [inner] to [tap] too, so speaker recognition hears what speech-to-text hears. */
class TappedAudioSource(private val inner: AudioSource) : AudioSource {
    @Volatile
    var tap: ((ShortArray) -> Unit)? = null

    override fun chunks(): Flow<ShortArray> = inner.chunks().onEach { tap?.invoke(it) }
}

internal const val SHERPA_SAMPLE_RATE = 16000
private const val CHUNK_MS = 100
internal const val SHERPA_CHUNK_SAMPLES = SHERPA_SAMPLE_RATE * CHUNK_MS / 1000

/**
 * Reads from the device microphone in 100 ms chunks. Precondition: the caller (UI layer) already
 * holds RECORD_AUDIO before collecting [chunks]; this class never requests it. Audio is read and
 * handed off chunk by chunk; nothing is buffered beyond the current chunk.
 */
class MicAudioSource : AudioSource {
    override fun chunks(): Flow<ShortArray> = flow {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(SHERPA_SAMPLE_RATE, channelConfig, audioFormat)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SHERPA_SAMPLE_RATE,
            channelConfig,
            audioFormat,
            maxOf(minBuffer, SHERPA_CHUNK_SAMPLES * 2) * 2,
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord n'a pas pu être initialisé" }
            record.startRecording()
            val buffer = ShortArray(SHERPA_CHUNK_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Debug-only [AudioSource] that replays a 16 kHz mono 16-bit PCM WAV file at real-time pace
 * instead of the microphone, looping back to the start once it ends so a demo session never goes
 * silent. Used by "Démo audio réelle" in Réglages, and by tests.
 */
class FileAudioSource(private val file: File) : AudioSource {
    override fun chunks(): Flow<ShortArray> = flow {
        if (!file.exists()) {
            throw FileNotFoundException("Fichier audio de démonstration introuvable : ${file.name}")
        }
        val wav = readWavHeader(file)
        require(wav.sampleRate == SHERPA_SAMPLE_RATE && wav.channels == 1 && wav.bitsPerSample == 16) {
            "Le fichier audio de démonstration doit être en 16 kHz mono 16 bits"
        }
        val buffer = ShortArray(SHERPA_CHUNK_SAMPLES)
        val bytes = ByteArray(SHERPA_CHUNK_SAMPLES * 2)
        RandomAccessFile(file, "r").use { raf ->
            while (currentCoroutineContext().isActive) {
                raf.seek(wav.dataOffset)
                var position = 0L
                while (currentCoroutineContext().isActive && position < wav.dataSize) {
                    val toRead = minOf(bytes.size.toLong(), wav.dataSize - position).toInt()
                    val read = raf.read(bytes, 0, toRead)
                    if (read <= 0) break
                    position += read
                    val sampleCount = read / 2
                    for (i in 0 until sampleCount) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        buffer[i] = ((hi shl 8) or lo).toShort()
                    }
                    emit(buffer.copyOf(sampleCount))
                    delay((sampleCount * 1000L) / SHERPA_SAMPLE_RATE)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    private fun readWavHeader(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            require(riff.decodeToString(0, 4) == "RIFF" && riff.decodeToString(8, 12) == "WAVE") {
                "Fichier audio de démonstration invalide (pas un WAV)"
            }
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = 0L
            val header = ByteArray(8)
            while (raf.read(header) == 8) {
                val id = header.decodeToString(0, 4)
                val size = leInt(header, 4)
                if (id == "fmt ") {
                    val fmt = ByteArray(size)
                    raf.readFully(fmt)
                    channels = leShort(fmt, 2)
                    sampleRate = leInt(fmt, 4)
                    bitsPerSample = leShort(fmt, 14)
                } else if (id == "data") {
                    dataOffset = raf.filePointer
                    dataSize = size.toLong()
                    break
                } else {
                    raf.seek(raf.filePointer + size)
                }
            }
            require(dataOffset >= 0) { "Chunk 'data' manquant dans le fichier audio de démonstration" }
            return WavInfo(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
        }
    }

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
