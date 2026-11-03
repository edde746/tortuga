package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.session.NextEpisode
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The screen lock on a touch screen: what a locked screen ignores, what still works through it, the three taps that
 * unlock it, and how long it lasts.
 */
class ScreenLockTest {
    private class FakeLevels : DeviceLevels {
        var changes = 0

        override fun adjustBrightness(delta: Double): Double {
            changes++
            return 0.5
        }

        override fun adjustVolume(delta: Double): Double {
            changes++
            return 0.5
        }

        override fun restoreBrightness() = Unit
    }

    /** Locked from the options menu, as the viewer does. */
    private fun locked(body: PlayerHarness.(FakeLevels) -> Unit) = playerTest(InputProfile.TOUCH) {
        val levels = FakeLevels()
        host.levels = levels
        playing()
        assertEquals(T.player.lock.button, state.lockLabel, "a touch screen's options menu offers Lock screen")
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.OPTIONS))
        dispatch(PlayerCommand.LockScreen)
        body(levels)
    }

    @Test
    fun noTouchActsWhileLocked() = locked { levels ->
        assertFalse(state.controlsVisible, "locking hides the controls")
        assertNull(state.menu, "locking closes the options menu")
        assertNotNull(state.screenLock)
        val layouts = engine.layouts.toList()

        input(InputEvent.Click(0.5f))
        input(InputEvent.Click(0.5f, clickCount = 2))
        input(InputEvent.Click(0.1f))
        input(InputEvent.Click(0.1f, clickCount = 2))
        input(InputEvent.Click(0.9f))
        input(InputEvent.Click(0.9f, clickCount = 2))
        input(InputEvent.Pinch(1.5))
        input(InputEvent.Pinch(2.0))
        input(InputEvent.Pinch(2.5))
        input(InputEvent.Pinch(3.0))
        input(InputEvent.PinchEnd)
        input(InputEvent.Swipe(0.2f, 0.5f, 0.3))
        input(InputEvent.Swipe(0.8f, 0.5f, 0.3))
        input(InputEvent.Hold(true))
        input(InputEvent.PointerMove(overControls = true))
        advance(1.seconds)

        assertEquals(emptyList(), engine.paused, "no tap or double tap pauses")
        assertEquals(emptyList(), engine.seeks, "no double tap seeks")
        assertEquals(layouts, engine.layouts, "no pinch zooms")
        assertEquals(0, levels.changes, "no swipe changes the brightness or the volume")
        assertEquals(emptyList(), engine.speeds, "no hold plays at 2×")
        assertNull(state.heldSpeed)
        assertNull(state.level)
        assertNull(state.osd)
        assertFalse(state.controlsVisible, "no touch shows the controls")
        assertNull(state.menu)
    }

    @Test
    fun mediaKeysTheSystemsControlsAndPictureInPictureStillWork() = locked {
        dispatch(PlayerCommand.SystemPause)
        assertEquals(listOf(true), engine.paused, "the system's Now Playing pause")
        assertFalse(state.controlsVisible, "paused, a locked screen keeps the controls hidden")
        dispatch(PlayerCommand.SystemResume)
        assertEquals(listOf(true, false), engine.paused)

        input(InputEvent.KeyDown(InputKey.MEDIA_PLAY_PAUSE))
        input(InputEvent.KeyUp(InputKey.MEDIA_PLAY_PAUSE))
        assertEquals(listOf(true, false, true), engine.paused, "a headset's or keyboard's play/pause key")
        store.onVisibleChanged(false)
        store.onPictureInPictureChanged(true)
        dispatch(PlayerCommand.SystemResume)
        assertTrue(store.inPictureInPicture.value)
        assertTrue(state.keepAwake, "playing on in the picture-in-picture window")
        store.onPictureInPictureChanged(false)
        store.onVisibleChanged(true)
        assertNotNull(state.screenLock, "the lock is still on after picture in picture")
    }

    @Test
    fun aTapShowsTheUnlockButtonATapArmsItAndATapOnTheArmedButtonUnlocks() = locked {
        assertEquals(UnlockButton(armed = false, label = T.player.lock.tapToUnlock), state.screenLock?.unlock, "locking shows the way out")
        advance(3.seconds)
        assertEquals(ScreenLockState(unlock = null), state.screenLock, "the button hides after 3 s")

        dispatch(PlayerCommand.UnlockPress)
        assertEquals(ScreenLockState(unlock = null), state.screenLock, "a press on the hidden button counts for nothing")

        input(InputEvent.Click(0.5f))
        assertEquals(false, state.screenLock?.unlock?.armed, "a tap shows it")
        input(InputEvent.Click(0.5f))
        assertNull(state.screenLock?.unlock, "a tap off the button hides it again")

        input(InputEvent.Click(0.5f))
        input(InputEvent.Pinch(1.2))
        assertNull(state.screenLock?.unlock, "a second finger hides it")

        input(InputEvent.Click(0.5f))
        dispatch(PlayerCommand.UnlockPress)
        assertEquals(UnlockButton(armed = true, label = T.player.lock.tapAgainToUnlock), state.screenLock?.unlock)
        advance(3.seconds)
        assertNull(state.screenLock?.unlock, "an armed button waits 3 s for its second tap")
        assertNotNull(state.screenLock)

        input(InputEvent.Click(0.5f))
        dispatch(PlayerCommand.UnlockPress)
        dispatch(PlayerCommand.UnlockPress)
        assertNull(state.screenLock, "the second tap on the armed button unlocks")
        assertTrue(state.controlsVisible, "unlocking shows the controls")

        input(InputEvent.Click(0.5f))
        advance(600.milliseconds)
        assertFalse(state.controlsVisible, "unlocked, a tap hides the controls again")
        input(InputEvent.Click(0.5f))
        advance(600.milliseconds)
        assertTrue(state.controlsVisible, "and shows them")
    }

    @Test
    fun theLockLastsThroughTheNextEpisodeAndBackLeavesThePlayer() = locked {
        sessionInfo { it.copy(episode = true, nextEpisode = NextEpisode(title = "Chapter Two", subtitle = "S1E2")) }
        dispatch(PlayerCommand.NextEpisode)
        assertEquals(1, session.nextEpisodes)
        playing(position = 0.0)
        assertNotNull(state.screenLock, "the next episode plays locked")
        assertFalse(state.controlsVisible)

        dispatch(PlayerCommand.Back)
        assertEquals(1, host.closed, "Back leaves the player, the lock with it")
    }

    @Test
    fun onlyATouchScreenLocks() = playerTest(InputProfile.DESKTOP) {
        playing()
        assertNull(state.lockLabel)
        dispatch(PlayerCommand.LockScreen)
        assertNull(state.screenLock)
        assertTrue(state.controlsVisible)
    }
}
