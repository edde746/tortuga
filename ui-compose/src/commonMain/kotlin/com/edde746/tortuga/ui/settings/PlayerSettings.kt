package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.presentation.settings.CustomPlayerForm
import com.edde746.tortuga.presentation.settings.PlayerChoiceRow
import com.edde746.tortuga.presentation.settings.PlayerSettingsStore
import com.edde746.tortuga.presentation.settings.PlayerSettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TextAndTrailing
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * Settings → Playback → Player's row ([PlayerSettingsUiState.row]): "Player", the player every play on this device
 * opens in, and "This device only" (the pane's other settings are the profile's); its press opens the page ([onOpen]).
 * No row where the platform has no player apps ([player] null). Harness name: `settings.playback.player`.
 */
internal fun playerSettingsItem(player: PlayerSettingsUiState?, onOpen: () -> Unit): SettingsItem? {
    val row = player?.row ?: return null
    return SettingsItem("player") { shape ->
        SettingsLinkRow(row.title, row.note, row.valueLabel, onOpen, shape, Modifier.layoutProbe("settings.playback.player"))
    }
}

/** [store]'s Player page ([PlayerSettingsOverlay]) over the Playback pane while it is open ([state]). */
@Composable
internal fun PlayerSettingsPage(store: PlayerSettingsStore, state: PlayerSettingsUiState) {
    if (!state.pageOpen) return
    PlayerSettingsOverlay(
        state = state,
        onSelect = store::select,
        onDelete = store::deleteCustom,
        onAddCustom = store::openAddCustom,
        onSaveCustom = store::saveCustom,
        onCloseCustom = store::closeAddCustom,
        onClose = store::closePage,
    )
}

/**
 * Settings → Playback → Player's page ([PlayerSettingsUiState]): what the choice does, then one list to choose from:
 * Tortuga, the players found on this device (with their icons where the platform draws them), the chosen one when it
 * is missing (marked), a spinner while the players are looked for, and the custom players (each with its Delete) with
 * "Add custom player" at their end. The chosen one shows a check, and takes focus as the page opens. Choosing another
 * plays everything on this device in it from now on ([onSelect]).
 *
 * "Add custom player" ([onAddCustom]) puts its form ([CustomPlayerFormContent]) in the page's place. Back (Esc, the
 * remote's Back), a click outside and Cancel close the form first, back to the list with focus on "Add custom player";
 * then Back, a click outside and Close close the page ([onClose]).
 *
 * Harness names: `settings.player`, `settings.player.option.<player id>` with a custom player's
 * `settings.player.option.<player id>.delete`, `settings.player.addCustom`, `settings.player.close`; the form's
 * `settings.player.custom.<name|command|arguments|error|cancel|save>`.
 */
@Composable
internal fun PlayerSettingsOverlay(
    state: PlayerSettingsUiState,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddCustom: () -> Unit,
    onSaveCustom: (name: String, command: String, arguments: String) -> Unit,
    onCloseCustom: () -> Unit,
    onClose: () -> Unit,
) {
    val form = state.customForm
    val selected = remember { FocusRequester() }
    val addCustom = remember { FocusRequester() }
    // One dismissal for the dialog, whichever step it shows: the form closes first, then the page.
    val formOpen by rememberUpdatedState(form != null)
    val closeCustom by rememberUpdatedState(onCloseCustom)
    val close by rememberUpdatedState(onClose)
    val dismiss = remember { { if (formOpen) closeCustom() else close() } }
    ModalOverlay(title = form?.title ?: state.title, onDismiss = dismiss, modifier = Modifier.layoutProbe("settings.player"), initialFocus = selected) {
        if (form != null) {
            CustomPlayerFormContent(form, onSaveCustom, onCloseCustom)
        } else {
            PlayerChoices(state, selected, addCustom, onSelect, onDelete, onAddCustom)
            OverlayActions {
                TortugaButton(T.settings.close, onClose, Modifier.layoutProbe("settings.player.close"), style = ButtonStyle.PRIMARY)
            }
        }
    }
    // Back from the form: focus to "Add custom player", which opened it.
    var formShown by remember { mutableStateOf(false) }
    LaunchedEffect(form != null) {
        if (form != null) {
            formShown = true
        } else if (formShown) {
            withFrameNanos { }
            addCustom.requestFocus()
        }
    }
}

/** The page's note and its list: the players, the spinner while they are looked for, and the custom ones. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.PlayerChoices(
    state: PlayerSettingsUiState,
    selected: FocusRequester,
    addCustom: FocusRequester,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddCustom: () -> Unit,
) {
    Text(state.note, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
    // Every row keeps the room of a custom player's Delete, so the groups stay one width.
    val reserveDelete = state.customPlayers.any { it.deleteText != null }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        state.players.forEachIndexed { index, row ->
            PlayerChoiceItem(row, groupShape(index, state.players.size), selected, reserveDelete, onSelect, onDelete, custom = false)
        }
        if (state.detecting) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
        }
        state.customHeader?.let { header ->
            GroupHeader(header)
            val count = state.customPlayers.size + if (state.addCustomText != null) 1 else 0
            state.customPlayers.forEachIndexed { index, row ->
                PlayerChoiceItem(row, groupShape(index, count), selected, reserveDelete, onSelect, onDelete, custom = true)
            }
            state.addCustomText?.let { text ->
                WithDeleteRoom(reserveDelete) {
                    GroupRow(
                        text,
                        onClick = onAddCustom,
                        modifier = Modifier.focusRequester(addCustom).layoutProbe("settings.player.addCustom"),
                        shape = groupShape(count - 1, count),
                        icon = TortugaIcons.Add,
                    )
                }
            }
        }
    }
}

/**
 * A player to choose: its icon (where [PlayerChoiceRow.hasIcon]), its name and line, an error mark when it is
 * [PlayerChoiceRow.missing], and a check when it is the chosen one; a custom player's Delete beside it. Its name is
 * content text (a player's own name, or one someone typed) unless it is missing ("… not found"); so is a [custom]
 * player's line (its command).
 */
