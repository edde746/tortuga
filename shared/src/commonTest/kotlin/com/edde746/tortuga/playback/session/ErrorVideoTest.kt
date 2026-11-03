package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.LibraryItemState
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.playback.ErrorVideoSign
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import com.edde746.tortuga.playback.resolve.serveFile
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.presentation.player.FakeHost
import com.edde746.tortuga.presentation.player.FakePreferences
import com.edde746.tortuga.presentation.player.PlayerChoices
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerPrompt
import com.edde746.tortuga.presentation.player.PlayerStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A stream that answers with an error video ([ErrorVideoSign]: an addon's clip of a message) in place of the
 * episode: the player shows it as an error and never plays or records it as the episode. The library's progress,
 * duration, watched marks and pointer, the last stream, the trackers and the resume point stay as they were.
 */
class ErrorVideoTest {
    private val file = lcgBytes(300_000)
    private val remembered = urlStream("https://addon.example/e1-remembered")

    /**
     * `addon.example/failed` redirects to an addon's "failed access" clip; any other `addon.example/<name>` to
     * `cdn.example/<name>.mkv`, whose length is what the test's engine opens it with.
     */
    private fun SessionHarness.links() {
        serveCdn(file)
        routes["clips.example"] = { request -> serveFile(request, file) }
        routes["addon.example"] = { request ->
            when (val name = request.url.segments.last()) {
                "failed" -> redirect("https://clips.example/videos/failed_access_v2.mp4")
                else -> redirect("https://cdn.example/$name.mkv")
            }
        }
    }

    /** S1E1 half watched, 12:00 of 24:00, last played from [remembered]: its library state. */
    private suspend fun SessionHarness.halfWatched(): LibraryItemState {
        data.library.upsertFromMeta(profileId, SERIES.librarySnapshot())
        data.library.recordProgress(profileId, SERIES.id, E1, 720_000, 1_440_000)
        data.streamHistory.record(profileId, SERIES.id, E1, AddonJson.encodeToString(Stream.serializer(), remembered), STREAMS_URL)
        return assertNotNull(item(SERIES.id)).state
    }

    private fun firstEpisode(name: String) = PlayRequest(SERIES, E1, choice(urlStream("https://addon.example/$name")))

    private fun SessionHarness.observer() = RecordingObserver().also { session.observe(it) }

    private suspend fun SessionHarness.lastStream() =
        (data.streamHistory.lastStream(profileId, SERIES.id, E1)?.stream?.source as? StreamSource.Url)?.url.orEmpty()

    private suspend fun SessionHarness.assertNothingRecorded(before: LibraryItemState, observer: RecordingObserver) {
        assertEquals(before, item(SERIES.id)?.state, "the library's progress, duration, watched marks and pointer")
        assertTrue("e1-remembered" in lastStream(), "the last stream stays: ${lastStream()}")
        assertEquals(emptyList(), observer.events, "the trackers hear nothing")
    }

    /**
     * The engine's report of the last loaded file's first frame, at the load's start, as it comes when mpv hands its
     * client the queued `playback-restart` before its property changes: no length yet (every engine resets it on load).
     */
    private fun SessionHarness.firstFrameWithoutLength() {
        val start = engine.loads.last().startSeconds
        engine.report { it.copy(loaded = true, durationSeconds = 0.0, positionSeconds = start, buffering = false, playbackRestarts = 1) }
        settle()
    }

    @Test
    fun anErrorVideoKnownByItsLinkNeverLoadsAndRecordsNothing() = runTest {
        val h = SessionHarness(this)
        h.links()
        val before = h.halfWatched()
        val observer = h.observer()
        h.start(firstEpisode("failed"))

        assertEquals(emptyList(), h.engine.loads, "the clip never loads")
        assertEquals(PlaybackFailure.ErrorVideo(ErrorVideoSign.Url, isEpisode = true, isMovie = false), h.session.info.value.failure)
        assertEquals(ErrorVideoSign.Url, h.session.info.value.errorVideo)
        h.assertNothingRecorded(before, observer)

        // Played from the start: the resume point stays where it was too.
        h.start(firstEpisode("failed").copy(startPositionSeconds = 0.0))
        assertEquals(ErrorVideoSign.Url, h.session.info.value.errorVideo)
        h.assertNothingRecorded(before, observer)

        // Another stream, the episode itself, is picked in the Streams sheet: it plays and records as usual.
        h.session.chooseStream()
        h.settle()
        assertEquals(SessionEvent.ChooseStream(SERIES, E1, notice = null), h.events.last())
        h.start(firstEpisode("e1-other"))
        assertNull(h.session.info.value.errorVideo)
        assertEquals(720.0, h.engine.loads.single().startSeconds, "from the resume point")
        h.engine.open(duration = 1440.0)
        h.settle()
        assertNull(h.session.info.value.failure)
        assertTrue("e1-other" in h.lastStream())
        assertEquals("started 720000/1440000", observer.events.first())
        h.playTo(740.0)
        h.engine.setPaused(true)
        h.settle()
        assertEquals(740_000, h.item(SERIES.id)?.state?.timeOffset)
    }

    @Test
    fun aResumedStreamWhoseAddonNowLinksAnErrorVideoIsCaughtAtResolveAndRecordsNothing() = runTest {
        // A case seen on 17 Oct: Resume asks the last stream's addon again (StreamAutoPicker.lastPlayed), whose
        // answer has the same release under a new link, and that link gives an addon's "not ready" clip.
        val h = SessionHarness(this)
        h.links()
        val before = h.halfWatched()
        val saved = remembered.copy(behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.1080p.mkv"))
        h.data.streamHistory.record(h.profileId, SERIES.id, E1, AddonJson.encodeToString(Stream.serializer(), saved), STREAMS_URL)
        var clipAsked = false
        h.routes["streams.example.com"] = {
            json("""{"streams":[{"url":"https://addon-b.example/playback/now/e1","behaviorHints":{"filename":"Show.S01E01.1080p.mkv"}}]}""")
        }
        h.routes["addon-b.example"] = { redirect("https://clips.example/videos/not_ready.mp4") }
        h.routes["clips.example"] = { request ->
            clipAsked = true
            serveFile(request, file)
        }
        val observer = h.observer()
        val request = assertNotNull(
            StreamAutoPicker(h.aggregator, h.data.streamHistory).lastPlayed(h.profileId, SERIES, E1, h.installed, StreamFilter.NONE),
            "the release found again in its addon's answer",
        )
        h.start(request)

        assertTrue(clipAsked, "the fresh link is the one resolved")
        assertEquals(emptyList(), h.engine.loads, "the clip never loads")
        assertEquals(PlaybackFailure.ErrorVideo(ErrorVideoSign.Url, isEpisode = true, isMovie = false), h.session.info.value.failure)
        h.assertNothingRecorded(before, observer)
    }

    @Test
    fun anErrorVideoKnownByItsLengthIsPausedShownAndRecordsNothingEvenPlayedToItsEnd() = runTest {
        val h = SessionHarness(this)
        h.links()
        val before = h.halfWatched()
        val observer = h.observer()
        // An addon that sends its clip from the playback link itself, with no size: only its length tells it.
        h.start(firstEpisode("e1-unsized"))
        assertEquals(720.0, h.engine.loads.single().startSeconds)
        h.engine.open(duration = 120.0)
        h.settle()

        val sign = ErrorVideoSign.Duration(120.0, 1440.0)
        val shown = PlaybackFailure.ErrorVideo(sign, isEpisode = true, isMovie = false)
        assertEquals(shown, h.session.info.value.failure)
        assertEquals(sign, h.session.info.value.errorVideo)
        assertTrue(h.engine.state.paused, "paused as it opens")
        // The engine plays on to the clip's end all the same.
        h.engine.setPaused(false)
        h.engine.report { it.copy(positionSeconds = 120.0, eof = true) }
        h.advance(60.seconds)
        assertEquals(shown, h.session.info.value.failure)
        h.assertNothingRecorded(before, observer)

        // Watch the message: the clip from its start, recording nothing, and the error again at its end.
        h.session.watchErrorVideo()
        h.settle()
        assertEquals(listOf(720.0, 0.0), h.engine.loads.map { it.startSeconds })
        assertNull(h.session.info.value.failure)
        assertEquals(sign, h.session.info.value.errorVideo, "still the error video while its message plays")
        h.engine.open(duration = 120.0)
        h.settle()
        assertFalse(h.engine.state.paused, "the message plays")
        h.playTo(90.0)
        h.session.onSeek(30.0)
        h.engine.seek(30.0, exact = true)
        h.playTo(120.0)
        h.engine.report { it.copy(eof = true) }
        h.settle()
        assertEquals(shown, h.session.info.value.failure)
        assertTrue(h.engine.state.paused)
        h.assertNothingRecorded(before, observer)
        assertTrue(h.events.isEmpty(), "no session events: ${h.events}")

        // Retry: this time the addon sends the episode, which plays and records as usual.
        h.session.retry()
        h.settle()
        assertNull(h.session.info.value.failure)
        assertNull(h.session.info.value.errorVideo)
        assertEquals(720.0, h.engine.loads.last().startSeconds, "from the resume point")
        h.engine.open(duration = 1440.0)
        h.settle()
        assertNull(h.session.info.value.failure)
        assertTrue("e1-unsized" in h.lastStream())
        assertEquals("started 720000/1440000", observer.events.first())
        h.playTo(740.0)
        h.engine.setPaused(true)
        h.settle()
        val state = assertNotNull(h.item(SERIES.id)).state
        assertEquals(740_000, state.timeOffset)
        assertEquals(1_440_000, state.duration)
    }

    @Test
    fun aShortVideoPlayedForTheFirstTimeIsRecordedAsAnyOther() = runTest {
        val h = SessionHarness(this)
        h.links()
        val observer = h.observer()
        // A 90 s short film: no length in the library, no size from the addon.
        val short = PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/short")))
        h.start(short)
        h.engine.open(duration = 90.0)
        h.settle()
        assertNull(h.session.info.value.failure)
        assertNull(h.session.info.value.errorVideo)
        assertEquals("started 0/90000", observer.events.first())
        h.playTo(90.0)
        h.engine.report { it.copy(eof = true) }
        h.settle()
        val state = assertNotNull(h.item(MOVIE.id)).state
        assertEquals(90_000, state.duration)
        assertEquals(1, state.timesWatched)
        assertNotNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id))

        // Played again: it ran as short before, so it is the film again.
        h.start(short)
        h.engine.open(duration = 90.0)
        h.settle()
        assertNull(h.session.info.value.errorVideo)
        assertNull(h.session.info.value.failure)
    }

    /**
     * mpv hands its client queued events (`file-loaded`, `playback-restart`) before property changes, so the engine's
     * report of an error video's first frame can come before its length, which follows with its other values in any
     * order. It plays on, recording nothing, until the length comes and tells it.
     */
    @Test
    fun anErrorVideoWhoseLengthComesAfterItsFirstFrameRecordsNothing() = runTest {
        val seekable: SessionHarness.() -> Unit = { engine.report { it.copy(seekable = true) } }
        val position: SessionHarness.() -> Unit = { engine.play(720.5) }
        val end: SessionHarness.() -> Unit = { engine.report { it.copy(eof = true) } }
        val nineSeconds: SessionHarness.() -> Unit = { playTo(729.0) }
        // What comes before the length.
        val orders: Map<String, List<SessionHarness.() -> Unit>> = mapOf(
            "the length next" to emptyList(),
            "seekable and the position first" to listOf(seekable, position),
            "its end first" to listOf(end),
            "9 s later" to listOf(nineSeconds),
        )
        val shown = PlaybackFailure.ErrorVideo(ErrorVideoSign.Duration(120.0, 1440.0), isEpisode = true, isMovie = false)
        for ((order, steps) in orders) {
            val h = SessionHarness(this)
            h.links()
            val before = h.halfWatched()
            val observer = h.observer()
            h.start(firstEpisode("e1-unsized"))
            h.firstFrameWithoutLength()
            for (step in steps) {
                h.step()
                h.settle()
            }
            assertNull(h.session.info.value.failure, "$order: not told before its length comes")
            assertNull(h.session.openedFile.value, "$order: not reported open before its length comes")
            h.assertNothingRecorded(before, observer)

            h.engine.report { it.copy(durationSeconds = 120.0) }
            h.settle()
            assertEquals(shown, h.session.info.value.failure, order)
            assertTrue(h.engine.state.paused, "$order: paused")
            h.advance(30.seconds)
            h.assertNothingRecorded(before, observer)
        }
    }

    /**
     * The player's store over the session: an error video opened at the video's resume point, past its own end, is
     * reported loaded at its end with its length before its first frame, and up next never comes up for it, nor plays
     * the next episode (Windows, 23 Oct: a 2-min slate opened at 12:00 showed up next until the slate was told).
     */
    @Test
    fun anErrorVideoOpenedPastItsEndNeverBringsUpNext() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.halfWatched()
        val config = PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis = 500)
        val store = PlayerStore(h.engine, h.session, FakeHost(), FakePreferences(PlayerChoices()), config, backgroundScope, testScheduler.timeSource)
        val prompts = mutableListOf<PlayerPrompt>()
        backgroundScope.launch { store.state.collect { state -> state.prompt?.let { prompts += it } } }
        h.start(firstEpisode("e1-unsized"))
        h.engine.report { it.copy(loaded = true, durationSeconds = 120.0, positionSeconds = 120.0, buffering = true) }
        h.settle()
        h.engine.report { it.copy(eof = true, paused = true, buffering = false) }
        h.settle()
        h.advance(30.seconds)
        assertEquals(emptyList(), prompts)
        assertEquals(1, h.engine.loads.size, "the next episode never loads")
        assertTrue(h.session.info.value.failure is PlaybackFailure.ErrorVideo, "told: ${h.session.info.value.failure}")
    }

    /** The episode itself when its length comes after its first frame: recorded once it comes, from the resume point. */
    @Test
    fun aVideoWhoseLengthComesAfterItsFirstFrameIsRecordedOnceItComes() = runTest {
        val h = SessionHarness(this)
        h.links()
        val before = h.halfWatched()
        val observer = h.observer()
        h.start(firstEpisode("e1-late"))
        h.firstFrameWithoutLength()
        h.playTo(721.0)
        h.assertNothingRecorded(before, observer)

        h.engine.report { it.copy(durationSeconds = 1440.0, seekable = true) }
        h.settle()
        assertNull(h.session.info.value.failure)
        assertTrue("e1-late" in h.lastStream())
        assertEquals("started 721000/1440000", observer.events.first())
        h.playTo(740.0)
        h.engine.setPaused(true)
        h.settle()
        val state = assertNotNull(h.item(SERIES.id)).state
        assertEquals(740_000, state.timeOffset, "on from the resume point")
        assertEquals(1_440_000, state.duration)
        assertEquals(0, state.timesWatched)
    }

    /**
     * A file the engine never reports a length for (a live stream, a file mpv can't measure) is no error video: taken
     * as the video 10 s after its first frame, playing or paused.
     */
    @Test
    fun aFileThatNeverReportsALengthIsTakenAsTheVideoAfterTheWait() = runTest {
        val h = SessionHarness(this)
        h.links()
        val before = h.halfWatched()
        val observer = h.observer()
        h.start(firstEpisode("e1-unmeasured"))
        h.firstFrameWithoutLength()
        h.advance(9.seconds)
        h.assertNothingRecorded(before, observer)
        assertNull(h.session.openedFile.value)

        h.advance(1.seconds)
        assertNull(h.session.info.value.failure)
        assertTrue("e1-unmeasured" in h.lastStream())
        assertEquals("started 720000/0", observer.events.first())
        assertNotNull(h.session.openedFile.value)
    }

    /**
     * The video after an error video's message: until the engine reports the video's length, the session has none,
     * not the message's. Here the library's 5 min can't tell an error video, so the file is taken at its first frame.
     */
    @Test
    fun theVideoAfterAnErrorVideosMessageNeverTakesTheMessagesLength() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.data.library.upsertFromMeta(h.profileId, MOVIE.librarySnapshot())
        h.data.library.recordProgress(h.profileId, MOVIE.id, MOVIE.id, 120_000, 300_000)
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/failed"))))
        assertEquals(ErrorVideoSign.Url, h.session.info.value.errorVideo)
        h.session.watchErrorVideo()
        h.settle()
        h.engine.open(duration = 60.0)
        h.settle()
        h.playTo(60.0)
        h.engine.report { it.copy(eof = true) }
        h.settle()
        assertEquals(ErrorVideoSign.Url, h.session.info.value.errorVideo)

        // The addon now sends the film.
        h.routes["addon.example"] = { redirect("https://cdn.example/film.mkv") }
        h.session.retry()
        h.settle()
        assertEquals(120.0, h.engine.loads.last().startSeconds)
        h.firstFrameWithoutLength()
        assertNull(h.session.info.value.failure)
        assertEquals(0, assertNotNull(h.item(MOVIE.id)).state.timesWatched, "2:00 isn't 70 % of the 1 min message")
        h.engine.report { it.copy(durationSeconds = 300.0, seekable = true) }
        h.playTo(130.0)
        h.engine.setPaused(true)
        h.settle()
        val state = assertNotNull(h.item(MOVIE.id)).state
        assertEquals(130_000, state.timeOffset)
        assertEquals(300_000, state.duration)
        assertEquals(0, state.timesWatched)
    }

    private companion object {
        const val E1 = "tt1:1:1"
    }
}
