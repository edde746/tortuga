package com.edde746.tortuga.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.watch.RecentRoomUi
import com.edde746.tortuga.presentation.watch.RelayStatusUi
import com.edde746.tortuga.presentation.watch.WatchLobbyUiState
import com.edde746.tortuga.presentation.watch.WatchRelayRow
import com.edde746.tortuga.presentation.watch.WatchStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.account.ErrorLine
import com.edde746.tortuga.ui.account.NoticeCard
import com.edde746.tortuga.ui.account.ProgressLine
import com.edde746.tortuga.ui.account.qrSize
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.Chevron
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.RowGroup
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.TimedToast
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.FocusInto
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.settings.InlineError
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.watch.WatchControlMode

/**
 * The Watch together screen of [app] ([Route.WatchTogether]): its [WatchStore] lives while the route is on the back
 * stack. The room's code goes to the clipboard and its invite to the platform's share sheet, where there is one.
 */
@Composable
fun WatchRoute(app: AppSession) {
    val platform = LocalTortugaPlatform.current
    val navigator = app.navigator
    // Once per appearance: the entry still draws while it animates out after its route left the stack (its store
    // released), and an entry first composed after that gets no store and draws nothing.
    val store = remember(app) {
        navigator.retain(Route.WatchTogether, WatchStore.KEY, create = app::watchStore, release = WatchStore::release)
    } ?: return
    val state by store.state.collectAsState()
    val intents = remember(store, platform) {
        WatchIntents(
            setJoinText = store::setJoinText,
            join = store::join,
            enterRecent = store::enterRecent,
            removeRecent = store::removeRecent,
            retryRelay = store::retryRelay,
            setRelay = store::setRelay,
            resetRelay = store::resetRelay,
            dismissFailure = store::dismissFailure,
            toastShown = store::toastShown,
            copyCode = platform::setClipboardText,
            canShare = platform.canShare,
            share = platform::share,
            makeHost = store::makeHost,
            kick = store::kick,
            setControlMode = store::setControlMode,
            setLocked = store::setLocked,
            joinPlayback = store::joinPlayback,
            leave = store::leave,
            end = store::end,
            joinInvite = store::joinInvite,
            dismissInvite = store::dismissInvite,
        )
    }
    WatchScreen(state, intents, onBack = if (navigator.canGoBack) ({ navigator.back() }) else null)
}

/** What the Watch together screen asks of [WatchStore] and the platform; the route builds it from their methods. */
class WatchIntents(
    val setJoinText: (text: String) -> Unit,
    val join: () -> Unit,
    val enterRecent: (code: String, relay: String) -> Unit,
    val removeRecent: (code: String, relay: String) -> Unit,
    val retryRelay: () -> Unit,

    /** Sets this device's relay; false, with nothing stored, when `url` isn't a server's address. */
    val setRelay: (url: String) -> Boolean,
    val resetRelay: () -> Unit,
    val dismissFailure: () -> Unit,
    val toastShown: () -> Unit,

    /** Puts the room's `code` on the clipboard. */
    val copyCode: (code: String) -> Unit,

    /** The platform has a share sheet for the invite ([share]). */
    val canShare: Boolean,
    val share: (text: String) -> Unit,
    val makeHost: (peerId: String) -> Unit,
    val kick: (peerId: String) -> Unit,
    val setControlMode: (mode: WatchControlMode) -> Unit,
    val setLocked: (locked: Boolean) -> Unit,
    val joinPlayback: () -> Unit,
    val leave: () -> Unit,
    val end: () -> Unit,

    /** Joins the room of the invite that came in, leaving this one ([WatchLobbyUiState.inviteQuestion]), or stays ([dismissInvite]). */
    val joinInvite: () -> Unit,
    val dismissInvite: () -> Unit,
)

/**
 * Watch together (the lobby, pushed over Home): out of a room, what it is and that a
 * room starts in the player, the relay's state (checking, or unreachable with Try again), Join a room (a field taking
 * a code or a pasted invite link: the desktop's way in from an invite), the recent rooms (enter one again, or remove
 * it) and this device's relay ([WatchRelayOverlay] edits it); in a room, the room ([WatchRoomContent]: the code beside
 * its QR code on TV and wide windows), where a guest waits for the host to play something, and, when an invite link to
 * another room came in, the room's Leave question ([ConfirmQuestion]). A failure shows as a notice over it until
 * dismissed, work in progress as a line with the actions off, and the room's latest event as a toast at the bottom
 * ([TimedToast], which TalkBack speaks) until it has shown ([WatchIntents.toastShown]). Focus moves into the page as it
 * opens and whenever it changes between the lobby and the room (a remote's user lands on the join field, then on Copy
 * once in the room). Back leaves it, as every pushed screen.
 *
 * Harness names: `watch.header`, `watch.column` (the page under it), `watch.relay.<status|retry>`, `watch.busy`,
 * `watch.failure`, `watch.join.field`, `watch.join`, `watch.join.error`, `watch.recent.<i>` with `.remove`,
 * `watch.relay.setting`, `watch.toast`, the relay's editor's ([WatchRelayOverlay]) and the room's ([WatchRoomContent]).
 */
