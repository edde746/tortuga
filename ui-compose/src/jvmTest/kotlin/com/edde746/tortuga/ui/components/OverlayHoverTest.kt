package com.edde746.tortuga.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.FocusVisibility
import com.edde746.tortuga.ui.focus.LocalFocusVisibility
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Over a picture or the video a control that rests transparent or translucent (an icon button, the player's buttons,
 * the watch-together indicator) stays see-through under the pointer and pressed: a translucent tint over the picture,
 * never a solid surface. Over the black page that tint is the grey a hovered transparent control always had; a filled
 * control keeps its solid step. A button over a picture (Home's header) lets the picture through at rest, its label
 * readable, and over the black page shows the grey a filled button has.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayHoverTest {
    /** A bright frame. */
    private val picture = Color(0xFFE0D8C8)
    private val translucent = Color.Black.copy(alpha = 0.45f)

    @Test
    fun aTransparentControlOverAPictureLetsItThroughHoveredAndPressed() = runComposeUiTest {
        buttons(picture)
        assertFill("plain", picture, "at rest")
        hover("plain")
        assertFill("plain", TortugaColors.HoverTint.compositeOver(picture), "hovered")
        onNodeWithTag("plain").performMouseInput { press() }
        assertFill("plain", TortugaColors.HoverTint.compositeOver(picture), "pressed")
        onNodeWithTag("plain").performMouseInput { release() }

        hover("translucent")
        assertFill("translucent", TortugaColors.HoverTint.compositeOver(translucent).compositeOver(picture), "translucent, hovered")
    }

    @Test
    fun aButtonOverAPictureLetsItThroughAtRestAndKeepsItsLabelReadable() = runComposeUiTest {
        buttons(picture)
        val rest = fill("pill")
        assertTrue(rest.luminance() > TortugaColors.SurfaceHigh.luminance() * 4, "the pill at rest hides the picture: $rest")
        val contrast = (TortugaColors.Text.luminance() + 0.05f) / (rest.luminance() + 0.05f)
        assertTrue(contrast >= 3f, "the pill's label over a bright picture: $contrast:1")
    }

    @Test
    fun overThePageTheTintIsTheGreyItWasAndAFilledButtonKeepsItsStep() = runComposeUiTest {
        buttons(TortugaColors.Background)
        hover("plain")
        assertFill("plain", TortugaColors.SurfaceHigh, "hovered over the page")
        hover("filled")
        assertFill("filled", TortugaColors.SurfaceHighest, "a filled button, hovered")
        assertFill("pill", TortugaColors.SurfaceHigh, "a button over a picture, at rest over the page")
    }

    private fun ComposeUiTest.hover(tag: String) {
        onNodeWithTag(tag).performMouseInput { moveTo(center) }
        waitForIdle()
    }

    /** The control's fill 15 % down its middle: inside the circle, clear of the icon. */
    private fun ComposeUiTest.fill(tag: String): Color {
        waitForIdle()
        val bounds = onNodeWithTag(tag).getBoundsInRoot()
        val pixels = onRoot().captureToImage().toPixelMap()
        return with(density) {
            pixels[((bounds.left + bounds.right) / 2).toPx().roundToInt(), (bounds.top + (bounds.bottom - bounds.top) * 0.15f).toPx().roundToInt()]
        }
    }

    private fun ComposeUiTest.assertFill(tag: String, expected: Color, what: String) {
        val actual = fill(tag)
        val off = listOf(actual.red - expected.red, actual.green - expected.green, actual.blue - expected.blue).maxOf { abs(it) }
        assertTrue(off <= 2f / 255, "$tag $what: $actual, expected $expected")
    }

    /** On [under], as the desktop app with the mouse in hand: focus hidden (a press may focus a control). */
    private fun ComposeUiTest.buttons(under: Color) = setContent {
        TortugaTheme {
            CompositionLocalProvider(LocalFocusVisibility provides FocusVisibility(false)) {
                Box(Modifier.size(640.dp, 360.dp).background(under)) {
                    AdaptiveLayout(FormFactor.DESKTOP) {
                        Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            TortugaIconButton(TortugaIcons.ArrowBack, "Back", {}, Modifier.testTag("plain"), style = IconButtonStyle.PLAIN)
                            TortugaIconButton(TortugaIcons.Share, "Share", {}, Modifier.testTag("filled"))
                            TortugaSurface({}, Modifier.size(48.dp).testTag("translucent"), shape = CircleShape, color = translucent) {}
                            TortugaButton("Downloads", {}, Modifier.testTag("pill"), style = ButtonStyle.OVER_PICTURE, icon = TortugaIcons.Download)
                        }
                    }
                }
            }
        }
    }
}
