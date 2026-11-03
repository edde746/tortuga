package com.edde746.tortuga.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new playback's store takes the last one's place under the player (a stream picked over the player): what the
 * player reports goes to the new store from then on, the controls' height (which keeps subtitles above them) at once,
 * a pinch's zoom as it comes.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerNewStoreTest {
    /** What a store hears from the player: the controls' height and the pinches. */
    private class Store {
        val heights = mutableListOf<Double>()
        val pinches = mutableListOf<InputEvent>()
        val onControlsHeightChanged: (Double) -> Unit = { heights += it }
        val onInput: (InputEvent) -> Boolean = { event ->
            val pinch = event is InputEvent.Pinch || event == InputEvent.PinchEnd
            if (pinch) pinches += event
            pinch
        }
    }

    private var store by mutableStateOf(Store())

    /** The new store hears how much of the video the controls cover, though that hasn't changed. */
    @Test
    fun theNewStoreHearsTheControlsHeight() = runComposeUiTest {
        val first = store
        player(FormFactor.DESKTOP)
        runOnIdle { assertTrue(first.heights.isNotEmpty() && first.heights.last() > 0.0, "the first store's controls' heights: ${first.heights}") }

        val second = Store()
        store = second
        runOnIdle { assertEquals(listOf(first.heights.last()), second.heights, "the new store's controls' heights") }
    }

    /** A pinch after the new store came zooms its picture; the last store hears nothing more. */
    @Test
    fun aPinchGoesToTheNewStore() = runComposeUiTest {
        val first = store
        player(FormFactor.HANDHELD)
        pinch()
        runOnIdle { assertTrue(first.pinches.isNotEmpty(), "the first store heard no pinch") }

        val second = Store()
        store = second
        val heard = first.pinches.size
        pinch()
        runOnIdle {
            assertEquals(emptyList(), first.pinches.drop(heard), "the last store's pinches after the new one came")
            assertTrue(second.pinches.any { it is InputEvent.Pinch } && second.pinches.last() == InputEvent.PinchEnd, "the new store's pinches: ${second.pinches}")
        }
    }

    /** Two fingers spreading apart on the video, between the top bar and the controls. */
    private fun ComposeUiTest.pinch() {
        onRoot().performTouchInput {
            val y = height * 0.4f
            down(0, Offset(width * 0.45f, y))
            down(1, Offset(width * 0.55f, y))
            moveTo(0, Offset(width * 0.35f, y))
            moveTo(1, Offset(width * 0.65f, y))
            up(0)
            up(1)
        }
        waitForIdle()
    }

    /** The player on a 960 × 540 dp window of [formFactor], reporting to the current [store]. */
    private fun ComposeUiTest.player(formFactor: FormFactor) = setContent {
        val owner = remember {
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(formFactor) {
                        OverlayHostBox {
                            val current = store
                            PlayerScreen(
                                testPlayerState(),
                                MutableStateFlow(testTimeline),
                                testPlayerConfig,
                                onCommand = {},
                                onInput = current.onInput,
                                onTimelineFocusChanged = {},
                                onControlsHeightChanged = current.onControlsHeightChanged,
                            ) {}
                        }
                    }
                }
            }
        }
    }
}
