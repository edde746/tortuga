package com.edde746.tortuga.ui.board

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.FolderTile
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A folder tile's cover against the row's shape (square here): one whose aspect is the tile's fills it; one far off it
 * (16 : 9) is drawn whole, its own image dimmed and blurred filling the bars; a transparent one (a logo) is drawn whole
 * over the tile's grey, with nothing behind it.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class FolderCoverTest {
    @AfterTest
    fun forgetTheImageLoader() = SingletonImageLoader.reset()

    /** A [width] × [height] cover: red, with a green stripe down its left tenth; [logo]: transparent but a red middle. */
    private fun cover(width: Int, height: Int, logo: Boolean = false) = ImageBitmap(width, height).also {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(width.toFloat(), height.toFloat())) {
            if (logo) {
                drawRect(Color.Red, topLeft = Offset(width / 4f, height / 4f), size = Size(width / 2f, height / 2f))
            } else {
                drawRect(Color.Red)
                drawRect(Color.Green, size = Size(width / 10f, height.toFloat()))
            }
        }
    }.toCoilBitmap().asImage()

    private val covers = mapOf(
        "https://covers.test/square.png" to cover(200, 196),
        "https://covers.test/wide.png" to cover(320, 180),
        "https://covers.test/logo.png" to cover(320, 180, logo = true),
    )

    @Test
    fun aCoverOffTheTilesShapeIsDrawnWholeOverItsOwnFill() = runComposeUiTest {
        tiles()
        val square = bounds("Square")
        val wide = bounds("Wide")
        val logo = bounds("Logo")
        val started = TimeSource.Monotonic.markNow()
        var pixels: PixelMap
        // Each tile's middle is the cover's red once loaded; the wide tile's bar is its fill once that is loaded.
        do {
            mainClock.advanceTimeByFrame()
            pixels = onRoot().captureToImage().toPixelMap()
            val loaded = listOf(square, wide, logo).all { red(pixels.at(it, 0.5f, 0.5f)) } &&
                pixels.at(wide, 0.5f, 0.08f) != TortugaColors.SurfaceHigh
        } while (!loaded && started.elapsedNow() < 5.seconds)
        // The covers' fades in done.
        mainClock.advanceTimeBy(1_000)
        pixels = onRoot().captureToImage().toPixelMap()

        // Within 5 % of the tile's aspect: cropped to fill, the stripe at its edge, its own red at the top.
        assertTrue(green(pixels.at(square, 0.03f, 0.5f)), "the square cover's stripe: ${pixels.at(square, 0.03f, 0.5f)}")
        assertTrue(pixels.at(square, 0.5f, 0.08f).red > 0.95f, "the square cover fills its tile: ${pixels.at(square, 0.5f, 0.08f)}")

        // 16 : 9 in a square: whole (its stripe at the tile's edge, which a crop would cut off), the bars its own
        // colours, dimmed.
        assertTrue(green(pixels.at(wide, 0.03f, 0.5f)), "the wide cover isn't drawn whole: ${pixels.at(wide, 0.03f, 0.5f)}")
        val bar = pixels.at(wide, 0.5f, 0.08f)
        assertTrue(bar.red in 0.35f..0.9f && bar.green < 0.25f && bar.blue < 0.25f, "the wide cover's bar isn't its dimmed red: $bar")

        // A transparent cover: whole over the tile's grey, no fill behind it.
        assertTrue(red(pixels.at(logo, 0.5f, 0.5f)), "the logo: ${pixels.at(logo, 0.5f, 0.5f)}")
        assertTrue(same(pixels.at(logo, 0.5f, 0.08f), TortugaColors.SurfaceHigh), "the logo's bar isn't the tile's grey: ${pixels.at(logo, 0.5f, 0.08f)}")
    }

    private fun red(color: Color) = color.red > 0.9f && color.green < 0.1f && color.blue < 0.1f

    private fun green(color: Color) = color.green > 0.4f && color.red < 0.2f

    private fun same(a: Color, b: Color) = abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    /** The tile titled [title]'s bounds, in px. */
    private fun ComposeUiTest.bounds(title: String): Rect {
        val dp = onNode(hasContentDescription(title) and hasClickAction()).getBoundsInRoot()
        return Rect(dp.left.value, dp.top.value, dp.right.value, dp.bottom.value).let { r ->
            Rect(r.left * density.density, r.top * density.density, r.right * density.density, r.bottom * density.density)
        }
    }

    /** The pixel at [x], [y] (fractions of [tile]'s width and height). */
    private fun PixelMap.at(tile: Rect, x: Float, y: Float) =
        this[(tile.left + tile.width * x).roundToInt(), (tile.top + tile.height * y).roundToInt()]

    private fun ComposeUiTest.tiles() {
        val fetcher = object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                override suspend fun fetch() = ImageFetchResult(covers.getValue(data.toString()), isSampled = false, dataSource = DataSource.NETWORK)
            }
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(fetcher) }.build())
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.DESKTOP) {
                    Row {
                        for ((title, url) in listOf("Square" to "square", "Wide" to "wide", "Logo" to "logo")) {
                            FolderTileCard(
                                FolderTile(url, title, "https://covers.test/$url.png", showTitle = false, note = null),
                                PosterShape.SQUARE,
                                onClick = {},
                                probe = "tile.$url",
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
    }
}
