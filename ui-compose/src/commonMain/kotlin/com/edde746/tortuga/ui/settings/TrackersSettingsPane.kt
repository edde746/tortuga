package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.TrackerAccountUiState
import com.edde746.tortuga.presentation.settings.TrackerConfirmDialog
import com.edde746.tortuga.presentation.settings.TrackerConnectStep
import com.edde746.tortuga.presentation.settings.TrackerConnectUiState
import com.edde746.tortuga.presentation.settings.TrackerServiceRow
import com.edde746.tortuga.presentation.settings.TrackersSettingsStore
import com.edde746.tortuga.presentation.settings.TrackersSettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.ui.account.ErrorLine
import com.edde746.tortuga.ui.account.ProgressLine
import com.edde746.tortuga.ui.account.QrCodeImage
import com.edde746.tortuga.ui.account.qrSize
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TextAndTrailing
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.detail.mark
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/** What the trackers section asks of [TrackersSettingsStore]; the route builds it from the store's methods. */
internal class TrackersSettingsIntents(
    val connect: (service: TrackerService) -> Unit,
    val retryConnect: () -> Unit,
    val cancelConnect: () -> Unit,
    val openInBrowser: () -> Unit,
    val disconnect: (service: TrackerService) -> Unit,
    val confirmDialog: () -> Unit,
    val dismissDialog: () -> Unit,
    val dismissNotice: () -> Unit,
    val setScrobbling: (service: TrackerService, on: Boolean) -> Unit,
    val setMovies: (service: TrackerService, on: Boolean) -> Unit,
    val setSeries: (service: TrackerService, on: Boolean) -> Unit,
    val setAnime: (service: TrackerService, on: Boolean) -> Unit,
)

/** The trackers section over [store]. */
@Composable
internal fun TrackersSettingsRoute(store: TrackersSettingsStore) {
    val state by store.state.collectAsState()
    val intents = remember(store) {
        TrackersSettingsIntents(
            connect = store::connect,
            retryConnect = store::retryConnect,
            cancelConnect = store::cancelConnect,
            openInBrowser = store::openInBrowser,
            disconnect = store::disconnect,
            confirmDialog = store::confirmDialog,
            dismissDialog = store::dismissDialog,
            dismissNotice = store::dismissNotice,
            setScrobbling = store::setScrobbling,
            setMovies = store::setMovies,
            setSeries = store::setSeries,
            setAnime = store::setAnime,
        )
    }
    TrackersSettingsPane(state, intents)
}

/**
 * The tracker services: each one's connection on this profile and device, Connect (a device code with
 * its QR code and countdown, or a sign-in link, over the section) or Disconnect (after a confirmation), and a
 * connected account's switches: sending at all, what to send, the changes waiting to be sent and those that couldn't be.
 * What an action did shows as a notice.
 *
 * Harness names: `settings.trackers.notice`, `settings.trackers.<service id>.<status|connect|disconnect|scrobble|movies|
 * series|anime|queued|failed>`, `settings.trackers.connect.<code|qr|link|expires|message|openInBrowser|retry|cancel>`,
 * `settings.trackers.dialog.<confirm|cancel>`.
 */
@Composable
internal fun TrackersSettingsPane(state: TrackersSettingsUiState, intents: TrackersSettingsIntents, modifier: Modifier = Modifier) {
    SettingsList(modifier) {
        // One item whether or not a notice shows: a lazy list keeps its first visible item in place, so a notice
        // inserted above it later would sit above the view.
        item(key = "notice") {
            state.notice?.let { notice ->
                NoticeBanner(notice, T.settings.close, intents.dismissNotice, Modifier.layoutProbe("settings.trackers.notice"))
            }
        }
        item(key = "note") { SettingsNote(state.note) }
        for (row in state.services) service(row, intents)
    }
    state.connect?.let { ConnectOverlay(it, intents) }
    state.dialog?.let { DisconnectOverlay(it, intents) }
}

/**
 * One service as a group: its mark, name and connection with Connect or Disconnect, then, once connected, sending at
 * all; under its own heading what to send; then the changes waiting to be sent and those that couldn't be.
 */
