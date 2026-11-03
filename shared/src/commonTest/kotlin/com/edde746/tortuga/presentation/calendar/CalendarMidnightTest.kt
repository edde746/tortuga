@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.presentation.board.BrowseEnv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The Calendar's today moves on at the device's midnight while the screen is open. */
class CalendarMidnightTest {
    private fun TestScope.store(start: Instant): CalendarStore {
        val env = BrowseEnv(this) { request -> episodesAnswer(request, RELEASED) }
        val library = MutableStateFlow<List<LibraryItem>>(listOf(libraryItem("tt1")))
        val feed = EpisodeFeed(
            Extras.CALENDAR_VIDEOS_IDS, LibraryItem::onCalendar, library, MutableStateFlow(listOf(EPISODES)), MutableStateFlow(true),
            env.aggregator, backgroundScope, testScheduler.timeSource,
        )
        val clock = object : Clock {
            override fun now() = start + testScheduler.currentTime.milliseconds
        }
        val host = object : CalendarHost {
            override fun openEpisode(type: String, id: String, videoId: String) = Unit
        }
        return CalendarStore(feed, library, host, clock, backgroundScope)
    }

    /** The device's next midnight after [instant] (no summer time change on the days these tests use). */
    private fun nextMidnight(instant: Instant): Instant {
        val millis = instant.toEpochMilliseconds()
        val day = localDay(millis, ::localUtcOffsetSeconds)
        return Instant.fromEpochMilliseconds((day + 1) * MILLIS_PER_DAY - localUtcOffsetSeconds(millis) * 1000L)
    }

    private fun TestScope.advanceTo(start: Instant, instant: Instant) {
        advanceTimeBy(instant - start - testScheduler.currentTime.milliseconds)
        runCurrent()
    }

    @Test
    fun todayAndTomorrowMoveOnAtMidnight() = runTest {
        val start = Instant.parse("2026-10-26T12:00:00Z")
        val store = store(start)
        runCurrent()
        fun labels() = store.state.value.agenda.map { it.label }
        assertEquals(listOf(26), store.state.value.days.filter { it.today }.map { it.day })
        assertEquals(listOf("Today", "Tomorrow", "Thursday, October 29"), labels())

        val midnight = nextMidnight(start)
        advanceTo(start, midnight - 1.seconds)
        assertEquals(listOf("Today", "Tomorrow", "Thursday, October 29"), labels(), "a second before midnight")

        advanceTo(start, midnight)
        assertEquals(listOf(27), store.state.value.days.filter { it.today }.map { it.day })
        assertEquals(listOf("Monday, October 26", "Today", "Thursday, October 29"), labels())
    }

    @Test
    fun theMonthShownMovesOnWithTodayAndAnotherMonthChosenStays() = runTest {
        val start = Instant.parse("2026-10-31T12:00:00Z")
        val following = store(start)
        val chosen = store(start)
        runCurrent()
        chosen.previousMonth()

        advanceTo(start, nextMidnight(start))
        assertEquals("November 2026", following.state.value.month)
        assertTrue(following.state.value.currentMonth)
        assertEquals(listOf(1), following.state.value.days.filter { it.today }.map { it.day })
        assertEquals("September 2026", chosen.state.value.month)
        assertFalse(chosen.state.value.currentMonth)
    }

    private companion object {
        val RELEASED = mapOf("tt1" to listOf("2026-10-26T05:00:00Z", "2026-10-27T05:00:00Z", "2026-10-29T05:00:00Z"))
    }
}
