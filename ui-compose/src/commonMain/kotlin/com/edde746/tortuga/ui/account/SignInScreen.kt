package com.edde746.tortuga.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.presentation.account.SignInConfirmUiState
import com.edde746.tortuga.presentation.account.SignInServerUiState
import com.edde746.tortuga.presentation.account.SignInStep
import com.edde746.tortuga.presentation.account.SignInStore
import com.edde746.tortuga.presentation.account.SignInUiState
import com.edde746.tortuga.presentation.account.signInStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.components.ButtonLabelInset
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.rememberLiveStore
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * The sign-in screen of [root] ([com.edde746.tortuga.presentation.account.AccountDestination.SignIn]): its
 * [SignInStore] lives while the screen shows. Coming back to the window (from approving on another device) asks the
 * device link for its result at once. A sign-in to another account than the one whose unsynced changes this device
 * holds asks first ([SignInUiState.confirm]).
 */
@Composable
fun SignInRoute(root: RootSession) {
    val store = rememberLiveStore(SignInStore.KEY, { root.graph.signInStore(root.gate, root.signIn) }, SignInStore::release)
    val state by store.state.collectAsState()
    val notice = root.gateState.collectAsState().value.notice
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) store.onForeground() }
    SignInScreen(
        state = state,
        notice = notice,
        intents = SignInIntents(
            signIn = store::signIn,
            cancel = store::cancel,
            retry = store::retry,
            editServer = store::editServer,
            setServerField = store::setServerField,
            submitServer = store::submitServer,
            cancelServerEdit = store::cancelServerEdit,
            useHostedServer = store::useHostedServer,
            linkDevice = store::linkDevice,
            restartLink = store::restartLink,
            dismissNotice = root.gate::dismissNotice,
        ),
    )
    state.confirm?.let { UnsyncedOverlay(it, store) }
}

