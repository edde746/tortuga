package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailAction
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailDownload
import com.edde746.tortuga.presentation.detail.DetailDownloadState
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailEpisodeRange
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailLink
import com.edde746.tortuga.presentation.detail.DetailPlayerApp
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailRating
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailTrackerScore
import com.edde746.tortuga.presentation.detail.DetailTrailer
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.DetailUserRating
import com.edde746.tortuga.presentation.detail.RatingLogo
import com.edde746.tortuga.presentation.detail.tvActions
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android TV's Detail page keeps Left and Right within a section: each row of the page, reached by D-pad
 * as a remote does (Up from Play to More and on to the user's rating, the actions where the page opens, Down to the
 * player app's answers, or to the seasons, a long season's ranges, the episodes and Cast & crew), is walked off its end
 * and off its start, in the app's frame beside the TV column. Right past a row's end leaves focus on its last element
 * (the actions', the seasons', the ranges' and Cast & crew's last went up to More); Left past its start goes to the
 * side navigation, the one thing beside the page, but from More, at the end of the description, and the user's rating,
 * at the end of the ratings, which keep it (More's went down to Play). Select on an episode card plays it; on the
 * user's rating it opens the picker on its stars, and focus comes back to the rating as the picker closes. The page
 * opens on Play, also when Play comes after the title; focus moved elsewhere before then stays, and the line under the
 * actions with it. The player app's answers and the seasons take focus in one order whatever the answers' labels.
 */
@OptIn(ExperimentalTestApi::class)
class DetailTvRowsTest {
    private val names = listOf("Karl Urban", "Jack Quaid", "Antony Starr", "Eric Kripke")

    /** 52 episodes: two ranges, 1-50 and 51-52. */
    private val episodes = (1..52).map { number ->
        DetailEpisode(
            videoId = "tt1190634:2:$number",
            code = "S2E$number",
            number = "$number",
            title = "Episode $number",
            numberedTitle = "$number. Episode $number",
            moreLabel = T.detail.moreActionsNamed("$number. Episode $number"),
            infoLine = "Sep 4, 2020 · 1 h",
            overview = "What happens in episode $number.",
            thumbnail = null,
            progress = null,
            blurred = false,
            watched = false,
            upcoming = false,
            nextUp = number == 1,
            watchedActionText = T.detail.markWatched,
            detailLine = "Sep 4, 2020 · 1 h",
            stateText = null,
        )
    }

    /** A series with every row: every action (the download last), three seasons, two ranges, the episodes, the names. */
    private val series = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "series",
            imdbId = null,
            title = "The Boys",
            poster = null,
            background = null,
            logo = null,
            posterShape = PosterShape.POSTER,
            infoLine = "2019 · TV-MA · 4 seasons",
            ratings = emptyList(),
            // Three lines and more: More ends the third, past the actions' end, as Dune's does.
            overview = "A group of vigilantes known informally as The Boys set out to take down corrupt superheroes with " +
                "no more than blue-collar grit and a willingness to fight dirty, while the heroes' company hides what " +
                "they do behind its brand, its films and its lawyers, season after season.",
            linkGroups = emptyList(),
            factsTitle = "Information",
            facts = emptyList(),
            castLine = "With Karl Urban, Jack Quaid, Antony Starr",
            castAndCrew = names.mapIndexed { index, name -> DetailLink("link:$index", name) },
        )),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("S2E1", "tt1190634:2:1", resume = false, episodeTitle = "Episode 1", progress = null, spokenLabel = "Play S2E1"),
        inLibrary = true,
        libraryActionText = T.detail.removeFromLibrary,
        watched = false,
        watchedActionText = T.detail.markAllWatched,
        trailers = listOf(DetailTrailer("a", "Trailer")),
        trailerActionText = "Trailer",
        seasons = (1..3).map { DetailSeason(it, "Season $it", selected = it == 2, watched = false) },
        episodes = episodes,
        chooseStreamText = T.detail.chooseStream,
        download = DetailDownload(DetailDownloadState.DOWNLOADING, "Downloading 45%", 0.45, opensMenu = false, menuTitle = T.detail.download.menuTitle, menu = emptyList()),
        currentEpisode = "tt1190634:2:1",
        episodeRanges = listOf(DetailEpisodeRange("1-50", 0, 50), DetailEpisodeRange("51-52", 50, 52)),
    )

    /** The series with a tracker that rates it, not rated yet: the action row ends with the rating's button. */
    private val rateable = series.copy(
        userRating = DetailUserRating(
            text = null,
            label = T.detail.userRating.rateLabel,
            score = null,
            trackers = listOf(DetailTrackerScore(TrackerService.TRAKT, "Trakt", T.detail.userRating.notRated, busy = false, attention = false)),
        ),
    )

    /** [rateable] rated 8: the score also ends the ratings, read-only. */
    private val rated = rateable.copy(userRating = rateable.userRating?.copy(text = "8", label = T.detail.userRating.label("8"), score = 8))

    /** Each action's harness name. */
    private val probes = mapOf(
        DetailAction.CHOOSE_STREAM to "detail.chooseStream",
        DetailAction.TRAILER to "detail.trailer",
        DetailAction.LIBRARY to "detail.library",
        DetailAction.WATCHED to "detail.watched",
        DetailAction.DOWNLOAD to "detail.download",
        DetailAction.RATE to "detail.rate",
    )

    /**
     * A row of the page: how a remote reaches it from where the page opens, its elements' harness names, the one it
     * lands on, and whether Left past its start goes to the side navigation (else focus stays).
     */
    private class PageRow(
        val name: String,
        val reach: List<Key>,
        val elements: List<String>,
        val entry: Int = 0,
        val toNavigation: Boolean = true,
        val state: DetailUiState? = null,
    )

    private val rows = listOf(
        PageRow("the title block", listOf(Key.DirectionUp), listOf("detail.more"), toNavigation = false),
        PageRow("the actions", emptyList(), listOf("detail.play") + series.tvActions.map { probes.getValue(it) }),
        PageRow("the actions with the rating", emptyList(), listOf("detail.play") + rateable.tvActions.map { probes.getValue(it) }, state = rateable),
        PageRow(
            "the player app's answers",
            listOf(Key.DirectionDown),
            listOf("detail.playerApp.markWatched", "detail.playerApp.notYet"),
            state = series.copy(playerApp = DetailPlayerApp("Opened in VLC. Finished watching S2E1?", "Mark as watched", "Not yet")),
        ),
        PageRow("the seasons", listOf(Key.DirectionDown), series.seasons.indices.map { "detail.season.$it" }, entry = 1),
        PageRow("the ranges", List(2) { Key.DirectionDown }, series.episodeRanges.indices.map { "detail.range.$it" }),
        PageRow("the episodes", List(3) { Key.DirectionDown }, episodes.indices.map { "detail.episode.$it" }),
        PageRow("Cast & crew", List(4) { Key.DirectionDown }, names.indices.map { "detail.cast.$it" }),
    )

    /**
     * The page of [state] in the app's frame on a 1080p TV (960 × 540 dp), the TV column beside it, the arrow keys as the
     * D-pad; the episodes a card's Select plays go to [opened], the scores the rating picker gives to [rated].
     */
    private fun ComposeUiTest.page(
        probe: FocusedProbe,
        opened: MutableList<String> = mutableListOf(),
        rated: MutableList<Int?> = mutableListOf(),
        state: () -> DetailUiState,
    ) {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.BOARD, {}, showNavigation = true) {
                                OverlayHostBox {
                                    DetailScreen(
                                        state(),
                                        testDetailIntents(openEpisode = { opened += it }, rate = { rated += it }),
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

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /**
     * Each row on a page of its own, walked along to its last element and Right once more, then on another back from
     * its entry to its first and Left once more. Every row's wrong move is listed, not only the first.
     */
    @Test
    fun onTvLeftAndRightStayInEachRow() {
        val wrong = mutableListOf<String>()
        for (row in rows) {
            for (towardsEnd in listOf(true, false)) runComposeUiTest {
                val probe = FocusedProbe()
                page(probe) { row.state ?: series }
                assertEquals("detail.play", probe.focused, "the page doesn't open on Play")
                for (key in row.reach) press(key)
                assertEquals(row.elements[row.entry], probe.focused, "a remote doesn't reach ${row.name}")
                val key = if (towardsEnd) Key.DirectionRight else Key.DirectionLeft
                val walk = if (towardsEnd) row.elements.drop(row.entry + 1) else row.elements.take(row.entry).reversed()
                for (element in walk) {
                    press(key)
                    assertEquals(element, probe.focused, "a move along ${row.name}")
                }
                val from = probe.focused
                press(key)
                if (towardsEnd && probe.focused != from) wrong += "Right past the end of ${row.name} ($from) went to ${probe.focused}"
                if (!towardsEnd && row.toNavigation && probe.focused?.startsWith("nav.") != true) wrong += "Left past the start of ${row.name} ($from) went to ${probe.focused}, not the side navigation"
                if (!towardsEnd && !row.toNavigation && probe.focused != from) wrong += "Left past the start of ${row.name} ($from) went to ${probe.focused}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** Select on the focused episode card plays that episode, as Play under it did: Down to the episodes, Right twice. */
    @Test
    fun onTvSelectOnAnEpisodeCardPlaysIt() = runComposeUiTest {
        val probe = FocusedProbe()
        val opened = mutableListOf<String>()
        page(probe, opened) { series }
        repeat(3) { press(Key.DirectionDown) }
        repeat(2) { press(Key.DirectionRight) }
        assertEquals("detail.episode.2", probe.focused, "a remote doesn't reach the third card")
        press(Key.Enter)
        assertEquals(listOf("tt1190634:2:3"), opened)
        assertEquals("detail.episode.2", probe.focused, "focus left the card it played")
    }

    /** The remote's Play/Pause and Play keys play the focused episode card, as Select does; on a season chip, nothing. */
    @Test
    fun onTvPlayPauseAndPlayOnAnEpisodeCardPlayIt() = runComposeUiTest {
        val probe = FocusedProbe()
        val opened = mutableListOf<String>()
        page(probe, opened) { series }
        press(Key.DirectionDown)
        assertEquals("detail.season.1", probe.focused, "a remote doesn't reach the selected season's chip")
        press(Key.MediaPlayPause)
        assertEquals(emptyList(), opened, "Play/Pause on a season chip played an episode")
        repeat(2) { press(Key.DirectionDown) }
        repeat(2) { press(Key.DirectionRight) }
        assertEquals("detail.episode.2", probe.focused, "a remote doesn't reach the third card")
        press(Key.MediaPlayPause)
        press(Key.MediaPlay)
        assertEquals(listOf("tt1190634:2:3", "tt1190634:2:3"), opened)
    }

    /** Spirited Away's ten community ratings, which wrap over three lines and put the user's score at the third's start. */
    private val tenRatings = listOf(
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

    /**
     * Where the description wraps and the ratings line up decides where More lies, never where Up and Down take focus:
     * with the user's score (read-only, taking no focus) after a short metadata line, at the start of the ratings' third
     * line (Spirited Away's ten ratings), or absent, and a description of 4 to 64 words (one line to three, More right
     * after a short last line or far right after a full one), Up from Play and from the last action reaches More, Up
     * from More keeps it, and Down from More reaches Play again. Every case's wrong moves are listed.
     */
    @Test
    fun onTvUpAndDownReachMoreWhateverTheTextsWrap() {
        val words = ("During her family's move to the suburbs, a sullen 10-year-old girl wanders into a world ruled by gods, " +
            "witches and spirits, and where humans are changed into beasts. She must work in a bathhouse for the spirits " +
            "to free her parents and find her way back, with a boy who knows more than he says about the place.").split(" ")
        val layouts = listOf(
            "the score after the metadata line" to rated,
            "the score under ten ratings" to rated.copy(body = DetailBody.Loaded((rated.body as DetailBody.Page).header.copy(infoLine = "2003 · 2 h 5 min · Animation, Adventure", ratings = tenRatings))),
            "no rating" to series,
        )
        val wrong = mutableListOf<String>()
        for ((layout, base) in layouts) {
            for (count in 4..64 step 4) runComposeUiTest {
                val probe = FocusedProbe()
                val last = probes.getValue(base.tvActions.last())
                page(probe) { base.copy(body = DetailBody.Loaded((base.body as DetailBody.Page).header.copy(overview = List(count) { words[it % words.size] }.joinToString(" ")))) }
                val case = "$layout, $count words"
                fun move(key: Key, expected: String, from: String) {
                    press(key)
                    if (probe.focused != expected) wrong += "$case: $key from $from went to ${probe.focused}, not $expected"
                }
                assertEquals("detail.play", probe.focused, "$case: the page doesn't open on Play")
                move(Key.DirectionUp, "detail.more", "Play")
                move(Key.DirectionUp, "detail.more", "More")
                move(Key.DirectionDown, "detail.play", "More")
                repeat(base.tvActions.size) { press(Key.DirectionRight) }
                if (probe.focused != last) {
                    wrong += "$case: Right along the actions from Play ended on ${probe.focused}, not $last"
                    return@runComposeUiTest
                }
                move(Key.DirectionUp, "detail.more", last)
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * The player app's answers and the seasons take focus in one order whatever the answers' labels: Down from each
     * action lands on Mark as watched (where the answer under the focus was, Not yet took it from the last actions),
     * Up from the answers goes back to the action Down left, and Up from each season chip lands on Mark as watched,
     * else (no answers) on the action that had focus last. Every case's wrong moves are listed.
     */
    @Test
    fun onTvTheAnswersAndTheSeasonsTakeFocusInOneOrderWhateverTheLabels() {
        val answers = listOf(
            null,
            "Mark as watched" to "Not yet",
            "Yes" to "Not yet, I stopped partway and will finish it another day",
            "Mark this episode and the ones before it as watched" to "No",
        )
        val actions = listOf("detail.play") + series.tvActions.map { probes.getValue(it) }
        val markWatched = "detail.playerApp.markWatched"
        val wrong = mutableListOf<String>()
        for (labels in answers) runComposeUiTest {
            val probe = FocusedProbe()
            val case = labels?.let { (mark, notYet) -> "answers \"$mark\" and \"$notYet\"" } ?: "no answers"
            page(probe) { series.copy(playerApp = labels?.let { (mark, notYet) -> DetailPlayerApp("Opened in VLC. Finished watching S2E1?", mark, notYet) }) }
            fun move(key: Key, expected: String, from: String) {
                press(key)
                if (probe.focused != expected) wrong += "$case: $key from $from went to ${probe.focused}, not $expected"
            }
            if (labels != null) {
                for ((index, action) in actions.withIndex()) {
                    if (index > 0) press(Key.DirectionRight)
                    if (probe.focused != action) {
                        wrong += "$case: Right along the actions reached ${probe.focused}, not $action"
                        return@runComposeUiTest
                    }
                    move(Key.DirectionDown, markWatched, action)
                    move(Key.DirectionUp, action, "the answers")
                }
                press(Key.DirectionDown)
            }
            // From Mark as watched, else from Play: Down to the selected season's chip, along to each chip, then Up.
            val above = if (labels != null) markWatched else "detail.play"
            for ((chip, keys) in listOf(1 to emptyList(), 0 to listOf(Key.DirectionLeft), 2 to listOf(Key.DirectionRight))) {
                press(Key.DirectionDown)
                if (probe.focused != "detail.season.1") {
                    wrong += "$case: Down from $above went to ${probe.focused}, not the selected season"
                    return@runComposeUiTest
                }
                for (key in keys) press(key)
                move(Key.DirectionUp, above, "season chip $chip")
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * Select on the rating's button opens the picker with focus on its stars, one focus stop: Right and Left move the
     * score a point without giving it (eleven Rights, the last ones past 10 doing nothing, then two Lefts: 8), Select
     * gives it. Back closes the picker, and focus is on the rating's button again.
     */
    @Test
    fun onTvTheRatingOpensThePickerOnItsStarsAndFocusComesBack() = runComposeUiTest {
        val probe = FocusedProbe()
        val rated = mutableListOf<Int?>()
        openPicker(probe, rated)
        repeat(11) { press(Key.DirectionRight) }
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals("detail.userRating.stars", probe.focused, "Left or Right left the stars")
        assertEquals(emptyList(), rated, "a move gave a score")
        press(Key.Enter)
        assertEquals(listOf<Int?>(8), rated)
        press(Key.Escape)
        assertEquals("detail.rate", probe.focused, "focus didn't come back to the rating's button")
    }

    /**
     * Keys that come faster than the frames (a remote's held Right, the harness's keys in one `input` call) each move
     * the score a point: nine Rights and a Left, then Select, between two frames give 8. Each moved it from the score
     * the last frame showed, so together they gave 1 (the TV emulator).
     */
    @Test
    fun onTvKeysFasterThanTheFramesEachMoveTheScore() = runComposeUiTest {
        val probe = FocusedProbe()
        val rated = mutableListOf<Int?>()
        openPicker(probe, rated)
        // Every key at one moment, so no frame comes between them.
        mainClock.autoAdvance = false
        onRoot().performKeyInput {
            repeat(9) { pressKey(Key.DirectionRight, pressDurationMillis = 0) }
            pressKey(Key.DirectionLeft, pressDurationMillis = 0)
            pressKey(Key.Enter, pressDurationMillis = 0)
        }
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(listOf<Int?>(8), rated)
    }

    /** The page with the rating's button, its picker opened by a remote: Right along the actions to it, then Select. */
    private fun ComposeUiTest.openPicker(probe: FocusedProbe, rated: MutableList<Int?>) {
        page(probe, rated = rated) { rateable }
        repeat(rateable.tvActions.size) { press(Key.DirectionRight) }
        assertEquals("detail.rate", probe.focused, "a remote doesn't reach the rating's button")
        press(Key.Enter)
        assertEquals("detail.userRating.stars", probe.focused, "the picker doesn't open on its stars")
    }

    /**
     * The title can come before the library's answer, and Play only with it: focus the page put on Add to library
     * then moves to Play as it comes, the page opening on Play as one whose answers came together.
     */
    @Test
    fun onTvFocusThePagePlacedMovesToPlayComingAfterTheTitle() = runComposeUiTest {
        val probe = FocusedProbe()
        var state by mutableStateOf(series.copy(primaryAction = null))
        page(probe) { state }
        assertEquals("detail.library", probe.focused, "the page without Play doesn't open on Add to library")

        state = series
        waitForIdle()
        assertEquals("detail.play", probe.focused)
    }

    /**
     * The line under the actions names the focused button: focus moved off Add to library before Play comes stays
     * where it was moved as Play comes, and the line still names that button.
     */
    @Test
    fun onTvTheLineUnderTheActionsStaysWithTheFocusedButtonAsPlayComes() = runComposeUiTest {
        val probe = FocusedProbe()
        var state by mutableStateOf(series.copy(primaryAction = null))
        page(probe) { state }
        assertEquals("detail.library", probe.focused)
        press(Key.DirectionRight)
        assertEquals("detail.watched", probe.focused)
        onNodeWithText(series.watchedActionText).assertExists()

        state = series
        waitForIdle()
        assertEquals("detail.watched", probe.focused, "Play coming moved focus")
        onNodeWithText(series.watchedActionText).assertExists()
        onNodeWithText(series.libraryActionText).assertDoesNotExist()
    }
}
