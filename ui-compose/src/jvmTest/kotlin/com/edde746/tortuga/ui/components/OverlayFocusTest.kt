package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.presentation.settings.TrackerAccountUiState
import com.edde746.tortuga.presentation.settings.TrackerConfirmDialog
import com.edde746.tortuga.presentation.settings.TrackerConnectStep
import com.edde746.tortuga.presentation.settings.TrackerConnectUiState
import com.edde746.tortuga.presentation.settings.TrackerServiceRow
import com.edde746.tortuga.presentation.settings.TrackersSettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.LocalScreenFocus
import com.edde746.tortuga.ui.focus.ScreenFocus
import com.edde746.tortuga.ui.focus.focusGate
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.settings.TrackersSettingsIntents
import com.edde746.tortuga.ui.settings.TrackersSettingsPane
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test

/**
 * On TV an overlay that closes (a dialog, a sheet, a line over the screens, a page over the whole app) gives focus back
 * to the control that opened it, or, once that control is gone, to the control nearest its place on its screen: never
 * to the navigation, which the platform hands focus to when the focused control leaves. Settings -> Trackers (Simkl,
 * then Trakt) is the screen.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayFocusTest {
    private var trackers by mutableStateOf(
        TrackersSettingsUiState(
            title = "Trackers",
            note = "Connected services get what this profile watches.",
            services = listOf(service(TrackerService.SIMKL, "Simkl", connected = true), service(TrackerService.TRAKT, "Trakt", connected = false)),
            connect = null,
            dialog = null,
            notice = null,
        ),
    )
    private var sheet by mutableStateOf(false)
    private var line by mutableStateOf(false)
    private var page by mutableStateOf(false)

    /** Trakt's connect dialog cancelled: focus back on Trakt's Connect. */
    @Test
    fun aCancelledConnectGivesFocusBackToConnect() = runComposeUiTest {
        settings()
        onNode(hasContentDescription(connect("Trakt"))).requestFocus()
        trackers = trackers.copy(connect = connecting(TrackerService.TRAKT)).with(TrackerService.TRAKT) { it.copy(canConnect = false) }
        waitForIdle()
        onNodeWithText("Cancel").assertIsFocused()
        trackers = trackers.copy(connect = null).with(TrackerService.TRAKT) { it.copy(canConnect = true) }
        waitForIdle()
        onNode(hasContentDescription(connect("Trakt"))).assertIsFocused()
    }

    /** Trakt approves: its dialog closes as Connect turns into Disconnect, which takes focus. */
    @Test
    fun anApprovedConnectGivesFocusToTheDisconnectInConnectsPlace() = runComposeUiTest {
        settings()
        onNode(hasContentDescription(connect("Trakt"))).requestFocus()
        trackers = trackers.copy(connect = connecting(TrackerService.TRAKT)).with(TrackerService.TRAKT) { it.copy(canConnect = false) }
        waitForIdle()
        trackers = trackers.copy(connect = null, notice = "Trakt is connected.").with(TrackerService.TRAKT) { service(it.service, it.name, connected = true) }
        waitForIdle()
        onNode(hasContentDescription(disconnect("Trakt"))).assertIsFocused()
    }

    /** Disconnect's confirmation cancelled: focus back on Disconnect. */
    @Test
    fun aCancelledDisconnectGivesFocusBackToDisconnect() = runComposeUiTest {
        trackers = trackers.with(TrackerService.TRAKT) { service(it.service, it.name, connected = true) }
        settings()
        onNode(hasContentDescription(disconnect("Trakt"))).requestFocus()
        trackers = trackers.copy(dialog = confirmation(TrackerService.TRAKT))
        waitForIdle()
        onNodeWithText("Cancel").assertIsFocused()
        trackers = trackers.copy(dialog = null)
        waitForIdle()
        onNode(hasContentDescription(disconnect("Trakt"))).assertIsFocused()
    }

    /**
     * Disconnect confirmed: focus goes back to Disconnect as the dialog closes, and to the Connect that replaces it once
     * the server answers, not to the screen's first control (Simkl's).
     */
    @Test
    fun aDisconnectThatLandsAfterItsDialogClosedGivesFocusToConnect() = runComposeUiTest {
        trackers = trackers.with(TrackerService.TRAKT) { service(it.service, it.name, connected = true) }
        settings()
        onNode(hasContentDescription(disconnect("Trakt"))).requestFocus()
        trackers = trackers.copy(dialog = confirmation(TrackerService.TRAKT))
        waitForIdle()
        trackers = trackers.copy(dialog = null)
        waitForIdle()
        trackers = trackers.copy(notice = "Trakt is disconnected.").with(TrackerService.TRAKT) { service(it.service, it.name, connected = false) }
        waitForIdle()
        onNode(hasContentDescription(connect("Trakt"))).assertIsFocused()
    }

    /** A sheet opened from a control gives focus back to it as it closes. */
    @Test
    fun aClosedSheetGivesFocusBackToItsOpener() = runComposeUiTest {
        settings()
        onNode(hasContentDescription(connect("Trakt"))).requestFocus()
        sheet = true
        waitForIdle()
        onNodeWithText("Everything").assertIsFocused()
        sheet = false
        waitForIdle()
        onNode(hasContentDescription(connect("Trakt"))).assertIsFocused()
    }

    /** A line over the screens (a player app's hand-off) that took focus gives it back as it leaves. */
    @Test
    fun aLineThatLeavesGivesFocusBackToTheControlItTookItFrom() = runComposeUiTest {
        settings()
        onNode(hasContentDescription(disconnect("Simkl"))).requestFocus()
        line = true
        waitForIdle()
        onNodeWithText("Play in Tortuga").assertIsFocused()
        line = false
        waitForIdle()
        onNode(hasContentDescription(disconnect("Simkl"))).assertIsFocused()
    }

    /** A page over the whole app (the profile editor) gives focus back to the control that opened it as it closes. */
    @Test
    fun aClosedPageGivesFocusBackToItsOpener() = runComposeUiTest {
        settings()
        onNode(hasContentDescription(disconnect("Simkl"))).requestFocus()
        page = true
        waitForIdle()
        onNodeWithText("Save").requestFocus()
        waitForIdle()
        page = false
        waitForIdle()
        onNode(hasContentDescription(disconnect("Simkl"))).assertIsFocused()
    }

    /**
     * The app on a TV: the navigation's column, Settings -> Trackers as the screen, and a sheet, a line and a page over
     * them.
     */
    private fun ComposeUiTest.settings() = setContent {
        TortugaTheme {
            Box(Modifier.size(960.dp, 540.dp)) {
                AdaptiveLayout(FormFactor.TV) {
                    OverlayHostBox {
                        NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.SETTINGS, onSelect = {}, showNavigation = true) {
                            Screen { TrackersSettingsPane(trackers, intents) }
                        }
                        if (sheet) TortugaSheet(onDismiss = { sheet = false }, title = "Import") { TortugaButton("Everything", {}) }
                        if (line) {
                            val focus = remember { FocusRequester() }
                            TopOverlay(focus, onBack = { line = false }) { TortugaButton("Play in Tortuga", {}, Modifier.focusRequester(focus)) }
                        }
                        if (page) OverlayPage { TortugaButton("Save", {}) }
                    }
                }
            }
        }
    }

    /** A screen of the back stack, as the app places one. */
    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        val screen = remember { ScreenFocus { true } }
        Box(Modifier.fillMaxSize().then(screen.modifier).focusGate { true }) {
            CompositionLocalProvider(LocalScreenFocus provides screen) { Column { content() } }
        }
    }

    private fun TrackersSettingsUiState.with(service: TrackerService, change: (TrackerServiceRow) -> TrackerServiceRow) =
        copy(services = services.map { if (it.service == service) change(it) else it })

    private fun connect(name: String) = T.settings.trackers.connectNamed(name)

    private fun disconnect(name: String) = T.settings.trackers.disconnectNamed(name)

    private fun connecting(service: TrackerService) = TrackerConnectUiState(
        service = service,
        title = "Connect ${service.name}",
        step = TrackerConnectStep.Starting("Asking for a code…"),
        openInBrowserLabel = "Open in browser",
        retryLabel = "Try again",
        cancelLabel = "Cancel",
    )

    private fun confirmation(service: TrackerService) = TrackerConfirmDialog(
        service = service,
        title = "Disconnect?",
        message = "Tortuga stops sending what you watch.",
        confirmLabel = "Disconnect",
        cancelLabel = "Cancel",
        destructive = true,
    )

    private fun service(service: TrackerService, name: String, connected: Boolean) = TrackerServiceRow(
        service = service,
        name = name,
        status = if (connected) "Connected" else "Not connected",
        connected = connected,
        username = if (connected) "tester" else null,
        available = true,
        canConnect = !connected,
        connectLabel = "Connect",
        connectNamedLabel = connect(name),
        disconnectLabel = "Disconnect",
        disconnectNamedLabel = disconnect(name),
        account = if (connected) {
            TrackerAccountUiState(
                scrobble = SettingToggleRow("TRAKT.scrobble", "Send what you watch", null, true),
                filtersHeader = "What to send",
                movies = SettingToggleRow("TRAKT.movies", "Movies", null, true),
                series = SettingToggleRow("TRAKT.series", "Series", null, true),
                anime = SettingToggleRow("TRAKT.anime", "Anime", null, true),
                queuedCount = 0,
                queued = null,
                failedCount = 0,
                failed = null,
            )
        } else {
            null
        },
    )

    private val intents = TrackersSettingsIntents(
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
