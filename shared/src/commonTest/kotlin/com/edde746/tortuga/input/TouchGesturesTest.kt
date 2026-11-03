package com.edde746.tortuga.input

import com.edde746.tortuga.input.PlayerCommand.AdjustBrightness
import com.edde746.tortuga.input.PlayerCommand.AdjustSystemVolume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The touch keymap's swipes and holds: which command each gives, where and when. */
class TouchGesturesTest {
    private val touch = Keymap.forProfile(InputProfile.TOUCH)
    private val playing = InputContext()

    /** A phone's edges with gesture navigation: the back gesture at both sides, the shade at the top, home at the bottom. */
    private val edges = SystemEdges(left = 0.05f, top = 0.08f, right = 0.05f, bottom = 0.1f)

    private fun swipe(x: Float, y: Float, distance: Double = 0.1, context: InputContext = playing) =
        touch.swipe(InputEvent.Swipe(x, y, distance, edges), context)

    @Test
    fun aSwipeOnTheLeftHalfIsTheBrightnessAndOnTheRightHalfTheVolume() {
        assertEquals(AdjustBrightness(0.1), swipe(0.2f, 0.5f))
        assertEquals(AdjustBrightness(-0.25), swipe(0.49f, 0.3f, -0.25))
        assertEquals(AdjustSystemVolume(0.1), swipe(0.8f, 0.5f))
        assertEquals(AdjustSystemVolume(-0.05), swipe(0.5f, 0.7f, -0.05))
    }

    @Test
    fun noSwipeStartsInAnEdgeTheSystemSwipesFrom() {
        val outside = listOf(
            "the back gesture's left edge" to (0.02f to 0.5f),
            "the back gesture's right edge" to (0.98f to 0.5f),
            "the notification shade's and Control Center's top edge" to (0.3f to 0.03f),
            "the top edge on the right" to (0.9f to 0.05f),
            "the home gesture's bottom edge" to (0.7f to 0.95f),
        )
        for ((where, point) in outside) assertNull(swipe(point.first, point.second), where)
        // Just inside the edges, the swipe is the player's.
        assertEquals(AdjustBrightness(0.1), swipe(0.06f, 0.09f))
        assertEquals(AdjustSystemVolume(0.1), swipe(0.94f, 0.89f))
        // Where the system takes no edge (3-button navigation), the whole video swipes.
        assertEquals(AdjustBrightness(0.1), touch.swipe(InputEvent.Swipe(0.01f, 0.5f, 0.1), playing))
    }

    @Test
    fun noSwipeWithSwipesOffAMenuOpenOrAnError() {
        assertNull(swipe(0.2f, 0.5f, context = playing.copy(swipes = false)))
        assertNull(swipe(0.8f, 0.5f, context = playing.copy(swipes = false)))
        assertNull(swipe(0.8f, 0.5f, context = playing.copy(menuOpen = true)))
        assertNull(swipe(0.2f, 0.5f, context = playing.copy(errorShown = true)))
    }

    @Test
    fun aHoldPlaysAtTwiceTheSpeedUntilItLiftsButNotLive() {
        assertEquals(PlayerCommand.HoldSpeed, touch.hold(true, playing))
        assertEquals(PlayerCommand.ReleaseSpeed, touch.hold(false, playing))
        assertNull(touch.hold(true, playing.copy(live = true)))
        assertNull(touch.hold(true, playing.copy(menuOpen = true)))
        assertNull(touch.hold(true, playing.copy(errorShown = true)))
        // A lift always gives the speed back, whatever came meanwhile.
        assertEquals(PlayerCommand.ReleaseSpeed, touch.hold(false, playing.copy(live = true)))
    }
}
