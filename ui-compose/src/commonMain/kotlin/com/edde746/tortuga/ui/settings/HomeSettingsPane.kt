package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.CollectionDeleteDialog
import com.edde746.tortuga.presentation.settings.HomeRowSetting
import com.edde746.tortuga.presentation.settings.HomeSettingsBody
import com.edde746.tortuga.presentation.settings.HomeSettingsPage
import com.edde746.tortuga.presentation.settings.HomeSettingsStore
import com.edde746.tortuga.presentation.settings.HomeSettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.app.SaveResult
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OrderedRow
import com.edde746.tortuga.ui.components.OrderedRowActions
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.RowSwitch
import com.edde746.tortuga.ui.components.TextAndTrailing
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe

/**
 * The home section over [store], with what its collections open over it: the delete confirmation
 * ([HomeSettingsUiState.deleteDialog]), and the import or the editor sheet while [HomeSettingsUiState.page] says so.
 */
@Composable
internal fun HomeSettingsRoute(store: HomeSettingsStore) {
    val state by store.state.collectAsState()
    HomeSettingsPane(
        state = state,
        onToggle = store::toggle,
        onShown = store::setShown,
        onMove = { id, up -> if (up) store.moveUp(id) else store.moveDown(id) },
        onMoveTo = store::moveTo,
        onReset = store::reset,
        onEdit = store::editCollection,
        onDelete = store::askDelete,
        onDismissNotice = store::dismissNotice,
    )
    state.deleteDialog?.let { DeleteCollectionOverlay(it, store::confirmDelete, store::dismissDelete) }
    when (state.page) {
        HomeSettingsPage.IMPORT -> store.collectionImport?.let { CollectionImportSheet(it, store::closePage) }
        HomeSettingsPage.EDITOR -> store.collectionEditor?.let { CollectionEditorSheet(it, store::closePage) }
        null -> {}
    }
}

/**
 * The Home section's actions in its heading row: New collection, Import collections and Export collections, round
 * icon buttons named by their labels. Export saves the file through the system's save dialog where the platform has
 * one, else hands it to the share sheet; with neither (TV) it isn't offered, and it is off while the profile has no
 * collection. Harness names: `settings.home.new`, `settings.home.import`, `settings.home.export`.
 */
@Composable
internal fun HomeActions(store: HomeSettingsStore) {
    val state by store.state.collectAsState()
    val platform = LocalTortugaPlatform.current
    val actions = state.collections
    TortugaIconButton(TortugaIcons.Add, actions.newLabel, store::newCollection, Modifier.layoutProbe("settings.home.new"))
    TortugaIconButton(TortugaIcons.Download, actions.importLabel, store::openImport, Modifier.layoutProbe("settings.home.import"))
    if (platform.hasFileDialogs || platform.canShare) {
        val export = {
            store.export()?.let { file ->
                if (platform.hasFileDialogs) {
                    platform.saveFile(file.fileName, file.text) { if (it == SaveResult.FAILED) store.exportFailed() }
                } else {
                    platform.share(file.text)
                }
            }
            Unit
        }
        TortugaIconButton(SettingsIcons.Upload, actions.exportLabel, export, Modifier.layoutProbe("settings.home.export"), enabled = actions.canExport)
    }
}

/** A collection's Delete, confirmed: Cancel has focus first. */
@Composable
private fun DeleteCollectionOverlay(dialog: CollectionDeleteDialog, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = onDismiss, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(dialog.cancelLabel, onDismiss, Modifier.focusRequester(cancelFocus).layoutProbe("settings.home.delete.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(dialog.confirmLabel, onConfirm, Modifier.layoutProbe("settings.home.delete.confirm"), style = ButtonStyle.DESTRUCTIVE)
        }
    }
}

