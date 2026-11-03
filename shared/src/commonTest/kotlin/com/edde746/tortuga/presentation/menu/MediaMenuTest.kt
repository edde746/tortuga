package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.downloads.DownloadFailure
import com.edde746.tortuga.downloads.DownloadFailureKind
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.board.ContinueWatchingCard
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.toCard
import com.edde746.tortuga.presentation.menu.MediaAction.ADD_TO_LIBRARY
import com.edde746.tortuga.presentation.menu.MediaAction.CANCEL_DOWNLOAD
import com.edde746.tortuga.presentation.menu.MediaAction.CHOOSE_STREAM
import com.edde746.tortuga.presentation.menu.MediaAction.DELETE_DOWNLOAD
import com.edde746.tortuga.presentation.menu.MediaAction.DETAILS
import com.edde746.tortuga.presentation.menu.MediaAction.DOWNLOAD
import com.edde746.tortuga.presentation.menu.MediaAction.MARK_SEASON_UNWATCHED
import com.edde746.tortuga.presentation.menu.MediaAction.MARK_SEASON_WATCHED
import com.edde746.tortuga.presentation.menu.MediaAction.MARK_UNWATCHED
import com.edde746.tortuga.presentation.menu.MediaAction.MARK_WATCHED
import com.edde746.tortuga.presentation.menu.MediaAction.PAUSE_DOWNLOAD
import com.edde746.tortuga.presentation.menu.MediaAction.PLAY_FROM_START
import com.edde746.tortuga.presentation.menu.MediaAction.REMOVE_FROM_CONTINUE_WATCHING
import com.edde746.tortuga.presentation.menu.MediaAction.REMOVE_FROM_LIBRARY
import com.edde746.tortuga.presentation.menu.MediaAction.RESUME_DOWNLOAD
import com.edde746.tortuga.presentation.menu.MediaAction.RETRY_DOWNLOAD
import com.edde746.tortuga.presentation.menu.MediaAction.TRAILER
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.edde746.tortuga.presentation.downloads.download as engineDownload

/** A media item's menu: the entries each kind of item offers, in order, and what they do to the library. */
class MediaMenuTest {
    private val data = testData(TestClock())

    @AfterTest
    fun close() = data.close()

    private val show = MetaItem(
        MetaItemPreview(id = "tt9", type = "series", name = "Show"),
        listOf(Video(id = "tt9:1:1", seriesInfo = SeriesInfo(1, 1)), Video(id = "tt9:1:2", seriesInfo = SeriesInfo(1, 2))),
    )

    /** The actions, the title's meta loading as [meta]; these tests carry them out themselves. */
    private fun actions(meta: MetaItem?) = MediaActions("p", data.library, trackerMarks = null, CoroutineScope(Job()), notice = {}) { _, _ -> meta }

    private fun List<MediaMenuEntry>.actions() = map { it.action }

    @Test
    fun aTitlesCardOffersItsMarkAStreamItsDownloadAndTrailerThenTheLibrary() {
        val movie = MetaCard("tt1", "movie", "One", null, null, null, PosterShape.POSTER, null)
        assertEquals(listOf(MARK_WATCHED, CHOOSE_STREAM, ADD_TO_LIBRARY), movie.menu.actions())
        assertEquals(listOf("Mark as watched", "Choose a stream", "Add to library"), movie.menu.map { it.label })
        assertEquals(listOf(MARK_UNWATCHED, CHOOSE_STREAM, REMOVE_FROM_LIBRARY), movie.copy(watched = true, inLibrary = true).menu.actions())
        assertEquals(
            listOf(MARK_WATCHED, CHOOSE_STREAM, DOWNLOAD, TRAILER, REMOVE_FROM_LIBRARY),
            movie.copy(inLibrary = true, download = MenuDownload.NONE, trailer = true).menu.actions(),
        )
        val series = movie.copy(id = "tt9", type = "series", watched = true)
        assertEquals(listOf(MARK_UNWATCHED, CHOOSE_STREAM, ADD_TO_LIBRARY), series.menu.actions(), "a series' poster offers what a movie's does")
        val channel = movie.copy(id = "yt1", type = "channel")
        assertEquals(listOf(MARK_WATCHED, ADD_TO_LIBRARY), channel.menu.actions(), "a channel's Play plays one of its videos, not the channel")
    }

