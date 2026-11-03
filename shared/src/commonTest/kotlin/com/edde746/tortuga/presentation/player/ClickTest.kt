package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.input.VideoClickAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClickTest {
    @Test
    fun aClickTogglesPauseOnceTheDoubleClickIntervalHasPassed() = playerTest {
        playing()
        input(InputEvent.Click(x = 0.5f))
        advance(499.milliseconds)
        assertTrue(engine.paused.isEmpty())
        advance(1.milliseconds)
        assertEquals(listOf(true), engine.paused)
        assertTrue(state.paused)
    }

    @Test
    fun aDoubleClickTogglesFullscreenWithoutPausing() = playerTest {
        playing()
        input(InputEvent.Click(x = 0.5f, clickCount = 1))
        advance(200.milliseconds)
        input(InputEvent.Click(x = 0.5f, clickCount = 2))
        assertEquals(listOf(true), host.fullscreenRequests)
        assertTrue(state.fullscreen)
        advance(2.seconds)
        assertTrue(engine.paused.isEmpty())

        // And back, still without pausing; a triple click's third click does nothing more.
        input(InputEvent.Click(x = 0.5f, clickCount = 1))
        input(InputEvent.Click(x = 0.5f, clickCount = 2))
        input(InputEvent.Click(x = 0.5f, clickCount = 3))
        advance(2.seconds)
        assertEquals(listOf(true, false), host.fullscreenRequests)
        assertTrue(engine.paused.isEmpty())
    }

    @Test
    fun platformsThatDontCountClicksGetDoubleClicksToo() = playerTest {
        playing()
        input(InputEvent.Click(x = 0.5f))
        advance(300.milliseconds)
        input(InputEvent.Click(x = 0.5f))
        advance(2.seconds)
        assertEquals(listOf(true), host.fullscreenRequests)
        assertTrue(engine.paused.isEmpty())
    }

    @Test
    fun twoSlowClicksAreTwoClicks() = playerTest {
        playing()
        input(InputEvent.Click(x = 0.5f))
        advance(600.milliseconds)
        input(InputEvent.Click(x = 0.5f))
        advance(600.milliseconds)
        assertEquals(listOf(true, false), engine.paused)
        assertTrue(host.fullscreenRequests.isEmpty())
    }

    @Test
    fun escapeLeavesFullscreenBeforeTheFullscreenToggleIsTouchedAgain() = playerTest {
        playing()
        press(InputKey.F)
        assertTrue(state.fullscreen)
        press(InputKey.ESCAPE)
        assertFalse(state.fullscreen)
        assertEquals(listOf(true, false), host.fullscreenRequests)
        // The platform reports fullscreen changes it made itself.
        store.onFullscreenChanged(true)
        assertTrue(state.fullscreen)
        press(InputKey.F)
        assertEquals(listOf(true, false, false), host.fullscreenRequests)
    }

    @Test
    fun aClickWithAMenuOpenClosesIt() = playerTest {
        playing()
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.SPEED))
        input(InputEvent.Click(x = 0.5f))
        advance(500.milliseconds)
        assertNull(state.menu)
        assertTrue(engine.paused.isEmpty())
    }

    @Test
    fun withTheControlsChosenAClickHidesAndShowsTheControlsAndNeverPauses() = playerTest {
        playing()
        // The profile's setting, changed while the video plays.
        sessionInfo { it.copy(videoClick = VideoClickAction.CONTROLS) }
        assertTrue(state.controlsVisible)
        input(InputEvent.Click(x = 0.5f))
        advance(499.milliseconds)
        assertTrue(state.controlsVisible, "the click waits for a second one")
        advance(1.milliseconds)
        assertFalse(state.controlsVisible, "hidden at once, not after the auto-hide")

        input(InputEvent.Click(x = 0.5f))
        advance(500.milliseconds)
        assertTrue(state.controlsVisible)
        advance(2999.milliseconds)
        assertTrue(state.controlsVisible)
        advance(1.milliseconds)
        assertFalse(state.controlsVisible, "shown with the usual auto-hide")
        assertTrue(engine.paused.isEmpty())
        assertFalse(state.paused)
    }

    @Test
    fun withTheControlsChosenADoubleClickOnlyTogglesFullscreen() = playerTest {
        playing()
        sessionInfo { it.copy(videoClick = VideoClickAction.CONTROLS) }
        input(InputEvent.Click(x = 0.5f, clickCount = 1))
        advance(200.milliseconds)
        input(InputEvent.Click(x = 0.5f, clickCount = 2))
        advance(1.seconds)
        assertEquals(listOf(true), host.fullscreenRequests)
        assertTrue(state.controlsVisible, "the double click's first click didn't hide the controls")
        assertTrue(engine.paused.isEmpty())
    }

    @Test
    fun withTheControlsChosenTheKeysAndThePlayButtonStillPauseAndAClickNeverPlays() = playerTest {
        playing()
        sessionInfo { it.copy(videoClick = VideoClickAction.CONTROLS) }
        press(InputKey.SPACE)
        assertEquals(listOf(true), engine.paused)
        // Paused, the controls stay up, as they do whatever input there is; the click doesn't play.
        input(InputEvent.Click(x = 0.5f))
        advance(500.milliseconds)
        assertTrue(state.paused)
        assertTrue(state.controlsVisible)
        // The controls' play button.
        dispatch(PlayerCommand.TogglePause)
        assertEquals(listOf(true, false), engine.paused)

        // A click with a menu open still closes it.
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.SPEED))
        input(InputEvent.Click(x = 0.5f))
        advance(500.milliseconds)
        assertNull(state.menu)
        assertTrue(state.controlsVisible)
        assertEquals(listOf(true, false), engine.paused)
    }

    @Test
    fun aHeldToggleActsOnce() = playerTest {
        playing(position = 100.0)
        input(InputEvent.KeyDown(InputKey.SPACE))
        repeat(3) { input(InputEvent.KeyDown(InputKey.SPACE, repeat = true)) }
        assertTrue(input(InputEvent.KeyUp(InputKey.SPACE)))
        assertEquals(listOf(true), engine.paused)
        // Keys the store doesn't use stay with the platform.
        assertFalse(input(InputEvent.KeyDown(InputKey.Q)))
        assertFalse(input(InputEvent.KeyUp(InputKey.Q)))
    }

    /**
     * A held seek key seeks as it goes down; its repeats only move the target the timeline shows, and one exact seek
     * goes there as it comes up: an exact seek per repeat kept a slow decoder from showing any picture until then.
     */
    @Test
    fun aHeldSeekKeySeeksOnceMoreWhereItIsLetGo() = playerTest {
        playing(position = 100.0)
        input(InputEvent.KeyDown(InputKey.RIGHT))
        assertEquals(listOf(110.0 to true), engine.seeks, "the first press seeks at once")
        for (shown in listOf("2:00", "2:10", "2:20")) {
            advance(50.milliseconds)
            input(InputEvent.KeyDown(InputKey.RIGHT, repeat = true))
            assertEquals(shown, timeline.position, "the timeline follows the repeats")
        }
        assertEquals(listOf(110.0 to true), engine.seeks, "repeats move the target the timeline shows")
        assertTrue(input(InputEvent.KeyUp(InputKey.RIGHT)))
        assertEquals(listOf(110.0 to true, 140.0 to true), engine.seeks)
        assertEquals(listOf(110.0, 140.0), session.seeks, "the session hears of both seeks")
        advance(2.seconds)
        assertEquals(2, engine.seeks.size)
        assertEquals("2:20", timeline.position)
    }

    /** A held seek whose release never reaches the store (it went to a menu) lands a moment after its last repeat. */
    @Test
    fun aHeldSeekWithoutItsReleaseLandsAfterItsLastRepeat() = playerTest {
        playing(position = 100.0)
        input(InputEvent.KeyDown(InputKey.LEFT))
        repeat(2) { input(InputEvent.KeyDown(InputKey.LEFT, repeat = true)) }
        advance(299.milliseconds)
        assertEquals(listOf(90.0), engine.seeks.map { it.first })
        assertEquals("1:10", timeline.position)
        advance(1.milliseconds)
        assertEquals(listOf(90.0, 70.0), engine.seeks.map { it.first })
    }

    @Test
    fun quickSeeksBuildOnEachOtherBeforeTheEngineReports() = playerTest {
        playing(position = 100.0)
        engine.echo = false
        repeat(3) { press(InputKey.LEFT) }
        assertEquals(listOf(90.0, 80.0, 70.0), engine.seeks.map { it.first })
        assertEquals("1:10", timeline.position)
    }

    /**
     * Keyframes 10.427 s apart (250 frames at 23.976 fps, x264's and x265's default): a keyframe seek 10 s on from just
     * past one lands back on it, and a seek bar's few seconds forward too.
     */
    @Test
    fun aSeekLandsWhereItAsksWhereverTheKeyframesAre() = playerTest {
        engine.keyframeSeconds = 10.427
        playing(position = 10.5)
        press(InputKey.RIGHT)
        advance(2.seconds)
        assertEquals("0:20", timeline.position, "Right: 10 s on")
        dispatch(PlayerCommand.SeekTo(25.0))
        advance(2.seconds)
        assertEquals("0:25", timeline.position, "the system's seek bar")
    }

    @Test
    fun onTouchADoubleTapSeeksBySideAndATapTogglesTheControls() = playerTest(
        profile = InputProfile.TOUCH,
        config = PlayerConfig.defaults(InputProfile.TOUCH, doubleClickIntervalMillis = 300),
    ) {
        playing(position = 100.0)
        input(InputEvent.Click(x = 0.1f))
        input(InputEvent.Click(x = 0.1f))
        input(InputEvent.Click(x = 0.9f))
        input(InputEvent.Click(x = 0.9f, clickCount = 2))
        input(InputEvent.Click(x = 0.5f))
        input(InputEvent.Click(x = 0.5f))
        assertEquals(listOf(90.0, 100.0), engine.seeks.map { it.first })
        assertEquals(listOf(true), engine.paused)

        dispatch(PlayerCommand.Play)
        advance(5.seconds)
        assertFalse(state.controlsVisible)
        input(InputEvent.Click(x = 0.5f))
        advance(299.milliseconds)
        assertFalse(state.controlsVisible)
        advance(1.milliseconds)
        assertTrue(state.controlsVisible)
        input(InputEvent.Click(x = 0.5f))
        advance(300.milliseconds)
        assertFalse(state.controlsVisible)
    }
}
