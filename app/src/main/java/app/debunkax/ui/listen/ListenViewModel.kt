package app.debunkax.ui.listen

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.debunkax.AppContainer
import app.debunkax.session.ListeningSession
import app.debunkax.session.ListeningSource
import app.debunkax.session.PlaybackCaptureService
import app.debunkax.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ListenViewModel(private val container: AppContainer) : ViewModel() {
    private var session: ListeningSession? = null
    private var starting = false
    private var stopRequested = false
    private var activeSource: ListeningSource? = null

    /** The Alert whose ding has already played; survives leaving and re-entering the Écoute screen. */
    var lastDingedAlertId: Long? = null

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Shown under the live transcript while a session runs, so the active input is never ambiguous. */
    private val _sourceLabel = MutableStateFlow<String?>(null)
    val sourceLabel: StateFlow<String?> = _sourceLabel.asStateFlow()

    // Mic sessions listen only in the foreground (AGENTS.md): onStop stops them. App-audio
    // sessions (ADR 0004) keep running in the background on purpose — the video plays in another
    // app — and are controlled by the platform's consent, visible indicator and revocable token.
    private val backgroundStopper = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (activeSource !is ListeningSource.AppPlayback) stop()
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundStopper)
    }

    fun start(source: ListeningSource = ListeningSource.Microphone) {
        if (session != null || starting) return
        starting = true
        stopRequested = false
        // Set before the async setup: the background stopper reads it, and the user may switch
        // to the playing app before newSession() has returned.
        activeSource = source
        viewModelScope.launch {
            try {
                val newSession = container.newSession(viewModelScope, source)
                session = newSession
                _sourceLabel.value = when (source) {
                    ListeningSource.Microphone -> null
                    is ListeningSource.AppPlayback -> "Audio des apps"
                }
                var wasListening = false
                viewModelScope.launch { newSession.state.collect { st ->
                    _state.value = st
                    if (st.listening) {
                        wasListening = true
                    } else if (wasListening && session === newSession) {
                        // The session ended on its own (e.g. the app-audio consent was revoked):
                        // release the capture side, which a manual stop() would have done already.
                        session = null
                        releaseActiveSource()
                    }
                } }
                newSession.start()
            } catch (e: Exception) {
                // Setup failed: don't leak the capture foreground service if consent was given.
                activeSource = null
                _sourceLabel.value = null
                if (source is ListeningSource.AppPlayback) {
                    runCatching { source.projection.stop() }
                    PlaybackCaptureService.stop(container.appContext)
                }
            } finally {
                starting = false
            }
            // The app went to the background while the session was being set up.
            if (stopRequested) stop()
        }
    }

    fun stop() {
        if (starting) stopRequested = true
        val current = session ?: return
        session = null
        releaseActiveSource()
        // App scope: closing the screen cancels viewModelScope right after, which would cancel the
        // History write that ends the session.
        container.appScope.launch { current.stop() }
    }

    /** Stops the capture token and its mandatory foreground service when the source was app audio. */
    private fun releaseActiveSource() {
        val source = activeSource ?: return
        activeSource = null
        _sourceLabel.value = null
        if (source is ListeningSource.AppPlayback) {
            runCatching { source.projection.stop() }
            PlaybackCaptureService.stop(container.appContext)
        }
    }

    fun dismissAlert() {
        session?.dismissAlert()
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(backgroundStopper)
        val current = session ?: return
        session = null
        releaseActiveSource()
        container.appScope.launch { current.stop() }
    }
}