private fun LazyListScope.service(row: TrackerServiceRow, intents: TrackersSettingsIntents) {
    val id = row.service.id
    val name = "settings.trackers.$id"
    val account = row.account
    settingsGroup(
        id,
        null,
        listOf(
            SettingsItem("$id.status") { shape -> ServiceRow(row, name, shape, intents) },
            account?.let { SettingsItem("$id.scrobble") { shape -> SettingToggleItem(it.scrobble, { on -> intents.setScrobbling(row.service, on) }, shape, Modifier.layoutProbe("$name.scrobble")) } },
        ),
    )
    if (account != null) {
        settingsGroup(
            "$id.filters",
            account.filtersHeader,
            listOf(
                SettingsItem("$id.movies") { shape -> SettingToggleItem(account.movies, { intents.setMovies(row.service, it) }, shape, Modifier.layoutProbe("$name.movies")) },
                SettingsItem("$id.series") { shape -> SettingToggleItem(account.series, { intents.setSeries(row.service, it) }, shape, Modifier.layoutProbe("$name.series")) },
                SettingsItem("$id.anime") { shape -> SettingToggleItem(account.anime, { intents.setAnime(row.service, it) }, shape, Modifier.layoutProbe("$name.anime")) },
            ),
        )
        val lines = listOfNotNull(
            account.queued?.let { Triple("queued", it, TortugaIcons.SyncProblem) },
            account.failed?.let { Triple("failed", it, TortugaIcons.ErrorOutline) },
        )
        for ((key, text, icon) in lines) {
            item(key = "$id.$key") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).layoutProbe("$name.$key"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = TortugaColors.TextMuted)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
                }
            }
        }
    }
}

/**
 * A service's first row: its mark, name and connection, with Connect or Disconnect beside them, or under them at the
 * row's end where beside them the text would be too narrow ([TextAndTrailing]: a foldable's cover screen).
 */
