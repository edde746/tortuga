package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.streams.StreamPickRepository
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What came of a stream picked by hand ([PickWatch]): played past two minutes or to its end is good; a failure or a
 * long stall is bad; left within 30 s is bad once another stream is picked for the same video. Going on to the player's
 * next stream is a pick of its own. Picks are recorded only while the profile's suggestions are on.
 */
class StreamPickOutcomeTest {
    private val shown = listOf(
        PickedStream(resolution = ResolutionBadge.UHD_2160, videoCodec = VideoCodecBadge.HEVC, sizeBytes = 40L shl 30),
        PickedStream(resolution = ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.HEVC, sizeBytes = 3L shl 30),
        PickedStream(resolution = ResolutionBadge.HD_720, videoCodec = VideoCodecBadge.AVC, sizeBytes = 1L shl 30),
    )

    private fun SessionHarness.links() {
        serveCdn(lcgBytes(300_000))
        routes["addon.example"] = { request -> redirect("https://cdn.example/${request.url.segments.last()}.mkv") }
    }

    /** Turns the profile's suggestions [on] or off: picks are recorded only while on. */
    private suspend fun SessionHarness.suggest(on: Boolean) {
        data.settings.set(profileId, ProfileSetting.StreamSuggestions, on)
        settle()
    }

    /** With suggestions on, the second of [shown] picked in the list, played: its pick's id. */
    private suspend fun SessionHarness.pickAndPlay(name: String = "movie"): String {
        suggest(true)
        val id = assertNotNull(picks.listPick(MOVIE, MOVIE.id, shown, chosen = 1))
        start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/$name")), listOf(choice(urlStream("https://addon.example/other"))), pickId = id))
        advance(1.seconds)
        engine.open(duration = 3000.0)
        settle()
        return id
    }

    private suspend fun SessionHarness.pick(id: String): StreamPick = picks.picks().single { it.id == id }

