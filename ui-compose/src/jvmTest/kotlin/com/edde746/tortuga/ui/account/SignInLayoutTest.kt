package com.edde746.tortuga.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.account.SignInOptionUi
import com.edde746.tortuga.presentation.account.SignInServerUiState
import com.edde746.tortuga.presentation.account.SignInStep
import com.edde746.tortuga.presentation.account.SignInUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.message
import com.edde746.tortuga.sync.account.signin.SignInMethod
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sign-in screen as the hosted server's answer arrives, whatever it is (as many buttons as the room kept for them,
 * fewer, none, or that the server couldn't be reached): "Link from another device" and "Use your own server" stay where
 * they showed while the options loaded, so a tap, click or the focus aimed at them as it comes lands on them; the room
 * kept for the buttons is no target, then or after. On TV Down lands on the server line's first button and on Check
 * server wherever their labels and the address put them.
 */
@OptIn(ExperimentalTestApi::class)
class SignInLayoutTest {
    @Test
    fun onAPhoneTheLinkAndTheServerLineStayPutAsTheOptionsArrive() = runComposeUiTest {
        assertStillAsTheOptionsArrive(FormFactor.HANDHELD, DpSize(411.dp, 900.dp))
    }

    @Test
    fun onTheDesktopTheLinkAndTheServerLineStayPutAsTheOptionsArrive() = runComposeUiTest {
        assertStillAsTheOptionsArrive(FormFactor.DESKTOP, DpSize(1280.dp, 720.dp))
    }

    @Test
    fun onTvTheLinkAndTheServerLineStayPutAsTheOptionsArrive() = runComposeUiTest {
        assertStillAsTheOptionsArrive(FormFactor.TV, DpSize(960.dp, 540.dp))
    }

    private fun ComposeUiTest.assertStillAsTheOptionsArrive(formFactor: FormFactor, size: DpSize) {
        var state by mutableStateOf(loading)
        setContent {
            TortugaTheme {
                Box(Modifier.size(size)) {
                    AdaptiveLayout(formFactor) { SignInScreen(state, notice = null, intents = intents) }
                }
            }
        }
        val link = onNodeWithText(T.account.signIn.linkDevice).getUnclippedBoundsInRoot()
        val server = onNodeWithText(T.account.signIn.useOwnServer).getUnclippedBoundsInRoot()
        onAllNodes(hasClickAction()).assertCountEquals(2)

        for (answer in answers) {
            state = loading
            waitForIdle()
            state = answer
            val what = "with ${answer.options.map { it.provider }}, note ${answer.optionsNote}"
            answer.options.forEach { onNodeWithText(it.label).getUnclippedBoundsInRoot() }
            answer.optionsNote?.let { onNodeWithText(it).getUnclippedBoundsInRoot() }
            assertEquals(link, onNodeWithText(T.account.signIn.linkDevice).getUnclippedBoundsInRoot(), "Link from another device $what")
            assertEquals(server, onNodeWithText(T.account.signIn.useOwnServer).getUnclippedBoundsInRoot(), "Use your own server $what")
            onAllNodes(hasClickAction()).assertCountEquals(answer.options.size + (if (answer.canRetry) 1 else 0) + 2)
        }
    }

    /**
     * On TV Down from "Link from another device" lands on "Use your own server", wherever the address leaves the line's
     * buttons: beside a short one, or under a long one, where "Use the hosted server" comes nearer the middle.
     */
    @Test
    fun onTvDownLandsOnTheServerLinesFirstButtonWhereverTheAddressPutsIt() {
        val wrong = mutableListOf<String>()
        for (address in listOf("my.server", "sync.tortuga.example.com", "https://a-long-self-hosted-tortuga-sync-server.example.com:8443")) runComposeUiTest {
            val probe = FocusedProbe()
            tvSignIn(probe, answers.first().copy(server = loading.server.copy(text = address, custom = true)))
            onNodeWithText(T.account.signIn.linkDevice).requestFocus()
            press(Key.DirectionDown)
            if (probe.focused != "signIn.server.edit") wrong += "$address: Down from Link from another device went to ${probe.focused}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On TV Down from the server's address field lands on Check server, not on Cancel beside it (nearer the field's
     * middle), and on Cancel while there is no address to check.
     */
    @Test
    fun onTvDownFromTheServerFieldLandsOnCheckServer() {
        val wrong = mutableListOf<String>()
        for ((field, expected) in listOf("https://my.server" to "signIn.server.submit", "" to "signIn.server.cancel")) runComposeUiTest {
            val probe = FocusedProbe()
            tvSignIn(probe, answers.first().copy(server = loading.server.copy(editing = true, field = field)))
            if (probe.focused != "signIn.server.field") wrong += "\"$field\": the field doesn't take focus (${probe.focused})"
            press(Key.DirectionDown)
            if (probe.focused != expected) wrong += "\"$field\": Down from the field went to ${probe.focused}, not $expected"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The sign-in screen of [state] on a 1080p TV (960 × 540 dp), the arrow keys as the D-pad. */
    private fun ComposeUiTest.tvSignIn(probe: FocusedProbe, state: SignInUiState) {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { SignInScreen(state, notice = null, intents = intents) }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private val loading = SignInUiState(
        step = SignInStep.Choose(error = null),
        server = SignInServerUiState("Server", "api.usetortuga.app", custom = false, editing = false, field = "", checking = false, error = null),
        options = emptyList(),
        optionsLoading = true,
        optionsRoom = 3,
        optionsNote = null,
        canRetry = false,
    )

    private val hostedOptions = listOf(
        SignInOptionUi("apple", SignInMethod.APPLE_WEB, T.account.signIn.apple),
        SignInOptionUi("google", SignInMethod.BROWSER, T.account.signIn.google),
        SignInOptionUi("github", SignInMethod.GITHUB_WEB, T.account.signIn.github),
    )

    /** Every count of buttons from the room's down to one, none this device can use, and the server out of reach. */
    private val answers = (loading.optionsRoom downTo 1).map { loading.copy(options = hostedOptions.take(it), optionsLoading = false) } +
        loading.copy(optionsLoading = false, optionsNote = T.account.signIn.noOptions) +
        loading.copy(optionsLoading = false, optionsNote = AccountError.Network.message, canRetry = true)

    private val intents = SignInIntents(
        signIn = {}, cancel = {}, retry = {}, editServer = {}, setServerField = {}, submitServer = {},
        cancelServerEdit = {}, useHostedServer = {}, linkDevice = {}, restartLink = {}, dismissNotice = {},
    )
}
