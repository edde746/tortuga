package com.edde746.tortuga.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.model.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One finger on the video on a phone, the controls down: a tap stays a tap, a finger held still is a hold and no tap,
 * a drag up or down is a swipe from where it went down, and a drag sideways is nothing.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerTouchTest {
    private fun touches(takeHolds: Boolean = true, gesture: TouchInjectionScope.() -> Unit): List<InputEvent> {
        val events = mutableListOf<InputEvent>()
        runComposeUiTest {
            player(FormFactor.HANDHELD, onInput = { events += it; it !is InputEvent.Hold || takeHolds }) {
                testPlayerState().copy(controlsVisible = false)
            }
            onRoot().performTouchInput(gesture)
            waitForIdle()
        }
        return events
    }

    @Test
    fun aTapIsAClick() {
        val events = touches { click(Offset(width * 0.8f, height * 0.5f)) }
        assertEquals(listOf<InputEvent>(InputEvent.Click(0.8f)), events)
    }

    @Test
    fun aFingerHeldStillIsAHoldUntilItLiftsAndNoTap() {
        val events = touches {
            down(Offset(width * 0.5f, height * 0.5f))
            advanceEventTime(1500)
            up()
        }
        assertEquals(listOf<InputEvent>(InputEvent.Hold(true), InputEvent.Hold(false)), events)
    }

    @Test
    fun aHoldTheStoreLeavesAloneIsTheTapItWas() {
        val events = touches(takeHolds = false) {
            down(Offset(width * 0.5f, height * 0.5f))
            advanceEventTime(1500)
            up()
        }
        assertEquals(listOf(InputEvent.Hold(true), InputEvent.Click(0.5f)), events)
    }

    @Test
    fun aDragUpIsASwipeFromWhereItWentDown() {
        val events = touches { swipeUp(startY = height * 0.8f, endY = height * 0.3f, durationMillis = 300) }
        val swipes = events.filterIsInstance<InputEvent.Swipe>()
        assertTrue(swipes.isNotEmpty() && swipes.size == events.size, "only swipes: $events")
        assertTrue(swipes.all { it.x == 0.5f && it.y == 0.8f }, "from where it went down: $swipes")
        assertEquals(0.5, swipes.sumOf { it.distance }, 0.01, "up by half the player's height")
    }

    @Test
    fun aDragSidewaysIsNothing() {
        val events = touches { swipeLeft(startX = width * 0.8f, endX = width * 0.2f, durationMillis = 300) }
        assertEquals(emptyList(), events)
    }
}