    @Test
    fun aPickRecordsWhatWasShownAroundItAndWhere() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        val pick = h.pick(id)
        assertEquals(shown, pick.candidates)
        assertEquals(1, pick.chosen)
        assertEquals(PickSource.LIST, pick.source)
        assertEquals(MOVIE.id to MOVIE.id, pick.metaId to pick.videoId)
        assertEquals(false, pick.context.metered)
        assertNull(pick.outcome, "nothing is known yet")
    }

    @Test
    fun playingTwoMinutesIsGood() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.playTo(119.0)
        assertNull(h.pick(id).outcome)
        h.playTo(121.0)
        assertEquals(PickOutcome.GOOD, h.pick(id).outcome)
        // Whatever comes later, it stays good.
        h.engine.fail("connection reset")
        h.settle()
        assertEquals(PickOutcome.GOOD, h.pick(id).outcome)
    }

    @Test
    fun aSeekIsNoPlayingTime() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.playTo(10.0)
        h.session.onSeek(2000.0)
        h.engine.seek(2000.0, exact = false)
        h.settle()
        h.playTo(2010.0)
        h.session.close()
        h.settle()
        assertEquals(PickOutcome.BACKED_OUT, h.pick(id).outcome, "20 s played, the jump not counted")
    }

    @Test
    fun backingOutThenPickingAnotherStreamIsBad() = runTest {
        val h = SessionHarness(this)
        h.links()
        val first = h.pickAndPlay()
        h.playTo(20.0)
        h.session.close()
        h.settle()
        assertEquals(PickOutcome.BACKED_OUT, h.pick(first).outcome, "left within 30 s: not bad until another is picked")

        h.picks.listPick(MOVIE, MOVIE.id, shown, chosen = 2)
        h.settle()
        assertEquals(PickOutcome.BAD, h.pick(first).outcome)
    }

    @Test
    fun pickingAnotherStreamOverThePlayerIsBadForTheOneLeft() = runTest {
        val h = SessionHarness(this)
        h.links()
        val first = h.pickAndPlay()
        h.playTo(10.0)
        // The list opens over the player: the new pick is recorded, then its playback replaces the one playing.
        val second = h.pickAndPlay("second")
        assertEquals(PickOutcome.BAD, h.pick(first).outcome)
        assertNull(h.pick(second).outcome)
    }

    @Test
    fun anotherStreamLongAfterLeavingSaysNothing() = runTest {
        val h = SessionHarness(this)
        h.links()
        val first = h.pickAndPlay()
        h.playTo(20.0)
        h.session.close()
        h.settle()
        h.wallClock.now += StreamPickRepository.REPICK_WINDOW_MILLIS + 1
        h.picks.listPick(MOVIE, MOVIE.id, shown, chosen = 2)
        h.settle()
        assertEquals(PickOutcome.BACKED_OUT, h.pick(first).outcome)
    }

    @Test
    fun leavingAfterHalfAMinuteSaysNothing() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.playTo(60.0)
        h.session.close()
        h.settle()
        assertNull(h.pick(id).outcome)
    }

    @Test
    fun aFailureIsBad() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.playTo(5.0)
        h.engine.fail("demuxer error")
        h.settle()
        assertEquals(PickOutcome.BAD, h.pick(id).outcome)
    }

    @Test
    fun aLongStallIsBadAShortOneIsNot() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.playTo(5.0)
        h.engine.report { it.copy(buffering = true) }
        h.advance(10.seconds)
        h.engine.report { it.copy(buffering = false) }
        h.settle()
        assertNull(h.pick(id).outcome, "10 s of buffering")

        h.engine.report { it.copy(buffering = true) }
        h.advance(PickWatch.STALL + 1.seconds)
        assertEquals(PickOutcome.BAD, h.pick(id).outcome)
    }

    @Test
    fun aStallWhilePausedIsNoStall() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.engine.setPaused(true)
        h.engine.report { it.copy(buffering = true) }
        h.advance(1.minutes)
        assertNull(h.pick(id).outcome)
    }

    @Test
    fun theEndIsGood() = runTest {
        val h = SessionHarness(this)
        h.links()
        val id = h.pickAndPlay()
        h.engine.report { it.copy(positionSeconds = 3000.0, eof = true) }
        h.settle()
        assertEquals(PickOutcome.GOOD, h.pick(id).outcome)
    }

    @Test
    fun thePlayersNextStreamIsAPickOverTheOneThatPlayed() = runTest {
        val h = SessionHarness(this)
        h.links()
        val first = h.pickAndPlay()
        h.playTo(8.0)
        h.session.playNextStream()
        h.settle()
        val next = h.picks.picks().single { it.id != first }
        assertEquals(PickSource.PLAYER, next.source)
        assertEquals(1, next.chosen)
        assertEquals(2, next.candidates.size, "the stream left and the one picked")
        assertEquals(PickOutcome.BAD, h.pick(first).outcome, "left within 30 s for another stream")
        h.advance(1.seconds)
        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(130.0)
        assertEquals(PickOutcome.GOOD, h.pick(next.id).outcome)
    }

    @Test
    fun aStreamNobodyPickedRecordsNothing() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie"))))
        h.advance(1.seconds)
        h.engine.open(duration = 3000.0)
        h.playTo(130.0)
        assertEquals(emptyList(), h.picks.picks())
    }

    /**
     * While the profile's suggestions are off neither the list's pick nor the player's next stream is recorded; what
     * comes of a pick recorded while they were on still is.
     */
    @Test
    fun whileSuggestionsAreOffNoPickIsRecordedButAnEarlierOnesOutcomeIs() = runTest {
        val h = SessionHarness(this)
        h.links()
        assertNull(h.picks.listPick(MOVIE, MOVIE.id, shown, chosen = 1), "off by default")
        val first = h.pickAndPlay()
        h.suggest(false)
        assertNull(h.picks.listPick(MOVIE, MOVIE.id, shown, chosen = 2))
        h.playTo(8.0)
        h.session.playNextStream()
        h.settle()
        assertEquals(listOf(first), h.picks.picks().map { it.id }, "neither the list's pick nor the player's next stream")
        assertEquals(PickOutcome.BACKED_OUT, h.pick(first).outcome, "left within 30 s: the outcome of the pick made while on")
    }
}
