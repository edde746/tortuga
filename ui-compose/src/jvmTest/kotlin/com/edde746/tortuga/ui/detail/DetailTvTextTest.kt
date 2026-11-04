package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.itemStateText
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailDownload
import com.edde746.tortuga.presentation.detail.DetailDownloadState
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailLink
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailTrailer
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.interfaceLanguages
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android TV's Detail page keeps every text inside the screen's safe area and off the other sections at the system's
 * font sizes (1 and the largest, 1.3), with the longest strings it shows: the focused action's label stays under the
 * action row, from Play's start edge to the screen's padding; at each of the page's stops a section's text shows whole
 * on screen and never under the title's name at the top.
 * Each runs on a 1080p TV (960 × 540 dp) in the app's frame beside the TV column, the arrow keys as the D-pad.
 */
@OptIn(ExperimentalTestApi::class)
class DetailTvTextTest {
    private val names = listOf("Karl Urban", "Jack Quaid", "Antony Starr", "Erin Moriarty", "Eric Kripke")

    private fun episode(number: Int) = DetailEpisode(
        videoId = "tt1190634:2:$number",
        code = "S2E$number",
        number = "$number",
        title = "Episode $number",
        numberedTitle = if (number == 5) "5. We Gotta Go Now" else "$number. Episode $number",
        moreLabel = T.detail.moreActionsNamed(if (number == 5) "5. We Gotta Go Now" else "$number. Episode $number"),
        infoLine = "Sep 18, 2020 · 1 h 4 min",
        overview = "VoughtStudios is pleased to announce that filming has begun on #DawnOfTheSeven. 12 years of VCU movies " +
            "have led to this. If you like movies about One Hero, you'll love a movie about Seven Heroes.",
        thumbnail = null,
        progress = if (number == 5) 0.55 else null,
        blurred = false,
        watched = number < 5,
        upcoming = false,
        nextUp = number == 5,
        watchedActionText = T.detail.markWatched,
        detailLine = if (number == 5) T.detail.infoJoin("Sep 18, 2020 · 1 h 4 min", "28 min left") else "Sep 18, 2020 · 1 h 4 min",
        stateText = itemStateText(null, null, if (number == 5) 0.55 else null, watched = number < 5),
    )

