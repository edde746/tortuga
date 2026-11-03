@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.library

import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.FakeAppPlatform
import com.edde746.tortuga.app.FakeHomeScreen
import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.kids.AgeRatings
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.kids.BLUEY
import com.edde746.tortuga.kids.BREAKING_BAD
import com.edde746.tortuga.kids.FakeAges
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.calendar.episodesAnswer
import com.edde746.tortuga.presentation.items
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.ids.FixtureMappings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A series the user caught up on comes back to Continue Watching on its next episode once that is out, as the addons'
 * last videos list it (what the new-episode badges count): on Home's row, the Continue Watching screen and the TV's
 * home screen alike, ordered by the episode's release as if watched then.
 */
class ContinueWatchingNewEpisodeTest {
    /** One profile's app on the test's clock, with an addon that lists the last videos of [releases]' series. */
    private class World(private val scope: TestScope) {
        private val clock = object : Clock {
            override fun now() = START + scope.testScheduler.currentTime.milliseconds
        }
        val data = LocalData(
            inMemoryDatabase(), { clock.now().toEpochMilliseconds() }, StandardTestDispatcher(scope.testScheduler),
            HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )

        /** Each series' episodes (`<id>:1:<n>`) by their release times, as the addon lists them. */
        val releases = mutableMapOf<String, List<Instant>>()
        private val addon = FakeHttp(scope) { request -> episodesAnswer(request, releases.mapValues { (_, times) -> times.map { it.toString() } }) }
        private val graph = scope.testGraph(data, addon.client, clock)
        val home = FakeHomeScreen()
        lateinit var profileId: String
        lateinit var app: AppSession
        lateinit var board: BoardStore
        lateinit var continueWatching: LibraryStore

        /** The profile, a kids profile capped at [AgeStep.KIDS] with [kids], and its addon. */
        suspend fun start(kids: Boolean = false) {
            graph.ageRatings = AgeRatings(FakeAges(), FixtureMappings())
            profileId = data.profiles.create("Mia", null, kids = kids).id
            if (kids) data.settings.set(profileId, ProfileSetting.KidsCap, AgeStep.KIDS)
            data.addons.install(profileId, "https://episodes.example.com/manifest.json", MANIFEST)
            graph.setForeground(true)
        }

        /** The app open on Home's row and the Continue Watching screen, on a TV with a home screen. */
        fun open() {
            app = AppSession(graph, profileId, FakeAppPlatform(homeScreen = home), start = null, profileAccount = null)
            board = app.boardStore()
            continueWatching = app.continueWatchingStore()
            scope.runCurrent()
        }

        /** Adds the series [id] to the library and watches its episode [episode] through, now. */
        suspend fun watch(id: String, episode: Int) {
            data.library.addToLibrary(profileId, MetaSnapshot(id, "series", "Series $id", null, "poster"))
            data.library.recordProgress(profileId, id, "$id:1:$episode", 1_750_000, 1_800_000, meta(id), watched = true)
        }

        fun meta(id: String) = MetaItem(
            MetaItemPreview(id = id, type = "series", name = "Series $id"),
            videos = releases.getValue(id).mapIndexed { index, at -> Video(id = "$id:1:${index + 1}", released = at, seriesInfo = SeriesInfo(1, index + 1)) },
        )

        /** Continue Watching's titles, as Home's row, the Continue Watching screen and the TV's home screen all list them. */
        fun shown(): List<String> {
            scope.runCurrent()
            val row = board.state.value.continueWatching.map { it.id }
            assertEquals(row, continueWatching.state.value.items.map { it.id }, "the Continue Watching screen")
            assertEquals(row, home.published.last().map { it.id }, "the TV's home screen")
            return row
        }
    }

