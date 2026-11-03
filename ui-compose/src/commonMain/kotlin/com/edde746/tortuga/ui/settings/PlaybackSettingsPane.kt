package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.LanguageListKind
import com.edde746.tortuga.presentation.settings.LanguagePickerUiState
import com.edde746.tortuga.presentation.settings.PlaybackNumber
import com.edde746.tortuga.presentation.settings.PlaybackSettingRow
import com.edde746.tortuga.presentation.settings.PlaybackSettingsStore
import com.edde746.tortuga.presentation.settings.PlaybackSettingsUiState
import com.edde746.tortuga.presentation.settings.PlayerSettingsStore
import com.edde746.tortuga.presentation.settings.PlayerSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingChosenLanguage
import com.edde746.tortuga.presentation.settings.StreamChoicesKind
import com.edde746.tortuga.presentation.settings.StreamPatternKind
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OrderedRow
import com.edde746.tortuga.ui.components.OrderedRowActions
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * The playback section over [store], with the language editor and the Streams group's overlays over it, and [player]'s
 * row and page (Settings → Playback → Player) where the platform has player apps.
 */
@Composable
internal fun PlaybackSettingsRoute(store: PlaybackSettingsStore, player: PlayerSettingsStore?) {
    val state by store.state.collectAsState()
    val playerState = player?.state?.collectAsState()?.value
    var streamsOverlay by remember { mutableStateOf<StreamSettingsOverlay?>(null) }
    PlaybackSettingsPane(
        state = state,
        player = playerState,
        onChoose = store::choose,
        onToggle = store::toggle,
        onStep = store::stepNumber,
        onOpenLanguages = store::openLanguages,
        onOpenPlayer = { player?.openPage() },
        onOpenStreams = { streamsOverlay = it },
    )
    if (player != null && playerState != null) PlayerSettingsPage(player, playerState)
    state.languagePicker?.let { picker ->
        LanguagePickerOverlay(
            picker = picker,
            onSearch = store::searchLanguages,
            onAdd = store::addLanguage,
            onRemove = store::removeLanguage,
            onMove = store::moveLanguage,
            onMoveTo = store::moveLanguageTo,
            onClose = store::closeLanguages,
        )
    }
    when (val overlay = streamsOverlay) {
        StreamSettingsOverlay.Qualities -> StreamChoicesOverlay(
            state.streamChoices(StreamChoicesKind.QUALITIES), "quality", "qualities", store::setStreamQualityShown, onClose = { streamsOverlay = null },
        )
        StreamSettingsOverlay.Ranges -> StreamChoicesOverlay(
            state.streamChoices(StreamChoicesKind.RANGES), "range", "ranges", store::setStreamRangeShown, onClose = { streamsOverlay = null },
        )
        StreamSettingsOverlay.Codecs -> StreamChoicesOverlay(
            state.streamChoices(StreamChoicesKind.CODECS), "codec", "codecs", store::setStreamCodecShown, onClose = { streamsOverlay = null },
        )
        is StreamSettingsOverlay.Pattern -> {
            val row = state.streamPattern(overlay.kind)
            PatternOverlay(
                title = row.title,
                subtitle = row.subtitle,
                value = row.value,
                placeholder = row.placeholder,
                probe = "settings.streams.pattern",
                errorFor = store::patternError,
                onSave = { pattern ->
                    store.setStreamPattern(overlay.kind, pattern)
                    streamsOverlay = null
                },
                onClose = { streamsOverlay = null },
            )
        }
        null -> {}
    }
}

/**
 * The profile's playback settings (per profile, synced, as the note says): the state's groups and rows in
 * their order, each group under its header and above its footer. This device's Player ([player]: [playerSettingsItem],
 * opening its page through [onOpenPlayer]) heads them where the device has player apps. A choice gives its row's id and
 * the option's to [onChoose], a switch its row's id to [onToggle]. The language lists each open their editor, the
 * Streams group's switches and patterns theirs ([onOpenStreams]).
 *
 * Harness names: `settings.playback.<audioLanguages|subtitleLanguages|player>`, the choices and switches
 * `settings.playback.<row id>` (`showSubtitles`, `bingeWatching`, `videoClick`, `skipIntros`,
 * `skipRecaps`, `skipCredits`, `streams.autoPlay`, `streams.minSize`, `streams.maxSize`), the
 * stepper rows `settings.playback.<watchedThreshold|upNextCountdown|seekShort|seekLong>` with their parts (see
 * [SettingStepperItem]) and `settings.playback.streams.<qualities|ranges|codecs|include|exclude>`.
 */
