package com.edde746.tortuga.ui.board

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.PosterTemplate
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.LocalPosterTemplate
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.util.Collections
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * With a poster template a catalog card asks for the template's poster first and, when that fails to load, shows the
 * addon's; without one it asks for the addon's alone.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class MetaPosterTemplateTest {
    @AfterTest
    fun forgetTheImageLoader() = SingletonImageLoader.reset()

    @Test
    fun aTemplatePosterThatFailsGivesWayToTheAddons() = runComposeUiTest {
        val requested = show(PosterTemplate.of("https://posters.test/{imdb_id}.jpg", kids = false))
        assertEquals(listOf("https://posters.test/tt0133093.jpg", ADDON), requested.toList())
    }

    @Test
    fun withoutATemplateOnlyTheAddonsPosterIsAskedFor() = runComposeUiTest {
        val requested = show(null)
        assertEquals(listOf(ADDON), requested.toList())
    }

    /** Shows the card under [template] until the addon's red poster is drawn; the URLs asked for, in order. */
    private fun ComposeUiTest.show(template: PosterTemplate?): List<String> {
        val red = ImageBitmap(200, 293).also {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(200f, 293f)) { drawRect(Color.Red) }
        }.toCoilBitmap().asImage()
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val fetcher = object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                override suspend fun fetch(): FetchResult {
                    val url = data.toString()
                    requested += url
                    if (url != ADDON) error("not found: $url")
                    return ImageFetchResult(red, isSampled = false, dataSource = DataSource.MEMORY_CACHE)
                }
            }
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(fetcher) }.build())
        val card = MetaCard("tt0133093", "movie", "The Matrix", "1999", null, ADDON, PosterShape.POSTER, null)
        setContent {
            CompositionLocalProvider(LocalPosterTemplate provides template) {
                TortugaTheme {
                    Box(Modifier.size(400.dp, 400.dp)) {
                        AdaptiveLayout(FormFactor.DESKTOP) { MetaPoster(card, onClick = {}, onMenu = {}, probe = "card", width = 200.dp) }
                    }
                }
            }
        }
        // The artwork is 200 dp wide from the top left corner: its upper middle is the poster's.
        val started = TimeSource.Monotonic.markNow()
        fun redShown(): Boolean {
            val (x, y) = listOf(100.dp, 40.dp).map { (it.value * density.density).roundToInt() }
            val pixel = onRoot().captureToImage().toPixelMap()[x, y]
            return pixel.red > 0.9f && pixel.green < 0.1f && pixel.blue < 0.1f
        }
        while (!redShown() && started.elapsedNow() < 5.seconds) mainClock.advanceTimeByFrame()
        assertTrue(redShown(), "the addon's poster isn't drawn (asked for: $requested)")
        return requested
    }

    private companion object {
        const val ADDON = "https://addon.test/matrix.jpg"
    }
}