@Composable
private fun ServiceRow(row: TrackerServiceRow, name: String, shape: Shape, intents: TrackersSettingsIntents) {
    val compact = LocalWindowLayout.current.widthClass == WidthClass.COMPACT
    Row(
        Modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHigh, shape)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(row.service.mark, contentDescription = null, Modifier.size(24.dp))
        TextAndTrailing(
            text = {
                Column(Modifier.layoutProbe("$name.status"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (row.connected) Icon(TortugaIcons.CheckCircle, contentDescription = null, Modifier.size(16.dp), tint = TortugaColors.Text)
                        Text(row.status, style = MaterialTheme.typography.bodyMedium, color = if (row.connected) TortugaColors.Text else TortugaColors.TextMuted)
                    }
                }
            },
            trailing = {
                when {
                    row.connected -> TortugaButton(
                        row.disconnectLabel,
                        { intents.disconnect(row.service) },
                        Modifier.layoutProbe("$name.disconnect").semantics { contentDescription = row.disconnectNamedLabel },
                        style = ButtonStyle.SECONDARY,
                        icon = if (compact) null else TortugaIcons.Logout,
                    )
                    row.available -> TortugaButton(
                        row.connectLabel,
                        { intents.connect(row.service) },
                        Modifier.layoutProbe("$name.connect").semantics { contentDescription = row.connectNamedLabel },
                        style = ButtonStyle.PRIMARY,
                        icon = if (compact) null else SettingsIcons.Link,
                        enabled = row.canConnect,
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Connecting a service, over the whole Settings screen: while the app waits, the device code (large, to type on a phone
 * or computer) with the page to type it on, its QR code, the countdown and the waiting line, or the sign-in link as a
 * QR code; the outcome with Try again when it didn't connect. Wide windows and TVs put the QR code beside the text, as
 * large as the card's height allows, so code and QR code show whole without scrolling (a TV is 540 dp high); phones
 * stack them and scroll. The step's main button has focus (Open in browser while waiting, where the device offers it,
 * else Cancel; Try again after a failure), so a remote can go on at once.
 */
@Composable
private fun ConnectOverlay(connect: TrackerConnectUiState, intents: TrackersSettingsIntents) {
    val layout = LocalWindowLayout.current
    val roomy = layout.formFactor == FormFactor.TV || layout.widthClass != WidthClass.COMPACT
    val primary = remember { FocusRequester() }
    ModalOverlay(title = connect.title, onDismiss = intents.cancelConnect, initialFocus = primary, maxWidth = if (roomy) 900.dp else 560.dp) {
        val step = connect.step
        val qr = when (step) {
            is TrackerConnectStep.Code -> step.code.qrPayload to step.code.scanHint
            is TrackerConnectStep.Link -> step.link.qrPayload to step.link.scanHint
            is TrackerConnectStep.Starting, is TrackerConnectStep.Expired, is TrackerConnectStep.Declined, is TrackerConnectStep.Failed -> null
        }
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val beside = qr != null && maxWidth >= 640.dp
            val side = if (beside) minOf(qrSize(), maxHeight - 24.dp).coerceAtLeast(120.dp) else qrSize()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (beside) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ConnectDetails(step, large = true, scanHint = qr.second)
                        }
                        QrPanel(qr.first, side)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ConnectDetails(step, large = false, scanHint = null)
                        qr?.let { (payload, hint) ->
                            QrPanel(payload, side, Modifier.align(Alignment.CenterHorizontally))
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = TortugaColors.TextMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        val canOpenInBrowser = when (step) {
            is TrackerConnectStep.Code -> step.canOpenInBrowser
            is TrackerConnectStep.Link -> step.canOpenInBrowser
            is TrackerConnectStep.Starting, is TrackerConnectStep.Expired, is TrackerConnectStep.Declined, is TrackerConnectStep.Failed -> false
        }
        val canRetry = step is TrackerConnectStep.Expired || step is TrackerConnectStep.Declined || step is TrackerConnectStep.Failed
        OverlayActions {
            val cancelFocus = if (!canRetry && !canOpenInBrowser) Modifier.focusRequester(primary) else Modifier
            TortugaButton(connect.cancelLabel, intents.cancelConnect, Modifier.layoutProbe("settings.trackers.connect.cancel").then(cancelFocus), style = ButtonStyle.TEXT)
            if (canOpenInBrowser) {
                TortugaButton(
                    connect.openInBrowserLabel,
                    intents.openInBrowser,
                    Modifier.layoutProbe("settings.trackers.connect.openInBrowser").then(if (!canRetry) Modifier.focusRequester(primary) else Modifier),
                    style = ButtonStyle.SECONDARY,
                    icon = TortugaIcons.OpenInNew,
                )
            }
            if (canRetry) {
                TortugaButton(
                    connect.retryLabel,
                    intents.retryConnect,
                    Modifier.layoutProbe("settings.trackers.connect.retry").focusRequester(primary),
                    style = ButtonStyle.PRIMARY,
                    icon = TortugaIcons.Refresh,
                )
            }
        }
    }
    // A new step (the code arriving, a failure) moves focus to its main button; the countdown ticking doesn't.
    LaunchedEffect(connect.step::class) {
        withFrameNanos { }
        primary.requestFocus()
    }
}

/**
 * The connect flow's text: where to go and the code, or the link's instructions; [scanHint] (what the QR code beside it
 * is for); the countdown; the status line.
 */
@Composable
private fun ConnectDetails(step: TrackerConnectStep, large: Boolean, scanHint: String?) {
    val hint: @Composable () -> Unit = {
        if (scanHint != null) Text(scanHint, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
    }
    if (step is TrackerConnectStep.Code) {
        val code = step.code
        Text(code.instructions, style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)) {
            // One line whatever the width: a narrow window's code shrinks rather than breaking in two.
            val style = (if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall)
                .copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
            Text(
                code.userCode,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
                style = style,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp).layoutProbe("settings.trackers.connect.code"),
            )
        }
        hint()
        Row(
            Modifier.layoutProbe("settings.trackers.connect.expires"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(TortugaIcons.Timer, contentDescription = null, Modifier.size(18.dp), tint = TortugaColors.TextMuted)
            Text(code.expiresText, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        }
    }
    if (step is TrackerConnectStep.Link) {
        Text(step.link.instructions, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.layoutProbe("settings.trackers.connect.link"))
        hint()
    }
    val message = Modifier.layoutProbe("settings.trackers.connect.message")
    when (step) {
        is TrackerConnectStep.Starting, is TrackerConnectStep.Code, is TrackerConnectStep.Link -> ProgressLine(step.message, message)
        is TrackerConnectStep.Expired, is TrackerConnectStep.Declined, is TrackerConnectStep.Failed -> ErrorLine(step.message, message)
    }
}

/** The QR code on its light square, [size] wide. */
@Composable
private fun QrPanel(payload: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)) {
        QrCodeImage(payload, T.account.signIn.deviceCode.qrDescription, size, Modifier.padding(12.dp).layoutProbe("settings.trackers.connect.qr"))
    }
}

/** Disconnecting, confirmed first; Cancel has focus, and a destructive Disconnect is in the error tones. */
@Composable
private fun DisconnectOverlay(dialog: TrackerConfirmDialog, intents: TrackersSettingsIntents) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = intents.dismissDialog, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(
                dialog.cancelLabel,
                intents.dismissDialog,
                Modifier.layoutProbe("settings.trackers.dialog.cancel").focusRequester(cancelFocus),
                style = ButtonStyle.TEXT,
            )
            TortugaButton(
                dialog.confirmLabel,
                intents.confirmDialog,
                Modifier.layoutProbe("settings.trackers.dialog.confirm"),
                style = if (dialog.destructive) ButtonStyle.DESTRUCTIVE else ButtonStyle.PRIMARY,
            )
        }
    }
}