@Composable
internal fun PlaybackSettingsPane(
    state: PlaybackSettingsUiState,
    player: PlayerSettingsUiState?,
    onChoose: (key: String, optionId: String) -> Unit,
    onToggle: (key: String, checked: Boolean) -> Unit,
    onStep: (PlaybackNumber, up: Boolean) -> Unit,
    onOpenLanguages: (LanguageListKind) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenStreams: (StreamSettingsOverlay) -> Unit,
    modifier: Modifier = Modifier,
) {
    /** [row] as its group's item, named `settings.playback.<name>`; null for the Player without [player]. */
    fun settingsItem(row: PlaybackSettingRow): SettingsItem? {
        return when (row) {
            PlaybackSettingRow.Player -> playerSettingsItem(player, onOpenPlayer)
            is PlaybackSettingRow.Languages -> {
                val name = when (row.kind) {
                    LanguageListKind.AUDIO -> "audioLanguages"
                    LanguageListKind.SUBTITLES -> "subtitleLanguages"
                    LanguageListKind.SECOND_SUBTITLE -> "secondSubtitleLanguage"
                }
                SettingsItem(name) { shape ->
                    SettingsLinkRow(
                        row.row.title, row.row.subtitle, row.row.valueLabel, { onOpenLanguages(row.kind) }, shape,
                        Modifier.layoutProbe("settings.playback.$name"),
                    )
                }
            }
            is PlaybackSettingRow.Choice -> SettingsItem(row.row.id) { shape ->
                SettingChoiceItem(row.row, { onChoose(row.row.id, it) }, "settings.playback.${row.row.id}", shape)
            }
            is PlaybackSettingRow.Stepper -> {
                val name = when (row.field) {
                    PlaybackNumber.WATCHED_THRESHOLD -> "watchedThreshold"
                    PlaybackNumber.UP_NEXT_COUNTDOWN -> "upNextCountdown"
                    PlaybackNumber.SEEK_SHORT -> "seekShort"
                    PlaybackNumber.SEEK_LONG -> "seekLong"
                }
                SettingsItem(name) { shape -> SettingStepperItem(row.row, { up -> onStep(row.field, up) }, "settings.playback.$name", shape) }
            }
            is PlaybackSettingRow.Toggle -> SettingsItem(row.row.id) { shape ->
                SettingToggleItem(row.row, { onToggle(row.row.id, it) }, shape, Modifier.layoutProbe("settings.playback.${row.row.id}"))
            }
            is PlaybackSettingRow.StreamChoices -> {
                val (name, overlay) = when (row.kind) {
                    StreamChoicesKind.QUALITIES -> "qualities" to StreamSettingsOverlay.Qualities
                    StreamChoicesKind.RANGES -> "ranges" to StreamSettingsOverlay.Ranges
                    StreamChoicesKind.CODECS -> "codecs" to StreamSettingsOverlay.Codecs
                }
                SettingsItem("streams.$name") { shape -> ChoicesRow(row.row, { onOpenStreams(overlay) }, "settings.playback.streams.$name", shape) }
            }
            is PlaybackSettingRow.StreamPattern -> {
                val name = when (row.row.kind) {
                    StreamPatternKind.INCLUDE -> "include"
                    StreamPatternKind.EXCLUDE -> "exclude"
                }
                SettingsItem("streams.$name") { shape ->
                    PatternRow(
                        row.row.title, row.row.subtitle, row.row.valueLabel, row.row.error,
                        { onOpenStreams(StreamSettingsOverlay.Pattern(row.row.kind)) }, "settings.playback.streams.$name", shape,
                    )
                }
            }
        }
    }
    SettingsList(modifier) {
        item(key = "note") { SettingsNote(state.note) }
        for (section in state.sections) {
            val key = section.group.name.lowercase()
            settingsGroup(key, section.header, section.rows.map(::settingsItem))
            section.footer?.let { footer -> item(key = "$key.footer") { SettingsNote(footer) } }
        }
    }
}

/**
 * Editing one language list: the chosen languages in order, each an [OrderedRow] (reordered by its drag handle or its
 * menu's Move up and Move down, on TV by its ↑ and ↓ buttons; removed from its menu or with Delete: at once, as a
 * language is added back in a moment) and the others to add, searched by name. Side by side on wide windows, one list
 * on narrow ones.
 *
 * Harness names: `settings.languages.chosen.<i>` with `.handle`, `.more` (⋮), TV's `.up` and `.down`, and its menu
 * `.menu.<up|down|remove>`; `settings.languages.search`, `settings.languages.available.<i>`,
 * `settings.languages.close`.
 */
