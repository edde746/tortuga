package com.edde746.tortuga.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import com.edde746.tortuga.R
import com.edde746.tortuga.TortugaApplication
import com.edde746.tortuga.presentation.player.MediaCommand
import com.edde746.tortuga.presentation.player.NowPlaying
import com.edde746.tortuga.presentation.player.NowPlayingStatus
import com.edde746.tortuga.strings.T
import kotlin.math.roundToLong

/**
 * The playback's media notification on the "Now playing" channel, which [PlaybackService] shows: a
 * [Notification.MediaStyle] over the session's token (Android 13+ draws its controls from the session itself), with the
 * title, the episode line, the artwork, and previous (when there is a previous episode), play or pause, and next (when
 * there is a next one). A tap opens the app; dismissing it (possible once the service has left the foreground) stops
 * the playback. Its actions arrive at [MediaActionReceiver].
 */
internal class PlaybackNotification(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Creates the channel, or renames it in the interface language now. */
    fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, T.player.nowPlaying, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    fun build(now: NowPlaying, artwork: Bitmap?, session: MediaSession.Token, open: PendingIntent): Notification {
        val actions = buildList {
            if (now.canSkipPrevious) add(action(PREVIOUS, R.drawable.ic_media_previous, T.player.previousEpisode))
            add(if (now.playing) action(PAUSE, R.drawable.ic_media_pause, T.player.pause) else action(PLAY, R.drawable.ic_media_play, T.player.play))
            if (now.canSkipNext) add(action(NEXT, R.drawable.ic_media_next, T.player.nextEpisode))
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(now.title)
            .setContentText(now.subtitle)
            .setLargeIcon(artwork)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(STOP))
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // A group of its own: never bundled with the download notifications.
            .setGroup(CHANNEL)
            .setActions(*actions.toTypedArray())
            .setStyle(Notification.MediaStyle().setMediaSession(session).setShowActionsInCompactView(*actions.indices.toList().toIntArray()))
        // The time played in the header while it plays at normal speed (releases before Android 13 show it).
        if (now.status == NowPlayingStatus.PLAYING && now.rate == 1.0) {
            builder.setWhen(System.currentTimeMillis() - (now.positionSeconds * 1000).roundToLong()).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    /** Takes the notification away: one left in the shade outlives the service that showed it. */
    fun cancel() {
        notifications.cancel(ID)
    }

    private fun action(command: Int, icon: Int, label: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), label, broadcast(command)).build()

    private fun broadcast(command: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        command,
        Intent(context, MediaActionReceiver::class.java).putExtra(EXTRA_COMMAND, command),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ID = 0x7D02
        private const val CHANNEL = "playback"
        const val EXTRA_COMMAND = "command"
        const val PLAY = 1
        const val PAUSE = 2
        const val PREVIOUS = 3
        const val NEXT = 4
        const val STOP = 5
    }
}

/** The media notification's actions and its dismissal, in this process (not exported): the playback's commands. */
internal class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = when (intent.getIntExtra(PlaybackNotification.EXTRA_COMMAND, 0)) {
            PlaybackNotification.PLAY -> MediaCommand.Play
            PlaybackNotification.PAUSE -> MediaCommand.Pause
            PlaybackNotification.PREVIOUS -> MediaCommand.Previous
            PlaybackNotification.NEXT -> MediaCommand.Next
            PlaybackNotification.STOP -> MediaCommand.Stop
            else -> return
        }
        (context.applicationContext as TortugaApplication).platform.mediaControls.perform(command)
    }
}
