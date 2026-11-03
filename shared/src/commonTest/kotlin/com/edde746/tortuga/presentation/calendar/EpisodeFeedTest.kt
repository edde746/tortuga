@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.presentation.board.BrowseEnv
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What the addons answer for the library's series ([EpisodeFeed]): when it asks, for which series, what it keeps. */
class EpisodeFeedTest {
    private class Fixture(
        val scope: TestScope,
        extraName: String = Extras.LAST_VIDEOS_IDS,
        follows: (LibraryItem) -> Boolean = LibraryItem::followsNewEpisodes,
        items: List<LibraryItem> = listOf(libraryItem("tt1", lastWatched = WATCHED), libraryItem("tt2", lastWatched = WATCHED)),
    ) {
        var failing = false
        val env = BrowseEnv(scope) { request ->
            if (failing) respond("", HttpStatusCode.InternalServerError) else episodesAnswer(request, RELEASED)
        }
        val library = MutableStateFlow(items)
        val foreground = MutableStateFlow(true)
        val feed = EpisodeFeed(
            extraName, follows, library, MutableStateFlow(listOf(EPISODES)), foreground, env.aggregator, scope.backgroundScope,
            scope.testScheduler.timeSource,
        )
        val states = mutableListOf<EpisodeFeedState>()

        /** Each request's catalog and ids, in order. */
        val asked: List<String> get() = env.http.requests.map { it.idsPath }

        /** A screen showing the feed, until the job is cancelled. */
        fun show(): Job = scope.backgroundScope.launch { feed.state.collect { states += it } }

        /** The episodes of the last state. */
        fun episodes(): List<String> = states.last().metas.flatMap { meta -> meta.videos.map { it.id } }
    }

    @Test
    fun itAsksOnlyInTheForegroundAndAgainEverySixHours() = runTest {
        val f = Fixture(this)
        f.foreground.value = false
        f.show()
        runCurrent()
        assertEquals(emptyList(), f.asked, "in the background it asks nothing")

        f.foreground.value = true
        runCurrent()
        assertEquals(listOf("series/last-videos/lastVideosIds=tt1,tt2"), f.asked)
        assertEquals(EpisodeFeedState(f.states.last().metas, asked = true, loading = false), f.states.last())
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt2:1:1"), f.episodes())

        advanceTimeBy(6.hours - 1.minutes)
        runCurrent()
        assertEquals(1, f.asked.size, "the answer holds for six hours")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, f.asked.size, "six hours on, it asks again")

        f.foreground.value = false
        advanceTimeBy(12.hours)
        runCurrent()
        assertEquals(2, f.asked.size, "nothing while the app is in the background")
        f.foreground.value = true
        runCurrent()
        assertEquals(3, f.asked.size, "back in the foreground with a 12-hour-old answer, it asks at once")
    }

    @Test
    fun aScreenOpenedAgainWithinSixHoursAsksNothing() = runTest {
        val f = Fixture(this)
        val first = f.show()
        runCurrent()
        assertEquals(1, f.asked.size)
        first.cancel()
        advanceTimeBy(5.hours)

        f.states.clear()
        f.show()
        runCurrent()
        assertEquals(1, f.asked.size, "the second screen gets the kept answer")
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt2:1:1"), f.episodes())
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(2, f.asked.size, "the six hours count from the answer, not from the screen")
    }

    @Test
    fun aFailedRefreshKeepsTheLastAnswer() = runTest {
        val f = Fixture(this)
        f.show()
        runCurrent()
        val answer = f.states.last()

        f.failing = true
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(2, f.asked.size)
        assertEquals(answer, f.states.last(), "the failed refresh changes nothing")
    }

    @Test
    fun aSeriesWithNotificationsOffIsLeftOutOfTheNewEpisodesButNotOfTheCalendar() = runTest {
        val items = listOf(
            libraryItem("tt1", lastWatched = WATCHED),
            libraryItem("tt2", lastWatched = WATCHED, noNotif = true),
            libraryItem("tt3", type = "movie", lastWatched = WATCHED),
        )
        val badges = Fixture(this, items = items)
        badges.show()
        val calendar = Fixture(this, Extras.CALENDAR_VIDEOS_IDS, LibraryItem::onCalendar, items)
        calendar.show()
        runCurrent()
        assertEquals(listOf("series/last-videos/lastVideosIds=tt1"), badges.asked)
        assertEquals(listOf("series/calendar-videos/calendarVideosIds=tt1,tt2"), calendar.asked, "a movie's type has no catalog")

        badges.library.value = items.map { if (it.id == "tt2") libraryItem("tt2", lastWatched = WATCHED) else it }
        runCurrent()
        assertEquals("series/last-videos/lastVideosIds=tt1,tt2", badges.asked.last(), "notifications back on: asked at once")
    }

    private companion object {
        val WATCHED = Instant.parse("2026-10-01T20:00:00Z")
        val RELEASED = mapOf(
            "tt1" to listOf("2026-10-05T05:00:00Z", "2026-10-12T05:00:00Z"),
            "tt2" to listOf("2026-10-07T05:00:00Z"),
            "tt3" to listOf("2026-10-07T05:00:00Z"),
        )
    }
}
