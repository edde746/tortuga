package com.edde746.tortuga.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import coil3.request.crossfade
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A poster loaded from the network fades in over the card's grey, never over what is behind the card (the black page):
 * on its way in the poster's area never gets darker than the blank card. Screen readers hear a card as one element: its
 * title, then its caption's second line, its progress and "Watched" as its state, and no caption of its own after it.
 * Without its caption a card shows no text and reads the same.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class PosterCardTest {
    @AfterTest
    fun forgetTheImageLoader() = SingletonImageLoader.reset()

    @Test
    fun aLoadingPosterFadesInOverTheGreyCardNotThePage() = runComposeUiTest {
        // The poster's lower half is the card's own grey, so a frame of its fade drawn over anything darker shows darker
        // there; its upper half is white, to follow the fade.
        val poster = ImageBitmap(200, 293).also {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(200f, 293f)) {
                drawRect(TortugaColors.SurfaceHigh)
                drawRect(Color.White, size = Size(200f, 146f))
            }
        }.toCoilBitmap().asImage()
        val fetcher = object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                override suspend fun fetch() = ImageFetchResult(poster, isSampled = false, dataSource = DataSource.NETWORK)
            }
        }
        // The app's loader (ProvideImageLoader) crossfades: the same here.
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(fetcher) }.crossfade(true).build())
        setContent {
            TortugaTheme {
                Box(Modifier.size(400.dp, 400.dp).background(Color.Black)) {
                    AdaptiveLayout(FormFactor.TV) {
                        PosterCard("Poster", "https://posters.test/poster.jpg", PosterShape.POSTER, onClick = {}, width = 200.dp)
                    }
                }
            }
        }
        // The artwork is 200 × 293 dp from the top left corner; its icon, while it loads, is at the centre.
        fun level(color: Color) = (color.red * 255).roundToInt()
        val frames = mutableListOf<Pair<Int, Int>>()
        val started = TimeSource.Monotonic.markNow()
        do {
            mainClock.advanceTimeByFrame()
            val pixels = onRoot().captureToImage().toPixelMap()
            val (x, top, bottom) = listOf(100.dp, 40.dp, 250.dp).map { (it.value * density.density).roundToInt() }
            frames += level(pixels[x, top]) to level(pixels[x, bottom])
        } while (frames.last().first < 250 && started.elapsedNow() < 5.seconds)

        val grey = level(TortugaColors.SurfaceHigh)
        assertTrue(frames.last().first >= 250, "the poster never came up (upper, lower half by frame: $frames)")
        assertTrue(frames.any { it.first in grey + 8..240 }, "no frame of the poster's fade was drawn: $frames")
        assertTrue(frames.all { it.second >= grey - 1 }, "the poster faded in over something darker than the card's grey: $frames")
    }

    @Test
    fun screenReadersHearACardAsOneElementWithItsStateAndNoCaptionAfterIt() = runComposeUiTest {
        setContent {
            TortugaTheme {
                PosterCard(
                    "Dune", null, PosterShape.POSTER, onClick = {}, subtitle = "2021 · ★ 8.0", progress = 0.45f, watched = true,
                    badge = "2", badgeDescription = T.calendar.newEpisodes(2),
                )
            }
        }
        val state = listOf(T.calendar.newEpisodes(2), "2021 · ★ 8.0", T.settings.value.percent("45"), T.library.watched)
            .joinToString(T.detail.listSeparator)
        onNode(hasClickAction() and hasContentDescription("Dune"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        val read = !SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        onAllNodes(read and (hasText("Dune") or hasText("2021 · ★ 8.0") or hasText("2"))).assertCountEquals(0)
    }

    @Test
    fun aCardWithoutItsCaptionShowsNoTextButReadsAsBefore() = runComposeUiTest {
        setContent {
            TortugaTheme {
                PosterCard("Dune", null, PosterShape.POSTER, onClick = {}, subtitle = "2021 · ★ 8.0", watched = true, caption = false)
            }
        }
        onAllNodes(hasText("Dune") or hasText("2021 · ★ 8.0"), useUnmergedTree = true).assertCountEquals(0)
        onNode(hasClickAction() and hasContentDescription("Dune"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, listOf("2021 · ★ 8.0", T.library.watched).joinToString(T.detail.listSeparator)))
    }
}
