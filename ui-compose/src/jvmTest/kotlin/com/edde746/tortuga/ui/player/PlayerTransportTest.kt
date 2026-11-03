package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.EpisodeButtons
import com.edde746.tortuga.presentation.player.TrackItem
import com.edde746.tortuga.strings.T
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The player's transport with the previous and next episode buttons around it: their order and TV focus, each off at
 * its end of the series, none for a movie, and the row on a narrow phone. Beside it, the timeline and the volume, which
 * screen readers find by their names.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerTransportTest {
    private val transport = listOf("player.previous", "player.rewind", "player.playPause", "player.fastForward", "player.next")
    private val menus = listOf("player.audio", "player.subtitles", "player.speed", "player.options")

    /**
     * The transport reads previous episode, rewind, play/pause, forward, next episode, left to right. On TV focus comes
     * to it on play/pause, in its middle, and the D-pad walks it in that order.
     */
    @Test
    fun onTvFocusStartsOnPlayPauseAndTheDpadWalksTheTransportInOrder() = runComposeUiTest {
        val probe = PlayerProbe()
        tvPlayer(probe) { testPlayerState() }
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        val lefts = transport.map { probe.bounds.getValue(it).left }
        assertEquals(lefts.sorted(), lefts, "the transport's buttons from left to right")

        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        val walked = mutableListOf<String?>(runOnIdle { probe.focused })
        repeat(transport.size - 1) {
            press(Key.DirectionRight)
            walked += runOnIdle { probe.focused }
        }
        assertEquals<List<String?>>(transport, walked, "focus from the previous episode rightwards")
    }

    /**
     * On the first episode Previous is there but off, drawn faint, and the D-pad passes it by; on the last Next is.
     * The one that is on plays its episode.
     */
    @Test
    fun previousIsOffOnTheFirstEpisodeAndNextOnTheLast() = runComposeUiTest {
        val commands = mutableListOf<PlayerCommand>()
        val probe = PlayerProbe()
        var state by mutableStateOf(testPlayerState(episodes = EpisodeButtons(previous = false, next = true)))
        player(FormFactor.TV, probe = probe, onCommand = { commands += it }) { state }
        onNodeWithContentDescription(T.player.previousEpisode).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.nextEpisode).assertIsEnabled()
        val faint = brightest(T.player.previousEpisode)
        val bright = brightest(T.player.nextEpisode)
        assertTrue(faint < bright * 0.6f, "the off button's icon at luminance $faint, the one on at $bright")
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        press(Key.DirectionLeft)
        runOnIdle { assertEquals("player.rewind", probe.focused) }
        press(Key.DirectionLeft)
        runOnIdle { assertNotEquals("player.previous", probe.focused, "focus on the off button") }
        onNodeWithContentDescription(T.player.nextEpisode).performClick()

        state = state.copy(episodes = EpisodeButtons(previous = true, next = false))
        onNodeWithContentDescription(T.player.nextEpisode).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.previousEpisode).assertIsEnabled().performClick()
        runOnIdle {
            val episodes = commands.filter { it == PlayerCommand.NextEpisode || it == PlayerCommand.PreviousEpisode }
            assertEquals(listOf<PlayerCommand>(PlayerCommand.NextEpisode, PlayerCommand.PreviousEpisode), episodes)
        }
    }

    /** A movie has neither episode button: rewind, play/pause and forward, with TV focus on play/pause. */
    @Test
    fun aMovieHasNeitherEpisodeButton() = runComposeUiTest {
        val probe = PlayerProbe()
        tvPlayer(probe) { testPlayerState(episodes = null) }
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        onNodeWithContentDescription(T.player.previousEpisode).assertDoesNotExist()
        onNodeWithContentDescription(T.player.nextEpisode).assertDoesNotExist()
        val movie = listOf("player.rewind", "player.playPause", "player.fastForward")
        val lefts = movie.map { probe.bounds.getValue(it).left }
        assertEquals(lefts.sorted(), lefts, "the transport's buttons from left to right")
    }

    /**
     * A 360 dp phone in portrait has no room for the transport and all five menus on one line: the transport keeps its
     * line, in order, and the menus take the next one, at its end; nothing overlaps or leaves the window.
     */
    @Test
    fun onANarrowPhoneTheMenusTakeALineOfTheirOwn() = runComposeUiTest {
        val size = DpSize(360.dp, 740.dp)
        val probe = phone(size)
        val row = transport.map { probe.bounds.getValue(it) }
        val menuRow = menus.map { probe.bounds.getValue(it) }
        val width = with(density) { size.width.toPx() }
        assertEquals(row.map { it.left }.sorted(), row.map { it.left }, "the transport's buttons from left to right")
        assertOneLine(row, "the transport")
        assertOneLine(menuRow, "the menus")
        assertTrue(menuRow.minOf { it.top } >= row.maxOf { it.bottom }, "the menus below the transport: $menuRow, $row")
        assertTrue(width - menuRow.last().right <= with(density) { 16.dp.toPx() }, "the menus at the line's end: $menuRow")
        assertApart(row + menuRow, width)
    }

    /** A phone in landscape has the transport and the five menus on one line, apart. */
    @Test
    fun onAPhoneInLandscapeTheButtonsAreOneLine() = runComposeUiTest {
        val size = DpSize(800.dp, 360.dp)
        val probe = phone(size)
        val buttons = (transport + menus).map { probe.bounds.getValue(it) }
        assertOneLine(buttons, "the buttons")
        assertApart(buttons, with(density) { size.width.toPx() })
    }

    /** Screen readers find the timeline and the desktop's volume by their names, and hear the position and the level. */
    @Test
    fun screenReadersFindTheTimelineAndTheVolumeByTheirNames() = runComposeUiTest {
        player(FormFactor.DESKTOP) { testPlayerState() }
        onNodeWithContentDescription(T.player.timeline).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:15 / 0:20"))
        onNodeWithContentDescription(T.player.volume.title).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
    }

    /** The player on a phone of [size] with audio and subtitle tracks, so all five menus show. */
    private fun ComposeUiTest.phone(size: DpSize): PlayerProbe {
        val probe = PlayerProbe()
        val audio = listOf(TrackItem(1, "English", null, selected = true), TrackItem(2, "Japanese", null, selected = false))
        val subtitles = listOf(TrackItem(null, "Off", null, selected = false), TrackItem(3, "English", null, selected = true))
        val state = testPlayerState().copy(audioTracks = audio, subtitleTracks = subtitles)
        player(FormFactor.HANDHELD, probe = probe, size = size) { state }
        waitForIdle()
        return probe
    }

    private fun assertOneLine(buttons: List<Rect>, what: String) {
        assertTrue(buttons.all { (it.center.y - buttons[0].center.y).roundToInt() == 0 }, "$what on one line: $buttons")
    }

    /** No two of [buttons] overlap, and all lie within the window's [width]. */
    private fun assertApart(buttons: List<Rect>, width: Float) {
        for ((i, a) in buttons.withIndex()) {
            assertTrue(a.left >= 0f && a.right <= width, "$a outside the window's width $width")
            for (b in buttons.drop(i + 1)) assertTrue(!a.overlaps(b), "$a over $b")
        }
    }

    /** The brightest luminance drawn within the button labelled [label] (its icon, over the controls' scrim). */
    private fun ComposeUiTest.brightest(label: String): Float {
        val bounds = onNodeWithContentDescription(label).getBoundsInRoot()
        val pixels = onRoot().captureToImage().toPixelMap()
        val xs = with(density) { bounds.left.toPx().roundToInt().coerceAtLeast(0) until bounds.right.toPx().roundToInt().coerceAtMost(pixels.width) }
        val ys = with(density) { bounds.top.toPx().roundToInt().coerceAtLeast(0) until bounds.bottom.toPx().roundToInt().coerceAtMost(pixels.height) }
        var max = 0f
        for (x in xs) for (y in ys) max = maxOf(max, pixels[x, y].luminance())
        return max
    }
}
