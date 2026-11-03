package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.DownloadStorage
import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate

/** The Downloads screen's groups and rows from the profile's downloads, as [DownloadsUiState] shows them. */
internal object DownloadRows {
    /**
     * [downloads] (newest first) grouped by title, the title with the latest download first, every download a row: a
     * title's only download without an episode number as a movie on its own; otherwise its episodes by season (specials
     * last) in episode order, then its downloads without an episode number. [online]: queued downloads wait for a
     * connection otherwise. [cacheWording]: failures may speak of instant streams (not on Apple). [playsWith]: this
     * device has player apps that open the files, so a completed download offers "Play with…".
     */
    fun groups(downloads: List<Download>, online: Boolean, cacheWording: Boolean, playsWith: Boolean): List<DownloadGroup> =
        downloads.groupBy { it.metaId }.map { (metaId, ofTitle) ->
            val first = ofTitle.first()
            val movie = ofTitle.singleOrNull()?.takeIf { it.episode == null }
            if (movie != null) {
                DownloadGroup(
                    key = metaId,
                    type = first.type,
                    title = first.title,
                    poster = first.poster,
                    summary = first.totalBytes?.let(::formatByteSize),
                    seasons = emptyList(),
                    videos = emptyList(),
                    videosTitle = T.downloads.otherVideos,
                    movie = row(movie, online, cacheWording, playsWith),
                    deleteText = T.downloads.action.deleteGroup,
                    moreLabel = T.detail.moreActionsNamed(isolate(first.title)),
                )
            } else {
                val (episodes, videos) = ofTitle.partition { it.episode != null }
                val size = ofTitle.mapNotNull { it.totalBytes }.takeIf { it.isNotEmpty() }?.sum()
                val count = count(ofTitle)
                DownloadGroup(
                    key = metaId,
                    type = first.type,
                    title = first.title,
                    poster = first.poster,
                    summary = size?.let { T.downloads.summary(count, formatByteSize(it)) } ?: count,
                    seasons = episodes.groupBy { it.season ?: 0 }
                        .toList()
                        .sortedWith(compareBy({ it.first == 0 }, { it.first }))
                        .map { (season, inSeason) ->
                            DownloadSeason(
                                season = season,
                                title = if (season == 0) T.detail.specials else T.detail.season(season.toString()),
                                episodes = inSeason.sortedBy { it.episode }.map { row(it, online, cacheWording, playsWith) },
                            )
                        },
                    videos = videos.map { row(it, online, cacheWording, playsWith) },
                    videosTitle = T.downloads.otherVideos,
                    movie = null,
                    deleteText = T.downloads.action.deleteGroup,
                    moreLabel = T.detail.moreActionsNamed(isolate(first.title)),
                )
            }
        }

    /** "3 episodes", or "4 videos" when one of [downloads] has no episode number. */
    private fun count(downloads: List<Download>): String =
        if (downloads.all { it.episode != null }) T.downloads.episodes(downloads.size) else T.downloads.videos(downloads.size)

    /** The question before [downloads] of the title [name] go at once: "Delete 5 episodes of Breaking Bad?". */
    fun deleteDialog(name: String, downloads: List<Download>): DownloadDeleteDialog {
        val text = T.downloads.deleteConfirm
        return DownloadDeleteDialog(text.title(count(downloads), isolate(name)), text.message, text.confirm, text.cancel)
    }

    /** One download's row; [playsWith] as [groups] takes it. */
    fun row(download: Download, online: Boolean, cacheWording: Boolean, playsWith: Boolean): DownloadRow {
        val title = title(download)
        return DownloadRow(
            id = download.id,
            metaId = download.metaId,
            videoId = download.videoId,
            title = title,
            subtitle = if (download.season != null && download.episode != null) {
                T.session.episode(download.season.toString(), download.episode.toString())
            } else {
                null
            },
            artwork = download.thumbnail ?: download.poster,
            status = download.status,
            statusText = statusText(download, online),
            progress = download.fraction?.takeIf { download.status != DownloadStatus.COMPLETED },
            bytesText = bytesText(download),
            failureText = download.failure?.takeIf { download.status == DownloadStatus.FAILED }?.let { DownloadText.failure(it, cacheWording) },
            actions = actions(download, playsWith),
            moreLabel = T.detail.moreActionsNamed(isolate(title)),
        )
    }

