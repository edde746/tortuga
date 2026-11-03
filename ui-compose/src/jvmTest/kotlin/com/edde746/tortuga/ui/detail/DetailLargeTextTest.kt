package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.itemStateText
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** A series' Detail on a phone at Android's largest font scale (2.0), against the default (1.0). */
@OptIn(ExperimentalTestApi::class)
class DetailLargeTextTest {
    private val title = "1. The One Where the Long Episode Title Runs On Past Two Lines of the Row"
    private val upNext = "The Episode Under Play, Whose Name Is Longer Than One Line Can Hold"

    private val series = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "series",
            imdbId = null,
            title = "Breaking Bad",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = null,
            ratings = emptyList(),
            overview = null,
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("S1E1 · 12:34", "tt0903747:1:1", resume = true, episodeTitle = upNext, progress = 0.27),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markAllWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = listOf(DetailSeason(1, "Season 1", selected = true, watched = false)),
        episodes = listOf(
            DetailEpisode(
                videoId = "tt0903747:1:1",
                code = "S1E1",
                number = "1",
                title = title.removePrefix("1. "),
                numberedTitle = title,
                moreLabel = T.detail.moreActionsNamed(title),
                infoLine = "Jan 20, 2008 · 58 min",
                overview = "What happens in the episode, over two lines of the row at the default size.",
                thumbnail = null,
                progress = 0.27,
                blurred = false,
                watched = false,
                upcoming = false,
                nextUp = true,
                watchedActionText = T.detail.markWatched,
                detailLine = "Jan 20, 2008 · 58 min",
                stateText = itemStateText(null, null, 0.27, watched = false),
            ),
        ),
        chooseStreamText = null,
    )

    /**
     * At 2.0 an episode row puts its title under the 16:9 thumbnail (128 dp wide on a phone: 72 dp tall), at the row's
     * 12 dp inset, whole, and the episode line under Play is whole; at 1.0 the title stays beside the thumbnail.
     */
    @Test
    fun atTheLargestFontScaleAnEpisodesTitleGoesUnderItsThumbnailWhole() {
        val wrong = mutableListOf<String>()
        for (fontScale in listOf(1f, 2f)) runSkikoComposeUiTest(size = Size(412f, 3000f)) {
            detail(fontScale)
            val row = onNode(hasClickAction() and hasText(title)).getUnclippedBoundsInRoot()
            val text = onNode(hasText(title), useUnmergedTree = true).getUnclippedBoundsInRoot()
            val inset = row.left + 12.dp
            if (fontScale == 2f) {
                if (abs((text.left - inset).value) > 0.5f) wrong += "the title starts at ${text.left}, not at the row's inset $inset, at 2.0"
                if (text.top < row.top + 12.dp + 72.dp) wrong += "the title starts at ${text.top}, beside the thumbnail (the row's top is ${row.top}), at 2.0"
                if (cut(title)) wrong += "the title is cut at 2.0"
                if (cut(upNext)) wrong += "the episode line under Play is cut at 2.0"
            } else if (text.left < inset + 128.dp) {
                wrong += "the title starts at ${text.left}, not beside the thumbnail, at 1.0"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** Whether [text]'s lines run past its line limit or its box. */
    private fun ComposeUiTest.cut(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText(text), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().didOverflowHeight
    }

    private fun ComposeUiTest.detail(fontScale: Float) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            TortugaTheme {
                Box(Modifier.size(412.dp, 3000.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) {
                        OverlayHostBox {
                            DetailScreen(
                                series,
                                testDetailIntents(),
                                onBack = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
