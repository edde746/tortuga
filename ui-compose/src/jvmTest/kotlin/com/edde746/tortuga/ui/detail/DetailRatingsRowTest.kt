package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailRating
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.RatingLogo
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class DetailRatingsRowTest {
    /** Spirited Away's row with every source, as the store makes it: only IMDb's opens a page. */
    private val ratings = listOf(
        DetailRating(RatingSource.ROTTEN_TOMATOES_CRITICS, RatingLogo.RT_FRESH, "96%", "Rotten Tomatoes critics 96 percent", null),
        DetailRating(RatingSource.ROTTEN_TOMATOES_AUDIENCE, RatingLogo.RT_UPRIGHT, "96%", "Rotten Tomatoes audience 96 percent", null),
        DetailRating(RatingSource.IMDB, RatingLogo.IMDB, "8.6", "IMDb 8.6", "link:0"),
        DetailRating(RatingSource.TMDB, RatingLogo.TMDB, "85%", "TMDB 85 percent", null),
        DetailRating(RatingSource.METACRITIC, RatingLogo.METACRITIC, "96", "Metacritic 96", null),
        DetailRating(RatingSource.LETTERBOXD, RatingLogo.LETTERBOXD, "4.5", "Letterboxd 4.5", null),
        DetailRating(RatingSource.TRAKT, RatingLogo.TRAKT, "87%", "Trakt 87 percent", null),
        DetailRating(RatingSource.SIMKL, RatingLogo.SIMKL, "8.8", "Simkl 8.8", null),
        DetailRating(RatingSource.MYANIMELIST, RatingLogo.MYANIMELIST, "8.77", "MyAnimeList 8.77", null),
        DetailRating(RatingSource.ANILIST, RatingLogo.ANILIST, "86%", "AniList 86 percent", null),
    )

    private val state = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "movie",
            imdbId = null,
            title = "Spirited Away",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = "2001 · 2 h 5 min · Animation, Family",
            ratings = ratings,
            overview = null,
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("Play", "tt0245429", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = "Add to library",
        watched = false,
        watchedActionText = "Mark as watched",
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
    )

    /**
     * On a narrow phone the ten ratings wrap onto more lines inside the page's content edges (16 dp in from each side),
     * none clipped or overlapping, each line centred under the centred headline. Each rating is drawn plain: just
     * inside its corners, above and below its mark and score, the page shows, no fill. Each is one element read as its
     * label: its score is no text node of its own.
     */
    @Test
    fun onAPhoneTheRowWrapsCentredInsideThePage() = runComposeUiTest {
        val width = 360
        detail(FormFactor.HANDHELD, width, 1400) {}
        val items = ratings.map { bounds(hasContentDescription(it.label)) }
        for (item in items) {
            assertTrue(item.left >= 16.dp && item.right <= (width - 16).dp, "a rating lies at ${item.left}–${item.right}, outside the content's 16–${width - 16} dp: $items")
            assertTrue(item.width > 0.dp && item.height > 0.dp, "a rating is squeezed to ${item.width} × ${item.height}")
        }
        for ((i, a) in items.withIndex()) {
            for (b in items.drop(i + 1)) {
                assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top, "ratings overlap: $a and $b")
            }
        }
        val lines = items.groupBy { ((it.top + it.bottom) / 2).value.roundToInt() }.values
        assertTrue(lines.size > 1, "ten ratings on one line of a $width dp page: $items")
        for (line in lines) {
            val before = line.minOf { it.left } - 16.dp
            val after = (width - 16).dp - line.maxOf { it.right }
            assertTrue(abs((before - after).value) <= 1f, "a line of ratings isn't centred: $before before it, $after after it")
        }
        val colourAt = screen()
        for ((rating, item) in ratings.zip(items)) {
            for (x in listOf(item.left + 2.dp, item.right - 2.dp)) {
                for (y in listOf(item.top + 2.dp, item.bottom - 2.dp)) {
                    assertEquals(TortugaColors.Background, colourAt(x, y), "${rating.label} is drawn on a fill (at $x, $y)")
                }
            }
        }
        assertTrue(onAllNodes(hasText("8.77")).fetchSemanticsNodes().isEmpty(), "a score is a text node of its own")
    }

    /** The IMDb rating opens IMDb; the other ratings are no controls. */
    @Test
    fun onlyTheImdbRatingOpensAPage() = runComposeUiTest {
        val opened = mutableListOf<String>()
        detail(FormFactor.HANDHELD, 411, 1400) { opened += it }
        onNode(hasContentDescription("IMDb 8.6")).assertHasClickAction().performClick()
        assertEquals(listOf("link:0"), opened)
        for (rating in ratings.filter { it.linkId == null }) {
            onNode(hasContentDescription(rating.label)).assertHasNoClickAction()
        }
    }

    /** On TV the metadata line and its ratings are plain (Prime's are): the IMDb rating opens nothing there. */
    @Test
    fun onTvNoRatingIsAControl() = runComposeUiTest {
        detail(FormFactor.TV, 960, 540) {}
        waitForIdle()
        for (rating in ratings) onNode(hasContentDescription(rating.label)).assert(hasNoClickAction())
    }

    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): DpRect = onNode(matcher).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.focused(matcher: SemanticsMatcher): Boolean =
        onNode(matcher).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    /** The screen as drawn now: its colour at a point, in dp. */
    private fun ComposeUiTest.screen(): (Dp, Dp) -> Color {
        val pixels = onRoot().captureToImage().toPixelMap()
        val density = density
        return { x, y -> with(density) { pixels[x.toPx().roundToInt(), y.toPx().roundToInt()] } }
    }

    private fun ComposeUiTest.detail(
        formFactor: FormFactor,
        width: Int,
        height: Int,
        state: DetailUiState = this@DetailRatingsRowTest.state,
        scroll: BringIntoViewSpec? = null,
        onOpenLink: (String) -> Unit,
    ) = setContent {
        TortugaTheme {
            // The app's background, as its root draws it behind every screen.
            Box(Modifier.size(width.dp, height.dp).background(TortugaColors.Background)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides (scroll ?: LocalBringIntoViewSpec.current)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        DetailScreen(
                            state,
                            testDetailIntents(openLink = onOpenLink),
                            onBack = null,
                        )
                    }
                }
            }
        }
    }
}