/** Whether to delete the changes of another account that never synced; Cancel has focus first. */
@Composable
private fun UnsyncedOverlay(confirm: SignInConfirmUiState, store: SignInStore) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = confirm.title, onDismiss = store::keepUnsynced, initialFocus = cancelFocus) {
        Text(confirm.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(confirm.cancelLabel, store::keepUnsynced, Modifier.focusRequester(cancelFocus).layoutProbe("signIn.unsynced.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(confirm.confirmLabel, store::discardUnsynced, Modifier.layoutProbe("signIn.unsynced.confirm"), style = ButtonStyle.DESTRUCTIVE)
        }
    }
}

/** What the sign-in screen reports; each maps to the [SignInStore] intent of the same name. */
class SignInIntents(
    val signIn: (provider: String) -> Unit,
    val cancel: () -> Unit,
    val retry: () -> Unit,
    val editServer: () -> Unit,
    val setServerField: (String) -> Unit,
    val submitServer: () -> Unit,
    val cancelServerEdit: () -> Unit,
    val useHostedServer: () -> Unit,
    val linkDevice: () -> Unit,
    val restartLink: () -> Unit,
    val dismissNotice: () -> Unit,
)

/**
 * Sign-in, one step at a time: the provider buttons with the server line (and the self-hosted server
 * field), a running flow's progress with cancel, a TV's device code, and linking from another device. Every text
 * comes from [state] or `T`; [notice] is why this device was signed out.
 */
@Composable
fun SignInScreen(state: SignInUiState, notice: String?, intents: SignInIntents, modifier: Modifier = Modifier) {
    when (val step = state.step) {
        is SignInStep.Choose -> AccountPage(T.account.signIn.title, T.account.signIn.intro, modifier) {
            if (notice != null) NoticeCard(notice, intents.dismissNotice)
            Choices(state, step.error, intents)
        }
        // The header's back button cancels the sign-in in progress (and has focus, so a remote's OK does).
        is SignInStep.Working -> AccountPage(T.account.signIn.title, null, modifier, onBack = intents.cancel, backProbe = "signIn.back", focusBack = true) {
            ProgressLine(step.progress, Modifier.layoutProbe("signIn.progress"))
        }
        is SignInStep.DeviceCode -> DeviceCodePage(T.account.signIn.title, null, step.code, step.progress, intents.cancel, backProbe = "signIn.back", modifier = modifier)
        is SignInStep.Link -> DeviceLinkPage(step.link, intents.restartLink, intents.cancel, backProbe = "signIn.back", modifier = modifier)
    }
}

/**
 * The provider buttons (or why there are none), the link choice, and the server with its field. The buttons stand in the
 * room of [SignInUiState.optionsRoom] buttons laid out unseen and out of reach, the spinner over the first while they
 * load: whatever the server answers (fewer buttons than room, none, why it couldn't be reached), what lies under them
 * stays put as it comes; [error] (why the last attempt failed) under them.
 */
@Composable
private fun Choices(state: SignInUiState, error: String?, intents: SignInIntents) {
    val first = remember { FocusRequester() }
    val server = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth()) {
        // Spaced as the page's parts (AccountPage).
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(state.optionsRoom) { index ->
                Box(contentAlignment = Alignment.CenterStart) {
                    TortugaButton(" ", {}, Modifier.alpha(0f).clearAndSetSemantics {}, icon = TortugaIcons.Devices, enabled = false, fill = true)
                    if (index == 0 && state.optionsLoading) ProgressLine(T.account.signIn.loadingOptions)
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.optionsLoading) {
                if (state.optionsRoom == 0) ProgressLine(T.account.signIn.loadingOptions)
            } else {
                state.options.forEachIndexed { index, option ->
                    TortugaButton(
                        option.label,
                        { intents.signIn(option.provider) },
                        Modifier.layoutProbe("signIn.option.${option.provider}").then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        style = if (index == 0) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                        icon = providerIcon(option.provider),
                        logo = providerLogo(option.provider),
                        fill = true,
                    )
                }
                state.optionsNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted) }
                if (state.canRetry) TortugaButton(T.account.signIn.retry, intents.retry, Modifier.layoutProbe("signIn.retry"), style = ButtonStyle.SECONDARY, icon = TortugaIcons.Refresh)
            }
        }
    }
    error?.let { ErrorLine(it, Modifier.layoutProbe("signIn.error")) }
    TortugaButton(T.account.signIn.linkDevice, intents.linkDevice, Modifier.layoutProbe("signIn.link"), style = ButtonStyle.SECONDARY, icon = TortugaIcons.Devices, fill = true)
    Text(T.account.signIn.linkDeviceHint, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted)
    HorizontalDivider(Modifier.fillMaxWidth(), color = TortugaColors.Hairline)
    Server(state.server, intents, server)
    // Keyboards and remotes start on the first way in; with none offered, on the server line.
    LaunchedEffect(state.options.isNotEmpty(), state.optionsLoading, state.server.editing) {
        when {
            state.server.editing -> Unit
            state.options.isNotEmpty() -> first.requestFocus()
            !state.optionsLoading -> server.requestFocus()
        }
    }
}

/**
 * "Server: api.usetortuga.app" with "Use your own server" (or back to the hosted one), or the address field while editing.
 * [editFocus] is on "Use your own server". Down from the field lands on the button under it that had focus last, else
 * Check server (Cancel while Check server is off), whatever their labels' widths ([focusEntry]). On TV the keyboard waits
 * for the field to be pressed.
 */
@Composable
private fun Server(server: SignInServerUiState, intents: SignInIntents, editFocus: FocusRequester) {
    if (!server.editing) {
        ServerLine(server, intents, editFocus)
        return
    }
    val field = remember { FocusRequester() }
    val submit = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The field keeps its own text and cursor (the cursor a remote leaves the field by), the store hears each change.
    val text = remember { TextFieldState(server.field) }
    val keyboard = rememberKeyboardOnSelect()
    SystemBackHandler(onBack = intents.cancelServerEdit)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            state = text,
            label = { Text(T.account.signIn.serverField) },
            placeholder = { Text(T.account.signIn.serverFieldHint) },
            lineLimits = TextFieldLineLimits.SingleLine,
            enabled = !server.checking,
            readOnly = keyboard.readOnly,
            isError = server.error != null,
            supportingText = server.error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            onKeyboardAction = { intents.submitServer() },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .then(keyboard.modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // A hardware Enter; the soft keyboard's Go comes through onKeyboardAction. The store checks
                        // nothing while Check server is off (`canSubmit`).
                        Key.Enter, Key.NumPadEnter -> {
                            intents.submitServer()
                            true
                        }
                        // A single-line field would move the cursor; Down leaves it for its buttons (D-pad: Back
                        // there cancels the edit).
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> false
                    }
                }
                .remoteLeavesField(text)
                .inViewAboveKeyboard()
                .layoutProbe("signIn.server.field"),
        )
        if (server.checking) ProgressLine(T.account.signIn.checkingServer)
        Row(Modifier.focusEntry(submit), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TortugaButton(
                T.account.signIn.checkServer,
                intents.submitServer,
                Modifier.layoutProbe("signIn.server.submit").focusRequester(submit),
                style = ButtonStyle.PRIMARY,
                enabled = server.canSubmit,
            )
            TortugaButton(T.account.signIn.cancel, intents.cancelServerEdit, Modifier.layoutProbe("signIn.server.cancel"), style = ButtonStyle.TEXT)
        }
    }
    OnTextChange(text, intents.setServerField)
    LaunchedEffect(Unit) { field.requestFocus() }
}