@Composable
fun WatchScreen(state: WatchLobbyUiState, intents: WatchIntents, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val dimens = LocalTortugaDimens.current
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val content = remember { FocusRequester() }
    val room = state.room
    val busy = state.busy != null
    var editingRelay by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = statusBarOverlap)) {
            ScreenHeader(state.title, Modifier.contentColumn().layoutProbe("watch.header", reportsFocus = false), onBack = onBack)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = tv || maxWidth >= 720.dp
                Column(
                    Modifier
                        .contentColumn()
                        .fillMaxSize()
                        .layoutProbe("watch.column", reportsFocus = false)
                        .focusRequester(content)
                        .focusGroup()
                        .verticalScroll(rememberScrollState())
                        .padding(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.focusReach, bottom = dimens.sectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.failure?.let { NoticeCard(it, intents.dismissFailure, Modifier.layoutProbe("watch.failure")) }
                    state.busy?.let { ProgressLine(it, Modifier.layoutProbe("watch.busy")) }
                    if (room != null) {
                        WatchRoomContent(
                            room,
                            RoomActions(
                                onCopy = intents.copyCode,
                                onShare = if (intents.canShare) intents.share else null,
                                onMakeHost = intents.makeHost,
                                onKick = intents.kick,
                                onControlMode = intents.setControlMode,
                                onLock = intents.setLocked,
                                onJoinPlayback = intents.joinPlayback,
                                onLeave = intents.leave,
                                onEnd = intents.end,
                            ),
                            wide = wide,
                            qrSize = qrSize(),
                            enabled = !busy,
                        )
                    } else {
                        Lobby(state, intents, busy, onEditRelay = { editingRelay = true })
                    }
                }
            }
        }
        state.toast?.let { TimedToast(it, "watch.toast", intents.toastShown, Modifier.align(Alignment.BottomCenter).padding(bottom = dimens.sectionSpacing)) }
    }
    if (editingRelay) {
        WatchRelayOverlay(
            state.relaySetting,
            onSave = { intents.setRelay(it).also { saved -> if (saved) editingRelay = false } },
            onReset = {
                intents.resetRelay()
                editingRelay = false
            },
            onClose = { editingRelay = false },
        )
    }
    state.inviteQuestion?.let {
        ConfirmQuestion(it, if (state.room == null) "watch.invite" else "watch.room.leave", onConfirm = intents.joinInvite, onCancel = intents.dismissInvite)
    }
    // Into the page as it opens, and again as it turns from the lobby to the room, to busy and back (the control that
    // had focus may have gone or turned off).
    FocusInto(content, key = if (room != null) "room:${room.code}" else if (busy) "busy" else "lobby:${state.failure}")
}

/** Out of a room: the intro, the relay's state, Join, the recent rooms and this device's relay. */
@Composable
private fun Lobby(state: WatchLobbyUiState, intents: WatchIntents, busy: Boolean, onEditRelay: () -> Unit) {
    Text(state.intro, style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted)
    RelayStatus(state.relay, intents.retryRelay)
    Column {
        GroupHeader(state.joinTitle)
        JoinField(state, intents)
    }
    if (state.recentRooms.isNotEmpty()) {
        Column {
            GroupHeader(state.recentTitle)
            RowGroup {
                state.recentRooms.forEachIndexed { index, recent ->
                    RecentRoomRow(recent, index, state.recentRooms.size, enabled = !busy, intents)
                }
            }
        }
    }
    val relay = state.relaySetting
    GroupRow(
        relay.title,
        onClick = onEditRelay,
        modifier = Modifier.layoutProbe("watch.relay.setting"),
        subtitle = relay.subtitle,
        value = relay.valueLabel,
        enabled = !busy,
        trailing = { Chevron() },
    )
}

/** The relay check: a progress line while checking; unreachable, why and Try again; nothing once it answered. */
@Composable
private fun RelayStatus(relay: RelayStatusUi, onRetry: () -> Unit) {
    val status = relay.status ?: return
    if (relay.unreachable) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ErrorLine(status, Modifier.weight(1f).layoutProbe("watch.relay.status"))
            TortugaButton(relay.retryLabel, onRetry, Modifier.layoutProbe("watch.relay.retry"), icon = TortugaIcons.Refresh)
        }
    } else {
        ProgressLine(status, Modifier.layoutProbe("watch.relay.status"))
    }
}

/**
 * The join field (a code, or an invite link pasted whole) and Join, enabled once it holds one; why it can't under it.
 * The field is a pill, as the search field, and Join beside it is as tall. Enter (the keyboard's, or the soft
 * keyboard's Go) joins. The field keeps its own text and tells the store each change; it empties itself as it joins.
 * On TV the keyboard waits for the field to be pressed, and Right at the text's end goes on to Join ([remoteLeavesField]).
 */
