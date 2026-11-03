package com.edde746.tortuga.downloads.android

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.edde746.tortuga.MainActivity
import com.edde746.tortuga.R
import com.edde746.tortuga.TortugaApplication
import com.edde746.tortuga.downloads.TransferRequest
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate

/**
 * The download worker's notification on the "Downloads" channel: the first running transfer's title and percentage,
 * how many downloads are under way, Pause (pauses them all) and Cancel (the one shown); a tap opens the app. Built from
 * the transfers' titles only ([TransferRequest.title]): never a URL.
 */
internal class DownloadNotifications(private val context: Context, private val downloads: AndroidDownloads) {
    private val notifications = NotificationManagerCompat.from(context)

    /** Creates the channel, or renames it in the interface language now. */
    fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(T.downloads.notification.channel)
                .setShowBadge(false)
                .build(),
        )
    }

    /** What the worker's foreground service shows. */
    fun foregroundInfo(): ForegroundInfo = ForegroundInfo(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Shows the transfers as they are now (while the worker is in the foreground). */
    fun update() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notifications.notify(ID, build())
    }

    private fun build(): Notification {
        val transfer = downloads.host.running.value.firstOrNull()
        val count = downloads.underWay()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, T.downloads.notification.pause, broadcast(REQUEST_PAUSE, Intent(ACTION_PAUSE_ALL)))
        if (count > 0) builder.setSubText(T.downloads.notification.count(count))
        if (transfer == null) {
            val waiting = downloads.installed.value.any { entry -> entry.manager.all.value.any { it.waitingForWifi } }
            builder.setContentTitle(if (waiting) T.downloads.notification.waiting else T.downloads.notification.preparing)
            builder.setProgress(0, 0, !waiting)
            return builder.build()
        }
        builder.setContentTitle(T.downloads.notification.title(isolate(transfer.request.title)))
        val total = transfer.totalBytes?.takeIf { it > 0 }
        if (total != null) {
            val percent = (transfer.bytes * 100 / total).toInt().coerceIn(0, 100)
            builder.setContentText(T.downloads.notification.progress(percent.toString())).setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val cancel = Intent(ACTION_CANCEL).putExtra(EXTRA_TRANSPORT, transfer.owner.token).putExtra(EXTRA_ID, transfer.request.id)
        builder.addAction(0, T.downloads.notification.cancel, broadcast(REQUEST_CANCEL, cancel))
        return builder.build()
    }

    /** The app as the launcher opens it: the running task comes back as it was. */
    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(request: Int, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, request, intent.setClass(context, DownloadActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 0x7D01
        private const val REQUEST_OPEN = 0
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_CANCEL = 2

        const val ACTION_PAUSE_ALL = "com.edde746.tortuga.downloads.PAUSE_ALL"
        const val ACTION_CANCEL = "com.edde746.tortuga.downloads.CANCEL"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_ID = "id"
    }
}

/** The notification's actions, in this process (not exported): Pause pauses every download, Cancel the one it showed. */
internal class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val downloads = (context.applicationContext as TortugaApplication).downloads
        when (intent.action) {
            DownloadNotifications.ACTION_PAUSE_ALL -> downloads.pauseAll()
            DownloadNotifications.ACTION_CANCEL -> {
                val id = intent.getStringExtra(DownloadNotifications.EXTRA_ID)?.toLongOrNull() ?: return
                downloads.cancel(intent.getIntExtra(DownloadNotifications.EXTRA_TRANSPORT, 0), id)
            }
        }
    }
}
