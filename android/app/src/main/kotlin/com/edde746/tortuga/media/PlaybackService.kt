package com.edde746.tortuga.media

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.edde746.tortuga.TortugaApplication

/**
 * Shows the playback's media notification ([AndroidMediaControls], [PlaybackNotification]): as a media-playback
 * foreground service while the controls ask for it (playing, e.g. in picture-in-picture), else left in the shade with
 * the service in the background. The controls start it when a playback starts and stop it when the playback ends; the
 * session is theirs, the service only shows it.
 */
class PlaybackService : Service() {
    private val controls: AndroidMediaControls get() = (application as TortugaApplication).platform.mediaControls

    override fun onCreate() {
        super.onCreate()
        controls.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent): IBinder? = null

    /** Swiped away from Recents: the playback has no screen left, so it stops (and with it this service). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controls.onTaskRemoved()
    }

    override fun onDestroy() {
        controls.detach(this)
        super.onDestroy()
    }

    /**
     * Shows [notification], in the foreground when [foreground]. From the background Android 12+ may refuse the
     * foreground (no user action led there): the notification is then shown as it is, the service staying where it is.
     */
    internal fun show(notification: Notification, foreground: Boolean) {
        if (foreground) {
            try {
                ServiceCompat.startForeground(this, PlaybackNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                return
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
                Log.w(TAG, "Not allowed into the foreground now; the notification shows without it", e)
            }
        }
        // The notification first, then out of the foreground: the other order can drop this update.
        getSystemService(NotificationManager::class.java).notify(PlaybackNotification.ID, notification)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