@Composable
private fun JoinField(state: WatchLobbyUiState, intents: WatchIntents) {
    val keyboard = rememberKeyboardOnSelect()
    val focusManager = LocalFocusManager.current
    // The cursor after what was typed, as the field comes back with it.
    val field = remember { TextFieldState(state.joinText) }
    val submit = {
        if (state.canJoin) {
            intents.join()
            field.clearText()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The row is as tall as the field; Join fills that height, so the two line up top and bottom.
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state = field,
                lineLimits = TextFieldLineLimits.SingleLine,
                readOnly = keyboard.readOnly,
                isError = state.joinError != null,
                placeholder = { Text(state.joinHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TortugaColors.Primary,
                    unfocusedBorderColor = TortugaColors.TextFaint,
                    cursorColor = TortugaColors.Primary,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                onKeyboardAction = { submit() },
                modifier = Modifier
                    .weight(1f)
                    .then(keyboard.modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // A hardware Enter joins, as the soft keyboard's Go does.
                            Key.Enter, Key.NumPadEnter -> {
                                submit()
                                true
                            }
                            // A single-line field would move the cursor; Down leaves it for what is under it (D-pad).
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            else -> false
                        }
                    }
                    .remoteLeavesField(field)
                    .inViewAboveKeyboard()
                    .layoutProbe("watch.join.field"),
            )
            TortugaButton(state.joinLabel, submit, Modifier.fillMaxHeight().layoutProbe("watch.join"), style = ButtonStyle.PRIMARY, enabled = state.canJoin)
        }
        state.joinError?.let { InlineError(it, Modifier.layoutProbe("watch.join.error")) }
    }
    OnTextChange(field) { if (it != state.joinText) intents.setJoinText(it) }
}

/** A recent room: its code as people read it and when it was used (choosing it enters the room again), and Remove at its end. */
@Composable
private fun RecentRoomRow(recent: RecentRoomUi, index: Int, count: Int, enabled: Boolean, intents: WatchIntents) {
    val shape = groupShape(index, count)
    Row(
        Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, shape).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TortugaSurface(
            onClick = { intents.enterRecent(recent.code, recent.relay) },
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).layoutProbe("watch.recent.$index"),
            shape = shape,
            color = TortugaColors.SurfaceHigh,
            enabled = enabled,
            contentAlignment = Alignment.CenterStart,
            grows = false,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(recent.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                Text(recent.detail, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.6f))
            }
        }
        TortugaIconButton(
            TortugaIcons.Close,
            recent.removeLabel,
            { intents.removeRecent(recent.code, recent.relay) },
            Modifier.layoutProbe("watch.recent.$index.remove"),
            style = IconButtonStyle.PLAIN,
            enabled = enabled,
        )
    }
}

/**
 * Editing the watch-together relay: a field starting at the stored relay (empty for the public relays, an example
 * address as the placeholder). Save stores what's typed (empty: the public relays) and closes, unless it isn't a
 * server's address, which the line under the field then says; "Use the public relays" (while another is set) goes back
 * to them. Cancel, Back and a click outside close it. On TV the keyboard waits for the field to be pressed.
 *
 * Harness names: `watch.relay.<field|error|reset|cancel|save>`.
 */
@Composable
private fun WatchRelayOverlay(row: WatchRelayRow, onSave: (String) -> Boolean, onReset: () -> Unit, onClose: () -> Unit) {
    val keyboard = rememberKeyboardOnSelect()
    val field = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The field keeps its own text: the store only hears of it on Save.
    val text = remember { TextFieldState(row.value) }
    var refused by remember { mutableStateOf(false) }
    val save = { refused = !onSave(text.text.toString()) }
    val saveFocus = remember { FocusRequester() }
    ModalOverlay(title = row.title, onDismiss = onClose, initialFocus = field) {
        Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        OutlinedTextField(
            state = text,
            lineLimits = TextFieldLineLimits.SingleLine,
            readOnly = keyboard.readOnly,
            isError = refused,
            placeholder = { Text(row.placeholder) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { save() },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .then(keyboard.modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // A hardware Enter saves, as the soft keyboard's Done does.
                        Key.Enter, Key.NumPadEnter -> {
                            save()
                            true
                        }
                        // A single-line field would move the cursor; Down leaves it for the buttons (D-pad, arrows).
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> false
                    }
                }
                .remoteLeavesField(text)
                .inViewAboveKeyboard()
                .layoutProbe("watch.relay.field"),
        )
        if (refused) InlineError(row.invalid, Modifier.layoutProbe("watch.relay.error"))
        OverlayActions(primary = saveFocus) {
            if (row.custom) TortugaButton(row.resetLabel, onReset, Modifier.layoutProbe("watch.relay.reset"), style = ButtonStyle.TEXT)
            TortugaButton(T.settings.cancel, onClose, Modifier.layoutProbe("watch.relay.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(row.saveLabel, { save() }, Modifier.focusRequester(saveFocus).layoutProbe("watch.relay.save"), style = ButtonStyle.PRIMARY)
        }
    }
    OnTextChange(text) { refused = false }
}
