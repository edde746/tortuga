package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailTrailer
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.tvActions
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.getDrawnBoundsInRoot
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Detail on a kids profile: a blocked title shows only that it isn't available, why, and Ask a parent, on phones and
 * TVs alike; an allowed one offers Block on this profile (phones: behind ⋯, after every other action; TV: last in the
 * row).
 */
@OptIn(ExperimentalTestApi::class)
class DetailKidsTest {
    private val calls = mutableListOf<String>()

    private val block = DetailBody.Blocked(T.kids.blocked.title, T.kids.blocked.suggested("16"), T.kids.blocked.ask)

    /**
     * A blocked title shows the block alone: why, and Ask a parent, which asks, and on TV has focus as the page opens;
     * nothing of the page's actions.
     */
    @Test
    fun aBlockedTitleShowsOnlyTheBlockWithAskAParent() {
        val state = movie.copy(body = block, primaryAction = null, trailers = emptyList())
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.TV)) {
            calls.clear()
            runComposeUiTest {
                page(formFactor, state)
                onNode(hasText(T.kids.blocked.title)).assertExists()
                onNode(hasText(T.kids.blocked.suggested("16"))).assertExists()
                onNode(hasText("Play", substring = true)).assertDoesNotExist()
                onNode(hasContentDescription(T.detail.moreActions)).assertDoesNotExist()
                val ask = onNode(hasClickAction() and hasText(T.kids.blocked.ask))
                if (formFactor == FormFactor.TV) ask.assert(isFocused())
                ask.performClick()
                runOnIdle { assertEquals(listOf("askParent"), calls, "$formFactor") }
            }
        }
    }

    /**
     * On a phone, an allowed title's Block on this profile is behind ⋯, after the actions the row has no room for, even
     * where every other action has its button (a tablet's width); its entry blocks.
     */
    @Test
    fun onPhonesBlockOnThisProfileIsTheLastEntryBehindMore() {
        for (width in listOf(360, 1024)) {
            calls.clear()
            runComposeUiTest {
                page(FormFactor.HANDHELD, movie.copy(kidsBlockAction = T.kids.block), width)
                onNode(hasClickAction() and hasContentDescription(T.detail.moreActions)).performClick()
                val entries = onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes().map { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
                }
                assertEquals(T.kids.block, entries.last(), "at $width dp: $entries")
                onNode(hasClickAction() and hasText(T.kids.block) and hasAnyAncestor(isPopup())).performClick()
                runOnIdle { assertEquals(listOf("blockOnProfile"), calls, "at $width dp") }
            }
        }
    }

    /**
     * On a phone whose row has room beside Play for exactly the title's three round buttons, Block on this profile's ⋯
     * takes only the last of them, as ⋯ does for any title whose actions don't all fit: the library and the watched
     * mark keep their buttons, the trailer goes behind ⋯ before Block.
     */
    @Test
    fun onPhonesMoreForBlockOnThisProfileTakesOneSlot() {
        val state = movie.copy(kidsBlockAction = T.kids.block)
        var play = 0f
        runComposeUiTest {
            page(FormFactor.HANDHELD, state)
            play = onNode(hasClickAction() and hasText("Play")).getDrawnBoundsInRoot().width.value
        }
        // The page's 16 dp edges, Play, then three round buttons of 40 dp, each after a 10 dp gap.
        val width = ceil(2 * 16 + play + 3 * (40 + 10)).toInt() + 1
        runComposeUiTest {
            page(FormFactor.HANDHELD, state, width)
            onNode(hasClickAction() and hasContentDescription(T.detail.addToLibrary)).assertExists()
            onNode(hasClickAction() and hasContentDescription(T.detail.markWatched)).assertExists()
            onNode(hasClickAction() and hasContentDescription("Trailer")).assertDoesNotExist()
            onNode(hasClickAction() and hasContentDescription(T.detail.moreActions)).performClick()
            val entries = onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes().map { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
            }
            assertEquals(listOf("Trailer", T.kids.block), entries, "at $width dp")
        }
    }

    /** On TV Right from Play walks the title's actions, then Block on this profile, last; it blocks. */
    @Test
    fun onTvBlockOnThisProfileIsLastInTheRow() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val state = movie.copy(kidsBlockAction = T.kids.block)
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { Screen(state) } }
                }
            }
        }
        onNode(isFocused()).assert(hasText("Play"))
        repeat(state.tvActions.size) { onRoot().performKeyInput { pressKey(Key.DirectionRight) } }
        onNode(isFocused()).assert(hasContentDescription(T.kids.block))
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNode(isFocused()).assert(hasContentDescription(T.kids.block))
        onNode(hasClickAction() and hasContentDescription(T.kids.block)).performClick()
        runOnIdle { assertEquals(listOf("blockOnProfile"), calls) }
    }

    private val movie = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "movie",
            imdbId = null,
            title = "Paddington",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = "2014 · 1 h 35 min · Comedy, Family",
            ratings = emptyList(),
            overview = null,
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("Play", "tt1109624", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = listOf(DetailTrailer("a", "Trailer")),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
    )

    /** Detail of [state] in a [formFactor] window ([width] dp wide on phones; 960 × 540 dp on TV). */
    private fun ComposeUiTest.page(formFactor: FormFactor, state: DetailUiState, width: Int = 411) = setContent {
        TortugaTheme {
            val tv = formFactor == FormFactor.TV
            Box(Modifier.size(if (tv) 960.dp else width.dp, if (tv) 540.dp else 900.dp)) {
                AdaptiveLayout(formFactor, if (tv) Modifier.arrowKeyFocus() else Modifier) { OverlayHostBox { Screen(state) } }
            }
        }
    }

    /** Detail of [state], Ask a parent and Block on this profile recorded in [calls]. */
    @Composable
    private fun Screen(state: DetailUiState) = DetailScreen(
        state,
        testDetailIntents(askParent = { calls += "askParent" }, blockOnProfile = { calls += "blockOnProfile" }),
        onBack = null,
    )
}
