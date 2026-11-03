package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.collections.CollectionImportRow
import com.edde746.tortuga.presentation.collections.CollectionImportStep
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The import summary on TV: taller than the sheet, it scrolls above Import, and the D-pad walks every row and line. */
@OptIn(ExperimentalTestApi::class)
class CollectionImportSheetTest {
    private val placed = mutableMapOf<String, Rect>()
    private var focused: String? = null

    private val step = CollectionImportStep.Summary(
        collections = List(5) { CollectionImportRow("Collection $it", "Adds a collection", "Folder A, Folder B", replaces = false) },
        lines = List(14) { "Summary line $it: what the import needs or leaves out" },
        importLabel = "Import",
        canImport = true,
    )

    @Test
    fun onTvUpFromImportWalksTheWholeSummaryInView() = runComposeUiTest {
        summary()
        onNodeWithText("Import").assertIsFocused().assertIsDisplayed()
        val window = onRoot().fetchSemanticsNode().boundsInRoot
        val import = placed.getValue("collections.import.confirm")
        assertTrue(import.bottom <= window.bottom && import.top >= window.top, "Import is in the window: $import in $window")
        val summary = placed.getValue("collections.import.summary")
        assertTrue(summary.bottom <= import.top, "the summary ends above Import: $summary, $import")

        val stops = step.collections.indices.map { "collections.import.collection.$it" } + step.lines.indices.map { "collections.import.line.$it" }
        for (stop in stops.reversed()) {
            onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()
            assertEquals(stop, focused, "Up reaches each row and line in turn")
            val bounds = placed.getValue(stop)
            val room = placed.getValue("collections.import.summary")
            assertTrue(bounds.top >= room.top - 0.5f && bounds.bottom <= room.bottom + 0.5f, "$stop is scrolled into view: $bounds in $room")
            onNodeWithText("Import").assertIsDisplayed()
        }
    }

    /** The summary step in a TV sheet (960 × 540 dp, the arrow keys moving focus as the D-pad). */
    private fun ComposeUiTest.summary() = setContent {
        val owner = remember {
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
        }
        val probe = object : LayoutProbe {
            override fun place(name: String, bounds: Rect?) {
                if (bounds != null) placed[name] = bounds else placed -= name
            }

            override fun focus(name: String, focused: Boolean) {
                if (focused) this@CollectionImportSheetTest.focused = name
            }
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        OverlayHostBox(Modifier.fillMaxSize()) {
                            val primary = remember { FocusRequester() }
                            TortugaSheet(onDismiss = {}, title = "Import collections", initialFocus = primary) {
                                ImportSummaryStep(step, primary) {}
                            }
                        }
                    }
                }
            }
        }
    }
}
