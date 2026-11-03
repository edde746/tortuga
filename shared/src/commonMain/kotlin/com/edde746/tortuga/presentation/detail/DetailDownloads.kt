package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.KeepSynced
import com.edde746.tortuga.downloads.SeasonPick
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.presentation.downloads.DownloadText.percent
import com.edde746.tortuga.presentation.downloads.active
import com.edde746.tortuga.presentation.downloads.retryable
import com.edde746.tortuga.strings.T

/**
 * Detail's download button and menus ([DetailDownload], [DetailEpisodeDownload]) from the profile's downloads of the
 * title. The menus' entry ids are the constants here.
 */
internal object DetailDownloads {
    // An episode's entries, and a movie's.
    const val DOWNLOAD = "download"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val RETRY = "retry"
    const val PICK_STREAM = "pickStream"
    const val CANCEL = "cancel"
    const val DELETE = "delete"

    // A series' entries: "season:UNWATCHED" (a SeasonPick of the selected season), "series:NEXT_5" (of every
    // season), "sync:5" and "sync:all" (Keep synced), "syncRules" (the Keep synced screen, where a rule pauses or
    // stops), and managing what the series has.
    const val SEASON_PREFIX = "season:"
    const val SERIES_PREFIX = "series:"
    const val SYNC_PREFIX = "sync:"
    const val SYNC_ALL = "all"
    const val SYNC_RULES = "syncRules"
    const val PAUSE_ALL = "pauseAll"
    const val RESUME_ALL = "resumeAll"
    const val DELETE_SEASON = "deleteSeason"
    const val DELETE_ALL = "deleteAll"

    /** The button as shown, and the entry id a press runs at once (null when the press opens the menu). */
    class Title(val download: DetailDownload, val press: String?)

    /** [meta] can be downloaded: a movie (a title without videos) or a series; not a channel's videos. */
    fun downloadable(meta: MetaItem): Boolean = meta.videos.isEmpty() || isSeries(meta)

    fun isSeries(meta: MetaItem): Boolean = meta.videos.any { it.seriesInfo != null }

    /** The video a movie plays (and downloads) as. */
    fun movieVideoId(meta: MetaItem): String = meta.preview.behaviorHints.defaultVideoId ?: meta.id

    /** The download of [videoId] among the title's [downloads], if any. */
    fun of(downloads: List<Download>, videoId: String): Download? = downloads.firstOrNull { it.videoId == videoId }

    /**
     * The button of [meta] with the profile's [downloads] of it (cancelled ones left out) and its Keep synced [rule];
     * [season] is the season Detail shows, [outEpisodes] how many of its episodes are out. A series' menu leads to the
     * Keep synced screen, where a rule stops.
     */
    fun title(meta: MetaItem, downloads: List<Download>, rule: KeepSynced?, season: Int?, outEpisodes: Int): Title =
        if (isSeries(meta)) series(meta, downloads, rule, season, outEpisodes) else movie(meta, downloads)

    /** An episode's state for its row, from its [download] (null when it has none). */
    fun episode(download: Download?): DetailEpisodeDownload {
        val text = T.detail.download
        val label = when (download?.status) {
            null, DownloadStatus.CANCELLED -> null
            DownloadStatus.QUEUED, DownloadStatus.RESOLVING -> if (download.waitingForWifi) text.waitingForWifi else text.episode.queued
            DownloadStatus.DOWNLOADING -> download.fraction?.let { text.episode.downloading(percent(it)) } ?: text.downloadingStarted
            DownloadStatus.PAUSED -> text.episode.paused
            DownloadStatus.FAILED -> text.episode.failed
            DownloadStatus.COMPLETED -> text.episode.downloaded
        }
        return DetailEpisodeDownload(state(download), label, progress(download))
    }

    private fun movie(meta: MetaItem, downloads: List<Download>): Title {
        val download = of(downloads, movieVideoId(meta)) ?: downloads.firstOrNull()
        val text = T.detail.download
        val state = state(download)
        val label = when (download?.status) {
            null, DownloadStatus.CANCELLED -> text.download
            DownloadStatus.QUEUED, DownloadStatus.RESOLVING -> if (download.waitingForWifi) text.waitingForWifi else text.queued
            DownloadStatus.DOWNLOADING -> download.fraction?.let { text.downloading(percent(it)) } ?: text.downloadingStarted
            DownloadStatus.PAUSED -> text.paused
            DownloadStatus.FAILED -> text.failed
            DownloadStatus.COMPLETED -> text.downloaded
        }
        val entries = entries(download)
        // Download, Paused and Download failed act at once (the first entry: download, resume, retry or pick a stream).
        val press = if (state == DetailDownloadState.AVAILABLE || state == DetailDownloadState.PAUSED || state == DetailDownloadState.FAILED) {
            entries.first().id
        } else {
            null
        }
        val ui = DetailDownload(
            state = state,
            label = label,
            progress = progress(download)?.takeIf { state == DetailDownloadState.DOWNLOADING },
            opensMenu = press == null,
            menuTitle = text.menuTitle,
            menu = listOf(DetailDownloadSection(title = null, entries = entries)),
        )
        return Title(ui, press)
    }

