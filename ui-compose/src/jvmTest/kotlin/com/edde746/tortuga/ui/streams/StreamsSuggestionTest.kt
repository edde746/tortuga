package com.edde746.tortuga.ui.streams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.streams.GroupStatus
import com.edde746.tortuga.presentation.streams.StreamGroup
import com.edde746.tortuga.presentation.streams.StreamRow
import com.edde746.tortuga.presentation.streams.StreamSuggestion
import com.edde746.tortuga.presentation.streams.StreamsBody
import com.edde746.tortuga.presentation.streams.StreamsUiState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test

/** The suggested stream's mark: "Suggested" above its name and why under it, part of its row on every layout. */
@OptIn(ExperimentalTestApi::class)
class StreamsSuggestionTest {
    private val reason = "Like your recent picks: 1080p, HEVC, English audio"
    private val fourK = StreamRow("a", "4K", "Movie.2024.2160p.mkv", emptyList(), external = false, lastUsed = false, filtered = false, noteKind = null)
    private val hd = StreamRow("b", "1080p", "Movie.2024.1080p.mkv", emptyList(), external = false, lastUsed = false, filtered = false, noteKind = null)
    private val suggested = hd.copy(suggestion = StreamSuggestion("Suggested", reason))

    private fun state(vararg rows: StreamRow) = StreamsUiState(
        title = "The Shawshank Redemption",
        subtitle = null,
        body = StreamsBody.Groups(listOf(StreamGroup("group-0", "Test addon", GroupStatus.Ready, rows.toList(), null, null))),
        notice = null,
        filter = null,
    )

    /** The suggested row carries the mark and its reason where the list put it; the row beside it carries neither. */
    @Test
    fun onlyTheSuggestedRowIsMarked() = runComposeUiTest {
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 800.dp)) { AdaptiveLayout(FormFactor.HANDHELD) { screen(state(fourK, suggested)) } }
            }
        }
        onNode(hasClickAction() and hasText("1080p")).assert(hasText("Suggested") and hasText(reason))
        onNode(hasClickAction() and hasText("4K")).assert(!hasText("Suggested") and !hasText(reason))
        onNode(hasClickAction() and hasText("Suggested")).assert(hasText("1080p"))
    }

    /** A list without a suggestion (suggestions off) marks no row. */
    @Test
    fun withoutASuggestionNoRowIsMarked() = runComposeUiTest {
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 800.dp)) { AdaptiveLayout(FormFactor.HANDHELD) { screen(state(fourK, hd)) } }
            }
        }
        onNode(hasText("Suggested", substring = true)).assertDoesNotExist()
        onNode(hasText(reason)).assertDoesNotExist()
    }

    /**
     * On TV the mark arrives once every addon has answered: focus stays on the row that has it, and the remote walks the
     * rows as before, the mark being no stop of its own.
     */
    @Test
    fun onTvTheMarkTakesNoFocus() = runComposeUiTest {
        var current by mutableStateOf(state(fourK, hd))
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) { AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { screen(current) } }
            }
        }
        onNode(isFocused()).assert(hasText("4K"))
        current = state(fourK.copy(suggestion = StreamSuggestion("Suggested", reason)), hd)
        waitForIdle()
        onNode(isFocused()).assert(hasText("4K") and hasText("Suggested") and hasText(reason))
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNode(isFocused()).assert(hasText("1080p"))
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNode(isFocused()).assert(hasText("4K"))
    }

    @Composable
    private fun screen(state: StreamsUiState) = StreamsScreen(
        state = state,
        onPick = {},
        onDownload = {},
        onPlayWith = { _, _ -> },
        onDismissDownloadMessage = {},
        onReload = {},
        onToggleFiltered = {},
        onSelectAddon = {},
        onRefresh = {},
        onToggleSort = {},
        onBack = null,
    )
}
