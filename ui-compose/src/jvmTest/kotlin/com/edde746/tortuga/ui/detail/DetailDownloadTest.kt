package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailDownload
import com.edde746.tortuga.presentation.detail.DetailDownloadEntry
import com.edde746.tortuga.presentation.detail.DetailDownloadSection
import com.edde746.tortuga.presentation.detail.DetailDownloadState
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailEpisodeDownload
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.TOAST_MILLIS
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailDownloadTest {
    private val text = T.detail.download

    private val movie = DetailUiState(
        body = DetailBody.Loaded(header("movie", "The Shawshank Redemption")),
        sources = emptyList(),
        primaryAction = DetailPrimaryAction(T.detail.play, "tt0111161", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
        download = DetailDownload(
            state = DetailDownloadState.AVAILABLE,
            label = text.download,
            progress = null,
            opensMenu = false,
            menuTitle = text.menuTitle,
            menu = listOf(DetailDownloadSection(null, listOf(DetailDownloadEntry("download", text.episode.download)))),
        ),
    )

    private val pilot = DetailEpisode(
        videoId = "tt0903747:1:1",
        code = "S1E1",
        number = "1",
        title = "Pilot",
        numberedTitle = "1. Pilot",
        moreLabel = T.detail.moreActionsNamed("1. Pilot"),
        infoLine = "Jan 20, 2008 · 58 min",
        overview = null,
        thumbnail = null,
        progress = null,
        blurred = false,
        watched = false,
        upcoming = false,
        nextUp = true,
        watchedActionText = T.detail.markWatched,
        detailLine = "Jan 20, 2008 · 58 min",
        stateText = null,
        download = DetailEpisodeDownload(state = DetailDownloadState.DOWNLOADING, label = "45%", progress = 0.45),
        menu = listOf(
            MediaMenuEntry(MediaAction.MARK_WATCHED, T.detail.markWatched),
            MediaMenuEntry(MediaAction.PAUSE_DOWNLOAD, text.episode.pause),
            MediaMenuEntry(MediaAction.CANCEL_DOWNLOAD, text.episode.cancel),
        ),
    )

    private val series = movie.copy(
        body = DetailBody.Loaded(header("series", "Breaking Bad")),
        primaryAction = DetailPrimaryAction(T.detail.play, pilot.videoId, resume = false, episodeTitle = "Pilot", progress = null),
        seasons = listOf(DetailSeason(1, "Season 1", selected = true, watched = false)),
        episodes = listOf(pilot),
        download = DetailDownload(
            state = DetailDownloadState.PARTIAL,
            label = "1 of 7 downloaded",
            progress = null,
            opensMenu = true,
            menuTitle = text.menuTitle,
            menu = listOf(
                DetailDownloadSection("Season 1", listOf(DetailDownloadEntry("season:ALL", text.pick.all), DetailDownloadEntry("season:UNWATCHED", text.pick.unwatched))),
                DetailDownloadSection(
                    text.keepSynced,
                    listOf(DetailDownloadEntry("sync:5", "Keep 5 unwatched episodes", selected = true), DetailDownloadEntry("syncRules", T.downloads.keepSynced.manage)),
                ),
            ),
        ),
    )

    /** A movie's Download acts at once (it opens the Streams sheet in download mode): no menu opens. */
    @Test
    fun aMoviesDownloadPressesAtOnce() = runComposeUiTest {
        var pressed = 0
        detail(onPressDownload = { pressed++ }) { movie }
        onNode(hasClickAction() and hasContentDescription(text.download)).performClick()
        runOnIdle { assertEquals(1, pressed) }
        onNodeWithText(text.episode.download).assertDoesNotExist()
    }

    /** A long press on a movie's Download opens its menu all the same. */
    @Test
    fun aLongPressOpensTheMenuOfADownloadThatActsAtOnce() = runComposeUiTest {
        val picked = mutableListOf<String>()
        detail(onDownloadAction = { picked += it }) { movie }
        onNode(hasClickAction() and hasContentDescription(text.download)).performTouchInput { longClick() }
        onNodeWithText(text.episode.download).performClick()
        runOnIdle { assertEquals(listOf("download"), picked) }
    }

    /**
     * A series' Download opens its menu: the season's choices under the season's name, Keep synced with its current rule
     * checked; a choice runs its entry.
     */
    @Test
    fun aSeriesDownloadOpensItsMenuInSections() = runComposeUiTest {
        val picked = mutableListOf<String>()
        detail(onDownloadAction = { picked += it }) { series }
        onNode(hasClickAction() and hasContentDescription("1 of 7 downloaded")).performClick()
        onNodeWithText(text.keepSynced).assertIsDisplayed()
        onNodeWithText("Keep 5 unwatched episodes").assertIsDisplayed()
        onNodeWithText(text.pick.unwatched).performClick()
        runOnIdle { assertEquals(listOf("season:UNWATCHED"), picked) }
    }

    /** An episode row shows its download's state, and its menu holds the download's entries after the watched ones. */
    @Test
    fun anEpisodeShowsItsDownloadAndItsMenuHoldsTheEntries() = runComposeUiTest {
        val picked = mutableListOf<Pair<String, MediaAction>>()
        detail(onEpisodeMenu = { videoId, action -> picked += videoId to action }) { series }
        onNode(hasClickAction() and hasText(pilot.numberedTitle) and hasText("45%")).performTouchInput { longClick() }
        onNodeWithText(text.episode.cancel).assertIsDisplayed()
        onNodeWithText(text.episode.pause).performClick()
        runOnIdle { assertEquals(listOf(pilot.videoId to MediaAction.PAUSE_DOWNLOAD), picked) }
    }

    /** What a "Download…" choice came to shows for a few seconds, then the store hears it was shown. */
    @Test
    fun theDownloadNoticeShowsThenIsDismissed() = runComposeUiTest {
        var state by mutableStateOf(series)
        var dismissed = 0
        detail(onDismissDownloadNotice = {
            dismissed++
            state = state.copy(downloadNotice = null)
        }) { state }
        state = state.copy(downloadNotice = "4 episodes added to Downloads")
        onNodeWithText("4 episodes added to Downloads").assertIsDisplayed()
        mainClock.advanceTimeBy(TOAST_MILLIS + 500)
        runOnIdle { assertEquals(1, dismissed) }
        onNodeWithText("4 episodes added to Downloads").assertDoesNotExist()
    }

    /** The saved title's note shows under the actions. */
    @Test
    fun theOfflineNoteShows() = runComposeUiTest {
        detail { movie.copy(offlineNote = T.offline.detail) }
        onNodeWithText(T.offline.detail).assertIsDisplayed()
    }

    /**
     * On TV a remote walks the action row to Download (Right from Play past the library and watched buttons) and OK opens
     * its menu in the side panel, in its sections. The picture is kept as build/screenshots/detail-tv-download-menu.png.
     */
    @Test
    fun onTvTheDownloadMenuOpensInTheSidePanel() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val picked = mutableListOf<String>()
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        OverlayHostBox { screen(series, onDownloadAction = { picked += it }) }
                    }
                }
            }
        }
        onNode(isFocused()).assert(hasText(T.detail.play))
        repeat(3) { onRoot().performKeyInput { pressKey(Key.DirectionRight) } }
        onNode(isFocused()).assert(hasContentDescription("1 of 7 downloaded"))
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithText(text.keepSynced).assertIsDisplayed()
        mainClock.advanceTimeBy(1_000)
        val shot = File("build/screenshots/detail-tv-download-menu.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
        onNode(hasClickAction() and hasText(text.pick.all)).performClick()
        runOnIdle { assertEquals(listOf("season:ALL"), picked) }
    }

    private fun ComposeUiTest.detail(
        onPressDownload: () -> Unit = {},
        onDownloadAction: (String) -> Unit = {},
        onEpisodeMenu: (String, MediaAction) -> Unit = { _, _ -> },
        onDismissDownloadNotice: () -> Unit = {},
        state: () -> DetailUiState,
    ) = setContent {
        TortugaTheme {
            Box(Modifier.size(411.dp, 1400.dp)) {
                AdaptiveLayout(FormFactor.HANDHELD) { screen(state(), onPressDownload, onDownloadAction, onEpisodeMenu, onDismissDownloadNotice) }
            }
        }
    }

    @Composable
    private fun screen(
        state: DetailUiState,
        onPressDownload: () -> Unit = {},
        onDownloadAction: (String) -> Unit = {},
        onEpisodeMenu: (String, MediaAction) -> Unit = { _, _ -> },
        onDismissDownloadNotice: () -> Unit = {},
    ) = DetailScreen(
        state,
        testDetailIntents(
            episodeMenu = onEpisodeMenu,
            pressDownload = onPressDownload,
            downloadAction = onDownloadAction,
            dismissDownloadNotice = onDismissDownloadNotice,
        ),
        onBack = null,
    )

    private fun header(type: String, title: String) = DetailHeader(
        id = "tt0000001",
        type = type,
        imdbId = null,
        title = title,
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
    )
}
