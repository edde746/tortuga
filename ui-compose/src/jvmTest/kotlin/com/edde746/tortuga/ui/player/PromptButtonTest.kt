package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.input.InputContext
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.Keymap
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.PlayerPrompt
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.PromptKind
import com.edde746.tortuga.presentation.player.ScrubState
import com.edde746.tortuga.presentation.player.StillWatchingState
import com.edde746.tortuga.presentation.player.TrackItem
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.watch.testPlayerWatch
import com.edde746.tortuga.ui.watch.testRoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The prompt button (the skip button, up next): TV focus and keys, desktop keys and hover, a press, its place, an open
 * menu kept still as it goes and comes, its countdown's fill, its icon and its label's room.
 */
@OptIn(ExperimentalTestApi::class)
class PromptButtonTest {
    private val intro = PlayerPrompt(
        PromptKind.INTRO,
        lead = null,
        text = T.player.skip.intro,
        progress = null,
        secondsLeft = null,
        command = PlayerCommand.SkipSegment,
        withControls = false,
    )
    private val counting = intro.copy(progress = 0.2f, secondsLeft = 3)
    private val upNext = PlayerPrompt(
        PromptKind.UP_NEXT,
        lead = T.session.episodeTitle("S1E2", ""),
        text = "Please Remain Calm",
        progress = null,
        secondsLeft = null,
        command = PlayerCommand.NextEpisode,
        withControls = false,
    )
    private val asking = StillWatchingState(countdown = "Pausing in 25 seconds", secondsLeft = 25, progress = 1f / 6)

    /** Playing with the controls down. */
    private val hidden = testPlayerState().copy(controlsVisible = false)

    /**
     * On TV the button takes focus as it appears with the controls down, the skip button and up next alike; Select
     * presses it once: the store takes the key (and its key-up), so the focused button doesn't act on it as well.
     */
    @Test
    fun onTvTheButtonTakesFocusAsItAppearsAndSelectPressesItOnce() {
        for ((prompt, name) in listOf(counting to "player.skip", upNext.copy(progress = 0.2f) to "player.upNext")) runComposeUiTest {
            val store = Store(InputProfile.TV, hidden)
            val probe = PlayerProbe()
            player(FormFactor.TV, probe = probe, onCommand = store::dispatch, onInput = store::onInput) { store.state }
            store.state = store.state.copy(prompt = prompt)
            onNodeWithContentDescription(prompt.label).assertIsFocused()
            runOnIdle { assertEquals(name, probe.focused) }
            press(Key.Enter)
            runOnIdle { assertEquals(listOf(prompt.command), store.commands) }
        }
    }

