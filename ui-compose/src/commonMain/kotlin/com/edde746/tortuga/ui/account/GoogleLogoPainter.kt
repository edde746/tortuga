package com.edde746.tortuga.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.toPath
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.ui.icons.pathNodes
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A sign-in provider's mark, drawn in its own colours on its buttons and its linked sign-in's settings row: Google's
 * G. Apple's logo and GitHub's mark are icons the button tints instead ([providerIcon]).
 */
@Composable
internal fun providerLogo(provider: String): Painter? =
    if (provider == IdentityProvider.GOOGLE) remember { GoogleLogoPainter() } else null

/**
 * Google's G: the gradient mark of Google's sign-in branding kit (`signin-assets.zip`, 2025), drawn from the kit's SVG.
 * A conic gradient and six ellipses of colour lie inside the G's outline. The kit blurs them by one unit, which a
 * vector can't do on every Android version; here each ellipse's edge fades along that blur's profile instead and the
 * gradient stays sharp, which draws the same G within a level or two of the kit's PNGs. The Apple app's `GoogleLogo`
 * draws it from the same numbers. It fills the largest square that fits.
 */
internal class GoogleLogoPainter : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val factor = min(size.width, size.height) / SIDE
        drawContext.canvas.saveLayer(size.toRect(), Paint())
        withTransform({
            translate((size.width - SIDE * factor) / 2, (size.height - SIDE * factor) / 2)
            scale(factor, factor, Offset.Zero)
            translate(-12f, -12f)
        }) {
            // The G's coverage first; each layer then paints only over it (SrcAtop), which keeps the G's smooth edge.
            drawPath(Outline, Color.Black)
            rotate(-45f, SweepCenter) {
                drawRect(Sweep, size = Size(44f, 44f), blendMode = BlendMode.SrcAtop)
            }
            for (glow in Glows) {
                withTransform({
                    translate(glow.x, glow.y)
                    rotate(glow.rotation, Offset.Zero)
                    scale(glow.rx, glow.ry, Offset.Zero)
                }) {
                    drawCircle(glow.brush, radius = glow.reach, center = Offset.Zero, blendMode = BlendMode.SrcAtop)
                }
            }
        }
        drawContext.canvas.restore()
    }
}

/** The G's square, in the kit's units. */
private const val SIDE = 20f

private val Outline = pathNodes(
    "M31.3987 20.1814H21.9849V24.0445H27.3598C27.1286 25.294 26.4294 26.3596 25.3676 27.0712C24.4746 27.6716 " +
        "23.3266 28.0211 21.9849 28.0211C19.3864 28.0211 17.1823 26.2666 16.3947 23.9004C16.1952 23.2989 16.0853 " +
        "22.6599 16.0853 21.9983C16.0853 21.3367 16.1952 20.6966 16.3947 20.0962C17.1823 17.7311 19.3864 15.9755 " +
        "21.9849 15.9755C23.4524 15.9755 24.767 16.4816 25.8039 17.4713L28.6653 14.6057C26.936 12.9908 24.6786 12 " +
        "21.9849 12C18.0832 12 14.705 14.2414 13.0618 17.5076C12.383 18.8592 12 20.3834 12 21.9994C12 23.6155 " +
        "12.383 25.1396 13.0618 26.4913C14.705 29.7597 18.0832 32 21.9849 32C24.6797 32 26.9485 31.1137 28.6018 " +
        "29.5861C30.4887 27.8452 31.5732 25.2702 31.5732 22.2275C31.5732 21.5182 31.5131 20.835 31.3987 " +
        "20.1825V20.1814Z",
).toPath()

/**
 * The conic gradient about [SweepCenter]: (degrees clockwise from the start, up and to the right of the centre;
 * colour). A sweep starts to the right, so the painter turns it back 45°.
 */
private val SweepCenter = Offset(21.6819f, 21.7927f)
private val Sweep = Brush.sweepGradient(
    *listOf(
        0f to 0xFF4641, 4.14555f to 0xFF4641, 39.154f to 0x3186FF, 72.0044f to 0x3186FF, 96.7463f to 0x00A5B7,
        120.897f to 0x0EBC5F, 154.722f to 0x0EBC5F, 179.136f to 0x6CC400, 203.588f to 0xFFCC00, 226.915f to 0xFFD314,
        251.688f to 0xFFCC00, 273.129f to 0xFF6A2B, 289.305f to 0xFD4641, 359.593f to 0xFF4641, 360f to 0xFF4641,
    ).map { (degrees, rgb) -> degrees / 360f to rgbColor(rgb) }.toTypedArray(),
    center = SweepCenter,
)

/** A one-unit Gaussian blur's edge: how much of a shape still covers a point `t` blur widths outside it. */
private val Edge = listOf(
    -3f to 0.99865f, -2.25f to 0.98778f, -1.5f to 0.93319f, -0.75f to 0.77337f, 0f to 0.5f,
    0.75f to 0.22663f, 1.5f to 0.06681f, 2.25f to 0.01222f, 3f to 0.00135f,
)

/**
 * An ellipse of colour, drawn as a unit circle stretched to its radii and turned ([rotation], degrees clockwise) about
 * its centre. Its edge fades as the kit's one-unit blur fades it: over (−3, 3) blur widths, the width taken over the
 * ellipse's mean radius.
 */
private class Glow(val x: Float, val y: Float, val rx: Float, val ry: Float, val rotation: Float = 0f, rgb: Int) {
    private val width = 1f / sqrt(rx * ry)

    /** How far past the unit circle the fade reaches. */
    val reach = 1f + 3f * width
    val brush = rgbColor(rgb).let { color ->
        Brush.radialGradient(
            *(listOf(0f to color) + Edge.map { (t, cover) -> (1f + t * width) / reach to color.copy(alpha = cover) })
                .toTypedArray(),
            center = Offset.Zero,
            radius = reach,
        )
    }
}

private val Glows = listOf(
    Glow(22.0496f, 22.2413f, 5.39634f, 2.83537f, rotation = 24.4473f, rgb = 0x3186FF),
    Glow(35.3538f, 20.2155f, 7.43918f, 3.09357f, rgb = 0x3186FF),
    Glow(27.2744f, 18.2195f, 7.40854f, 2.37805f, rgb = 0xFF4641),
    Glow(31.5427f, 14.9268f, 7.40854f, 2.37805f, rgb = 0xFF5B8B),
    Glow(26.4817f, 21.878f, 8.5061f, 3.10976f, rgb = 0x3186FF),
    Glow(27.1842f, 16.0197f, 4.53882f, 2.37805f, rotation = -28.6599f, rgb = 0xFF4641),
)

private fun rgbColor(rgb: Int) = Color(0xFF000000.toInt() or rgb)
