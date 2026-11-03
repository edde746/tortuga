package com.edde746.tortuga.presentation.homescreen

import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.movie
import com.edde746.tortuga.data.series
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.model.PosterShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a profile's Continue Watching puts on the TV's home screen, and the links its items open. */
class HomeScreenItemsTest {
    private val clock = TestClock()
    private val data = testData(clock)

    @AfterTest
    fun close() = data.close()

    private suspend fun TestScope.items(profileId: String) = testGraph(data).homeScreenItems(profileId).first()

    @Test
    fun continueWatchingBecomesTheProfilesItemsNewestFirstWithTheirLinks() = runTest {
        val p = data.profiles.create("Alice", null).id
        data.library.upsertFromMeta(p, movie())
        data.library.recordProgress(p, "tt0111161", "tt0111161", 60_000, 8_520_000)
        clock.now += 1_000
        data.library.upsertFromMeta(p, series())
        data.library.recordProgress(p, "tt0903747", "tt0903747:2:3", 600_000, 2_900_000)

        val (episode, film) = items(p)
        assertEquals(
            HomeScreenItem(
                profileId = p, type = "series", id = "tt0903747", videoId = "tt0903747:2:3", kind = HomeScreenItem.Kind.EPISODE,
                title = "Breaking Bad", season = 2, episode = 3, positionMillis = 600_000, durationMillis = 2_900_000,
                progress = 600_000.0 / 2_900_000, posterUrl = null, posterShape = PosterShape.POSTER, lastEngagedMillis = clock.now,
                link = HomeScreenLink.Play(p, "series", "tt0903747", "tt0903747:2:3").toUrl(),
                detailLink = HomeScreenLink.Detail(p, "series", "tt0903747").toUrl(),
            ),
            episode,
        )
        assertEquals(HomeScreenItem.Kind.MOVIE, film.kind)
        assertEquals(listOf(null, null), listOf(film.season, film.episode))
        assertEquals(60_000, film.positionMillis)
        assertEquals("https://images.example/tt0111161.jpg", film.posterUrl)
        assertEquals(clock.now - 1_000, film.lastEngagedMillis)
        assertEquals(HomeScreenLink.Play(p, "movie", "tt0111161", "tt0111161"), HomeScreenLink.parse(film.link))
    }

    @Test
    fun aVideoPastItsCreditsPlaysFromTheStartAndAKitsuEpisodeHasNoSeason() = runTest {
        val p = data.profiles.create("Alice", null).id
        data.library.upsertFromMeta(p, movie())
        data.library.recordProgress(p, "tt0111161", "tt0111161", 95_000, 100_000)
        clock.now += 1_000
        data.library.upsertFromMeta(p, series(id = "kitsu:1"))
        data.library.recordProgress(p, "kitsu:1", "kitsu:1:5", 60_000, 1_400_000)

        val (anime, film) = items(p)
        assertEquals(0, film.positionMillis)
        assertEquals(listOf(null, 5), listOf(anime.season, anime.episode))
        assertEquals(HomeScreenItem.Kind.EPISODE, anime.kind)
    }

    @Test
    fun aVideoIdWithANegativeNumberNamesNoSeasonOrEpisode() = runTest {
        val p = data.profiles.create("Alice", null).id
        data.library.upsertFromMeta(p, series())
        data.library.recordProgress(p, "tt0903747", "tt0903747:-1:3", 600_000, 2_900_000)

        val (item) = items(p)
        assertEquals(HomeScreenItem.Kind.EPISODE, item.kind)
        assertEquals(listOf(null, null), listOf(item.season, item.episode))
    }

    @Test
    fun aProfileWithAPinShowsNothingWhileItHasOneAndOthersShowTheirOwn() = runTest {
        val alice = data.profiles.create("Alice", null).id
        val bob = data.profiles.create("Bob", null).id
        data.library.upsertFromMeta(alice, movie())
        data.library.recordProgress(alice, "tt0111161", "tt0111161", 60_000, 8_520_000)

        data.profiles.setPin(alice, "1234")
        assertEquals(emptyList(), items(alice))
        assertEquals(emptyList(), items(bob), "Bob's Continue Watching is his own")
        data.profiles.setPin(alice, null)
        assertEquals(listOf("tt0111161"), items(alice).map { it.id })

        data.profiles.delete(alice)
        assertEquals(emptyList(), items(alice), "a deleted profile shows nothing")
    }

    @Test
    fun anItemLeavesOnceItLeavesContinueWatching() = runTest {
        val p = data.profiles.create("Alice", null).id
        data.library.upsertFromMeta(p, movie())
        data.library.recordProgress(p, "tt0111161", "tt0111161", 60_000, 8_520_000)
        assertEquals(1, items(p).size)
        data.library.markWatched(p, movie(), watched = true)
        assertEquals(emptyList(), items(p))
    }

    @Test
    fun aLinkOpensInItsProfileOnlyAndNeedsEveryValue() {
        val play = HomeScreenLink.Play("6f1c-4e", "series", "kitsu:1", "kitsu:1:5 & more")
        assertEquals("tortuga://play?profile=6f1c-4e&type=series&id=kitsu%3A1&video=kitsu%3A1%3A5%20%26%20more", play.toUrl())
        assertEquals(play, HomeScreenLink.parse(play.toUrl()))
        val detail = HomeScreenLink.Detail("6f1c-4e", "movie", "tt1")
        assertEquals(detail, HomeScreenLink.parse(" TORTUGA://detail/?profile=6f1c-4e&type=movie&id=tt1 "))
        assertNull(HomeScreenLink.parse("tortuga://detail?type=movie&id=tt1"), "no profile")
        assertNull(HomeScreenLink.parse("tortuga://play?profile=p&type=movie&id=tt1"), "no video")
        assertNull(HomeScreenLink.parse("tortuga://watch?code=ABC123"))
    }
}
