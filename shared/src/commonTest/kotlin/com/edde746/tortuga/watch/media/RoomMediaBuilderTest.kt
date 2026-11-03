package com.edde746.tortuga.watch.media

import com.edde746.tortuga.model.ImdbEpisode
import com.edde746.tortuga.model.Link
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.ResolvedIds
import com.edde746.tortuga.trackers.TrackerIdResolver
import com.edde746.tortuga.trackers.TrackerItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The host's room media ([RoomMediaBuilder]): ids, mapped ids, title and the file's fingerprint. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMediaBuilderTest {
    private val chernobyl = MetaItem(
        MetaItemPreview(
            id = "tt7366338",
            type = "series",
            name = "Chernobyl",
            links = listOf(Link("TMDB", "share", "https://www.themoviedb.org/tv/87108-chernobyl")),
        ),
        videos = listOf(
            Video("tt7366338:1:1", "1:23:45", seriesInfo = SeriesInfo(1, 1)),
            Video("tt7366338:1:2", "Please Remain Calm", seriesInfo = SeriesInfo(1, 2)),
        ),
    )

    /** A Kitsu entry whose second season is its own entry: its episode 5 is IMDb's S2E5 where the addon says so. */
    private val bleach = MetaItem(
        MetaItemPreview(id = "kitsu:6686", type = "series", name = "Bleach: Sennen Kessen-hen - Ketsubetsu-tan", imdbId = "tt0434665"),
        videos = listOf(
            Video("kitsu:6686:5", "The Blade Is Me", seriesInfo = SeriesInfo(1, 5), imdb = ImdbEpisode("tt0434665", 2, 5)),
            Video("kitsu:6686:6", "Kill the Shadow", seriesInfo = SeriesInfo(1, 6)),
        ),
    )

    private val stream = Stream(
        StreamSource.Url("https://cdn.example/example-show-s01e02.mkv"),
        name = "⚡ Addon A\n4k HDR",
        description = "Example.Show.S01E02.2160p.WEB-DL.DDP5.1.HEVC\n💾 9.2 GB",
        behaviorHints = StreamBehaviorHints(filename = "Example.Show.S01E02.2160p.WEB-DL.DDP5.1.HEVC.mkv"),
    )
    private val choice = StreamChoice(stream, "https://addon.example/manifest.json", "Addon A")
    private val resolved = ResolvedStream("https://cdn.example/final.mkv", emptyMap(), size = 9_876_543_210, videoHash = "8e245d9679d31e12", filename = "final.mkv")

    /** Answers [ids] after [latency]; records what it was asked. */
    private class FakeResolver(private val ids: ResolvedIds?, private val latency: Duration = Duration.ZERO, private val failure: Exception? = null) : TrackerIdResolver {
        val asked = mutableListOf<Pair<String, Boolean>>()

        override suspend fun resolve(item: TrackerItem, animeIds: Boolean): ResolvedIds? {
            asked += item.videoId to animeIds
            delay(latency)
            failure?.let { throw it }
            return ids
        }
    }

    @Test
    fun aCinemetaEpisodeCarriesItsIdsTitleAndFile() = runTest {
        val media = RoomMediaBuilder(ids = null).build(chernobyl, "tt7366338:1:2", choice, resolved, durationMs = 3_970_123)

        assertEquals(
            RoomMedia(
                type = "series",
                metaId = "tt7366338",
                videoId = "tt7366338:1:2",
                imdbId = "tt7366338",
                season = 1,
                episode = 2,
                tmdbId = "87108",
                title = "Chernobyl",
                episodeLine = "S1E2 · \u2068Please Remain Calm\u2069",
                fingerprint = FileFingerprint(
                    durationMs = 3_970_123,
                    sizeBytes = 9_876_543_210,
                    videoHash = "8e245d9679d31e12",
                    filename = "Example.Show.S01E02.2160p.WEB-DL.DDP5.1.HEVC.mkv",
                    resolution = "4K",
                    source = "WEB-DL",
                    codec = "HEVC",
                ),
                episodeTitle = "Please Remain Calm",
            ),
            media,
        )
    }

    @Test
    fun withoutAResolvedStreamTheFingerprintComesFromTheStreamsHints() = runTest {
        val hinted = choice.copy(stream = stream.copy(behaviorHints = StreamBehaviorHints(videoHash = "0123456789abcdef", videoSize = 1234)))
        assertEquals(
            FileFingerprint(60_000, sizeBytes = 1234, videoHash = "0123456789abcdef", resolution = "4K", source = "WEB-DL", codec = "HEVC"),
            RoomMediaBuilder(ids = null).build(chernobyl, "tt7366338:1:1", hinted, resolved = null, durationMs = 60_000).fingerprint,
        )
        // Nothing but the text: the size it names is rounded, so no size is sent.
        assertNull(
            RoomMediaBuilder(ids = null).build(chernobyl, "tt7366338:1:1", choice.copy(stream = stream.copy(behaviorHints = StreamBehaviorHints())), null, 60_000)
                .fingerprint?.sizeBytes,
        )
    }

    @Test
    fun aTmdbEpisodeTakesTheMetasImdbId() = runTest {
        val meta = MetaItem(MetaItemPreview(id = "tmdb:87108", type = "series", name = "Chernobyl", imdbId = "tt7366338"))
        val media = RoomMediaBuilder(ids = null).build(meta, "tmdb:87108:1:3", choice, resolved, 1)
        assertEquals(listOf("tt7366338", "87108", 1, 3), listOf(media.imdbId, media.tmdbId, media.season, media.episode))
        assertEquals(null, media.episodeLine)
    }

    @Test
    fun anAnimeEpisodeTakesTheImdbEpisodeItsAddonNames() = runTest {
        val resolver = FakeResolver(ResolvedIds(ExternalIds(imdb = "tt9999999"), season = 9, episode = 9))
        val media = RoomMediaBuilder(resolver).build(bleach, "kitsu:6686:5", choice, resolved, 1)

        assertEquals(listOf("tt0434665", null, 2, 5), listOf(media.imdbId, media.tmdbId, media.season, media.episode))
        assertEquals("S1E5 · \u2068The Blade Is Me\u2069", media.episodeLine)
        assertEquals(emptyList(), resolver.asked)
    }

    @Test
    fun otherwiseAnAnimeEpisodeIsMappedThroughTheTrackersResolver() = runTest {
        val resolver = FakeResolver(ResolvedIds(ExternalIds(imdb = "tt0434665", tmdb = 30984), season = 2, episode = 6))
        val media = RoomMediaBuilder(resolver).build(bleach, "kitsu:6686:6", choice, resolved, 1)

        assertEquals(listOf("tt0434665", "30984", 2, 6), listOf(media.imdbId, media.tmdbId, media.season, media.episode))
        assertEquals(listOf("kitsu:6686:6" to true), resolver.asked)
    }

    @Test
    fun theResolverIsWaitedForBrieflyAndItsFailuresLeaveTheIdsOut() = runTest {
        val slow = FakeResolver(ResolvedIds(ExternalIds(imdb = "tt0434665"), season = 2, episode = 6), latency = 1.minutes)
        val media = RoomMediaBuilder(slow).build(bleach, "kitsu:6686:6", choice, resolved, 1)
        assertEquals(RoomMediaBuilder.ID_TIMEOUT.inWholeMilliseconds, currentTime)
        // The meta's title id still goes, without an episode.
        assertEquals(listOf("tt0434665", null, null, null), listOf(media.imdbId, media.tmdbId, media.season, media.episode))

        val failing = FakeResolver(null, failure = IllegalStateException("dataset download failed"))
        val failed = RoomMediaBuilder(failing).build(bleach, "kitsu:6686:6", choice, resolved, 1)
        assertEquals(listOf("tt0434665", null, null, null), listOf(failed.imdbId, failed.tmdbId, failed.season, failed.episode))

        // Without the trackers, the same.
        val untracked = RoomMediaBuilder(ids = null).build(bleach, "kitsu:6686:6", choice, resolved, 1)
        assertEquals(listOf("tt0434665", null, null, null), listOf(untracked.imdbId, untracked.tmdbId, untracked.season, untracked.episode))
    }

    @Test
    fun aResolverAnswerWithoutAnEpisodeMapsTheTitleOnly() = runTest {
        val resolver = FakeResolver(ResolvedIds(ExternalIds(imdb = "tt0434665", tmdb = 30984), season = null, episode = 6))
        val media = RoomMediaBuilder(resolver).build(bleach, "kitsu:6686:6", choice, resolved, 1)
        assertEquals(listOf("tt0434665", "30984", null, null), listOf(media.imdbId, media.tmdbId, media.season, media.episode))
    }

    @Test
    fun aMovieCarriesItsTitleIdsAndNoEpisode() = runTest {
        val movie = MetaItem(
            MetaItemPreview(
                id = "tt0111161",
                type = "movie",
                name = "The Shawshank Redemption",
                links = listOf(Link("TMDB", "share", "https://www.themoviedb.org/movie/278")),
            ),
        )
        val media = RoomMediaBuilder(ids = null).build(movie, "tt0111161", choice, resolved, 1)
        assertEquals(listOf("tt0111161", "278", null, null, null), listOf(media.imdbId, media.tmdbId, media.season, media.episode, media.episodeLine))
        assertEquals("The Shawshank Redemption", media.title)
    }

    @Test
    fun aVideoOfTheAddonsOwnNamingIsPlacedByItsSeasonAndEpisode() = runTest {
        val meta = chernobyl.copy(videos = listOf(Video("chernobyl-2", "Please Remain Calm", seriesInfo = SeriesInfo(1, 2))))
        val media = RoomMediaBuilder(ids = null).build(meta, "chernobyl-2", choice, resolved, 1)
        assertEquals(listOf("tt7366338", "87108", 1, 2), listOf(media.imdbId, media.tmdbId, media.season, media.episode))
    }

    @Test
    fun theRoomNamesTheYearADownloadsFolderDoes() = runTest {
        val released = Instant.parse("2019-05-06T00:00:00Z")
        for ((releaseInfo, date) in listOf("2019–2021" to null, "2019–2021" to Instant.parse("2018-01-01T00:00:00Z"), null to released)) {
            val meta = chernobyl.copy(preview = chernobyl.preview.copy(releaseInfo = releaseInfo, released = date))
            val media = RoomMediaBuilder(ids = null).build(meta, "tt7366338:1:2", choice, resolved, 1)
            // A downloads folder takes the same year (the preview's `year`).
            assertEquals(2019, media.year, "$releaseInfo $date")
        }
    }
}
