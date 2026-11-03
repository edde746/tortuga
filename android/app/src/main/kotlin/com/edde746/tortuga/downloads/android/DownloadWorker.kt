package com.edde746.tortuga.downloads.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.edde746.tortuga.TortugaApplication
import com.edde746.tortuga.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * The one worker downloads run in ([DownloadScheduler] enqueues it): a foreground `dataSync` service with the
 * downloads' notification ([DownloadNotifications]) while some queue is active, the transfers running in its coroutine
 * ([TransferHost.attach]). It ends once no queue has been active for a moment; when downloads wait for the network
 * then, it ends as a retry, so WorkManager runs it again once its network constraint holds, starting the process if
 * it has to. The system stopping it (the constraint gone, a time limit) cancels it: the transfers stop and the manager
 * queues them again.
 *
 * Started in a process WorkManager launched for it (the app was killed), it builds the product's graph, whose manager
 * reconciles the queue and resumes it. Android 12+ refuses a foreground service started from the background: the
 * worker then runs as plain work (the system's time limit applies) and goes to the foreground when the app next does.
 */
internal class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val downloads = (applicationContext as TortugaApplication).downloads
        withContext(Dispatchers.Main) { downloads.ensureInstalled() }
        // After a process start the queue reconciles first (milliseconds): give it a moment to have work.
        if (withTimeoutOrNull(START_GRACE) { downloads.active.first { it } } == null) return end(downloads)

        downloads.notifications.ensureChannel()
        val inForeground = goForeground(downloads)
        try {
            serve(downloads, inForeground)
        } finally {
            if (isStopped) Log.i(TAG) { "Download worker stopped (reason $stopReason)" }
        }
        return end(downloads)
    }

    /** Runs the transfers until no queue has been active for [END_GRACE]; meanwhile keeps the notification current. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun serve(downloads: AndroidDownloads, inForeground: Boolean) = coroutineScope {
        val transfers = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job) + Dispatchers.IO)
        downloads.host.attach(transfers)
        val notification = launch {
            if (!inForeground) {
                // Refused in the background: try again each time the app comes to the foreground.
                var seen = downloads.foregrounds.value
                do {
                    seen = downloads.foregrounds.first { it > seen }
                    delay(FOREGROUND_SETTLE)
                } while (!goForeground(downloads))
            }
            while (true) {
                delay(UPDATE_INTERVAL)
                downloads.notifications.update()
            }
        }
        try {
            downloads.active.transformLatest { active ->
                if (!active) {
                    delay(END_GRACE)
                    emit(Unit)
                }
            }.first()
        } finally {
            downloads.host.detach(transfers)
            transfers.cancel()
            notification.cancel()
        }
    }

    private suspend fun goForeground(downloads: AndroidDownloads): Boolean = try {
        setForeground(downloads.notifications.foregroundInfo())
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException: the app is in the background (Android 12+).
        Log.w(TAG) { "Downloads can't start their foreground service now: ${e::class.simpleName}" }
        false
    }

    private fun end(downloads: AndroidDownloads): Result = if (downloads.waitingForNetwork()) Result.retry() else Result.success()

    private companion object {
        const val TAG = "Downloads"
        val START_GRACE = 10.seconds
        val END_GRACE = 2.seconds
        val UPDATE_INTERVAL = 1.seconds
        val FOREGROUND_SETTLE = 1.seconds
    }
}
