package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.EpisodeButtons
import com.edde746.tortuga.presentation.player.PlaybackStatus
import com.edde746.tortuga.presentation.player.PlayerOption
import com.edde746.tortuga.presentation.player.PlayerOptionId
import com.edde746.tortuga.presentation.watch.WatchActionUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.watch.testMismatch
import com.edde746.tortuga.ui.watch.testOffset
import com.edde746.tortuga.ui.watch.testPlayerWatch
import com.edde746.tortuga.ui.watch.testRoom
import com.edde746.tortuga.watch.WatchControlMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Watch together in the player: the session indicator and the room panel it opens (the host's commands, this device's
 * offset), starting a room from the options, a guest's file search and mismatch prompt in place of the spinner, the
 * room's pill with the transient message in the top bar's row, and a guest's controls off in a host-only room.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerWatchTest {
    private val commands = mutableListOf<PlayerCommand>()

    /** In a room the top bar shows how many watch, with the host badge on the host's player; it opens the room panel. */
    @Test
    fun theSessionIndicatorShowsTheRoomAndOpensItsPanel() = runComposeUiTest {
        player(FormFactor.DESKTOP, onCommand = { commands += it }) { testPlayerState().copy(watch = testPlayerWatch(room = testRoom(host = true))) }
        onNodeWithContentDescription(T.watch.player.watching(2)).performClick()
        onNodeWithText(T.watch.player.hostBadge).assertExists()
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.OpenMenu(PlayerMenu.WATCH_TOGETHER)), commands) }
    }

    /**
     * The room panel lays out the room: the code, the participants, Make host, and End for everyone once confirmed; no
     * offset while this player doesn't play the room's media.
     */
    @Test
    fun theRoomPanelHandsTheRoomOverAndEndsItAfterAsking() = runComposeUiTest {
        player(FormFactor.HANDHELD, onCommand = { commands += it }) {
            testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = testPlayerWatch(room = testRoom(host = true)))
        }
        onNodeWithText("K7M-Q2X").assertExists()
        onNodeWithText(T.watch.player.offsetTitle).assertDoesNotExist()
        onNodeWithText(T.watch.room.makeHost).performScrollTo().performClick()
        // End's 48 dp hit square reaches into the panel's bottom padding, past the content performScrollTo measures the
        // view by, so it would scroll for ever at the end: its label (the unmerged node) scrolls into view instead.
        onNodeWithText(T.watch.room.end, useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText(T.watch.room.endTitle).assertExists()
        onAllNodesWithText(T.watch.room.endConfirm).onLast().performClick()
        runOnIdle {
            assertEquals(
                listOf<PlayerCommand>(PlayerCommand.Watch(WatchCommand.MakeHost("anna")), PlayerCommand.Watch(WatchCommand.End)),
                commands,
            )
        }
    }

    /**
     * The room panel sends the host's other commands: who controls playback, the lock, Remove once confirmed, and Leave
     * once confirmed (the room going on with the next participant as host).
     */
    @Test
    fun theRoomPanelSendsTheHostsCommands() = runComposeUiTest {
        player(FormFactor.HANDHELD, onCommand = { commands += it }) {
            testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = testPlayerWatch(room = testRoom(host = true)))
        }
        onNodeWithText(T.watch.controlMode.anyone).performScrollTo().performClick()
        onNodeWithText(T.watch.room.lock).performScrollTo().performClick()
        onNodeWithContentDescription(T.watch.room.kickNamed("Anna")).performScrollTo().performClick()
        onNodeWithText(T.watch.room.kickTitle("Anna")).assertExists()
        onAllNodesWithText(T.watch.room.kickConfirm).onLast().performClick()
        // As End above: Leave's label scrolls into view, its hit square reaching into the panel's bottom padding.
        onNodeWithText(T.watch.room.leave, useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText(T.watch.room.leaveHostTitle).assertExists()
        onNodeWithText(T.watch.room.leaveConfirm).performClick()
        runOnIdle {
            assertEquals<List<PlayerCommand>>(
                listOf(WatchCommand.SetControlMode(WatchControlMode.ANYONE), WatchCommand.Lock(true), WatchCommand.Kick("anna"), WatchCommand.Leave)
                    .map { PlayerCommand.Watch(it) },
                commands,
            )
        }
    }

    /**
     * While this player plays the room's media the room panel shows its offset: the value, what it is for, and its
     * buttons, each sending its nudge or Reset. On TV Copy still takes focus as the panel opens (a stray press on a
     * nudge would move playback), and Up reaches the offset's buttons.
     */
    @Test
    fun theRoomPanelNudgesThisDevicesOffset() = runComposeUiTest {
        val probe = PlayerProbe()
        player(FormFactor.TV, probe = probe, onCommand = { commands += it }) {
            testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = testPlayerWatch(room = testRoom(host = false), offset = testOffset))
        }
        onNodeWithText(T.watch.player.offsetDetail).assertExists()
        onNodeWithText(testOffset.value).assertExists()
        onNodeWithText(T.watch.room.copyCode).assertIsFocused()
        press(Key.DirectionUp)
        runOnIdle { assertTrue(probe.focused?.startsWith("player.watch.offset.") == true, "Up from Copy reached ${probe.focused}") }

        for (action in testOffset.actions) onNodeWithText(action.label).performScrollTo().performClick()
        runOnIdle {
            assertEquals<List<PlayerCommand>>(
                listOf(
                    WatchCommand.NudgeOffset(-5_000),
                    WatchCommand.NudgeOffset(-500),
                    WatchCommand.NudgeOffset(500),
                    WatchCommand.NudgeOffset(5_000),
                    WatchCommand.ResetOffset,
                ).map { PlayerCommand.Watch(it) },
                commands,
            )
        }
    }

    /**
     * On TV Up from Copy into the offset's buttons lands on the first (-5 s), else on the one that had focus last
     * (-0.5 s), wherever the panel's width wraps them: on one line in a 1920 dp wide TV's panel, or with Reset on a line
     * of its own, nearer Copy than -5 s, in a 960 dp wide one's (German's "Zurücksetzen" puts it there whatever the
     * font). Every case's wrong moves are listed.
     */
    @Test
    fun onTvUpFromCopyLandsOnTheOffsetsFirstButtonElseTheOneFocusedLastWhereverThePanelWrapsThem() {
        val german = testOffset.copy(actions = testOffset.actions.dropLast(1) + WatchActionUi("Zurücksetzen", WatchCommand.ResetOffset))
        val cases = listOf(
            DpSize(960.dp, 540.dp) to testOffset,
            DpSize(960.dp, 540.dp) to german,
            DpSize(1920.dp, 1080.dp) to testOffset,
        )
        val wrong = mutableListOf<String>()
        for ((size, offset) in cases) runComposeUiTest {
            val probe = FocusedProbe()
            player(FormFactor.TV, probe = probe, size = size) {
                testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = testPlayerWatch(room = testRoom(host = false), offset = offset))
            }
            val case = "${size.width.value.toInt()} dp wide, Reset as \"${offset.actions.last().label}\""
            // Nothing in the offset's buttons focused yet, then -0.5 s focused last.
            for ((last, expected) in listOf(null to "player.watch.offset.0", 1 to "player.watch.offset.1")) {
                last?.let { onNodeWithText(offset.actions[it].label).requestFocus() }
                onNodeWithText(T.watch.room.copyCode).requestFocus()
                press(Key.DirectionUp)
                val after = last?.let { " after \"${offset.actions[it].label}\"" } ?: ""
                if (probe.focused != expected) wrong += "$case: Up from Copy$after went to ${probe.focused}, not $expected"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * Outside a room the options list Watch together first, whose page starts a room with what plays; on TV the first
     * way to start takes focus.
     */
    @Test
    fun outsideARoomThePanelStartsOneWithWhatPlays() = runComposeUiTest {
        val watch = testPlayerWatch()
        val option = PlayerOption(PlayerOptionId.WATCH_TOGETHER, watch.title, T.player.options.off, adjusted = false, PlayerMenu.WATCH_TOGETHER, emptyList(), on = null, toggle = null)
        player(FormFactor.TV, onCommand = { commands += it }) {
            testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = watch, options = listOf(option) + testPlayerOptions)
        }
        onNodeWithText(T.watch.player.startTitle).assertExists()
        onNodeWithText(T.watch.controlMode.hostOnly).assertIsFocused()
        onNodeWithText(T.watch.controlMode.anyone).performClick()
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.Watch(WatchCommand.Start(WatchControlMode.ANYONE))), commands) }
    }

    /**
     * A live stream can't start a room: on TV the panel says so, and Close, its one button, takes focus and closes it.
     * The picture is kept as build/screenshots/player-watch-live-tv.png.
     */
    @Test
    fun aLiveStreamsPanelSaysNoRoomAndCloses() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val live = testPlayerWatch().copy(startTitle = T.watch.player.liveNoRoom, startChoices = emptyList())
        player(FormFactor.TV, onCommand = { commands += it }) { testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = live) }
        onNodeWithText(T.watch.player.liveNoRoom).assertExists()
        onNodeWithText(T.nav.close).assertIsFocused()
        keepShot("player-watch-live-tv")
        press(Key.Enter)
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.CloseMenu), commands) }
    }

    /**
     * What the room's player side couldn't do shows over the room panel until dismissed: the host's video the room
     * can't take. The picture is kept as build/screenshots/player-watch-share-failure-tv.png.
     */
    @Test
    fun aShareFailureShowsOverTheRoomUntilDismissed() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val watch = testPlayerWatch(room = testRoom(host = true)).copy(failure = T.watch.failure.share)
        player(FormFactor.TV, onCommand = { commands += it }) { testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = watch) }
        onNodeWithText(T.watch.failure.share).assertExists()
        keepShot("player-watch-share-failure-tv")
        onNodeWithContentDescription(T.nav.dismiss).performClick()
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.Watch(WatchCommand.DismissFailure)), commands) }
    }

    /** Keeps what shows as build/screenshots/[name].png, once the panel has settled. */
    private fun ComposeUiTest.keepShot(name: String) {
        mainClock.advanceTimeBy(1_000)
        val file = File("build/screenshots/$name.png")
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file), "no PNG writer")
    }

    /** Each way to start a room keeps its title on one line: in the desktop's panel, a phone's sheet and TV's side panel. */
    @Test
    fun theStartChoicesTitlesTakeOneLineInEachPanel() {
        val windows = listOf(
            FormFactor.DESKTOP to DpSize(960.dp, 540.dp),
            FormFactor.HANDHELD to DpSize(411.dp, 760.dp),
            FormFactor.HANDHELD to DpSize(900.dp, 411.dp),
            FormFactor.TV to DpSize(960.dp, 540.dp),
        )
        for ((formFactor, size) in windows) runComposeUiTest {
            player(formFactor, size = size) { testPlayerState(menu = PlayerMenu.WATCH_TOGETHER).copy(watch = testPlayerWatch()) }
            for (title in listOf(T.watch.controlMode.hostOnly, T.watch.controlMode.anyone)) {
                val layouts = mutableListOf<TextLayoutResult>()
                onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.single().lineCount, "$title on $formFactor ${size.width} × ${size.height}")
            }
        }
    }

    /** While a guest looks for its file, the search's line shows with the spinner instead of the loading text. */
    @Test
    fun aGuestsFileSearchShowsInPlaceOfTheLoadingText() = runComposeUiTest {
        val matching = T.watch.player.matchingStart("Sam")
        player(FormFactor.HANDHELD) {
            testPlayerState().copy(status = PlaybackStatus.OPENING, loadingText = "Preparing stream…", watch = testPlayerWatch(room = testRoom(host = false), matching = matching))
        }
        onNodeWithText(matching).assertExists()
        onNodeWithText("Preparing stream…").assertDoesNotExist()
    }

    /**
     * A guest without a file of the room's length gets the prompt in place of the spinner: its message, why it matters
     * and all its actions (Play anyway, Choose another stream, Don't play); on TV Play anyway has focus, and each action
     * sends its command.
     */
    @Test
    fun aMismatchPromptsTheGuestInPlaceOfTheSpinner() = runComposeUiTest {
        player(FormFactor.TV, onCommand = { commands += it }) {
            testPlayerState().copy(
                status = PlaybackStatus.OPENING,
                loadingText = "Preparing stream…",
                controlsVisible = false,
                watch = testPlayerWatch(room = testRoom(host = false), prompt = testMismatch),
            )
        }
        onNodeWithText(testMismatch.message).assertExists()
        onNodeWithText(T.watch.player.mismatchDetail).assertExists()
        onNodeWithText("Preparing stream…").assertDoesNotExist()
        onNodeWithText(T.watch.player.playAnyway).assertIsFocused()
        onNodeWithText(T.watch.player.chooseStream).performClick()
        onNodeWithText(T.watch.player.playAnyway).performClick()
        onNodeWithText(T.watch.player.dontPlay).performClick()
        runOnIdle {
            assertEquals<List<PlayerCommand>>(
                listOf(WatchCommand.ChooseStream, WatchCommand.PlayAnyway, WatchCommand.DontPlay).map { PlayerCommand.Watch(it) },
                commands,
            )
        }
    }

    /**
     * A guest of a host-only room has play/pause, the skips and the speed off, says why, and the episode buttons there
     * but off (the store turns them off for anyone but the host); the menus stay. On TV focus starts on the options,
     * as play/pause can't take it.
     */
    @Test
    fun aGuestOfAHostOnlyRoomHasItsPlaybackControlsOff() = runComposeUiTest {
        player(FormFactor.TV) {
            testPlayerState(episodes = EpisodeButtons(previous = false, next = false))
                .copy(watch = testPlayerWatch(room = testRoom(host = false), canControl = false, canChangeEpisode = false))
        }
        onNodeWithText(T.watch.player.hostControls("Sam")).assertExists()
        onNodeWithContentDescription("Pause").assertIsNotEnabled()
        onNodeWithContentDescription(T.nav.player.rewind).assertIsNotEnabled()
        onNodeWithContentDescription(T.nav.player.fastForward).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.menu.speed).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.previousEpisode).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.nextEpisode).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.options.title).assertIsEnabled().assertIsFocused()
    }

    /**
     * A guest whose player paused here alone (a call, its sleep timer) while the room plays on: the line says so in the
     * lock's place, and play/pause is on, taking focus on TV, its Play the way back to the room; the skips and the
     * speed stay off.
     */
    @Test
    fun aGuestPausedHereAloneHasPlayOn() = runComposeUiTest {
        val commands = mutableListOf<PlayerCommand>()
        player(FormFactor.TV, onCommand = { commands += it }) {
            testPlayerState().copy(
                status = PlaybackStatus.PAUSED,
                paused = true,
                playPauseLabel = "Play",
                watch = testPlayerWatch(room = testRoom(host = false), canControl = false, canChangeEpisode = false, pausedHere = true),
            )
        }
        onNodeWithText(T.watch.player.pausedHere).assertExists()
        onNodeWithText(T.watch.player.hostControls("Sam")).assertDoesNotExist()
        onNodeWithContentDescription(T.nav.player.rewind).assertIsNotEnabled()
        onNodeWithContentDescription(T.player.menu.speed).assertIsNotEnabled()
        onNodeWithContentDescription("Play").assertIsEnabled().assertIsFocused().performClick()
        runOnIdle { assertEquals<List<PlayerCommand>>(listOf(PlayerCommand.TogglePause), commands) }
    }

    /**
     * The room's pill ("Waiting for Anna…") and the transient message sit side by side in the top bar's row, the pill
     * first, centred on the player and level with close, 12 dp clear of close, the title's text and the session
     * indicator: on the desktop, a phone in landscape and TV. On a phone upright the row has no room for them: under
     * the top bar, the pill above the message, clear of it all the same. With the controls down they stay put.
     */
    @Test
    fun theMessagesAtTheTopSitInTheTopBarsRowWhereTheyFit() {
        val windows = listOf(
            FormFactor.DESKTOP to DpSize(960.dp, 540.dp),
            FormFactor.HANDHELD to DpSize(900.dp, 411.dp),
            FormFactor.TV to DpSize(960.dp, 540.dp),
            FormFactor.HANDHELD to DpSize(411.dp, 760.dp),
        )
        for ((formFactor, size) in windows) runComposeUiTest {
            val probe = PlayerProbe()
            val watch = testPlayerWatch(room = testRoom(host = true), pill = T.watch.player.waiting("Anna"))
            var state by mutableStateOf(testPlayerState().copy(osd = T.player.osd.zoom("150%"), watch = watch))
            player(formFactor, probe = probe, size = size) { state }
            waitForIdle()
            val where = "$formFactor ${size.width} × ${size.height}"
            val pill = probe.bounds.getValue("player.watch.pill")
            val osd = probe.bounds.getValue("player.osd")
            val close = probe.bounds.getValue("player.back")
            val gap = with(density) { 12.dp.toPx() }
            for ((name, message) in listOf("the pill" to pill, "the message" to osd)) {
                probe.requireClear("$where: $name", message, listOf("player.back", "player.title", "player.watch"), gap)
            }
            if (size.width > size.height) {
                for (message in listOf(pill, osd)) assertEquals(close.center.y, message.center.y, 1f, "$where: $message level with close $close")
                assertTrue(pill.right < osd.left, "$where: the pill $pill beside the message $osd, first")
                assertEquals(with(density) { size.width.toPx() } / 2, (pill.left + osd.right) / 2, 1f, "$where: the two centred")
            } else {
                assertTrue(close.bottom < pill.top && pill.bottom < osd.top, "$where: the pill $pill above the message $osd under close $close")
            }

            state = state.copy(controlsVisible = false)
            waitForIdle()
            assertEquals(pill, probe.bounds.getValue("player.watch.pill"), "$where: the pill with the controls down")
            assertEquals(osd, probe.bounds.getValue("player.osd"), "$where: the message with the controls down")
        }
    }
}
