package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.downloads.LocalSubtitle
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.playback.ErrorVideoSign
import com.edde746.tortuga.playback.resolve.ResolvedStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A download playing from the device ([PlayRequest.local]) and the next episode's download. */
class LocalPlaybackTest {
    /** The device's downloads: [videos] by meta and video id; what was reported playing in [events]. */
    private class FakeLocalVideos : LocalVideos {
        val videos = mutableMapOf<Pair<String, String>, LocalVideo>()
        val events = mutableListOf<String>()

        override suspend fun localVideo(metaId: String, videoId: String): LocalVideo? = videos[metaId to videoId]

        override fun playbackStarted(downloadId: Long) {
            events += "started $downloadId"
        }

        override fun playbackEnded(downloadId: Long) {
            events += "ended $downloadId"
        }
    }

    private fun local(id: Long, stream: StreamChoice, path: String) = LocalVideo(
        downloadId = id,
        path = path,
        size = 2_000_000L,
        videoHash = "0123456789abcdef",
        filename = "Movie.mkv",
        subtitles = listOf(LocalSubtitle("$path.en.srt", "English", "eng")),
        stream = stream,
    )

    @Test
    fun aDownloadPlaysItsFileWithItsSubtitlesAndTheHistoryKeepsItsStream() = runTest {
        val downloads = FakeLocalVideos()
        val h = SessionHarness(this, localVideos = downloads)
        val stream = choice(urlStream("https://host.example/movie.mkv", name = "Streams\n1080p"))
        val local = local(7, stream, "/data/Movies/Movie (1994)/Movie (1994) [abcd1234].mkv")

        h.start(localPlayRequest(MOVIE, MOVIE.id, local))
        assertEquals(listOf("started 7"), downloads.events)
        val load = h.engine.loads.single()
        assertEquals(local.path, load.url)
        assertEquals(emptyMap(), load.headers)
        assertFalse(h.session.info.value.resolving)
        assertEquals(true, h.session.info.value.stream?.downloaded)
        assertEquals(emptyList(), h.engine.addedSubtitles, "subtitles come once the file is open")

        h.engine.open(duration = 3600.0)
        h.settle()
        assertEquals(listOf(Triple<String, String?, String?>(local.subtitles.single().path, "English", "eng")), h.engine.addedSubtitles)
        val opened = assertNotNull(h.session.openedFile.value)
        assertEquals(stream, opened.choice)
        assertEquals(ResolvedStream(local.path, emptyMap(), 2_000_000L, "0123456789abcdef", "Movie.mkv"), opened.resolved)
        val remembered = h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id)
        assertEquals(stream.stream, remembered?.stream, "the original stream, never the path")
        assertEquals(stream.addonUrl, remembered?.transportUrl)
        assertTrue(h.http.requests.isEmpty(), "nothing resolves and no addon is asked for subtitles")

        h.engine.fail("the file is gone")
        h.settle()
        assertNotNull(h.session.info.value.failure, "no link to expire: the error is the failure")
        h.session.retry()
        h.settle()
        assertEquals(listOf(local.path, local.path), h.engine.loads.map { it.url }, "retry loads the file again")
        assertTrue(h.http.requests.isEmpty())

        h.session.close()
        h.settle()
        assertEquals(listOf("started 7", "ended 7"), downloads.events)
    }

    @Test
    fun aDownloadWhoseStreamCantBeReadPlaysItsFileAndTheHistoryKeepsWhatItHad() = runTest {
        val h = SessionHarness(this, localVideos = FakeLocalVideos())
        val before = AddonJson.encodeToString(Stream.serializer(), urlStream("https://addon.example/before"))
        h.data.streamHistory.record(h.profileId, MOVIE.id, MOVIE.id, before, STREAMS_URL)
        val path = "/data/Movies/Movie (1994)/Movie (1994) [abcd1234].mkv"
        // A completed download whose stored stream no longer decodes plays as its file, with no addon.
        val file = StreamChoice(Stream(StreamSource.Url(path)), addonUrl = "", addonName = "")

        h.start(localPlayRequest(MOVIE, MOVIE.id, local(7, file, path)))
        h.engine.open(duration = 3600.0)
        h.settle()

        assertEquals(listOf(path), h.engine.loads.map { it.url })
        assertNotNull(h.session.openedFile.value, "it plays")
        assertEquals(STREAMS_URL, h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id)?.transportUrl, "the history keeps what it had")
        h.session.close()
        h.settle()
    }

    /** A download has no link to fail: its file's end is the end, however short of the length it comes. */
    @Test
    fun aDownloadThatEndsShortOfItsLengthEndsThere() = runTest {
        val h = SessionHarness(this, localVideos = FakeLocalVideos())
        h.start(localPlayRequest(MOVIE, MOVIE.id, local(7, choice(urlStream("https://host.example/movie.mkv")), "/data/Movie.mkv")))
        h.engine.open(duration = 3600.0)
        h.settle()
        h.engine.report { it.copy(positionSeconds = 1200.0, eof = true, paused = true) }
        h.settle()
        assertTrue(h.session.engineState.value.eof)
        assertEquals(1, h.engine.loads.size)
        assertEquals(null, h.session.info.value.failure)
    }

    /** A download saved before downloads refused error videos: what its stream tells shows it as one. */
    @Test
    fun aDownloadedErrorVideoShowsAsOne() = runTest {
        val h = SessionHarness(this, localVideos = FakeLocalVideos())
        val sized = urlStream("https://host.example/movie.mkv").let { it.copy(behaviorHints = it.behaviorHints.copy(videoSize = 2_000_000_000L)) }
        h.start(localPlayRequest(MOVIE, MOVIE.id, local(7, choice(sized), "/data/Movie.mkv")))
        assertEquals(emptyList(), h.engine.loads, "the clip never loads")
        assertEquals(ErrorVideoSign.Size(2_000_000L, 2_000_000_000L), h.session.info.value.errorVideo)

        // Its length tells it where the library knows the video's.
        h.data.library.upsertFromMeta(h.profileId, MOVIE.librarySnapshot())
        h.data.library.recordProgress(h.profileId, MOVIE.id, MOVIE.id, 0, 7_200_000)
        h.start(localPlayRequest(MOVIE, MOVIE.id, local(8, choice(urlStream("https://host.example/movie.mkv")), "/data/Clip.mkv")))
        h.engine.open(duration = 60.0)
        h.settle()
        assertEquals(ErrorVideoSign.Duration(60.0, 7200.0), h.session.info.value.errorVideo)
    }

    @Test
    fun theNextEpisodePlaysFromItsDownloadWithoutAskingTheAddons() = runTest {
        val downloads = FakeLocalVideos()
        val h = SessionHarness(this, localVideos = downloads)
        val first = local(5, choice(urlStream("https://host.example/s01e01.mkv", bingeGroup = "group")), "/data/S01E01.mkv")
        val second = local(8, choice(urlStream("https://host.example/s01e02.mkv", bingeGroup = "group")), "/data/S01E02.mkv")
        downloads.videos["tt1" to "tt1:1:2"] = second

        h.start(localPlayRequest(SERIES, "tt1:1:1", first))
        h.engine.open(duration = 3600.0)
        h.settle()
        h.session.playNextEpisode()
        h.settle()
        assertEquals(second.path, h.engine.loads.last().url)
        assertEquals(true, h.session.info.value.stream?.downloaded)
        assertTrue(h.http.requests.isEmpty(), "the binge pick isn't needed")
        assertEquals(listOf("started 5", "ended 5", "started 8"), downloads.events)
    }
}
