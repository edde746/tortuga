package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An open [TortugaSheet] keeps keyboard, D-pad and remote focus, so keys and Back reach it and never the screen under
 * it; once it is closing, focus may leave it for the screen that takes its place. A screen reader reaches only the
 * newest sheet.
 */
@OptIn(ExperimentalTestApi::class)
class TortugaSheetTest {
    private val under = FocusRequester()
    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }
    private var visible by mutableStateOf(true)
    private var composed by mutableStateOf(true)
    private var buttons by mutableStateOf(listOf("First", "Second"))
    private var dismissed = 0
    private var screenBacks = 0

    @Test
    fun whenTheFocusedControlGoesAwayTheSheetTakesFocusBack() = runComposeUiTest {
        sheetOverScreen()
        onNodeWithText("First").assertIsFocused()

        buttons = listOf("Second")
        onNodeWithText("Second").assertIsFocused()
        screenButton().assertIsNotFocused()
    }

    @Test
    fun theScreenUnderAnOpenSheetCantTakeFocus() = runComposeUiTest {
        sheetOverScreen()
        onNodeWithText("First").assertIsFocused()

        runOnIdle { under.requestFocus() }
        onNodeWithText("First").assertIsFocused()
        screenButton().assertIsNotFocused()
    }

    @Test
    fun aClosingSheetLetsTheScreenTakeFocusWhileItSlidesAway() = runComposeUiTest {
        sheetOverScreen()
        onNodeWithText("First").assertIsFocused()

        mainClock.autoAdvance = false
        visible = false
        mainClock.advanceTimeByFrame()
        // Still on screen, sliding away: the screen that takes its place (the player after a stream is picked) gets focus.
        onNodeWithText("First").assertExists()
        runOnIdle { under.requestFocus() }
        screenButton().assertIsFocused()
    }

    @Test
    fun backClosesTheSheetNotTheScreenUnderIt() = runComposeUiTest {
        sheetOverScreen()
        onNodeWithText("First").assertIsFocused()

        runOnIdle { back.backCompleted() }
        runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, screenBacks)
        }
    }

    /**
     * Once the sheet has closed, focus is back on the control that had it before the sheet opened (a control of the
     * screen's scrolling column), not on the first control of the window, the navigation.
     */
    @Test
    fun aClosedSheetGivesFocusBackToTheControlThatHadIt() = runComposeUiTest {
        visible = false
        sheetOverScreen()
        runOnIdle { under.requestFocus() }
        onNodeWithText("Under").assertIsFocused()

        visible = true
        onNodeWithText("First").assertIsFocused()

        visible = false
        onNodeWithText("First").assertDoesNotExist()
        onNodeWithText("Under").assertIsFocused()
        onNodeWithText("Navigation").assertIsNotFocused()
    }

    /** A sheet closed by no longer composing it (TV Detail's More panel) gives focus back to that control too. */
    @Test
    fun aSheetNoLongerComposedGivesFocusBackToTheControlThatHadIt() = runComposeUiTest {
        composed = false
        sheetOverScreen()
        runOnIdle { under.requestFocus() }
        onNodeWithText("Under").assertIsFocused()

        composed = true
        onNodeWithText("First").assertIsFocused()

        composed = false
        onNodeWithText("First").assertDoesNotExist()
        onNodeWithText("Under").assertIsFocused()
        onNodeWithText("Navigation").assertIsNotFocused()
    }

    /** A sheet with nothing to focus yet (a list still loading) holds focus itself, so Escape still closes it. */
    @Test
    fun escapeClosesASheetWithNothingFocusableInIt() = runComposeUiTest {
        buttons = emptyList()
        sheetOverScreen()
        screenButton().assertIsNotFocused()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, screenBacks)
        }
    }

    /**
     * A screen reader reaches only the newest sheet, announced as a pane titled by its title: the screen under an open
     * sheet, and an earlier sheet under a newer one, are hidden from it, as the scrim keeps touch and focus from them.
     */
    @Test
    fun aScreenReaderReachesOnlyTheNewestSheet() = runComposeUiTest {
        var second by mutableStateOf(false)
        setContent {
            TortugaTheme {
                OverlayHostBox(Modifier.fillMaxSize()) {
                    TortugaButton("Under", {})
                    TortugaSheet(onDismiss = {}, title = "Sort") {
                        TortugaButton("First", {})
                        if (second) TortugaSheet(onDismiss = {}, title = "Filter") { TortugaButton("Second", {}) }
                    }
                }
            }
        }
        onNodeWithText("First").assertExists()
        onNodeWithText("Under").assertDoesNotExist()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Sort")).assertExists()

        second = true
        onNodeWithText("Second").assertExists()
        onNodeWithText("First").assertDoesNotExist()
        onNodeWithText("Under").assertDoesNotExist()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Filter")).assertExists()
    }

    /**
     * On TV a menu opened from a control in the sheet (a stream row's, a side panel over the sheet) takes focus from the
     * sheet; picking an entry runs it, and focus goes back to the sheet.
     */
    @Test
    fun onTvAMenuOpenedInTheSheetTakesFocus() = runComposeUiTest {
        var picked = 0
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.TV) {
                    OverlayHostBox(Modifier.fillMaxSize()) {
                        TortugaSheet(onDismiss = { dismissed++ }) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                TortugaButton("Row", { menu = true })
                                ItemMenu(menu, { menu = false }, listOf(ItemMenuEntry("Entry") { picked++ }), probe = "menu")
                            }
                        }
                    }
                }
            }
        }
        onNodeWithText("Row").assertIsFocused().performClick()
        onNode(hasClickAction() and hasText("Entry")).assertIsFocused()

        onRoot().performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(1, picked)
            assertEquals(0, dismissed)
        }
        onNodeWithText("Entry").assertDoesNotExist()
        onNodeWithText("Row").assertIsFocused()
    }

    /** The screen's button under the sheet, which the sheet, while it shows, hides from screen readers, not from focus. */
    private fun ComposeUiTest.screenButton() = onNodeWithTag("under", useUnmergedTree = true)

    /**
     * A screen with its own Back (as the navigation container has) beside the navigation, its button in a scrolling
     * column (a focus group of its own) and part of its focus memory, under a sheet of [buttons].
     */
    private fun ComposeUiTest.sheetOverScreen() = setContent {
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            TortugaTheme {
                OverlayHostBox(Modifier.fillMaxSize()) {
                    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true, onBackCompleted = { screenBacks++ })
                    Row {
                        TortugaButton("Navigation", {})
                        val memory = rememberFocusMemory()
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            TortugaButton("Under", {}, Modifier.testTag("under").rememberFocus(memory, "under").focusRequester(under))
                        }
                    }
                    if (composed) {
                        TortugaSheet(onDismiss = { dismissed++ }, visible = visible) {
                            for (label in buttons) TortugaButton(label, {})
                        }
                    }
                }
            }
        }
    }
}
