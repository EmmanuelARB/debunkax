package app.debunkax.session

import android.media.projection.MediaProjection

/**
 * Where a Listening Session hears from. The [Microphone] is the room (a TV, a live debate);
 * [AppPlayback] is this device's app audio (YouTube, France.tv Pluzz…), captured from the audio
 * output with the system's per-session consent, and only for media playback.
 */
sealed interface ListeningSource {
    data object Microphone : ListeningSource
    data class AppPlayback(val projection: MediaProjection) : ListeningSource
}
