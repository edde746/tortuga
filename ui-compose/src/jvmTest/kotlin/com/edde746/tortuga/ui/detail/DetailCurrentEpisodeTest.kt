package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
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
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The current episode on Detail (S2E7 of a 13-episode season): beside the details on a wide window the list opens with
 * its row in the middle of what shows of it, and on TV the episode row opens with its card in the middle and Down
 * through the seasons lands on it. A phone's one-column page opens at its top, and brings its list to an anime list
 * entry's first episode alone, at the top under the back button. Screen readers hear an episode's progress and
 * "Watched" as its state, never as a selected row or card.
 */
@OptIn(ExperimentalTestApi::class)
class DetailCurrentEpisodeTest {
    private val current = "tt0903747:2:7"

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
        primaryAction = DetailPrimaryAction("S2E7 · 12:34", current, resume = true, episodeTitle = "Negro y Azul", progress = 0.27),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markAllWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = listOf(
            DetailSeason(1, "Season 1", selected = false, watched = true),
            DetailSeason(2, "Season 2", selected = true, watched = false),
        ),
        episodes = (1..13).map { number ->
            DetailEpisode(
                videoId = "tt0903747:2:$number",
                code = "S2E$number",
                number = "$number",
                title = "Episode $number",
                numberedTitle = "$number. Episode $number",
                moreLabel = T.detail.moreActionsNamed("$number. Episode $number"),
                infoLine = "Mar 8, 2009 · 47 min",
                overview = "What happens in episode $number, over two lines of the row.",
                thumbnail = null,
                progress = if (number == 7) 0.27 else null,
                blurred = false,
                watched = number < 7,
                upcoming = false,
                nextUp = number == 7,
                watchedActionText = T.detail.markWatched,
                detailLine = "Mar 8, 2009 · 47 min",
                stateText = itemStateText(null, null, if (number == 7) 0.27 else null, watched = number < 7),
            )
        },
        chooseStreamText = null,
        currentEpisode = current,
        shownEpisode = current,
    )

    private fun ComposeUiTest.detail(formFactor: FormFactor, width: Dp, height: Dp, state: DetailUiState = series, onShown: () -> Unit = {}) {
        setContent {
            TortugaTheme {
                Box(Modifier.size(width, height)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        OverlayHostBox {
                            DetailScreen(
                                state,
                                testDetailIntents(episodeShown = onShown),
                                onBack = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.row(number: Int): SemanticsNodeInteraction = onNode(hasClickAction() and hasText("$number. Episode $number"))

    private val SemanticsNodeInteraction.bounds: Rect get() = fetchSemanticsNode().boundsInRoot

    /** The episodes' list: the lazy list holding [row]. */
    private fun ComposeUiTest.listAround(row: Rect): Rect =
        onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().map { it.boundsInRoot }.single { it.contains(row.center) }

    private fun assertMiddle(expected: Float, row: Rect, what: String) {
        assertTrue(abs(row.center.y - expected) <= 1f, "$what: the current episode's middle is at ${row.center.y}, not at $expected")
    }

    @Test
    fun onAPhoneThePageOpensAtItsTop() = runComposeUiTest {
        var shown = 0
        detail(FormFactor.HANDHELD, 400.dp, 800.dp) { shown++ }
        waitForIdle()
        onNode(hasClickAction() and hasText(series.primaryAction!!.label)).assertIsDisplayed()
        row(7).assertDoesNotExist()
        assertEquals(0, shown, "the store isn't told the list was brought there")
    }

    @Test
    fun onAPhoneAnAnimeEntrysFirstEpisodeComesToTheTopUnderTheBackButton() = runComposeUiTest {
        var shown = 0
        // S2E3: far enough from the season's end for the list to bring it to the top.
        detail(FormFactor.HANDHELD, 400.dp, 800.dp, series.copy(shownEpisode = "tt0903747:2:3", shownEpisodeFromEntry = true)) { shown++ }
        waitForIdle()
        val row = row(3).bounds
        val top = listAround(row).top + with(density) { 68.dp.toPx() }
        assertTrue(abs(row.top - top) <= 1f, "the entry's first episode's top is at ${row.top}, not at $top")
        assertEquals(1, shown, "the store hears the list was brought there")
    }

    @Test
    fun besideTheDetailsTheListOpensWithTheCurrentEpisodeInTheMiddle() = runComposeUiTest {
        detail(FormFactor.DESKTOP, 1280.dp, 800.dp)
        waitForIdle()
        val row = row(7).bounds
        val list = listAround(row)
        assertMiddle(list.center.y, row, "wide window")
    }

    /** TV's episode row is a row of cards, each named by its code. */
    private fun ComposeUiTest.card(number: Int): SemanticsNodeInteraction = onNode(hasClickAction() and hasText("S2E$number"))

    @Test
    fun onTvTheRowOpensWithTheCurrentEpisodeInTheMiddle() = runComposeUiTest {
        var shown = 0
        detail(FormFactor.TV, 960.dp, 540.dp) { shown++ }
        waitForIdle()
        val card = card(7).bounds
        val row = listAround(card)
        assertTrue(abs(card.center.x - row.center.x) <= 1f, "the current episode's middle is at ${card.center.x}, not at ${row.center.x}")
        assertEquals(1, shown, "the store hears the row was brought there")
    }

    /**
     * The row at its start (already shown once, so the store asks for nothing): Down from the actions lands on the
     * selected season's chip, and Down again on the current episode, brought to the middle of the row.
     */
    @Test
    fun onTvDownThroughTheSeasonsLandsOnTheCurrentEpisodeInTheMiddle() = runComposeUiTest {
        detail(FormFactor.TV, 960.dp, 540.dp, series.copy(shownEpisode = null))
        waitForIdle()
        onNode(isFocused()).assert(hasText(series.primaryAction!!.label))
        card(7).assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNode(isFocused()).assert(hasText("Season 2"))
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        card(7).assertIsFocused()
        val card = card(7).bounds
        val row = listAround(card)
        assertTrue(abs(card.center.x - row.center.x) <= 1f, "focused, the current episode's middle is at ${card.center.x}, not at ${row.center.x}")
    }

    /** S2E6's row or card says it is watched, S2E7's how far it is watched, and neither is a selected one. */
    private fun assertStates(episode: (Int) -> SemanticsNodeInteraction) {
        episode(6).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, T.library.watched)).assert(!isSelectable())
        episode(7).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, T.settings.value.percent("27"))).assert(!isSelectable())
    }

    @Test
    fun besideTheDetailsScreenReadersHearTheRowsProgressAndWatchedAsTheirState() = runComposeUiTest {
        detail(FormFactor.DESKTOP, 1280.dp, 800.dp)
        waitForIdle()
        assertStates { row(it) }
    }

    @Test
    fun onTvScreenReadersHearTheCardsProgressAndWatchedAsTheirState() = runComposeUiTest {
        detail(FormFactor.TV, 960.dp, 540.dp)
        waitForIdle()
        assertStates { card(it) }
    }
}