@Composable
internal fun LanguagePickerOverlay(
    picker: LanguagePickerUiState,
    onSearch: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (code: String, up: Boolean) -> Unit,
    onMoveTo: (code: String, index: Int) -> Unit,
    onClose: () -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    ModalOverlay(title = picker.title, onDismiss = onClose, modifier = Modifier.fillMaxHeight(), initialFocus = searchFocus, maxWidth = 960.dp) {
        Text(picker.explanation, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val reorder = rememberReorderState(4.dp)
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                        chosen(picker, reorder, onRemove, onMove, onMoveTo)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                        available(picker, onSearch, onAdd, searchFocus)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                    chosen(picker, reorder, onRemove, onMove, onMoveTo)
                    available(picker, onSearch, onAdd, searchFocus)
                }
            }
        }
        OverlayActions {
            TortugaButton(T.settings.close, onClose, Modifier.layoutProbe("settings.languages.close"), style = ButtonStyle.PRIMARY)
        }
    }
}

private fun LazyListScope.chosen(
    picker: LanguagePickerUiState,
    reorder: ReorderState,
    onRemove: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onMoveTo: (String, Int) -> Unit,
) {
    item(key = "chosenHeader") { GroupHeader(picker.chosenHeader) }
    picker.noneChosenText?.let { text -> item(key = "noneChosen") { SettingsNote(text) } }
    itemsIndexed(picker.chosen, key = { _, language -> "chosen:${language.code}" }) { index, language ->
        ChosenLanguageRow(language, index, picker.chosen.size, reorder, onRemove, onMove, onMoveTo)
    }
}

@Composable
private fun ChosenLanguageRow(
    language: SettingChosenLanguage,
    index: Int,
    count: Int,
    reorder: ReorderState,
    onRemove: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onMoveTo: (String, Int) -> Unit,
) {
    val probe = "settings.languages.chosen.$index"
    val actions = OrderedRowActions(
        moveUp = ItemMenuEntry(T.settings.playback.moveUp, "up", icon = TortugaIcons.ArrowUpward) { onMove(language.code, true) },
        moveDown = ItemMenuEntry(T.settings.playback.moveDown, "down", icon = TortugaIcons.ArrowDownward) { onMove(language.code, false) },
        moveUpName = language.moveUpLabel,
        moveDownName = language.moveDownLabel,
        canMoveUp = language.canMoveUp,
        canMoveDown = language.canMoveDown,
        remove = ItemMenuEntry(T.settings.playback.removeLanguage, "remove", destructive = true, icon = TortugaIcons.Delete) { onRemove(language.code) },
    )
    // Dragging needs a pointer: TV moves a language by its ↑ and ↓ buttons.
    val draggable = count > 1 && LocalWindowLayout.current.formFactor != FormFactor.TV
    OrderedRow(
        actions = actions,
        title = language.name,
        moreLabel = language.moreLabel,
        probe = probe,
        index = index,
        shape = MaterialTheme.shapes.medium,
        onClick = null,
        modifier = Modifier.reorderItem(reorder, language.code, index),
        surfaceModifier = Modifier.layoutProbe(probe),
        horizontalPadding = 8.dp,
        verticalPadding = 8.dp,
    ) {
        if (draggable) {
            DragHandleIcon(language.dragLabel, Modifier.layoutProbe("$probe.handle").reorderHandle(reorder, language.code, index, count, onMoveTo))
        }
        Text(language.rank, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current.copy(alpha = 0.6f), modifier = Modifier.width(24.dp))
        Text(language.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

private fun LazyListScope.available(picker: LanguagePickerUiState, onSearch: (String) -> Unit, onAdd: (String) -> Unit, searchFocus: FocusRequester) {
    item(key = "availableHeader") { GroupHeader(picker.availableHeader) }
    item(key = "search") {
        // The field keeps its own text (the store's state arrives a frame later, and typing must not wait for it). On TV
        // the keyboard waits for the field to be pressed.
        val query = remember(picker.kind) { TextFieldState(picker.query) }
        val keyboard = rememberKeyboardOnSelect()
        OutlinedTextField(
            state = query,
            lineLimits = TextFieldLineLimits.SingleLine,
            readOnly = keyboard.readOnly,
            placeholder = { Text(picker.searchHint) },
            leadingIcon = { Icon(TortugaIcons.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
                .focusRequester(searchFocus)
                .then(keyboard.modifier)
                .remoteLeavesField(query)
                .layoutProbe("settings.languages.search"),
        )
        OnTextChange(query, onSearch)
    }
    picker.emptyText?.let { text -> item(key = "empty") { SettingsNote(text, Modifier.padding(vertical = 8.dp)) } }
    itemsIndexed(picker.available, key = { _, option -> "available:${option.code}" }) { index, option ->
        TortugaSurface(
            onClick = { onAdd(option.code) },
            modifier = Modifier.fillMaxWidth().layoutProbe("settings.languages.available.$index"),
            shape = MaterialTheme.shapes.medium,
            grows = false,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(TortugaIcons.Add, contentDescription = null, Modifier.size(20.dp), tint = LocalContentColor.current.copy(alpha = 0.6f))
                Text(option.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
