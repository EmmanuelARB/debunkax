package app.debunkax.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** One file expected in the extracted model directory, with the checksum it must match. */
private data class ModelFile(val name: String, val sha256: String, val size: Long)

/**
 * The sherpa-onnx `sherpa-onnx-streaming-zipformer-fr-2023-04-14` model: int8 encoder and joiner,
 * fp32 decoder, tokens (the same combination measured in spikes/stt/RESULTS.md). It is not bundled
 * in the APK; [SherpaModelManager] downloads it from the official k2-fsa/sherpa-onnx GitHub release
 * on first use. That download is a plain GET for a public file and sends no user data (see
 * AGENTS.md's "nothing leaves the phone" rule).
 */
private object SherpaModelFiles {
    const val RELEASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-streaming-zipformer-fr-2023-04-14.tar.bz2"
    private const val ARCHIVE_ROOT = "sherpa-onnx-streaming-zipformer-fr-2023-04-14"

    val encoder = ModelFile(
        "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
        "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e",
        126_655_903,
    )
    val joiner = ModelFile(
        "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
        "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711",
        259_572,
    )
    val decoder = ModelFile(
        "decoder-epoch-29-avg-9-with-averaged-model.onnx",
        "3ef2840b684440e26382af87b2174d25732a9f113f1965cd8abba7ffc5d033fb",
        2_092_272,
    )
    val tokens = ModelFile(
        "tokens.txt",
        "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31",
        4_819,
    )
    val all = listOf(encoder, joiner, decoder, tokens)

    fun archiveEntryName(f: ModelFile) = "$ARCHIVE_ROOT/${f.name}"
}

/** Local, absolute paths to the four model files sherpa-onnx's OnlineRecognizer needs. */
data class SherpaModelPaths(val encoder: String, val joiner: String, val decoder: String, val tokens: String)

sealed interface ModelDownloadProgress {
    data object Idle : ModelDownloadProgress
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ModelDownloadProgress
    data object Extracting : ModelDownloadProgress
    data object Ready : ModelDownloadProgress
    data class Failed(val detail: String) : ModelDownloadProgress
}

/**
 * Downloads, verifies and exposes the sherpa-onnx French model in app-private storage
 * (filesDir/sherpa-model). One download attempt runs to completion on [appScope] regardless of
 * whether the caller keeps collecting, mirroring [app.debunkax.speech.MlKitSpeechEngine]'s own
 * model download. Never touches audio or transcripts: this only ever moves model weights.
 */
class SherpaModelManager(
    context: Context,
    private val http: OkHttpClient,
    private val appScope: CoroutineScope,
) {
    private val modelDir = File(context.filesDir, "sherpa-model")
    private val archiveFile = File(context.filesDir, "sherpa-model-download.tar.bz2")
    private var downloadJob: Job? = null

    private val _progress = MutableStateFlow<ModelDownloadProgress>(ModelDownloadProgress.Idle)
    val progress: StateFlow<ModelDownloadProgress> = _progress.asStateFlow()

    /** Cheap check (file existence and size only) so it is safe to call before every session. */
    fun isReady(): Boolean = SherpaModelFiles.all.all { matchesSize(File(modelDir, it.name), it) }

    fun paths(): SherpaModelPaths = SherpaModelPaths(
        encoder = File(modelDir, SherpaModelFiles.encoder.name).absolutePath,
        joiner = File(modelDir, SherpaModelFiles.joiner.name).absolutePath,
        decoder = File(modelDir, SherpaModelFiles.decoder.name).absolutePath,
        tokens = File(modelDir, SherpaModelFiles.tokens.name).absolutePath,
    )

    @Synchronized
    fun startDownloadIfNeeded() {
        if (downloadJob?.isActive == true) return
        downloadJob = appScope.launch(Dispatchers.IO) {
            try {
                downloadAndExtract()
                _progress.value = ModelDownloadProgress.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next isReady()/startDownloadIfNeeded() call (from the session's retry loop) tries again.
                _progress.value = ModelDownloadProgress.Failed(e.message ?: "Échec du téléchargement du modèle")
            }
        }
    }

    private suspend fun downloadAndExtract() {
        modelDir.mkdirs()
        download()
        _progress.value = ModelDownloadProgress.Extracting
        extract()
        archiveFile.delete()
        if (!isReady()) throw IOException("Fichiers du modèle sherpa-onnx invalides après extraction")
    }

    private fun download() {
        val resumeFrom = if (archiveFile.exists()) archiveFile.length() else 0L
        val request = Request.Builder()
            .url(SherpaModelFiles.RELEASE_URL)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Téléchargement du modèle échoué (code ${response.code})")
            val body = response.body
            val resuming = response.code == 206 && resumeFrom > 0
            val alreadyRead = if (resuming) resumeFrom else 0L
            val total = body.contentLength().let { if (it >= 0) it + alreadyRead else -1L }
            FileOutputStream(archiveFile, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var readSoFar = alreadyRead
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        readSoFar += n
                        _progress.value = ModelDownloadProgress.Downloading(readSoFar, total)
                    }
                }
            }
        }
    }

    private fun extract() {
        val wanted = SherpaModelFiles.all.associateBy { SherpaModelFiles.archiveEntryName(it) }
        BZip2CompressorInputStream(archiveFile.inputStream()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val expected = wanted[entry.name]
                    if (expected != null) {
                        val partial = File(modelDir, expected.name + ".part")
                        FileOutputStream(partial).use { out -> tar.copyTo(out) }
                        val finalFile = File(modelDir, expected.name)
                        if (matchesSha256(partial, expected)) {
                            partial.renameTo(finalFile)
                        } else {
                            partial.delete()
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    private fun matchesSize(file: File, expected: ModelFile): Boolean =
        file.exists() && file.length() == expected.size

    private fun matchesSha256(file: File, expected: ModelFile): Boolean {
        if (!matchesSize(file, expected)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex == expected.sha256
    }
}
