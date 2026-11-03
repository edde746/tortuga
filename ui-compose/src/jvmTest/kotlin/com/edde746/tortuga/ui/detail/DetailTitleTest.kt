package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
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
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation

/**
 * Detail's title at the largest text size: the name whole on every layout, Play still reachable under it; the name's
 * first line readable on the backdrop's fade; the title read once while the logo loads.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class DetailTitleTest {
    @AfterTest
    fun forgetTheImageLoader() = SingletonImageLoader.reset()

    /** The test addon's long name (tools/media/test_addon.py, HERO_LONG_NAME). */
    private val name = "Tortuga Hero Long Test: A Name Long Enough to Wrap onto Four Lines in a Narrow Window, and onto More at Large Text Sizes"

    private val infoLine = "2026 · 24 min · Animation, Comedy"

    private val header = DetailHeader(
        id = "tt0000001",
        type = "movie",
        imdbId = null,
        title = name,
        poster = null,
        background = null,
        logo = null,
        posterShape = PosterShape.POSTER,
        infoLine = infoLine,
        ratings = emptyList(),
        overview = null,
        linkGroups = emptyList(),
        factsTitle = "Information",
        facts = emptyList(),
    )

    private val movie = DetailUiState(
        body = DetailBody.Loaded(header),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction(T.detail.play, "tortuga-hero-long", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
    )

    /**
     * A phone in portrait (the narrow page) and in landscape (the wide page), a narrow desktop window and TV, each at the
     * largest font scale (the desktop has none: its own): the name on every line it takes, none ending in an ellipsis,
     * and Play reachable under it, scrolled to by touch or the wheel, brought into view by focus on TV.
     */
    @Test
    fun theNameIsWholeAndPlayReachableAtTheLargestTextSize() {
        val windows = listOf(
            Triple(FormFactor.HANDHELD, DpSize(411.dp, 914.dp), 2f),
            Triple(FormFactor.HANDHELD, DpSize(914.dp, 411.dp), 2f),
            Triple(FormFactor.DESKTOP, DpSize(640.dp, 480.dp), 1f),
            Triple(FormFactor.TV, DpSize(960.dp, 540.dp), 2f),
        )
        for ((formFactor, size, fontScale) in windows) runComposeUiTest {
            val at = "on $formFactor ${size.width} × ${size.height} at font scale $fontScale"
            detail(movie, formFactor, size, fontScale)
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val lines = layout.lineCount
            assertFalse((0 until lines).any(layout::isLineEllipsized), "the name ends in an ellipsis on line $lines $at")
            assertFalse(layout.hasVisualOverflow, "the name is cut after $lines lines $at")
            val play = if (formFactor == FormFactor.TV) {
                onNode(isFocused()).assert(hasText(T.detail.play))
            } else {
                onNodeWithText(T.detail.play).performScrollTo()
            }
            val bounds = play.getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= 0.dp && bounds.bottom <= size.height, "Play lies at ${bounds.top}–${bounds.bottom}, outside the window $at")
        }
    }

    /**
     * The narrow page over a white backdrop: the name's first line reads at 3:1 or better on the backdrop's fade, a name
     * taller than the title's box (at the default and the largest text size) and one of two lines that fits it alike;
     * the fade only rises (no row lighter than under a one-line name) and stays 80 % black at the metadata line's top.
     * The backdrop is read beside the name, in the page's margin.
     */
    @Test
    fun theNamesFirstLineReadsOnTheBackdropsFade() {
        val short = backdropBeside("Tortuga", 1f)
        for ((title, fontScale) in listOf(name to 1f, name to 2f, "Tortuga Hero Plain Test, a Name on Two Lines" to 1f)) {
            val page = backdropBeside(title, fontScale)
            val at = "\"$title\" at font scale $fontScale"
            assertTrue(page.firstLine >= 3.0, "the name's first line reads at ${page.firstLine}:1, under 3:1, $at")
            val lighter = page.levels.indices.filter { page.levels[it] > short.levels[it] + 1 }
            assertTrue(lighter.isEmpty(), "the backdrop is lighter than under a one-line name at $lighter dp $at")
            val text = short.textTop
            assertTrue(abs(page.levels[text] - short.levels[text]) <= 1, "the fade's 80 % point moved: ${page.levels[text]} at $text dp, not ${short.levels[text]} $at")
        }
    }

    /**
     * While the addon's logo loads, its name shows in the logo's place and a screen reader hears the title once: the
     * logo, loaded unseen, isn't named until it shows. On the phone's page and on TV's.
     */
    @Test
    fun theTitleIsReadOnceWhileTheLogoLoads() {
        val loading = object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                override suspend fun fetch(): FetchResult = awaitCancellation()
            }
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(loading) }.build())
        val title = "Tortuga Hero Plain Test"
        val state = movie.copy(body = DetailBody.Loaded(header.copy(title = title, logo = "https://logos.test/logo.png")))
        for ((formFactor, size) in listOf(FormFactor.HANDHELD to phone, FormFactor.TV to DpSize(960.dp, 540.dp))) runComposeUiTest {
            detail(state, formFactor, size, 1f)
            val read = onAllNodes(hasText(title) or hasContentDescription(title)).fetchSemanticsNodes()
            assertEquals(1, read.size, "the title is read ${read.size} times on $formFactor")
        }
    }

    /**
     * A level (0–255) per dp row of the capture, 8 dp in from its edge; the metadata line's top (dp); the name's first
     * line's contrast as the Apple check measures it: WCAG's ratio of the brightest 2 % of the pixels in the line's top
     * half (the letters) against their median (what is behind them).
     */
    private class Beside(val levels: List<Int>, val textTop: Int, val firstLine: Double)

    /** [backdropBeside]'s window: a phone in portrait, the narrow page. */
    private val phone = DpSize(411.dp, 914.dp)

    /** The narrow page of a movie named [title] over a white backdrop at [fontScale], read beside the title. */
    private fun backdropBeside(title: String, fontScale: Float): Beside {
        val white = ImageBitmap(160, 90).also {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(160f, 90f)) { drawRect(Color.White) }
        }.toCoilBitmap().asImage()
        val fetcher = object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                // As from the memory cache, so it shows at once rather than fading in.
                override suspend fun fetch() = ImageFetchResult(white, isSampled = false, dataSource = DataSource.MEMORY_CACHE)
            }
        }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(fetcher) }.build())
        lateinit var beside: Beside
        runComposeUiTest {
            val state = movie.copy(body = DetailBody.Loaded(header.copy(title = title, background = "https://backdrops.test/white.png")))
            detail(state, FormFactor.HANDHELD, phone, fontScale)
            // Play taking focus brings it into view, below a name this long: the page back to its top.
            onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            val x = with(density) { 8.dp.roundToPx() }
            fun PixelMap.level(row: Int) = (this[x, with(density) { row.dp.roundToPx() }].red * 255).roundToInt()
            // The page is black until the backdrop has loaded.
            waitUntil("the backdrop under \"$title\" at font scale $fontScale", timeoutMillis = 5_000) {
                onRoot().captureToImage().toPixelMap().level(40) > 0
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            val nameNode = onNodeWithText(title, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            nameNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds = nameNode.getUnclippedBoundsInRoot()
            val (left, top, right) = with(density) { listOf(bounds.left, bounds.top, bounds.right).map { it.roundToPx() } }
            val lumas = (top until top + (layouts.single().getLineBottom(0) / 2).roundToInt()).flatMap { y ->
                (left until right).map { column -> pixels[column, y].luminance().toDouble() }
            }.sorted()
            beside = Beside(
                levels = (0 until with(density) { pixels.height.toDp().value.toInt() }).map { pixels.level(it) },
                textTop = onNodeWithText(infoLine).getUnclippedBoundsInRoot().top.value.roundToInt(),
                firstLine = (lumas[(lumas.size - 1) * 98 / 100] + 0.05) / (lumas[lumas.size / 2] + 0.05),
            )
        }
        return beside
    }

    private fun ComposeUiTest.detail(state: DetailUiState, formFactor: FormFactor, size: DpSize, fontScale: Float) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            TortugaTheme {
                Box(Modifier.size(size).background(Color.Black)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        DetailScreen(
                            state,
                            testDetailIntents(),
                            onBack = null,
                        )
                    }
                }
            }
        }
    }
}
