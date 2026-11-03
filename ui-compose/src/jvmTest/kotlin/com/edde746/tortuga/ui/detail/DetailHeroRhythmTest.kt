package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.annotation.DelicateCoilApi
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailRating
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.RatingLogo
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.getDrawnBoundsInRoot
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Detail's hero keeps one rhythm on every layout: Play's top sits as far under the metadata block's last line (the
 * ratings row, else the metadata line) as the metadata line's top under the title's box, the headline's spacing, within
 * 1 dp; no row keeps room for one that isn't there. Each layout shows a movie and a series with a logo and a backdrop
 * (the title's box rises by a clear logo's transparent bottom) and a movie with a backdrop alone (on narrow pages its
 * name's box rises the same), each with the addon's IMDb rating and without. The images fail here (no network), so
 * the name stands at the bottom of the title's box, as while a logo loads.
 */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class DetailHeroRhythmTest {
    @AfterTest
    fun forgetTheImageLoader() = SingletonImageLoader.reset()

    @Test
    fun wideWindow() = rhythm(FormFactor.DESKTOP, 1280, 720)

    @Test
    fun narrowWindow() = rhythm(FormFactor.DESKTOP, 640, 480)

    @Test
    fun phoneUpright() = rhythm(FormFactor.HANDHELD, 412, 915)

    @Test
    fun phoneLandscape() = rhythm(FormFactor.HANDHELD, 915, 412)

    private class Case(val name: String, val state: DetailUiState, val rated: Boolean)

    private val movie = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "movie",
            imdbId = null,
            title = TITLE,
            poster = null,
            background = "https://images.test/backdrop.jpg",
            logo = "https://images.test/logo.png",
            posterShape = PosterShape.POSTER,
            infoLine = INFO_LINE,
            ratings = listOf(DetailRating(RatingSource.IMDB, RatingLogo.IMDB, "9.3", IMDB, "link:0")),
            overview = null,
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("Play", "tt0111161", resume = false, episodeTitle = null, progress = null),
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

    private val cases: List<Case> = buildList {
        val series = movie.copy(
            body = DetailBody.Loaded((movie.body as DetailBody.Page).header.copy(type = "series")),
            seasons = listOf(DetailSeason(1, "Season 1", selected = true, watched = false)),
        )
        val plain = movie.copy(body = DetailBody.Loaded((movie.body as DetailBody.Page).header.copy(logo = null)))
        for ((name, state) in listOf("movie with a logo" to movie, "series with a logo" to series, "movie without a logo" to plain)) {
            add(Case("$name, rated", state, rated = true))
            add(Case("$name, no ratings", state.copy(body = DetailBody.Loaded((state.body as DetailBody.Page).header.copy(ratings = emptyList()))), rated = false))
        }
    }

    private fun rhythm(formFactor: FormFactor, width: Int, height: Int) = runComposeUiTest {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(Offline) }.build())
        var shown by mutableStateOf(cases.first())
        setContent {
            TortugaTheme {
                Box(Modifier.size(width.dp, height.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        key(shown) {
                            DetailScreen(
                                shown.state,
                                testDetailIntents(),
                                onBack = null,
                            )
                        }
                    }
                }
            }
        }
        val measured = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        for (case in cases) {
            shown = case
            waitForIdle()
            val title = bounds(hasText(TITLE))
            val line = bounds(hasText(INFO_LINE))
            val last = if (case.rated) bounds(hasContentDescription(IMDB)).bottom else line.bottom
            val play = bounds(hasText("Play") and hasClickAction())
            val spacing = line.top - title.bottom
            val actions = play.top - last
            val what = "${case.name}: the metadata line ${spacing.value} dp under the title, Play ${actions.value} dp under ${if (case.rated) "the ratings" else "the metadata line"} (its top at ${play.top.value} dp)"
            measured += what
            if (abs((actions - spacing).value) > 1f) wrong += what
        }
        println("hero rhythm, $formFactor $width × $height dp:\n  ${measured.joinToString("\n  ")}")
        assertTrue(wrong.isEmpty(), "on a $formFactor $width × $height dp page Play isn't the headline's spacing under the block above it:\n  ${wrong.joinToString("\n  ")}")
    }

    /** Where [matcher]'s element is drawn (on a phone Play takes taps in a taller box than it shows). */
    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): DpRect = onNode(matcher).getDrawnBoundsInRoot()

    /** Every image fails at once, as without a network. */
    private object Offline : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
            override suspend fun fetch(): FetchResult = error("offline: $data")
        }
    }

    private companion object {
        const val TITLE = "The Shawshank Redemption"
        const val INFO_LINE = "1994 · 2 h 22 min · Drama"
        const val IMDB = "IMDb 9.3"
    }
}
