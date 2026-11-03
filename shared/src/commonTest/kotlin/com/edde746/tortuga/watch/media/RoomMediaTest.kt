package com.edde746.tortuga.watch.media

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import party.openwatch.Media
import party.openwatch.MediaFile
import party.openwatch.MediaKind
import party.openwatch.Series
import party.openwatch.checkMedia
import party.openwatch.parseMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A room's media as an Open Watch Party descriptor ([toMedia]) and a descriptor from any app as a room's media ([roomMedia]). */
class RoomMediaTest {
    private val episode = RoomMedia(
        type = "series",
        metaId = "tt7366338",
        videoId = "tt7366338:1:2",
        imdbId = "tt7366338",
        season = 1,
        episode = 2,
        tmdbId = "87108",
        title = "Chernobyl",
        episodeLine = "S1E2 · Please Remain Calm",
        fingerprint = FileFingerprint(3_970_123, sizeBytes = 9_876_543_210, videoHash = "8e245d9679d31e12", filename = "Chernobyl.S01E02.mkv", resolution = "4K"),
        year = 2019,
        episodeTitle = "Please Remain Calm",
    )

    @Test
    fun anEpisodeSendsItsSeriesIdsFileAndTortugasOwnIds() {
        val media = episode.toMedia()

        assertEquals(
            Media(
                kind = MediaKind.EPISODE,
                title = "Please Remain Calm",
                durationMs = 3_970_123,
                series = Series("Chernobyl", 2019, mapOf("imdb" to "tt7366338", "tmdb" to "87108")),
                season = 1,
                episode = 2,
                file = MediaFile(sizeBytes = 9_876_543_210, oshash = "8e245d9679d31e12", name = "Chernobyl.S01E02.mkv"),
                ext = buildJsonObject {
                    put("app.usetortuga", buildJsonObject { put("ty", "series"); put("mi", "tt7366338"); put("vi", "tt7366338:1:2") })
                },
            ),
            media,
        )
        assertNull(checkMedia(media))
    }

    @Test
    fun aTortugaDescriptorReadsBackAsTheHostsOwnRequest() {
        val anime = episode.copy(type = "anime", metaId = "kitsu:6686", videoId = "kitsu:6686:5", season = 2, episode = 5, tmdbId = null)

        val read = roomMedia(parseMedia(anime.toMedia().toJson()))

        assertEquals(listOf("anime", "kitsu:6686", "kitsu:6686:5", "tt7366338", 2, 5), listOf(read.type, read.metaId, read.videoId, read.imdbId, read.season, read.episode))
        // The host's badges stay with the host; the file's hints travel.
        assertEquals(FileFingerprint(3_970_123, sizeBytes = 9_876_543_210, videoHash = "8e245d9679d31e12", filename = "Chernobyl.S01E02.mkv"), read.fingerprint)
    }

    @Test
    fun aMovieSendsItsOwnIdsAndYear() {
        val movie = RoomMedia(
            type = "movie",
            metaId = "tt0111161",
            videoId = "tt0111161",
            imdbId = "tt0111161",
            tmdbId = "278",
            title = "The Shawshank Redemption",
            fingerprint = FileFingerprint(HOUR),
            year = 1994,
        )

        val media = movie.toMedia()

        assertEquals(MediaKind.MOVIE, media.kind)
        assertEquals(listOf("The Shawshank Redemption", 1994L, null), listOf(media.title, media.year, media.series))
        assertEquals(mapOf("imdb" to "tt0111161", "tmdb" to "278"), media.ids)
    }

    @Test
    fun aSeriesTmdbIdIsntSentAsAMovies() {
        // A whole series played as one title (no video of its own): sent as a movie, so its TV id would be wrong there.
        val whole = RoomMedia(type = "series", metaId = "tmdb:87108", videoId = "tmdb:87108", tmdbId = "87108", title = "Chernobyl", fingerprint = FileFingerprint(HOUR))

        val media = whole.toMedia()

        assertEquals(MediaKind.MOVIE, media.kind)
        assertEquals(emptyMap(), media.ids)
    }

    @Test
    fun titlesWithLineBreaksAndBidiMarksAreSentClean() {
        // An addon's titles as they may come: a line break, a right-to-left mark, a lone surrogate.
        val messy = episode.copy(title = "Cherno\u200Fbyl\n(2019)", episodeTitle = "\u202BPlease\tRemain\uD800 Calm")

        val media = messy.toMedia()

        assertNull(checkMedia(media))
        assertEquals("Chernobyl (2019)", media.series?.title)
        assertEquals("Please Remain Calm", media.title)
    }

    @Test
    fun ownIdsThatLocateMediaAndPathsInFileNamesStayHome() {
        val url = episode.copy(
            videoId = "https://addon.example/stream/tt7366338:1:2",
            fingerprint = episode.fingerprint?.copy(filename = "/downloads/Chernobyl.S01E02.mkv"),
        )

        val media = url.toMedia()

        assertNull(media.ext)
        assertEquals("Chernobyl.S01E02.mkv", media.file?.name)
        assertNull(checkMedia(media))
    }

    @Test
    fun anotherAppsEpisodeWithOnlyAnImdbIdBecomesCinemetasIds() {
        val plex = Media(
            kind = MediaKind.EPISODE,
            title = "Please Remain Calm",
            durationMs = HOUR,
            series = Series("Chernobyl", 2019, mapOf("imdb" to "tt7366338", "plex" to "plex://show/5d9c")),
            season = 1,
            episode = 2,
            ext = buildJsonObject { put("app.plezy", buildJsonObject { put("item", "4711") }) },
        )

        val read = roomMedia(plex)

        assertEquals(listOf("series", "tt7366338", "tt7366338:1:2", "tt7366338", null), listOf(read.type, read.metaId, read.videoId, read.imdbId, read.tmdbId))
        assertEquals(listOf("Chernobyl", "S1E2 · \u2068Please Remain Calm\u2069", 2019), listOf(read.title, read.episodeLine, read.year))
    }

    @Test
    fun aTmdbOnlyMovieBecomesATmdbIdAndAVideoWithoutIdsAsksNoAddon() {
        val tmdb = roomMedia(Media(kind = MediaKind.MOVIE, title = "The Shawshank Redemption", durationMs = HOUR, ids = mapOf("tmdb" to "278")))
        assertEquals(listOf("movie", "tmdb:278", "tmdb:278", "278"), listOf(tmdb.type, tmdb.metaId, tmdb.videoId, tmdb.tmdbId))

        val home = roomMedia(Media(kind = MediaKind.VIDEO, title = "Holiday 2024", durationMs = HOUR))
        assertEquals("", home.metaId)
    }

    @Test
    fun aForgedTortugaExtThatIsntThreeStringsIsIgnored() {
        val forged = Media(
            kind = MediaKind.MOVIE,
            title = "Heat",
            durationMs = HOUR,
            ids = mapOf("imdb" to "tt0113277"),
            ext = buildJsonObject { put("app.usetortuga", buildJsonObject { put("ty", "movie"); put("mi", 7) }) },
        )

        val read = roomMedia(forged)

        assertEquals(listOf("movie", "tt0113277", "tt0113277"), listOf(read.type, read.metaId, read.videoId))
    }
}
