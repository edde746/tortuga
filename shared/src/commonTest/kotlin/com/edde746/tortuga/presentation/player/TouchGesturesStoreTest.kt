package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.SystemEdges
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Swipes and holds on a touch screen, through the store: the levels a swipe changes and shows, and the speed a held
 * finger plays at and gives back, alone, live and in a watch-together room.
 */
class TouchGesturesStoreTest {
    /** The device's levels, in memory. */
    private class FakeLevels(var brightness: Double = 0.5, var volume: Double = 0.5) : DeviceLevels {
        override fun adjustBrightness(delta: Double): Double {
            brightness = (brightness + delta).coerceIn(0.0, 1.0)
            return brightness
        }

        override fun adjustVolume(delta: Double): Double {
            volume = (volume + delta).coerceIn(0.0, 1.0)
            return volume
        }

        override fun restoreBrightness() = Unit
    }

    private fun gestures(body: PlayerHarness.(FakeLevels) -> Unit) = playerTest(InputProfile.TOUCH) {
        val levels = FakeLevels()
        host.levels = levels
        playing()
        body(levels)
    }

    @Test
    fun aSwipeChangesTheLevelOfItsHalfAndShowsItInTheMessagesPlace() = gestures { levels ->
        dispatch(PlayerCommand.SetSpeed(1.5))
        assertEquals(T.player.osd.speed(PlayerText.speed(1.5)), state.osd)

        input(InputEvent.Swipe(0.2f, 0.5f, 0.1))
        input(InputEvent.Swipe(0.2f, 0.5f, 0.1))
        assertEquals(0.7, levels.brightness, 1e-9)
        assertEquals(LevelIndicator(LevelKind.BRIGHTNESS, 0.7f, PlayerText.volume(70), T.player.osd.brightness(PlayerText.volume(70))), state.level)
        assertNull(state.osd, "the level shows in the message's place")

        input(InputEvent.Swipe(0.8f, 0.5f, -0.3))
        assertEquals(0.2, levels.volume, 1e-9)
        assertEquals(LevelIndicator(LevelKind.VOLUME, 0.2f, PlayerText.volume(20), T.player.osd.volume(PlayerText.volume(20))), state.level)
        assertEquals(0.7, levels.brightness, 1e-9, "the other half's level stays")

        advance(2.seconds)
        assertNull(state.level, "the level goes as a message does")
    }

    @Test
    fun aSwipeFromASystemEdgeOrWithSwipesOffChangesNothing() = gestures { levels ->
        val edges = SystemEdges(left = 0.05f, top = 0.08f, right = 0.05f, bottom = 0.1f)
        assertFalse(input(InputEvent.Swipe(0.02f, 0.5f, 0.2, edges)), "the back gesture's edge")
        assertFalse(input(InputEvent.Swipe(0.8f, 0.02f, 0.2, edges)), "the shade's edge")
        assertFalse(input(InputEvent.Swipe(0.5f, 0.97f, 0.2, edges)), "the home gesture's edge")

        choices { it.copy(swipeGestures = false) }
        assertFalse(input(InputEvent.Swipe(0.2f, 0.5f, 0.2)))
        assertFalse(input(InputEvent.Swipe(0.8f, 0.5f, 0.2)))

        assertEquals(0.5, levels.brightness)
        assertEquals(0.5, levels.volume)
        assertNull(state.level)
    }

    @Test
    fun aHoldPlaysAtTwiceTheSpeedAndTheLiftGivesBackTheSpeedItHad() = gestures {
        dispatch(PlayerCommand.SetSpeed(1.5))
        advance(2.seconds)

        input(InputEvent.Hold(true))
        assertEquals(2.0, engine.speeds.last())
        assertEquals(PlayerText.speed(2.0), state.heldSpeed)
        assertNull(state.osd, "the held speed shows in its own place, not as a message")

        input(InputEvent.Hold(false))
        assertEquals(listOf(1.5, 2.0, 1.5), engine.speeds)
        assertNull(state.heldSpeed)
        assertEquals(PlayerText.speed(1.5), state.speedLabel)
    }

    @Test
    fun noHoldLive() = gestures {
        sessionInfo { it.copy(live = true) }
        input(InputEvent.Hold(true))
        input(InputEvent.Hold(false))
        assertEquals(emptyList(), engine.speeds)
        assertNull(state.heldSpeed)
    }

    /**
     * Holding in a room this device controls changes the room's speed, so everyone plays at 2× together, and the lift
     * gives everyone back the room's speed: not the player's own while the room nudges it to catch up.
     */
    @Test
    fun inARoomThisDeviceControlsTheHoldIsTheRoomsSpeedForEveryone() = gestures {
        val watch = FakeWatch(canControl = true, canChangeEpisode = false)
        store.attachWatch(watch)
        watch.routesControls = true
        watch.roomRate = 1.25
        start { store.watchPlayer.setRate(1.3) }

        input(InputEvent.Hold(true))
        assertEquals(listOf(2.0), watch.rates)
        input(InputEvent.Hold(false))
        assertEquals(listOf(2.0, 1.25), watch.rates)
        assertEquals(listOf(1.3, 2.0, 1.25), engine.speeds)
    }

    @Test
    fun aGuestWhoDoesntControlTheRoomHoldsNothingAndIsToldWhy() = gestures {
        val watch = FakeWatch(canControl = false, canChangeEpisode = false)
        store.attachWatch(watch)
        watch.routesControls = true

        input(InputEvent.Hold(true))
        assertEquals("Only the host controls playback", state.osd)
        input(InputEvent.Hold(false))
        assertEquals(emptyList(), engine.speeds)
        assertEquals(emptyList(), watch.rates)
        assertNull(state.heldSpeed)
    }

    /** The host takes control away during a hold: the speed is the room's now, and the lift changes nothing. */
    @Test
    fun aHoldWhenTheRoomTakesControlAwayEndsWhereItIs() = gestures {
        val watch = FakeWatch(canControl = true, canChangeEpisode = false)
        store.attachWatch(watch)
        watch.routesControls = true

        input(InputEvent.Hold(true))
        watch.state.value = watch.state.value?.copy(canControl = false)
        advance(0.seconds)
        input(InputEvent.Hold(false))
        assertEquals(listOf(2.0), watch.rates)
        assertEquals(listOf(2.0), engine.speeds)
        assertNull(state.heldSpeed)
    }
}
