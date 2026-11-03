package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.settings.DeleteAllDownloadsDialog
import com.edde746.tortuga.presentation.settings.DownloadFolderRow
import com.edde746.tortuga.presentation.settings.DownloadSettingsStore
import com.edde746.tortuga.presentation.settings.DownloadSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * The downloads section over [store], with the subtitle languages' editor (Playback's [LanguagePickerOverlay]) and
 * Delete all's confirmation over it. The desktop's Change folder opens the platform's folder picker
 * ([com.edde746.tortuga.ui.app.TortugaPlatform.chooseFolder]); the storage is read again each time it shows.
 */
@Composable
internal fun DownloadSettingsRoute(store: DownloadSettingsStore) {
    val state by store.state.collectAsState()
    val platform = LocalTortugaPlatform.current
    LaunchedEffect(store) { store.refreshStorage() }
    DownloadSettingsPane(
        state = state,
        onChoose = store::choose,
        onToggle = store::toggle,
        onChangeFolder = { folder -> platform.chooseFolder(folder.changeLabel, folder.path.ifBlank { null }, store::setFolder) },
        onStepAtOnce = store::stepAtOnce,
        onOpenLanguages = store::openLanguages,
        onUseProfileLanguages = store::useProfileLanguages,
        onDeleteAll = store::requestDeleteAll,
    )
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
    state.deleteAllDialog?.let { dialog -> DeleteAllOverlay(dialog, onConfirm = store::confirmDeleteAll, onDismiss = store::dismissDialog) }
}

/**
 * This device's download settings (never synced, as the note says): where downloads go (Android's locations as a
 * choice; the desktop's folder with Change folder), then Wi-Fi only, downloads at once and auto-remove watched, the
 * subtitle languages saved with each download (opening their editor; "Use my subtitle languages" while a list is chosen
 * here), and the storage with Delete all (confirmed first). Rows this device doesn't offer are absent from [state]. A
 * choice gives its row's id and the option's to [onChoose], a switch its row's id to [onToggle].
 *
 * Harness names: `settings.downloads.<location|wifiOnly|autoRemoveWatched|subtitleLanguages|useProfileLanguages|storage|
 * deleteAll>`, the location's options `settings.downloads.location.option.<i>`, the folder `settings.downloads.folder`
 * with its `.change`, the stepper `settings.downloads.atOnce` with its parts (see [SettingStepperItem]), and the
 * confirmation's `settings.downloads.deleteAll.<cancel|confirm>`.
 */
@Composable
internal fun DownloadSettingsPane(
    state: DownloadSettingsUiState,
    onChoose: (key: String, optionId: String) -> Unit,
    onToggle: (key: String, checked: Boolean) -> Unit,
    onChangeFolder: (DownloadFolderRow) -> Unit,
    onStepAtOnce: (up: Boolean) -> Unit,
    onOpenLanguages: () -> Unit,
    onUseProfileLanguages: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun toggle(row: SettingToggleRow) =
        SettingsItem(row.id) { shape -> SettingToggleItem(row, { onToggle(row.id, it) }, shape, Modifier.layoutProbe("settings.downloads.${row.id}")) }
    SettingsList(modifier) {
        item(key = "note") { SettingsNote(state.note) }
        settingsGroup(
            "location",
            null,
            listOf(
                state.location?.let { row ->
                    SettingsItem(row.id) { shape -> SettingChoiceItem(row, { onChoose(row.id, it) }, "settings.downloads.${row.id}", shape) }
                },
                state.folder?.let { folder -> SettingsItem("folder") { shape -> FolderRow(folder, { onChangeFolder(folder) }, shape) } },
            ),
        )
        settingsGroup(
            "transfer",
            state.transferHeader,
            listOf(
                state.wifiOnly?.let(::toggle),
                SettingsItem("atOnce") { shape -> SettingStepperItem(state.atOnce, onStepAtOnce, "settings.downloads.atOnce", shape) },
                toggle(state.autoRemoveWatched),
            ),
        )
        settingsGroup(
            "subtitles",
            null,
            listOf(
                SettingsItem("subtitleLanguages") { shape ->
                    val row = state.subtitleLanguages
                    SettingsLinkRow(row.title, row.subtitle, row.valueLabel, onOpenLanguages, shape, Modifier.layoutProbe("settings.downloads.subtitleLanguages"))
                },
                state.useProfileLanguagesText?.let { text ->
                    SettingsItem("useProfileLanguages") { shape ->
                        GroupRow(text, onClick = onUseProfileLanguages, modifier = Modifier.layoutProbe("settings.downloads.useProfileLanguages"), shape = shape)
                    }
                },
            ),
        )
        settingsGroup(
            "storage",
            state.storageHeader,
            listOf(
                SettingsItem("storage") { shape ->
                    GroupRow(state.storage, onClick = null, modifier = Modifier.layoutProbe("settings.downloads.storage"), shape = shape, icon = TortugaIcons.Download)
                },
                SettingsItem("deleteAll") { shape ->
                    GroupRow(
                        state.deleteAll.label,
                        onClick = onDeleteAll,
                        modifier = Modifier.layoutProbe("settings.downloads.deleteAll"),
                        shape = shape,
                        icon = TortugaIcons.Delete,
                        enabled = state.deleteAll.enabled,
                        destructive = true,
                    )
                },
            ),
        )
    }
}

/**
 * The desktop's download folder: the row's title and hint, then the folder's path beside Change folder, which opens the
 * platform's folder picker.
 */
@Composable
private fun FolderRow(folder: DownloadFolderRow, onChange: () -> Unit, shape: Shape) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHigh, shape)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .layoutProbe("settings.downloads.folder"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RowText(folder.title, folder.subtitle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(TortugaIcons.Folder, contentDescription = null, Modifier.size(20.dp), tint = TortugaColors.TextMuted)
            Text(
                folder.path,
                style = MaterialTheme.typography.bodyMedium.contentDirection(),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TortugaButton(folder.changeLabel, onChange, Modifier.layoutProbe("settings.downloads.folder.change"), style = ButtonStyle.TEXT, grows = false)
        }
    }
}

/** Delete all's question, worded by the store (it deletes every profile's downloads on this device); Cancel has focus first. */
@Composable
private fun DeleteAllOverlay(dialog: DeleteAllDownloadsDialog, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = onDismiss, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(dialog.cancelLabel, onDismiss, Modifier.focusRequester(cancelFocus).layoutProbe("settings.downloads.deleteAll.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(dialog.confirmLabel, onConfirm, Modifier.layoutProbe("settings.downloads.deleteAll.confirm"), style = ButtonStyle.DESTRUCTIVE)
        }
    }
}
