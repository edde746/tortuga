package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.itemStateText
import com.edde746.tortuga.presentation.detail.DetailAction
import com.edde746.tortuga.presentation.detail.DetailActionFit
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailDownload
import com.edde746.tortuga.presentation.detail.DetailDownloadEntry
import com.edde746.tortuga.presentation.detail.DetailDownloadSection
import com.edde746.tortuga.presentation.detail.DetailDownloadState
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailTrailer
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.DetailUserRating
import com.edde746.tortuga.presentation.detail.actions
import com.edde746.tortuga.presentation.detail.fitActions
import com.edde746.tortuga.presentation.detail.tvActions
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.MIN_LABEL_SIZE
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.getDrawnBoundsInRoot
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailActionRowTest {
    /**
     * Play's label, longer than any the store puts there: a resumed episode with a two-digit season and an hour's
     * position in its spoken words (Play shows "S12E108 · 1:23:45"). A 320 dp phone has room for Play and ⋯ alone, and
     * in a wide font (macOS's) not even for Play's full width beside ⋯.
     */
    private val resume = T.detail.resumeEpisode("S12E108", "1:23:45")

    private val state = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "series",
            imdbId = null,
            title = "Chernobyl",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = "2019 · 1 h 5 min · Drama, History",
            ratings = emptyList(),
            overview = null,
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction(resume, "tt7366338:12:108", resume = true, episodeTitle = "Vichnaya Pamyat", progress = 0.4),
        inLibrary = true,
        libraryActionText = T.detail.removeFromLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        // Two trailers, auto-play's stream list button and a download in progress (its press opens its menu) too, so
        // the row has every action it can.
        trailers = listOf(DetailTrailer("a", "Trailer"), DetailTrailer("b", "Teaser")),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = T.detail.chooseStream,
        download = DetailDownload(
            DetailDownloadState.DOWNLOADING,
            "Downloading 45%",
            0.45,
            opensMenu = true,
            menuTitle = T.detail.download.menuTitle,
            menu = listOf(DetailDownloadSection(null, listOf(DetailDownloadEntry("pause", "Pause download")))),
        ),
    )

    /** Each action's words: its round button's, and its entry's in ⋯'s menu. */
    private val labels = linkedMapOf(
        DetailAction.CHOOSE_STREAM to T.detail.chooseStream,
        DetailAction.LIBRARY to T.detail.removeFromLibrary,
        DetailAction.WATCHED to T.detail.markWatched,
        DetailAction.DOWNLOAD to "Downloading 45%",
        DetailAction.TRAILER to "Trailer",
    )

    /** The same title as a series, its season's episodes beside the details on wide layouts and TV. */
    private val series = state.copy(
        seasons = listOf(DetailSeason(12, "Season 12", selected = true, watched = false)),
        episodes = listOf(
            DetailEpisode(
                videoId = "tt7366338:12:108",
                code = "S12E108",
                number = "108",
                title = "Vichnaya Pamyat",
                numberedTitle = "108. Vichnaya Pamyat",
                moreLabel = T.detail.moreActionsNamed("108. Vichnaya Pamyat"),
                infoLine = "Jun 4, 2019 · 1 h 5 min",
                overview = null,
                thumbnail = null,
                progress = 0.4,
                blurred = false,
                watched = false,
                upcoming = false,
                nextUp = true,
                watchedActionText = T.detail.markWatched,
                detailLine = "Jun 4, 2019 · 1 h 5 min",
                stateText = itemStateText(null, null, 0.4, watched = false),
            ),
        ),
    )

    private val more = hasClickAction() and hasContentDescription(T.detail.moreActions)

    /**
     * On phones from a Fold's 320 dp cover screen up to 440 dp, with the longest Resume label, at the default font size
     * and at 130 %: the row keeps as many round buttons as fit beside Play at its label's full width (its width on a
     * tablet), each at its full 40 dp after a 10 dp gap inside the page's content edges (16 dp in), ⋯ taking the last
     * place ([fitActions] with that room); Play is narrower only where Play and ⋯ alone don't fit, and there takes the
     * rest of the row. Play's label stays whole at the default size everywhere (it shrinks a little where it must) and
     * at 130 % wherever Play has its full width. Every control lies on one line, none overlapping; ⋯'s menu lists
     * exactly the others in the row's order, and each entry does what its button does (the download's and the
     * trailers' entries open their menus). Across the widths every action goes through ⋯ once at least. Every width is
     * measured, so the test holds whatever font the host draws the label in.
     */
    @Test
    fun onPhonesTheRowKeepsItsButtonsWholeAndTheRestBehindMore() {
        val used = mutableSetOf<DetailAction>()
        for (fontScale in listOf(1f, 1.3f)) {
            val playWidth = tabletPlayWidth(fontScale)
            for (width in listOf(320, 375, 412, 440)) {
                val calls = mutableListOf<String>()
                runComposeUiTest {
                    detail(width, calls, fontScale)
                    val shown = labels.filterValues { exists(hasClickAction() and hasContentDescription(it)) }.keys.toList()
                    val hidden = labels.keys.filterNot { it in shown }
                    val play = bounds(hasText(resume))
                    val buttons = shown.map { bounds(hasContentDescription(labels.getValue(it))) } + bounds(more)
                    val row = listOf(play) + buttons
                    val at = "at $width dp, font scale $fontScale: $row"
                    val content = (width - 2 * 16).dp
                    val slots = floor((content - playWidth) / (40.dp + 10.dp)).toInt()
                    assertEquals(state.fitActions(slots), DetailActionFit(shown, hidden), "the row keeps the wrong actions for its room $at")
                    if (slots >= 1) {
                        assertEquals(playWidth, play.width, "Play doesn't have its label's full width $at")
                    } else {
                        assertEquals(content - 50.dp, play.width, "Play doesn't take the row's rest beside ⋯ $at")
                    }
                    if (fontScale == 1f || slots >= 1) assertFalse(playLabelCut(), "Play's label is cut short $at")
                    for (control in row) {
                        assertTrue(control.left >= 16.dp && control.right <= (width - 16).dp, "a control lies at ${control.left}–${control.right}, outside the content $at")
                    }
                    assertTrue(row.zipWithNext().all { (a, b) -> a.right <= b.left }, "the row's controls overlap $at")
                    for (button in buttons) {
                        assertTrue(button.width == 40.dp && button.height == 40.dp, "a round button is ${button.width} × ${button.height}, not 40 dp $at")
                    }
                    val middle = (play.top + play.bottom) / 2
                    assertTrue(row.all { middle > it.top && middle < it.bottom }, "the row wraps $at")

                    onNode(more).performClick()
                    assertEquals(hidden.map(labels::getValue), menuEntries(), "⋯'s menu $at")
                    for (action in hidden) {
                        if (action != hidden.first()) onNode(more).performClick()
                        onNode(hasClickAction() and hasText(labels.getValue(action)) and hasAnyAncestor(isPopup())).performClick()
                        when (action) {
                            DetailAction.DOWNLOAD -> onNode(hasClickAction() and hasText("Pause download")).performClick()
                            DetailAction.TRAILER -> onNode(hasClickAction() and hasText("Teaser")).performClick()
                            else -> {}
                        }
                        waitForIdle()
                    }
                    assertEquals(hidden.map(sends::getValue), calls, "⋯'s entries $at")
                    used += hidden
                }
            }
        }
        assertEquals(labels.keys, used, "some actions never went behind ⋯")
    }

    /** On a tablet (1024 dp, the details' column beside the backdrop) every action has its button and there's no ⋯. */
    @Test
    fun onATabletTheRowShowsEveryActionWithoutMore() = runComposeUiTest {
        detail(1024, mutableListOf())
        for (label in labels.values) assertTrue(exists(hasClickAction() and hasContentDescription(label)), "the tablet's row has no $label")
        assertTrue(!exists(more), "the tablet's row has ⋯")
    }

    /**
     * The user's rating, where a tracker rates the title, is an entry of ⋯'s menu ("Your rating: 8"), last, also on a
     * tablet whose row has room for every button; it opens the rating picker. The score also ends the ratings row,
     * read-only: no button there.
     */
    @Test
    fun theRatingIsBehindMoreAndItsScoreIsReadOnly() {
        val label = T.detail.userRating.label("8")
        val rated = state.copy(userRating = DetailUserRating(text = "8", label = label, score = 8, trackers = emptyList()))
        for (width in listOf(412, 1024)) runComposeUiTest {
            setContent {
                TortugaTheme {
                    Box(Modifier.size(width.dp, 1200.dp)) {
                        AdaptiveLayout(FormFactor.HANDHELD) { OverlayHostBox { Screen(rated, mutableListOf()) } }
                    }
                }
            }
            assertTrue(exists(hasContentDescription(label)), "the ratings row doesn't end with the score at $width dp")
            assertFalse(exists(hasClickAction() and hasContentDescription(label)), "the score in the ratings row is a button at $width dp")
            onNode(more).performClick()
            assertEquals(label, menuEntries().last(), "⋯'s menu doesn't end with the rating at $width dp")
            onNode(hasClickAction() and hasText(label) and hasAnyAncestor(isPopup())).performClick()
            waitForIdle()
            assertTrue(exists(hasText(T.detail.userRating.hint)), "the rating's entry doesn't open the picker at $width dp")
        }
    }

    /**
     * Desktop windows in the app's frame (the 80 dp rail), from the narrowest the app allows (640 dp) past the switch to
     * the wide layout (920 dp: the details' column beside the episodes) to the default 1280 dp, at 100 % and 150 %, with
     * a resumed series (Play "S1E10 · 15:36", and the longest Resume label): Play, the round buttons (40 dp each) and ⋯
     * lie side by side inside the window's content (past the rail, 24 dp in from the page's edges) and, beside the
     * episodes, before them; every action without a button is behind ⋯, the row keeping the most important
     * ([fitActions]).
     */
    @Test
    fun inDesktopWindowsTheRowStaysInsideTheWindowAndTheRestBehindMore() {
        val label = "S1E10 · 15:36"
        val owner = series.copy(primaryAction = DetailPrimaryAction(label, "tt7366338:12:108", resume = true, episodeTitle = "Vichnaya Pamyat", progress = 0.3, spokenLabel = T.detail.resumeEpisode("S1E10", "15:36")))
        val slack = 0.5.dp
        for (density in listOf(1f, 1.5f)) {
            for (width in listOf(640, 760, 919, 920, 1280)) {
                for (title in listOf(owner, series)) {
                    runSkikoComposeUiTest(size = Size(width * density, 800 * density), density = Density(density)) {
                        setContent {
                            TortugaTheme {
                                Box(Modifier.size(width.dp, 800.dp)) {
                                    AdaptiveLayout(FormFactor.DESKTOP) {
                                        NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.BOARD, {}, showNavigation = true) {
                                            Screen(title, mutableListOf())
                                        }
                                    }
                                }
                            }
                        }
                        val play = bounds(hasText(title.primaryAction!!.label))
                        val shown = labels.filterValues { onLine(play, hasContentDescription(it)) != null }.keys.toList()
                        val hidden = title.actions - shown
                        val moreButton = onLine(play, hasContentDescription(T.detail.moreActions))
                        val buttons = shown.map { onLine(play, hasContentDescription(labels.getValue(it)))!! } + listOfNotNull(moreButton)
                        val row = listOf(play) + buttons
                        val at = "in a $width dp window at ${(density * 100).toInt()} % with Play '${title.primaryAction!!.label}': $row"
                        assertEquals(hidden.isNotEmpty(), moreButton != null, "⋯ and the actions without a button ($hidden) disagree $at")
                        assertEquals(title.fitActions(if (hidden.isEmpty()) shown.size else shown.size + 1), DetailActionFit(shown, hidden), "the row keeps the wrong actions $at")
                        for (control in row) {
                            assertTrue(control.left >= 104.dp - slack && control.right <= (width - 24).dp + slack, "a control lies at ${control.left}–${control.right}, outside the window's content $at")
                        }
                        assertTrue(row.zipWithNext().all { (a, b) -> a.right <= b.left + slack }, "the row's controls overlap $at")
                        for (button in buttons) {
                            assertTrue(abs((button.width - 40.dp).value) < slack.value && abs((button.height - 40.dp).value) < slack.value, "a round button is ${button.width} × ${button.height}, not 40 dp $at")
                        }
                        if (width >= 920) {
                            val episodes = onAllNodes(hasClickAction() and hasText("Vichnaya Pamyat", substring = true))
                            val lefts = List(episodes.fetchSemanticsNodes().size) { episodes[it].getUnclippedBoundsInRoot().left }
                            assertTrue(lefts.isNotEmpty() && lefts.all { it >= row.last().right }, "the row runs under the episodes (at $lefts) $at")
                        }
                    }
                }
            }
        }
    }

    /**
     * On TV the row has room for every action (a 960 dp screen, a series with all of them): Right from Play walks them
     * in the TV's order ([tvActions]), nothing behind ⋯, and each does what it says.
     */
    @Test
    fun onTvRightFromPlayWalksEveryActionInTheTvsOrder() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val calls = mutableListOf<String>()
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { Screen(series, calls) } }
                }
            }
        }
        onNode(isFocused()).assert(hasText(resume))
        for (action in series.tvActions) {
            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            onNode(isFocused()).assert(hasContentDescription(labels.getValue(action)))
        }
        assertFalse(exists(hasContentDescription(T.detail.moreActions)), "TV's row has a ⋯")
        onNode(hasClickAction() and hasContentDescription(labels.getValue(DetailAction.WATCHED))).performClick()
        runOnIdle { assertEquals(listOf(sends.getValue(DetailAction.WATCHED)), calls) }
    }

    /** What each action sends when used. */
    private val sends = mapOf(
        DetailAction.CHOOSE_STREAM to "chooseStream",
        DetailAction.LIBRARY to "library",
        DetailAction.WATCHED to "watched:true",
        DetailAction.DOWNLOAD to "download:pause",
        DetailAction.TRAILER to "trailer:b",
    )

    /** Play's width with all the room it wants: on a tablet (1024 dp) at [fontScale], its label whole. */
    private fun tabletPlayWidth(fontScale: Float): Dp {
        var width = 0.dp
        runComposeUiTest {
            detail(1024, mutableListOf(), fontScale)
            assertFalse(playLabelCut(), "Play's label is cut short on a tablet at font scale $fontScale")
            width = bounds(hasText(resume)).width
        }
        return width
    }

    /**
     * Whether Play's label is cut short: it doesn't fit its room even at the smallest size a button's label shrinks to
     * ([MIN_LABEL_SIZE]). The semantics lay the text out at its full size, so it is measured again in the same room.
     */
    private fun ComposeUiTest.playLabelCut(): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText(resume), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val label = layouts.single().layoutInput
        return TextMeasurer(label.fontFamilyResolver, label.density, label.layoutDirection)
            .measure(label.text, label.style.copy(fontSize = MIN_LABEL_SIZE), label.overflow, label.softWrap, label.maxLines, constraints = label.constraints)
            .hasVisualOverflow
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Where the control matching [matcher] is drawn (on a phone it takes taps in a larger square around that). */
    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): DpRect =
        onNode(hasClickAction() and matcher).getDrawnBoundsInRoot()

    /** Where the control matching [matcher] on [play]'s line (an episode row has a ⋯ of its own) is drawn, if any. */
    private fun ComposeUiTest.onLine(play: DpRect, matcher: SemanticsMatcher): DpRect? {
        val nodes = onAllNodes(hasClickAction() and matcher)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it].getDrawnBoundsInRoot() }.firstOrNull { (it.top + it.bottom) / 2 in play.top..play.bottom }
    }

    /** The open menu's entries' words, in order. */
    private fun ComposeUiTest.menuEntries(): List<String> =
        onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        }

    /** Detail of [state] in a phone or tablet window [width] dp wide, the system's font size at [fontScale]. */
    private fun ComposeUiTest.detail(width: Int, calls: MutableList<String>, fontScale: Float = 1f) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            TortugaTheme {
                Box(Modifier.size(width.dp, 1200.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) { Screen(state, calls) }
                }
            }
        }
    }

    /** Detail of [state], each intent of the action row recorded in [calls]. */
    @Composable
    private fun Screen(state: DetailUiState, calls: MutableList<String>) = DetailScreen(
        state,
        testDetailIntents(
            chooseStream = { calls += "chooseStream" },
            openTrailer = { calls += "trailer:$it" },
            toggleLibrary = { calls += "library" },
            markWatched = { calls += "watched:$it" },
            pressDownload = { calls += "press" },
            downloadAction = { calls += "download:$it" },
        ),
        onBack = null,
    )
}
