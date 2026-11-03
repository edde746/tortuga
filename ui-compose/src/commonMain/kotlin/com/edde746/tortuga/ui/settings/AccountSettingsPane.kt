package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.presentation.settings.AccountActionRow
import com.edde746.tortuga.presentation.settings.AccountConfirmDialog
import com.edde746.tortuga.presentation.settings.AccountSettingsStore
import com.edde746.tortuga.presentation.settings.AccountSettingsUiState
import com.edde746.tortuga.presentation.settings.LinkApprovalStep
import com.edde746.tortuga.presentation.settings.LinkApprovalUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.api.formatLinkCode
import com.edde746.tortuga.sync.api.typedLinkCode
import com.edde746.tortuga.ui.account.providerIcon
import com.edde746.tortuga.ui.account.providerLogo
import com.edde746.tortuga.ui.components.Badge
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.ErrorState
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TextAndTrailing
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/** What the account section asks of [AccountSettingsStore]; the route builds it from the store's methods. */
internal class AccountSettingsIntents(
    val refresh: () -> Unit,
    val syncNow: () -> Unit,
    val linkIdentity: () -> Unit,
    val unlinkIdentity: (id: String) -> Unit,
    val revokeDevice: (id: String) -> Unit,
    val signOut: () -> Unit,
    val deleteAccount: () -> Unit,
    val confirmDialog: () -> Unit,
    val dismissDialog: () -> Unit,
    val dismissNotice: () -> Unit,
    val startLinkApproval: () -> Unit,
    /** The code field changed to `text`, its letters and digits as typed; the store keeps them grouped for submit. */
    val updateLinkCode: (text: String) -> Unit,
    val submitLinkCode: () -> Unit,
    val approveLink: () -> Unit,
    val rejectLink: () -> Unit,
    val closeLinkApproval: () -> Unit,
)

/** The account section over [store]. */
@Composable
internal fun AccountSettingsRoute(store: AccountSettingsStore) {
    val state by store.state.collectAsState()
    val intents = remember(store) {
        AccountSettingsIntents(
            refresh = store::refresh,
            syncNow = store::syncNow,
            linkIdentity = store::linkIdentity,
            unlinkIdentity = store::unlinkIdentity,
            revokeDevice = store::revokeDevice,
            signOut = store::signOut,
            deleteAccount = store::deleteAccount,
            confirmDialog = store::confirmDialog,
            dismissDialog = store::dismissDialog,
            dismissNotice = store::dismissNotice,
            startLinkApproval = store::startLinkApproval,
            updateLinkCode = store::updateLinkCode,
            submitLinkCode = store::submitLinkCode,
            approveLink = store::approveLink,
            rejectLink = store::rejectLink,
            closeLinkApproval = store::closeLinkApproval,
        )
    }
    AccountSettingsPane(state, intents)
}

