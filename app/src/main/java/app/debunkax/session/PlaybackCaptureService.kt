package app.debunkax.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service of type [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION].
 *
 * Since Android 14, starting a MediaProjection — even for audio-only playback capture — requires
 * the app to be running such a service; the framework throws a SecurityException otherwise.
 * It runs only while an app-audio Listening Session is being set up or is active; the audio
 * itself is read by [app.debunkax.speech.PlaybackCaptureAudioSource].
 */
class PlaybackCaptureService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio des apps",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Débunkax écoute l'audio des apps pendant une session" }
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Débunkax écoute l'audio des apps")
            .setContentText("L'audio lue par les apps est transcrite sur cet appareil. Rien n'est enregistré.")
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "playback_capture"
        const val ACTION_STOP = "app.debunkax.playback_capture.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackCaptureService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
