package com.edde746.tortuga.ui.streams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.StreamText
import com.edde746.tortuga.presentation.streams.GroupStatus
import com.edde746.tortuga.presentation.streams.StreamGroup
import com.edde746.tortuga.presentation.streams.StreamNoteKind
import com.edde746.tortuga.presentation.streams.StreamRow
import com.edde746.tortuga.presentation.streams.StreamsBody
import com.edde746.tortuga.presentation.streams.StreamsUiState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A stream row's note ("Last played", "Hidden by your filters", "Opens outside the app") sits at its top trailing
 * corner beside its name, clear of its description and inside its row, and rows don't reach each other, over styled-text
 * rows on a phone, a tablet and a TV.
 */
@OptIn(ExperimentalTestApi::class)
class StreamRowNoteTest {
    private fun styledTexts(video: String): List<Pair<String?, String?>> {
        val json = File("../shared/src/commonTest/fixtures/streams/styled-texts/$video.json").readText()
        return (AddonResponse.decode(json).content as ResourceContent.Streams).items.map { StreamText.name(it.name) to StreamText.description(it.description) }
    }

    /**
     * The anime episode's first three rows, then the longest description of the movie whose descriptions are
     * the longest: the second played last, the third hidden, the fourth external.
     */
    private fun rows(): List<StreamRow> {
        val notes = listOf(null, StreamNoteKind.LAST_USED, StreamNoteKind.FILTERED, StreamNoteKind.EXTERNAL)
        val longest = styledTexts("movie-ex0040").maxBy { it.second?.length ?: 0 }
        return styledTexts("series-ex0010-1-1").take(3).plus(longest).mapIndexed { index, (name, description) ->
            StreamRow(
                "row-$index",
                name.orEmpty(),
                description,
                emptyList(),
                external = notes[index] == StreamNoteKind.EXTERNAL,
                lastUsed = notes[index] == StreamNoteKind.LAST_USED,
                filtered = notes[index] == StreamNoteKind.FILTERED,
                noteKind = notes[index],
            )
        }
    }

    @Test
    fun theNoteIsBesideTheNameClearOfTheDescriptionOnAPhone() = runComposeUiTest {
        setContent { TortugaTheme { Box(Modifier.size(360.dp, 2400.dp)) { AdaptiveLayout(FormFactor.HANDHELD) { screen() } } } }
        assertNotesClear()
    }

    @Test
    fun theNoteIsBesideTheNameClearOfTheDescriptionOnATablet() = runComposeUiTest {
        setContent { TortugaTheme { Box(Modifier.size(1280.dp, 2400.dp)) { AdaptiveLayout(FormFactor.HANDHELD) { screen() } } } }
        assertNotesClear()
    }

    @Test
    fun theNoteIsBesideTheNameClearOfTheDescriptionOnTv() = runComposeUiTest {
        setContent { TortugaTheme { Box(Modifier.size(960.dp, 2400.dp)) { AdaptiveLayout(FormFactor.TV) { screen() } } } }
        assertNotesClear()
    }

    /**
     * Each note's top is level with its row's name, and the note neither overlaps the row's description nor leaves its
     * row, and each row ends above the next.
     */
    private fun ComposeUiTest.assertNotesClear() {
        val rows = rows()
        val bounds = rows.map { row -> bounds(onNode(hasClickAction() and hasText(row.name))) }
        for (index in 1 until bounds.size) {
            assertTrue(bounds[index - 1].bottom <= bounds[index].top, "row ${index - 1} ${bounds[index - 1]} reaches row $index ${bounds[index]}")
        }
        for ((index, row) in rows.withIndex()) {
            val note = row.note ?: continue
            val noteBounds = bounds(onNode(hasText(note), useUnmergedTree = true))
            val name = bounds(onNode(hasText(row.name) and !hasClickAction(), useUnmergedTree = true))
            // A word the description has as written and as drawn, styled or not.
            val word = Regex("[A-Za-z]{4,}").find(row.description!!)!!.value
            val description = onAllNodes(hasText(word, substring = true) and !hasText(row.name) and !hasClickAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { node -> with(density) { node.boundsInRoot.let { Rect(it.left.toDp().value, it.top.toDp().value, it.right.toDp().value, it.bottom.toDp().value) } } }
                .single { it.top >= bounds[index].top && it.bottom <= bounds[index].bottom }
            assertTrue(!noteBounds.overlaps(description), "row $index's note $noteBounds overlaps its description $description")
            assertTrue(noteBounds.bottom <= description.top, "row $index's note $noteBounds reaches below its description's top $description")
            assertTrue(noteBounds.top < name.bottom && !noteBounds.overlaps(name), "row $index's note $noteBounds isn't beside its name $name")
            val rowBounds = bounds[index]
            assertTrue(
                noteBounds.left >= rowBounds.left && noteBounds.right <= rowBounds.right &&
                    noteBounds.top >= rowBounds.top && noteBounds.bottom <= rowBounds.bottom,
                "row $index's note $noteBounds leaves its row $rowBounds",
            )
        }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.getBoundsInRoot().toRect()

    private fun DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    @Composable
    private fun screen() = StreamsScreen(
        state = StreamsUiState(
            title = "Rent-a-Girlfriend",
            subtitle = null,
            body = StreamsBody.Groups(listOf(StreamGroup("group-0", "Styled Streams", GroupStatus.Ready, rows(), null, null))),
            notice = null,
            filter = null,
        ),
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
