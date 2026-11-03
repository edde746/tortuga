package com.edde746.tortuga.downloads

import com.edde746.tortuga.playback.session.StreamChoice

/**
 * Where a download is in its life ([DownloadManager]): queued → resolving → downloading → completed, with paused and
 * failed on the way and cancelled as an end of its own.
 */
enum class DownloadStatus {
    /** Waiting for its turn (or for Wi-Fi: [Download.waitingForWifi]). */
    QUEUED,

    /** Its stream is being resolved (the resolved link's final URL, size and hash) right before the transfer. */
    RESOLVING,

    /** Bytes are moving. */
    DOWNLOADING,

    /** Stopped by the user; what arrived is kept and the transfer resumes from there. */
    PAUSED,

    /** Stopped by an error ([Download.failure]), or found without its file once complete; retryable. */
    FAILED,

    /** The whole file is on the device: it plays offline. */
    COMPLETED,

    /** Stopped by the user for good; the partial file is gone. */
    CANCELLED,
}

/** Why a download failed ([Download.failure]). None of these carry a URL or an exception message (secrets). */
enum class DownloadFailureKind {
    /** No stream could be picked automatically for the episode (a season download); the user picks one by hand. */
    NO_STREAM,

    /** The connection failed or timed out, after the retries. */
    NETWORK,

    /** The server answered an HTTP error ([DownloadFailure.httpStatus]) that re-resolving didn't fix. */
    HTTP,

    /** The link expired and the addon no longer offers the same stream. */
    STREAM_GONE,

    /** The device ran out of space mid-transfer; the queue stopped. */
    DISK_FULL,

    /** There isn't room for the file's size before it starts. */
    NOT_ENOUGH_SPACE,

    /** The finished file's size isn't the size the server announced. */
    SIZE_MISMATCH,

    /** The stream isn't an http(s) file this app can download. */
    UNSUPPORTED,

    /** The download location is gone (an SD card removed, a folder deleted or not writable). */
    LOCATION_UNAVAILABLE,

    /** A completed download's file is gone from its location (deleted outside the app), found as it was played. */
    FILE_MISSING,

    /** The app's own storage failed every attempt (its database, too busy or failing, threw); retried before. */
    STORAGE,
}

/** @param httpStatus the server's status for [DownloadFailureKind.HTTP]; null otherwise. */
data class DownloadFailure(val kind: DownloadFailureKind, val httpStatus: Int? = null)

/**
 * One video downloaded (or to be) on this device, as the screens show it.
 *
 * @param id the download's id, stable for its life.
 * @param type the meta's type (`movie`, `series`, …).
 * @param title the movie's or the series' name.
 * @param episodeTitle the episode's own title, for an episode; null for a movie.
 * @param season the episode's season, for an episode; null for a movie.
 * @param episode the episode's number, for an episode; null for a movie.
 * @param poster the meta's poster: a `file://` URL once saved on the device, else the addon's URL; null for none.
 * @param thumbnail the episode's thumbnail (same rules as [poster]); null for a movie or none.
 * @param status where it is.
 * @param failure why it failed, while [status] is [DownloadStatus.FAILED].
 * @param bytes what arrived so far.
 * @param totalBytes the file's size, once known.
 * @param bytesPerSecond the transfer's current speed, while [status] is [DownloadStatus.DOWNLOADING].
 * @param waitingForWifi queued, held back by the Wi-Fi only setting on a metered network.
 * @param addedAt when it was asked for, epoch milliseconds.
 * @param completedAt when it completed, epoch milliseconds.
 * @param owners the profiles that asked for it (a profile sees its own downloads only).
 * @param keepSyncedEpisode it was queued by Keep synced, not by hand.
 */
data class Download(
    val id: Long,
    val type: String,
    val metaId: String,
    val videoId: String,
    val title: String,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val poster: String?,
    val thumbnail: String?,
    val status: DownloadStatus,
    val failure: DownloadFailure?,
    val bytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long?,
    val waitingForWifi: Boolean,
    val addedAt: Long,
    val completedAt: Long?,
    val owners: Set<String>,
    val keepSyncedEpisode: Boolean,
) {
    /** 0 to 1 once the size is known; null before. */
    val fraction: Double? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0) }
}

/**
 * The download location's space.
 *
 * @param usedBytes what this app's downloads take (completed files and partial ones).
 * @param freeBytes free space on the location's volume; null where the platform can't tell.
 */
data class DownloadStorage(val usedBytes: Long, val freeBytes: Long?)

/** Which episodes "Download season…" queues. */
enum class SeasonPick {
    ALL,
    UNWATCHED,
    NEXT_5,
    NEXT_10,
}

/**
 * Keep synced: a series kept at [episodes] unwatched episodes downloaded; [episodes] null keeps
 * every unwatched episode. Choices: 1, 3, 5, 10, all.
 */
data class KeepSynced(val episodes: Int?) {
    companion object {
        /** The counts the menus offer, then "all" (null). */
        val CHOICES: List<Int?> = listOf(1, 3, 5, 10, null)
    }
}

/**
 * A profile's Keep synced rule as the rules screen shows it, from what this device saved (no addon is asked).
 *
 * @param metaId the series.
 * @param type the series' meta type.
 * @param title the series' name as last fetched; its id until a rule older than the name runs once.
 * @param poster the series' poster: a `file://` URL once saved with its downloads, else the addon's URL; null for none.
 * @param rule how many unwatched episodes it keeps.
 * @param paused it doesn't run until resumed; its downloads carry on.
 */
data class KeepSyncedRule(
    val metaId: String,
    val type: String,
    val title: String,
    val poster: String?,
    val rule: KeepSynced,
    val paused: Boolean,
)

/**
 * What Keep synced queued on its own (at launch, after a watch, on a network change) for a profile since the profile
 * last saw it: [episodes] episodes of the series named [titles], in the order they were queued.
 */
data class KeepSyncedNotice(val episodes: Int, val titles: List<String>)

/**
 * A completed download, ready to play from the device ([com.edde746.tortuga.playback.session.PlayRequest.local]).
 *
 * @param downloadId the download it is.
 * @param path the video file's absolute path.
 * @param size the file's size in bytes.
 * @param videoHash its OpenSubtitles hash, when known (watch together's file matching, subtitles).
 * @param filename the file name the stream had (the addon's, the server's), else the saved file's.
 * @param subtitles the subtitle files saved next to it.
 * @param stream the stream it was downloaded from: what the stream history keeps, never the local path.
 */
data class LocalVideo(
    val downloadId: Long,
    val path: String,
    val size: Long,
    val videoHash: String?,
    val filename: String?,
    val subtitles: List<LocalSubtitle>,
    val stream: StreamChoice,
) {
    override fun toString(): String = "LocalVideo(downloadId=$downloadId, size=$size, subtitles=${subtitles.size}, stream=$stream)"
}

/** A subtitle file saved with a download: its absolute [path], the addon's [title] and its [language] tag. */
data class LocalSubtitle(val path: String, val title: String, val language: String?)
