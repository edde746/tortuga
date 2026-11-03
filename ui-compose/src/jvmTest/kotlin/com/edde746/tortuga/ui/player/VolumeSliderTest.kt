package com.edde746.tortuga.ui.player

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.VolumeState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The desktop control bar's volume slider: a small round thumb on a thin track, set by the pointer and the keys. */
@OptIn(ExperimentalTestApi::class)
class VolumeSliderTest {
    private val commands = mutableListOf<PlayerCommand>()

    /** The thumb is a dot in proportion to the bar, not Material's 44 dp tall handle: 12 dp, 16 dp under the pointer. */
    @Test
    fun theThumbIsASmallDotThatGrowsUnderThePointer() = runComposeUiTest {
        player(FormFactor.DESKTOP) { testPlayerState().copy(volume = sixtyPercent) }
        val slider = slider()
        val resting = thumbHeight(slider)
        assertTrue(resting in 10..12, "the resting thumb is $resting dp tall")
        slider.performMouseInput { moveTo(center) }
        val hovered = thumbHeight(slider)
        assertTrue(hovered in 14..16, "the hovered thumb is $hovered dp tall")
    }

    /**
     * It says the volume to screen readers, and with keyboard focus (the thumb grows a step for it) the arrow keys step
     * it up and down.
     */
    @Test
    fun theArrowKeysStepTheVolume() = runComposeUiTest {
        player(FormFactor.DESKTOP, onCommand = { commands += it }) { testPlayerState().copy(volume = sixtyPercent) }
        val slider = slider().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "60%"))
        val sixty = 60.0
        slider.requestFocus().performKeyInput { pressKey(Key.DirectionRight) }
        runOnIdle { assertTrue(lastVolume() > sixty, "Right set ${lastVolume()}") }
        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        runOnIdle { assertTrue(lastVolume() < sixty, "Left set ${lastVolume()}") }
        slider.assertIsFocused()
        val focused = thumbHeight(slider)
        assertTrue(focused in 14..16, "the focused thumb is $focused dp tall")
    }

    private val sixtyPercent = VolumeState(60, 0.6f, muted = false, label = "60%")

    // The timeline sets its progress too.
    private fun ComposeUiTest.slider() = onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasContentDescription(T.player.volume.title))

    private fun lastVolume() = commands.filterIsInstance<PlayerCommand.SetVolume>().last().percent

    /** The tallest run of the thumb's colour down any column of [slider]: the thumb's drawn height, in dp. */
    private fun ComposeUiTest.thumbHeight(slider: SemanticsNodeInteraction): Int {
        waitForIdle()
        val pixels = slider.captureToImage().toPixelMap()
        var tallest = 0
        for (x in 0 until pixels.width) {
            var run = 0
            for (y in 0 until pixels.height) {
                run = if (pixels[x, y] == TortugaColors.OnVideo) run + 1 else 0
                tallest = maxOf(tallest, run)
            }
        }
        return (tallest / density.density).roundToInt()
    }
}
