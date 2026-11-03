package com.edde746.tortuga.downloads.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.downloads.DownloadLocation
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadPlatform
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.KtorTransport
import com.edde746.tortuga.downloads.installDownloads
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Downloads on Android, one per process (`TortugaApplication.downloads`): the [DownloadPlatform] of each graph with
 * downloads ([install]: the product's, a debug launch's dev session) and the WorkManager worker their bytes move in.
 *
 * - **Locations:** the app's internal storage (`noBackupFilesDir`, so backups leave the files out), then every other
 *   volume the system gives the app a directory on (an SD card, a USB drive: `volume:<uuid>`), named as the system names
 *   it. No storage permission: both are the app's own directories.
 * - **Transfers:** each graph's [WorkerTransport] hands its requests to [DownloadWorker], which runs them in its own
 *   coroutine while it runs in the foreground ([TransferHost]), so bytes never move without its `dataSync` service and
 *   notification. The manager's [DownloadPlatform.onQueueActive] enqueues the worker ([DownloadScheduler]); it ends
 *   once no graph's queue is active.
 * - **Wi-Fi only:** on by default on phones and tablets, off on Android TV; the worker's network constraint follows it.
 * - `POST_NOTIFICATIONS` (Android 13+) is asked once, through the activity, as the first download starts.
 *
 * @param buildProduct builds the product's graph (which installs its downloads): the worker's way to the queue when
 *   WorkManager started the process without an activity. Main thread.
 */
