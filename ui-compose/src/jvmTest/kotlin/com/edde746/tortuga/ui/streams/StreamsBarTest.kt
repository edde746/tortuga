package com.edde746.tortuga.ui.streams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.streams.GroupStatus
import com.edde746.tortuga.presentation.streams.StreamFilterBar
import com.edde746.tortuga.presentation.streams.StreamGroup
import com.edde746.tortuga.presentation.streams.StreamRow
import com.edde746.tortuga.presentation.streams.StreamsAddon
import com.edde746.tortuga.presentation.streams.StreamsBody
import com.edde746.tortuga.presentation.streams.StreamsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.getDrawnBoundsInRoot
import com.edde746.tortuga.ui.focus.FocusVisibility
import com.edde746.tortuga.ui.focus.LocalFocusVisibility
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.trackFocusVisibility
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Refresh at the end of the title's line and the row of the addons' chips over the stream list, by keyboard, remote and touch. */
@OptIn(ExperimentalTestApi::class)
class StreamsBarTest {
    private val test = StreamGroup("group-0", "Tortuga Test", GroupStatus.Ready, listOf(row("t1", "1080p"), row("t2", "720p")), null, null)
    private val second = StreamGroup("group-1", "Tortuga Second", GroupStatus.Ready, listOf(row("s1", "2160p"), row("s2", "480p")), null, null)
    private val broken = StreamGroup("group-2", "Tortuga Broken", GroupStatus.Failed("The addon answered HTTP 500"), emptyList(), null, null)
    private val ids = mapOf("test" to test, "second" to second, "broken" to broken)

    private var state by mutableStateOf(listed(null))
    private var refreshes = 0

