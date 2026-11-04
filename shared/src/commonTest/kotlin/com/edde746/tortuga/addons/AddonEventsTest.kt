package com.edde746.tortuga.addons

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.playback.session.PlayPurpose
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.PlaybackPosition
import com.edde746.tortuga.playback.session.SERIES
import com.edde746.tortuga.playback.session.SessionHarness
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The `player` events a real playback session sends, as stremio-core's player sends them (its
 * `unit_tests/player/addon_events.rs`): a GET of `/player/{type}/{videoId}/action={start|pause|stop}&currentTime={ms}
 * &duration={ms}.json` on every addon whose manifest declares `player` for the video's type and id, none on any other.
 */
class AddonEventsTest {
    /** An addon at `https://<host>/manifest.json` whose `player` and `library` cover [types] and ids starting with [idPrefixes]. */
    private fun receiver(host: String, types: String, idPrefixes: String) = Descriptor(
        Manifest.decode(
            """{"id":"org.example.$host","version":"1.0.0","name":"$host","types":["movie","series"],"resources":[""" +
                """{"name":"player","types":$types,"idPrefixes":$idPrefixes},{"name":"library","types":$types,"idPrefixes":$idPrefixes}]}""",
        ),
        "https://$host/manifest.json".transportUrl(),
    )

    private val tracker = receiver("tracker.example", """["movie","series"]""", """["tt"]""")
    private val seriesOnly = receiver("series.example", """["series"]""", """["tt"]""")
    private val otherIds = receiver("kitsu.example", """["movie","series"]""", """["kitsu:"]""")
    private val noPlayer = streamAddon("plain")

