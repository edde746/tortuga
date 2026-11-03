package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.resolve.ResolveFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ControlsVisibilityTest {
    @Test
    fun controlsHideAfterTheDelayWhilePlaying() = playerTest {
        playing()
        advance(2999.milliseconds)
        assertTrue(state.controlsVisible)
        advance(1.milliseconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun tvAndTouchWaitLonger() = playerTest(InputProfile.TV) {
        playing()
        advance(4999.milliseconds)
        assertTrue(state.controlsVisible)
        advance(1.milliseconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun hoverShowsTheControlsAndRestartsTheTimer() = playerTest {
        playing()
        advance(3.seconds)
        assertFalse(state.controlsVisible)
        input(InputEvent.PointerMove(overControls = false))
        assertTrue(state.controlsVisible)
        advance(2.seconds)
        input(InputEvent.PointerMove(overControls = false))
        advance(2999.milliseconds)
        assertTrue(state.controlsVisible)
        advance(1.milliseconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun anyKeyWhileTheControlsAreUpRestartsTheTimer() = playerTest(InputProfile.TV) {
        playing()
        advance(4.seconds)
        // Focus navigation among the controls: the store leaves the key to the platform but counts it as activity.
        assertFalse(press(InputKey.DOWN))
        advance(4999.milliseconds)
        assertTrue(state.controlsVisible)
        advance(1.milliseconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun pausedKeepsTheControlsUpAndResumingRestartsTheTimer() = playerTest {
        playing()
        dispatch(PlayerCommand.TogglePause)
        advance(30.seconds)
        assertTrue(state.controlsVisible)
        assertEquals(PlaybackStatus.PAUSED, state.status)
        dispatch(PlayerCommand.TogglePause)
        advance(2999.milliseconds)
        assertTrue(state.controlsVisible)
        advance(1.milliseconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun anOpenMenuKeepsTheControlsUp() = playerTest {
        playing()
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.SUBTITLES))
        advance(30.seconds)
        assertTrue(state.controlsVisible)
        assertEquals(PlayerMenu.SUBTITLES, state.menu)
        dispatch(PlayerCommand.CloseMenu)
        advance(3.seconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun scrubbingKeepsTheControlsUp() = playerTest {
        playing()
        advance(3.seconds)
        dispatch(PlayerCommand.ScrubTo(0.5))
        advance(30.seconds)
        assertTrue(state.controlsVisible)
        dispatch(PlayerCommand.CommitScrub)
        advance(3.seconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun anErrorKeepsTheControlsUp() = playerTest {
        playing()
        advance(3.seconds)
        sessionInfo { it.copy(failure = PlaybackFailure.Resolve(ResolveFailure.Http(404))) }
        advance(30.seconds)
        assertTrue(state.controlsVisible)
    }

    @Test
    fun loadingKeepsTheControlsUp() = playerTest {
        advance(30.seconds)
        assertTrue(state.controlsVisible)
        assertEquals(PlaybackStatus.OPENING, state.status)
        playing()
        advance(3.seconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun theMouseOnTheControlsKeepsThemUpAndLeavingTheWindowHidesThem() = playerTest {
        playing()
        input(InputEvent.PointerMove(overControls = true))
        advance(30.seconds)
        assertTrue(state.controlsVisible)
        input(InputEvent.PointerExit)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun anAssistiveTechnologyKeepsTheControlsUpUntilItStops() = playerTest {
        playing()
        advance(3.seconds)
        assertFalse(state.controlsVisible)
        store.onAssistiveTechnologyChanged(true)
        assertTrue(state.controlsVisible, "VoiceOver starting brings the controls up")
        advance(30.seconds)
        assertTrue(state.controlsVisible, "past the auto-hide delay while it runs")
        dispatch(PlayerCommand.ToggleControls)
        assertTrue(state.controlsVisible, "a tap on the video doesn't take them away either")
        store.onAssistiveTechnologyChanged(false)
        advance(2999.milliseconds)
        assertTrue(state.controlsVisible, "stopping it restarts the timer")
        advance(1.milliseconds)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun backClosesTheInnermostLayerFirst() = playerTest {
        playing()
        dispatch(PlayerCommand.ToggleInfo)
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.AUDIO))
        dispatch(PlayerCommand.ScrubTo(0.5))

        dispatch(PlayerCommand.Back)
        assertEquals(null, timeline.scrub)
        assertEquals(PlayerMenu.AUDIO, state.menu)
        dispatch(PlayerCommand.Back)
        assertEquals(null, state.menu)
        assertTrue(state.infoVisible)
        dispatch(PlayerCommand.Back)
        assertFalse(state.infoVisible)
        assertTrue(state.controlsVisible)
        dispatch(PlayerCommand.Back)
        assertFalse(state.controlsVisible)
        assertEquals(0, host.closed)
        dispatch(PlayerCommand.Back)
        assertEquals(1, host.closed)
        assertTrue(engine.seeks.isEmpty())
    }

    @Test
    fun backWhilePausedLeavesThePlayer() = playerTest {
        playing()
        dispatch(PlayerCommand.Pause)
        dispatch(PlayerCommand.Back)
        assertEquals(1, host.closed)
    }

    @Test
    fun subtitlesMoveAboveTheControlsWhileTheyShow() = playerTest {
        playing()
        // Nothing moves before the view has measured the controls.
        assertTrue(engine.subtitleLifts.isEmpty())
        store.onControlsHeightChanged(0.2)
        assertEquals(listOf(0.2), engine.subtitleLifts)
        advance(3.seconds)
        assertFalse(state.controlsVisible)
        assertEquals(listOf(0.2, 0.0), engine.subtitleLifts)
        // Hidden controls keep their measured height: the next show lifts at once.
        input(InputEvent.PointerMove(overControls = false))
        assertEquals(listOf(0.2, 0.0, 0.2), engine.subtitleLifts)
        // Sub-pixel layout changes stay in the view.
        store.onControlsHeightChanged(0.2002)
        assertEquals(3, engine.subtitleLifts.size)
        // No controls drawn (picture-in-picture): subtitles back in place while the store still has the controls up.
        store.onControlsHeightChanged(0.0)
        assertTrue(state.controlsVisible)
        assertEquals(0.0, engine.subtitleLifts.last())
        // Never more than half the player.
        store.onControlsHeightChanged(0.8)
        assertEquals(0.5, engine.subtitleLifts.last())
    }
}