    @Test
    fun anItemInProgressOffersEveryEntryInOrder() {
        val menu = mediaMenu(inLibrary = true, watched = true, inProgress = true, chooseStream = true, download = MenuDownload.NONE, trailer = true)
        assertEquals(listOf(PLAY_FROM_START, MARK_WATCHED, MARK_UNWATCHED, CHOOSE_STREAM, DOWNLOAD, TRAILER, REMOVE_FROM_LIBRARY), menu.actions())
        assertEquals(
            listOf("Play from the beginning", "Mark as watched", "Mark as unwatched", "Choose a stream", "Download", "Trailer", "Remove from library"),
            menu.map { it.label },
        )
        assertEquals(
            listOf(PLAY_FROM_START, MARK_WATCHED, CHOOSE_STREAM, DOWNLOAD, TRAILER, ADD_TO_LIBRARY),
            mediaMenu(inLibrary = false, watched = false, inProgress = true, chooseStream = true, download = MenuDownload.NONE, trailer = true).actions(),
            "never watched before: no mark to undo",
        )
    }

    @Test
    fun continueWatchingAddsRemovalAndDetailsAndAnItemInProgressCanStartOverOrBeFinished() {
        val card = ContinueWatchingCard("tt9", "series", "tt9:1:1", "Show", "S1E1 · 12 min left", 0.4f, null, PosterShape.POSTER, resumes = true)
        assertEquals(listOf(PLAY_FROM_START, MARK_WATCHED, REMOVE_FROM_CONTINUE_WATCHING, DETAILS, CHOOSE_STREAM, ADD_TO_LIBRARY), card.menu.actions())
        assertEquals(
            listOf("Play from the beginning", "Mark as watched", "Remove from Continue Watching", "Go to details", "Choose a stream", "Add to library"),
            card.menu.map { it.label },
        )
        assertEquals(
            listOf(
                PLAY_FROM_START, MARK_WATCHED, MARK_UNWATCHED, REMOVE_FROM_CONTINUE_WATCHING, DETAILS, CHOOSE_STREAM, DOWNLOAD,
                REMOVE_FROM_LIBRARY,
            ),
            card.copy(watched = true, inLibrary = true, download = MenuDownload.NONE).menu.actions(),
            "watched before and in progress again: finishing it and unmarking it; its video's download",
        )
        assertEquals(
            listOf(MARK_UNWATCHED, REMOVE_FROM_CONTINUE_WATCHING, DETAILS, CHOOSE_STREAM, ADD_TO_LIBRARY),
            card.copy(resumes = false, watched = true).menu.actions(),
            "its press doesn't resume it: nothing to start over, only its mark's undoing",
        )
    }

    @Test
    fun anEpisodeOffersItsMarksItsSeasonsAStreamItsDownloadAndItsTrailerButNoLibrary() {
        val episode = mediaMenu(
            inLibrary = null, watched = false, inProgress = true, seasonWatched = false, chooseStream = true, download = MenuDownload.PAUSED, trailer = true,
        )
        assertEquals(listOf(PLAY_FROM_START, MARK_WATCHED, MARK_SEASON_WATCHED, CHOOSE_STREAM, RESUME_DOWNLOAD, CANCEL_DOWNLOAD, TRAILER), episode.actions())
        assertEquals(
            listOf("Play from the beginning", "Mark as watched", "Mark season as watched", "Choose a stream", "Resume download", "Cancel download", "Trailer"),
            episode.map { it.label },
        )
        assertEquals(listOf(MARK_UNWATCHED, MARK_SEASON_UNWATCHED), mediaMenu(inLibrary = null, watched = true, seasonWatched = true).actions())
        assertEquals(
            listOf(PLAY_FROM_START, MARK_WATCHED, MARK_UNWATCHED),
            mediaMenu(inLibrary = null, watched = true, inProgress = true).actions(),
            "watched before and in progress again; no season, stream, download or trailer to offer",
        )
    }