/**
 * The server and the buttons that change it, on one line where they fit (a long address cut short, down to half the
 * line); otherwise the buttons go on the line under it, their labels in line with the column's edge as the server's
 * icon is (a text button stands out by its label's inset). The line is one focus group as wide as the column: a move
 * into it (Down from Link device) lands on the button that had focus last, else on "Use your own server" ([editFocus]),
 * wherever the address leaves the buttons ([focusEntry]).
 */
@Composable
private fun ServerLine(server: SignInServerUiState, intents: SignInIntents, editFocus: FocusRequester) {
    Layout(
        modifier = Modifier.focusEntry(editFocus),
        contents = listOf(
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(TortugaIcons.Dns, contentDescription = null, Modifier.size(18.dp), tint = TortugaColors.TextMuted)
                    Text(
                        server.text,
                        Modifier.layoutProbe("signIn.server"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TortugaColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            {
                TortugaButton(T.account.signIn.useOwnServer, intents.editServer, Modifier.layoutProbe("signIn.server.edit").focusRequester(editFocus), style = ButtonStyle.TEXT)
                if (server.custom) {
                    TortugaButton(T.account.signIn.useHostedServer, intents.useHostedServer, Modifier.layoutProbe("signIn.server.hosted"), style = ButtonStyle.TEXT)
                }
            },
        ),
    ) { (labels, buttons), constraints ->
        val width = constraints.maxWidth
        val gap = 4.dp.roundToPx()
        val inset = ButtonLabelInset.roundToPx()
        val label = labels.single()
        val placed = buttons.map { it.measure(Constraints(maxWidth = width + inset)) }
        val buttonsWidth = placed.sumOf { it.width } + gap * (placed.size - 1)
        val room = width - buttonsWidth - 8.dp.roundToPx()
        if (room >= minOf(label.maxIntrinsicWidth(Constraints.Infinity), width / 2)) {
            val text = label.measure(Constraints(maxWidth = room))
            val height = maxOf(text.height, placed.maxOf { it.height })
            layout(width, height) {
                text.placeRelative(0, (height - text.height) / 2)
                var x = text.width + 8.dp.roundToPx()
                for (button in placed) {
                    button.placeRelative(x, (height - button.height) / 2)
                    x += button.width + gap
                }
            }
        } else {
            val text = label.measure(Constraints(maxWidth = width))
            // The buttons in rows from the inset out, as many a row as fit.
            val rows = mutableListOf<MutableList<Placeable>>()
            var rowWidth = 0
            for (button in placed) {
                if (rows.isEmpty() || rowWidth + gap + button.width > width + inset) {
                    rows.add(mutableListOf(button))
                    rowWidth = button.width
                } else {
                    rows.last().add(button)
                    rowWidth += gap + button.width
                }
            }
            val heights = rows.map { row -> row.maxOf { it.height } }
            layout(width, text.height + heights.sumOf { it + gap }) {
                text.placeRelative(0, 0)
                var y = text.height + gap
                rows.forEachIndexed { index, row ->
                    var x = -inset
                    for (button in row) {
                        button.placeRelative(x, y + (heights[index] - button.height) / 2)
                        x += button.width + gap
                    }
                    y += heights[index] + gap
                }
            }
        }
    }
}