/**
 * The account: sync status and server, sign-ins, devices (revoking one asks first), approving a new
 * device's link by its code, sign-out and account deletion. Destructive actions confirm in a dialog the store words;
 * what an action did shows as a notice.
 *
 * Harness names: `settings.account.<syncNow|linkIdentity|approveLink|signOut|delete>`,
 * `settings.account.identity.<i>.unlink`, `settings.account.device.<i>.revoke`, `settings.account.dialog.<confirm|cancel>`,
 * `settings.account.link.<code|submit|approve|reject|done|cancel>`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountSettingsPane(state: AccountSettingsUiState, intents: AccountSettingsIntents, modifier: Modifier = Modifier) {
    val enabled = !state.working
    SettingsList(modifier) {
        // One item whether or not a notice shows: a lazy list keeps its first visible item in place, so a notice
        // inserted above it later would sit above the view.
        item(key = "notice") { state.notice?.let { notice -> NoticeBanner(notice, T.settings.close, intents.dismissNotice) } }
        settingsGroup(
            "sync",
            null,
            listOf(
                SettingsItem("status") { shape -> GroupRow(state.statusLabel, null, shape = shape, subtitle = state.lastSynced, value = state.status) },
                state.server?.let { server ->
                    SettingsItem("server") { shape -> GroupRow(state.serverLabel, null, shape = shape, value = server) }
                },
                if (state.signedIn) {
                    SettingsItem("syncNow") { shape ->
                        GroupRow(
                            state.syncNowLabel,
                            onClick = intents.syncNow,
                            modifier = Modifier.layoutProbe("settings.account.syncNow"),
                            shape = shape,
                            icon = TortugaIcons.Sync,
                            enabled = state.canSyncNow,
                        )
                    }
                } else {
                    null
                },
            ),
        )
        if (state.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
            }
        }
        state.loadError?.let { error ->
            item(key = "loadError") { ErrorState(error, Modifier.padding(vertical = 8.dp), onRetry = intents.refresh) }
        }
        if (state.signedIn) {
            settingsGroup(
                "identities",
                state.identitiesHeader,
                state.identities.mapIndexed { index, identity ->
                    SettingsItem("identity:${identity.id}") { shape ->
                        GroupRow(
                            identity.provider,
                            null,
                            shape = shape,
                            subtitle = identity.detail,
                            icon = providerIcon(identity.providerId),
                            logo = providerLogo(identity.providerId),
                        ) {
                            if (identity.canUnlink) {
                                TortugaIconButton(
                                    TortugaIcons.Delete,
                                    identity.unlinkLabel,
                                    { intents.unlinkIdentity(identity.id) },
                                    Modifier.layoutProbe("settings.account.identity.$index.unlink"),
                                    style = IconButtonStyle.PLAIN,
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                } + listOfNotNull(
                    if (state.canLinkIdentity) {
                        SettingsItem("linkIdentity") { shape ->
                            GroupRow(
                                state.linkIdentityLabel,
                                onClick = intents.linkIdentity,
                                modifier = Modifier.layoutProbe("settings.account.linkIdentity"),
                                shape = shape,
                                icon = SettingsIcons.Link,
                                enabled = enabled,
                            )
                        }
                    } else {
                        null
                    },
                ),
            )
            settingsGroup(
                "devices",
                state.devicesHeader,
                state.devices.mapIndexed { index, device ->
                    SettingsItem("device:${device.id}") { shape -> DeviceRow(device.name, device.detail, device.currentLabel, shape) {
                        TortugaButton(
                            T.settings.account.revoke.confirm,
                            { intents.revokeDevice(device.id) },
                            Modifier.layoutProbe("settings.account.device.$index.revoke").semantics { contentDescription = device.revokeLabel },
                            style = ButtonStyle.TEXT,
                            enabled = enabled,
                        )
                    } }
                } + SettingsItem("approveLink") { shape ->
                    GroupRow(
                        state.approveLinkLabel,
                        onClick = intents.startLinkApproval,
                        modifier = Modifier.layoutProbe("settings.account.approveLink"),
                        shape = shape,
                        icon = TortugaIcons.Devices,
                        enabled = enabled,
                    )
                },
            )
            settingsGroup(
                "leave",
                null,
                listOf(
                    SettingsItem("signOut") { shape ->
                        GroupRow(
                            state.signOutLabel,
                            onClick = intents.signOut,
                            modifier = Modifier.layoutProbe("settings.account.signOut"),
                            shape = shape,
                            icon = TortugaIcons.Logout,
                            enabled = enabled,
                            destructive = true,
                        )
                    },
                    SettingsItem("delete") { shape -> DestructiveRow(state.deleteAccount, enabled, intents.deleteAccount, "settings.account.delete", shape) },
                ),
            )
        }
    }
    state.dialog?.let { ConfirmOverlay(it, intents) }
    state.linkApproval?.let { LinkApprovalOverlay(it, intents) }
}

/**
 * A device: its name (content text: a device's own), a detail line, a solid badge for this device ("This device") and
 * its action ([TextAndTrailing]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(title: String, detail: String?, badge: String?, shape: Shape, action: @Composable () -> Unit) {
    TextAndTrailing(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The badge goes under the name when the two don't fit side by side (a phone's cover screen).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.contentDirection(), modifier = Modifier.align(Alignment.CenterVertically))
                    if (badge != null) Badge(badge, Modifier.align(Alignment.CenterVertically))
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted)
            }
        },
        trailing = action,
        modifier = Modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHigh, shape)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        spacing = 12.dp,
    )
}

/** "Delete account": an action with its consequence, in the error colour. */
@Composable
private fun DestructiveRow(row: AccountActionRow, enabled: Boolean, onClick: () -> Unit, probe: String, shape: Shape) {
    GroupRow(
        row.title,
        onClick = onClick,
        modifier = Modifier.layoutProbe(probe),
        shape = shape,
        subtitle = row.subtitle,
        icon = SettingsIcons.Warning,
        enabled = enabled,
        destructive = true,
    )
}

