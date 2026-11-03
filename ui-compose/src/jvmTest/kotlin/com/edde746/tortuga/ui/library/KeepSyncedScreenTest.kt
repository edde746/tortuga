package com.edde746.tortuga.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.downloads.KeepSyncedBody
import com.edde746.tortuga.presentation.downloads.KeepSyncedRow
import com.edde746.tortuga.presentation.downloads.KeepSyncedStopDialog
import com.edde746.tortuga.presentation.downloads.KeepSyncedUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.LocalScreenFocus
import com.edde746.tortuga.ui.focus.ScreenFocus
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.focusGate
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Keep synced screen: on a phone its rules, their pause and stop, the stop question, at 1.0 and at 2.0; on TV a
 * remote's walk through them; in a desktop window the mouse and the keyboard.
 */
@OptIn(ExperimentalTestApi::class)
class KeepSyncedScreenTest {
    private val text = T.downloads.keepSynced

    private val breakingBad = KeepSyncedRow(
        metaId = "tt0903747",
        title = "Breaking Bad",
        poster = null,
        countText = T.detail.download.keepSyncedCount(3),
        statusText = text.status.downloading(2),
        paused = false,
        pauseLabel = text.pauseRule("Breaking Bad"),
        stopLabel = text.stopRule("Breaking Bad"),
    )
    private val sopranos = KeepSyncedRow(
        metaId = "tt0141842",
        title = "The Sopranos",
        poster = null,
        countText = T.detail.download.keepSyncedAll,
        statusText = T.downloads.status.paused,
        paused = true,
        pauseLabel = text.resumeRule("The Sopranos"),
        stopLabel = text.stopRule("The Sopranos"),
    )
    private val rules = KeepSyncedUiState(text.title, text.note, KeepSyncedBody.Rules(listOf(breakingBad, sopranos)))
    private val stop = KeepSyncedStopDialog(text.stop.title("Breaking Bad"), text.stop.message(2), text.stop.keep, text.stop.delete, text.stop.cancel)

