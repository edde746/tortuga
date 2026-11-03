package com.edde746.tortuga.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import com.edde746.tortuga.presentation.players.PlayWithEntry
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.presentation.streams.GroupStatus
import com.edde746.tortuga.presentation.streams.StreamGroup
import com.edde746.tortuga.presentation.streams.StreamNoteKind
import com.edde746.tortuga.presentation.streams.StreamRow
import com.edde746.tortuga.presentation.streams.StreamsBody
import com.edde746.tortuga.presentation.streams.StreamsUiState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Play with…" in a stream row's menu: on rows that play, beside Download, on every input. */
@OptIn(ExperimentalTestApi::class)
class StreamsPlayWithTest {
    private val played = mutableListOf<Pair<String, String>>()
    private val downloaded = mutableListOf<String>()

    private val playWith = PlayWithMenu("Play with…", listOf(PlayWithEntry("tortuga", "Tortuga"), PlayWithEntry("org.videolan.vlc", "VLC")))

    private val movie = StreamRow("a", "1080p", "Movie.2024.1080p.mkv", emptyList(), external = false, lastUsed = false, filtered = false, noteKind = null, downloadActionText = "Download")
    private val trailer = StreamRow("b", "Trailer", null, emptyList(), external = true, lastUsed = false, filtered = false, noteKind = StreamNoteKind.EXTERNAL)

    private val state = StreamsUiState(
        title = "The Shawshank Redemption",
        subtitle = null,
        body = StreamsBody.Groups(listOf(StreamGroup("group-0", "Test addon", GroupStatus.Ready, listOf(movie, trailer), null, null))),
        notice = null,
        filter = null,
        playWith = playWith,
    )

    /** A long press on a row that plays offers Download and, under "Play with…", every player; one plays it there. */
    @Test
    fun aRowsMenuOffersEveryPlayer() = runComposeUiTest {
        streams(FormFactor.HANDHELD) { state }
        onNode(hasClickAction() and hasText("1080p")).performTouchInput { longClick() }
        onNodeWithText("Download").assertIsDisplayed()
        onNodeWithText("Play with…").assertIsDisplayed()
        onNodeWithText("Tortuga").assertIsDisplayed()
        onNode(hasClickAction() and hasText("VLC")).performClick()
        runOnIdle {
            assertEquals(listOf("a" to "org.videolan.vlc"), played)
            assertEquals(emptyList<String>(), downloaded)
        }
    }

    /** Without Download (a device without downloads) the menu holds the players alone. */
    @Test
    fun withoutDownloadsTheMenuHoldsThePlayers() = runComposeUiTest {
        streams(FormFactor.HANDHELD) { state.copy(body = StreamsBody.Groups(listOf(state.groups.single().copy(streams = listOf(movie.copy(downloadActionText = null)))))) }
        onNode(hasClickAction() and hasText("1080p")).performTouchInput { longClick() }
        onNodeWithText("Download").assertDoesNotExist()
        onNode(hasClickAction() and hasText("Tortuga")).performClick()
        runOnIdle { assertEquals(listOf("a" to "tortuga"), played) }
    }

    /** A row that opens outside the app plays in no player: it has no menu. */
    @Test
    fun aRowThatOpensOutsideHasNoPlayers() = runComposeUiTest {
        streams(FormFactor.HANDHELD) { state }
        onNode(hasClickAction() and hasText("Trailer")).performTouchInput { longClick() }
        onNodeWithText("Play with…").assertDoesNotExist()
    }

    /** On TV the row's long press opens its menu in the side panel, where a remote walks to a player and plays it. */
    @Test
    fun onTvTheMenuOpensInTheSidePanel() = runComposeUiTest {
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { screen(state) } }
                }
            }
        }
        onNode(isFocused()).assert(hasText("1080p"))
        onNode(hasClickAction() and hasText("1080p")).performTouchInput { longClick() }
        onNode(isFocused()).assert(hasText("Download"))
        repeat(2) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        onNode(isFocused()).assert(hasText("VLC"))
        onRoot().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf("a" to "org.videolan.vlc"), played) }
    }

    /**
     * A row's menu in the side panel on a 1080p TV (960 × 540 dp at twice the pixels). The picture is kept as
     * build/screenshots/streams-play-with-tv.png.
     */
    @Test
    fun onTvTheMenuShowsThePlayers() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { screen(state) } }
                }
            }
        }
        onNode(hasClickAction() and hasText("1080p")).performTouchInput { longClick() }
        onNodeWithText("Play with…").assertIsDisplayed()
        mainClock.advanceTimeBy(1_000)
        val shot = File("build/screenshots/streams-play-with-tv.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
    }

    private fun ComposeUiTest.streams(formFactor: FormFactor, state: () -> StreamsUiState) = setContent {
        TortugaTheme {
            Box(Modifier.size(411.dp, 800.dp)) {
                AdaptiveLayout(formFactor) { screen(state()) }
            }
        }
    }

    @Composable
    private fun screen(state: StreamsUiState) = StreamsScreen(
        state = state,
        onPick = {},
        onDownload = { downloaded += it },
        onPlayWith = { id, playerId -> played += id to playerId },
        onDismissDownloadMessage = {},
        onReload = {},
        onToggleFiltered = {},
        onSelectAddon = {},
        onRefresh = {},
        onToggleSort = {},
        onBack = null,
    )
}
