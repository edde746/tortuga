package com.edde746.tortuga.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.account.DeviceCodeUiState
import com.edde746.tortuga.presentation.account.DeviceLinkUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** A TV's screen is 960 × 540 dp on a 1080p panel and on a 720p one alike. */
@OptIn(ExperimentalTestApi::class)
class CodePageTest {
    /**
     * The link page on a TV holds everything at once: the code, its QR code, the countdown, the status line and the
     * way back, nothing scrolled out of view.
     */
    @Test
    fun aTvShowsTheWholeLinkPageOnOneScreen() = runComposeUiTest {
        val link = DeviceLinkUiState.Waiting(
            code = "7ZSN-NHKR",
            qrPayload = "tortuga://link?code=7ZSN-NHKR&server=https%3A%2F%2Fsync.example.com",
            expiresText = "The code expires in 9:54.",
            progress = "Waiting for approval on the other device…",
        )
        onTv { DeviceLinkPage(link, onRestart = {}, onBack = {}, backProbe = "signIn.back") }
        assertOnScreen(
            onNodeWithContentDescription(T.nav.back),
            onNodeWithText(T.account.link.title),
            onNodeWithText("7ZSN-NHKR"),
            onNodeWithContentDescription(T.account.link.qrDescription),
            onNodeWithText("The code expires in 9:54."),
            onNodeWithText("Waiting for approval on the other device…"),
        )
    }

    /**
     * The device-flow sign-in on a TV: the address, the code, its QR code, the countdown and the progress, with the
     * header's back button as the one way out (no Cancel beside it).
     */
    @Test
    fun aTvShowsTheWholeSignInCodePageOnOneScreen() = runComposeUiTest {
        val code = DeviceCodeUiState(
            userCode = "MBHB-JLFN",
            verificationUrl = "http://127.0.0.1:5556/dex/device",
            qrPayload = "http://127.0.0.1:5556/dex/device?user_code=MBHB-JLFN",
            instructions = "On a phone or computer, go to 127.0.0.1:5556/dex/device and enter this code:",
            scanHint = "Or scan the QR code with your phone.",
            expiresText = "The code expires in 4:54.",
        )
        onTv { DeviceCodePage(T.account.signIn.title, null, code, "Waiting for you to sign in…", onCancel = {}, backProbe = "signIn.back") }
        assertOnScreen(
            onNodeWithContentDescription(T.nav.back),
            onNodeWithText(code.instructions),
            onNodeWithText("MBHB-JLFN"),
            onNodeWithContentDescription(T.account.signIn.deviceCode.qrDescription),
            onNodeWithText(code.scanHint),
            onNodeWithText("The code expires in 4:54."),
            onNodeWithText("Waiting for you to sign in…"),
        )
        onNodeWithText(T.account.signIn.cancel).assertDoesNotExist()
    }

    private fun ComposeUiTest.onTv(content: @Composable () -> Unit) = setContent {
        TortugaTheme {
            Box(Modifier.size(960.dp, 540.dp)) {
                AdaptiveLayout(FormFactor.TV) { content() }
            }
        }
    }

    private fun assertOnScreen(vararg nodes: SemanticsNodeInteraction) {
        for (node in nodes) {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= 0.dp && bounds.right <= 960.dp && bounds.top >= 0.dp && bounds.bottom <= 540.dp, "$bounds is off the 960 × 540 dp screen")
        }
    }
}
