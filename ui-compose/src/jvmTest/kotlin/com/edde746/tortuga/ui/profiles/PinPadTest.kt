package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.ProfileAvatar
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PinPadTest {
    /**
     * A TV's screen (960 × 540 dp) holds the whole PIN prompt at once: whose PIN it asks for, every key and Cancel,
     * with nothing scrolled out of view.
     */
    @Test
    fun aTvShowsTheWholePinPromptOnOneScreen() = runComposeUiTest {
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV) {
                        PinPad(
                            title = "Enter the PIN for Alice",
                            entered = 2,
                            length = 4,
                            enteredText = "2 of 4 digits entered",
                            message = null,
                            acceptsInput = true,
                            onDigit = {},
                            onBackspace = {},
                            onCancel = {},
                            header = { ProfileAvatarTile("A", ProfileAvatar("slate", "Slate", 0xFF3A4048, 0xFFEDEDED), 72.dp) },
                        )
                    }
                }
            }
        }
        for (node in listOf(onNodeWithText("Enter the PIN for Alice"), onNodeWithText("1"), onNodeWithText("0"), onNodeWithText(T.profiles.pin.cancel))) {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= 0.dp && bounds.bottom <= 540.dp, "$bounds is off the 540 dp screen")
        }
    }

    /**
     * TalkBack speaks a wrong PIN as it shows, wherever its focus is, and a lockout as it starts, but not the lockout's
     * countdown as it ticks to the next second.
     */
    @Test
    fun aWrongPinAndALockoutAreSpokenButNotTheCountdownsTicks() = runComposeUiTest {
        var message by mutableStateOf<String?>(null)
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.HANDHELD) {
                    PinPad("Enter the PIN for Alice", entered = 0, length = 4, enteredText = "0 of 4 digits entered", message = message, acceptsInput = true, onDigit = {}, onBackspace = {}, onCancel = {})
                }
            }
        }
        message = T.profiles.pin.wrong
        onNodeWithText(T.profiles.pin.wrong).assert(spoken)
        // The next digit clears it; the fourth one, wrong again, locks the pad.
        message = null
        onNodeWithText(T.profiles.pin.wrong).assertDoesNotExist()
        message = T.profiles.pin.lockedOut("0:30")
        onNodeWithText(T.profiles.pin.lockedOut("0:30")).assert(spoken)
        message = T.profiles.pin.lockedOut("0:29")
        onNodeWithText(T.profiles.pin.lockedOut("0:29")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    private val spoken = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    /**
     * On TV Right from Cancel goes back to the key Left reached Cancel from (1, which has focus as the pad opens, or 4),
     * whatever the title's and the message's wrap (a title of one, two or three lines; no message, one line or two): not
     * to the key level with Cancel, which they decide.
     */
    @Test
    fun onTvRightFromCancelLandsOnTheKeyItWasLeftFromWhateverTheTitleWraps() {
        val cases = listOf(
            T.profiles.pin.title("Al") to null,
            T.profiles.pin.title("Maximiliane-Charlotte von Hohenberg") to T.profiles.pin.wrong,
            T.profiles.pin.title("Maximiliane-Charlotte Josephine von Hohenberg-Lichtenstein") to T.profiles.pin.lockedOut("14 minutes and 59 seconds"),
        )
        val wrong = mutableListOf<String>()
        for ((title, message) in cases) runComposeUiTest {
            val probe = FocusedProbe()
            setContent {
                CompositionLocalProvider(LocalLayoutProbe provides probe) {
                    TortugaTheme {
                        Box(Modifier.size(960.dp, 540.dp)) {
                            AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                                PinPad(
                                    title = title,
                                    entered = 0,
                                    length = 4,
                                    enteredText = "0 of 4 digits entered",
                                    message = message,
                                    acceptsInput = true,
                                    onDigit = {},
                                    onBackspace = {},
                                    onCancel = {},
                                    header = { ProfileAvatarTile("M", ProfileAvatar("slate", "Slate", 0xFF3A4048, 0xFFEDEDED), 72.dp) },
                                )
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val case = "\"$title\", ${message?.let { "\"$it\"" } ?: "no message"}"
            if (probe.focused != "pin.digit.1") wrong += "$case: the pad opens with focus on ${probe.focused}, not 1"
            for (digit in listOf(1, 4)) {
                onNodeWithText("$digit").requestFocus()
                press(Key.DirectionLeft)
                if (probe.focused != "pin.cancel") {
                    wrong += "$case: Left from $digit went to ${probe.focused}, not ${T.profiles.pin.cancel}"
                    continue
                }
                press(Key.DirectionRight)
                if (probe.focused != "pin.digit.$digit") wrong += "$case: Right from ${T.profiles.pin.cancel}, after Left from $digit, went to ${probe.focused}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }
}
