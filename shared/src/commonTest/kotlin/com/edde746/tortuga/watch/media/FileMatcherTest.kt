package com.edde746.tortuga.watch.media

import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import kotlinx.coroutines.test.runTest
import party.openwatch.Media
import party.openwatch.MediaKind
import party.openwatch.Series
import kotlin.test.Test
import kotlin.test.assertEquals

/** A guest's files of the room's media ([FileMatcher], the resolver's core) over fake addons. */
class FileMatcherTest {
    private val streams = addon("streams")

    private val host = FileFingerprint(HOUR, sizeBytes = 2 * GIB, videoHash = "8e245d9679d31e12")

    /** Chernobyl S1E2 as the host opened it through Cinemeta, with its TMDB id. */
    private val media = RoomMedia(
        type = "series",
        metaId = "tt7366338",
        videoId = "tt7366338:1:2",
        imdbId = "tt7366338",
        season = 1,
        episode = 2,
        tmdbId = "87108",
        title = "Chernobyl",
        episodeLine = "S1E2 · Please Remain Calm",
        fingerprint = host,
    )

    private fun title(media: RoomMedia) = RoomTitle(MetaItem(MetaItemPreview(id = media.metaId, type = media.type, name = media.title)), media.videoId)

    private suspend fun WatchMediaFixture.files(media: RoomMedia = this@FileMatcherTest.media): List<String> =
        matcher.files(media, title(media)).files.map { it.choice.path }

    @Test
    fun theHostsReleaseComesFirstAndStreamsThatAreNotReadyAreLeftOut() = runTest {
        val f = WatchMediaFixture(this, streams)
        f.streams(
            "streams", media.videoId,
            streamJson("other", name = "⚡ Addon A\n1080p"),
            streamJson("download", name = "[Not ready] Addon A\n1080p", hash = "8e245d9679d31e12"),
            streamJson("same", name = "⚡ Addon A\n2160p", hash = "8E245D9679D31E12"),
        )

        assertEquals(listOf("same", "other"), f.files())
    }

    @Test
    fun noFileWhenNoAddonOffersAPlayableStream() = runTest {
        val f = WatchMediaFixture(this, streams)
        f.streams("streams", media.videoId, """{"url":"rtmp://cdn.example/live","name":"1080p"}""")

        assertEquals(emptyList(), f.files())
        // The room's own ids, then its TMDB form (its IMDb form is its own request).
        assertEquals(listOf("streams stream/series/tt7366338:1:2", "streams stream/series/tmdb:87108:1:2"), f.requests)
    }

    @Test
    fun theMappedIdsAreAskedWhenNoAddonAnswersTheRoomsOwn() = runTest {
        val kitsu = addon("kitsu", "stream", "kitsu:")
        val cinemetaOnly = addon("streams", "stream", "tt")
        val tmdbOnly = addon("tmdbstreams", "stream", "tmdb:")
        val anime = RoomMedia(
            type = "series",
            metaId = "kitsu:1376",
            videoId = "kitsu:1376:5",
            imdbId = "tt0434665",
            season = 1,
            episode = 5,
            tmdbId = "30984",
            title = "Bleach",
            fingerprint = host,
        )
        val f = WatchMediaFixture(this, kitsu, cinemetaOnly, tmdbOnly)
        f.streams("tmdbstreams", "tmdb:30984:1:5", streamJson("tmdb"))

        assertEquals(listOf("tmdb"), f.files(anime))
        assertEquals(
            listOf("kitsu stream/series/kitsu:1376:5", "streams stream/series/tt0434665:1:5", "tmdbstreams stream/series/tmdb:30984:1:5"),
            f.requests,
        )

        // The IMDb form answering, the TMDB form isn't asked.
        f.streams("streams", "tt0434665:1:5", streamJson("imdb"))
        f.http.requests.clear()
        assertEquals(listOf("imdb"), f.files(anime))
        assertEquals(listOf("kitsu stream/series/kitsu:1376:5", "streams stream/series/tt0434665:1:5"), f.requests)
    }