@Composable
private fun PlayerChoiceItem(
    row: PlayerChoiceRow,
    shape: Shape,
    selected: FocusRequester,
    reserveDelete: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    custom: Boolean,
) {
    val probe = "settings.player.option.${row.id}"
    val delete = row.deleteText?.let { label ->
        @Composable { TortugaIconButton(TortugaIcons.Delete, label, { onDelete(row.id) }, Modifier.layoutProbe("$probe.delete"), style = IconButtonStyle.PLAIN) }
    }
    WithDeleteRoom(reserveDelete, delete) {
        TortugaSurface(
            onClick = { onSelect(row.id) },
            modifier = Modifier.fillMaxWidth().then(if (row.selected) Modifier.focusRequester(selected) else Modifier).layoutProbe(probe),
            shape = shape,
            role = Role.RadioButton,
            selected = row.selected,
            grows = false,
        ) {
            Row(
                Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (row.hasIcon) LocalTortugaPlatform.current.PlayerAppIcon(row.id, Modifier.size(32.dp))
                TextAndTrailing(
                    text = { RowText(row.name, row.detail, contentTitle = !row.missing, contentSubtitle = custom) },
                    trailing = {
                        if (row.missing || row.selected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (row.missing) Icon(TortugaIcons.ErrorOutline, contentDescription = null, Modifier.size(20.dp), tint = TortugaColors.Error)
                                if (row.selected) Icon(TortugaIcons.Check, contentDescription = null, Modifier.size(20.dp))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** [content] as wide as the list leaves it, then [delete] (a custom player's Delete), or its room when [reserve]. */
@Composable
private fun WithDeleteRoom(reserve: Boolean, delete: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) { content() }
        when {
            delete != null -> delete()
            reserve -> Spacer(Modifier.size(LocalTortugaDimens.current.iconButtonSize))
        }
    }
}

/**
 * The Add custom player form ([CustomPlayerForm]) in the page's place: the name, then the program, URL template or
 * package name as this platform takes it ([CustomPlayerForm.commandLabel]), the arguments where it takes them
 * (prefilled with [CustomPlayerForm.argumentsDefault]), the line explaining the placeholders, why the last Save was
 * refused, and Cancel and Save. The fields keep what's typed; the store hears it on Save ([onSave]; the arguments empty
 * where the form has none), and keeps the form open with its error when it refuses it. The name takes focus as the form
 * opens; Up and Down move between the fields and the buttons (D-pad, arrow keys; Down from the last field lands on
 * Save, [OverlayActions]), Enter moves to the next field and saves from the last. On TV the keyboard waits for a field
 * to be pressed.
 */
@Composable
private fun ColumnScope.CustomPlayerFormContent(form: CustomPlayerForm, onSave: (name: String, command: String, arguments: String) -> Unit, onCancel: () -> Unit) {
    val nameFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val name = remember { TextFieldState() }
    val command = remember { TextFieldState() }
    val arguments = remember { TextFieldState(form.argumentsDefault) }
    val argumentsLabel = form.argumentsLabel
    val save = { onSave(name.text.toString(), command.text.toString(), if (argumentsLabel != null) arguments.text.toString() else "") }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerFormField(
            name, form.nameLabel, form.nameHint, "settings.player.custom.name",
            KeyboardType.Text, KeyboardCapitalization.Words, last = false, onDone = save, modifier = Modifier.focusRequester(nameFocus),
        )
        PlayerFormField(
            command, form.commandLabel, form.commandHint, "settings.player.custom.command",
            if (form.kind == CustomPlayerKind.URL_TEMPLATE) KeyboardType.Uri else KeyboardType.Ascii, KeyboardCapitalization.None,
            last = argumentsLabel == null, onDone = save,
        )
        if (argumentsLabel != null) {
            PlayerFormField(
                arguments, argumentsLabel, form.argumentsHint, "settings.player.custom.arguments",
                KeyboardType.Ascii, KeyboardCapitalization.None, last = true, onDone = save,
            )
        }
        form.placeholders?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted) }
        form.error?.let { InlineError(it, Modifier.layoutProbe("settings.player.custom.error")) }
    }
    OverlayActions(primary = saveFocus) {
        TortugaButton(form.cancelText, onCancel, Modifier.layoutProbe("settings.player.custom.cancel"), style = ButtonStyle.TEXT)
        TortugaButton(form.saveText, { save() }, Modifier.focusRequester(saveFocus).layoutProbe("settings.player.custom.save"), style = ButtonStyle.PRIMARY)
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        nameFocus.requestFocus()
    }
}

/**
 * One single-line field of the form: [label] over it, [hint] while it is empty. A single-line field would move its
 * cursor on Up and Down; here they move focus to the field or button above or below. Enter (and the soft keyboard's
 * Next) moves on to the next field, or saves ([onDone]) from the [last].
 */
@Composable
private fun PlayerFormField(
    state: TextFieldState,
    label: String,
    hint: String?,
    probe: String,
    keyboardType: KeyboardType,
    capitalization: KeyboardCapitalization,
    last: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = rememberKeyboardOnSelect()
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        readOnly = keyboard.readOnly,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        onKeyboardAction = { if (last) onDone() else focusManager.moveFocus(FocusDirection.Down) },
        modifier = modifier
            .fillMaxWidth()
            .then(keyboard.modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        if (last) onDone() else focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    else -> false
                }
            }
            .remoteLeavesField(state)
            .inViewAboveKeyboard()
            .layoutProbe(probe),
    )
}
