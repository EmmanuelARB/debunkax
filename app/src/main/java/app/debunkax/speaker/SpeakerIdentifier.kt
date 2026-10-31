package app.debunkax.speaker

import android.content.res.AssetManager
import android.util.Log
import app.debunkax.BuildConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * Tells which enrolled leader is speaking in a few seconds of 16 kHz audio (ADR 0003). The model and
 * prints are bundled assets, copied by spikes/speakers/export_prints.py; audio stays in memory.
 */
class SpeakerIdentifier private constructor(
    private val extractor: SpeakerEmbeddingExtractor,
    val speakers: List<EnrolledSpeaker>,
) {
    fun identify(samples: FloatArray, threshold: Float): SpeakerGuess {
        val level = rms(samples)
        if (level < SILENCE_RMS) return SpeakerGuess.Silence
        if (BuildConfig.DEBUG) Log.d(TAG, "rms=${"%.4f".format(level)} gain=${"%.1f".format(TARGET_RMS / level)}")
        // The model scores quiet audio lower (0.77 at 0.05 RMS, 0.66 at 0.0025, 0.38 at 0.0005 on the
        // same clip), and a TV across the room is quiet: bring every window to the enrolment clips' level.
        val gain = TARGET_RMS / level
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }, SAMPLE_RATE)
            stream.inputFinished()
            return SpeakerMatcher.guess(extractor.compute(stream), speakers, threshold) { if (BuildConfig.DEBUG) Log.d(TAG, it) }
        } finally {
            stream.release()
        }
    }

    fun release() = extractor.release()

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "Speaker"
        private const val DIR = "speakers"
        // A TV across the room through VOICE_RECOGNITION is far quieter than the spike's files.
        private const val SILENCE_RMS = 0.0005f
        private const val TARGET_RMS = 0.05f

        fun available(assets: AssetManager): Boolean = assets.list(DIR)?.contains("prints.json") == true

        fun load(assets: AssetManager): SpeakerIdentifier {
            val json = JSONObject(assets.open("$DIR/prints.json").bufferedReader().use { it.readText() })
            val people = json.getJSONArray("people")
            val speakers = (0 until people.length()).map { i ->
                val p = people.getJSONObject(i)
                val prints = p.getJSONArray("prints")
                EnrolledSpeaker(
                    name = p.getString("name"),
                    role = if (p.optString("role") == "journalist") SpeakerRole.JOURNALIST else SpeakerRole.POLITICIAN,
                    prints = (0 until prints.length()).map { j ->
                        val v = prints.getJSONArray(j)
                        SpeakerMatcher.normalized(FloatArray(v.length()) { v.getDouble(it).toFloat() })
                    },
                )
            }
            val config = SpeakerEmbeddingExtractorConfig(model = "$DIR/${json.getString("model")}", numThreads = 2)
            return SpeakerIdentifier(SpeakerEmbeddingExtractor(assets, config), speakers)
        }

        fun rms(samples: FloatArray): Float {
            var s = 0.0
            for (x in samples) s += x * x
            return sqrt(s / samples.size).toFloat()
        }
    }
}
