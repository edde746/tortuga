@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.LibraryItemState
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemBehaviorHints
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.FakeHttp
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * stremio-core's new-episode rule (`update_notification_items`), the per-series notifications-off flag, and the badges'
 * count as an episode comes out.
 */
class NewEpisodesTest {
    /** stremio-core's `pull_notifications_data.json`: watched on 1 January 2020 at 10:00, four episodes around it. */
    private val episodes = listOf("2019-12-20T00:00:00Z", "2020-01-01T00:00:00Z", "2020-01-05T00:00:00Z", "2020-01-15T00:00:00Z")
    private val now = Instant.parse("2024-01-01T10:30:00Z")

    @Test
    fun theEpisodesOutSinceTheSeriesWasLastWatchedAreNewAsInStremioCore() {
        // Episodes 6 and 7: episode 5 came out the day the series was last watched, before the watch.
        assertEquals(2, newEpisodeCount(item(), meta(), now))
        assertEquals(mapOf("tt1" to 2), newEpisodeCounts(listOf(item()), listOf(meta()), now))
    }

    @Test
    fun anEpisodeNotOutYetIsNotNew() {
        assertEquals(1, newEpisodeCount(item(), meta(), Instant.parse("2020-01-10T00:00:00Z")), "episode 6")
        assertEquals(Instant.parse("2020-01-15T00:00:00Z"), nextNewEpisode(listOf(item()), listOf(meta()), Instant.parse("2020-01-10T00:00:00Z")))
    }

    @Test
    fun aSeriesWithNotificationsOffIsNeitherAskedForNorCounted() {
        val off = item(noNotif = true)
        assertFalse(off.followsNewEpisodes)
        assertTrue(off.onCalendar, "the calendar still shows its episodes")
        assertEquals(emptyMap(), newEpisodeCounts(listOf(off), listOf(meta()), now))
    }

    @Test
    fun onlyEpisodicTitlesInTheLibraryAreFollowed() {
        assertFalse(item(type = "movie").followsNewEpisodes)
        assertFalse(item(type = "other").followsNewEpisodes)
        assertFalse(item(type = "tv").followsNewEpisodes)
        assertFalse(item(removed = true).followsNewEpisodes)
        assertFalse(item(temp = true).followsNewEpisodes)
        assertFalse(item(removed = true).onCalendar)
        assertTrue(item(type = "anime").followsNewEpisodes)
    }

    @Test
    fun nothingIsNewOfANeverWatchedSeriesALiveChannelOrATitleOfOneVideo() {
        assertEquals(0, newEpisodeCount(item(lastWatched = null), meta(), now))
        assertEquals(0, newEpisodeCount(item(), meta(hints = MetaItemBehaviorHints(isLive = true)), now))
        assertEquals(0, newEpisodeCount(item(), meta(hints = MetaItemBehaviorHints(defaultVideoId = "tt1:1:7")), now))
    }

    @Test
    fun theFirstAnswerWithVideosCountsAndAnEpisodeListedTwiceCountsOnce() {
        val empty = meta(released = emptyList())
        val repeated = meta().let { it.copy(videos = it.videos + it.videos.last()) }
        val other = meta(released = listOf("2020-01-05T00:00:00Z"))
        assertEquals(mapOf("tt1" to 2), newEpisodeCounts(listOf(item()), listOf(empty, repeated, other), now))
    }

    @Test
    fun anEpisodeListedAheadOfItsReleaseCountsFromItsReleaseOnWithNothingElseChanging() = runTest {
        // One clock on the test's time for the library and the badges: the series is last watched as the test starts.
        val start = Instant.parse("2026-10-29T11:00:00Z")
        val clock = object : Clock {
            override fun now() = start + testScheduler.currentTime.milliseconds
        }
        val data = LocalData(
            inMemoryDatabase(), { clock.now().toEpochMilliseconds() }, StandardTestDispatcher(testScheduler),
            HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val http = FakeHttp(this) { request ->
            episodesAnswer(request, mapOf("tt1" to listOf((start - 1.days).toString(), (start + 1.hours).toString())))
        }
        val graph = testGraph(data, http.client, clock)
        data.addons.install(
            "p", "https://episodes.example.com/manifest.json",
            """{"id":"org.example.episodes","version":"1.0.0","name":"Episodes","types":["series"],"resources":["catalog"],
            "catalogs":[{"type":"series","id":"last-videos","extra":[{"name":"lastVideosIds","isRequired":true,"optionsLimit":100}]}]}""",
        )
        data.library.addToLibrary("p", MetaSnapshot("tt1", "series", "Series tt1", null, "poster"))
        data.library.recordProgress("p", "tt1", "tt1:1:1", 600_000, 1_800_000)
        graph.setForeground(true)
        val counts = mutableListOf<Map<String, Int>>()
        backgroundScope.launch { graph.newEpisodeCounts("p").collect { counts += it } }
        runCurrent()
        assertEquals(1, http.requests.size, "the addon answered")
        assertEquals(emptyMap(), counts.last(), "episode 2 comes out in an hour")

        advanceTimeBy(1.hours - 1.seconds)
        runCurrent()
        assertEquals(emptyMap(), counts.last(), "a second before its release")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(mapOf("tt1" to 1), counts.last(), "out now")
        assertEquals(1, http.requests.size, "counted from the same answer")
    }

    private fun item(
        type: String = "series",
        lastWatched: String? = "2020-01-01T10:00:00Z",
        noNotif: Boolean = false,
        removed: Boolean = false,
        temp: Boolean = false,
    ) = LibraryItem(
        id = "tt1", type = type, name = "name", poster = null, posterShape = "poster", removed = removed, temp = temp,
        ctime = 0, mtime = 0,
        state = LibraryItemState(
            lastWatched = lastWatched?.let { Instant.parse(it).toEpochMilliseconds() }, timeWatched = 0, timeOffset = 0,
            overallTimeWatched = 0, timesWatched = 1, flaggedWatched = 0, duration = 0, videoId = null, watchedVideos = null,
            noNotif = noNotif,
        ),
    )

    private fun meta(released: List<String> = episodes, hints: MetaItemBehaviorHints = MetaItemBehaviorHints()) = MetaItem(
        MetaItemPreview(id = "tt1", type = "series", name = "name", behaviorHints = hints),
        videos = released.mapIndexed { index, date ->
            Video(id = "tt1:1:${index + 4}", released = Instant.parse(date), seriesInfo = SeriesInfo(1, index + 4))
        },
    )
}