    @Test
    fun aDownloadsEntriesFollowWhereItStands() {
        fun entries(download: MenuDownload?) = mediaMenu(inLibrary = null, watched = true, download = download).drop(1).map { it.action to it.label }
        assertEquals(listOf(DOWNLOAD to "Download"), entries(MenuDownload.NONE))
        assertEquals(listOf(PAUSE_DOWNLOAD to "Pause download", CANCEL_DOWNLOAD to "Cancel download"), entries(MenuDownload.ACTIVE))
        assertEquals(listOf(RESUME_DOWNLOAD to "Resume download", CANCEL_DOWNLOAD to "Cancel download"), entries(MenuDownload.PAUSED))
        assertEquals(listOf(RETRY_DOWNLOAD to "Retry download", DELETE_DOWNLOAD to "Delete download"), entries(MenuDownload.FAILED))
        assertEquals(listOf(DOWNLOAD to "Pick a stream", DELETE_DOWNLOAD to "Delete download"), entries(MenuDownload.NO_STREAM))
        assertEquals(listOf(DELETE_DOWNLOAD to "Delete download"), entries(MenuDownload.DOWNLOADED))
        assertEquals(emptyList(), entries(null), "where the device has no downloads")

        assertEquals(MenuDownload.NONE, menuDownload(null))
        assertEquals(MenuDownload.NONE, menuDownload(engineDownload(1, "tt1", DownloadStatus.CANCELLED)), "a cancelled download counts as none")
        assertEquals(MenuDownload.ACTIVE, menuDownload(engineDownload(1, "tt1", DownloadStatus.QUEUED)))
        assertEquals(MenuDownload.ACTIVE, menuDownload(engineDownload(1, "tt1", DownloadStatus.DOWNLOADING)))
        assertEquals(MenuDownload.PAUSED, menuDownload(engineDownload(1, "tt1", DownloadStatus.PAUSED)))
        val failed = engineDownload(1, "tt1", DownloadStatus.FAILED, failure = DownloadFailure(DownloadFailureKind.NETWORK))
        assertEquals(MenuDownload.FAILED, menuDownload(failed))
        val noStream = engineDownload(1, "tt1:1:1", DownloadStatus.FAILED, season = 1, episode = 1, failure = DownloadFailure(DownloadFailureKind.NO_STREAM))
        assertEquals(MenuDownload.NO_STREAM, menuDownload(noStream))
        assertEquals(MenuDownload.DOWNLOADED, menuDownload(engineDownload(1, "tt1", DownloadStatus.COMPLETED)))
    }

    @Test
    fun aCardsDownloadIsItsMoviesOrItsEpisodesAndNoneWithoutDownloads() {
        val marks: DownloadMarks = mapOf(
            "tt1" to mapOf("tt1" to MenuDownload.PAUSED),
            "tt2" to mapOf("tt2:default" to MenuDownload.DOWNLOADED),
            "tt9" to mapOf("tt9:1:2" to MenuDownload.DOWNLOADED, "tt9:1:1" to MenuDownload.FAILED),
        )
        assertEquals(MenuDownload.PAUSED, marks.menuDownload("movie", "tt1", videoId = null))
        assertEquals(MenuDownload.DOWNLOADED, marks.menuDownload("movie", "tt2", videoId = null), "a movie downloaded under its default video")
        assertEquals(MenuDownload.NONE, marks.menuDownload("movie", "tt3", videoId = null), "nothing downloaded: Download")
        assertEquals(MenuDownload.DOWNLOADED, marks.menuDownload("series", "tt9", "tt9:1:2"), "a Continue Watching card's episode")
        assertEquals(MenuDownload.NONE, marks.menuDownload("series", "tt9", "tt9:1:3"))
        assertNull(marks.menuDownload("series", "tt9", videoId = null), "a series card doesn't know the episode Detail's Play would play")
        assertNull(marks.menuDownload("channel", "yt1", "yt1:v1"))
        val none: DownloadMarks? = null
        assertNull(none.menuDownload("movie", "tt1", videoId = null), "a device without downloads")
    }

