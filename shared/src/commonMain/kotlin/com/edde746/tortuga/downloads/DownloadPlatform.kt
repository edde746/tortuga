package com.edde746.tortuga.downloads

/**
 * What [DownloadManager] needs from the OS (the glue stays native): where files go, how much room there
 * is, and how bytes move. The shared engine decides what downloads and when; the platform moves the bytes where it
 * must ([transport]). One per process, given to [installDownloads]. Apple TV has none: no downloads there.
 *
 * - Android: [KtorTransport] inside a WorkManager foreground `dataSync` worker ([onQueueActive] starts it); locations
 *   are the app's internal storage and the external volumes the system offers.
 * - iPhone, iPad, Mac: a background `URLSession` ([DownloadTransport] in Swift, using [TransferFiles]); one location in
 *   Application Support, excluded from backups.
 * - Desktop: [KtorTransport] in-process; one location, a folder the user picks (default: Videos/Tortuga).
 */
interface DownloadPlatform {
    /**
     * The places downloads can go, the default first. Android: internal storage, then each removable volume mounted;
     * Apple: one; desktop: the default folder (and [DownloadLocation.folder] for any other the user picks).
     */
    fun locations(): List<DownloadLocation>

    /** Settings offers "Change folder" for a folder the user picks (desktop) instead of [locations]' list. */
    val choosesFolder: Boolean get() = false

    /** Free bytes on the volume holding [path]; null where it can't be told. */
    fun freeBytes(path: String): Long?

    /** Moves the bytes. */
    val transport: DownloadTransport

    /**
     * Whether the Wi-Fi only setting is offered, and its default: true on phones and tablets, false on Android TV;
     * null where it isn't offered (desktop, which can't tell a metered network).
     */
    val wifiOnlyDefault: Boolean?

    /**
     * The queue has work to do ([active]) or none left. Android starts its foreground worker (with an unmetered
     * network constraint when [wifiOnly]) and lets it end; elsewhere nothing happens. Main thread.
     */
    fun onQueueActive(active: Boolean, wifiOnly: Boolean) {}

    /** A directory under a location was created: Apple marks it excluded from backups. */
    fun excludeFromBackup(path: String) {}
}

/**
 * A place downloads can go.
 *
 * @param id stored with each download, so its path survives the absolute path changing (an iOS container move): the
 *   built-in ids are `internal` (the app's own storage) and, on Android, `volume:<uuid>` per removable volume; a
 *   folder the user picked is `folder:<absolute path>`.
 * @param path the directory's absolute path now.
 * @param label as Settings names it ("Internal storage", "SD card", the folder's path); already translated.
 * @param removable it can go away (an SD card, an external drive).
 */
data class DownloadLocation(val id: String, val path: String, val label: String, val removable: Boolean) {
    companion object {
        const val INTERNAL: String = "internal"
        private const val FOLDER_PREFIX = "folder:"

        /** A folder the user picked (desktop). */
        fun folder(path: String, label: String = path): DownloadLocation = DownloadLocation(FOLDER_PREFIX + path, path, label, removable = true)

        /** The folder of a `folder:` id, or null for another id. */
        fun folderPath(id: String): String? = id.takeIf { it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)
    }
}

/**
 * One file's transfer, as [DownloadManager] hands it to the [DownloadTransport]: the resolved stream's final URL into
 * [partPath], continuing after the [offset] bytes already there.
 *
 * The URL and header values carry API keys: [toString] leaves them out, and so must every log, notification and
 * error built from a request.
 *
 * @param id the download's id ([Download.id] as text): the transfer's key in every callback.
 * @param url the final URL ([com.edde746.tortuga.playback.resolve.ResolvedStream.url]).
 * @param headers the stream's request headers, `User-Agent` included.
 * @param partPath where the bytes go: `<video path>.part`, renamed to the video path once complete and verified.
 * @param offset bytes already in [partPath] to keep: sent as `Range: bytes=<offset>-`; 0 starts over.
 * @param expectedSize the file's size from the resolve, when known: a resumed answer's `Content-Range` total must match.
 * @param validator the `ETag` (or else `Last-Modified`) the bytes in [partPath] came with, sent as `If-Range`.
 * @param title the video's display name, for a platform notification (no URL in it).
 * @param wifiOnly the transfer may only use an unmetered network (Apple: no expensive or constrained access).
 */
data class TransferRequest(
    val id: String,
    val url: String,
    val headers: Map<String, String>,
    val partPath: String,
    val offset: Long,
    val expectedSize: Long?,
    val validator: String?,
    val title: String,
    val wifiOnly: Boolean,
) {
    override fun toString(): String =
        "TransferRequest(id=$id, url=<redacted>, headers=${headers.keys}, offset=$offset, expectedSize=$expectedSize, validator=${validator != null})"
}

/** How a transfer ended ([TransferListener.onResult]). */
sealed interface TransferResult {
    /**
     * [TransferRequest.partPath] holds the whole file: [bytes] long, with the [validator] its answer carried (`ETag`,
     * else `Last-Modified`).
     */
    data class Completed(val bytes: Long, val validator: String?) : TransferResult

    /** The server answered [status] (403, 404, 410: a resolved link that expired; the manager re-resolves). */
    data class Http(val status: Int) : TransferResult

    /**
     * The server's file isn't the one in [TransferRequest.partPath]: a resumed request was answered with the whole file
     * (`If-Range` didn't match) or a different total. The manager restarts it from zero.
     */
    data object Changed : TransferResult

    /** The connection failed or timed out. [kind] names the exception's class for diagnostics, never its message. */
    data class Network(val kind: String) : TransferResult

    /** Writing failed and the transport knows the volume is full (Apple: the system's out-of-space error codes). */
    data object DiskFull : TransferResult

    /** Writing failed; the manager tells a full volume from the location's free space, never from the error's text. */
    data object WriteFailed : TransferResult

    /** [DownloadTransport.stop] stopped it; what arrived is kept in [TransferRequest.partPath] (or resume data). */
    data object Stopped : TransferResult
}

/** What a [DownloadTransport] reports; callable from any thread. */
interface TransferListener {
    /** [bytes] of the file are in place now (the request's offset included), of [totalBytes] when known. */
    fun onProgress(id: String, bytes: Long, totalBytes: Long?)

    /**
     * The server accepted the transfer's request (a 200, or a 206 continuing the file): the bytes arriving now carry
     * [validator] (`ETag`, else `Last-Modified`), which a later resume sends as `If-Range`. Transports that resume by
     * other means (Apple's resume data) may skip it.
     */
    fun onResponse(id: String, validator: String?)

    /** The transfer ended. Called once per [DownloadTransport.start]. */
    fun onResult(id: String, result: TransferResult)
}

/**
 * Moves one file's bytes at a time per request ([DownloadManager] decides how many run at once). Implemented by
 * [KtorTransport] (Android, desktop) and by the Apple apps over a background `URLSession`.
 */
interface DownloadTransport {
    /** Starts [request]; it reports to [listener] until its [TransferListener.onResult]. */
    fun start(request: TransferRequest, listener: TransferListener)

    /** Stops the transfer [id], keeping what arrived; its result is [TransferResult.Stopped]. Nothing when it isn't running. */
    fun stop(id: String)

    /**
     * At launch: which of [ids] (transfers the database had running when the last process ended) still run (Apple
     * background sessions go on while the app is suspended or killed). Those report to [listener] from now on; [done]
     * gets their ids, and the manager queues the rest again. The default: none still runs.
     */
    fun reattach(ids: List<String>, listener: TransferListener, done: (running: List<String>) -> Unit) {
        done(emptyList())
    }
}
