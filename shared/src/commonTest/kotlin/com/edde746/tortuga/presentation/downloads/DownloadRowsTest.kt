package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadFailure
import com.edde746.tortuga.downloads.DownloadFailureKind
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.DownloadStorage
import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A download as the engine reports it: an episode of Chernobyl (`tt1`) when [episode] is set, else a movie. */
internal fun download(
    id: Long,
    videoId: String,
    status: DownloadStatus,
    season: Int? = null,
    episode: Int? = null,
    bytes: Long = 0,
    total: Long? = null,
    failure: DownloadFailure? = null,
    waitingForWifi: Boolean = false,
    speed: Long? = null,
): Download {
    val isEpisode = episode != null
    return Download(
        id = id,
        type = if (isEpisode) "series" else "movie",
        metaId = if (isEpisode) "tt1" else videoId,
        videoId = videoId,
        title = if (isEpisode) "Chernobyl" else "The Shawshank Redemption",
        episodeTitle = episode?.let { "Part $it" },
        season = season,
        episode = episode,
        poster = "file:///artwork/poster.jpg",
        thumbnail = null,
        status = status,
        failure = failure,
        bytes = bytes,
        totalBytes = total,
        bytesPerSecond = speed,
        waitingForWifi = waitingForWifi,
        addedAt = id,
        completedAt = null,
        owners = setOf("p"),
        keepSyncedEpisode = false,
    )
}

class DownloadRowsTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun seriesGroupBySeasonAndEpisodeAndMoviesStandAlone() {
        val downloads = listOf(
            download(4, "tt1:1:2", DownloadStatus.COMPLETED, season = 1, episode = 2, bytes = gib, total = gib),
            download(3, "tt2", DownloadStatus.COMPLETED, bytes = 2 * gib, total = 2 * gib),
            download(2, "tt1:0:1", DownloadStatus.COMPLETED, season = 0, episode = 1, bytes = gib, total = gib),
            download(1, "tt1:1:1", DownloadStatus.COMPLETED, season = 1, episode = 1, bytes = gib, total = gib),
        )
        val groups = DownloadRows.groups(downloads, online = true, cacheWording = true, playsWith = false)
        assertEquals(listOf("tt1", "tt2"), groups.map { it.key }, "the title with the latest download first")
        val series = groups[0]
        assertEquals(T.downloads.summary(T.downloads.episodes(3), formatByteSize(3 * gib)), series.summary)
        assertEquals(listOf(1, 0), series.seasons.map { it.season }, "specials last")
        assertEquals(listOf(T.detail.season("1"), T.detail.specials), series.seasons.map { it.title })
        assertEquals(listOf("1. \u2068Part 1\u2069", "2. \u2068Part 2\u2069"), series.seasons[0].episodes.map { it.title }, "the episode's title isolated")
        assertEquals(listOf("S1E1", "S1E2"), series.seasons[0].episodes.map { it.subtitle })
        assertNull(series.movie)
        val movie = groups[1]
        assertEquals("The Shawshank Redemption", movie.movie?.title)
        assertEquals(formatByteSize(2 * gib), movie.summary)
        assertEquals(
            listOf(DownloadAction.PLAY, DownloadAction.OPEN_DETAIL, DownloadAction.DELETE),
            movie.movie?.actions?.map { it.action },
        )
    }

    @Test
    fun everyDownloadIsARowWithOrWithoutAnEpisodeNumber() {
        // A series' video without an episode number comes after its seasons; the summary counts it.
        val extra = download(2, "tt1:extra", DownloadStatus.COMPLETED, bytes = gib, total = gib)
            .copy(type = "series", metaId = "tt1", title = "Chernobyl", episodeTitle = "Inside Chernobyl")
        val episode = download(1, "tt1:1:1", DownloadStatus.COMPLETED, season = 1, episode = 1, bytes = gib, total = gib)
        val series = DownloadRows.groups(listOf(extra, episode), online = true, cacheWording = true, playsWith = false).single()
        assertEquals(listOf("tt1:1:1"), series.seasons.single().episodes.map { it.videoId })
        assertEquals(listOf("tt1:extra"), series.videos.map { it.videoId })
        assertEquals("Inside Chernobyl", series.videos.single().title)
        assertNull(series.movie)
        assertEquals(T.downloads.summary(T.downloads.videos(2), formatByteSize(2 * gib)), series.summary)

        // A title with two downloads, neither numbered: both rows, newest first, under its heading.
        val newer = download(4, "tt5:b", DownloadStatus.COMPLETED).copy(metaId = "tt5")
        val older = download(3, "tt5:a", DownloadStatus.COMPLETED).copy(metaId = "tt5")
        val title = DownloadRows.groups(listOf(newer, older), online = true, cacheWording = true, playsWith = false).single()
        assertEquals(listOf("tt5:b", "tt5:a"), title.videos.map { it.videoId })
        assertEquals(emptyList(), title.seasons)
        assertNull(title.movie)
    }

    @Test
    fun rowsSayWhereEachDownloadIs() {
        val moving = row(download(1, "tt2", DownloadStatus.DOWNLOADING, bytes = 450, total = 1000, speed = 2048))
        assertEquals(T.downloads.status.downloadingPercent("45"), moving.statusText)
        assertEquals(0.45, moving.progress)
        assertEquals(listOf(DownloadAction.PAUSE, DownloadAction.CANCEL, DownloadAction.OPEN_DETAIL), moving.actions.map { it.action })

        val waiting = download(2, "tt3", DownloadStatus.QUEUED, waitingForWifi = true)
        assertEquals(T.downloads.status.waitingForWifi, row(waiting).statusText)
        assertEquals(T.downloads.status.waitingForNetwork, DownloadRows.row(waiting, online = false, cacheWording = true, playsWith = false).statusText)

        val noStream = download(3, "tt1:1:3", DownloadStatus.FAILED, season = 1, episode = 3, failure = DownloadFailure(DownloadFailureKind.NO_STREAM))
        val failed = row(noStream)
        assertEquals(T.downloads.failure.noStreamInstant, failed.failureText)
        assertEquals(T.downloads.failure.noStream, DownloadRows.row(noStream, online = true, cacheWording = false, playsWith = false).failureText, "no cache wording on Apple")
        assertEquals(listOf(DownloadAction.PICK_STREAM, DownloadAction.DELETE, DownloadAction.OPEN_DETAIL), failed.actions.map { it.action })

        val http = download(4, "tt4", DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureKind.HTTP, 410))
        assertEquals(T.downloads.failure.http("410"), row(http).failureText)
        assertEquals(DownloadAction.RETRY, row(http).actions.first().action)
    }

    /**
     * The line after the status: the bytes so far of the size, then the speed while it downloads (it changes every
     * second, so it goes last); the bytes alone while paused; the size once complete; nothing while neither is known.
     */
    @Test
    fun theBytesComeBeforeTheSpeed() {
        val progress = T.downloads.progress(formatByteSize(450), formatByteSize(1000))
        assertEquals(
            T.detail.infoJoin(progress, T.downloads.speed(formatByteSize(2048))),
            row(download(1, "tt2", DownloadStatus.DOWNLOADING, bytes = 450, total = 1000, speed = 2048)).bytesText,
        )
        assertEquals(progress, row(download(1, "tt2", DownloadStatus.PAUSED, bytes = 450, total = 1000)).bytesText)
        assertEquals(formatByteSize(1000), row(download(1, "tt2", DownloadStatus.COMPLETED, bytes = 1000, total = 1000)).bytesText)
        assertEquals(formatByteSize(1000), row(download(1, "tt2", DownloadStatus.QUEUED, total = 1000)).bytesText, "no bytes yet: the size")
        assertNull(row(download(1, "tt2", DownloadStatus.QUEUED)).bytesText)
    }

    /**
     * Where this device has player apps, a completed download's "Play with…" comes right after Play, before Go to
     * details and Delete.
     */
    @Test
    fun playWithComesRightAfterPlay() {
        val done = download(1, "tt2", DownloadStatus.COMPLETED, bytes = 1000, total = 1000)
        val withPlayers = DownloadRows.row(done, online = true, cacheWording = true, playsWith = true).actions
        assertEquals(listOf(DownloadAction.PLAY, DownloadAction.PLAY_WITH, DownloadAction.OPEN_DETAIL, DownloadAction.DELETE), withPlayers.map { it.action })
        assertEquals(T.playerApps.playWith, withPlayers[1].label)
        assertEquals(listOf(DownloadAction.PLAY, DownloadAction.OPEN_DETAIL, DownloadAction.DELETE), row(done).actions.map { it.action }, "no player apps")
        val moving = download(2, "tt3", DownloadStatus.DOWNLOADING, bytes = 450, total = 1000)
        assertEquals(
            listOf(DownloadAction.PAUSE, DownloadAction.CANCEL, DownloadAction.OPEN_DETAIL),
            DownloadRows.row(moving, online = true, cacheWording = true, playsWith = true).actions.map { it.action },
            "nothing to play yet",
        )
    }

    @Test
    fun storageSaysWhatIsUsedAndFree() {
        assertEquals(T.downloads.storage(formatByteSize(gib), formatByteSize(4 * gib)), DownloadRows.storage(DownloadStorage(gib, 4 * gib)))
        assertEquals(T.downloads.storageUsed(formatByteSize(gib)), DownloadRows.storage(DownloadStorage(gib, freeBytes = null)))
    }

    /** [download]'s row online, in a build with cache wording, on a device without player apps. */
    private fun row(download: Download) = DownloadRows.row(download, online = true, cacheWording = true, playsWith = false)
}
