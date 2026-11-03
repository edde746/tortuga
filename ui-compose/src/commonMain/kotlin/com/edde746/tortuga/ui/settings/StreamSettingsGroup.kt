package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.settings.SettingMultiChoiceRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.presentation.settings.StreamPatternKind
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.Chevron
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors

/** What the Streams group has open over the pane: the qualities', ranges' or codecs' switches, or one pattern's editor. */
internal sealed interface StreamSettingsOverlay {
    data object Qualities : StreamSettingsOverlay

    data object Ranges : StreamSettingsOverlay

    data object Codecs : StreamSettingsOverlay

    data class Pattern(val kind: StreamPatternKind) : StreamSettingsOverlay
}

/** A Streams row of switches' options (the qualities, ranges or codecs): the ones on, and a chevron to their switches. */
@Composable
internal fun ChoicesRow(row: SettingMultiChoiceRow, onClick: () -> Unit, probe: String, shape: Shape) {
    SettingsLinkRow(row.title, row.subtitle, row.valueLabel, onClick, shape, Modifier.layoutProbe(probe))
}

/**
 * A typed setting's row (a stream pattern, the poster template): its [valueLabel], and when the stored text is ignored,
 * why ([error], in place of [subtitle]) and a mark.
 */
@Composable
internal fun PatternRow(title: String, subtitle: String, valueLabel: String, error: String?, onClick: () -> Unit, probe: String, shape: Shape) {
    GroupRow(
        title,
        onClick = onClick,
        modifier = Modifier.layoutProbe(probe),
        shape = shape,
        subtitle = error ?: subtitle,
        value = valueLabel,
        trailing = {
            if (error != null) Icon(TortugaIcons.ErrorOutline, contentDescription = null, Modifier.size(20.dp), tint = TortugaColors.Error)
            Chevron()
        },
    )
}

/**
 * A filter's values the stream lists show, one switch each (on: shown): the resolutions, the dynamic ranges or the
 * codecs. Harness names: `settings.streams.<item>.<id>`, `settings.streams.<list>.close` (`quality`, `qualities`;
 * `range`, `ranges`; `codec`, `codecs`).
 */
@Composable
internal fun StreamChoicesOverlay(row: SettingMultiChoiceRow, item: String, list: String, onShown: (id: String, shown: Boolean) -> Unit, onClose: () -> Unit) {
    ModalOverlay(title = row.title, onDismiss = onClose) {
        row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted) }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            row.options.forEachIndexed { index, option ->
                SettingToggleItem(
                    SettingToggleRow(option.id, option.label, subtitle = null, checked = option.selected),
                    { shown -> onShown(option.id, shown) },
                    groupShape(index, row.options.size),
                    Modifier.layoutProbe("settings.streams.$item.${option.id}"),
                )
            }
        }
        OverlayActions {
            TortugaButton(T.settings.close, onClose, Modifier.layoutProbe("settings.streams.$list.close"), style = ButtonStyle.PRIMARY)
        }
    }
}

/**
 * Editing a typed setting (a stream pattern, the poster template): [subtitle], a text field starting at [value] with
 * [placeholder], typed as [keyboardType] with no autocorrect or capitals, and under it, while what's typed would be
 * ignored, [errorFor]'s line. Save stores what's typed (an ignored value is kept), Clear stores none; both close it, as
 * Cancel, Back and a click outside do. On TV the keyboard waits for the field to be pressed.
 *
 * Harness names: `<probe>.<field|error|clear|cancel|save>`.
 */
@Composable
internal fun PatternOverlay(
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    probe: String,
    errorFor: (String) -> String?,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    val keyboard = rememberKeyboardOnSelect()
    val field = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The field keeps its own text: the store only hears of it on Save.
    val text = remember(title) { TextFieldState(value) }
    val error = errorFor(text.text.toString())
    ModalOverlay(title = title, onDismiss = onClose, initialFocus = field) {
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        OutlinedTextField(
            state = text,
            lineLimits = TextFieldLineLimits.SingleLine,
            readOnly = keyboard.readOnly,
            isError = error != null,
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { onSave(text.text.toString()) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .then(keyboard.modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // A hardware Enter saves, as the soft keyboard's Done does.
                        Key.Enter, Key.NumPadEnter -> {
                            onSave(text.text.toString())
                            true
                        }
                        // A single-line field would move the cursor; Down leaves it for the buttons (D-pad, arrows).
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> false
                    }
                }
                .remoteLeavesField(text)
                .inViewAboveKeyboard()
                .layoutProbe("$probe.field"),
        )
        if (error != null) InlineError(error, Modifier.layoutProbe("$probe.error"))
        OverlayActions(primary = saveFocus) {
            TortugaButton(T.settings.playback.streams.clear, { onSave("") }, Modifier.layoutProbe("$probe.clear"), style = ButtonStyle.TEXT)
            TortugaButton(T.settings.cancel, onClose, Modifier.layoutProbe("$probe.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(T.settings.playback.streams.save, { onSave(text.text.toString()) }, Modifier.focusRequester(saveFocus).layoutProbe("$probe.save"), style = ButtonStyle.PRIMARY)
        }
    }
}
