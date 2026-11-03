package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailEpisodeRange
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A series' season chips: picking a season or a range shows its episodes, and a season's menu marks it watched. */
@OptIn(ExperimentalTestApi::class)
class DetailSeasonChipsTest {
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
        primaryAction = DetailPrimaryAction(T.detail.play, "tt0903747:1:1", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = listOf(season(1, selected = true), season(2, selected = false)),
        episodes = episodes(1, 3),
        chooseStreamText = null,
    )

    /** The phone's one column and a desktop window's two columns. */
    private val offTv = listOf(Layout(FormFactor.HANDHELD, 400.dp, 1000.dp), Layout(FormFactor.DESKTOP, 1280.dp, 800.dp))

    /**
     * On TV a held Select on a season's chip opens its menu, and letting go doesn't pick the season again (focus
     * moving onto the chip picked it): a remote may have no Menu key, and the SHIELD's system keeps it from apps.
     */
    @Test
    fun onTvAHeldSelectOpensASeasonsMenu() = runComposeUiTest {
        val calls = mutableListOf<String>()
        screen(Layout(FormFactor.TV, 960.dp, 540.dp), series, calls)
        onNode(hasClickAction() and hasText("Season 2")).requestFocus()
        onRoot().performKeyInput { keyDown(Key.Enter) }
        mainClock.advanceTimeBy(1_000)
        onRoot().performKeyInput { keyUp(Key.Enter) }
        onNode(hasClickAction() and hasText("Mark season 2 watched")).performClick()
        runOnIdle { assertEquals(listOf("select 2", "season 2 markSeasonWatched"), calls) }
    }