    @Test
    fun aCatalogItemOffersItsTrailerOnlyWhereItNamesOneThisAppOpens() {
        val item = MetaItemPreview(id = "tt1", type = "movie", name = "One")
        fun offersTrailer(vararg streams: Stream) = TRAILER in item.copy(trailerStreams = streams.toList()).toCard().menu.actions()
        val unplayable = Stream(StreamSource.Url("rtmp://cdn.example/live/trailer"))
        assertFalse(offersTrailer(), "none named: nothing is asked of the network for one")
        assertFalse(offersTrailer(unplayable))
        assertTrue(offersTrailer(Stream(StreamSource.YouTube("abc"))))
        assertTrue(offersTrailer(unplayable, Stream(StreamSource.Url("https://cdn.example/trailer.mp4"))))
    }

    @Test
    fun aTitlesEntriesChangeItsPlaceInTheLibrary() = runTest {
        val actions = actions(meta = null)
        val title = MetaSnapshot("tt1", "movie", "One", null, "poster")
        suspend fun mark() = data.library.observeMarks("p").first()["tt1"]

        actions.carryOut(title, videoId = null, ADD_TO_LIBRARY)
        assertEquals(TitleMark(inLibrary = true, watched = false), mark())
        actions.carryOut(title, videoId = null, MARK_WATCHED)
        assertEquals(TitleMark(inLibrary = true, watched = true), mark())
        actions.carryOut(title, videoId = null, REMOVE_FROM_LIBRARY)
        assertEquals(TitleMark(inLibrary = false, watched = true), mark(), "out of the library, still watched")
        actions.carryOut(title, videoId = null, MARK_UNWATCHED)
        assertEquals(null, mark(), "neither in the library nor watched")
    }

    @Test
    fun aSeriesMarkIsEveryEpisodesAsOnItsDetailScreen() = runTest {
        val title = MetaSnapshot("tt9", "series", "Show", null, "poster")
        suspend fun watched() = WatchedBitField.of(data.library.item("p", "tt9")?.state?.watchedVideos, show)
            .let { marks -> show.videos.map { marks.isWatched(it.id) } }

        assertEquals(T.mediaMenu.markFailed(isolate("Show")), actions(meta = null).carryOut(title, videoId = null, MARK_WATCHED), "without the series' meta the screen says so")
        assertEquals(null, data.library.item("p", "tt9"), "and nothing is marked")
        assertNull(actions(show).carryOut(title, videoId = null, MARK_WATCHED))
        assertEquals(listOf(true, true), watched())
        assertEquals(TitleMark(inLibrary = false, watched = true), data.library.observeMarks("p").first()["tt9"])
        actions(show).carryOut(title, videoId = null, MARK_UNWATCHED)
        assertEquals(listOf(false, false), watched())
    }

    @Test
    fun aContinueWatchingEpisodesMarksAreTheEpisodes() = runTest {
        val title = MetaSnapshot("tt9", "series", "Show", null, "poster")
        data.library.upsertFromMeta("p", title)
        data.library.recordProgress("p", "tt9", "tt9:1:1", 600_000, 2_400_000)
        suspend fun watched(videoId: String) =
            WatchedBitField.of(data.library.item("p", "tt9")?.state?.watchedVideos, show).isWatched(videoId)

        assertEquals(T.mediaMenu.markFailed(isolate("Show")), actions(meta = null).carryOut(title, "tt9:1:1", MARK_WATCHED), "without the title's meta the screen says so")
        assertFalse(watched("tt9:1:1"), "and nothing is marked")
        actions(show).carryOut(title, "tt9:1:1", MARK_WATCHED)
        assertTrue(watched("tt9:1:1"))
        assertFalse(watched("tt9:1:2"))
        actions(show).carryOut(title, "tt9:1:1", MARK_UNWATCHED)
        assertFalse(watched("tt9:1:1"))
    }
}