    private fun series(meta: MetaItem, downloads: List<Download>, rule: KeepSynced?, season: Int?, out: Int): Title {
        val text = T.detail.download
        val active = downloads.filter { it.status.active }
        val completed = downloads.count { it.status == DownloadStatus.COMPLETED }
        val state = when {
            downloads.any { it.status == DownloadStatus.DOWNLOADING } -> DetailDownloadState.DOWNLOADING
            active.isNotEmpty() -> DetailDownloadState.QUEUED
            downloads.any { it.status == DownloadStatus.PAUSED } -> DetailDownloadState.PAUSED
            downloads.any { it.status == DownloadStatus.FAILED } -> DetailDownloadState.FAILED
            completed in 1 until out -> DetailDownloadState.PARTIAL
            completed > 0 -> DetailDownloadState.DOWNLOADED
            else -> DetailDownloadState.AVAILABLE
        }
        // What is still to come: the episodes on their way, with a known size.
        val pending = downloads.filter { (it.status.active || it.status == DownloadStatus.PAUSED) && (it.totalBytes ?: 0) > 0 }
        val progress = pending.takeIf { state == DetailDownloadState.DOWNLOADING && it.isNotEmpty() }
            ?.let { list -> list.sumOf { it.bytes }.toDouble() / list.sumOf { checkNotNull(it.totalBytes) } }
            ?.coerceIn(0.0, 1.0)
        val label = when (state) {
            DetailDownloadState.DOWNLOADING -> progress?.let { text.downloading(percent(it)) } ?: text.downloadingStarted
            DetailDownloadState.QUEUED -> if (active.all { it.waitingForWifi }) text.waitingForWifi else text.queued
            DetailDownloadState.PAUSED -> text.paused
            DetailDownloadState.FAILED -> text.failed
            DetailDownloadState.PARTIAL -> text.partial(completed.toString(), out.toString())
            DetailDownloadState.DOWNLOADED -> text.downloaded
            DetailDownloadState.AVAILABLE -> text.download
        }
        // Paused resumes at once; everything else opens the menu.
        val press = if (state == DetailDownloadState.PAUSED) RESUME_ALL else null
        val ui = DetailDownload(
            state = state,
            label = label,
            progress = progress,
            opensMenu = press == null,
            menuTitle = text.menuTitle,
            menu = seriesMenu(meta, downloads, rule, season),
        )
        return Title(ui, press)
    }

    private fun seriesMenu(meta: MetaItem, downloads: List<Download>, rule: KeepSynced?, season: Int?): List<DetailDownloadSection> {
        val text = T.detail.download
        fun picks(prefix: String) = listOf(
            SeasonPick.ALL to text.pick.all,
            SeasonPick.UNWATCHED to text.pick.unwatched,
            SeasonPick.NEXT_5 to text.pick.next5,
            SeasonPick.NEXT_10 to text.pick.next10,
        ).map { (pick, label) -> DetailDownloadEntry(prefix + pick.name, label) }
        val keepSynced = KeepSynced.CHOICES.map { count ->
            DetailDownloadEntry(
                id = SYNC_PREFIX + (count?.toString() ?: SYNC_ALL),
                label = count?.let(text::keepSyncedCount) ?: text.keepSyncedAll,
                selected = rule != null && rule.episodes == count,
            )
        } + DetailDownloadEntry(SYNC_RULES, T.downloads.keepSynced.manage)
        val retryable = downloads.any { it.status == DownloadStatus.PAUSED || it.retryable }
        val manage = listOfNotNull(
            DetailDownloadEntry(PAUSE_ALL, text.pauseAll).takeIf { downloads.any { it.status.active } },
            DetailDownloadEntry(RESUME_ALL, text.resumeAll).takeIf { retryable },
            DetailDownloadEntry(DELETE_SEASON, text.deleteSeason, destructive = true).takeIf { season != null && downloads.any { it.season == season } },
            DetailDownloadEntry(DELETE_ALL, text.deleteAll, destructive = true).takeIf { downloads.isNotEmpty() },
        )
        return listOfNotNull(
            season?.let { DetailDownloadSection(if (it == 0) T.detail.specials else T.detail.season(it.toString()), picks(SEASON_PREFIX)) },
            DetailDownloadSection(text.allSeasons, picks(SERIES_PREFIX)).takeIf { season == null || meta.episodesPerSeason.size > 1 },
            DetailDownloadSection(text.keepSynced, keepSynced),
            DetailDownloadSection(text.manage, manage).takeIf { manage.isNotEmpty() },
        )
    }

    /** A movie's download menu entries for its [download]. */
    private fun entries(download: Download?): List<DetailDownloadEntry> {
        val text = T.detail.download.episode
        return when (download?.status) {
            null, DownloadStatus.CANCELLED -> listOf(DetailDownloadEntry(DOWNLOAD, text.download))
            DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING ->
                listOf(DetailDownloadEntry(PAUSE, text.pause), DetailDownloadEntry(CANCEL, text.cancel, destructive = true))
            DownloadStatus.PAUSED -> listOf(DetailDownloadEntry(RESUME, text.resume), DetailDownloadEntry(CANCEL, text.cancel, destructive = true))
            DownloadStatus.FAILED -> listOf(
                if (download.retryable) DetailDownloadEntry(RETRY, text.retry) else DetailDownloadEntry(PICK_STREAM, text.pickStream),
                DetailDownloadEntry(DELETE, text.delete, destructive = true),
            )
            DownloadStatus.COMPLETED -> listOf(DetailDownloadEntry(DELETE, text.delete, destructive = true))
        }
    }

    private fun state(download: Download?): DetailDownloadState = when (download?.status) {
        null, DownloadStatus.CANCELLED -> DetailDownloadState.AVAILABLE
        DownloadStatus.QUEUED, DownloadStatus.RESOLVING -> DetailDownloadState.QUEUED
        DownloadStatus.DOWNLOADING -> DetailDownloadState.DOWNLOADING
        DownloadStatus.PAUSED -> DetailDownloadState.PAUSED
        DownloadStatus.FAILED -> DetailDownloadState.FAILED
        DownloadStatus.COMPLETED -> DetailDownloadState.DOWNLOADED
    }

    /** How far a download on its way is: while downloading or paused, with a known size. */
    private fun progress(download: Download?): Double? =
        download?.takeIf { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED }?.fraction
}
