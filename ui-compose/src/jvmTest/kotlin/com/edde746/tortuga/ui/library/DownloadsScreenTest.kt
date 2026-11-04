package com.edde746.tortuga.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.downloads.DownloadAction
import com.edde746.tortuga.presentation.downloads.DownloadActionItem
import com.edde746.tortuga.presentation.downloads.DownloadGroup
import com.edde746.tortuga.presentation.downloads.DownloadRow
import com.edde746.tortuga.presentation.downloads.DownloadSeason
import com.edde746.tortuga.presentation.downloads.DownloadsBody
import com.edde746.tortuga.presentation.downloads.DownloadsUiState
import com.edde746.tortuga.presentation.downloads.KeepSyncedLink
import com.edde746.tortuga.presentation.players.PlayWithEntry
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadsScreenTest {
    private val text = T.downloads

    private val pause = DownloadActionItem(DownloadAction.PAUSE, text.action.pause, destructive = false)
    private val cancel = DownloadActionItem(DownloadAction.CANCEL, text.action.cancel, destructive = true)
    private val details = DownloadActionItem(DownloadAction.OPEN_DETAIL, text.action.openDetail, destructive = false)
    private val play = DownloadActionItem(DownloadAction.PLAY, text.action.play, destructive = false)
    private val delete = DownloadActionItem(DownloadAction.DELETE, text.action.delete, destructive = true)

    private val downloading = row(1, "1. Pilot", "S1E1", DownloadStatus.DOWNLOADING, "Downloading · 45%", 0.45, listOf(pause, cancel, details))
    private val complete = row(2, "2. Cat's in the Bag...", "S1E2", DownloadStatus.COMPLETED, text.status.completed, null, listOf(play, details, delete))
    private val movie = row(3, "The Shawshank Redemption", null, DownloadStatus.COMPLETED, text.status.completed, null, listOf(play, details, delete))

    /** A series' episodes and a film. */
    private val groups = listOf(
        DownloadGroup(
            key = "tt0903747",
            type = "series",
            title = "Breaking Bad",
            poster = null,
            summary = "2 episodes · 3.1 GB",
            seasons = listOf(DownloadSeason(1, "Season 1", listOf(downloading, complete))),
            videos = emptyList(),
            videosTitle = text.otherVideos,
            movie = null,
            deleteText = text.action.deleteGroup,
            moreLabel = T.detail.moreActionsNamed(isolate("Breaking Bad")),
        ),
        DownloadGroup(
            "tt0111161", "movie", movie.title, null, "4.5 GB", emptyList(), emptyList(), text.otherVideos, movie, text.action.deleteGroup,
            T.detail.moreActionsNamed(isolate(movie.title)),
        ),
    )

    private val loaded = DownloadsUiState(
        title = text.title,
        body = DownloadsBody.Groups(groups),
        offlineNote = null,
        storage = "7.6 GB used · 40 GB free",
        pauseAllText = text.pauseAll,
        resumeAllText = null,
    )

    /** The film first, then the series, with the row to Keep synced above them. */
    private val keepingSynced = loaded.copy(body = DownloadsBody.Groups(groups.reversed()), keepSynced = KeepSyncedLink(text.keepSynced.title, text.keepSynced.series(2)))

    private val tabs = listOf(T.library.title, T.library.continueWatching, text.title)

    /** With nothing downloaded the section shows the store's empty text under the storage line, Downloads' tab selected. */
    @Test
    fun withNothingDownloadedTheEmptyTextAndTheStorageShow() = runComposeUiTest {
        downloads(FormFactor.DESKTOP, 1000) {
            loaded.copy(body = DownloadsBody.Empty(text.empty), storage = "0 B used · 40 GB free", pauseAllText = null)
        }
        onNodeWithText(text.empty).assertIsDisplayed()
        onNodeWithText("0 B used · 40 GB free").assertIsDisplayed()
        onNode(hasClickAction() and hasText(text.title)).assertIsDisplayed()
    }

    /**
     * A series shows under its name with its season's title over its episodes, a movie as a row of its own; a row's
     * press opens it (a complete one plays), and Pause all pauses everything.
     */
    @Test
    fun rowsShowGroupedAndOpenOnPress() = runComposeUiTest {
        val opened = mutableListOf<Long>()
        var paused = 0
        downloads(FormFactor.DESKTOP, 1000, onOpen = { opened += it }, onPauseAll = { paused++ }) { loaded }
        onNodeWithText("Breaking Bad").assertIsDisplayed()
        onNodeWithText("Season 1").assertIsDisplayed()
        onNodeWithText("S1E1 · Downloading · 45%", substring = true).assertIsDisplayed()
        onNode(hasClickAction() and hasText(complete.title)).performClick()
        onNode(hasClickAction() and hasText(movie.title)).performClick()
        onNode(hasClickAction() and hasText(text.pauseAll)).performClick()
        runOnIdle {
            assertEquals(listOf(2L, 3L), opened)
            assertEquals(1, paused)
        }
    }

    /** A series' video without an episode number shows under "Other videos" after its seasons, and opens on press. */
    @Test
    fun aSeriesVideoWithoutAnEpisodeNumberShowsAfterItsSeasons() = runComposeUiTest {
        val opened = mutableListOf<Long>()
        val extra = row(4, "Inside Breaking Bad", null, DownloadStatus.COMPLETED, text.status.completed, null, listOf(play, details, delete))
        downloads(FormFactor.DESKTOP, 1000, onOpen = { opened += it }) {
            loaded.copy(body = DownloadsBody.Groups(listOf(groups.first().copy(videos = listOf(extra)))))
        }
        onNodeWithText(text.otherVideos).assertIsDisplayed()
        onNode(hasClickAction() and hasText(extra.title)).performClick()
        runOnIdle { assertEquals(listOf(4L), opened) }
    }

    /** A long press on a row opens its actions in order; picking one runs it on that row. */
    @Test
    fun aLongPressOpensTheRowsActions() = runComposeUiTest {
        val performed = mutableListOf<Pair<Long, DownloadAction>>()
        downloads(FormFactor.HANDHELD, 411, onPerform = { id, action -> performed += id to action }) { loaded }
        onNode(hasClickAction() and hasText(downloading.title)).performTouchInput { longClick() }
        onNodeWithText(text.action.cancel).assertIsDisplayed()
        onNodeWithText(text.action.openDetail).assertIsDisplayed()
        onNodeWithText(text.action.pause).performClick()
        runOnIdle { assertEquals(listOf(1L to DownloadAction.PAUSE), performed) }
    }

    /**
     * A complete download's menu lists its actions in the store's order, "Play with…" a section of every player where
     * it comes (right after Play), and a player plays it there; a download that doesn't play yet has none.
     */
    @Test
    fun aCompleteDownloadsMenuOffersEveryPlayer() = runComposeUiTest {
        val played = mutableListOf<Pair<Long, String>>()
        val playWith = PlayWithMenu("Play with…", listOf(PlayWithEntry("tortuga", "Tortuga"), PlayWithEntry("org.videolan.vlc", "VLC")))
        val players = DownloadActionItem(DownloadAction.PLAY_WITH, playWith.title, destructive = false)
        val playable = movie.copy(actions = listOf(play, players, details, delete))
        downloads(FormFactor.HANDHELD, 411, onPlayWith = { id, playerId -> played += id to playerId }) {
            loaded.copy(body = DownloadsBody.Groups(groups.map { it.copy(movie = it.movie?.let { playable }) }), playWith = playWith)
        }
        onNode(hasClickAction() and hasText(movie.title)).performTouchInput { longClick() }
        val order = listOf(text.action.play, playWith.title, "Tortuga", "VLC", text.action.openDetail, text.action.delete)
        val tops = order.map { onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops, "the menu reads ${order.sortedBy { tops[order.indexOf(it)] }}, not $order")
        onNode(hasClickAction() and hasText("VLC")).performClick()
        runOnIdle { assertEquals(listOf(3L to "org.videolan.vlc"), played) }

        onNode(hasClickAction() and hasText(downloading.title)).performTouchInput { longClick() }
        onNodeWithText(text.action.cancel).assertIsDisplayed()
        onNodeWithText("Play with…").assertDoesNotExist()
    }

    /**
     * On TV the first row takes focus as the section opens; Right from it reaches the row's ⋮ (its actions without a
     * Menu key), which opens them in the side panel.
     */
    @Test
    fun onTvRightFromARowReachesItsActions() = runComposeUiTest {
        val performed = mutableListOf<Pair<Long, DownloadAction>>()
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        // The side panel draws in the app's overlay host, as in the app.
                        OverlayHostBox { screen(loaded, onPerform = { id, action -> performed += id to action }) }
                    }
                }
            }
        }
        onNode(isFocused()).assert(hasText(downloading.title))
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNode(isFocused()).assert(hasContentDescription(T.detail.moreActionsNamed(isolate(downloading.title))))
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithText(text.action.cancel).assertIsDisplayed()
        onNode(hasClickAction() and hasText(text.action.pause)).performClick()
        runOnIdle { assertEquals(listOf(1L to DownloadAction.PAUSE), performed) }
    }

    /**
     * On TV Down from Pause all or Resume all lands on the first title's row when it is a film, not on the ⋮ beside the
     * row, which a button at the toolbar's end has under it: either button alone, both, labels of two widths.
     */
    @Test
    fun onTvDownFromTheToolbarLandsOnTheFirstFilmsRow() {
        val longPause = "Pause every download now"
        val cases = listOf(
            Triple(text.pauseAll, null, text.pauseAll),
            Triple(null, text.resumeAll, text.resumeAll),
            Triple(text.pauseAll, text.resumeAll, text.pauseAll),
            Triple(text.pauseAll, text.resumeAll, text.resumeAll),
            Triple(longPause, null, longPause),
        )
        val wrong = mutableListOf<String>()
        for ((pauseAll, resumeAll, from) in cases) runComposeUiTest {
            val probe = FocusedProbe()
            tvDownloads(probe, loaded.copy(body = DownloadsBody.Groups(groups.reversed()), pauseAllText = pauseAll, resumeAllText = resumeAll))
            onNode(hasClickAction() and hasText(from)).requestFocus()
            waitForIdle()
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            if (probe.focused != "downloads.row.${movie.id}") wrong += "${listOfNotNull(pauseAll, resumeAll)}: Down from $from went to ${probe.focused}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On TV the Keep synced row is reachable with the remote: Down from Pause all lands on it (not on the film's row
     * under it), Select opens the Keep synced screen, and coming back from that screen focus is on the row again.
     */
    @Test
    fun onTvDownFromTheToolbarReachesTheKeepSyncedRowAndFocusComesBackToIt() = runComposeUiTest {
        val probe = FocusedProbe()
        var away by mutableStateOf(false)
        var opened = 0
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            OverlayHostBox {
                                // The navigation's: the section's saved state (its focus memory) while Keep synced is on top.
                                val screens = rememberSaveableStateHolder()
                                if (!away) screens.SaveableStateProvider("library") { screen(keepingSynced, onOpenKeepSynced = { opened++ }) }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasClickAction() and hasText(text.pauseAll)).requestFocus()
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals("downloads.keepSynced", probe.focused, "Down from Pause all")
        onRoot().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, opened) }

        away = true
        waitForIdle()
        away = false
        waitForIdle()
        assertEquals("downloads.keepSynced", probe.focused, "back from Keep synced")
    }

    /** On TV, with rules but nothing downloaded, the section opens with focus on the Keep synced row. */
    @Test
    fun onTvWithNothingDownloadedTheSectionOpensOnTheKeepSyncedRow() = runComposeUiTest {
        val probe = FocusedProbe()
        tvDownloads(probe, keepingSynced.copy(body = DownloadsBody.Empty(text.empty), pauseAllText = null))
        assertEquals("downloads.keepSynced", probe.focused)
    }

    /** On TV a first title of videos without episode numbers alone opens the section with focus on its first one. */
    @Test
    fun onTvAFirstTitleOfVideosAloneOpensOnItsFirstVideo() = runComposeUiTest {
        val probe = FocusedProbe()
        val videos = listOf(4L, 5L).map { row(it, "Extra $it", null, DownloadStatus.COMPLETED, text.status.completed, null, listOf(play, details, delete)) }
        tvDownloads(probe, loaded.copy(body = DownloadsBody.Groups(listOf(groups.first().copy(seasons = emptyList(), videos = videos))), pauseAllText = null))
        assertEquals("downloads.row.4", probe.focused)
    }

    /** The section of [state] on a 1080p TV (960 × 540 dp), the arrow keys as the D-pad. */
    private fun ComposeUiTest.tvDownloads(probe: FocusedProbe, state: DownloadsUiState) {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { screen(state) } }
                    }
                }
            }
        }
        waitForIdle()
    }

    /**
     * The section in a desktop window (1280 × 800 dp at twice the pixels), offline: the note, the storage line with Pause
     * all at its end, a series and a movie. The picture is kept as build/screenshots/downloads-desktop.png.
     */
    @Test
    fun inADesktopWindowTheSectionShowsItsGroups() = runSkikoComposeUiTest(size = Size(2560f, 1600f), density = Density(2f)) {
        setContent {
            TortugaTheme {
                Box(Modifier.size(1280.dp, 800.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(FormFactor.DESKTOP) { screen(loaded.copy(offlineNote = text.offline)) }
                }
            }
        }
        onNodeWithText(text.offline).assertIsDisplayed()
        val shot = File("build/screenshots/downloads-desktop.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
    }

    /**
     * The section on a 1080p TV (960 × 540 dp at twice the pixels) with a row's actions open in the side panel, reached
     * by Right and OK from the row. The picture is kept as build/screenshots/downloads-tv-menu.png.
     */
    @Test
    fun onTvARowsActionsOpenInTheSidePanel() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { screen(loaded) } }
                }
            }
        }
        onNode(isFocused()).assert(hasText(downloading.title))
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onRoot().performKeyInput { pressKey(Key.Enter) }
        onNodeWithText(text.action.cancel).assertIsDisplayed()
        mainClock.advanceTimeBy(1_000)
        val shot = File("build/screenshots/downloads-tv-menu.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
    }

    /**
     * On a phone at the largest font scale (2.0) a series' heading and a download put their title under the artwork,
     * at the row's leading inset, whole; at 1.0 the title stays beside the artwork.
     */
    @Test
    fun atTheLargestFontScaleTheTitlesGoUnderTheArtworkWhole() {
        val name = "Breaking Bad: The Complete Series, Remastered With Every Extra"
        val title = "2. Cat's in the Bag, and the Bag's in the River, and the River Runs On"
        val state = loaded.copy(
            body = DownloadsBody.Groups(
                listOf(
                    groups.first().let { group ->
                        group.copy(title = name, seasons = listOf(DownloadSeason(1, "Season 1", listOf(complete.copy(title = title)))))
                    },
                ),
            ),
        )
        val wrong = mutableListOf<String>()
        for (fontScale in listOf(1f, 2f)) runSkikoComposeUiTest(size = Size(412f, 1600f)) {
            downloads(FormFactor.HANDHELD, 412, height = 1600, fontScale = fontScale) { state }
            val row = onNode(hasClickAction() and hasText(title)).getUnclippedBoundsInRoot()
            val inset = row.left + 12.dp
            for (text in listOf(name, title)) {
                val bounds = onNode(hasText(text), useUnmergedTree = true).getUnclippedBoundsInRoot()
                if (fontScale == 2f) {
                    if (abs((bounds.left - inset).value) > 0.5f) wrong += "\"$text\" starts at ${bounds.left}, not at the row's inset $inset, at 2.0"
                    if (cut(text)) wrong += "\"$text\" is cut at 2.0"
                } else if (bounds.left < inset + 40.dp) {
                    wrong += "\"$text\" starts at ${bounds.left}, not beside the artwork, at 1.0"
                }
            }
            // Under the episode's 16:9 thumbnail (112 dp wide on a phone: 63 dp tall) and the row's 12 dp inset.
            val top = onNode(hasText(title), useUnmergedTree = true).getUnclippedBoundsInRoot().top
            if (fontScale == 2f && top < row.top + 12.dp + 63.dp) wrong += "\"$title\" starts at $top, beside its thumbnail (the row's top is ${row.top}), at 2.0"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** Whether [text]'s lines run past its line limit or its box. */
    private fun ComposeUiTest.cut(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText(text), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().didOverflowHeight
    }

    private fun ComposeUiTest.downloads(
        formFactor: FormFactor,
        width: Int,
        onOpen: (Long) -> Unit = {},
        onPerform: (Long, DownloadAction) -> Unit = { _, _ -> },
        onPauseAll: () -> Unit = {},
        onPlayWith: (Long, String) -> Unit = { _, _ -> },
        height: Int = 800,
        fontScale: Float = 1f,
        state: () -> DownloadsUiState,
    ) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            TortugaTheme {
                Box(Modifier.size(width.dp, height.dp)) {
                    AdaptiveLayout(formFactor) { screen(state(), onOpen, onPerform, onPauseAll, onPlayWith) }
                }
            }
        }
    }

    @Composable
    private fun screen(
        state: DownloadsUiState,
        onOpen: (Long) -> Unit = {},
        onPerform: (Long, DownloadAction) -> Unit = { _, _ -> },
        onPauseAll: () -> Unit = {},
        onPlayWith: (Long, String) -> Unit = { _, _ -> },
        onOpenKeepSynced: () -> Unit = {},
    ) = DownloadsScreen(
        state = state,
        tabs = tabs,
        selectedTab = 2,
        onSelectTab = {},
        onOpen = onOpen,
        onPerform = onPerform,
        onPlayWith = onPlayWith,
        onDeleteGroup = {},
        onConfirmDeleteGroup = {},
        onDismissDeleteGroup = {},
        onPauseAll = onPauseAll,
        onResumeAll = {},
        onOpenKeepSynced = onOpenKeepSynced,
        onNoticeShown = {},
    )

    private fun row(id: Long, title: String, subtitle: String?, status: DownloadStatus, statusText: String, progress: Double?, actions: List<DownloadActionItem>) =
        DownloadRow(
            id = id,
            metaId = if (subtitle == null) "tt0111161" else "tt0903747",
            videoId = if (subtitle == null) "tt0111161" else "tt0903747:1:$id",
            title = title,
            subtitle = subtitle,
            artwork = null,
            status = status,
            statusText = statusText,
            progress = progress,
            bytesText = when {
                status == DownloadStatus.DOWNLOADING -> "0.7 GB of 1.6 GB · 3.4 MB/s"
                progress != null -> "0.7 GB of 1.6 GB"
                else -> "1.6 GB"
            },
            failureText = null,
            actions = actions,
            moreLabel = T.detail.moreActionsNamed(isolate(title)),
        )
}
