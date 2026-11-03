package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.presentation.settings.TrackerAccountUiState
import com.edde746.tortuga.presentation.settings.TrackerServiceRow
import com.edde746.tortuga.presentation.settings.TrackersSettingsUiState
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** Settings → Trackers. */
@OptIn(ExperimentalTestApi::class)
class TrackersSettingsPaneTest {
    /** A notice that comes after the pane is shown (an action's outcome) is in view at the top of a list that scrolls. */
    @Test
    fun aNoticeShowsAtTheTop() = runComposeUiTest {
        var state by mutableStateOf(trackers(TrackerService.entries.map { connected(it) }))
        setContent {
            TortugaTheme {
                Box(Modifier.size(640.dp, 400.dp)) {
                    AdaptiveLayout(FormFactor.DESKTOP) { TrackersSettingsPane(state, noIntents) }
                }
            }
        }
        waitForIdle()
        state = state.copy(notice = "Trakt is disconnected.")
        onNodeWithText("Trakt is disconnected.").assertIsDisplayed()
    }

    /**
     * On TV Down from the switch above a connected service's first row and Up from the one under it land on the row's
     * Disconnect, whatever the status line's wrap and the button's label: alone at the row's end, Disconnect would lose
     * to the full-width switch beyond it.
     */
    @Test
    fun onTvUpAndDownLandOnAServicesDisconnectWhateverItsStatusAndLabel() {
        val wrong = mutableListOf<String>()
        val cases = listOf(
            "Connected as tester" to "Disconnect",
            "Connected as tester. What this profile watches on this device goes to Simkl as it plays, and each title's watched mark once it ends." to "Disconnect",
            "Connected as tester" to "Disconnect this account",
        )
        for ((status, label) in cases) runComposeUiTest {
            val probe = FocusedProbe()
            tvTrackers(probe, listOf(connected(TrackerService.TRAKT), connected(TrackerService.SIMKL, status, label)))
            // The two services have the same switches: Trakt's come first.
            val moves = listOf(
                Triple("Up from Simkl's Send what you watch", Key.DirectionUp, onAllNodesWithText("Send what you watch")[1]),
                Triple("Down from Trakt's Anime", Key.DirectionDown, onAllNodesWithText("Anime")[0]),
            )
            for ((move, key, start) in moves) {
                start.requestFocus()
                press(key)
                if (probe.focused != "settings.trackers.simkl.disconnect") wrong += "\"$status\", \"$label\": $move went to ${probe.focused}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The pane over [services] on a 1080p TV (960 × 540 dp), the arrow keys as the D-pad, its focus going to [probe]. */
    private fun ComposeUiTest.tvTrackers(probe: FocusedProbe, services: List<TrackerServiceRow>) {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { TrackersSettingsPane(trackers(services), noIntents) }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun trackers(services: List<TrackerServiceRow>) = TrackersSettingsUiState(
        title = "Trackers",
        note = "Connected services get what this profile watches.",
        services = services,
        connect = null,
        dialog = null,
        notice = null,
    )

    private fun connected(service: TrackerService, status: String = "Connected", disconnectLabel: String = "Disconnect") = TrackerServiceRow(
        service = service,
        name = service.name,
        status = status,
        connected = true,
        username = "tester",
        available = true,
        canConnect = true,
        connectLabel = "Connect",
        connectNamedLabel = "Connect ${service.name}",
        disconnectLabel = disconnectLabel,
        disconnectNamedLabel = "$disconnectLabel ${service.name}",
        account = TrackerAccountUiState(
            scrobble = SettingToggleRow("TRAKT.scrobble", "Send what you watch", null, true),
            filtersHeader = "What to send",
            movies = SettingToggleRow("TRAKT.movies", "Movies", null, true),
            series = SettingToggleRow("TRAKT.series", "Series", null, true),
            anime = SettingToggleRow("TRAKT.anime", "Anime", null, true),
            queuedCount = 0,
            queued = null,
            failedCount = 0,
            failed = null,
        ),
    )

    private val noIntents = TrackersSettingsIntents(
        connect = {},
        retryConnect = {},
        cancelConnect = {},
        openInBrowser = {},
        disconnect = {},
        confirmDialog = {},
        dismissDialog = {},
        dismissNotice = {},
        setScrobbling = { _, _ -> },
        setMovies = { _, _ -> },
        setSeries = { _, _ -> },
        setAnime = { _, _ -> },
    )
}
