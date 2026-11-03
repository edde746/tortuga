package com.edde746.tortuga.watch.media

import com.edde746.tortuga.model.ImdbEpisode
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Video
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A guest's meta for the room's media ([RoomTitleResolver]): its own, under the IMDb id, or the room's. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomTitleResolverTest {
    private val chernobyl = RoomMedia(
        type = "series",
        metaId = "tt7366338",
        videoId = "tt7366338:1:2",
        imdbId = "tt7366338",
        season = 1,
        episode = 2,
        title = "Chernobyl",
        episodeLine = "S1E2 · Please Remain Calm",
    )

    /** A Kitsu episode whose IMDb episode is S2E5. */
    private val bleach = RoomMedia(
        type = "series",
        metaId = "kitsu:6686",
        videoId = "kitsu:6686:5",
        imdbId = "tt0434665",
        season = 2,
        episode = 5,
        tmdbId = "30984",
        title = "Bleach: Sennen Kessen-hen - Ketsubetsu-tan",
        episodeLine = "S1E5 · The Blade Is Me",
    )

    private val bleachOnImdb =
        """{"id":"tt0434665","type":"series","name":"Bleach","videos":[
            {"id":"tt0434665:1:1","title":"A Shinigami Is Born","season":1,"episode":1},
            {"id":"tt0434665:2:5","title":"The Blade Is Me","season":2,"episode":5}
        ]}"""

    @Test
    fun theRoomsOwnMetaComesFromTheFirstAddonInTheInstalledOrder() = runTest {
        val f = WatchMediaFixture(this, addon("broken", "meta"), addon("slow", "meta"), addon("fast", "meta"))
        f.meta("slow", "tt7366338", """{"id":"tt7366338","type":"series","name":"Chernobyl (slow)"}""")
        f.meta("fast", "tt7366338", """{"id":"tt7366338","type":"series","name":"Chernobyl (fast)"}""")
        f.latency["slow"] = 3.seconds

        val title = f.titles.resolve(chernobyl)

        assertEquals("Chernobyl (slow)", title.meta.name)
        assertEquals("tt7366338:1:2", title.videoId)
        assertFalse(title.minimal)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun aTitleNoAddonHasUnderItsOwnIdIsFoundUnderItsImdbId() = runTest {
        val f = WatchMediaFixture(this, addon("kitsu", "meta", "kitsu:"), addon("cinemeta", "meta", "tt"))
        f.meta("cinemeta", "tt0434665", bleachOnImdb)

        val title = f.titles.resolve(bleach)

        assertEquals("tt0434665", title.meta.id)
        assertEquals("tt0434665:2:5", title.videoId)
        assertEquals(listOf("kitsu meta/series/kitsu:6686", "cinemeta meta/series/tt0434665"), f.requests)
    }

    @Test
    fun theImdbMetasEpisodeIsFoundBySeasonAndEpisode() = runTest {
        val f = WatchMediaFixture(this, addon("imdbmeta", "meta", "tt"))
        f.meta("imdbmeta", "tt0434665", """{"id":"tt0434665","type":"series","name":"Bleach","videos":[{"id":"bleach-2-5","season":2,"episode":5}]}""")

        assertEquals("bleach-2-5", f.titles.resolve(bleach).videoId)
    }

    @Test
    fun aMovieIsFoundUnderItsImdbId() = runTest {
        val f = WatchMediaFixture(this, addon("cinemeta", "meta", "tt"))
        f.meta("cinemeta", "tt0111161", """{"id":"tt0111161","type":"movie","name":"The Shawshank Redemption"}""")
        val movie = RoomMedia(type = "movie", metaId = "mdb:278", videoId = "mdb:278", imdbId = "tt0111161", title = "The Shawshank Redemption")

        val title = f.titles.resolve(movie)

        assertEquals(RoomTitle(title.meta, "tt0111161"), title)
        assertEquals("tt0111161", title.meta.id)
    }

    @Test
    fun withoutAnyMetaTheRoomsOwnIsPlayed() = runTest {
        val f = WatchMediaFixture(this, addon("streams", "stream"))

        val title = f.titles.resolve(bleach)

        assertTrue(title.minimal)
        assertEquals("kitsu:6686:5", title.videoId)
        assertEquals(
            MetaItem(
                MetaItemPreview(id = "kitsu:6686", type = "series", name = "Bleach: Sennen Kessen-hen - Ketsubetsu-tan", imdbId = "tt0434665"),
                listOf(Video(id = "kitsu:6686:5", title = "S1E5 · The Blade Is Me", imdb = ImdbEpisode("tt0434665", 2, 5))),
            ),
            title.meta,
        )
        assertEquals(emptyList(), f.requests)
    }

    @Test
    fun anImdbMetaWithoutTheEpisodeIsNotPlayed() = runTest {
        val f = WatchMediaFixture(this, addon("cinemeta", "meta", "tt"))
        f.meta("cinemeta", "tt0434665", """{"id":"tt0434665","type":"series","name":"Bleach","videos":[{"id":"tt0434665:1:1","season":1,"episode":1}]}""")

        val title = f.titles.resolve(bleach)

        assertTrue(title.minimal)
        assertEquals("kitsu:6686:5", title.videoId)
    }

    @Test
    fun theMinimalMetaOfAMovieHasNoVideosAndIgnoresMalformedIds() = runTest {
        val f = WatchMediaFixture(this)
        val movie = RoomMedia(type = "movie", metaId = "local:1", videoId = "local:1", imdbId = "../etc", tmdbId = "1:2", title = "Home video")

        val title = f.titles.resolve(movie)

        assertEquals(MetaItem(MetaItemPreview(id = "local:1", type = "movie", name = "Home video")), title.meta)
        assertEquals("local:1", title.videoId)
        assertEquals(emptyList(), movie.mappedVideos())
    }
}
