package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Detail's description is the addon's, in its own language whatever the interface's: its paragraph reads in the
 * direction of its script, its full stop at the end of its last line, and its lines keep to the interface's start edge,
 * on a phone and on a TV.
 */
@OptIn(ExperimentalTestApi::class)
class DetailDescriptionDirectionTest {
    @Test
    fun anEnglishDescriptionReadsLeftToRightInAnArabicInterface() = check(
        language = "ar",
        description = "The city's last family-run printing press prints wedding invitations by day and something else by night.",
        reads = ResolvedTextDirection.Ltr,
    )

    @Test
    fun anArabicDescriptionReadsRightToLeftInAnEnglishInterface() = check(
        language = "en",
        description = "آخر مطبعة عائلية في المدينة تطبع دعوات الأعراس نهارًا، وفي الليل تطبع شيئًا آخر لا يعرفه أحد.",
        reads = ResolvedTextDirection.Rtl,
    )

    /**
     * [description] on Detail in [language]'s interface on a phone and a TV: its paragraph resolves to [reads], the full
     * stop ends the last line on that side, and the last line (the shortest) sits against the interface's start edge.
     */
    private fun check(language: String, description: String, reads: ResolvedTextDirection) {
        val wrong = mutableListOf<String>()
        try {
            T.locale = language
            for ((formFactor, size) in listOf(FormFactor.HANDHELD to DpSize(411.dp, 914.dp), FormFactor.TV to DpSize(960.dp, 540.dp))) {
                runComposeUiTest {
                    detail(state(description), formFactor, size)
                    val at = "on $formFactor in $language"
                    val rtl = InterfaceLocale.isRightToLeft(language)
                    for (layout in layouts(description)) {
                        val last = layout.lineCount - 1
                        val direction = layout.getParagraphDirection(0)
                        if (direction != reads) wrong += "the description reads $direction, not $reads, $at"
                        val stop = layout.getBoundingBox(description.lastIndex)
                        val lineLeft = layout.getLineLeft(last)
                        val lineRight = layout.getLineRight(last)
                        val ends = if (reads == ResolvedTextDirection.Ltr) abs(stop.right - lineRight) < 1f else abs(stop.left - lineLeft) < 1f
                        if (!ends) wrong += "the full stop spans ${stop.left}–${stop.right} on a last line of $lineLeft–$lineRight $at"
                        val fromLeft = lineLeft
                        val fromRight = layout.size.width - lineRight
                        val atStart = if (rtl) fromRight < fromLeft else fromLeft < fromRight
                        if (!atStart) wrong += "the last line runs $lineLeft–$lineRight in a box ${layout.size.width} wide, off the interface's start edge, $at"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The layout of each text drawn with [text]; fails when there is none. */
    private fun ComposeUiTest.layouts(text: String): List<TextLayoutResult> {
        val nodes = onAllNodes(hasText(text), useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue(count > 0, "no text \"$text\" is drawn")
        return List(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.single()
        }
    }

    private fun state(description: String) = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "series",
            imdbId = null,
            title = "The Night Press",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = "2023– · 55 min · Drama, Mystery",
            ratings = emptyList(),
            overview = description,
            linkGroups = emptyList(),
            factsTitle = T.detail.facts.title,
            facts = emptyList(),
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction(T.detail.play, "tt-night-press", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = emptyList(),
        trailerActionText = T.detail.trailer,
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
    )

    /**
     * Detail in a window of [size], laid out right to left in a language that reads so, as the app lays it out: a TV's in
     * the app's frame beside the TV column.
     */
    private fun ComposeUiTest.detail(state: DetailUiState, formFactor: FormFactor, size: DpSize) = setContent {
        val direction = if (InterfaceLocale.isRightToLeft(T.locale)) LayoutDirection.Rtl else LayoutDirection.Ltr
        val screen = @Composable {
            DetailScreen(
                state,
                testDetailIntents(),
                onBack = null,
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            TortugaTheme {
                Box(Modifier.size(size)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        if (formFactor == FormFactor.TV) {
                            NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.BOARD, {}, showNavigation = true) { OverlayHostBox { screen() } }
                        } else {
                            screen()
                        }
                    }
                }
            }
        }
    }
}
