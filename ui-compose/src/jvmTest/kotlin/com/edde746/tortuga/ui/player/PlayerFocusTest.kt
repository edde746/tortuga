package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.EpisodeButtons
import com.edde746.tortuga.presentation.player.ErrorAction
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlayerFocusTest {
    private val error = testError(alternatives = "3 more streams available")
    private val errorVideo = testErrorVideo

    /**
     * A stream that fails at once (a 404) opens the player with its error already there: on TV the error's first
     * action has focus, so the remote can act on it, rather than the player behind the panel.
     */
    @Test
    fun onTvAnErrorThereAsThePlayerOpensHasFocus() = runComposeUiTest {
        tvPlayer { testPlayerState(error = error) }
        onNodeWithText(T.player.error.retry).assertIsFocused()
    }

    /**
     * An error that comes while the TV controls have focus takes it; once a retry has failed too and the next stream
     * becomes the primary action, focus moves to it.
     */
    @Test
    fun onTvAnErrorTakesFocusFromTheControlsAndFollowsItsPrimaryAction() = runComposeUiTest {
        var state by mutableStateOf(testPlayerState())
        tvPlayer { state }
        state = state.copy(error = error)
        onNodeWithText(T.player.error.retry).assertIsFocused()
        state = state.copy(error = testError(primary = ErrorAction.NEXT, alternatives = "3 more streams available"))
        onNodeWithText(T.player.error.nextStream).assertIsFocused()
    }

    /**
     * An error video caught while it plays (its length gave it away) takes focus from the controls to Choose another
     * stream, so the remote's first Select asks for another stream.
     */
    @Test
    fun onTvAnErrorVideoFocusesChooseAnotherStreamAndSelectAsksForOne() = runComposeUiTest {
        val commands = mutableListOf<PlayerCommand>()
        var state by mutableStateOf(testPlayerState())
        player(FormFactor.TV, onCommand = { commands += it }) { state }
        state = state.copy(error = errorVideo)
        onNodeWithText(T.player.errorVideo.chooseStream).assertIsFocused()
        press(Key.Enter)
        runOnIdle { assertEquals(listOf<PlayerCommand>(PlayerCommand.ChooseStream), commands) }
    }

    /**
     * Previous on the second episode plays the first, which has none before it: the focused button turns off. Focus
     * goes to play/pause, so the remote still moves along the controls; it was left nowhere.
     */
    @Test
    fun onTvTheFocusedPreviousTurningOffSendsFocusToPlayPause() = runComposeUiTest {
        val probe = PlayerProbe()
        var state by mutableStateOf(testPlayerState())
        tvPlayer(probe) { state }
        assertEquals("player.playPause", probe.focused)
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals("player.previous", probe.focused)
        state = state.copy(episodes = EpisodeButtons(previous = false, next = true))
        waitForIdle()
        assertEquals("player.playPause", probe.focused)
    }

    /**
     * With the controls up and the player itself holding focus (where Android puts focus that dropped), a D-pad key
     * takes focus to play/pause; the player has no neighbour to move to, so the key would do nothing.
     */
    @Test
    fun onTvAKeyWithTheControlsUpAndNoneFocusedFocusesPlayPause() = runComposeUiTest {
        val probe = PlayerProbe()
        tvPlayer(probe) { testPlayerState() }
        onNode(playerItself, useUnmergedTree = true).requestFocus().assertIsFocused()
        assertEquals(null, probe.focused)
        press(Key.DirectionUp)
        assertEquals("player.playPause", probe.focused)
    }

    /** The player's own focus target: the outermost one, around the video and every control. */
    private val playerItself = SemanticsMatcher("the player itself") { node ->
        SemanticsActions.RequestFocus in node.config && generateSequence(node.parent) { it.parent }.none { SemanticsActions.RequestFocus in it.config }
    }
}