    @Test
    fun aCaughtUpSeriesComesBackOnItsNextEpisodeOnceItIsOutAsIfWatchedThen() = runTest {
        val w = World(this)
        w.start()
        w.releases["tt1"] = listOf(START - 2.days, START - 1.days, START + 1.hours)
        w.watch("tt1", 2)
        advanceTimeBy(30.minutes)
        w.data.library.upsertFromMeta(w.profileId, MOVIE)
        w.data.library.recordProgress(w.profileId, MOVIE.id, MOVIE.id, 600_000, 6_000_000)
        w.open()
        assertEquals(listOf(MOVIE.id), w.shown(), "caught up, and nothing new")

        advanceTimeBy(30.minutes - 1.seconds)
        assertEquals(listOf(MOVIE.id), w.shown(), "a second before episode 3 is out")
        advanceTimeBy(1.seconds)
        assertEquals(listOf("tt1", MOVIE.id), w.shown(), "out: ahead of the movie watched before it")
        val card = w.board.state.value.continueWatching.first()
        assertEquals("tt1:1:3", card.videoId)
        assertEquals(T.browse.continueWatching.detail(T.session.episode("1", "3"), T.browse.continueWatching.upNext), card.detail)
        val item = w.home.published.last().first()
        assertEquals(listOf<Any?>("tt1:1:3", 0L, (START + 1.hours).toEpochMilliseconds()), listOf(item.videoId, item.positionMillis, item.lastEngagedMillis))

        advanceTimeBy(1.hours)
        w.data.library.recordProgress(w.profileId, MOVIE.id, MOVIE.id, 900_000, 6_000_000)
        assertEquals(listOf(MOVIE.id, "tt1"), w.shown(), "behind the movie watched since")
        w.app.close()
    }

    @Test
    fun removedItStaysOutUntilTheNextEpisodeIsOut() = runTest {
        val w = World(this)
        w.start()
        w.releases["tt1"] = listOf(START - 2.days, START - 1.days, START + 1.hours, START + 1.days)
        w.watch("tt1", 2)
        w.open()
        advanceTimeBy(1.hours)
        assertEquals(listOf("tt1"), w.shown())

        w.continueWatching.menuAction("tt1", MediaAction.REMOVE_FROM_CONTINUE_WATCHING)
        assertEquals(emptyList(), w.shown(), "removed")
        advanceTimeBy(1.days - 1.hours - 1.seconds)
        assertEquals(emptyList(), w.shown(), "a second before episode 4 is out")
        advanceTimeBy(1.seconds)
        assertEquals(listOf("tt1"), w.shown(), "episode 4 brings it back")
        assertEquals("tt1:1:3", w.board.state.value.continueWatching.single().videoId, "on the episode after the last one watched")
        w.app.close()
    }

    @Test
    fun aSeriesWithNotificationsOffOrOutOfTheLibraryStaysOut() = runTest {
        val w = World(this)
        w.start()
        for (id in listOf("tt1", "tt2", "tt3")) {
            w.releases[id] = listOf(START - 1.days, START + 1.hours)
            w.watch(id, 1)
        }
        // The title's notifications off, as a sync from a device that turned them off stores it.
        w.data.db.write {
            val row = checkNotNull(q.libraryItemQueries.selectLive(w.profileId, "tt2").executeAsOneOrNull())
            q.libraryItemQueries.upsert(row.copy(no_notif = true))
        }
        w.data.library.removeFromLibrary(w.profileId, "tt3")
        w.open()
        advanceTimeBy(1.hours)
        assertEquals(listOf("tt1"), w.shown())
        w.app.close()
    }

    @Test
    fun aSeriesWithEveryEpisodeWatchedStaysOut() = runTest {
        val w = World(this)
        w.start()
        for (id in listOf("tt1", "tt2")) {
            w.releases[id] = listOf(START - 1.days, START + 1.hours)
            w.watch(id, 1)
        }
        advanceTimeBy(2.hours)
        // Its last episode marked watched elsewhere: the marks a sync carries, its last watched time as it was.
        val finished = WatchedBitField.of(null, w.meta("tt2")).apply {
            set("tt2:1:1", true)
            set("tt2:1:2", true)
        }
        w.data.library.setWatchedVideos(w.profileId, "tt2", finished.serialize())
        w.open()
        assertEquals(listOf("tt1"), w.shown())
        w.app.close()
    }

    @Test
    fun aKidsProfileGetsBackOnlyTheSeriesItsCapAllows() = runTest {
        val w = World(this)
        w.start(kids = true)
        for (series in listOf(BLUEY, BREAKING_BAD)) {
            w.releases[series.id] = listOf(START - 1.days, START + 1.hours)
            w.watch(series.id, 1)
        }
        w.open()
        advanceTimeBy(1.hours)
        assertEquals(listOf(BLUEY.id), w.shown())
        w.app.close()
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-29T11:00:00Z")
        val MOVIE = MetaSnapshot("tt9", "movie", "Movie tt9", null, "poster")
        const val MANIFEST = """{"id":"org.example.episodes","version":"1.0.0","name":"Episodes","types":["series"],"resources":["catalog"],
            "catalogs":[{"type":"series","id":"last-videos","extra":[{"name":"lastVideosIds","isRequired":true,"optionsLimit":100}]}]}"""
    }
}