    /** "12.3 GB used · 40 GB free", or the used space alone. */
    fun storage(storage: DownloadStorage): String {
        val used = formatByteSize(storage.usedBytes)
        return storage.freeBytes?.let { T.downloads.storage(used, formatByteSize(it)) } ?: T.downloads.storageUsed(used)
    }

    /** A meta that stands in for [download]'s title when none was saved with it: its name, poster and own video. */
    fun minimalMeta(download: Download): MetaItem {
        val season = download.season
        val episode = download.episode
        val video = if (season != null && episode != null) {
            Video(id = download.videoId, title = download.episodeTitle.orEmpty(), thumbnail = download.thumbnail, seriesInfo = SeriesInfo(season, episode))
        } else {
            null
        }
        return MetaItem(MetaItemPreview(id = download.metaId, type = download.type, name = download.title, poster = download.poster), listOfNotNull(video))
    }

    private fun title(download: Download): String {
        val number = download.episode?.toString()
        val own = download.episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            own != null && number != null -> T.detail.numberedTitle(number, isolate(own))
            own != null -> own
            number != null -> T.detail.episodeNumbered(number)
            else -> download.title
        }
    }

    /**
     * "1.2 GB of 4.5 GB" until it is complete, else its size, then the speed while it downloads. The speed goes last:
     * it changes every second and goes when the transfer stops, so nothing else on the line moves with it, and a line
     * cut short loses it first.
     */
    private fun bytesText(download: Download): String? {
        val total = download.totalBytes?.takeIf { it > 0 }
        val bytes = when {
            total == null -> null
            download.status != DownloadStatus.COMPLETED && download.bytes > 0 -> T.downloads.progress(formatByteSize(download.bytes), formatByteSize(total))
            else -> formatByteSize(total)
        }
        val speed = download.bytesPerSecond?.takeIf { download.status == DownloadStatus.DOWNLOADING }?.let { T.downloads.speed(formatByteSize(it)) }
        return listOfNotNull(bytes, speed).reduceOrNull(T.detail::infoJoin)
    }

    private fun statusText(download: Download, online: Boolean): String {
        val text = T.downloads.status
        return when (download.status) {
            DownloadStatus.QUEUED -> when {
                !online -> text.waitingForNetwork
                download.waitingForWifi -> text.waitingForWifi
                else -> text.queued
            }
            DownloadStatus.RESOLVING -> text.resolving
            DownloadStatus.DOWNLOADING -> download.fraction?.let { text.downloadingPercent(DownloadText.percent(it)) } ?: text.downloading
            DownloadStatus.PAUSED -> text.paused
            DownloadStatus.FAILED -> text.failed
            DownloadStatus.COMPLETED -> text.completed
            DownloadStatus.CANCELLED -> text.cancelled
        }
    }

    /** The row's actions in menu order; "Play with…", a way to play, right after Play. */
    private fun actions(download: Download, playsWith: Boolean): List<DownloadActionItem> {
        val text = T.downloads.action
        fun item(action: DownloadAction, label: String, destructive: Boolean = false) = DownloadActionItem(action, label, destructive)
        val open = item(DownloadAction.OPEN_DETAIL, text.openDetail)
        return when (download.status) {
            DownloadStatus.COMPLETED -> listOfNotNull(
                item(DownloadAction.PLAY, text.play),
                item(DownloadAction.PLAY_WITH, T.playerApps.playWith).takeIf { playsWith },
                open,
                item(DownloadAction.DELETE, text.delete, destructive = true),
            )
            DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING ->
                listOf(item(DownloadAction.PAUSE, text.pause), item(DownloadAction.CANCEL, text.cancel, destructive = true), open)
            DownloadStatus.PAUSED -> listOf(item(DownloadAction.RESUME, text.resume), item(DownloadAction.CANCEL, text.cancel, destructive = true), open)
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> listOf(
                if (download.needsStream) item(DownloadAction.PICK_STREAM, text.pickStream) else item(DownloadAction.RETRY, text.retry),
                item(DownloadAction.DELETE, text.delete, destructive = true),
                open,
            )
        }
    }
}
