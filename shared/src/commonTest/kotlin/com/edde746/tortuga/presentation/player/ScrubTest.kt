package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.playback.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScrubTest {
    @Test
    fun draggingMovesThePreviewWithoutSeeking() = playerTest {
        playing(position = 100.0, duration = 600.0)
        dispatch(PlayerCommand.ScrubTo(0.5))
        assertEquals(ScrubState(seconds = 300.0, progress = 0.5f, position = "5:00", offset = "+3:20", chapter = null), timeline.scrub)
        dispatch(PlayerCommand.ScrubTo(0.1))
        assertEquals(ScrubState(seconds = 60.0, progress = 0.1f, position = "1:00", offset = "−0:40", chapter = null), timeline.scrub)
        assertTrue(engine.seeks.isEmpty())
        assertEquals("1:40", timeline.position)
    }

    @Test
    fun releasingCommitsOneExactSeekAndTheBarHoldsTheTargetUntilTheEngineGetsThere() = playerTest {
        playing(position = 100.0, duration = 600.0)
        engine.echo = false
        dispatch(PlayerCommand.ScrubTo(0.25))
        dispatch(PlayerCommand.ScrubTo(0.75))
        dispatch(PlayerCommand.CommitScrub)
        assertEquals(listOf(450.0 to true), engine.seeks)
        assertNull(timeline.scrub)
        // The engine still reports the old position: the bar shows the target, not a jump back.
        position(100.3)
        assertEquals("7:30", timeline.position)
        position(450.2)
        assertEquals("7:30", timeline.position)
        position(451.0)
        assertEquals("7:31", timeline.position)
    }

    @Test
    fun aSeekTheEngineNeverConfirmsShowsTheEnginePositionAgainAfterATimeout() = playerTest {
        playing(position = 100.0, duration = 600.0)
        engine.echo = false
        dispatch(PlayerCommand.ScrubTo(0.5))
        dispatch(PlayerCommand.CommitScrub)
        advance(1499.milliseconds)
        assertEquals("5:00", timeline.position)
        advance(1.milliseconds)
        assertEquals("1:40", timeline.position)
    }

    @Test
    fun cancellingDropsThePreview() = playerTest {
        playing(position = 100.0, duration = 600.0)
        dispatch(PlayerCommand.ScrubTo(0.9))
        dispatch(PlayerCommand.CancelScrub)
        assertNull(timeline.scrub)
        assertTrue(engine.seeks.isEmpty())
    }

    @Test
    fun thePreviewNamesTheChapterUnderIt() = playerTest {
        playing(position = 10.0, duration = 600.0)
        engineState { it.copy(chapters = listOf(Chapter("Opening", 0.0), Chapter("Part A", 90.0), Chapter("Credits", 540.0))) }
        dispatch(PlayerCommand.ScrubTo(0.5))
        assertEquals("Part A", timeline.scrub?.chapter)
        assertEquals(listOf(0.15f, 0.9f), timeline.chapters)
    }

    @Test
    fun dpadPressesStepThePreviewAndCommitOnceAfterAPause() = playerTest(InputProfile.TV) {
        playing(position = 100.0, duration = 600.0)
        advance(5.seconds)
        assertFalse(state.controlsVisible)

        assertTrue(press(InputKey.RIGHT))
        assertEquals("1:50", timeline.scrub?.position)
        assertTrue(state.controlsVisible)
        advance(300.milliseconds)
        press(InputKey.RIGHT)
        advance(300.milliseconds)
        press(InputKey.RIGHT)
        assertEquals("2:10", timeline.scrub?.position)
        assertEquals("+0:30", timeline.scrub?.offset)

        advance(999.milliseconds)
        assertTrue(engine.seeks.isEmpty())
        advance(1.milliseconds)
        assertEquals(listOf(130.0 to true), engine.seeks)
        assertNull(timeline.scrub)
    }

    @Test
    fun holdingTheDpadAcceleratesAndReleasingStopsIt() = playerTest(InputProfile.TV) {
        playing(position = 0.0, duration = 7200.0)
        advance(5.seconds)
        input(InputEvent.KeyDown(InputKey.RIGHT))
        advance(400.milliseconds)
        // A press is one step; the hold only starts moving after the hold delay.
        assertEquals(10.0, preview())
        val marks = (1..4).map {
            advance(1.seconds)
            preview()
        }
        val perSecond = (listOf(10.0) + marks).zipWithNext { a, b -> b - a }
        assertTrue(perSecond.zipWithNext().all { (a, b) -> b > a }, "not accelerating: $perSecond")
        assertTrue(perSecond.last() > 2 * perSecond.first(), "not accelerating enough: $perSecond")

        // Platform auto-repeat adds nothing: the hold drives the preview.
        val beforeRepeat = preview()
        input(InputEvent.KeyDown(InputKey.RIGHT, repeat = true))
        assertEquals(beforeRepeat, preview())

        input(InputEvent.KeyUp(InputKey.RIGHT))
        val released = preview()
        advance(900.milliseconds)
        assertEquals(released, preview())
        assertTrue(engine.seeks.isEmpty())
        advance(100.milliseconds)
        assertEquals(1, engine.seeks.size)
        assertEquals(released, engine.seeks.single().first)
    }

    @Test
    fun aHeldDpadStopsAtTheEndWithoutPassingIt() = playerTest(InputProfile.TV) {
        // A ten-minute video: the hold may cross it in no less than ScrubConfig.fullScanSeconds.
        playing(position = 0.0, duration = 600.0)
        advance(5.seconds)
        input(InputEvent.KeyDown(InputKey.RIGHT))
        advance(5400.milliseconds)
        assertTrue(preview() < 600.0, "crossed the video in 5 s: ${preview()}")
        advance(60.seconds)
        assertEquals(600.0, preview())
        assertEquals("10:00", timeline.scrub?.position)
        input(InputEvent.KeyUp(InputKey.RIGHT))
        advance(1.seconds)
        assertEquals(listOf(600.0 to true), engine.seeks)
    }

    @Test
    fun selectCommitsAtOnceAndBackCancels() = playerTest(InputProfile.TV) {
        playing(position = 100.0, duration = 600.0)
        advance(5.seconds)
        press(InputKey.LEFT)
        press(InputKey.SELECT)
        assertEquals(listOf(90.0 to true), engine.seeks)
        assertNull(timeline.scrub)

        advance(5.seconds)
        press(InputKey.LEFT)
        assertNotNull(timeline.scrub)
        press(InputKey.BACK)
        assertNull(timeline.scrub)
        advance(5.seconds)
        assertEquals(1, engine.seeks.size)
        assertEquals(0, host.closed)
    }

    @Test
    fun withTheControlsUpOnlyAFocusedSeekBarScrubs() = playerTest(InputProfile.TV) {
        playing(position = 100.0, duration = 600.0)
        assertTrue(state.controlsVisible)
        assertFalse(press(InputKey.RIGHT))
        assertNull(timeline.scrub)
        store.onTimelineFocusChanged(true)
        assertTrue(press(InputKey.RIGHT))
        assertEquals("1:50", timeline.scrub?.position)
    }

    /**
     * The position, the cache and a drag's preview move the timeline alone: the rest of the player's state stays the
     * same value, so the views that don't draw the timeline aren't drawn again as the video plays.
     */
    @Test
    fun playbackAndADragMoveTheTimelineAndLeaveTheRestOfTheStateAsItIs() = playerTest {
        playing(position = 100.0, duration = 600.0)
        val playing = state
        position(100.25)
        engineState { it.copy(cacheEndSeconds = 131.0) }
        position(101.0)
        assertSame(playing, state)
        assertEquals("1:41", timeline.position)
        assertEquals(131f / 600f, timeline.buffered, 0.0001f)

        dispatch(PlayerCommand.ScrubTo(0.5))
        val scrubbing = state
        assertTrue(scrubbing.scrubbing)
        dispatch(PlayerCommand.ScrubTo(0.6))
        position(101.5)
        assertSame(scrubbing, state)
        assertEquals("6:00", timeline.scrub?.position)
    }

    private fun PlayerHarness.preview(): Double = assertNotNull(timeline.scrub).seconds
}