    /**
     * The Boys as a device shows it, resuming S2E5: a logo (that never loads here, so its box holds the name),
     * every action (the download last), five seasons, eight episodes, the names.
     */
    private val boys = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "series",
            imdbId = null,
            title = "The Boys",
            poster = null,
            background = null,
            logo = "https://logos.test/the-boys.png",
            posterShape = PosterShape.POSTER,
            infoLine = "2019–2026 · 1 h 4 min · Action, Comedy",
            ratings = emptyList(),
            overview = "A group of vigilantes set out to take down corrupt superheroes who abuse their superpowers.",
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
            castLine = "With Karl Urban, Jack Quaid, Antony Starr",
            castAndCrew = names.mapIndexed { index, name -> DetailLink("link:$index", name) },
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction(
            "S2E5 · 32:44",
            "tt1190634:2:5",
            resume = true,
            episodeTitle = "We Gotta Go Now",
            progress = 0.55,
        ),
        inLibrary = true,
        libraryActionText = T.detail.removeFromLibrary,
        watched = false,
        watchedActionText = T.detail.markAllWatched,
        trailers = listOf(DetailTrailer("a", "Trailer")),
        trailerActionText = "Trailer",
        seasons = (1..5).map { DetailSeason(it, "Season $it", selected = it == 2, watched = it == 1) },
        episodes = (1..8).map { episode(it) },
        chooseStreamText = T.detail.chooseStream,
        download = DetailDownload(DetailDownloadState.DOWNLOADING, "Downloading 45%", 0.45, opensMenu = false, menuTitle = T.detail.download.menuTitle, menu = emptyList()),
        currentEpisode = "tt1190634:2:5",
    )

    /** Where the screen's padding ends: the page's text and controls stay before it. */
    private val safeEnd = 960.dp - 48.dp

    /**
     * The line under the row (Play's episode's title: as long as the longest on a device, and one longer than any)
     * and each round button's label (the longest each takes) in every language, walked to with Right: one line from
     * Play's start edge to the screen's padding, under the row and above the seasons.
     */
    @Test
    fun onTvEachActionsLabelStaysUnderTheRowInsideTheScreen() {
        val lines = listOf(
            "I'm Marlo! The Girl Who's Gonna Be Queen of the Docks!",
            "The Strongest Generation Clashes on the Roof of Onigashima! ".repeat(3).trim(),
        )
        val wrong = mutableListOf<String>()
        try {
            for (language in interfaceLanguages) {
                T.locale = language
                val texts = boys.copy(
                    chooseStreamText = T.detail.chooseStream,
                    trailerActionText = T.detail.trailer,
                    libraryActionText = T.detail.removeFromLibrary,
                    watchedActionText = T.detail.markAllUnwatched,
                    download = boys.download!!.copy(label = T.detail.download.episode.failed),
                )
                for (fontScale in listOf(1f, 1.3f)) for (line in lines) runComposeUiTest {
                    val state = texts.copy(primaryAction = boys.primaryAction!!.copy(episodeTitle = line))
                    page(state, fontScale)
                    val play = onNode(isFocused()).getUnclippedBoundsInRoot()
                    val seasons = onNode(hasClickAction() and hasText("Season 1")).getUnclippedBoundsInRoot()
                    val labels = listOfNotNull(line, state.chooseStreamText, state.trailerActionText, state.libraryActionText, state.watchedActionText, state.download!!.label)
                    for ((index, label) in labels.withIndex()) {
                        if (index > 0) press(Key.DirectionRight)
                        val at = "\"$label\" in $language at font scale $fontScale"
                        val shown = onNode(hasText(label))
                        shown.assertIsDisplayed()
                        val bounds = shown.getUnclippedBoundsInRoot()
                        if (bounds.left < play.left) wrong += "$at starts at ${bounds.left}, before Play's start edge (${play.left})"
                        if (bounds.right > safeEnd) wrong += "$at ends at ${bounds.right}, past the screen's padding ($safeEnd)"
                        if (bounds.top < play.bottom || bounds.bottom > seasons.top) wrong += "$at lies at ${bounds.top}–${bounds.bottom}, not between the row (${play.bottom}) and the seasons (${seasons.top})"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * The title block, then Down from Play to the seasons, the episodes and Cast & crew: at each, every text of the
     * sections under the title block (a season, the episode's details, a card's code, Cast & crew and its names) that
     * shows lies whole between the screen's top and bottom edges, and none under the title's name at the top.
     */
    @Test
    fun onTvEachStopShowsItsSectionsWholeAndClearOfTheName() {
        val texts = boys.seasons.map { it.label } + listOf("5. We Gotta Go Now", "Sep 18, 2020 · 1 h 4 min · 28 min left", boys.episodes[4].overview!!) +
            boys.episodes.mapNotNull { it.code } + T.detail.castAndCrew + names
        val wrong = mutableListOf<String>()
        for (fontScale in listOf(1f, 1.3f)) runComposeUiTest {
            page(boys, fontScale)
            for (stop in listOf("the title block", "the seasons", "the episodes", "Cast & crew")) {
                if (stop != "the title block") press(Key.DirectionDown)
                val at = "at $stop, font scale $fontScale"
                val shown = texts.associateWith { shownBounds(it) }
                for ((text, all) in shown) for (bounds in all) {
                    if (bounds.top < 0.dp || bounds.bottom > 540.dp) wrong += "\"$text\" (${bounds.top}–${bounds.bottom}) runs off the screen $at"
                }
                if (stop == "the title block") continue
                val name = shownBounds("The Boys").filter { it.top >= 0.dp }.minByOrNull { it.top }
                if (name == null) {
                    wrong += "no name at the top $at"
                    continue
                }
                for ((text, all) in shown) for (bounds in all) {
                    val overlap = bounds.left < name.right && name.left < bounds.right && bounds.top < name.bottom && name.top < bounds.bottom
                    if (overlap) wrong += "\"$text\" (${bounds.top}–${bounds.bottom}) lies under the name (${name.top}–${name.bottom}) $at"
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The bounds of each node reading [text] that shows on screen. */
    private fun ComposeUiTest.shownBounds(text: String): List<DpRect> {
        val nodes = onAllNodes(hasText(text))
        return List(nodes.fetchSemanticsNodes().size) { nodes[it] }
            .filter { runCatching { it.assertIsDisplayed() }.isSuccess }
            .map { it.getUnclippedBoundsInRoot() }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /** The page of [state] in the app's frame beside the TV column, the system's font size at [fontScale]. */
    private fun ComposeUiTest.page(state: DetailUiState, fontScale: Float) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.BOARD, {}, showNavigation = true) {
                                OverlayHostBox {
                                    DetailScreen(
                                        state,
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
        waitForIdle()
    }
}