    /**
     * Each rule shows its series, count and state; a press opens the series, Pause pauses one, Resume the paused one,
     * Stop asks; the question's Cancel keeps the rule, Keep downloads stops it, Delete downloads stops and deletes. The
     * pictures are kept as build/screenshots/keep-synced-phone.png and keep-synced-stop.png.
     */
    @Test
    fun aPhoneWalksTheRulesAndTheStopQuestion() = runSkikoComposeUiTest(size = Size(824f, 1600f), density = Density(2f)) {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(rules)
        val intents = KeepSyncedIntents(
            open = { calls += "open $it" },
            pause = { calls += "pause $it" },
            resume = { calls += "resume $it" },
            stop = {
                calls += "stop $it"
                state = state.copy(stopDialog = stop)
            },
            confirmStop = {
                calls += "confirm delete=$it"
                state = state.copy(stopDialog = null)
            },
            dismissStop = {
                calls += "dismiss"
                state = state.copy(stopDialog = null)
            },
            dismissNotice = {},
        )
        phone(fontScale = 1f) { KeepSyncedScreen(state, intents, onBack = {}) }
        onNodeWithText(text.note).assertIsDisplayed()
        onNodeWithText(breakingBad.countText).assertIsDisplayed()
        onNodeWithText(breakingBad.statusText).assertIsDisplayed()
        onNodeWithText(sopranos.statusText).assertIsDisplayed()
        shot("keep-synced-phone")

        onNode(hasClickAction() and hasText("Breaking Bad")).performClick()
        onNode(hasContentDescription(breakingBad.pauseLabel)).performClick()
        onNode(hasContentDescription(sopranos.pauseLabel)).performClick()
        onNode(hasContentDescription(breakingBad.stopLabel)).performClick()
        onNodeWithText(stop.message).assertIsDisplayed()
        shot("keep-synced-stop")
        onNode(hasClickAction() and hasText(stop.cancelLabel)).performClick()
        onNode(hasContentDescription(breakingBad.stopLabel)).performClick()
        onNode(hasClickAction() and hasText(stop.keepLabel)).performClick()
        onNode(hasContentDescription(breakingBad.stopLabel)).performClick()
        onNode(hasClickAction() and hasText(stop.deleteLabel!!)).performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    "open tt0903747", "pause tt0903747", "resume tt0141842",
                    "stop tt0903747", "dismiss",
                    "stop tt0903747", "confirm delete=false",
                    "stop tt0903747", "confirm delete=true",
                ),
                calls,
            )
        }
    }

    /** At the largest font scale (2.0) a rule's name, count and state go under its poster, whole. */
    @Test
    fun atTheLargestFontScaleARulesTextGoesUnderItsPosterWhole() = runSkikoComposeUiTest(size = Size(824f, 1600f), density = Density(2f)) {
        val long = breakingBad.copy(title = "Breaking Bad: The Complete Series, Remastered With Every Extra")
        phone(fontScale = 2f) { KeepSyncedScreen(rules.copy(body = KeepSyncedBody.Rules(listOf(long, sopranos))), intents(), onBack = {}) }
        val row = onNode(hasClickAction() and hasText(long.title)).getUnclippedBoundsInRoot()
        val wrong = mutableListOf<String>()
        for (line in listOf(long.title, long.countText, long.statusText)) {
            val bounds = onNode(hasText(line), useUnmergedTree = true).getUnclippedBoundsInRoot()
            // Under the 48 dp poster (72 dp tall) and the row's 12 dp inset.
            if (bounds.top < row.top + 12.dp + 72.dp) wrong += "\"$line\" starts at ${bounds.top}, beside the poster (the row's top is ${row.top})"
            if (cut(line)) wrong += "\"$line\" is cut"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        shot("keep-synced-phone-font-2")
    }

    /**
     * On TV the first rule has focus as the screen opens. Right reaches its Pause, then its Stop, and Left comes back; Up
     * and Down move between the rules, Pause to Pause and Stop to Stop. Select on Resume resumes the rule and on Pause
     * pauses it, focus staying on the button each time. The picture, Pause focused, is kept as
     * build/screenshots/keep-synced-tv.png.
     */
    @Test
    fun onTvARemoteReachesEachRulesPauseAndStopAndFocusStaysOnPause() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val store = Store()
        tv { KeepSyncedScreen(store.state, store.intents, onBack = {}) }
        val bb = "keepSynced.rule.${breakingBad.metaId}"
        val so = "keepSynced.rule.${sopranos.metaId}"
        val walk = listOf(
            null to bb,
            Key.DirectionRight to "$bb.pause",
            Key.DirectionRight to "$bb.stop",
            Key.DirectionLeft to "$bb.pause",
            Key.DirectionLeft to bb,
            Key.DirectionDown to so,
            Key.DirectionRight to "$so.resume",
            Key.DirectionUp to "$bb.pause",
            Key.DirectionRight to "$bb.stop",
            Key.DirectionDown to "$so.stop",
            Key.DirectionLeft to "$so.resume",
            Key.Enter to "$so.pause",
            Key.Enter to "$so.resume",
        )
        val reached = walk.map { (key, _) ->
            if (key != null) press(key)
            probe.focused
        }
        assertEquals(walk.map { it.second }, reached, "focus after each key: ${walk.map { it.first }}")
        runOnIdle { assertEquals(listOf("resume ${sopranos.metaId}", "pause ${sopranos.metaId}"), store.calls) }
        press(Key.DirectionUp)
        shot("keep-synced-tv")
    }

    /**
     * On TV Stop's question opens with Cancel focused and Back (the system's back: a remote's Back) closes it, focus back
     * on Stop. Stopped with Delete downloads, the question closes with focus back on Stop and the rule goes a moment
     * later, as the app's store has it: focus goes on to the rule that took its place, not to the header's back button
     * Android hands it to. The question's picture is kept as build/screenshots/keep-synced-tv-stop.png.
     */
    @Test
    fun onTvBackClosesTheStopQuestionAndFocusStaysOnTheRules() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val store = Store()
        tv { KeepSyncedScreen(store.state, store.intents, onBack = {}) }
        val bb = "keepSynced.rule.${breakingBad.metaId}"
        repeat(2) { press(Key.DirectionRight) }
        assertEquals("$bb.stop", probe.focused)
        press(Key.Enter)
        onNodeWithText(stop.message).assertIsDisplayed()
        assertEquals("keepSynced.stop.cancel", probe.focused, "the question's first focus")
        shot("keep-synced-tv-stop")
        systemBack()
        onNodeWithText(stop.message).assertDoesNotExist()
        assertEquals("$bb.stop", probe.focused, "focus after Back closed the question")

        press(Key.Enter)
        repeat(3) { if (probe.focused != "keepSynced.stop.delete") press(Key.DirectionRight) }
        assertEquals("keepSynced.stop.delete", probe.focused, "Right along the question's answers")
        press(Key.Enter)
        onNodeWithText(stop.message).assertDoesNotExist()
        assertEquals("$bb.stop", probe.focused, "focus as the question closed")
        runOnIdle { store.stopLands() }
        waitForIdle()
        onNodeWithText(breakingBad.title).assertDoesNotExist()
        val after = probe.focused
        assertTrue(after != null && after.startsWith("keepSynced.rule.${sopranos.metaId}"), "focus after the rule stopped: $after")
        runOnIdle { assertEquals(listOf("stop ${breakingBad.metaId}", "dismiss", "stop ${breakingBad.metaId}", "confirm delete=true"), store.calls) }
    }

    /**
     * In a desktop window a click opens a rule's series, pauses and resumes it and asks to stop it (Cancel by a click as
     * well). Tab reaches each rule's Pause and Stop in turn (Shift+Tab back), Enter presses them, focus staying on Pause
     * as it turns Resume, and Escape (the system's back) closes the question, focus back on Stop.
     */
    @Test
    fun onDesktopTheMouseAndTheKeyboardReachPauseAndStop() = runComposeUiTest {
        val store = Store()
        desktop { KeepSyncedScreen(store.state, store.intents, onBack = {}) }
        onNode(hasClickAction() and hasText(breakingBad.title)).performMouseInput { click() }
        onNode(hasClickAction() and hasContentDescription(breakingBad.pauseLabel)).performMouseInput { click() }
        onNode(hasClickAction() and hasContentDescription(text.resumeRule(breakingBad.title))).performMouseInput { click() }
        onNode(hasClickAction() and hasContentDescription(breakingBad.stopLabel)).performMouseInput { click() }
        onNode(hasClickAction() and hasText(stop.cancelLabel)).performMouseInput { click() }
        onNodeWithText(stop.message).assertDoesNotExist()
        runOnIdle {
            assertEquals(listOf("open ${breakingBad.metaId}", "pause ${breakingBad.metaId}", "resume ${breakingBad.metaId}", "stop ${breakingBad.metaId}", "dismiss"), store.calls)
        }
        store.calls.clear()

        val bb = "keepSynced.rule.${breakingBad.metaId}"
        onNode(hasClickAction() and hasText(breakingBad.title)).requestFocus()
        val walk = listOf(
            Triple(Key.Tab, false, "$bb.pause"),
            Triple(Key.Tab, false, "$bb.stop"),
            Triple(Key.Tab, false, "keepSynced.rule.${sopranos.metaId}"),
            Triple(Key.Tab, true, "$bb.stop"),
            Triple(Key.Tab, true, "$bb.pause"),
            Triple(Key.Enter, false, "$bb.resume"),
            Triple(Key.Tab, false, "$bb.stop"),
            Triple(Key.Enter, false, "keepSynced.stop.cancel"),
        )
        val reached = walk.map { (key, shift, _) ->
            press(key, shift)
            probe.focused
        }
        assertEquals(walk.map { it.third }, reached, "focus after each key: ${walk.map { (key, shift) -> if (shift) "Shift+$key" else "$key" }}")
        systemBack()
        onNodeWithText(stop.message).assertDoesNotExist()
        assertEquals("$bb.stop", probe.focused, "focus after Escape closed the question")
        runOnIdle { assertEquals(listOf("pause ${breakingBad.metaId}", "stop ${breakingBad.metaId}", "dismiss"), store.calls) }
    }

    /**
     * The screen's store as far as the screen sees it: Pause and Resume turn a rule paused and back, Stop asks, Cancel
     * closes the question, Keep downloads and Delete downloads close it, and the rule goes once the stop lands
     * ([stopLands]), as the app's store does after the downloads manager has stopped it. Each intent is kept in [calls].
     */
    private inner class Store {
        var state by mutableStateOf(rules)
        val calls = mutableListOf<String>()
        private var stopping: String? = null

        private fun shown(): List<KeepSyncedRow> = (state.body as KeepSyncedBody.Rules).rules

        /** The stop confirmed last lands: its rule goes. */
        fun stopLands() {
            val left = shown().filter { it.metaId != stopping }
            state = state.copy(body = if (left.isEmpty()) KeepSyncedBody.Empty(text.empty) else KeepSyncedBody.Rules(left))
        }

        private fun paused(metaId: String, paused: Boolean) {
            state = state.copy(
                body = KeepSyncedBody.Rules(
                    shown().map {
                        if (it.metaId != metaId) {
                            it
                        } else {
                            it.copy(
                                paused = paused,
                                statusText = if (paused) T.downloads.status.paused else text.status.downloading(2),
                                pauseLabel = if (paused) text.resumeRule(it.title) else text.pauseRule(it.title),
                            )
                        }
                    },
                ),
            )
        }

        val intents = KeepSyncedIntents(
            open = { calls += "open $it" },
            pause = {
                calls += "pause $it"
                paused(it, true)
            },
            resume = {
                calls += "resume $it"
                paused(it, false)
            },
            stop = {
                calls += "stop $it"
                stopping = it
                state = state.copy(stopDialog = stop)
            },
            confirmStop = { delete ->
                calls += "confirm delete=$delete"
                state = state.copy(stopDialog = null)
            },
            dismissStop = {
                calls += "dismiss"
                state = state.copy(stopDialog = null)
            },
            dismissNotice = {},
        )
    }

    /** The element focused last, by its harness name. */
    private val probe = FocusedProbe()

    /** The system's back: Esc on desktop, a remote's Back (Android 13 and later: a predictive back). */
    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }

    private fun ComposeUiTest.systemBack() {
        runOnIdle { back.backCompleted() }
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key, shift: Boolean = false) {
        onRoot().performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
        waitForIdle()
    }

    /** [content] on a 1080p TV (960 × 540 dp), the arrow keys as the D-pad. */
    private fun ComposeUiTest.tv(content: @Composable () -> Unit) = window(FormFactor.TV, 960, 540, content)

    /** [content] in a 1000 × 700 dp desktop window. */
    private fun ComposeUiTest.desktop(content: @Composable () -> Unit) = window(FormFactor.DESKTOP, 1000, 700, content)

    /**
     * [content] as the app places a screen ([ScreenFocus]), in a window of [width] × [height] dp. On TV, as on Android,
     * focus that drops (the focused element left) goes to the window's first element; a desktop leaves it nowhere.
     */
    private fun ComposeUiTest.window(formFactor: FormFactor, width: Int, height: Int, content: @Composable () -> Unit) {
        setContent {
            val owner = remember {
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            }
            val screen = remember { ScreenFocus { true } }
            val first = remember { FocusRequester() }
            val held = remember { booleanArrayOf(false) }
            val drop = if (formFactor != FormFactor.TV) {
                Modifier
            } else {
                Modifier.onFocusChanged {
                    if (it.hasFocus) held[0] = true else if (held[0]) first.requestFocus()
                }
            }
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(width.dp, height.dp).background(TortugaColors.Background)) {
                        AdaptiveLayout(formFactor, Modifier.arrowKeyFocus().then(drop)) {
                            OverlayHostBox {
                                Box(Modifier.fillMaxSize().focusRequester(first).focusGroup().then(screen.modifier).focusGate { true }) {
                                    CompositionLocalProvider(LocalScreenFocus provides screen) { content() }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun intents() = KeepSyncedIntents({}, {}, {}, {}, {}, {}, {})

    private fun ComposeUiTest.phone(fontScale: Float, content: @Composable () -> Unit) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            TortugaTheme {
                Box(Modifier.size(412.dp, 800.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(FormFactor.HANDHELD) { OverlayHostBox { content() } }
                }
            }
        }
    }

    /** Whether [text]'s lines run past its line limit or its box. */
    private fun ComposeUiTest.cut(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText(text), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().didOverflowHeight
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        val file = File("build/screenshots/$name.png")
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file), "no PNG writer")
    }
}
