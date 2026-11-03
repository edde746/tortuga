package com.edde746.tortuga.ui.focus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.ui.components.LocalOverlayOpeners
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.ShelfRow
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A screen ([ScreenFocus]) and a dialog never sit on view with nothing focused: the focused row leaving sends focus
 * back into the screen, a dialog that closes gives focus back to the row that opened it, and a screen coming on view
 * without focus in it takes it.
 */
@OptIn(ExperimentalTestApi::class)
class FocusHomeTest {
    private var rows by mutableStateOf(listOf("One", "Two", "Three"))
    private var dialog by mutableStateOf(false)
    private var onView by mutableStateOf(true)
    private var ready by mutableStateOf(true)
    private var sheet by mutableStateOf(false)
    private var sheetShown by mutableStateOf(true)

    /**
     * A screen opened under a sheet (Detail under the stream list Home's menu opened) and never focused: when the sheet
     * closes, focus goes from wherever it was (the navigation) to the screen's fallback.
     */
    @Test
    fun aScreenComingOnViewWithoutFocusTakesItAtItsFallback() = runComposeUiTest {
        onView = false
        screen()
        onNodeWithText("Navigation").requestFocus()
        waitForIdle()
        onView = true
        waitForIdle()
        onNodeWithText("One").assertIsFocused()
    }

    /**
     * Detail opened from a catalog card's menu (Choose a stream): focus drops on the navigation while Detail loads, the
     * Streams sheet opens over it from there, and Back closes the sheet, which holds focus while it slides away: focus
     * goes to the screen's fallback, not back to the navigation the app's focus restorer last saw focused.
     */
    @Test
    fun aSheetOpenedFromTheNavigationGivesFocusToTheScreenUnderIt() = runComposeUiTest {
        ready = false
        screen()
        onNodeWithText("Navigation").requestFocus()
        waitForIdle()
        onView = false
        sheet = true
        waitForIdle()
        onNodeWithText("Stream").assertIsFocused()
        ready = true
        waitForIdle()
        onView = true
        sheetShown = false
        waitForIdle()
        onNodeWithText("One").assertIsFocused()
    }

    /** A row removed while focused (an item deleted from its menu): focus goes to the screen's first row. */
    @Test
    fun theFocusedRowLeavingSendsFocusToTheScreensFallback() = runComposeUiTest {
        screen()
        onNodeWithText("Three").requestFocus()
        rows = listOf("One", "Two")
        waitForIdle()
        onNodeWithText("One").assertIsFocused()
    }

    /**
     * The focused row leaves with nothing to take its place for longer than the screen waits (its content reloading):
     * the screen holds focus meanwhile, and a D-pad key then takes it to what has come, where it would do nothing.
     */
    @Test
    fun aKeyTakesFocusToTheScreensHomeThatCameAfterItStoppedWaiting() = runComposeUiTest {
        screen()
        onNodeWithText("Three").requestFocus()
        rows = emptyList()
        waitForIdle()
        rows = listOf("Four")
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithText("Four").assertIsFocused()
    }

    /** A confirmation answered: focus goes back to the row that asked, not nowhere. */
    @Test
    fun aClosingDialogGivesFocusBackToTheRowThatOpenedIt() = runComposeUiTest {
        screen()
        onNodeWithText("Two").requestFocus()
        dialog = true
        waitForIdle()
        onNodeWithText("Yes").assertIsFocused()
        dialog = false
        waitForIdle()
        onNodeWithText("Two").assertIsFocused()
    }

    /**
     * A focused card that leaves its shelf hands focus to the card taking its place, however many frames that card
     * takes to compose its button: the screen around the shelf waits for the handover, and never takes focus to its
     * fallback meanwhile.
     */
    @Test
    fun aShelfHandingFocusToALateCardKeepsTheScreenFromTakingIt() = runComposeUiTest {
        var cards by mutableStateOf(listOf("A"))
        var fallbackFocused = 0
        setContent {
            TortugaTheme {
                OverlayHostBox {
                    val screen = remember { ScreenFocus { true } }
                    Box(screen.modifier) {
                        CompositionLocalProvider(LocalScreenFocus provides screen) {
                            val memory = rememberFocusMemory()
                            Column {
                                TortugaButton("Top", {}, Modifier.rememberFocus(memory, "top").onFocusChanged { if (it.hasFocus) fallbackFocused++ })
                                ShelfRow("Shelf", cards, key = { it }) { _, card ->
                                    // B's button comes a few frames after B takes A's place.
                                    var shown by remember { mutableStateOf(card == "A") }
                                    LaunchedEffect(Unit) {
                                        repeat(LATE_FRAMES) { withFrameNanos { } }
                                        shown = true
                                    }
                                    if (shown) TortugaButton(card, {})
                                }
                            }
                            RestoreFocus(memory, ready = true, fallback = "top")
                        }
                    }
                }
            }
        }
        onNodeWithText("A").requestFocus()
        waitForIdle()
        fallbackFocused = 0
        cards = listOf("B")
        waitForIdle()
        onNodeWithText("B").assertIsFocused()
        assertEquals(0, fallbackFocused, "the screen took focus to its fallback while the shelf handed it on")
    }

    /**
     * The navigation (no overlay gives focus back to it), then a screen of [rows] in a focus memory (the first its
     * fallback), and a dialog while [dialog].
     */
    private fun ComposeUiTest.screen() = setContent {
        TortugaTheme {
            OverlayHostBox {
                val screen = remember { ScreenFocus { onView } }
                Column {
                    CompositionLocalProvider(LocalOverlayOpeners provides false) { TortugaButton("Navigation", {}) }
                    Box(screen.modifier.focusGate { onView }) {
                        CompositionLocalProvider(LocalScreenFocus provides screen) {
                            val memory = rememberFocusMemory()
                            Column {
                                for (row in rows) key(row) { TortugaButton(row, {}, Modifier.rememberFocus(memory, row)) }
                            }
                            RestoreFocus(memory, ready = ready, fallback = rows.firstOrNull())
                            if (dialog) ModalOverlay("Sure?", onDismiss = { dialog = false }) { TortugaButton("Yes", { dialog = false }) }
                            if (sheet) TortugaSheet(onDismiss = { sheetShown = false }, visible = sheetShown) { TortugaButton("Stream", {}) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** How many frames a card's button takes to come after the card. */
        const val LATE_FRAMES = 3
    }
}
