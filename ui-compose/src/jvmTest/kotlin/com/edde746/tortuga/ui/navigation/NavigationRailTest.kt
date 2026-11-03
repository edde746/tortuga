package com.edde746.tortuga.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.focus.LocalScreenFocus
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.ScreenFocus
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NavigationRailTest {
    private val destinations = TopLevelDestination.entries

    /**
     * A phone in landscape (a Pixel 7: 914 × 360 dp between its status bar and its gesture bar): every destination
     * shows whole, icon and label, one under another and above the avatar, each still a full touch target.
     */
    @Test
    fun onAPhoneInLandscapeEveryDestinationFitsAboveTheAvatar() = runComposeUiTest {
        rail(FormFactor.HANDHELD, 914, 360)
        val avatar = shown(hasContentDescription(AVATAR))
        assertEquals(48f, avatar.height.value, 0.5f, "the avatar is squeezed to ${avatar.height}")
        val items = destinations.map { destination ->
            onNode(hasText(destination.shortLabel)).assertIsDisplayed()
            val item = shown(hasText(destination.shortLabel))
            val whole = onNode(hasText(destination.shortLabel)).getUnclippedBoundsInRoot()
            assertTrue(abs((item.height - whole.height).value) < 0.5f, "${destination.shortLabel} is cut to ${item.height} of ${whole.height}")
            assertTrue(item.height >= 48.dp, "${destination.shortLabel} is ${item.height} tall, under a touch target")
            assertTrue(item.top >= 0.dp && item.bottom <= avatar.top, "${destination.shortLabel} at ${item.top}–${item.bottom} isn't above the avatar at ${avatar.top}")
            item
        }
        for ((a, b) in items.zipWithNext()) assertTrue(a.bottom <= b.top, "destinations overlap: $a and $b")
    }

    /**
     * A window shorter still: the destinations scroll in the space above the avatar, each one scrolled into view
     * whole, none ever drawn over the avatar, which stays put at the bottom.
     */
    @Test
    fun aShorterRailScrollsItsDestinationsAboveTheAvatar() = runComposeUiTest {
        rail(FormFactor.HANDHELD, 914, 240)
        val avatar = shown(hasContentDescription(AVATAR))
        assertTrue(avatar.bottom <= 240.dp && avatar.bottom >= 230.dp, "the avatar isn't at the rail's bottom: $avatar")
        for (destination in destinations) {
            onNode(hasText(destination.shortLabel)).performScrollTo().assertIsDisplayed()
            val item = shown(hasText(destination.shortLabel))
            val whole = onNode(hasText(destination.shortLabel)).getUnclippedBoundsInRoot()
            assertTrue(abs((item.height - whole.height).value) < 0.5f, "${destination.shortLabel} scrolled to is cut to ${item.height} of ${whole.height}")
            for (other in destinations) {
                val shownPart = shown(hasText(other.shortLabel))
                assertTrue(shownPart.height == 0.dp || shownPart.bottom <= avatar.top, "${other.shortLabel} at $shownPart is drawn over the avatar at $avatar")
            }
            assertEquals(avatar, shown(hasContentDescription(AVATAR)), "the avatar moved as the destinations scrolled")
        }
    }

    /** A tall window (a desktop's; inside the test's 1024 × 768 window): the roomy rail, its group in the middle. */
    @Test
    fun aTallRailCentresItsDestinations() = runComposeUiTest { assertRoomyAndCentred(FormFactor.DESKTOP, 1000, 700) }

    /**
     * A rail just tall enough for the roomy layout (a Fold's inner screen with the keyboard up: 465 dp) keeps it, as on
     * a tablet: the items 4 dp apart, the group in the middle of the rail's height.
     */
    @Test
    fun aRailJustTallEnoughStaysRoomy() = runComposeUiTest { assertRoomyAndCentred(FormFactor.HANDHELD, 673, 460) }

    /**
     * On a 1080p TV (960 × 540 dp) the first Left from the screen into the column lands on the selected destination,
     * from an element above the destinations, level with the first, the third or the last of them, or below them: never
     * on the one level with the element. After Down within the column and Right back to the screen, Left lands on the
     * destination focused there last. For two selected destinations, neither level with an element.
     */
    @Test
    fun onTvLeftLandsOnTheSelectedDestinationThenOnTheOneFocusedLastWhateverTheElementsHeight() {
        // The destinations are 48 dp tall and 8 dp apart, centred in the column: the first from 134 dp down.
        val elements = listOf(
            "above the destinations" to 40,
            "level with the first" to 134,
            "level with the third" to 246,
            "level with the last" to 358,
            "below the destinations" to 460,
        )
        val wrong = mutableListOf<String>()
        for (selected in listOf(TopLevelDestination.DISCOVER, TopLevelDestination.LIBRARY)) {
            for ((element, _) in elements) runComposeUiTest {
                val probe = FocusedProbe()
                tvColumn(probe, selected, elements)
                onNodeWithText(element).requestFocus()
                waitForIdle()
                val case = "${selected.probeName} selected, from the element $element"
                press(Key.DirectionLeft)
                val first = probe.focused
                if (first != "nav.${selected.probeName}") wrong += "$case: Left went to $first, not nav.${selected.probeName}"
                press(Key.DirectionDown)
                val last = probe.focused
                press(Key.DirectionRight)
                val screen = probe.focused
                if (last?.startsWith("nav.") != true || screen == null || screen.startsWith("nav.")) {
                    wrong += "$case: Down from $first went to $last, then Right to $screen: not within the column, then back to the screen"
                } else {
                    press(Key.DirectionLeft)
                    if (probe.focused != last) wrong += "$case: Left again from $screen went to ${probe.focused}, not $last, focused there last"
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On a TV, a screen whose content arrives late (Home after Back on a TV box): focus walked from the screen into the
     * column and down it stays there as the content arrives, never taken back to the screen's first card; and Left from
     * the chip at the start of the screen's first row, pressed as focus comes back to it while the column still narrows,
     * lands in the column.
     */
    @Test
    fun onTvFocusWalkedIntoTheColumnStaysAsTheContentArrivesAndLeftFromTheChipReachesTheNarrowingColumn() = runComposeUiTest {
        val probe = FocusedProbe()
        var ready by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            NavigationScaffold(destinations = destinations, selected = TopLevelDestination.BOARD, onSelect = {}, showNavigation = true) {
                                val screen = remember { ScreenFocus { true } }
                                Box(Modifier.fillMaxSize().then(screen.modifier)) {
                                    CompositionLocalProvider(LocalScreenFocus provides screen) {
                                        val memory = rememberFocusMemory()
                                        Column(Modifier.padding(start = 48.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                            // Narrower than the open column reaches into the screen.
                                            TortugaButton(CHIP, onClick = {}, modifier = Modifier.width(120.dp).layoutProbe("screen.chip"))
                                            if (ready) TortugaButton(CARD, onClick = {}, modifier = Modifier.width(240.dp).rememberFocus(memory, CARD).layoutProbe("screen.card"))
                                        }
                                        RestoreFocus(memory, ready, fallback = CARD.takeIf { ready })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        onNodeWithText(CHIP).requestFocus()
        waitForIdle()
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        val walked = probe.focused
        assertTrue(walked?.startsWith("nav.") == true, "Left, then Down from the chip went to $walked, not down the column")
        ready = true
        waitForIdle()
        assertEquals(walked, probe.focused, "the content arriving took focus from $walked")
        mainClock.autoAdvance = false
        onNodeWithText(CHIP).requestFocus()
        mainClock.advanceTimeByFrame()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        mainClock.advanceTimeByFrame()
        assertTrue(probe.focused?.startsWith("nav.") == true, "Left from the chip while the column narrows went to ${probe.focused}")
    }

    private fun ComposeUiTest.assertRoomyAndCentred(formFactor: FormFactor, width: Int, height: Int) {
        rail(formFactor, width, height)
        val avatar = shown(hasContentDescription(AVATAR))
        val items = destinations.map { shown(hasText(it.shortLabel)) }
        val above = items.first().top
        val below = height.dp - items.last().bottom
        assertTrue(abs((above - below).value) <= 1f, "the group isn't centred: $above above it, $below below it")
        for ((a, b) in items.zipWithNext()) assertEquals(4f, (b.top - a.bottom).value, 0.5f, "destinations $a and $b aren't 4 dp apart")
        assertTrue(items.last().bottom <= avatar.top, "the group reaches the avatar: ${items.last()} and $avatar")
    }

    private fun ComposeUiTest.shown(matcher: SemanticsMatcher): DpRect = onNode(matcher).getBoundsInRoot()

    private fun ComposeUiTest.rail(formFactor: FormFactor, width: Int, height: Int) = setContent {
        TortugaTheme {
            Box(Modifier.size(width.dp, height.dp)) {
                AdaptiveLayout(formFactor) {
                    NavigationScaffold(
                        destinations = destinations,
                        selected = TopLevelDestination.BOARD,
                        onSelect = {},
                        showNavigation = true,
                        footer = { Box(Modifier.size(48.dp).semantics { contentDescription = AVATAR }) },
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    /**
     * The TV frame of a 1080p TV (960 × 540 dp), the arrow keys as the D-pad, [selected] chosen, around a screen of a
     * button for each of [elements] (its label, its top in dp), 48 dp in from the screen's place.
     */
    private fun ComposeUiTest.tvColumn(probe: FocusedProbe, selected: TopLevelDestination, elements: List<Pair<String, Int>>) = setContent {
        CompositionLocalProvider(LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        NavigationScaffold(destinations = destinations, selected = selected, onSelect = {}, showNavigation = true) {
                            Box(Modifier.fillMaxSize()) {
                                for ((label, top) in elements) {
                                    TortugaButton(label, onClick = {}, modifier = Modifier.padding(start = 48.dp, top = top.dp).width(480.dp).layoutProbe("screen.$label"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private companion object {
        const val AVATAR = "avatar"
        const val CHIP = "Watch together"
        const val CARD = "card"
    }
}
