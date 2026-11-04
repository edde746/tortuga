package com.edde746.tortuga.trackers

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.session.PlaybackPosition
import com.edde746.tortuga.trackers.TrackerService.ANILIST
import com.edde746.tortuga.trackers.TrackerService.MYANIMELIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Playback reports and watched marks as the services get them. */
class TrackerCoordinatorTest {
    private var fixture: TrackerFixture? = null

    @AfterTest
    fun close() {
        fixture?.close()
    }

    private fun kotlinx.coroutines.test.TestScope.fixture() = TrackerFixture(this).also { fixture = it }

    /** A 1000 s kitsu anime episode (`kitsu:100:3`), which Trakt knows as `tt5626028` S1E3. */
    private val anime = MetaItem(
        MetaItemPreview(id = "kitsu:100", type = "series", name = "An anime"),
        videos = (1..3).map { Video(id = "kitsu:100:$it", seriesInfo = SeriesInfo(1, it)) },
    )

    /** Reports each second of [from]..[to] (seconds of a 1000 s video), a second of virtual time apart. */
    private fun TrackerFixture.play(from: Int, to: Int) {
        for (second in from..to) {
            observer.onPosition(at(second))
            advance(1.seconds)
        }
    }

    @Test
    fun aWatchIsTheRealtimeStopOrTheThresholdMarkNeverBoth() = runTest {
        val f = fixture()
        f.resolver.kitsuImdb["kitsu:100"] = "tt5626028"
        f.connect(TRAKT)
        f.connect(MYANIMELIST)
        f.connect(ANILIST)
        f.observer.onVideoStarted(anime, "kitsu:100:3", at(0), playing = true)
        f.observer.onPlayingChanged(true, at(0))
        f.play(1, 699)
        assertEquals(listOf("scrobble START 0"), f.calls(TRAKT))
        assertEquals(emptyList(), f.calls(MYANIMELIST), "no mark before the threshold")

        // 70 % (the profile's default threshold): the list trackers get the watch, once; Trakt owns its own.
        f.play(700, 750)
        assertEquals(listOf("watched 100@3"), f.calls(MYANIMELIST))
        assertEquals(listOf("watched 100@3"), f.calls(ANILIST))
        assertEquals(listOf("scrobble START 0"), f.calls(TRAKT))

        f.observer.onVideoStopped(at(750))
        f.drain()
        assertEquals(listOf("scrobble START 0", "scrobble STOP 75", "reconcile 75"), f.calls(TRAKT))
        assertEquals(listOf("watched 100@3"), f.calls(MYANIMELIST), "not marked again at stop")
        assertEquals(listOf("watched 100@3"), f.calls(ANILIST))
        val trakt = f.log.first { it.service == TRAKT }.context!!
        assertEquals(TrackerContentType.ANIME, trakt.contentType)
        assertEquals(listOf("tt5626028", 1, 3), listOf(trakt.ids.imdb, trakt.season, trakt.episode), "the external numbering")
    }

    @Test
    fun aCrossingMissedBetweenReportsIsMarkedAtStopAndBelowTheThresholdNothingIs() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(ANILIST)
        f.data.settings.set(f.profileId, com.edde746.tortuga.data.settings.ProfileSetting.WatchedThresholdPercent, 90)
        f.observer.onVideoStarted(anime, "kitsu:100:1", at(0), playing = true)
        f.play(1, 5)
        f.observer.onVideoStopped(at(890))
        f.drain()
        assertEquals(emptyList(), f.calls(ANILIST), "89 % is short of the profile's 90 %")
        assertEquals(listOf("scrobble START 0", "scrobble STOP 89"), f.calls(TRAKT), "no reconcile below the threshold")

