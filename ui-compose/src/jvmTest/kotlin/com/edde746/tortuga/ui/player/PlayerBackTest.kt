package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.model.FormFactor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One Back acts once on TV, whichever layers its press and its release reach: a Back pressed as a menu opens reaches
 * the player, whose controls still have focus, while its release reaches the menu's sheet.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerBackTest {
    private val commands = mutableListOf<PlayerCommand>()
    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }

    /**
     * From Android 13 the release comes as the system's back event, which the sheet takes (the menu closes), and then
     * as the key-up, to the player once focus is back on its controls: the key-up does nothing, the controls stay up.
     */
    @Test
    fun aBackPressedAsAMenuOpensAndReleasedBySystemBackClosesTheMenuAlone() = runComposeUiTest {
        var state by mutableStateOf(testPlayerState())
        player(FormFactor.TV, dispatcher, onCommand = { commands += it }) { state }
        onRoot().performKeyInput { keyDown(Key.Back) }
        state = state.copy(menu = PlayerMenu.SUBTITLES)
        waitForIdle()

        runOnIdle { back.backCompleted() }
        state = state.copy(menu = null)
        waitForIdle()
        onRoot().performKeyInput { keyUp(Key.Back) }
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.CloseMenu), commands) }
    }

    /** Before Android 13 the release is the key-up alone, to the sheet that has taken focus: it closes the menu. */
    @Test
    fun aBackPressedAsAMenuOpensAndReleasedInItsSheetClosesTheMenu() = runComposeUiTest {
        var state by mutableStateOf(testPlayerState())
        player(FormFactor.TV, dispatcher, onCommand = { commands += it }) { state }
        onRoot().performKeyInput { keyDown(Key.Back) }
        state = state.copy(menu = PlayerMenu.SUBTITLES)
        waitForIdle()

        onRoot().performKeyInput { keyUp(Key.Back) }
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.CloseMenu), commands) }
    }
}
