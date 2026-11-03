package com.edde746.tortuga.ui.detail

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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPlayerApp
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The title's playback in a player app, under Detail's actions: the "Playing in" line, or the "Finished watching?" prompt. */
@OptIn(ExperimentalTestApi::class)
class DetailPlayerAppTest {
    private val answers = mutableListOf<Boolean>()

    private val prompt = DetailPlayerApp("Opened in VLC. Finished watching The Shawshank Redemption?", "Mark as watched", "Not yet")

    private val movie = DetailUiState(
        body = DetailBody.Loaded(DetailHeader(
            id = "tt0000001",
            type = "movie",
            imdbId = null,
            title = "The Shawshank Redemption",
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
        playerApp = prompt,
    )

    /** The prompt shows its question; Mark as watched and Not yet answer it. */
    @Test
    fun thePromptsButtonsAnswerIt() = runComposeUiTest {
        detail(FormFactor.HANDHELD, movie)
        onNodeWithText(prompt.text).assertIsDisplayed()
        onNode(hasClickAction() and hasText("Mark as watched")).performClick()
        onNode(hasClickAction() and hasText("Not yet")).performClick()
        runOnIdle { assertEquals(listOf(true, false), answers) }
    }

    /** While a player that reports back plays the title, its line shows alone, without answers. */
    @Test
    fun aLivePlayersLineHasNoAnswers() = runComposeUiTest {
        detail(FormFactor.HANDHELD, movie.copy(playerApp = DetailPlayerApp("Playing in mpv · 12:34 of 1:45:00", null, null)))
        onNodeWithText("Playing in mpv · 12:34 of 1:45:00").assertIsDisplayed()
        onNodeWithText("Not yet").assertDoesNotExist()
    }

    /** On TV Down from Play reaches the prompt's answers; answering gives focus back to Play. */
    @Test
    fun onTvDownFromPlayReachesTheAnswers() = runComposeUiTest {
        detail(FormFactor.TV, movie)
        onNode(isFocused()).assert(hasText(T.detail.play))
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNode(isFocused()).assert(hasText("Mark as watched"))
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNode(isFocused()).assert(hasText("Not yet"))
        onRoot().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(listOf(false), answers) }
        onNode(isFocused()).assert(hasText(T.detail.play))
    }

    /** The prompt on a phone (411 × 800 dp at twice the pixels). The picture is kept as build/screenshots/detail-player-app-phone.png. */
    @Test
    fun onAPhoneThePromptShowsUnderTheActions() = runSkikoComposeUiTest(size = Size(822f, 1600f), density = Density(2f)) {
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 800.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(FormFactor.HANDHELD) { screen(movie) }
                }
            }
        }
        onNodeWithText(prompt.text).assertIsDisplayed()
        val shot = File("build/screenshots/detail-player-app-phone.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
    }

    private fun ComposeUiTest.detail(formFactor: FormFactor, state: DetailUiState) = setContent {
        TortugaTheme {
            Box(Modifier.size(if (formFactor == FormFactor.TV) 960.dp else 411.dp, if (formFactor == FormFactor.TV) 540.dp else 1000.dp)) {
                AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) { screen(state) }
            }
        }
    }

    @Composable
    private fun screen(state: DetailUiState) = DetailScreen(
        state,
        testDetailIntents(answerPlayerApp = { answers += it }),
        onBack = null,
    )
}