internal class AndroidDownloads(
    context: Context,
    private val formFactor: FormFactor,
    private val activity: () -> Activity?,
    private val buildProduct: () -> Unit,
) {
    private val context: Context = context.applicationContext

    /** A graph's downloads: its manager and the transport its platform hands it. */
    class Installed(val graph: AppGraph, val manager: DownloadManager, val transport: WorkerTransport)

    /** Where transfers run: the worker's coroutine while it runs. */
    val host = TransferHost()

    val notifications = DownloadNotifications(this.context, this)

    private val scheduler = DownloadScheduler(this.context)

    private val mutableInstalled = MutableStateFlow<List<Installed>>(emptyList())

    /** The graphs with downloads now, oldest first. */
    val installed: StateFlow<List<Installed>> = mutableInstalled.asStateFlow()

    /** Some graph's queue has work the network allows ([DownloadManager.active]): the worker runs while true. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val active: Flow<Boolean> = installed.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(false) else combine(list.map { it.manager.active }) { actives -> actives.any { it } }
    }.distinctUntilChanged()

    /** Whether the notification permission was asked already, on this device ([askNotifications]). */
    private val notificationsAsked = File(this.context.noBackupFilesDir, NOTIFICATIONS_ASKED)
    private var askedNotifications = false

    /**
     * Sets up downloads on [graph] (`installDownloads`) with its files under [directory] in each location: the product
     * uses `downloads`; a dev session a directory of its own, so its database never meets the product's files.
     * Main thread.
     */
    fun install(graph: AppGraph, directory: String = DIRECTORY): DownloadManager {
        val transport = WorkerTransport(KtorTransport(graph.http), host)
        val manager = graph.installDownloads(Platform(directory, transport))
        mutableInstalled.value += Installed(graph, manager, transport)
        return manager
    }

    /** [graph] is closing (a dev session ending): its manager stops its transfers; the worker no longer serves it. */
    fun uninstall(graph: AppGraph) {
        val gone = mutableInstalled.value.filter { it.graph === graph }
        mutableInstalled.value -= gone.toSet()
        gone.forEach { onQueueActive(it.transport, active = false, wifiOnly = false) }
    }

    /**
     * The worker's way to the queue: when no graph has downloads yet (WorkManager started the process for the worker),
     * builds the product's graph, whose manager reconciles the queue and resumes it. Main thread.
     */
    fun ensureInstalled() {
        if (mutableInstalled.value.isEmpty()) buildProduct()
    }

    /** The notification's Pause: every running or queued download of every graph pauses. Main thread. */
    fun pauseAll() {
        for (entry in mutableInstalled.value) {
            entry.manager.all.value.filter { it.status in UNDER_WAY }.forEach { entry.manager.pause(it.id) }
        }
    }

    /** The notification's Cancel: [id] of the graph whose [WorkerTransport] runs it. Main thread. */
    fun cancel(transport: Int, id: Long) {
        mutableInstalled.value.firstOrNull { it.transport.token == transport }?.manager?.cancel(id)
    }

    private val mutableForegrounds = MutableStateFlow(0)

    /**
     * Counts the app coming to the foreground: a worker that couldn't start its foreground service in the background
     * (Android 12+) tries again at the next one.
     */
    val foregrounds: StateFlow<Int> = mutableForegrounds.asStateFlow()

    /** The app came to the foreground (the activity started). Main thread. */
    fun onForeground() {
        mutableForegrounds.value++
    }

    /** Queued, resolving and downloading downloads of every graph: the notification's count. */
    fun underWay(): Int = mutableInstalled.value.sumOf { entry -> entry.manager.all.value.count { it.status in UNDER_WAY } }

    /**
     * Some graph has downloads under way that wait for the network: offline, or on a metered one with Wi-Fi only. The
     * worker ending then asks WorkManager to run it again once its network constraint holds, so they go on even when
     * the process is gone by then.
     */
    fun waitingForNetwork(): Boolean = mutableInstalled.value.any { entry ->
        val downloads = entry.manager.all.value.filter { it.status in UNDER_WAY }
        downloads.isNotEmpty() && (!entry.graph.network.value.online || downloads.any { it.waitingForWifi })
    }

    /** The Wi-Fi only setting of each graph whose queue is active ([DownloadPlatform.onQueueActive]), by its transport. */
    private val activeQueues = HashMap<WorkerTransport, Boolean>()

    /**
     * A graph's queue has work or none ([DownloadPlatform.onQueueActive]): the worker runs while some queue has, on
     * an unmetered network only while every active queue is Wi-Fi only (each manager holds back its own downloads on
     * a metered one). Asks for the notification permission the first time.
     */
    private fun onQueueActive(transport: WorkerTransport, active: Boolean, wifiOnly: Boolean) {
        if (active) activeQueues[transport] = wifiOnly else activeQueues.remove(transport)
        if (activeQueues.isEmpty()) {
            scheduler.idle() // The running worker ends on its own once no queue is active.
            return
        }
        if (active) askNotifications()
        scheduler.schedule(wifiOnly = activeQueues.values.all { it })
    }

    /**
     * Android 13+: asks for `POST_NOTIFICATIONS` once, through the activity on screen, so the download's notification
     * shows (the transfer runs either way). Without an activity it asks at the next download that has one.
     */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedNotifications) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            notificationsAsked.exists()
        ) {
            askedNotifications = true
            return
        }
        val activity = activity() ?: return
        askedNotifications = true
        notificationsAsked.createNewFile()
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /** A graph's [DownloadPlatform]: its files under [directory] in each location, its bytes through [transport]. */
    private inner class Platform(private val directory: String, override val transport: WorkerTransport) : DownloadPlatform {
        override fun locations(): List<DownloadLocation> {
            val internal = DownloadLocation(
                DownloadLocation.INTERNAL, File(context.noBackupFilesDir, directory).path, T.settings.downloads.location.deviceStorage, removable = false,
            )
            return listOf(internal) + volumes()
        }

        /**
         * The app's directory on each mounted volume but the primary one (the primary shares internal storage's space):
         * SD cards and USB drives, by their system name ("SD card", the card's brand).
         */
        private fun volumes(): List<DownloadLocation> {
            val storage = context.getSystemService(StorageManager::class.java) ?: return emptyList()
            return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
                if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return@mapNotNull null
                val volume = storage.getStorageVolume(dir)?.takeUnless { it.isPrimary } ?: return@mapNotNull null
                val uuid = volume.uuid ?: return@mapNotNull null
                val label = volume.getDescription(context)?.takeIf { it.isNotBlank() } ?: T.settings.downloads.location.external
                DownloadLocation("$VOLUME_PREFIX$uuid", File(dir, directory).path, label, removable = volume.isRemovable)
            }
        }

        /** The free bytes of the volume holding [path] (its nearest directory that exists yet). */
        override fun freeBytes(path: String): Long? {
            val existing = generateSequence(File(path)) { it.parentFile }.firstOrNull { it.exists() } ?: return null
            return try {
                StatFs(existing.path).availableBytes
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        override val wifiOnlyDefault: Boolean = formFactor != FormFactor.TV

        override fun onQueueActive(active: Boolean, wifiOnly: Boolean) = this@AndroidDownloads.onQueueActive(transport, active, wifiOnly)
    }

    companion object {
        /** The product's directory in each location. */
        const val DIRECTORY = "downloads"

        /** A debug launch's dev session's directory in each location. */
        const val DEV_DIRECTORY = "dev-downloads"

        private const val VOLUME_PREFIX = "volume:"
        private const val NOTIFICATIONS_ASKED = "downloads-notifications-asked"
        private const val REQUEST_NOTIFICATIONS = 0x7D01

        /** Downloads the queue still has to finish: the notification counts them, Pause pauses them. */
        val UNDER_WAY = setOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING)
    }
}