        f.observer.onVideoStarted(anime, "kitsu:100:2", at(0), playing = true)
        f.play(1, 5)
        // The player jumped to the end and left before another position report.
        f.observer.onVideoStopped(at(950))
        f.drain()
        assertEquals(listOf("watched 100@2"), f.calls(ANILIST))
        assertEquals(listOf("scrobble START 0", "scrobble STOP 95", "reconcile 95"), f.calls(TRAKT).drop(2))
    }

    @Test
    fun theCreditsStartingBeforeTheThresholdCountTheWatchThere() = runTest {
        val f = fixture()
        f.connect(ANILIST)
        f.observer.onVideoStarted(anime, "kitsu:100:1", PlaybackPosition(0, 1_000_000, creditsStartMillis = 600_000), playing = true)
        f.observer.onPosition(PlaybackPosition(599_000, 1_000_000, creditsStartMillis = 600_000))
        f.drain()
        assertEquals(emptyList(), f.calls(ANILIST), "before the credits and the threshold")
        f.observer.onPosition(PlaybackPosition(600_000, 1_000_000, creditsStartMillis = 600_000))
        f.drain()
        assertEquals(listOf("watched 100@1"), f.calls(ANILIST), "60 %: the credits start before the profile's 70 %")
    }

    @Test
    fun aStopThatDidNotLandFallsBackToAHistoryWriteQueuedIfThatFailsToo() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.play(1, 3)
        f.tracker(TRAKT).failNext("scrobble", TrackerException.Api(TRAKT, 502))
        f.tracker(SIMKL).failNext("scrobble", TrackerException.Network(SIMKL, IllegalStateException("offline")))
        f.tracker(SIMKL).failNext("watched", TrackerException.Network(SIMKL, IllegalStateException("offline")))
        f.observer.onVideoStopped(at(950))
        f.drain()
        // The stops failed, so the services' own completion rule never ran: the watch goes as a history write.
        assertEquals(listOf("scrobble START 0", "scrobble STOP 95", "watched tt0111161"), f.calls(TRAKT))
        assertEquals(listOf("scrobble START 0", "scrobble STOP 95", "watched tt0111161"), f.calls(SIMKL))
        assertEquals(listOf("simkl simkl|movie|imdb=tt0111161 watched=true attempts=0"), f.queued())
    }

    @Test
    fun jumpsOfMoreThanFiveSecondsAreSeeksWithinEachServicesPolicy() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.play(1, 10)
        f.observer.onPosition(at(15)) // 5 s: playback, not a seek
        f.advance(1.seconds)
        f.observer.onPosition(at(40)) // a seek
        f.advance(2.seconds)
        f.observer.onPosition(at(10)) // back again, but within Trakt's 5 s seek window
        f.advance(4.seconds)
        f.observer.onPosition(at(300)) // 6 s after the first seek
        f.advance(1.seconds)
        f.observer.onPlayingChanged(false, at(300))
        f.advance(1.seconds)
        f.observer.onPosition(at(600)) // a seek while paused: the service thinks nothing plays
        f.drain()
        assertEquals(listOf("scrobble START 0", "scrobble SEEK 4", "scrobble SEEK 30", "scrobble PAUSE 30"), f.calls(TRAKT))
        assertEquals(listOf("scrobble START 0", "scrobble PAUSE 30"), f.calls(SIMKL), "Simkl takes no seek reports")
    }

    @Test
    fun repeatedStatesWaitOutEachServicesThrottle() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.settle()
        f.observer.onPlayingChanged(true, at(0)) // the player's echo of the start
        f.advance(2.seconds)
        f.observer.onPlayingChanged(true, at(2)) // a reload: the same state again
        f.advance(19.seconds)
        f.observer.onPlayingChanged(true, at(21)) // 21 s after the start: Simkl's 20 s lock is over, Trakt's 30 s isn't
        f.advance(10.seconds)
        f.observer.onPlayingChanged(true, at(31))
        f.advance(1.seconds)
        f.observer.onPlayingChanged(false, at(32))
        f.advance(500.milliseconds)
        f.observer.onPlayingChanged(false, at(32)) // within the 1 s debounce
        f.advance(2.seconds)
        f.observer.onPlayingChanged(false, at(32)) // a pause may repeat after it
        f.drain()
        val trakt = f.log.filter { it.service == TRAKT }.map { it.op to it.atMillis }
        assertEquals(listOf("scrobble START 0" to 0L, "scrobble START 3" to 31_000L, "scrobble PAUSE 3" to 32_000L, "scrobble PAUSE 3" to 34_500L), trakt)
        val simkl = f.log.filter { it.service == SIMKL }.map { it.op to it.atMillis }
        assertEquals(listOf("scrobble START 0" to 0L, "scrobble START 2" to 21_000L, "scrobble PAUSE 3" to 32_000L, "scrobble PAUSE 3" to 34_500L), simkl)
    }

    @Test
    fun servicesGoInParallelAndEachOneInOrderWithItsWriteSpacing() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.tracker(TRAKT).latency = 10.seconds
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.settle()
        f.advance(1.seconds)
        f.observer.onPlayingChanged(false, at(1))
        f.advance(1.seconds)
        f.observer.onPlayingChanged(true, at(1))
        f.advance(40.seconds)
        // Simkl doesn't wait on Trakt's slow answers.
        assertEquals(listOf("scrobble START 0" to 0L, "scrobble PAUSE 0" to 1_000L, "scrobble START 0" to 2_000L), f.log.filter { it.service == SIMKL }.map { it.op to it.atMillis })
        // Trakt's reports go one at a time, in order, a second apart (its write limit) after each answer.
        assertEquals(
            listOf("scrobble START 0" to 10_000L, "scrobble PAUSE 0" to 21_000L, "scrobble START 0" to 32_000L),
            f.log.filter { it.service == TRAKT }.map { it.op to it.atMillis },
        )
    }

    @Test
    fun contentFiltersAndTheEnabledToggleDecideWhoHearsAboutWhat() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.hub.accounts.setContentFilter(f.profileId, TRAKT, TrackerContentType.MOVIE, on = false)
        f.hub.accounts.setContentFilter(f.profileId, TRAKT, TrackerContentType.ANIME, on = false)
        f.hub.accounts.setEnabled(f.profileId, SIMKL, false)
        f.settle()
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.drain()
        f.observer.onVideoStopped(at(10))
        val show = seriesMeta("tt0903747", 2)
        f.observer.onVideoStarted(show, "tt0903747:1:1", at(0), playing = true)
        f.drain()
        assertEquals(listOf("scrobble START 0"), f.calls(TRAKT), "series only")
        assertEquals(TrackerContentType.SERIES, f.log.single().context?.contentType)
        assertEquals(emptyList(), f.calls(SIMKL), "disabled")

        // Filtering series out mid-playback stops the reports.
        f.hub.accounts.setContentFilter(f.profileId, TRAKT, TrackerContentType.SERIES, on = false)
        f.settle()
        f.observer.onPlayingChanged(false, at(5))
        f.drain()
        assertEquals(listOf("scrobble START 0"), f.calls(TRAKT))
    }

    @Test
    fun aTvChannelIsLiveAndNeverReported() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.settle()
        f.observer.onVideoStarted(MetaItem(MetaItemPreview(id = "tt0111161", type = "tv", name = "A channel")), "tt0111161", at(0), playing = true)
        f.drain()
        f.observer.onVideoStopped(at(600))
        f.drain()
        assertEquals(emptyList(), f.calls(TRAKT))
    }

    @Test
    fun animeIsWhatTheIdsSayItIsWheneverAnAccountTreatsAnimeApart() = runTest {
        val f = fixture()
        f.resolver.animeTt["tt2560140"] = 16498
        f.connect(TRAKT)
        val show = seriesMeta("tt2560140", 3)
        f.observer.onVideoStarted(show, "tt2560140:1:1", at(0), playing = true)
        f.drain()
        // Anime and series both go to Trakt: what the item is changes nothing, so no mapping download.
        assertEquals(false, f.resolver.calls.last().second)
        assertEquals(listOf("scrobble START 0"), f.calls(TRAKT))
        f.observer.onVideoStopped(at(1))
        f.drain()
        f.log.clear()

        // Trakt's anime switch off: an IMDb-id anime is anime by the mappings, with no anime tracker connected.
        f.hub.accounts.setContentFilter(f.profileId, TRAKT, TrackerContentType.ANIME, on = false)
        f.settle()
        f.observer.onVideoStarted(show, "tt2560140:1:2", at(0), playing = true)
        f.drain()
        assertEquals(true, f.resolver.calls.last().second)
        assertEquals(emptyList(), f.calls(TRAKT), "anime is filtered out on Trakt")
        f.observer.onVideoStopped(at(1))
        f.coordinator.marks.onMarked(WatchedMark.Season(show, 1, watched = true))
        f.drain()
        assertEquals(emptyList(), f.calls(TRAKT), "its marks too")

        f.connect(ANILIST)
        f.observer.onVideoStarted(show, "tt2560140:1:2", at(0), playing = true)
        f.play(1, 800)
        f.drain()
        assertEquals(emptyList(), f.calls(TRAKT))
        assertEquals(listOf("watched 16498@2"), f.calls(ANILIST))
    }

    @Test
    fun trackerAndResolverFailuresStayInsideTheCoordinator() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.resolver.failing += "tt0000001"
        f.observer.onVideoStarted(movieMeta("tt0000001"), "tt0000001", at(0), playing = true)
        f.observer.onPlayingChanged(false, at(0))
        f.observer.onVideoStopped(at(0))
        f.drain()
        assertEquals(emptyList(), f.calls(TRAKT), "unresolved: nothing to report")

        f.tracker(TRAKT).failNext("scrobble", IllegalStateException("a bug in a tracker"))
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.advance(2.seconds)
        f.observer.onPlayingChanged(false, at(2))
        f.advance(2.seconds)
        f.observer.onPlayingChanged(true, at(4))
        f.drain()
        // The start failed, so there is no session to pause; the next start still goes out.
        assertEquals(listOf("scrobble START 0", "scrobble START 0"), f.calls(TRAKT))
        assertEquals(emptyList(), f.queued(), "live reports are never queued")
    }

    @Test
    fun aPlaybackWhoseStartNeverLandedIsRecordedByAHistoryWrite() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.tracker(TRAKT).failNext("scrobble", TrackerException.Api(TRAKT, 502))
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.play(1, 3)
        f.observer.onPlayingChanged(false, at(3))
        f.advance(2.seconds)
        f.observer.onVideoStopped(at(950))
        f.drain()
        // Trakt has no session: no pause or stop invents one, and the watch is written to its history, once.
        assertEquals(listOf("scrobble START 0", "watched tt0111161"), f.calls(TRAKT))
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun aPlaybackOpenedPausedStartsOnTheTrackersOnlyOnceItPlays() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        // A watch-together host opens the video paused for the room.
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = false)
        f.advance(5.seconds)
        f.drain()
        assertEquals(emptyList(), f.calls(TRAKT), "nothing plays")
        assertEquals(emptyList(), f.calls(SIMKL))
        f.observer.onPlayingChanged(true, at(0))
        f.play(1, 3)
        f.observer.onPlayingChanged(false, at(3))
        f.drain()
        assertEquals(listOf("scrobble START 0", "scrobble PAUSE 0"), f.calls(TRAKT))
        assertEquals(listOf("scrobble START 0", "scrobble PAUSE 0"), f.calls(SIMKL))

        // One that never plays never shows as playing, nor as a paused session.
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = false)
        f.observer.onVideoStopped(at(0))
        f.drain()
        assertEquals(listOf("scrobble START 0", "scrobble PAUSE 0", "scrobble STOP 0"), f.calls(TRAKT), "the first playback's stop only")
    }

    @Test
    fun marksReachEachServiceInTheOrderTheyWereMade() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        val show = seriesMeta("tt0903747", 2)
        f.metas["tt0903747"] = show
        f.metaLatency = 5.seconds
        // Watched from a library card, whose episodes take a while to fetch, then undone at once from Detail, which has them.
        f.coordinator.marks.onMarked(WatchedMark.Title("tt0903747", "series", null, watched = true))
        f.coordinator.marks.onMarked(WatchedMark.Title("tt0903747", "series", show, watched = false))
        f.drain()
        assertEquals(listOf("watched tt0903747:1:1, tt0903747:1:2", "unwatched tt0903747:1:1, tt0903747:1:2"), f.calls(TRAKT))
    }

    @Test
    fun manualMarksGoToTheTrackersThatCanTakeThem() = runTest {
        val f = fixture()
        f.resolver.kitsuImdb["kitsu:100"] = "tt5626028"
        f.connect(TRAKT)
        f.connect(ANILIST)
        val marks = f.coordinator.marks

        marks.onMarked(WatchedMark.Title("tt0111161", "movie", null, watched = true))
        f.drain()
        assertEquals(listOf("watched tt0111161"), f.calls(TRAKT))
        assertEquals(listOf("watched null@null"), f.calls(ANILIST), "a movie goes to every tracker; the list one ignores what it can't map")
        f.log.clear()

        // One episode unwatched: a series counter can't lose one episode.
        marks.onMarked(WatchedMark.Video(anime, "kitsu:100:2", watched = false))
        f.drain()
        assertEquals(listOf("unwatched kitsu:100:2"), f.calls(TRAKT))
        assertEquals(emptyList(), f.calls(ANILIST))

        // A season: one request for its episodes on Trakt (the one not out yet left alone), one update on the list.
        val now = f.clock.now()
        val season = anime.copy(videos = anime.videos.mapIndexed { i, v -> if (i == 2) v.copy(released = now + 2.days) else v })
        marks.onMarked(WatchedMark.Season(season, 1, watched = true))
        f.drain()
        assertEquals(listOf("unwatched kitsu:100:2", "watched kitsu:100:1, kitsu:100:2"), f.calls(TRAKT))
        assertEquals(listOf("watched 100@2"), f.calls(ANILIST))
        f.log.clear()

        // The whole show from the library, which has no episodes: the addons' meta has them. Unwatching resets the list
        // entry from its first episode.
        f.metas["kitsu:100"] = season
        marks.onMarked(WatchedMark.Title("kitsu:100", "series", null, watched = false))
        f.drain()
        assertEquals(listOf("unwatched kitsu:100:1, kitsu:100:2, kitsu:100:3"), f.calls(TRAKT))
        assertEquals(listOf("unwatched 100@1"), f.calls(ANILIST))
        assertTrue(f.queued().isEmpty())
    }

    @Test
    fun aShowMarkIsOneHistoryRequestPerHundredEpisodesWithTheServicesSpacing() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        val show = MetaItem(
            MetaItemPreview(id = "tt0903747", type = "series", name = "A long show"),
            videos = (1..250).map { Video(id = "tt0903747:${(it - 1) / 50 + 1}:${(it - 1) % 50 + 1}", seriesInfo = SeriesInfo((it - 1) / 50 + 1, (it - 1) % 50 + 1)) },
        )
        f.coordinator.marks.onMarked(WatchedMark.Title("tt0903747", "series", show, watched = true))
        f.drain()
        // Three requests a service, Trakt's a second apart (its write limit), every episode in exactly one.
        val trakt = f.log.filter { it.service == TRAKT }
        assertEquals(listOf(100, 100, 50), trakt.map { it.op.split(", ").size })
        assertEquals(listOf(0L, 1_000L, 2_000L), trakt.map { it.atMillis })
        assertEquals(show.videos.map { it.id }, trakt.flatMap { it.op.removePrefix("watched ").split(", ") })
        assertEquals(listOf(100, 100, 50), f.log.filter { it.service == SIMKL }.map { it.op.split(", ").size })
        assertTrue(f.queued().isEmpty())
    }
}
