package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The states a screen shows in place of its content, with one action under a title and a line. */
@OptIn(ExperimentalTestApi::class)
class MessageStatesTest {
    /**
     * On TV a remote's focus on the action (Ask a parent, which takes it as the blocked state shows; Go to Downloads)
     * reaches the harness under the action's name, not under the name of the state around it: a remote walked to the
     * action finds it there.
     */
    @Test
    fun onTvTheFocusedActionIsReportedUnderItsOwnName() {
        runComposeUiTest {
            val probe = tv { BlockedMessage("Not available on this profile", "Suggested 16+", "Ask a parent", {}, "detail.kidsBlock") }
            runOnIdle { assertEquals("detail.kidsBlock.action", probe.focused) }
        }
        runComposeUiTest {
            val probe = tv { OfflineMessage(OfflineState("You're offline", "Downloads still play.", "Go to Downloads"), {}) }
            onNode(hasClickAction()).requestFocus()
            runOnIdle { assertEquals("offline.openDownloads", probe.focused) }
        }
    }

    /** [content] on a 1080p TV (960 × 540 dp), the arrow keys as the D-pad; returns the probe its focus goes to. */
    private fun ComposeUiTest.tv(content: @Composable () -> Unit): FocusedProbe {
        val probe = FocusedProbe()
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) { AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { content() } }
                }
            }
        }
        waitForIdle()
        return probe
    }
}
