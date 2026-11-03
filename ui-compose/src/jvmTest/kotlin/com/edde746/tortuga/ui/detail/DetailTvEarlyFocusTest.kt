package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android TV's Detail page opened from a card while its title loads: an anime entry from Home's row shows the card's
 * page (its name, no actions) until the title is found. The page holds focus meanwhile, so focus neither goes to the
 * side navigation (which opens over the page) nor is lost with the card's screen; once the title comes, its Play has
 * focus, as on a page whose title was there at once.
 */
@OptIn(ExperimentalTestApi::class)
class DetailTvEarlyFocusTest {
    private val header = DetailHeader(
        id = "tt0000001",
        type = "series",
        imdbId = null,
        title = "One Piece",
        poster = "https://example.invalid/one-piece.jpg",
        background = null,
        logo = null,
        posterShape = PosterShape.POSTER,
        infoLine = "1999",
        ratings = emptyList(),
        overview = null,
        linkGroups = emptyList(),
        factsTitle = "Information",
        facts = emptyList(),
    )

    /** The card's page while the entry's title is looked up. */
    private val early = DetailUiState(
        body = DetailBody.Preview(header, episodesPending = true),
        sources = emptyList(),
        primaryAction = null,
        inLibrary = false,
        libraryActionText = T.detail.addToLibrary,
        watched = false,
        watchedActionText = T.detail.markAllWatched,
        trailers = emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = null,
    )

    /** The title found: its page, with Play. */
    private val resolved = early.copy(
        body = DetailBody.Loaded(header.copy(overview = "A boy sets sail to find the One Piece.")),
        primaryAction = DetailPrimaryAction("S1E1", "kitsu:12:1", resume = false, episodeTitle = "Episode 1", progress = null, spokenLabel = "Play S1E1"),
    )

    @Test
    fun onTvTheEarlyPageHoldsFocusUntilTheTitlesPlayTakesIt() = runComposeUiTest {
        val probe = FocusedProbe()
        val card = FocusRequester()
        var onHome by mutableStateOf(true)
        var state by mutableStateOf(early)
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.BOARD, {}, showNavigation = true) {
                                OverlayHostBox {
                                    // Home's card, pressed: Detail replaces Home's screen.
                                    if (onHome) {
                                        TortugaButton("One Piece", {}, Modifier.focusRequester(card).layoutProbe("board.row.0.item.0"))
                                    } else {
                                        DetailScreen(state, testDetailIntents(), onBack = {})
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { card.requestFocus() }
        waitForIdle()
        assertEquals("board.row.0.item.0", probe.focused)

        onHome = false
        waitForIdle()
        onNodeWithText("One Piece").assertIsDisplayed()
        assertEquals("detail.page", probe.focused, "while the title loads focus isn't on the Detail page")

        state = resolved
        waitForIdle()
        assertEquals("detail.play", probe.focused, "the title's Play didn't take focus from the page")
    }
}