/** A destructive action's confirmation, worded by the store; Cancel has focus first. */
@Composable
private fun ConfirmOverlay(dialog: AccountConfirmDialog, intents: AccountSettingsIntents) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = intents.dismissDialog, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(dialog.cancelLabel, intents.dismissDialog, Modifier.focusRequester(cancelFocus).layoutProbe("settings.account.dialog.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(
                dialog.confirmLabel,
                intents.confirmDialog,
                Modifier.layoutProbe("settings.account.dialog.confirm"),
                style = if (dialog.destructive) ButtonStyle.DESTRUCTIVE else ButtonStyle.PRIMARY,
            )
        }
    }
}

/**
 * Approving a new device: its code typed (large, drawn upper-case and grouped, [LinkCodeField]; Enter or the remote's
 * OK continues), checked with the server, then the device shown for a yes or no, then the outcome.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkApprovalOverlay(link: LinkApprovalUiState, intents: AccountSettingsIntents) {
    val primary = remember { FocusRequester() }
    ModalOverlay(title = link.title, onDismiss = intents.closeLinkApproval, initialFocus = primary) {
        val outcome: @Composable (String) -> Unit = { result ->
            Text(result, style = MaterialTheme.typography.bodyLarge)
            OverlayActions {
                TortugaButton(T.settings.account.link.done, intents.closeLinkApproval, Modifier.focusRequester(primary).layoutProbe("settings.account.link.done"), style = ButtonStyle.PRIMARY)
            }
        }
        when (val step = link.step) {
            is LinkApprovalStep.EnterCode -> {
                Text(link.instructions, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
                LinkCodeField(link.codeHint, step, intents, Modifier.focusRequester(primary))
                step.error?.let { InlineError(it) }
                val submit = remember { FocusRequester() }
                OverlayActions(primary = submit) {
                    TortugaButton(T.settings.account.cancel, intents.closeLinkApproval, Modifier.layoutProbe("settings.account.link.cancel"), style = ButtonStyle.TEXT)
                    TortugaButton(link.submitLabel, intents.submitLinkCode, Modifier.focusRequester(submit).layoutProbe("settings.account.link.submit"), style = ButtonStyle.PRIMARY, enabled = step.canSubmit)
                }
            }
            LinkApprovalStep.Checking, LinkApprovalStep.Approving, LinkApprovalStep.Rejecting -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(56.dp)) }
            }
            is LinkApprovalStep.Confirm -> {
                Text(step.question, style = MaterialTheme.typography.titleLarge)
                Box(Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(TortugaIcons.Devices, contentDescription = null)
                        Column {
                            Text(step.deviceName, style = MaterialTheme.typography.titleMedium)
                            Text(step.devicePlatform, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted)
                        }
                    }
                }
                Text(step.consequence, style = MaterialTheme.typography.bodyMedium)
                OverlayActions {
                    TortugaButton(link.rejectLabel, intents.rejectLink, Modifier.layoutProbe("settings.account.link.reject"), style = ButtonStyle.TEXT)
                    TortugaButton(link.approveLabel, intents.approveLink, Modifier.focusRequester(primary).layoutProbe("settings.account.link.approve"), style = ButtonStyle.PRIMARY)
                }
            }
            is LinkApprovalStep.Approved -> outcome(step.result)
            is LinkApprovalStep.Rejected -> outcome(step.result)
        }
    }
    // Each step's main control takes focus as the step appears (not as the code is typed), so a remote user can go on at once.
    LaunchedEffect(link.step::class) {
        withFrameNanos { }
        primary.requestFocus()
    }
}

/**
 * The link code's field. It keeps the letters and digits as typed, refusing what isn't part of a code
 * ([LinkCodeInput]), and only draws them grouped ([LinkCodeGrouping]): replacing a field's text while keys come in
 * restarts the keyboard's input on Android, and a key typed during the restart is lost. The store hears each change. On
 * TV the keyboard waits for the field to be pressed. A refused code, a failed answer or an expired link comes back to
 * the code with the store's code cleared and the error shown: the field starts again from the store's code, a change
 * not sent back as if typed, which clears the error. The step between (checking, the device's question) may never have
 * been drawn, so the error's arrival is the sign.
 */
