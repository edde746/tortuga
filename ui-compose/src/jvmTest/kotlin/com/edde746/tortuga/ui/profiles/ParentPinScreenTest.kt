package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.ParentPinUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The parent's PIN asked over a kids profile: what it unlocks, typed or pressed digits, Backspace and Cancel. */
@OptIn(ExperimentalTestApi::class)
class ParentPinScreenTest {
    private val calls = mutableListOf<String>()

    private val state = ParentPinUiState(
        title = T.kids.pin.title,
        subtitle = T.kids.pin.allow("Paddington"),
        entered = 1,
        length = 4,
        error = T.profiles.pin.wrong,
        acceptsInput = true,
    )

    /**
     * On a phone, a TV and a desktop window: the digit keys of a keyboard or remote type digits, as do the pad's keys;
     * Backspace (the key and the button) takes one back; Cancel cancels the check.
     */
    @Test
    fun digitsTypedOrPressedEnterThePinAndCancelCancels() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.TV, FormFactor.DESKTOP)) {
            calls.clear()
            runComposeUiTest {
                pad(formFactor)
                onRoot().performKeyInput { pressKey(Key.Two) }
                onRoot().performKeyInput { pressKey(Key.NumPad7) }
                onRoot().performKeyInput { pressKey(Key.Backspace) }
                onNodeWithText("5").performClick()
                onNodeWithContentDescription(T.profiles.pin.backspace).performClick()
                onNodeWithText(T.profiles.pin.cancel).performClick()
                runOnIdle { assertEquals(listOf("digit:2", "digit:7", "backspace", "digit:5", "backspace", "cancel"), calls, "$formFactor") }
            }
        }
    }

    /**
     * A TV's screen (960 × 540 dp) holds the whole prompt at once: whose PIN it asks for, what it unlocks, the wrong
     * PIN's line, every key and Cancel.
     */
    @Test
    fun aTvShowsTheWholeParentPromptOnOneScreen() = runComposeUiTest {
        pad(FormFactor.TV)
        val parts = listOf(T.kids.pin.title, T.kids.pin.allow("Paddington"), T.profiles.pin.wrong, "1", "0", T.profiles.pin.cancel)
        for (text in parts) {
            val bounds = onNodeWithText(text).getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= 0.dp && bounds.bottom <= 540.dp, "$text at $bounds is off the 540 dp screen")
        }
    }

    private fun ComposeUiTest.pad(formFactor: FormFactor) {
        setContent {
            TortugaTheme {
                val size = when (formFactor) {
                    FormFactor.TV -> 960 to 540
                    FormFactor.DESKTOP -> 1280 to 720
                    else -> 411 to 900
                }
                Box(Modifier.size(size.first.dp, size.second.dp)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        ParentPinScreen(
                            state,
                            onDigit = { calls += "digit:$it" },
                            onBackspace = { calls += "backspace" },
                            onCancel = { calls += "cancel" },
                        )
                    }
                }
            }
        }
        waitForIdle()
    }
}
