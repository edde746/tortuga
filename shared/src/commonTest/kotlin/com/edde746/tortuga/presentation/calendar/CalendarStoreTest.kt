@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.presentation.board.catalogAddon
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The Calendar screen ([CalendarStore]): October 2026 as the addons date the library's series, in English. */
class CalendarStoreTest {
    private class Fixture(
        scope: TestScope,
        items: List<LibraryItem> = listOf(libraryItem("tt1"), libraryItem("tt2")),
        addons: Flow<List<Descriptor>> = MutableStateFlow(listOf(EPISODES)),
        settings: Flow<ProfileSettings>? = null,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request -> episodesAnswer(request, RELEASED) },
    ) {
        val env = BrowseEnv(scope, answer)
        val library = MutableStateFlow(items)
        val opened = mutableListOf<String>()
        private val feed = EpisodeFeed(
            Extras.CALENDAR_VIDEOS_IDS, LibraryItem::onCalendar, library, addons, MutableStateFlow(true),
            env.aggregator, scope.backgroundScope, scope.testScheduler.timeSource,
        )
        val store = CalendarStore(
            feed,
            library,
            object : CalendarHost {
                override fun openEpisode(type: String, id: String, videoId: String) {
                    opened += "$type/$id/$videoId"
                }
            },
            object : Clock {
                override fun now() = NOW
            },
            scope.backgroundScope,
            settings = settings,
        )

        fun agenda() = store.state.value.agenda.map { day -> day.label to day.episodes.map { "${it.series} ${it.videoId}" } }
    }

    @Test
    fun octoberIsAGridOfWeeksFromMondayWithTodayAndEachDaysEpisodes() = runTest {
        val f = Fixture(this)
        runCurrent()
        val state = f.store.state.value
        assertEquals("Calendar", state.title)
        assertEquals("October 2026", state.month)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), state.weekdays)
        assertEquals(3, state.leadingDays, "1 October 2026 is a Thursday")
        assertEquals((1..31).toList(), state.days.map { it.day })
        assertEquals(listOf(26), state.days.filter { it.today }.map { it.day })
        assertEquals(mapOf(26 to 1, 27 to 2, 29 to 1), state.days.filter { it.episodes > 0 }.associate { it.day to it.episodes })
        assertEquals("Thursday, October 29, 1 episode", state.days[28].accessibilityLabel, "what a screen reader says of the day")
        assertEquals("Friday, October 30", state.days[29].accessibilityLabel)
        assertTrue(state.currentMonth)
        assertEquals(
            listOf(
                "Today" to listOf("Series tt1 tt1:1:1"),
                "Tomorrow" to listOf("Series tt1 tt1:1:2", "Series tt2 tt2:1:1"),
                "Thursday, October 29" to listOf("Series tt1 tt1:1:3"),
            ),
            f.agenda(),
            "an episode is on its UTC release date; September's and November's aren't in October",
        )
    }

    @Test
    fun theArrowsMoveAMonthAndThisMonthComesBack() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.nextMonth()
        var state = f.store.state.value
        assertEquals("November 2026", state.month)
        assertEquals(6, state.leadingDays, "1 November 2026 is a Sunday")
        assertEquals(30, state.days.size)
        assertFalse(state.currentMonth)
        assertEquals(listOf("Monday, November 2" to listOf("Series tt2 tt2:1:3")), f.agenda())

        f.store.previousMonth()
        f.store.previousMonth()
        state = f.store.state.value
        assertEquals("September 2026", state.month)
        assertEquals(listOf("Wednesday, September 30" to listOf("Series tt2 tt2:1:2")), f.agenda())

        f.store.thisMonth()
        assertEquals("October 2026", f.store.state.value.month)
        assertTrue(f.store.state.value.currentMonth)
    }

    @Test
    fun eachEmptyCalendarSaysWhy() = runTest {
        val empty = Fixture(this, items = emptyList())
        val noCalendar = Fixture(this, addons = MutableStateFlow(listOf(catalogAddon("lists", """[{"type":"series","id":"top"}]"""))))
        val quiet = Fixture(this)
        runCurrent()
        quiet.store.nextMonth()
        quiet.store.nextMonth()

        assertEquals(CalendarBody.Empty("Add series to your library to see their episodes here."), empty.store.state.value.body)
        assertEquals(CalendarBody.Empty("None of your addons has a calendar for the series in your library."), noCalendar.store.state.value.body)
        assertEquals(CalendarBody.Empty("No episodes."), quiet.store.state.value.body)
        assertEquals("December 2026", quiet.store.state.value.month)
        assertEquals(emptyList(), empty.env.http.requests + noCalendar.env.http.requests, "nobody to ask")
    }

    /**
     * Under the grid, the first that applies: the month's episodes, though another addon still answers; why there are
     * none, the empty library's text with no wait for the addons; a spinner while they have still to answer.
     */
    @Test
    fun theBodyIsTheFirstThatApplies() = runTest {
        // The installed addons not read yet: the episode feed has still to answer.
        val waiting = Fixture(this, addons = MutableSharedFlow())
        val emptyLibrary = Fixture(this, items = emptyList(), addons = MutableSharedFlow())
        val slow = catalogAddon("slow", SLOW_CATALOGS)
        val partly = Fixture(this, addons = MutableStateFlow(listOf(EPISODES, slow))) { request ->
            if (request.url.host == "slow.example.com") awaitCancellation() else episodesAnswer(request, RELEASED)
        }
        runCurrent()

        assertEquals(CalendarBody.Loading, waiting.store.state.value.body, "the library's series wait for the addons")
        assertEquals(
            CalendarBody.Empty("Add series to your library to see their episodes here."),
            emptyLibrary.store.state.value.body,
            "an empty library has nothing to wait for",
        )
        assertIs<CalendarBody.Agenda>(partly.store.state.value.body, "the month's episodes show while another addon still answers")
        assertEquals(3, partly.store.state.value.agenda.size)
    }

    /**
     * An episode's line is its code and its title, the title isolated, so that a title written the other way keeps its
     * place after the code ("S1E1 · الحلقة الأولى" in English), then the series' runtime as Detail's episode line has it;
     * the code or the title alone stands as it is, and a video that isn't an episode has no runtime.
     */
    @Test
    fun anEpisodesLineIsolatesItsTitleAndEndsInTheRuntime() = runTest {
        val f = Fixture(this, items = listOf(libraryItem("tt1"))) { json(LINES) }
        runCurrent()
        assertEquals(
            listOf("S1E1 · \u2068الحلقة الأولى\u2069 · 46 min", "S1E2 · 46 min", "Behind the Scenes", null),
            f.store.state.value.agenda.single().episodes.map { it.detail },
        )
    }

    /** An episode's picture is its own still, with the series' background, else its poster, for where it has none. */
    @Test
    fun anEpisodesArtIsItsStillWithTheSeriesLandscapeArtBehind() = runTest {
        val f = Fixture(this) { json(ART) }
        runCurrent()
        assertEquals(
            listOf(
                Triple("tt1:1:1", "https://img.example/1.jpg", "https://img.example/tt1-background.jpg"),
                Triple("tt1:1:2", null, "https://img.example/tt1-background.jpg"),
                Triple("tt2:1:1", null, "https://img.example/tt2-poster.jpg"),
            ),
            f.store.state.value.agenda.single().episodes.map { Triple(it.videoId, it.still, it.art) },
        )
    }

    @Test
    fun openingAnEpisodeShowsItsSeriesAtThatEpisode() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.open(f.store.state.value.agenda[1].episodes[1])
        assertEquals(listOf("series/tt2/tt2:1:1"), f.opened)
    }

    /**
     * With the profile blurring unwatched episodes, an episode is blurred, its still and its description, while it is
     * neither watched nor started, upcoming ones included, as on Detail; a description blurred has the words screen
     * readers say in its place. The setting off blurs nothing.
     */
    @Test
    fun episodesNeitherWatchedNorStartedAreBlurredWhileTheSettingIsOn() = runTest {
        val series = MetaItem(
            MetaItemPreview(id = "tt1", type = "series", name = "Series tt1"),
            (1..5).map { Video(id = "tt1:1:$it", title = "Episode $it", seriesInfo = SeriesInfo(1, it)) },
        )
        val marks = WatchedBitField.of(null, series).apply { set("tt1:1:1", true) }.serialize()
        val item = libraryItem("tt1").let {
            it.copy(state = it.state.copy(videoId = "tt1:1:2", timeOffset = 600_000, duration = 2_400_000, watchedVideos = marks))
        }
        val settings = MutableStateFlow(ProfileSettings.DEFAULT.copy(blurUnwatched = true))
        val f = Fixture(this, items = listOf(item), settings = settings) { json(STILLS) }
        fun blurred() = f.store.state.value.agenda.flatMap { it.episodes }.associate { it.videoId to (it.blurred to it.hiddenOverviewLabel) }
        runCurrent()
        val hidden = true to "Description hidden"
        assertEquals(
            mapOf("tt1:1:1" to (false to null), "tt1:1:2" to (false to null), "tt1:1:3" to hidden, "tt1:1:4" to hidden, "tt1:1:5" to hidden),
            blurred(),
            "watched and started sharp; unwatched and upcoming blurred, with or without a still",
        )

        settings.value = ProfileSettings.DEFAULT
        runCurrent()
        assertEquals(setOf(false to null), blurred().values.toSet(), "off: none is blurred")
    }

    private companion object {
        /** Noon UTC: 26 October 2026 in every time zone from UTC-11 to UTC+11. */
        val NOW = Instant.parse("2026-10-26T12:00:00Z")
        val RELEASED = mapOf(
            "tt1" to listOf("2026-10-26T05:00:00Z", "2026-10-27T05:00:00Z", "2026-10-29T00:00:00Z"),
            "tt2" to listOf("2026-10-27T23:59:59Z", "2026-09-30T05:00:00Z", "2026-11-02T05:00:00Z"),
        )
        val SLOW_CATALOGS = """[{"type":"series","id":"calendar-videos","extra":[{"name":"calendarVideosIds","isRequired":true,"optionsLimit":100}]}]"""

        /** tt1's videos of 26 October, its runtime 46 min: an Arabic title, a blank one, one without a season and number, one with neither. */
        val LINES = """{"metasDetailed":[{"id":"tt1","type":"series","name":"Series tt1","runtime":"46 min","videos":[
            {"id":"tt1:1:1","title":"الحلقة الأولى","released":"2026-10-26T05:00:00Z","season":1,"episode":1},
            {"id":"tt1:1:2","title":" ","released":"2026-10-26T05:00:00Z","season":1,"episode":2},
            {"id":"tt1:extra","title":"Behind the Scenes","released":"2026-10-26T05:00:00Z"},
            {"id":"tt1:clip","released":"2026-10-26T05:00:00Z"}
        ]}]}"""

        /** 26 October: tt1's episode 1 with a still and 2 without, tt1 with a background; tt2's episode 1, tt2 with a poster alone. */
        val ART = """{"metasDetailed":[
            {"id":"tt1","type":"series","name":"Series tt1","poster":"https://img.example/tt1-poster.jpg","background":"https://img.example/tt1-background.jpg","videos":[
                {"id":"tt1:1:1","title":"Episode 1","released":"2026-10-26T05:00:00Z","season":1,"episode":1,"thumbnail":"https://img.example/1.jpg"},
                {"id":"tt1:1:2","title":"Episode 2","released":"2026-10-26T05:00:00Z","season":1,"episode":2}
            ]},
            {"id":"tt2","type":"series","name":"Series tt2","poster":"https://img.example/tt2-poster.jpg","videos":[
                {"id":"tt2:1:1","title":"Episode 1","released":"2026-10-26T05:00:00Z","season":1,"episode":1}
            ]}
        ]}"""

        /** tt1's October, each episode with a description: 1 to 3 out with their stills, 4 not out yet, 5 not out and without a still. */
        val STILLS = """{"metasDetailed":[{"id":"tt1","type":"series","name":"Series tt1","background":"https://img.example/tt1.jpg","videos":[
            {"id":"tt1:1:1","title":"Episode 1","released":"2026-10-05T05:00:00Z","season":1,"episode":1,"thumbnail":"https://img.example/1.jpg","overview":"One."},
            {"id":"tt1:1:2","title":"Episode 2","released":"2026-10-12T05:00:00Z","season":1,"episode":2,"thumbnail":"https://img.example/2.jpg","overview":"Two."},
            {"id":"tt1:1:3","title":"Episode 3","released":"2026-10-19T05:00:00Z","season":1,"episode":3,"thumbnail":"https://img.example/3.jpg","overview":"Three."},
            {"id":"tt1:1:4","title":"Episode 4","released":"2026-10-28T05:00:00Z","season":1,"episode":4,"thumbnail":"https://img.example/4.jpg","overview":"Four."},
            {"id":"tt1:1:5","title":"Episode 5","released":"2026-10-30T05:00:00Z","season":1,"episode":5,"overview":"Five."}
        ]}]}"""
    }
}