    /**
     * On desktop a held Enter on a season's chip opens its menu, focus on its entry, and the key, still down, picks
     * nothing there: AWT repeats a held key as presses, then lets it go once, into the menu. A press that starts in
     * the menu picks the entry.
     */
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun onDesktopAHeldEnterOpensASeasonsMenuWithoutPickingInIt() = runComposeUiTest {
        val calls = mutableListOf<String>()
        screen(offTv[1], series, calls)
        onNode(hasClickAction() and hasText("Season 2")).requestFocus()
        onRoot().performKeyInput { keyDown(Key.Enter) }
        mainClock.advanceTimeBy(1_000)
        val entry = onNode(hasClickAction() and hasText("Mark season 2 watched")).assertIsFocused()

        entry.performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        entry.performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyUp))
        runOnIdle { assertEquals(emptyList(), calls, "the held Enter picked in the menu") }

        entry.performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyDown))
        entry.performKeyPress(KeyEvent(Key.Enter, KeyEventType.KeyUp))
        runOnIdle { assertEquals(listOf("season 2 markSeasonWatched"), calls) }
    }

    /** Off TV a tap or a click on a season's chip selects that season, and the list under the chips shows its episodes. */
    @Test
    fun offTvAChipShowsItsSeasonsEpisodes() {
        for (layout in offTv) runComposeUiTest {
            val calls = mutableListOf<String>()
            screen(layout, series, calls)
            onNode(hasText("1x1")).assertExists("${layout.formFactor}: season 1's episodes at first")
            onNode(hasClickAction() and hasText("Season 2")).performClick()
            waitForIdle()
            assertEquals(listOf("select 2"), calls, "${layout.formFactor}")
            onNode(hasText("2x1")).assertExists("${layout.formFactor}: season 2's episodes once its chip is picked")
            onNode(hasText("1x1")).assertDoesNotExist()
        }
    }

    /**
     * Off TV a season of more than 50 episodes lists the range holding the current episode, and a range's chip shows
     * that range's episodes instead.
     */
    @Test
    fun offTvARangesChipShowsItsEpisodes() {
        val long = series.copy(
            episodes = episodes(1, 120),
            currentEpisode = "tt0903747:1:75",
            episodeRanges = listOf(DetailEpisodeRange("1-50", 0, 50), DetailEpisodeRange("51-100", 50, 100), DetailEpisodeRange("101-120", 100, 120)),
            listedRange = 1,
        )
        for (layout in offTv) runComposeUiTest {
            screen(layout, long, mutableListOf())
            onNode(hasText("1x51")).assertExists("${layout.formFactor}: the range holding E75 at first")
            onNode(hasText("1x1")).assertDoesNotExist()
            onNode(hasClickAction() and hasText("1-50")).performClick()
            waitForIdle()
            onNode(hasText("1x1")).assertExists("${layout.formFactor}: 1-50's episodes once its chip is picked")
            onNode(hasText("1x51")).assertDoesNotExist()
        }
    }

    /** A long press on a phone's season chip opens its menu in the app's item menu, which marks the season watched. */
    @Test
    fun onAPhoneALongPressOnAChipMarksItsSeasonWatched() = runComposeUiTest {
        val calls = mutableListOf<String>()
        screen(offTv[0], series, calls)
        onNode(hasClickAction() and hasText("Season 2")).performTouchInput { longClick() }
        onNode(hasClickAction() and hasText("Mark season 2 watched")).performClick()
        runOnIdle { assertEquals(listOf("season 2 markSeasonWatched"), calls, "the menu, without selecting the season") }
    }

    /** A right click on a desktop window's season chip opens its menu, which marks the season watched. */
    @Test
    fun onDesktopARightClickOnAChipMarksItsSeasonWatched() = runComposeUiTest {
        val calls = mutableListOf<String>()
        screen(offTv[1], series, calls)
        onNode(hasClickAction() and hasText("Season 2")).performMouseInput { rightClick() }
        onNode(hasClickAction() and hasText("Mark season 2 watched")).performClick()
        runOnIdle { assertEquals(listOf("season 2 markSeasonWatched"), calls, "the menu, without selecting the season") }
    }

    /**
     * A pick moves no chip of the seasons' row, so the chip under the pointer or the finger stays under it: each is as
     * wide with its name SemiBold (selected) as Regular, at a density whose dp aren't whole pixels (a Pixel 7's). Off
     * TV a tap or a click picks; on TV focus moving onto a chip does.
     */
    @Test
    fun aPickMovesNoChip() {
        val three = series.copy(seasons = (1..3).map { season(it, selected = it == 1) })
        for (layout in offTv + Layout(FormFactor.TV, 960.dp, 540.dp)) {
            runSkikoComposeUiTest(size = Size(3400f, 2700f), density = Density(2.625f)) {
                screen(layout, three, mutableListOf())
                fun chip(season: Int) = onNode(hasClickAction() and hasText("Season $season"))
                // On TV the page first comes to the seasons' row, focus on the selected season's chip.
                if (layout.formFactor == FormFactor.TV) chip(1).requestFocus()
                waitForIdle()
                val before = (1..3).map { chip(it).getUnclippedBoundsInRoot() }
                for (pick in listOf(2, 3, 1)) {
                    if (layout.formFactor == FormFactor.TV) chip(pick).requestFocus() else chip(pick).performClick()
                    waitForIdle()
                    chip(pick).assertIsSelected()
                    assertEquals(before, (1..3).map { chip(it).getUnclippedBoundsInRoot() }, "${layout.formFactor}: the chips after picking season $pick")
                }
            }
        }
    }

    private class Layout(val formFactor: FormFactor, val width: Dp, val height: Dp)

    /**
     * The Detail screen of [initial] in [layout], a season picked swapping in its episodes and a range picked listing it;
     * [calls] the intents sent.
     */
    private fun ComposeUiTest.screen(layout: Layout, initial: DetailUiState, calls: MutableList<String>) {
        setContent {
            var state by remember { mutableStateOf(initial) }
            TortugaTheme {
                Box(Modifier.size(layout.width, layout.height)) {
                    AdaptiveLayout(layout.formFactor, Modifier.arrowKeyFocus()) {
                        OverlayHostBox {
                            DetailScreen(
                                state,
                                testDetailIntents(
                                    selectSeason = { season ->
                                        calls += "select $season"
                                        state = state.copy(
                                            seasons = state.seasons.map { it.copy(selected = it.season == season) },
                                            episodes = episodes(season, 3),
                                            episodeRanges = emptyList(),
                                            listedRange = 0,
                                        )
                                    },
                                    seasonMenu = { season, action -> calls += "season $season ${action.id}" },
                                    pickRange = { state = state.copy(listedRange = it) },
                                ),
                                onBack = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Season [number]'s chip, its menu marking it or the whole title watched as the store builds it. */
    private fun season(number: Int, selected: Boolean) = DetailSeason(
        number,
        "Season $number",
        selected = selected,
        watched = false,
        menu = listOf(
            MediaMenuEntry(MediaAction.MARK_SEASON_WATCHED, "Mark season $number watched"),
            MediaMenuEntry(MediaAction.MARK_WATCHED, T.detail.markAllWatched),
        ),
    )

    /** Season [season]'s [count] episodes, each titled `<season>x<number>`. */
    private fun episodes(season: Int, count: Int) = (1..count).map { number ->
        DetailEpisode(
            videoId = "tt0903747:$season:$number",
            code = "S${season}E$number",
            number = "$number",
            title = "${season}x$number",
            numberedTitle = "${season}x$number",
            moreLabel = T.detail.moreActionsNamed("${season}x$number"),
            infoLine = null,
            overview = null,
            thumbnail = null,
            progress = null,
            blurred = false,
            watched = false,
            upcoming = false,
            nextUp = false,
            watchedActionText = T.detail.markWatched,
            detailLine = null,
            stateText = null,
        )
    }
}
