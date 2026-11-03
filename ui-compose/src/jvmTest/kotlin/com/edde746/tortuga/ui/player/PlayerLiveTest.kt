package com.edde746.tortuga.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.PlaybackMode
import com.edde746.tortuga.presentation.player.TimelineState
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A live playback's controls: no timeline row and no rewind or forward, the live label right after play/pause and level
 * with it, before the menus. On TV focus passes it by; a tap or a click on it does nothing. With a rewind window a
 * screen reader moves the timeline.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerLiveTest {
    private val label = T.player.live.position("0:07")
    private val live = testPlayerState(episodes = null).copy(mode = PlaybackMode.LIVE, seekable = false)
    private val liveLabel = T.detail.infoJoin(T.player.live.badge, "0:07")
    private val liveTimeline = MutableStateFlow(
        TimelineState("", "", "", label, 0f, 0f, emptyList(), positionSeconds = 120.0, durationSeconds = 0.0, liveLabel = liveLabel),
    )
    private val gone = listOf("player.position", "player.scrub", "player.remaining", "player.rewind", "player.fastForward")
    private val menus = listOf("player.speed", "player.options")

    /** On TV focus starts on play/pause; Right goes on to the first menu and Left comes back, past the label both ways. */
    @Test
    fun onTvFocusPassesTheLabelBy() = runComposeUiTest {
        val probe = PlayerProbe()
        tvPlayer(probe, liveTimeline) { live }
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        assertLayout(probe)
        press(Key.DirectionRight)
        runOnIdle { assertEquals(menus.first(), probe.focused) }
        press(Key.DirectionLeft)
        runOnIdle { assertEquals("player.playPause", probe.focused) }
    }

    /** On a phone a tap on the label sends nothing and doesn't reach the video beneath. */
    @Test
    fun onAPhoneATapOnTheLabelDoesNothing() = runComposeUiTest {
        tapDoesNothing(FormFactor.HANDHELD) { performTouchInput { click() } }
    }

    /** On the desktop a click on the label sends nothing and doesn't reach the video beneath. */
    @Test
    fun onTheDesktopAClickOnTheLabelDoesNothing() = runComposeUiTest {
        tapDoesNothing(FormFactor.DESKTOP) { performMouseInput { click() } }
    }

    /**
     * With a rewind window a screen reader moves the timeline as a finger does: the player seeks there (live has no
     * rewind or forward buttons).
     */
    @Test
    fun aScreenReaderMovesARewindWindowsTimeline() = runComposeUiTest {
        val window = testPlayerState(episodes = null).copy(mode = PlaybackMode.LIVE_WINDOW, seekable = true)
        val timeline = MutableStateFlow(
            TimelineState("−1:28", "", "", label, 1f, 1f, emptyList(), positionSeconds = 88.0, durationSeconds = 88.0, liveLabel = liveLabel, atLiveEdge = true),
        )
        val commands = mutableListOf<PlayerCommand>()
        player(FormFactor.HANDHELD, onCommand = { commands += it }, timeline = timeline) { window }
        onNodeWithContentDescription(T.player.timeline).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.SeekToFraction(0.5)), commands) }
    }

    private fun ComposeUiTest.tapDoesNothing(formFactor: FormFactor, tap: SemanticsNodeInteraction.() -> Unit) {
        val probe = PlayerProbe()
        val commands = mutableListOf<PlayerCommand>()
        val clicks = mutableListOf<InputEvent.Click>()
        player(formFactor, probe = probe, onCommand = { commands += it }, onInput = { if (it is InputEvent.Click) clicks += it; false }, timeline = liveTimeline) { live }
        waitForIdle()
        assertLayout(probe)
        onNodeWithContentDescription(label).tap()
        runOnIdle {
            assertEquals(emptyList(), commands, "commands from a tap on the label")
            assertEquals(emptyList(), clicks, "a tap on the label reaching the video")
        }
    }

    /** No timeline row and no skips; the label after play/pause, their centres level, and before the menus. */
    private fun assertLayout(probe: PlayerProbe) {
        assertEquals(emptyList(), gone.filter { it in probe.bounds }, "shown while live")
        val play = probe.bounds.getValue("player.playPause")
        val badge = probe.bounds.getValue("player.live")
        assertTrue(badge.left > play.right, "the label $badge after play/pause $play")
        assertTrue(abs(badge.center.y - play.center.y) <= 1f, "the label $badge level with play/pause $play")
        assertTrue(badge.right < menus.minOf { probe.bounds.getValue(it).left }, "the label $badge before the menus")
    }
}