@Composable
internal fun LinkCodeField(codeHint: String, entry: LinkApprovalStep.EnterCode, intents: AccountSettingsIntents, modifier: Modifier) {
    val code = remember { TextFieldState(typedLinkCode(entry.code)) }
    val keyboard = rememberKeyboardOnSelect()
    var given by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.error) {
        val stored = typedLinkCode(entry.code)
        if (entry.error != null && code.text.toString() != stored) {
            given = stored
            code.setTextAndPlaceCursorAtEnd(stored)
        }
    }
    val submit = { if (entry.canSubmit) intents.submitLinkCode() }
    OutlinedTextField(
        state = code,
        inputTransformation = LinkCodeInput,
        outputTransformation = LinkCodeGrouping,
        lineLimits = TextFieldLineLimits.SingleLine,
        readOnly = keyboard.readOnly,
        placeholder = { Text(codeHint, style = codeStyle(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        textStyle = codeStyle().copy(textAlign = TextAlign.Center),
        isError = entry.error != null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Go,
        ),
        onKeyboardAction = { submit() },
        modifier = modifier
            .fillMaxWidth()
            .then(keyboard.modifier)
            .onPreviewKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (enter && event.type == KeyEventType.KeyDown) submit()
                enter
            }
            .remoteLeavesField(code)
            .inViewAboveKeyboard()
            .layoutProbe("settings.account.link.code"),
    )
    OnTextChange(code) { text ->
        if (text != given) intents.updateLinkCode(text)
        given = null
    }
}

/** What isn't part of a link code (a dash, a space, a character past its length) is refused ([typedLinkCode]). */
private object LinkCodeInput : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val typed = asCharSequence().toString()
        val kept = typedLinkCode(typed)
        if (kept == typed) return
        replace(0, length, kept)
        placeCursorAtEnd()
    }
}

/**
 * A link code's letters and digits drawn as the store groups them ([formatLinkCode]): "ABCD-EF". Each letter is drawn
 * in its capital in its place and each dash inserted, so the cursor keeps its place among the typed characters.
 */
private object LinkCodeGrouping : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        // The buffer grows with each dash inserted, keeping its offsets in step with the grouped text's.
        for ((index, char) in formatLinkCode(asCharSequence().toString()).withIndex()) {
            when {
                char == '-' -> insert(index, "-")
                asCharSequence()[index] != char -> replace(index, index + 1, char.toString())
            }
        }
    }
}

@Composable
private fun codeStyle() = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
