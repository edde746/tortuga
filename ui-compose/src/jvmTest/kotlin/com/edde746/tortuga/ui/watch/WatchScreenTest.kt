package com.edde746.tortuga.ui.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.watch.ConfirmAction
import com.edde746.tortuga.presentation.watch.RelayStatusUi
import com.edde746.tortuga.presentation.watch.WatchLobbyUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.TOAST_MILLIS
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.player.PlayerProbe
import com.edde746.tortuga.ui.player.press
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import com.edde746.tortuga.watch.WatchControlMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Watch together screen over states built here: joining (a code or a pasted invite link), recent rooms, the
 * relay's state and setting, and the room (its code and invite, the participants, the host's controls: Make host,
 * Remove, who controls playback, the lock and End; Leave, each question asked first).
 */
@OptIn(ExperimentalTestApi::class)
class WatchScreenTest {
    private val calls = mutableListOf<String>()
    private var canShare = false
    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }

    /**
     * Out of a room the lobby says a room starts in the player and offers no way to start one here; this device's relay
     * opens its editor: an address that isn't a server's stays with why, Save keeps one that is, and "Use the public
     * relays" (offered while another is set) goes back to them.
     */
    @Test
    fun theLobbyStartsNoRoomAndEditsThisDevicesRelay() = runComposeUiTest {
        var state by mutableStateOf(testLobby())
        lobby(FormFactor.HANDHELD) { state }
        onNodeWithText(T.watch.intro).assertExists()
        onNodeWithText(T.watch.controlMode.hostOnly).assertDoesNotExist()
        onNodeWithText(T.watch.relay.hosted).assertExists()
        onNodeWithText(T.watch.relay.title).performScrollTo().performClick()
        onNodeWithText(T.watch.relay.reset).assertDoesNotExist()
        // The editor's field, over the lobby's join field.
        onAllNodes(hasSetTextAction()).onLast().performTextInput("relay")
        onNodeWithText(T.watch.relay.save).performClick()
        onNodeWithText(T.watch.relay.invalid).assertExists()
        onAllNodes(hasSetTextAction()).onLast().performTextInput(".example.org")
        onNodeWithText(T.watch.relay.save).performClick()
        onNodeWithText(T.watch.relay.save).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("setRelay:relay", "setRelay:relay.example.org"), calls) }

        state = testLobby(relaySetting = testRelaySetting(custom = "https://relay.example.org"))
        onNodeWithText("relay.example.org").performScrollTo().performClick()
        onNodeWithText(T.watch.relay.reset).performClick()
        onNodeWithText(T.watch.relay.reset).assertDoesNotExist()
        runOnIdle { assertEquals("resetRelay", calls.last()) }
    }

    /**
     * The join field tells the store what is typed (a pasted invite link too); Join is off until the store says it
     * holds a code or a link, then joins and empties the field, telling the store so (Join off again). What can't be
     * joined says why.
     */
    @Test
    fun theJoinFieldTakesACodeOrALinkAndJoinsOnceItHoldsOne() = runComposeUiTest {
        var state by mutableStateOf(testLobby())
        lobby(FormFactor.DESKTOP, onJoinText = { state = state.copy(joinText = it, canJoin = it.length == 6 || it.startsWith("tortuga://")) }) { state }
        onNodeWithText(T.watch.joinLabel).assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("tortuga://watch?code=K7MQ2X")
        runOnIdle { assertEquals("setJoinText:tortuga://watch?code=K7MQ2X", calls.last()) }
        onNodeWithText(T.watch.joinLabel).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("join", "setJoinText:"), calls.takeLast(2)) }
        onNodeWithText(T.watch.joinLabel).assertIsNotEnabled()

        state = testLobby(joinText = "K7Q", joinError = T.watch.joinInvalid)
        onNodeWithText(T.watch.joinInvalid).assertExists()
    }

    /** Back in the lobby, the join field holds what was typed with the cursor after it: typing goes on at its end. */
    @Test
    fun theJoinFieldComesBackWithTheCursorAfterItsText() = runComposeUiTest {
        lobby(FormFactor.DESKTOP) { testLobby(joinText = "K7MQ") }
        onNode(hasSetTextAction()).performTextInput("2X")
        runOnIdle { assertEquals("setJoinText:K7MQ2X", calls.last()) }
    }

    /**
     * On TV a remote leaves the join field by Up, to Back above it (the field moved its cursor to the text's start
     * instead, where Right only moved it on, as the Fire TV's keyboard put it there), and by Right at the
     * text's end, to Join; within the text Left and Right move the cursor: Left from the end, Right back to it, staying
     * on the field, then Right once more to Join.
     */
    @Test
    fun onTvARemoteLeavesTheJoinFieldUpAndRightAtItsEnd() = runComposeUiTest {
        val probe = PlayerProbe()
        lobby(FormFactor.TV, probe = probe) { testLobby(joinText = "K7MQ2X", canJoin = true) }
        runOnIdle { assertEquals("watch.join.field", probe.focused, "the lobby doesn't open on the join field") }
        press(Key.DirectionUp)
        runOnIdle { assertEquals("header.back", probe.focused, "Up didn't leave the field") }
        press(Key.DirectionDown)
        runOnIdle { assertEquals("watch.join.field", probe.focused, "Down from Back didn't come back to the field") }
        press(Key.DirectionLeft)
        runOnIdle { assertEquals("watch.join.field", probe.focused, "Left within the text left the field") }
        press(Key.DirectionRight)
        runOnIdle { assertEquals("watch.join.field", probe.focused, "Right within the text left the field") }
        press(Key.DirectionRight)
        runOnIdle { assertEquals("watch.join", probe.focused, "Right at the text's end didn't go on to Join") }
    }

    /**
     * The join field and Join are one height and line up, top and bottom, on a phone, a tablet, TV and desktop, with
     * Join off and on.
     */
    @Test
    fun theJoinFieldAndJoinAreOneHeight() {
        val windows = listOf(
            FormFactor.HANDHELD to DpSize(411.dp, 900.dp),
            FormFactor.HANDHELD to DpSize(800.dp, 760.dp),
            FormFactor.TV to DpSize(960.dp, 540.dp),
            FormFactor.DESKTOP to DpSize(960.dp, 540.dp),
        )
        for ((formFactor, size) in windows) {
            for (canJoin in listOf(false, true)) runComposeUiTest {
                val bounds = mutableMapOf<String, Rect>()
                val probe = LayoutProbe { name, rect -> if (rect != null) bounds[name] = rect else bounds.remove(name) }
                lobby(formFactor, size, probe) { testLobby(joinText = if (canJoin) "K7MQ2X" else "", canJoin = canJoin) }
                runOnIdle {
                    val field = bounds.getValue("watch.join.field")
                    val join = bounds.getValue("watch.join")
                    val where = "$formFactor ${size.width}, canJoin $canJoin: field $field, Join $join"
                    assertTrue(field.height >= 40f, where)
                    assertEquals(field.top, join.top, 0.5f, where)
                    assertEquals(field.bottom, join.bottom, 0.5f, where)
                }
            }
        }
    }

    /** A recent room shows its code as people read it and enters again on a press; its Remove forgets it. */
    @Test
    fun aRecentRoomEntersAgainOrIsRemoved() = runComposeUiTest {
        lobby(FormFactor.HANDHELD) { testLobby(recent = listOf(testRecentRoom)) }
        onNodeWithText(T.watch.recentTitle).assertExists()
        onNodeWithText("K7M-Q2X").performScrollTo().performClick()
        onNodeWithContentDescription(T.watch.removeRecentNamed("K7M-Q2X")).performScrollTo().performClick()
        runOnIdle { assertEquals(listOf("enter:K7MQ2X", "remove:K7MQ2X"), calls) }
    }

    /** An unreachable relay says so with Try again; a failure shows until dismissed; busy turns the relay's row off. */
    @Test
    fun theRelayStateAFailureAndWorkInProgressShow() = runComposeUiTest {
        lobby(FormFactor.HANDHELD) {
            testLobby(
                relay = RelayStatusUi(T.watch.relay.unreachable("relay.example.org"), unreachable = true, retryLabel = T.watch.relay.retry),
                busy = T.watch.busy.joining,
                failure = T.watch.failure.roomNotFound,
            )
        }
        onNodeWithText(T.watch.relay.unreachable("relay.example.org")).assertExists()
        onNodeWithText(T.watch.busy.joining).assertExists()
        onNodeWithText(T.watch.relay.title).assertIsNotEnabled()
        onNodeWithText(T.watch.relay.retry).performClick()
        onNodeWithText(T.watch.failure.roomNotFound).assertExists()
        onNodeWithContentDescription(T.nav.dismiss).performClick()
        runOnIdle { assertEquals(listOf("retryRelay", "dismissFailure"), calls) }
    }

    /**
     * In a room the host sees the code with Copy (then "Code copied"), the participants with the host badge and each
     * one's file, Make host on a guest, and End for everyone, which asks first: Cancel keeps the room, End room ends it.
     * The room's latest event shows as a toast until its time has passed, then the store hears it was shown.
     */
    @Test
    fun theHostCopiesTheCodeHandsTheRoomOverAndEndsItAfterAsking() = runComposeUiTest {
        var state by mutableStateOf(testLobby(room = testRoom(host = true), toast = T.watch.toast.joined("Anna")))
        lobby(FormFactor.HANDHELD) { state }
        onNodeWithText("K7M-Q2X").assertExists()
        onNodeWithText(T.watch.room.youHost).assertExists()
        onNodeWithText(T.watch.room.hostBadge).assertExists()
        onNodeWithText(T.watch.status.longer("12 s")).assertExists()
        onNodeWithText(T.watch.toast.joined("Anna")).assertExists()
        // The toast's time passes (it sits over the room's last buttons while it shows); the store then clears it.
        mainClock.advanceTimeBy(TOAST_MILLIS + 500)
        runOnIdle { assertEquals(listOf("toastShown"), calls) }
        state = state.copy(toast = null)
        onNodeWithText(T.watch.room.share).assertDoesNotExist()

        onNodeWithText(T.watch.room.copyCode).performClick()
        onNodeWithText(T.watch.room.codeCopied).assertExists()
        onNodeWithText(T.watch.room.makeHost).performScrollTo().performClick()

        // End's 48 dp hit square reaches into the page's bottom padding, past the content performScrollTo measures the
        // view by, so it would scroll for ever at the end: its label (the unmerged node) scrolls into view instead, here
        // and for the room's other exits below.
        onNodeWithText(T.watch.room.end, useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText(T.watch.room.endTitle).assertExists()
        onNodeWithText(T.watch.room.cancel).performClick()
        onNodeWithText(T.watch.room.endTitle).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("toastShown", "copy:K7M-Q2X", "makeHost:anna"), calls) }

        onNodeWithText(T.watch.room.end, useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText(T.watch.room.endTitle).assertExists()
        // The dialog's End room, over the button that opened it (the same words): the dialog's comes last.
        onAllNodesWithText(T.watch.room.endConfirm).onLast().performClick()
        runOnIdle { assertEquals(listOf("toastShown", "copy:K7M-Q2X", "makeHost:anna", "end"), calls) }
    }

    /** The host's Leave asks first, saying the room goes on with the next participant as host; confirmed, it leaves. */
    @Test
    fun theHostLeavesTheRoomToTheNextHostAfterAsking() = runComposeUiTest {
        lobby(FormFactor.HANDHELD) { testLobby(room = testRoom(host = true)) }
        onNodeWithText(T.watch.room.leave, useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText(T.watch.room.leaveHostTitle).assertExists()
        onNodeWithText(T.watch.room.leaveConfirm).performClick()
        runOnIdle { assertEquals(listOf("leave"), calls) }
    }

    /**
     * On TV an invite to a room on another relay asks first, naming the code and the relay, Cancel focused: Cancel stays
     * out of it, Join joins. The picture is kept as build/screenshots/watch-invite-question-tv.png.
     */
    @Test
    fun anInviteOnAnotherRelayAsksBeforeJoining() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val question = ConfirmAction(T.watch.invite.join, T.watch.invite.title("K7M-Q2X", "relay.example.com"), T.watch.invite.join, T.watch.room.cancel, destructive = false)
        lobby(FormFactor.TV) { testLobby(inviteQuestion = question) }
        onNodeWithText(question.title).assertExists()
        onNodeWithText(T.watch.room.cancel).assertIsFocused()
        mainClock.advanceTimeBy(1_000)
        val shot = File("build/screenshots/watch-invite-question-tv.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
        onNodeWithText(T.watch.room.cancel).performClick()
        // The dialog's Join, over the lobby's own (the same word): the dialog's comes last.
        onAllNodesWithText(T.watch.invite.join).onLast().performClick()
        runOnIdle { assertEquals(listOf("dismissInvite", "joinInvite"), calls) }
    }

    /**
     * On TV an invite that comes in while this device's relay is being edited asks over the editor with Cancel focused,
     * so the remote answers it; once it is answered the editor has focus again.
     */
    @Test
    fun onTvAnInviteThatComesInOverTheRelayEditorTakesFocus() = runComposeUiTest {
        val probe = PlayerProbe()
        val question = ConfirmAction(T.watch.invite.join, T.watch.invite.title("K7M-Q2X", "relay.example.com"), T.watch.invite.join, T.watch.room.cancel, destructive = false)
        var state by mutableStateOf(testLobby())
        lobby(FormFactor.TV, probe = probe) { state }
        onNodeWithText(T.watch.relay.title).performScrollTo().requestFocus()
        press(Key.Enter)
        runOnIdle { assertEquals("watch.relay.field", probe.focused) }

        state = testLobby(inviteQuestion = question)
        runOnIdle { assertEquals("watch.invite.cancel", probe.focused) }
        press(Key.Enter)
        runOnIdle { assertEquals(listOf("dismissInvite"), calls) }

        state = testLobby()
        runOnIdle { assertEquals("watch.relay.field", probe.focused) }
    }

    /** The host's Remove on a guest asks first, naming them: Cancel keeps them, Remove removes them. */
    @Test
    fun theHostRemovesAParticipantAfterAsking() = runComposeUiTest {
        lobby(FormFactor.HANDHELD) { testLobby(room = testRoom(host = true)) }
        onNodeWithContentDescription(T.watch.room.kickNamed("Anna")).performScrollTo().performClick()
        onNodeWithText(T.watch.room.kickTitle("Anna")).assertExists()
        onNodeWithText(T.watch.room.cancel).performClick()
        onNodeWithText(T.watch.room.kickTitle("Anna")).assertDoesNotExist()
        runOnIdle { assertEquals(emptyList<String>(), calls) }

        onNodeWithContentDescription(T.watch.room.kickNamed("Anna")).performScrollTo().performClick()
        onAllNodesWithText(T.watch.room.kickConfirm).onLast().performClick()
        onNodeWithText(T.watch.room.kickTitle("Anna")).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("kick:anna"), calls) }
    }

    /**
     * The host's choice of who controls playback marks the room's; choosing the other sets it, the room's own does
     * nothing. The lock switch shows the room's lock and turns it on, and a locked room says so.
     */
    @Test
    fun theHostChoosesWhoControlsPlaybackAndLocksTheRoom() = runComposeUiTest {
        var state by mutableStateOf(testLobby(room = testRoom(host = true)))
        lobby(FormFactor.HANDHELD) { state }
        onNodeWithText(T.watch.room.controlTitle).assertExists()
        onNodeWithText(T.watch.controlMode.hostOnly).performScrollTo().assertIsSelected().performClick()
        onNodeWithText(T.watch.controlMode.anyone).assertIsNotSelected().performClick()
        onNodeWithText(T.watch.room.lock).performScrollTo().assertIsOff().performClick()
        runOnIdle { assertEquals(listOf("controlMode:ANYONE", "lock:true"), calls) }

        state = testLobby(room = testRoom(host = true, locked = true, mode = WatchControlMode.ANYONE))
        onNodeWithText(T.watch.controlMode.anyone).assertIsSelected()
        onNodeWithText(T.watch.room.anyone).assertExists()
        onNodeWithText(T.watch.room.lock).assertIsOn().performClick()
        runOnIdle { assertEquals("lock:false", calls.last()) }
    }

    /**
     * A guest has Leave and none of the host's controls (End, Make host, Remove, who controls playback, the lock); it
     * sees a locked room say so and each participant's offset. Where the platform has a share sheet, Share sends the
     * invite.
     */
    @Test
    fun aGuestLeavesAndSharesTheInvite() = runComposeUiTest {
        canShare = true
        val room = testRoom(host = false, locked = true)
        lobby(FormFactor.HANDHELD) { testLobby(room = room) }
        onNodeWithText(T.watch.room.hostedBy("Sam")).assertExists()
        onNodeWithText(T.watch.room.lockDetail).assertExists()
        onNodeWithText(testAnnaOffset).assertExists()
        onNodeWithText(T.watch.room.makeHost).assertDoesNotExist()
        onNodeWithContentDescription(T.watch.room.kickNamed("Anna")).assertDoesNotExist()
        onNodeWithText(T.watch.room.controlTitle).assertDoesNotExist()
        onNodeWithText(T.watch.room.lock).assertDoesNotExist()
        onNodeWithText(T.watch.room.end).assertDoesNotExist()
        onNodeWithText(T.watch.room.share).performClick()
        onNodeWithText(T.watch.room.leave, useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithText(T.watch.room.leaveTitle).assertExists()
        onNodeWithText(T.watch.room.leaveConfirm).performClick()
        runOnIdle { assertEquals(listOf("share:${room.shareText}", "leave"), calls) }
    }

    /** On TV focus lands in the page as it opens (the join field), and on Copy once in a room. */
    @Test
    fun onTvFocusLandsOnTheJoinFieldThenOnCopyInTheRoom() = runComposeUiTest {
        val probe = PlayerProbe()
        var state by mutableStateOf(testLobby())
        lobby(FormFactor.TV, probe = probe) { state }
        runOnIdle { assertEquals("watch.join.field", probe.focused) }
        state = testLobby(room = testRoom(host = true))
        onNodeWithText(T.watch.room.copyCode).assertIsFocused()
    }

    /**
     * On TV a remote reaches the host's controls from Copy: Down passes who controls playback and the lock on its way
     * to a participant's Make host, Remove beside it, then Leave and End. End's question opens with Cancel focused, and
     * Back closes it with nothing ended, focus back on End.
     */
    @Test
    fun onTvARemoteReachesTheHostsControlsAndBackClosesAQuestion() = runComposeUiTest {
        val probe = PlayerProbe()
        lobby(FormFactor.TV, probe = probe) { testLobby(room = testRoom(host = true)) }
        onNodeWithText(T.watch.room.copyCode).assertIsFocused()
        val participant = setOf("watch.room.participant.1.makeHost", "watch.room.participant.1.kick")
        val walked = mutableListOf<String?>()
        var presses = 0
        while (runOnIdle { probe.focused } !in participant && presses++ < 12) {
            press(Key.DirectionDown)
            walked += runOnIdle { probe.focused }
        }
        assertTrue(walked.any { it?.startsWith("watch.room.control.") == true }, "Down passed over who controls playback: $walked")
        assertTrue("watch.room.lock" in walked, "Down passed over the lock: $walked")
        if (runOnIdle { probe.focused } == "watch.room.participant.1.kick") press(Key.DirectionLeft)
        runOnIdle { assertEquals("watch.room.participant.1.makeHost", probe.focused, "Down never reached the participant: $walked") }
        press(Key.DirectionRight)
        runOnIdle { assertEquals("watch.room.participant.1.kick", probe.focused) }

        // Leave and End share a row.
        press(Key.DirectionDown)
        if (runOnIdle { probe.focused } == "watch.room.leave") press(Key.DirectionRight)
        runOnIdle { assertEquals("watch.room.end", probe.focused) }
        press(Key.DirectionLeft)
        runOnIdle { assertEquals("watch.room.leave", probe.focused) }
        press(Key.DirectionRight)
        press(Key.Enter)
        onNodeWithText(T.watch.room.endTitle).assertExists()
        runOnIdle { assertEquals("watch.room.end.cancel", probe.focused) }

        runOnIdle { back.backCompleted() }
        onNodeWithText(T.watch.room.endTitle).assertDoesNotExist()
        runOnIdle {
            assertEquals("watch.room.end", probe.focused)
            assertEquals(emptyList<String>(), calls)
        }
    }

    /**
     * On TV Down from the participants (Anna's Remove) into the room's exits lands on Leave, else on the one of Leave
     * and End that had focus last, whatever the labels' widths: End beside Leave (its middle nearer Remove's), with a
     * long End too, or End under a long Leave. Every case's wrong moves are listed.
     */
    @Test
    fun onTvDownIntoTheRoomsExitsLandsOnLeaveElseTheOneFocusedLastWhateverTheLabels() {
        val labels = listOf(
            T.watch.room.leave to T.watch.room.end,
            "Leave" to "End the room for everyone",
            "Leave the room and stop watching with everyone" to "End the room for everyone who is watching",
        )
        val wrong = mutableListOf<String>()
        for ((leave, end) in labels) runComposeUiTest {
            val probe = FocusedProbe()
            val room = testRoom(host = true)
            lobby(FormFactor.TV, probe = probe) { testLobby(room = room.copy(leave = room.leave.copy(label = leave), end = room.end?.copy(label = end))) }
            // Nothing in the exits focused yet, then End, then Leave focused last.
            for ((last, expected) in listOf(null to "watch.room.leave", end to "watch.room.end", leave to "watch.room.leave")) {
                last?.let { onNodeWithText(it).requestFocus() }
                onNodeWithContentDescription(T.watch.room.kickNamed("Anna")).requestFocus()
                press(Key.DirectionDown)
                val after = last?.let { " after \"$it\"" } ?: ""
                if (probe.focused != expected) wrong += "\"$leave\" and \"$end\": Down from Remove$after went to ${probe.focused}, not $expected"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun ComposeUiTest.lobby(
        formFactor: FormFactor,
        size: DpSize = if (formFactor == FormFactor.HANDHELD) DpSize(411.dp, 900.dp) else DpSize(960.dp, 540.dp),
        probe: LayoutProbe? = null,
        onJoinText: (String) -> Unit = {},
        state: () -> WatchLobbyUiState,
    ) = setContent {
        val owner = remember {
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = dispatcher
            }
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(Modifier.size(size)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        OverlayHostBox { WatchScreen(state(), intents(onJoinText), onBack = {}) }
                    }
                }
            }
        }
    }

    /** Records the screen's requests. */
    private fun intents(onJoinText: (String) -> Unit) = WatchIntents(
        setJoinText = { text ->
            calls += "setJoinText:$text"
            onJoinText(text)
        },
        join = { calls += "join" },
        enterRecent = { code, _ -> calls += "enter:$code" },
        removeRecent = { code, _ -> calls += "remove:$code" },
        retryRelay = { calls += "retryRelay" },
        setRelay = { url ->
            calls += "setRelay:$url"
            url.contains('.')
        },
        resetRelay = { calls += "resetRelay" },
        dismissFailure = { calls += "dismissFailure" },
        toastShown = { calls += "toastShown" },
        copyCode = { code -> calls += "copy:$code" },
        canShare = canShare,
        share = { text -> calls += "share:$text" },
        makeHost = { peerId -> calls += "makeHost:$peerId" },
        kick = { peerId -> calls += "kick:$peerId" },
        setControlMode = { mode -> calls += "controlMode:$mode" },
        setLocked = { locked -> calls += "lock:$locked" },
        joinPlayback = { calls += "joinPlayback" },
        leave = { calls += "leave" },
        end = { calls += "end" },
        joinInvite = { calls += "joinInvite" },
        dismissInvite = { calls += "dismissInvite" },
    )
}
