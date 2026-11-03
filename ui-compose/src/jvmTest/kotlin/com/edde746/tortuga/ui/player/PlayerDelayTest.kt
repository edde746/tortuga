package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals

/** The subtitle and audio delay pages. */
@OptIn(ExperimentalTestApi::class)
class PlayerDelayTest {
    /**
     * A delay's page (a sheet on phones and the desktop, the side panel on TV) names − and + by the way they move the
     * delay, which screen readers say and find them by, and sends the delay's steps. Reset shows at every delay:
     * disabled at zero, so the first step moves nothing on the page, and enabled once the delay moved, where it sends
     * the delay's reset.
     */
    @Test
    fun theDelayPageNamesItsButtonsAndOffersResetAtEveryDelay() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.DESKTOP, FormFactor.TV)) {
            for (subtitles in listOf(true, false)) runComposeUiTest {
                val commands = mutableListOf<PlayerCommand>()
                val delay = testDelay(subtitles)
                var moved by mutableStateOf(false)
                player(formFactor, onCommand = { commands += it }) {
                    testPlayerState(menu = delay.menu).copy(subtitleDelay = testDelay(true, moved), audioDelay = testDelay(false, moved))
                }
                val reset = onNodeWithText(T.player.menu.reset)
                reset.assertIsDisplayed().assertIsNotEnabled()
                onNodeWithContentDescription(delay.decreaseLabel).performClick()
                onNodeWithContentDescription(delay.increaseLabel).performClick()
                moved = true
                reset.assertIsEnabled().performClick()
                runOnIdle { assertEquals(listOf(delay.decrease, delay.increase, delay.reset), commands, "${delay.menu} on $formFactor") }
            }
        }
    }
}