    /**
     * The arrows walk the row and its ends hold; Enter on a chip lists its addon alone with focus kept on the chip; Down
     * goes to the list's first stream and Up back to the selected chip. Up from the row reaches Refresh at the end of
     * the header's line, where Left, Right and Up hold; Refresh keeps focus while every addon reloads, its rows going and
     * coming back, and Down from it goes back into the row on the selected chip.
     */
    @Test
    fun aKeyboardPicksAnAddonAndFocusStaysInTheRow() = runComposeUiTest {
        screen()
        assertFocused("1080p")
        press(Key.DirectionUp)
        assertFocused(T.streams.all)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertFocused("Tortuga Second")
        press(Key.Enter)
        assertFocused("Tortuga Second")
        onNodeWithText("2160p").assertExists()
        onNodeWithText("1080p").assertDoesNotExist()

        press(Key.DirectionDown)
        assertFocused("2160p")
        press(Key.DirectionUp)
        assertFocused("Tortuga Second")

        repeat(2) { press(Key.DirectionLeft) }
        assertFocused(T.streams.all)
        press(Key.DirectionLeft)
        assertFocused(T.streams.all)
        press(Key.DirectionUp)
        assertRefreshFocused()
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)) {
            press(key)
            assertRefreshFocused()
        }
        press(Key.Enter)
        runOnIdle { assertEquals(1, refreshes) }
        // Every addon asked again: the groups load without rows, then answer.
        state = state.copy(body = StreamsBody.Groups(state.groups.map { it.copy(status = GroupStatus.Loading, streams = emptyList()) }))
        waitForIdle()
        state = listed("second")
        waitForIdle()
        assertRefreshFocused()
        press(Key.DirectionDown)
        assertFocused("Tortuga Second")

        press(Key.DirectionRight)
        assertFocused("Tortuga Broken")
        press(Key.DirectionRight)
        assertFocused("Tortuga Broken")
        press(Key.Enter)
        assertFocused("Tortuga Broken")
    }

    /**
     * On TV Refresh is in the side panel's top trailing corner, level with the title: Up from the row reaches it, and
     * Left, Right and Up hold there; Select asks every addon again, focus staying on it; Down goes back into the row on
     * the selected chip (All, then Tortuga Second once it is picked).
     */
    @Test
    fun onTvTheRemoteReachesRefreshInThePanelsCorner() = runComposeUiTest {
        sheet(FormFactor.TV)
        assertFocused("1080p")
        press(Key.DirectionUp)
        assertFocused(T.streams.all)
        press(Key.DirectionUp)
        assertRefreshFocused()
        assertRefreshInTheCorner("1080p")
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)) {
            press(key)
            assertRefreshFocused()
        }
        press(Key.Enter)
        runOnIdle { assertEquals(1, refreshes) }
        assertRefreshFocused()
        press(Key.DirectionDown)
        assertFocused(T.streams.all)

        repeat(2) { press(Key.DirectionRight) }
        press(Key.Enter)
        assertFocused("Tortuga Second")
        press(Key.DirectionUp)
        assertRefreshFocused()
        press(Key.DirectionDown)
        assertFocused("Tortuga Second")
    }

    /**
     * With one addon there are no chips and no row: Refresh stays at the end of the title's line, level with the title,
     * and a tap on it still asks every addon again.
     */
    @Test
    fun withOneAddonThereIsNoRowAndRefreshStillWorks() = runComposeUiTest {
        state = oneAddon()
        sheet(FormFactor.HANDHELD)
        onNodeWithText(T.streams.all).assertDoesNotExist()
        assertRefreshInTheCorner("2160p")
        onNode(hasClickAction() and hasContentDescription(T.streams.refresh)).performClick()
        runOnIdle { assertEquals(1, refreshes) }
    }

    /** On TV with one addon (no row) Up from the list's first stream reaches Refresh in the corner, and Down goes back. */
    @Test
    fun onTvWithOneAddonUpFromTheListReachesRefresh() = runComposeUiTest {
        state = oneAddon()
        sheet(FormFactor.TV)
        assertFocused("2160p")
        onNodeWithText(T.streams.all).assertDoesNotExist()
        assertRefreshInTheCorner("2160p")
        press(Key.DirectionUp)
        assertRefreshFocused()
        press(Key.Enter)
        runOnIdle { assertEquals(1, refreshes) }
        press(Key.DirectionDown)
        assertFocused("2160p")
    }

    /**
     * Refresh at the end of the title's line: its right edge, as drawn, where the list's rows end ([row], a stream's
     * name), its middle within the title's height.
     */
    private fun ComposeUiTest.assertRefreshInTheCorner(row: String) {
        val refresh = onNode(hasClickAction() and hasContentDescription(T.streams.refresh)).getDrawnBoundsInRoot()
        val title = onNodeWithText("The Movie").getUnclippedBoundsInRoot()
        val rows = onNode(hasClickAction() and hasText(row)).getUnclippedBoundsInRoot()
        val middle = (refresh.top + refresh.bottom) / 2
        assertTrue(middle in title.top..title.bottom, "Refresh $refresh isn't level with the title $title")
        assertTrue(abs((refresh.right - rows.right).value) <= 1f, "Refresh $refresh doesn't end where the rows $rows do")
    }

    private fun ComposeUiTest.assertRefreshFocused() {
        onNode(isFocused()).assert(hasContentDescription(T.streams.refresh))
    }

    /**
     * Focus coming up from the list onto the selected chip shows there, as focus moved along the row does: the ring;
     * also once a click picked the chip (a mouse press hides focus until the next key).
     */
    @Test
    fun focusComingUpFromTheListShowsOnTheChip() = runSkikoComposeUiTest(size = Size(1920f, 1440f), density = Density(2f)) {
        screen()
        onNode(hasClickAction() and hasText("Tortuga Second")).performMouseInput { click() }
        press(Key.DirectionDown)
        assertFocused("2160p")
        press(Key.DirectionUp)
        assertFocused("Tortuga Second")
        assertRing("Up from the list")
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        assertFocused("Tortuga Second")
        assertRing("Left and Right")
    }

    /** The focused element's ring, 2 dp wide 2 dp inside its edge: black at the middle of its top, 3 dp in. */
    private fun ComposeUiTest.assertRing(how: String) {
        val image = onNode(isFocused()).captureToImage().toPixelMap()
        val pixel = image[image.width / 2, 6]
        assertTrue(pixel.luminance() < 0.2f, "$how: no ring on the focused chip ($pixel)")
    }

    /**
     * A pick moves no chip of the row, so the chip under the pointer or the finger stays under it: each chip is as wide
     * selected (the check and the semibold label) as not, on desktop, a phone and TV, at a density whose dp aren't whole
     * pixels (a Pixel 7's). Picks go along the row both ways among the chips in view (a pick past the sheet's edge
     * scrolls the row, as it should): a chip after the selected one, one before it, and All; on desktop, where the
     * whole row is in view, Tortuga Broken after Tortuga Second too (a desktop test run caught that one moving 20 px).
     */
    @Test
    fun aPickMovesNoChip() {
        val chips = listOf(T.streams.all, "Tortuga Test", "Tortuga Second", "Tortuga Broken")
        for (formFactor in listOf(FormFactor.DESKTOP, FormFactor.HANDHELD, FormFactor.TV)) {
            runSkikoComposeUiTest(size = Size(2600f, 2200f), density = Density(2.625f)) {
                state = listed(null)
                if (formFactor == FormFactor.DESKTOP) screen() else sheet(formFactor)
                val before = chips.associateWith { onNode(hasClickAction() and hasText(it)).getUnclippedBoundsInRoot() }
                val picks = if (formFactor == FormFactor.DESKTOP) listOf("Tortuga Second", "Tortuga Broken", "Tortuga Test", T.streams.all) else listOf("Tortuga Second", "Tortuga Test", T.streams.all)
                for (pick in picks) {
                    onNode(hasClickAction() and hasText(pick)).performClick()
                    waitForIdle()
                    onNode(hasClickAction() and hasText(pick)).assertIsSelected()
                    for (chip in chips) {
                        assertEquals(before[chip], onNode(hasClickAction() and hasText(chip)).getUnclippedBoundsInRoot(), "$formFactor: $chip after picking $pick")
                    }
                }
            }
        }
    }

    /** With an addon's chip selected its streams have no heading with its name, but a failed one keeps its error and Retry. */
    @Test
    fun aSelectedAddonsGroupHasNoHeadingButKeepsItsRetry() = runComposeUiTest {
        screen()
        // All: the name on the chip and over the addon's group.
        onAllNodesWithText("Tortuga Broken").assertCountEquals(2)
        state = listed("broken")
        waitForIdle()
        onAllNodesWithText("Tortuga Broken").assertCountEquals(1)
        onNodeWithText("The addon answered HTTP 500").assertExists()
        onNodeWithText(T.nav.retry).assertExists()
    }

    /**
     * The addons answer in any order: focus the list put on its first stream moves to the new first stream when an
     * addon above answers, so Up from it reaches the row.
     */
    @Test
    fun focusTheListPlacedFollowsTheNewFirstStream() = runComposeUiTest {
        state = listed(null).copy(body = StreamsBody.Groups(listOf(test.copy(status = GroupStatus.Loading, streams = emptyList()), second, broken)))
        screen()
        assertFocused("2160p")
        state = listed(null)
        waitForIdle()
        assertFocused("1080p")
        press(Key.DirectionUp)
        assertFocused(T.streams.all)
    }

    /**
     * On TV the panel opens while the addon is asked (focus on Refresh, its one control); once the addon answers with
     * every stream hidden by the filters, focus goes to the filters' Show, the list's one control. Select lists the
     * hidden streams, and focus the list placed moves on to the first one.
     */
    @Test
    fun onTvWhenTheFiltersHideEveryStreamShowTakesFirstFocus() = runComposeUiTest {
        var toggles = 0
        state = oneAddon().copy(body = StreamsBody.Loading)
        sheet(FormFactor.TV, onToggleFiltered = {
            toggles++
            state = oneAddon().copy(filter = StreamFilterBar("Showing 2 streams your filters hide", "Hide", showing = true))
        })
        assertRefreshFocused()
        val hidden = StreamFilterBar("2 streams hidden by your filters", "Show", showing = false)
        state = oneAddon().copy(body = StreamsBody.Groups(listOf(second.copy(streams = emptyList()))), filter = hidden)
        waitForIdle()
        assertFocused("Show")
        press(Key.Enter)
        runOnIdle { assertEquals(1, toggles) }
        waitForIdle()
        assertFocused("2160p")
    }

    private fun listed(selected: String?) = StreamsUiState(
        title = "The Movie",
        subtitle = null,
        body = StreamsBody.Groups(selected?.let { listOf(ids.getValue(it)) } ?: listOf(test, second, broken)),
        notice = null,
        filter = null,
        addons = listOf(
            StreamsAddon("test", "Tortuga Test", selected == "test"),
            StreamsAddon("second", "Tortuga Second", selected == "second"),
            StreamsAddon("broken", "Tortuga Broken", selected == "broken"),
        ),
    )

    /** One addon with streams, so no chips: Tortuga Second's group alone. */
    private fun oneAddon() = listed(null).copy(body = StreamsBody.Groups(listOf(second)), addons = emptyList())

    /**
     * The Streams sheet on [formFactor] (TV: the side panel; else the bottom sheet), over a phone's or a TV's window;
     * [onToggleFiltered] for the filters' Show / Hide.
     */
    private fun ComposeUiTest.sheet(formFactor: FormFactor, onToggleFiltered: () -> Unit = {}) = setContent {
        val intents = remember { StreamsIntents({}, {}, { _, _ -> }, {}, {}, onToggleFiltered, { state = listed(it) }, { refreshes++ }, {}) }
        TortugaTheme {
            Box(if (formFactor == FormFactor.TV) Modifier.size(960.dp, 540.dp) else Modifier.size(411.dp, 800.dp)) {
                AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                    OverlayHostBox { TortugaSheet(onDismiss = {}) { StreamsSheetContent(state, intents) } }
                }
            }
        }
    }

    private fun ComposeUiTest.screen() = setContent {
        // As the desktop app keeps it: hidden at first, shown from a key press, hidden again by a mouse press.
        val visibility = remember { FocusVisibility(initial = false) }
        TortugaTheme {
            CompositionLocalProvider(LocalFocusVisibility provides visibility) {
                Box(Modifier.size(960.dp, 720.dp)) {
                    AdaptiveLayout(FormFactor.DESKTOP, Modifier.trackFocusVisibility(visibility).arrowKeyFocus()) {
                        StreamsScreen(
                            state = state,
                            onPick = {},
                            onDownload = {},
                            onPlayWith = { _, _ -> },
                            onDismissDownloadMessage = {},
                            onReload = {},
                            onToggleFiltered = {},
                            onSelectAddon = { state = listed(it) },
                            onRefresh = { refreshes++ },
                            onToggleSort = {},
                            onBack = null,
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.assertFocused(text: String) {
        onNode(isFocused()).assert(hasText(text))
    }

    private fun ComposeUiTest.press(key: Key) = onRoot().performKeyInput { pressKey(key) }

    private fun row(id: String, name: String) =
        StreamRow(id, name, null, emptyList(), external = false, lastUsed = false, filtered = false, noteKind = null)
}
