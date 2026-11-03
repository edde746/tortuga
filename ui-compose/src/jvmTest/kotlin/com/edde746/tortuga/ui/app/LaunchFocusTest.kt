package com.edde746.tortuga.ui.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.BundledText
import com.edde746.tortuga.app.PlatformPlayback
import com.edde746.tortuga.app.SystemInputs
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.library.LibraryRow
import com.edde746.tortuga.presentation.library.LibrarySort
import com.edde746.tortuga.presentation.library.LibrarySortOption
import com.edde746.tortuga.presentation.library.LibraryTypeOption
import com.edde746.tortuga.presentation.library.LibraryUiState
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.ui.library.LibraryScreen
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focus the app places as a screen opens (Library's first card) in a window that starts outside touch mode, as an
 * Android tablet's does after keys the system took before the app started (the Fold's 7" and 10" tablet captures
 * showed Library's first card ringed). The app's frame
 * ([TortugaFrame]) draws that focus only from a key press on, and a touch hides it again, on phones and tablets; TV
 * shows it from the start.
 */
@OptIn(ExperimentalTestApi::class)
class LaunchFocusTest {
    /** The system's settings, as a platform reports them before it builds a core. */
    @BeforeTest
    fun reportSystemInputs() = SystemInputs.report(listOf("en"), region = null, captions = false, audioDescriptions = false)

    /**
     * On a tablet the first card, focused as Library opens, is drawn exactly as it is without focus: no ring and no
     * focus layer. Right moves focus to the second card and draws it there; a touch hides it, focus staying put.
     */
    @Test
    fun onATabletFocusPlacedAtLaunchShowsFromAKeyUntilATouch() = runComposeUiTest {
        library(FormFactor.HANDHELD) { input ->
            onNode(poster(FIRST)).assertIsFocused()
            runOnIdle { assertEquals(InputMode.Keyboard, input().inputMode, "the window starts outside touch mode") }
            val firstAtLaunch = shot(FIRST)
            val secondAtRest = shot(SECOND)

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            onNode(poster(SECOND)).assertIsFocused()
            assertTrue(firstAtLaunch.contentEquals(shot(FIRST)), "the first card, focused as Library opened, was drawn with its focus before any key")
            assertFalse(secondAtRest.contentEquals(shot(SECOND)), "Right moved focus to the second card without drawing it")

            onRoot().performTouchInput { click(Offset(width - 8f, height - 8f)) }
            onNode(poster(SECOND)).assertIsFocused()
            assertTrue(secondAtRest.contentEquals(shot(SECOND)), "the second card's focus still drawn after a touch")
        }
    }

    /** On TV the first card, focused as Library opens, shows its focus at once. */
    @Test
    fun onTvFocusPlacedAtLaunchShowsAtOnce() = runComposeUiTest {
        library(FormFactor.TV) {
            onNode(poster(FIRST)).assertIsFocused()
            val firstAtLaunch = shot(FIRST)

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            onNode(poster(SECOND)).assertIsFocused()
            assertFalse(firstAtLaunch.contentEquals(shot(FIRST)), "the first card, focused as Library opened, wasn't drawn with its focus")
        }
    }

    /**
     * Library with two posters without artwork (drawn alike) in the app's frame on [formFactor], in a 7" tablet's
     * window, given to [test] with the window's input mode as the frame sees it.
     */
    private fun ComposeUiTest.library(formFactor: FormFactor, test: (input: () -> InputModeManager) -> Unit) =
        AppBootstrap.local(DatabaseFactory.inMemory(), BuildFlavor.SIDELOADED, "test", DeviceDescriptor("Test", DevicePlatform.ANDROID)) {}.use { boot ->
            lateinit var input: InputModeManager
            setContent {
                input = LocalInputModeManager.current
                TortugaFrame(boot.graph, TestPlatform(formFactor), Modifier.size(960.dp, 540.dp)) {
                    LibraryScreen(
                        state,
                        tabs = listOf("Library", "Continue Watching"),
                        selectedTab = 0,
                        onSelectTab = {},
                        onSelectType = {},
                        onSelectSort = {},
                        onOpen = {},
                        onMenu = { _, _ -> },
                        onLoadMore = {},
                        onOpenCalendar = {},
                    )
                }
            }
            waitForIdle()
            test { input }
        }

    private fun poster(title: String): SemanticsMatcher = hasContentDescription(title) and hasClickAction()

    /** The poster [title]'s pixels as drawn now. */
    private fun ComposeUiTest.shot(title: String): IntArray =
        onNode(poster(title)).captureToImage().toAwtImage().let { it.getRGB(0, 0, it.width, it.height, null, 0, it.width) }

    private val state = LibraryUiState(
        title = "Library",
        types = listOf(LibraryTypeOption(null, "All", true), LibraryTypeOption("movie", "Movies", false)),
        sorts = listOf(LibrarySortOption(LibrarySort.LAST_WATCHED, "Recently watched", true)),
        body = LibraryBody.Grid(listOf(FIRST, SECOND).map { LibraryRow(it, "movie", it, null, PosterShape.POSTER, null, watched = false, menu = emptyList()) }, hasMore = false),
    )

    /** The platform as far as Library in the app's frame reaches it: its form factor. */
    private class TestPlatform(override val formFactor: FormFactor) : TortugaPlatform {
        override val playerConfig: PlayerConfig get() = error("the test plays nothing")

        override fun createPlayback(): PlatformPlayback = error("the test plays nothing")

        override fun setFullscreen(fullscreen: Boolean) = Unit

        override fun openUrl(url: String) = false

        override fun setClipboardText(text: String, sensitive: Boolean) = Unit

        override fun deviceFeatures(): DeviceFeatures = error("the test opens no settings")

        override fun bundledTexts(folder: String): List<BundledText> = error("the test opens no settings")

        override fun exit() = Unit

        @Composable
        override fun VideoSurface(engine: PlayerEngine, modifier: Modifier) = Unit

        override fun saveFile(fileName: String, text: String, done: (SaveResult) -> Unit) = Unit

        override fun openFile(opened: (name: String, text: String) -> Unit, failed: () -> Unit) = Unit
    }
}

private const val FIRST = "Nine Winters North"
private const val SECOND = "The Brine Houses"
