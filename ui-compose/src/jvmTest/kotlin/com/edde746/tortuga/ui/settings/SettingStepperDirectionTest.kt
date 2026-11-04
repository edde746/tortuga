package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.SettingStepperRow
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In Arabic, laid out right to left, a stepper's + is on the left of its value and − on the right, and the arrows step
 * toward them: on the focused value Left steps up and Right down; − and + step down and up whatever the direction.
 */
@OptIn(ExperimentalTestApi::class)
class SettingStepperDirectionTest {
    @Test
    fun theArrowsStepTowardPlusAndMinusInArabic() {
        try {
            T.locale = "ar"
            runComposeUiTest {
                val steps = mutableListOf<Boolean>()
                val title = "Seek forward"
                val row = SettingStepperRow(
                    title, null, 0, "10", min = 5, max = 30, step = 5, canDecrease = true, canIncrease = true,
                    decreaseLabel = T.settings.decreaseNamed(title), increaseLabel = T.settings.increaseNamed(title),
                )
                setContent {
                    val direction = if (InterfaceLocale.isRightToLeft(T.locale)) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        TortugaTheme {
                            Box(Modifier.size(411.dp, 200.dp)) {
                                AdaptiveLayout(FormFactor.HANDHELD) { SettingStepperItem(row, { steps += it }, "stepper", RectangleShape) }
                            }
                        }
                    }
                }
                val value = onNode(hasContentDescription(title) and hasStateDescription("10"))
                value.requestFocus()
                value.performKeyInput {
                    pressKey(Key.DirectionLeft)
                    pressKey(Key.DirectionRight)
                    pressKey(Key.Minus)
                    pressKey(Key.Plus)
                }
                waitForIdle()
                assertEquals(listOf(true, false, false, true), steps, "Left, Right, − and + on the value in Arabic (true: up)")
            }
        } finally {
            T.locale = "en"
        }
    }
}