    /**
     * A session ([SessionHarness]) observed by the events of these four addons, its files served by `cdn.example`;
     * [sent] is each request an addon got, as `host path`, in order.
     */
    private inner class World(scope: TestScope) {
        val h = SessionHarness(scope)
        val sent = mutableListOf<String>()

        init {
            h.serveCdn(lcgBytes(300_000))
            for (addon in listOf(tracker, seriesOnly, otherIds, noPlayer)) {
                val host = addon.transportUrl.removePrefix("https://").substringBefore('/')
                h.routes[host] = { request ->
                    sent += "$host ${request.url.encodedPath}"
                    json("{}")
                }
            }
            val transport = AddonTransport(h.http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler))
            val events = AddonEvents({ listOf(tracker, seriesOnly, otherIds, noPlayer) }, transport, scope.backgroundScope)
            h.session.observe(events.player())
        }
    }

    private fun movie(vararg alternatives: String) = PlayRequest(
        MOVIE, MOVIE.id, choice(urlStream("https://cdn.example/movie.mkv")), alternatives.map { choice(urlStream(it)) },
    )

    private fun moviePath(action: String, millis: Long, durationMillis: Long) =
        "tracker.example /player/movie/tt2/action=$action&currentTime=$millis&duration=$durationMillis.json"

    @Test
    fun openedPlayingItSendsStartThenPauseAndStartAsItPausesAndResumesAndOneStopWhenLeft() = runTest {
        val w = World(this)
        w.h.start(movie())
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        w.h.playTo(10.0)
        w.h.engine.setPaused(true)
        w.h.settle()
        w.h.engine.setPaused(false)
        w.h.playTo(20.0)
        w.h.session.close()
        w.h.settle()

        assertEquals(
            listOf(
                moviePath("start", 0, 1_000_000),
                moviePath("pause", 10_000, 1_000_000),
                moviePath("start", 10_000, 1_000_000),
                moviePath("stop", 20_000, 1_000_000),
            ),
            w.sent,
        )
    }

    @Test
    fun openedPausedItSendsStartOnlyOnceItPlays() = runTest {
        val w = World(this)
        w.h.start(movie().copy(startPaused = true))
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        assertEquals(emptyList(), w.sent, "paused on its first frame: nothing plays yet")

        w.h.engine.setPaused(false)
        w.h.playTo(5.0)
        w.h.session.close()
        w.h.settle()
        assertEquals(listOf(moviePath("start", 0, 1_000_000), moviePath("stop", 5_000, 1_000_000)), w.sent)
    }

    @Test
    fun aSeekSendsStartWhilePlayingAndPauseWhilePausedAtWhereItLands() = runTest {
        val w = World(this)
        w.h.start(movie())
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        w.h.playTo(10.0)
        // The player seeks (the store tells the session, then the engine), and the engine gets there.
        w.h.session.onSeek(500.0)
        w.h.engine.seek(500.0, exact = false)
        w.h.advance(1.seconds)
        w.h.playTo(505.0)
        w.h.engine.setPaused(true)
        w.h.settle()
        w.h.session.onSeek(100.0)
        w.h.engine.seek(100.0, exact = false)
        w.h.advance(1.seconds)
        w.h.session.close()
        w.h.settle()

        assertEquals(
            listOf(
                moviePath("start", 0, 1_000_000),
                moviePath("start", 500_000, 1_000_000),
                moviePath("pause", 505_000, 1_000_000),
                moviePath("pause", 100_000, 1_000_000),
                moviePath("stop", 100_000, 1_000_000),
            ),
            w.sent,
        )
    }

    @Test
    fun playingToTheEndSendsOneStopAndNoPause() = runTest {
        val w = World(this)
        w.h.start(movie())
        w.h.engine.open(duration = 100.0)
        w.h.settle()
        w.h.playTo(100.0)
        w.h.engine.report { it.copy(eof = true) }
        w.h.advance(30.seconds)
        w.h.session.close()
        w.h.settle()

        assertEquals(listOf(moviePath("start", 0, 100_000), moviePath("stop", 100_000, 100_000)), w.sent)
    }

    @Test
    fun switchingToAnotherStreamOfTheVideoSendsNothing() = runTest {
        val w = World(this)
        w.h.start(movie("https://cdn.example/other.mkv"))
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        w.h.playTo(10.0)
        w.h.session.playNextStream()
        w.h.settle()
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        w.h.playTo(20.0)
        w.h.session.close()
        w.h.settle()

        assertEquals(listOf("https://cdn.example/movie.mkv", "https://cdn.example/other.mkv"), w.h.engine.loads.map { it.url })
        assertEquals(listOf(moviePath("start", 0, 1_000_000), moviePath("stop", 20_000, 1_000_000)), w.sent)
    }

    @Test
    fun onlyTheAddonsWhosePlayerCoversTheVideosTypeAndIdGetItsEvents() = runTest {
        val w = World(this)
        w.h.start(PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://cdn.example/e1.mkv"))))
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        // Another video in the same player: the episode is left, the movie starts.
        w.h.start(movie())
        w.h.engine.open(duration = 2000.0)
        w.h.settle()
        w.h.session.close()
        w.h.settle()

        val episode = "/player/series/tt1%3A1%3A1"
        assertEquals(
            listOf(
                "tracker.example $episode/action=start&currentTime=0&duration=1000000.json",
                "series.example $episode/action=start&currentTime=0&duration=1000000.json",
                "tracker.example $episode/action=stop&currentTime=0&duration=1000000.json",
                "series.example $episode/action=stop&currentTime=0&duration=1000000.json",
                moviePath("start", 0, 2_000_000),
                moviePath("stop", 0, 2_000_000),
            ),
            w.sent,
            "an addon for other ids or without player gets nothing; one for series gets the episode only",
        )
    }

    @Test
    fun aTrailerSendsNothingAndTheVideoPlayedAfterItDoes() = runTest {
        val w = World(this)
        w.h.start(PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://cdn.example/trailer.mkv")), purpose = PlayPurpose.TRAILER))
        w.h.engine.open(duration = 120.0)
        w.h.settle()
        w.h.playTo(10.0)
        w.h.engine.setPaused(true)
        w.h.settle()
        w.h.engine.setPaused(false)
        w.h.playTo(120.0)
        w.h.engine.report { it.copy(eof = true) }
        w.h.advance(5.seconds)
        assertEquals(emptyList(), w.sent, "a trailer isn't the video")

        w.h.start(PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://cdn.example/e1.mkv"))))
        w.h.engine.open(duration = 1000.0)
        w.h.settle()
        assertEquals(
            listOf(
                "tracker.example /player/series/tt1%3A1%3A1/action=start&currentTime=0&duration=1000000.json",
                "series.example /player/series/tt1%3A1%3A1/action=start&currentTime=0&duration=1000000.json",
            ),
            w.sent,
        )
    }

    @Test
    fun aWatchTogetherProbeSendsNothingUntilItIsKept() = runTest {
        val w = World(this)
        w.h.start(movie().copy(startPositionSeconds = 600.0, startPaused = true, purpose = PlayPurpose.PROBE))
        w.h.engine.open(duration = 3000.0)
        w.h.settle()
        w.h.engine.setPaused(false)
        w.h.playTo(620.0)
        w.h.engine.setPaused(true)
        w.h.session.onSeek(900.0)
        w.h.engine.seek(900.0, exact = true)
        w.h.advance(5.seconds)
        assertEquals(emptyList(), w.sent, "a candidate measured for the room isn't watched")

        w.h.session.keepProbe()
        w.h.settle()
        w.h.engine.setPaused(false)
        w.h.settle()
        w.h.session.close()
        w.h.settle()
        assertEquals(listOf(moviePath("start", 900_000, 3_000_000), moviePath("stop", 900_000, 3_000_000)), w.sent)
    }

    /**
     * stremio-core's `playback_before_meta_is_loaded_sends_start`: a video that plays before its meta and length are in
     * sends its start at 0 of 0, then its pause and stop where it is. Tortuga's session hands the observer the meta it
     * plays from, so this is the observer on its own, with a meta of the id and type alone.
     */
    @Test
    fun aVideoStartedBeforeItsMetaAndLengthAreKnownSendsStartAtZeroOfZero() = runTest {
        val sent = mutableListOf<String>()
        val http = mockClient { request ->
            sent += "${request.url.host} ${request.url.encodedPath}"
            json("{}")
        }
        val events = AddonEvents(
            { listOf(tracker, otherIds) },
            AddonTransport(http, parseDispatcher = StandardTestDispatcher(testScheduler)),
            backgroundScope,
        )
        val meta = MetaItem(MetaItemPreview(id = "tt123456", type = "series"))
        val player = events.player()

        player.onVideoStarted(meta, "tt123456:1:1", PlaybackPosition(0, 0), playing = true)
        player.onPosition(PlaybackPosition(600_000, 3_600_000))
        player.onPlayingChanged(false, PlaybackPosition(600_000, 3_600_000))
        player.onVideoStopped(PlaybackPosition(600_000, 3_600_000))
        runCurrent()

        val url = "tracker.example /player/series/tt123456%3A1%3A1"
        assertEquals(
            listOf(
                "$url/action=start&currentTime=0&duration=0.json",
                "$url/action=pause&currentTime=600000&duration=3600000.json",
                "$url/action=stop&currentTime=600000&duration=3600000.json",
            ),
            sent,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun eachAddonGetsItsEventsOneAfterTheOtherWhileAnotherAddonIsntHeldUp() = runTest {
        val answered = mutableListOf<String>()
        val http = mockClient { request ->
            val action = request.url.encodedPath.substringAfter("action=").substringBefore('&')
            // The tracker is slow to answer the start; the pause and the stop that follow it answer at once.
            if (request.url.host == "tracker.example" && action == "start") delay(2.seconds)
            answered += "${request.url.host} $action at ${testScheduler.currentTime}"
            json("{}")
        }
        val other = receiver("other.example", """["movie"]""", """["tt"]""")
        val events = AddonEvents(
            { listOf(tracker, other) },
            AddonTransport(http, parseDispatcher = StandardTestDispatcher(testScheduler)),
            backgroundScope,
        )
        val player = events.player()

        player.onVideoStarted(MOVIE, MOVIE.id, PlaybackPosition(0, 3_600_000), playing = true)
        player.onPlayingChanged(false, PlaybackPosition(1_000, 3_600_000))
        player.onVideoStopped(PlaybackPosition(1_000, 3_600_000))
        // The events go out in the background: time moves past the slow answer by hand.
        advanceTimeBy(3.seconds)
        runCurrent()

        assertEquals(
            listOf("start", "pause", "stop").map { "other.example $it at 0" } + listOf("start", "pause", "stop").map { "tracker.example $it at 2000" },
            answered,
        )
    }
}
