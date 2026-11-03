package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.downloads.DownloadFailure
import com.edde746.tortuga.downloads.DownloadFailureKind
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.KeepSynced
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.downloads.download
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Detail's download button and menus. */
class DetailDownloadsTest {
    private val movie = MetaItem(MetaItemPreview(id = "tt2", type = "movie", name = "The Shawshank Redemption"))

    /** Six episodes, all out. */
    private val series = MetaItem(
        MetaItemPreview(id = "tt1", type = "series", name = "Chernobyl"),
        videos = (1..5).map { Video("tt1:1:$it", seriesInfo = SeriesInfo(1, it)) } + Video("tt1:2:1", seriesInfo = SeriesInfo(2, 1)),
    )

    private fun movieButton(status: DownloadStatus?, failure: DownloadFailure? = null, bytes: Long = 0, total: Long? = null) =
        DetailDownloads.title(movie, listOfNotNull(status?.let { download(1, "tt2", it, bytes = bytes, total = total, failure = failure) }), null, null, outEpisodes = 0)

    @Test
    fun aMoviesButtonFollowsItsDownload() {
        var title = movieButton(null)
        assertEquals(DetailDownloadState.AVAILABLE, title.download.state)
        assertEquals(T.detail.download.download, title.download.label)
        assertEquals(DetailDownloads.DOWNLOAD, title.press, "Download picks the stream at once")
        assertFalse(title.download.opensMenu)

        title = movieButton(DownloadStatus.DOWNLOADING, bytes = 450, total = 1000)
        assertEquals(T.detail.download.downloading("45"), title.download.label)
        assertEquals(0.45, title.download.progress)
        assertTrue(title.download.opensMenu)
        assertEquals(listOf(DetailDownloads.PAUSE, DetailDownloads.CANCEL), title.download.menu.single().entries.map { it.id })

        assertEquals(DetailDownloads.RESUME, movieButton(DownloadStatus.PAUSED).press)
        assertEquals(DetailDownloads.RETRY, movieButton(DownloadStatus.FAILED, DownloadFailure(DownloadFailureKind.NETWORK)).press)
        assertEquals(DetailDownloads.PICK_STREAM, movieButton(DownloadStatus.FAILED, DownloadFailure(DownloadFailureKind.NO_STREAM)).press)

        title = movieButton(DownloadStatus.COMPLETED)
        assertEquals(DetailDownloadState.DOWNLOADED, title.download.state)
        assertNull(title.press)
        assertEquals(listOf(DetailDownloadEntry(DetailDownloads.DELETE, T.detail.download.episode.delete, destructive = true)), title.download.menu.single().entries)
        assertEquals(DetailDownloadState.AVAILABLE, movieButton(DownloadStatus.CANCELLED).download.state)
    }

    @Test
    fun aSeriesShowsWhatMovesFirstThenWhatWaitsThenWhatIsDone() {
        fun button(vararg statuses: DownloadStatus) = DetailDownloads.title(
            series,
            statuses.mapIndexed { i, status -> download(i + 1L, "tt1:1:${i + 1}", status, season = 1, episode = i + 1, bytes = 100, total = 400) },
            rule = null,
            season = 1,
            outEpisodes = 6,
        )
        var title = button(DownloadStatus.COMPLETED, DownloadStatus.COMPLETED)
        assertEquals(DetailDownloadState.PARTIAL, title.download.state)
        assertEquals(T.detail.download.partial("2", "6"), title.download.label)
        assertTrue(title.download.opensMenu)

        title = button(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PAUSED)
        assertEquals(DetailDownloadState.DOWNLOADING, title.download.state)
        assertEquals(0.25, title.download.progress, "what is still to come, together")

        assertEquals(DetailDownloadState.QUEUED, button(DownloadStatus.FAILED, DownloadStatus.QUEUED).download.state)
        title = button(DownloadStatus.FAILED, DownloadStatus.PAUSED)
        assertEquals(DetailDownloadState.PAUSED, title.download.state)
        assertEquals(DetailDownloads.RESUME_ALL, title.press)
        assertEquals(DetailDownloadState.FAILED, button(DownloadStatus.FAILED, DownloadStatus.COMPLETED).download.state)
        assertEquals(DetailDownloadState.DOWNLOADED, button(*Array(6) { DownloadStatus.COMPLETED }).download.state)
        assertEquals(DetailDownloadState.AVAILABLE, button().download.state)
    }

    @Test
    fun aSeriesMenuOffersTheSeasonTheSeriesKeepSyncedAndManaging() {
        val downloads = listOf(download(1, "tt1:1:1", DownloadStatus.COMPLETED, season = 1, episode = 1))
        val menu = DetailDownloads.title(series, downloads, KeepSynced(5), season = 1, outEpisodes = 6).download.menu
        assertEquals(
            listOf(T.detail.season("1"), T.detail.download.allSeasons, T.detail.download.keepSynced, T.detail.download.manage),
            menu.map { it.title },
        )
        assertEquals(listOf("season:ALL", "season:UNWATCHED", "season:NEXT_5", "season:NEXT_10"), menu[0].entries.map { it.id })
        assertEquals(listOf("series:ALL", "series:UNWATCHED", "series:NEXT_5", "series:NEXT_10"), menu[1].entries.map { it.id })
        assertEquals(listOf("sync:1", "sync:3", "sync:5", "sync:10", "sync:all", DetailDownloads.SYNC_RULES), menu[2].entries.map { it.id })
        assertEquals(T.downloads.keepSynced.manage, menu[2].entries.last().label)
        assertEquals(listOf("sync:5"), menu[2].entries.filter { it.selected }.map { it.id })
        assertEquals(listOf(DetailDownloads.DELETE_SEASON, DetailDownloads.DELETE_ALL), menu[3].entries.map { it.id })
    }

    @Test
    fun theKeepSyncedScreenIsOfferedWithoutARuleToo() {
        // The screen lists the profile's other rules, so it is offered without a rule for this series too.
        val entries = DetailDownloads.title(series, emptyList(), null, season = 1, outEpisodes = 6).download.menu.single { it.title == T.detail.download.keepSynced }.entries
        assertEquals(DetailDownloads.SYNC_RULES, entries.last().id)
    }

    @Test
    fun anEpisodeSaysWhereItsDownloadIs() {
        val moving = DetailDownloads.episode(download(1, "tt1:1:1", DownloadStatus.DOWNLOADING, season = 1, episode = 1, bytes = 1, total = 4))
        assertEquals(T.detail.download.episode.downloading("25"), moving.label)
        val none = DetailDownloads.episode(null)
        assertNull(none.label)
        assertEquals(T.detail.download.episode.downloaded, DetailDownloads.episode(download(2, "tt1:1:2", DownloadStatus.COMPLETED, season = 1, episode = 2)).label)
    }
}