    /**
     * A play resumed inside an intro opens the player with the button already there, the controls down: on TV it has
     * focus from the player's first frame, as one appearing later does, and Select skips once instead of pausing.
     */
    @Test
    fun onTvTheButtonThereAsThePlayerOpensHasFocusAndSelectSkipsOnce() = runComposeUiTest {
        val store = Store(InputProfile.TV, hidden.copy(prompt = intro))
        val probe = PlayerProbe()
        player(FormFactor.TV, probe = probe, onCommand = store::dispatch, onInput = store::onInput) { store.state }
        onNodeWithText(intro.label).assertIsFocused()
        runOnIdle { assertEquals("player.skip", probe.focused) }
        press(Key.Enter)
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.SkipSegment), store.commands) }
    }

    /**
     * With the controls up the button takes focus as it appears too, over play/pause; the D-pad is focus navigation
     * then, so Select presses the focused button: once, up next playing the next episode.
     */
    @Test
    fun onTvTheButtonTakesFocusOverTheControlsAndSelectPressesItOnce() = runComposeUiTest {
        val store = Store(InputProfile.TV, testPlayerState())
        val probe = PlayerProbe()
        player(FormFactor.TV, probe = probe, onCommand = store::dispatch, onInput = store::onInput) { store.state }
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        store.state = store.state.copy(prompt = upNext)
        onNodeWithContentDescription(upNext.label).assertIsFocused()
        press(Key.Enter)
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.NextEpisode), store.commands) }
    }

    /**
     * Past its own seconds the button shows only with the controls: brought back with them by the viewer (Up, Down,
     * Select pausing), on TV it leaves focus to them, play/pause, where Select doesn't skip.
     */
    @Test
    fun onTvTheButtonBackWithTheControlsLeavesThemFocus() = runComposeUiTest {
        val probe = PlayerProbe()
        var state by mutableStateOf(hidden)
        player(FormFactor.TV, probe = probe) { state }
        waitForIdle()
        state = state.copy(controlsVisible = true, prompt = intro.copy(withControls = true))
        runOnIdle { assertEquals("player.playPause", probe.focused) }
    }

    /** On TV a button appearing while the viewer scrubs on the seek bar leaves focus there. */
    @Test
    fun onTvTheButtonAppearingDuringAScrubLeavesFocusOnTheSeekBar() = runComposeUiTest {
        val probe = PlayerProbe()
        var state by mutableStateOf(testPlayerState())
        val timeline = MutableStateFlow(testTimeline)
        player(FormFactor.TV, probe = probe, timeline = timeline) { state }
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        press(Key.DirectionUp)
        runOnIdle { assertEquals("player.scrub", probe.focused, "focus after Up from play/pause") }
        state = state.copy(scrubbing = true)
        timeline.value = testTimeline.copy(scrub = ScrubState(12.0, 0.6f, "0:12", "−0:03", chapter = null))
        waitForIdle()
        state = state.copy(prompt = intro)
        waitForIdle()
        runOnIdle { assertEquals("player.scrub", probe.focused) }
    }

    /**
     * Down from the button with the controls down brings them up (the store's ShowControls) and focus lands on
     * play/pause, not back on the button; with the controls up, Down moves focus from it into the controls. Either Down
     * reaches the store first, so a move from the button counts as the viewer's input too.
     */
    @Test
    fun onTvDownFromTheButtonReachesTheControls() = runComposeUiTest {
        val store = Store(InputProfile.TV, hidden.copy(prompt = intro))
        val probe = PlayerProbe()
        player(FormFactor.TV, probe = probe, onCommand = store::dispatch, onInput = store::onInput) { store.state }
        runOnIdle { assertEquals("player.skip", probe.focused) }
        press(Key.DirectionDown)
        runOnIdle {
            assertEquals(listOf<PlayerCommand>(PlayerCommand.ShowControls), store.commands)
            assertEquals("player.playPause", probe.focused, "focus after Down brought the controls up")
        }

        store.state = store.state.copy(prompt = null)
        waitForIdle()
        store.state = store.state.copy(prompt = intro)
        runOnIdle { assertEquals("player.skip", probe.focused, "focus as the button appears over the controls") }
        press(Key.DirectionDown)
        runOnIdle {
            val focused = probe.focused
            assertTrue(focused != null && focused != "player.skip" && focused in probe.bounds, "Down from the button left focus on $focused")
            assertEquals(listOf(InputKey.DOWN, InputKey.DOWN), store.keys, "the keys the store saw")
        }
    }

    /**
     * The button gone while it has focus sends focus to play/pause with the controls up. A control the viewer moved to
     * from it keeps focus when it goes.
     */
    @Test
    fun onTvFocusGoesToPlayPauseWhenTheFocusedButtonGoes() = runComposeUiTest {
        val store = Store(InputProfile.TV, testPlayerState(prompt = intro))
        val probe = PlayerProbe()
        player(FormFactor.TV, probe = probe, onCommand = store::dispatch, onInput = store::onInput) { store.state }
        runOnIdle { assertEquals("player.skip", probe.focused) }
        store.state = store.state.copy(prompt = null)
        runOnIdle { assertEquals("player.playPause", probe.focused, "focus once the focused button went") }

        store.state = store.state.copy(prompt = intro)
        runOnIdle { assertEquals("player.skip", probe.focused) }
        press(Key.DirectionDown)
        val moved = runOnIdle { probe.focused }
        assertNotEquals("player.skip", moved)
        store.state = store.state.copy(prompt = null)
        runOnIdle { assertEquals(moved, probe.focused, "the control focus moved to kept it") }
    }

    /**
     * Credits that run into up next: on TV the focused skip button keeps focus as it turns into up next, and the
     * harness names follow it.
     */
    @Test
    fun onTvTheFocusedSkipButtonTurningIntoUpNextKeepsFocus() = runComposeUiTest {
        val credits = intro.copy(kind = PromptKind.CREDITS, text = T.player.skip.credits)
        val probe = PlayerProbe()
        var state by mutableStateOf(hidden.copy(prompt = credits))
        player(FormFactor.TV, probe = probe) { state }
        runOnIdle { assertEquals("player.skip", probe.focused) }
        state = state.copy(prompt = upNext)
        onNodeWithContentDescription(upNext.label).assertIsFocused()
        runOnIdle {
            assertEquals("player.upNext", probe.focused)
            assertTrue("player.skip" !in probe.bounds && "player.upNext" in probe.bounds, "the names placed: ${probe.bounds.keys}")
        }
    }

    /** On desktop Enter reaches the store as Select while the button shows, and presses it once. */
    @Test
    fun onDesktopEnterPressesTheButton() {
        for (prompt in listOf(intro, upNext)) runComposeUiTest {
            val store = Store(InputProfile.DESKTOP, hidden.copy(prompt = prompt))
            player(FormFactor.DESKTOP, onCommand = store::dispatch, onInput = store::onInput) { store.state }
            press(Key.Enter)
            runOnIdle {
                assertEquals(listOf(InputKey.SELECT), store.keys)
                assertEquals(listOf(prompt.command), store.commands)
            }
        }
    }

    /** A tap or a click on the button does what it says: the skip button skips, up next plays the next episode. */
    @Test
    fun aPressDoesWhatTheButtonSays() {
        for (prompt in listOf(intro, upNext)) runComposeUiTest {
            val commands = mutableListOf<PlayerCommand>()
            player(FormFactor.HANDHELD, onCommand = { commands += it }) { hidden.copy(prompt = prompt) }
            onNodeWithContentDescription(prompt.label).performClick()
            runOnIdle { assertEquals(listOf(prompt.command), commands) }
        }
    }

    /**
     * The button stands in the bottom trailing corner: under the sleep timer's question when that shows too, at its
     * trailing edge; right above the controls, clear of the timeline and the buttons (the episode buttons among them);
     * with the controls down, at the controls' bottom margin.
     */
    @Test
    fun onTvTheButtonSitsInTheCornerAboveTheControls() = runComposeUiTest {
        val probe = PlayerProbe()
        var state by mutableStateOf(testPlayerState(prompt = upNext, stillWatching = asking))
        player(FormFactor.TV, probe = probe) { state }
        waitForIdle()
        // Pause ends at the card's padding (16 dp) inside its edge.
        val pause = probe.bounds.getValue("player.stillWatching.pause")
        val cardEnd = pause.right + px(16.dp)
        val under = probe.bounds.getValue("player.upNext")
        assertEquals(cardEnd, under.right, 0.5f, "the button's trailing edge under the card")
        assertTrue(under.top >= pause.bottom + px(16.dp), "the button's top ${under.top} below the card's bottom")

        state = state.copy(stillWatching = null)
        waitForIdle()
        val pill = probe.bounds.getValue("player.upNext")
        assertEquals(cardEnd, pill.right, 0.5f, "the button's trailing edge")
        val controls = listOf(
            "player.position", "player.scrub", "player.remaining", "player.previous", "player.rewind", "player.playPause", "player.fastForward", "player.next",
            "player.speed", "player.options",
        )
        for (name in controls) assertTrue(!pill.overlaps(probe.bounds.getValue(name)), "the button over $name")
        val controlsTop = controls.minOf { probe.bounds.getValue(it).top }
        assertTrue(pill.bottom <= controlsTop && controlsTop - pill.bottom <= px(16.dp), "the button's bottom ${pill.bottom} right above the controls' top $controlsTop")
        val buttonsBottom = probe.bounds.getValue("player.playPause").bottom

        state = state.copy(controlsVisible = false)
        waitForIdle()
        val corner = probe.bounds.getValue("player.upNext")
        assertEquals(buttonsBottom, corner.bottom, 0.5f, "the button's bottom without the controls")
        assertEquals(pill.right, corner.right, 0.5f, "the button's trailing edge without the controls")
    }

    /**
     * A guest of a host-only room has the lock line above the timeline. With the controls up the button sits directly
     * above the timeline row, its trailing edge on the row's, beside the line where it fits there (a wide window), else
     * 12 dp above the line (a phone upright); 12 dp clear of the line and of every control either way.
     */
    @Test
    fun aGuestsButtonSitsBesideTheLockLineWhereItFits() {
        val guest = testPlayerWatch(room = testRoom(host = false), canControl = false, canChangeEpisode = false)
        for ((size, beside) in listOf(DpSize(960.dp, 540.dp) to true, DpSize(411.dp, 760.dp) to false)) runComposeUiTest {
            val probe = PlayerProbe()
            player(FormFactor.HANDHELD, probe = probe, size = size) { testPlayerState(prompt = upNext).copy(watch = guest) }
            waitForIdle()
            val where = "${size.width} × ${size.height}"
            val button = probe.bounds.getValue("player.upNext")
            val line = probe.bounds.getValue("player.watch.locked")
            val timeline = listOf("player.position", "player.scrub", "player.remaining").minOf { probe.bounds.getValue(it).top }
            assertEquals(probe.bounds.getValue("player.remaining").right, button.right, 1f, "$where: the button's trailing edge")
            if (beside) {
                assertEquals(timeline - px(12.dp), button.bottom, 1f, "$where: the button's bottom over the timeline row's top $timeline")
            } else {
                assertEquals(line.top - px(12.dp), button.bottom, 1f, "$where: the button's bottom over the lock line $line")
            }
            val controls = listOf(
                "player.watch.locked", "player.position", "player.scrub", "player.remaining", "player.previous", "player.rewind",
                "player.playPause", "player.fastForward", "player.next", "player.speed", "player.options",
            )
            probe.requireClear("$where: the button", button, controls, px(12.dp).toFloat())
        }
    }

    /**
     * Up next's button is as wide as its label up to 320 dp (420 dp on TV): past that the episode's name is cut short
     * and its code stays whole.
     */
    @Test
    fun aLongNameIsCutShortAndTheCodeStaysWhole() {
        val long = upNext.copy(text = "The Unbearably Long Name of an Episode That Goes On and On and On")
        for ((formFactor, width) in listOf(FormFactor.HANDHELD to 320.dp, FormFactor.TV to 420.dp)) runComposeUiTest {
            val probe = PlayerProbe()
            var state by mutableStateOf(hidden.copy(prompt = long))
            player(formFactor, probe = probe) { state }
            waitForIdle()
            assertEquals(px(width).toFloat(), probe.bounds.getValue("player.upNext").width, 1f, "the button's width on $formFactor")
            assertTrue(whole(long.lead!!), "the code cut short on $formFactor")
            assertTrue(!whole(long.text), "the name not cut short on $formFactor")

            state = hidden.copy(prompt = upNext)
            waitForIdle()
            assertTrue(probe.bounds.getValue("player.upNext").width < px(width), "a short label's button as wide as a long one's on $formFactor")
            assertTrue(whole(upNext.text), "a short name cut short on $formFactor")
        }
    }

    /**
     * A mouse resting where the button appears sends the store nothing (Compose's own move for the changed layout
     * would stop the countdown as it starts); a real move over it does, as over the controls.
     */
    @Test
    fun aMouseRestingWhereTheButtonAppearsMovesNothing() = runComposeUiTest {
        val probe = PlayerProbe()
        val events = mutableListOf<InputEvent>()
        var state by mutableStateOf(hidden.copy(prompt = intro))
        player(FormFactor.DESKTOP, probe = probe, onInput = { events += it; true }) { state }
        waitForIdle()
        val pill = probe.bounds.getValue("player.skip")
        state = state.copy(prompt = null)
        waitForIdle()
        onRoot().performMouseInput { moveTo(pill.center) }
        waitForIdle()
        assertTrue(events.any { it is InputEvent.PointerMove }, "the mouse's move reached the store")
        events.clear()

        state = state.copy(prompt = counting)
        waitForIdle()
        assertEquals(emptyList(), events.filterIsInstance<InputEvent.PointerMove>(), "moves as the button appeared under the mouse")
        onRoot().performMouseInput { moveBy(Offset(4f, 0f)) }
        waitForIdle()
        assertEquals(listOf(InputEvent.PointerMove(overControls = true)), events.filterIsInstance<InputEvent.PointerMove>(), "a move over the button")
    }

    /**
     * An open track menu stays where it opened while the skip button goes (the intro over) and comes back: none of its
     * rows moves from under the pointer. On the desktop the menu, opened over the button, is clear of it; on phones
     * and TV it is a sheet. In the desktop app's smallest window.
     */
    @Test
    fun anOpenMenuStaysPutAsTheButtonGoesAndComes() {
        val tracks = listOf(TrackItem(null, "Off", null, selected = false)) +
            (1L..5L).map { TrackItem(it, "Track $it", "Subtitles · SRT", selected = it == 1L) }
        for (formFactor in listOf(FormFactor.DESKTOP, FormFactor.HANDHELD, FormFactor.TV)) runComposeUiTest {
            val probe = PlayerProbe()
            var state by mutableStateOf(testPlayerState(prompt = intro).copy(subtitleTracks = tracks))
            player(formFactor, probe = probe, size = DpSize(640.dp, 480.dp)) { state }
            waitForIdle()
            val pill = probe.bounds.getValue("player.skip")
            fun rows() = probe.bounds.filterKeys { it.startsWith("player.menu.") }

            state = state.copy(menu = PlayerMenu.SUBTITLES)
            waitForIdle()
            val opened = rows()
            assertTrue(opened.size >= 3, "$formFactor: the menu's rows on screen: ${opened.keys}")
            if (formFactor == FormFactor.DESKTOP) for ((name, row) in opened) assertTrue(!row.overlaps(pill), "$name ($row) over the skip button ($pill)")

            state = state.copy(prompt = null)
            waitForIdle()
            assertEquals(opened, rows(), "$formFactor: the menu's rows once the skip button has gone")

            state = state.copy(prompt = intro)
            waitForIdle()
            assertEquals(opened, rows(), "$formFactor: the menu's rows once the skip button is back")
        }
    }

    /** A countdown fills the button from its leading edge, up next's as the skip button's; without one it is one fill. */
    @Test
    fun theCountdownFillsTheButton() {
        for (prompt in listOf(intro, upNext)) runComposeUiTest {
            var state by mutableStateOf(hidden.copy(prompt = prompt.copy(progress = 0.5f)))
            player(FormFactor.HANDHELD) { state }
            waitForIdle()
            val (filled, rest) = fillSamples(prompt.label)
            assertEquals(TortugaColors.SurfaceHigh, rest, "${prompt.kind}: the part the countdown hasn't reached")
            assertNotEquals(rest, filled, "${prompt.kind}: the part it has")

            state = state.copy(prompt = prompt)
            waitForIdle()
            val (start, end) = fillSamples(prompt.label)
            assertEquals(TortugaColors.SurfaceHigh, start, "${prompt.kind} without a countdown: the leading part")
            assertEquals(TortugaColors.SurfaceHigh, end, "${prompt.kind} without a countdown: the trailing part")
        }
    }

    /** The skip button wears the fast-forward icon, up next the skip-next one. */
    @Test
    fun theIconSaysWhatTheButtonDoes() = runComposeUiTest {
        var state by mutableStateOf(hidden.copy(prompt = intro))
        player(FormFactor.HANDHELD) { state }
        val forward = iconColumn(intro.label)
        state = hidden.copy(prompt = upNext)
        val skipNext = iconColumn(upNext.label)
        // The icons' column 15 of 24: fast-forward's second triangle crosses it, skip-next's gap before its bar doesn't.
        assertTrue(forward >= px(4.dp), "fast-forward drawn on $forward rows of the column")
        assertTrue(skipNext <= px(1.dp), "skip-next drawn on $skipNext rows of the column")
    }

    private fun ComposeUiTest.px(dp: Dp): Int = with(density) { dp.toPx() }.roundToInt()

    /** Whether the label's part [text] is drawn whole: as wide as it is laid out, not cut short. */
    private fun ComposeUiTest.whole(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        return layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.size.width
    }

    /** The button's colour at a quarter and three quarters of its width, near its top edge (clear of its content). */
    private fun ComposeUiTest.fillSamples(label: String): Pair<Color, Color> {
        val bounds = onNodeWithContentDescription(label).getBoundsInRoot()
        val pixels = onRoot().captureToImage().toPixelMap()
        val y = px(bounds.top + (bounds.bottom - bounds.top) * 0.15f)
        val width = bounds.right - bounds.left
        return pixels[px(bounds.left + width * 0.25f), y] to pixels[px(bounds.left + width * 0.75f), y]
    }

    /** How many rows of the button's icon (20 dp, 16 dp in from its leading edge) column 15 of 24 is drawn on. */
    private fun ComposeUiTest.iconColumn(label: String): Int {
        val bounds = onNodeWithContentDescription(label).getBoundsInRoot()
        val pixels: PixelMap = onRoot().captureToImage().toPixelMap()
        val x = px(bounds.left + 16.dp + 20.dp * (15.5f / 24f))
        val middle = (bounds.top + bounds.bottom) / 2
        val threshold = (TortugaColors.SurfaceHigh.luminance() + TortugaColors.OnVideo.luminance()) / 2
        return (px(middle - 10.dp) until px(middle + 10.dp)).count { pixels[x, it].luminance() > threshold }
    }

    /**
     * The store's side of the player's keys over the real keymap of [profile]: a key it maps goes out as a command and
     * is taken, its key-up too, as PlayerStore.onInput does; ShowControls brings the controls up.
     */
    private class Store(profile: InputProfile, initial: PlayerUiState) {
        var state by mutableStateOf(initial)
        val commands = mutableListOf<PlayerCommand>()
        val keys = mutableListOf<InputKey>()
        private val keymap = Keymap.forProfile(profile)
        private val taken = HashSet<InputKey>()

        fun onInput(event: InputEvent): Boolean = when (event) {
            is InputEvent.KeyDown -> {
                keys += event.key
                val context = InputContext(controlsVisible = state.controlsVisible, prompt = state.prompt?.command)
                val command = keymap.key(event.key, event.modifiers, context)
                if (command != null) {
                    taken += event.key
                    dispatch(command)
                }
                command != null
            }
            is InputEvent.KeyUp -> taken.remove(event.key)
            else -> false
        }

        fun dispatch(command: PlayerCommand) {
            commands += command
            if (command == PlayerCommand.ShowControls) state = state.copy(controlsVisible = true)
        }
    }
}