/**
 * Home's rows (per profile, synced, as the note says): Continue Watching first, then every row Home can show in Home's
 * order, each an [OrderedRow] whose click shows or hides it (its switch shows which). A row moves by its drag handle
 * (mouse, touch) or its menu (⋮, a right click or the Menu key): Move up and Move down; on TV by its ↑ and ↓ buttons
 * instead. A collection's row ([HomeRowSetting.collectionId]) adds Edit ([onEdit]) and Delete ([onDelete], which asks
 * first) to its menu. Reset to default ends the list. While the store reads the addons and the layout, a spinner holds
 * the place of the rows and Reset. Continue Watching's switch gives its row's id to [onToggle]. A failed export's
 * notice ([HomeSettingsUiState.notice]) heads the page until dismissed.
 *
 * Harness names: `settings.home.continueWatching`, `settings.home.row.<i>` with `.handle`, `.more` (⋮), its menu
 * `.menu.<up|down|edit|delete>` and TV's `.up` and `.down`, `settings.home.reset`, `settings.home.notice`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeSettingsPane(
    state: HomeSettingsUiState,
    onToggle: (key: String, checked: Boolean) -> Unit,
    onShown: (id: String, shown: Boolean) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    onMoveTo: (id: String, index: Int) -> Unit,
    onReset: () -> Unit,
    onEdit: (collectionId: String) -> Unit,
    onDelete: (collectionId: String) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val reorder = rememberReorderState(GroupGap)
    SettingsList(modifier) {
        item(key = "note") { SettingsNote(state.note) }
        state.notice?.let { notice ->
            item(key = "notice") {
                NoticeBanner(notice, T.settings.close, onDismissNotice, Modifier.layoutProbe("settings.home.notice", reportsFocus = false), error = true)
            }
        }
        settingsGroup(
            "continueWatching",
            null,
            listOf(
                SettingsItem("continueWatchingRow") { shape ->
                    val row = state.continueWatching
                    SettingToggleItem(row, { onToggle(row.id, it) }, shape, Modifier.layoutProbe("settings.home.${row.id}"))
                },
            ),
        )
        when (val body = state.body) {
            HomeSettingsBody.Loading -> {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
                }
                return@SettingsList
            }
            is HomeSettingsBody.Empty -> {
                item(key = "rowsHeader") { GroupHeader(state.rowsHeader) }
                item(key = "empty") { SettingsNote(body.text) }
            }
            is HomeSettingsBody.Rows -> {
                val rows = body.rows
                item(key = "rowsHeader") { GroupHeader(state.rowsHeader) }
                // Keyed by row, so focus stays on a moved row and it can be moved again at once.
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    HomeRowItem(
                        row = row,
                        index = index,
                        count = rows.size,
                        shape = groupShape(index, rows.size),
                        reorder = reorder,
                        draggable = !tv && rows.size > 1,
                        onShown = onShown,
                        onMove = onMove,
                        onMoveTo = onMoveTo,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
        item(key = "reset") {
            Box(Modifier.padding(top = 16.dp)) {
                TortugaButton(state.resetLabel, onReset, Modifier.layoutProbe("settings.home.reset"), style = ButtonStyle.SECONDARY, enabled = state.canReset)
            }
        }
    }
}

@Composable
private fun HomeRowItem(
    row: HomeRowSetting,
    index: Int,
    count: Int,
    shape: Shape,
    reorder: ReorderState,
    draggable: Boolean,
    onShown: (String, Boolean) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onMoveTo: (String, Int) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val probe = "settings.home.row.$index"
    val text = T.settings.home
    val collection = row.collectionId
    val actions = OrderedRowActions(
        moveUp = ItemMenuEntry(text.moveUp, "up", icon = TortugaIcons.ArrowUpward) { onMove(row.id, true) },
        moveDown = ItemMenuEntry(text.moveDown, "down", icon = TortugaIcons.ArrowDownward) { onMove(row.id, false) },
        moveUpName = row.moveUpLabel,
        moveDownName = row.moveDownLabel,
        canMoveUp = row.canMoveUp,
        canMoveDown = row.canMoveDown,
        remove = if (collection != null) row.deleteLabel?.let { ItemMenuEntry(it, "delete", destructive = true, icon = TortugaIcons.Delete) { onDelete(collection) } } else null,
        more = if (collection != null) listOfNotNull(row.editLabel?.let { ItemMenuEntry(it, "edit", icon = TortugaIcons.Edit) { onEdit(collection) } }) else emptyList(),
    )
    OrderedRow(
        actions = actions,
        title = row.title,
        moreLabel = row.moreLabel,
        probe = probe,
        index = index,
        shape = shape,
        onClick = { onShown(row.id, !row.shown) },
        modifier = Modifier.reorderItem(reorder, row.id, index),
        surfaceModifier = Modifier.layoutProbe(probe).semantics { toggleableState = ToggleableState(row.shown) },
    ) {
        if (draggable) {
            DragHandleIcon(row.dragLabel, Modifier.layoutProbe("$probe.handle").reorderHandle(reorder, row.id, index, count, onMoveTo))
        }
        // Without a handle the text starts where Continue Watching's does (a toggle row's 16 dp).
        TextAndTrailing(
            text = { RowText(row.title, row.sourceName, contentSubtitle = true) },
            trailing = { RowSwitch(row.shown) },
            modifier = Modifier.weight(1f).padding(start = if (draggable) 0.dp else 4.dp),
            spacing = 12.dp,
        )
    }
}