    @Test
    fun aVideoWithoutMappedEpisodeIsAskedUnderItsOwnIdsOnly() = runTest {
        val f = WatchMediaFixture(this, streams)
        val unplaced = media.copy(metaId = "kitsu:1376", videoId = "kitsu:1376:5", season = null, episode = null)

        assertEquals(emptyList(), f.files(unplaced))
        assertEquals(listOf("streams stream/series/kitsu:1376:5"), f.requests)
    }

    @Test
    fun theProfilesAutoPlayAndLastLinkOrderTheFiles() = runTest {
        val f = WatchMediaFixture(this, streams)
        f.streams("streams", media.videoId, streamJson("a"), streamJson("b"), streamJson("c"))
        f.data.settings.set(PROFILE, ProfileSetting.StreamAutoPlay, AutoPlayMode.REUSE_LAST_LINK)
        f.data.streamHistory.record(PROFILE, media.metaId, media.videoId, streamJson("c"), streams.transportUrl)

        assertEquals(listOf("c", "a", "b"), f.files())
    }

    @Test
    fun theLastLinkIsTheOneOfTheIdsThatAnswered() = runTest {
        val cinemetaOnly = addon("streams", "stream", "tt")
        val f = WatchMediaFixture(this, cinemetaOnly)
        val anime = media.copy(type = "anime", metaId = "kitsu:1376", videoId = "kitsu:1376:5", imdbId = "tt0434665", season = 1, episode = 5, tmdbId = null)
        f.streams("streams", "tt0434665:1:5", streamJson("a"), streamJson("b"))
        f.data.streamHistory.record(PROFILE, "tt0434665", "tt0434665:1:5", streamJson("b"), cinemetaOnly.transportUrl)
        f.data.settings.set(PROFILE, ProfileSetting.StreamAutoPlay, AutoPlayMode.OFF)

        assertEquals(listOf("b", "a"), f.files(anime))
    }

    @Test
    fun anotherAppsEpisodeWithOnlyAnImdbIdFindsTheAddonsStreams() = runTest {
        val f = WatchMediaFixture(this, streams)
        f.streams("streams", "tt7366338:1:2", streamJson("a"))
        val plexEpisode = Media(
            kind = MediaKind.EPISODE,
            title = "Please Remain Calm",
            durationMs = HOUR,
            series = Series(title = "Chernobyl", year = 2019, ids = mapOf("imdb" to "tt7366338", "plex" to "plex://show/5d9c")),
            season = 1,
            episode = 2,
        )

        assertEquals(listOf("a"), f.files(roomMedia(plexEpisode)))
        assertEquals(listOf("streams stream/series/tt7366338:1:2"), f.requests)
    }

    @Test
    fun anotherAppsMovieWithOnlyATmdbIdFindsTheAddonsStreams() = runTest {
        val tmdbOnly = addon("tmdbstreams", "stream", "tmdb:")
        val f = WatchMediaFixture(this, tmdbOnly)
        f.streams("tmdbstreams", "tmdb:278", streamJson("a"))
        val movie = Media(kind = MediaKind.MOVIE, title = "The Shawshank Redemption", durationMs = HOUR, year = 1994, ids = mapOf("tmdb" to "278"))

        assertEquals(listOf("a"), f.files(roomMedia(movie)))
        assertEquals(listOf("tmdbstreams stream/movie/tmdb:278"), f.requests)
    }

    @Test
    fun aDescriptorWithoutAnIdAddonsKnowAsksNoAddon() = runTest {
        val f = WatchMediaFixture(this, streams)
        val homeVideo = Media(kind = MediaKind.VIDEO, title = "Holiday 2024", durationMs = HOUR)

        assertEquals(emptyList(), f.files(roomMedia(homeVideo)))
        assertEquals(emptyList(), f.requests)
    }
}
