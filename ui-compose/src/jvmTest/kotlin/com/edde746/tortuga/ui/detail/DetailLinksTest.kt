package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailFact
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailLink
import com.edde746.tortuga.presentation.detail.DetailLinkGroup
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DetailLinksTest {
    private val header = DetailHeader(
        id = "tt0000001",
        type = "movie",
        imdbId = null,
        title = "The Shawshank Redemption",
        poster = null,
        background = null,
        logo = null,
        posterShape = PosterShape.POSTER,
        infoLine = "1994 · 2 h 22 min · Drama",
        ratings = emptyList(),
        overview = null,
        linkGroups = listOf(
            DetailLinkGroup("Genres", listOf(DetailLink("link:2", "Drama"))),
            DetailLinkGroup("Cast", listOf(DetailLink("link:3", "Tim Robbins"), DetailLink("link:4", "Morgan Freeman"))),
        ),
        castAndCrew = listOf(DetailLink("link:3", "Tim Robbins"), DetailLink("link:4", "Morgan Freeman")),
        factsTitle = "Information",
        facts = listOf(
            DetailFact("Runtime", "2 h 22 min", linked = false),
            DetailFact("Cast", "Tim Robbins, Morgan Freeman", linked = true),
            DetailFact("Genres", "Drama", linked = true),
        ),
    )

    private val state = DetailUiState(
        body = DetailBody.Loaded(header),
        sources = emptyList(),
        primaryAction = null,
        inLibrary = false,
        libraryActionText = "Add to library",
        watched = false,
        watchedActionText = "Mark as watched",
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
    )

    /**
     * The cast is a line of the information block, "Cast  Tim Robbins, Morgan Freeman", each name a link of its own: a
     * click on the second name opens that person, not the first one or the whole line. The names show once (the plain
     * cast line is left out), beside their label rather than in a section under it.
     */
    @Test
    fun eachCastNameIsALinkBesideItsLabel() = runComposeUiTest {
        val opened = mutableListOf<String>()
        detail(state, FormFactor.HANDHELD, 411, 1400) { opened += it }
        onNodeWithText("Morgan Freeman").assertHasClickAction().performClick()
        assertEquals(listOf("link:4"), opened)
        onNodeWithText("Tim Robbins, Morgan Freeman").assertDoesNotExist()
        val label = onNodeWithText("Cast").getUnclippedBoundsInRoot()
        val name = onNodeWithText("Tim Robbins").getUnclippedBoundsInRoot()
        assertTrue(name.left >= label.right && name.top < label.bottom && name.bottom > label.top, "Cast is at $label, its first name at $name")
    }

    /** On TV the remote walks from the actions down to the names, one name at a time, and Select opens the one focused. */
    @Test
    fun onTvTheRemoteReachesEachName() = runComposeUiTest {
        val opened = mutableListOf<String>()
        detail(state.copy(primaryAction = DetailPrimaryAction("Play", "tt0111161", resume = false, episodeTitle = null, progress = null)), FormFactor.TV, 960, 540) {
            opened += it
        }
        waitForIdle()
        var presses = 0
        while (!focused("Tim Robbins") && presses++ < 12) {
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
        }
        assertTrue(focused("Tim Robbins"), "Down from the actions never reached the cast")
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertTrue(focused("Morgan Freeman"), "Right from the first name doesn't reach the second")
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("link:4"), opened)
    }

    /**
     * On TV (the More panel) each category's names are one stop for Up and Down, whatever the names' widths and wrap:
     * Down from a name on a category's last line lands on the next category's first name, never on one further down
     * (a category of one short name, at the line's start, lost to the names straight under the focus beyond it), and
     * Down from a name above its category's last line stays in the category. In columns of 360 to 600 dp; every
     * wrong move is listed.
     */
    @Test
    fun onTvDownGoesThroughTheCategoriesInOrderWhateverTheNamesWrap() {
        val groups = listOf(
            "Genres" to listOf("Drama", "Crime", "Prison drama"),
            "Cast" to listOf("Tim Robbins", "Morgan Freeman", "Bob Gunton", "William Sadler", "Clancy Brown", "Gil Bellows", "James Whitmore"),
            "Directors" to listOf("Ed"),
            "Writers" to listOf("Stephen King", "Frank Darabont", "Richard Marquand", "Lawrence Kasdan"),
            "Studios" to listOf("Castle Rock Entertainment"),
        ).mapIndexed { group, (title, names) -> DetailLinkGroup(title, names.mapIndexed { index, name -> DetailLink("link:$group.$index", name) }) }
        val wrong = mutableListOf<String>()
        for (width in listOf(360, 420, 480, 540, 600)) runComposeUiTest {
            val probe = FocusedProbe()
            setContent {
                CompositionLocalProvider(LocalLayoutProbe provides probe) {
                    TortugaTheme {
                        Box(Modifier.size(960.dp, 540.dp)) {
                            AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                                Information(header.copy(linkGroups = groups, facts = emptyList()), rememberFocusMemory(), {}, Modifier.width(width.dp))
                            }
                        }
                    }
                }
            }
            waitForIdle()
            for ((group, category) in groups.withIndex()) {
                val tops = category.links.map { onNodeWithText(it.label).getUnclippedBoundsInRoot().top }
                for ((index, link) in category.links.withIndex()) {
                    onNodeWithText(link.label).requestFocus()
                    waitForIdle()
                    onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                    waitForIdle()
                    val lastLine = tops.none { it > tops[index] }
                    val focused = probe.focused
                    val from = "$width dp: Down from ${link.label}"
                    when {
                        !lastLine && focused?.startsWith("detail.link.$group.") != true -> wrong += "$from went to $focused, out of ${category.title}"
                        lastLine && group < groups.lastIndex && focused != "detail.link.${group + 1}.0" -> wrong += "$from went to $focused, not ${groups[group + 1].links.first().label}"
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun ComposeUiTest.focused(text: String): Boolean =
        onNodeWithText(text).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    private fun ComposeUiTest.detail(state: DetailUiState, formFactor: FormFactor, width: Int, height: Int, onOpenLink: (String) -> Unit) = setContent {
        TortugaTheme {
            Box(Modifier.size(width.dp, height.dp)) {
                AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                    DetailScreen(
                        state,
                        testDetailIntents(openLink = onOpenLink),
                        onBack = null,
                    )
                }
            }
        }
    }
}
