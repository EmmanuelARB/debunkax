package app.debunkax.ui.speaker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.debunkax.speaker.SpeakerGuess
import app.debunkax.speaker.SpeakerIdentifier
import app.debunkax.speaker.SpeakerMatcher
import app.debunkax.speech.MicAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpeakerTestState(
    val running: Boolean = false,
    val speakerCount: Int = 0,
    val threshold: Float = SpeakerMatcher.DEFAULT_THRESHOLD,
    val current: SpeakerGuess? = null,
    /** RMS of the last window, 0..1: shows whether the microphone hears anything at all. */
    val level: Float? = null,
    /** Newest first: one line each time the guess changes. */
    val log: List<String> = emptyList(),
    val error: String? = null,
)

/** 5 s windows every 2.5 s, as in spikes/speakers/live.py. Only the current window is kept in memory. */
class SpeakerTestViewModel(private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(SpeakerTestState())
    val state: StateFlow<SpeakerTestState> = _state.asStateFlow()

    private var identifier: SpeakerIdentifier? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                runCatching {
                    check(SpeakerIdentifier.available(context.assets)) { "Empreintes vocales absentes de cette version" }
                    SpeakerIdentifier.load(context.assets)
                }
            }
            loaded.onSuccess { id ->
                identifier = id
                _state.update { it.copy(speakerCount = id.speakers.size) }
            }.onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun setThreshold(value: Float) = _state.update { it.copy(threshold = value) }

    fun start() {
        val id = identifier ?: return
        if (job != null) return
        _state.update { it.copy(running = true, log = emptyList(), current = null) }
        val startedAt = SystemClock.elapsedRealtime()
        job = viewModelScope.launch(Dispatchers.Default) {
            val window = FloatArray(WINDOW)
            var filled = 0
            var lastKey: String? = null
            try {
                MicAudioSource().chunks().collect { chunk ->
                    for (s in chunk) {
                        window[filled++] = s / 32768f
                        if (filled == WINDOW) {
                            val level = SpeakerIdentifier.rms(window)
                            val guess = id.identify(window.copyOf(), _state.value.threshold)
                            Log.d("SpeakerTest", "rms=$level ${describe(guess)}")
                            val key = when (guess) {
                                is SpeakerGuess.Named -> guess.name
                                is SpeakerGuess.Unknown -> "?"
                                SpeakerGuess.Silence -> "-"
                            }
                            val line = if (key != lastKey) "${clock(SystemClock.elapsedRealtime() - startedAt)}  ${describe(guess)}" else null
                            lastKey = key
                            _state.update { st -> st.copy(current = guess, level = level, log = if (line != null) (listOf(line) + st.log).take(200) else st.log) }
                            window.copyInto(window, 0, HOP, WINDOW)
                            filled = WINDOW - HOP
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false) }
    }

    override fun onCleared() {
        job?.cancel()
        identifier?.release()
    }

    private fun describe(guess: SpeakerGuess) = when (guess) {
        is SpeakerGuess.Named -> "${guess.name} (${score(guess.score)})"
        is SpeakerGuess.Unknown -> "inconnu (plus proche : ${guess.closest} ${score(guess.score)})"
        SpeakerGuess.Silence -> "(silence)"
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    private companion object {
        const val WINDOW = SpeakerIdentifier.SAMPLE_RATE * 5
        const val HOP = SpeakerIdentifier.SAMPLE_RATE * 5 / 2
    }
}
