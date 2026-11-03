package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailFact
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailLink
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailRelated
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Detail's "More like this" row in each of its states, on the one-column page (phones, narrow windows), the wide page
 * (tablets, desktop windows) and Android TV: the titles in the order the store merged them (the addon's, then
 * Trakt's), an addon's alone, the heading with the line that says none were found, and no row at all; always before
 * the title's information. A card opens its title; its menu's entries reach the store. On TV a remote reaches the row
 * from the section above and leaves it by Up to that section, Down to Cast & crew and back up to the card it left.
 */
@OptIn(ExperimentalTestApi::class)
class DetailRelatedTest {
    private fun card(id: String, title: String, year: String? = null, type: String = "movie") =
        MetaCard(id, type, title, year, rating = null, posterUrl = null, posterShape = PosterShape.POSTER, backgroundUrl = null)

    /** The addon's two titles, then Trakt's two that the addon didn't name. */
    private val merged = DetailRelated(
        T.detail.related.title,
        loading = false,
        cards = listOf(
            card("tortuga-hero-plain", "Tortuga Hero Plain Test"),
            card("tt0068646", "The Godfather", "1972"),
            card("tt0061512", "Cool Hand Luke", "1967"),
            card("tt0099685", "Goodfellas", "1990"),
        ),
        emptyText = null,
    )

    /** A title without an IMDb id: the addon's titles alone. */
    private val addonOnly = DetailRelated(
        T.detail.related.title,
        loading = false,
        cards = listOf(card("tortuga-hero-wide", "Tortuga Hero Wide Test", type = "series"), card("tortuga-seasons", "Tortuga Seasons Test", type = "series")),
        emptyText = null,
    )

    private val empty = DetailRelated(T.detail.related.title, loading = false, cards = emptyList(), emptyText = T.detail.related.noneFound)

    private val loading = DetailRelated(T.detail.related.title, loading = true, cards = emptyList(), emptyText = null)

    private val names = listOf("Henry Fonda", "Lee J. Cobb", "Sidney Lumet")

    private val movie = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "movie",
            imdbId = null,
            title = "Tortuga Related Test",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = "1957 · 1 h 36 min",
            ratings = emptyList(),
            overview = "A title for Detail's More like this.",
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = listOf(DetailFact("Released", "1957", linked = false)),
            castAndCrew = names.mapIndexed { index, name -> DetailLink("link:$index", name) },
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("Play", "tortuga-related", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
        related = merged,
    )

    private val series = movie.copy(
        body = DetailBody.Loaded((movie.body as DetailBody.Page).header.copy(type = "series", title = "Tortuga Series Test")),
        primaryAction = DetailPrimaryAction("S1E1", "tortuga-series:1:1", resume = false, episodeTitle = "Episode 1", progress = null, spokenLabel = "Play S1E1"),
        seasons = listOf(DetailSeason(1, "Season 1", selected = true, watched = false)),
        episodes = (1..3).map { number ->
            DetailEpisode(
                videoId = "tortuga-series:1:$number",
                code = "S1E$number",
                number = "$number",
                title = "Episode $number",
                numberedTitle = "$number. Episode $number",
                moreLabel = T.detail.moreActionsNamed("$number. Episode $number"),
                infoLine = null,
                overview = null,
                thumbnail = null,
                progress = null,
                blurred = false,
                watched = false,
                upcoming = false,
                nextUp = number == 1,
                watchedActionText = T.detail.markWatched,
                detailLine = null,
                stateText = null,
            )
        },
        currentEpisode = "tortuga-series:1:1",
        related = addonOnly,
    )

    /**
     * The one-column page: each state of the row between the description (a movie) or the episodes (a series) and the
     * information. The cards run left to right in the row's order; the empty line sits under the heading; with no row
     * there is no heading either; while loading, the heading over blank cards.
     */
    @Test
    fun onTheOneColumnPageEachStateComesBeforeTheInformation() {
        val cases = listOf(
            Triple("merged", movie, merged.cards.map { it.title }),
            Triple("addon only", series, addonOnly.cards.map { it.title }),
            Triple("empty", movie.copy(related = empty), emptyList()),
            Triple("loading", movie.copy(related = loading), emptyList()),
        )
        for ((case, state, titles) in cases) tall {
            page(FormFactor.HANDHELD, 700, TALL, state)
            val heading = bounds(hasText(T.detail.related.title))
            val information = bounds(hasText("Information"))
            assertTrue(heading.bottom <= information.top, "$case: the row's heading at ${heading.top}–${heading.bottom} isn't above Information at ${information.top}")
            if (state.hasEpisodes) {
                val lastEpisode = bounds(hasText("3. Episode 3"))
                assertTrue(heading.top >= lastEpisode.bottom, "$case: the row starts at ${heading.top}, over the last episode ending at ${lastEpisode.bottom}")
            } else {
                val play = bounds(hasText("Play") and hasClickAction())
                assertTrue(heading.top >= play.bottom, "$case: the row starts at ${heading.top}, over Play ending at ${play.bottom}")
            }
            val cards = titles.map { bounds(hasText(it)) }
            for ((index, title) in titles.withIndex()) {
                val at = cards[index]
                assertTrue(at.top >= heading.bottom && at.bottom <= information.top, "$case: $title at ${at.top}–${at.bottom} isn't between the heading and Information")
                if (index > 0) assertTrue(at.left > cards[index - 1].right, "$case: $title isn't right of ${titles[index - 1]}")
            }
            for (other in (merged.cards + addonOnly.cards).map { it.title } - titles.toSet()) {
                assertTrue(onAllNodes(hasText(other)).fetchSemanticsNodes().isEmpty(), "$case: $other shows")
            }
            val line = onAllNodes(hasText(T.detail.related.noneFound)).fetchSemanticsNodes()
            if (state.related === empty) {
                val shown = bounds(hasText(T.detail.related.noneFound))
                assertTrue(shown.top >= heading.bottom && shown.bottom <= information.top, "$case: the empty line isn't under the heading, above Information")
            } else {
                assertTrue(line.isEmpty(), "$case: \"${T.detail.related.noneFound}\" shows")
            }
        }
    }

    /** No source answered: no heading, no line, the information as before. */
    @Test
    fun withoutARowThePageShowsNoHeading() = tall {
        page(FormFactor.HANDHELD, 700, TALL, movie.copy(related = null))
        assertTrue(onAllNodes(hasText(T.detail.related.title)).fetchSemanticsNodes().isEmpty(), "the heading shows without a row")
        assertTrue(onAllNodes(hasText(T.detail.related.noneFound)).fetchSemanticsNodes().isEmpty())
        bounds(hasText("Information"))
    }

    /** A tap on a card opens that title. */
    @Test
    fun aCardOpensItsTitle() = tall {
        val opened = mutableListOf<String>()
        page(FormFactor.HANDHELD, 700, TALL, movie, onOpen = { opened += it.id })
        onNode(hasContentDescription("The Godfather") and hasClickAction()).performClick()
        runOnIdle { assertEquals(listOf("tt0068646"), opened) }
    }

    /**
     * The wide page (a desktop window): the row in the details column before Information, its heading and first card
     * at the column's text edge; a right click on a card opens the Board's menu, whose Add to library reaches the store
     * for that card.
     */
    @Test
    fun onTheWidePageTheRowSitsInTheDetailsColumnAndTakesARightClick() = tall {
        val picked = mutableListOf<Pair<String, MediaAction>>()
        page(FormFactor.DESKTOP, 1280, TALL, movie, onMenu = { card, action -> picked += card.id to action })
        val heading = bounds(hasText(T.detail.related.title))
        val information = bounds(hasText("Information"))
        val first = bounds(hasText("Tortuga Hero Plain Test"))
        assertTrue(heading.bottom <= information.top && first.bottom <= information.top, "the row isn't above Information: heading $heading, first card $first, Information $information")
        assertTrue(abs((heading.left - information.left).value) <= 1f, "the heading starts at ${heading.left}, the column's text at ${information.left}")
        assertTrue(abs((first.left - information.left).value) <= 1f, "the first card's caption starts at ${first.left}, the column's text at ${information.left}")
        onNode(hasContentDescription("Cool Hand Luke") and hasClickAction()).performMouseInput { rightClick() }
        val add = merged.cards[2].menu.first { it.action == MediaAction.ADD_TO_LIBRARY }.label
        onNode(hasText(add) and hasClickAction()).performClick()
        runOnIdle { assertEquals(listOf("tt0061512" to MediaAction.ADD_TO_LIBRARY), picked) }
    }

    /**
     * Android TV, a movie: Down from Play lands on the row's first card; Right moves along it; Up leaves it for the
     * actions; Down comes back to the card it left; Down again goes to Cast & crew, and Up from there back to that card.
     */
    @Test
    fun onTvAMovieRowIsReachedFromTheActionsAndLeftByUp() = runComposeUiTest {
        val probe = FocusedProbe()
        page(FormFactor.TV, 960, 540, movie, probe = probe)
        assertEquals("detail.play", probe.focused, "the page doesn't open on Play")
        val moves = listOf(
            Key.DirectionDown to "detail.related.item.0",
            Key.DirectionRight to "detail.related.item.1",
            Key.DirectionUp to "detail.play",
            Key.DirectionDown to "detail.related.item.1",
            Key.DirectionDown to "detail.cast.0",
            Key.DirectionUp to "detail.related.item.1",
        )
        walk(probe, moves)
    }

    /**
     * Android TV, a series: Down from Play to the seasons, the episodes, then the row's first card; Up from it goes back
     * to the episode row, on the current episode, and Down returns to the card.
     */
    @Test
    fun onTvASeriesRowComesAfterTheEpisodesAndUpGoesBackToThem() = runComposeUiTest {
        val probe = FocusedProbe()
        page(FormFactor.TV, 960, 540, series, probe = probe)
        assertEquals("detail.play", probe.focused, "the page doesn't open on Play")
        val moves = listOf(
            Key.DirectionDown to "detail.season.0",
            Key.DirectionDown to "detail.episode.0",
            Key.DirectionDown to "detail.related.item.0",
            Key.DirectionUp to "detail.episode.0",
            Key.DirectionDown to "detail.related.item.0",
            Key.DirectionDown to "detail.cast.0",
            Key.DirectionUp to "detail.related.item.0",
        )
        walk(probe, moves)
    }

    /**
     * Android TV, a series whose More like this has no card (still loading, or none found): Down from the episode row
     * passes over it to Cast & crew, and Up from there comes back past it to the episode row, on the current episode.
     */
    @Test
    fun onTvUpFromCastPassesOverARowWithoutCardsToTheEpisodes() {
        for ((case, related) in listOf("loading" to loading, "empty" to empty)) {
            runComposeUiTest {
                val probe = FocusedProbe()
                page(FormFactor.TV, 960, 540, series.copy(related = related), probe = probe)
                assertEquals("detail.play", probe.focused, "$case: the page doesn't open on Play")
                val moves = listOf(
                    Key.DirectionDown to "detail.season.0",
                    Key.DirectionDown to "detail.episode.0",
                    Key.DirectionDown to "detail.cast.0",
                    Key.DirectionUp to "detail.episode.0",
                )
                walk(probe, moves, case)
            }
        }
    }

    /** Each of [moves] from where the last left focus; every wrong one is listed, after [case] where one is named. */
    private fun ComposeUiTest.walk(probe: FocusedProbe, moves: List<Pair<Key, String>>, case: String? = null) {
        val wrong = mutableListOf<String>()
        var from = probe.focused
        for ((key, expected) in moves) {
            onRoot().performKeyInput { pressKey(key) }
            waitForIdle()
            if (probe.focused != expected) wrong += "${case?.let { "$it: " }.orEmpty()}$key from $from went to ${probe.focused}, not $expected"
            from = probe.focused
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun ComposeUiTest.bounds(matcher: SemanticsMatcher): DpRect = onNode(matcher).getUnclippedBoundsInRoot()

    /**
     * A test in a window [TALL] dp high at density 1, so the one-column page's lazy list composes the whole page, the
     * row and Information included, and the wide page's column lies on screen for the pointer.
     */
    private fun tall(block: ComposeUiTest.() -> Unit) {
        runSkikoComposeUiTest(size = Size(1280f, TALL.toFloat()), density = Density(1f)) { block() }
    }

    private companion object {
        const val TALL = 3000
    }

    /**
     * The page of [state] in the app's frame ([width] × [height] dp, the side navigation beside it), the arrow keys as
     * the D-pad; a card's opening goes to [onOpen], its menu's picks to [onMenu].
     */
    private fun ComposeUiTest.page(
        formFactor: FormFactor,
        width: Int,
        height: Int,
        state: DetailUiState,
        probe: FocusedProbe = FocusedProbe(),
        onOpen: (MetaCard) -> Unit = {},
        onMenu: (MetaCard, MediaAction) -> Unit = { _, _ -> },
    ) {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(width.dp, height.dp)) {
                        AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                            NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.BOARD, {}, showNavigation = formFactor != FormFactor.HANDHELD) {
                                OverlayHostBox {
                                    DetailScreen(
                                        state,
                                        testDetailIntents(openRelated = onOpen, relatedMenu = onMenu),
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
