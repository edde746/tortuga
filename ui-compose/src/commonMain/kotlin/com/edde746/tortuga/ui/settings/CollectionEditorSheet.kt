package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.collections.CollectionEditorStore
import com.edde746.tortuga.presentation.collections.CollectionEditorUiState
import com.edde746.tortuga.presentation.collections.FolderEditorUiState
import com.edde746.tortuga.presentation.collections.ListUrlForm
import com.edde746.tortuga.presentation.collections.SourcePickerUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OrderedRow
import com.edde746.tortuga.ui.components.OrderedRowActions
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * The collection editor over Settings → Home, in a [TortugaSheet], over [store]: one page at a time, the sheet's title
 * the page's heading.
 * - The collection: its title, how it shows its folders (Tabs, Rows, Follow the app), the All tab, its tiles' shape
 *   (Poster, Landscape, Square: every folder's), its folders (a press opens one; each moves by its drag handle or its
 *   menu, on TV by ↑ and ↓, and its menu deletes it), Add folder, then why the last Save was refused, and Save.
 * - A folder ([CollectionEditorUiState.folder]): its title, its cover's link, Hide title, its sources (each with its
 *   note when it can't show now; moved as the folders are, removed from its menu) and Add source.
 * - Add source ([CollectionEditorUiState.picker]): the addons' catalogs and each connected tracker's lists, by group;
 *   a press adds one (a list by its link asks for the link first, [ListUrlForm]).
 *
 * Back (Esc, the remote's Back) leaves the page for the one before it, and from the collection closes the editor
 * ([CollectionEditorStore.back]): at once when nothing changed, else after "Discard changes?"
 * ([CollectionEditorUiState.discard]: Keep editing, which has focus first, or Discard); a click outside or a drag down
 * closes it unsaved ([onClose]). On TV the keyboard waits for a field to be pressed; every control is a stop of the
 * D-pad.
 *
 * Harness names: `collections.editor` (the sheet), `collections.editor.title`, `collections.editor.viewMode`,
 * `collections.editor.allTab`, `collections.editor.shape`, `collections.editor.folder.<i>` (with its `OrderedRow`
 * names), `collections.editor.addFolder`, `collections.editor.error`, `collections.editor.save`, the question's
 * `collections.editor.discard.keep` and `collections.editor.discard.confirm`; the folder page's
 * `collections.folder.title`, `collections.folder.cover`, `collections.folder.hideTitle`,
 * `collections.folder.source.<i>`, `collections.folder.addSource`; the picker's `collections.picker.<group>.<i>` and
 * the link's `collections.picker.url`, `collections.picker.url.add`.
 */
@Composable
internal fun CollectionEditorSheet(store: CollectionEditorStore, onClose: () -> Unit) {
    val state by store.state.collectAsState()
    val folder = state.folder
    val picker = state.picker
    val title = when {
        picker?.urlForm != null -> picker.urlForm?.title
        picker != null -> T.collections.editor.addSource
        folder != null -> folder.heading
        else -> state.heading
    }
    val first = remember { FocusRequester() }
    TortugaSheet(onDismiss = onClose, onBack = store::back, title = title, initialFocus = first, probe = "collections.editor") {
        val urlForm = picker?.urlForm
        when {
            urlForm != null -> ListUrlPage(urlForm, first, store::submitListUrl)
            picker != null -> PickerPage(picker, first, store::pick)
            folder != null -> FolderPage(folder, first, store)
            else -> CollectionPage(state, first, store)
        }
    }
    // A page's first control takes focus as the page appears.
    val page = when {
        picker?.urlForm != null -> 3
        picker != null -> 2
        folder != null -> 1
        else -> 0
    }
    LaunchedEffect(page) {
        withFrameNanos { }
        first.requestFocus()
    }
    state.discard?.let { question ->
        val keep = remember { FocusRequester() }
        ModalOverlay(title = question.title, onDismiss = store::keepEditing, initialFocus = keep) {
            OverlayActions {
                TortugaButton(question.keepLabel, store::keepEditing, Modifier.focusRequester(keep).layoutProbe("collections.editor.discard.keep"), style = ButtonStyle.TEXT)
                TortugaButton(question.discardLabel, store::discard, Modifier.layoutProbe("collections.editor.discard.confirm"), style = ButtonStyle.DESTRUCTIVE)
            }
        }
    }
}

/** The collection's page: its fields, its folders, Add folder, the refusal and Save. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.CollectionPage(state: CollectionEditorUiState, first: FocusRequester, store: CollectionEditorStore) {
    val text = T.collections.editor
    if (!state.loaded) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
        return
    }
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val reorder = rememberReorderState(GroupGap)
    PageColumn {
        EditorField(
            state.title, text.titleLabel, null, "collections.editor.title", store::setTitle, Modifier.focusRequester(first),
            keyboardOnFocus = state.title.isEmpty(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            SettingChoiceItem(state.viewMode, { store.choose(state.viewMode.id, it) }, "collections.editor.viewMode", groupShape(0, 3))
            SettingToggleItem(state.showAllTab, { store.toggle(state.showAllTab.id, it) }, groupShape(1, 3), Modifier.layoutProbe("collections.editor.allTab"))
            SettingChoiceItem(state.shape, { store.choose(state.shape.id, it) }, "collections.editor.shape", groupShape(2, 3))
        }
        Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            GroupHeader(text.foldersHeader)
            if (state.folders.isEmpty()) SettingsNote(text.noFolders)
            val count = state.folders.size + 1
            state.folders.forEachIndexed { index, row ->
                val probe = "collections.editor.folder.$index"
                EditorRow(
                    title = row.title,
                    detail = row.detail,
                    note = null,
                    probe = probe,
                    index = index,
                    count = state.folders.size,
                    key = row.id,
                    shapeIndex = index to count,
                    reorder = reorder,
                    draggable = !tv && state.folders.size > 1,
                    canMoveUp = row.canMoveUp,
                    canMoveDown = row.canMoveDown,
                    moveUpLabel = row.moveUpLabel,
                    moveDownLabel = row.moveDownLabel,
                    dragLabel = row.dragLabel,
                    moreLabel = row.moreLabel,
                    removeLabel = row.deleteLabel,
                    onClick = { store.openFolder(index) },
                    onMoveUp = { store.moveFolderUp(index) },
                    onMoveDown = { store.moveFolderDown(index) },
                    onMoveTo = { to -> store.moveFolderTo(index, to) },
                    onRemove = { store.deleteFolder(index) },
                )
            }
            GroupRow(
                text.addFolder,
                onClick = store::addFolder,
                modifier = Modifier.layoutProbe("collections.editor.addFolder"),
                shape = groupShape(count - 1, count),
                icon = TortugaIcons.Add,
            )
        }
        state.error?.let { InlineError(it, Modifier.layoutProbe("collections.editor.error", reportsFocus = false)) }
    }
    OverlayActions {
        if (state.saving) LoadingIndicator(Modifier.size(32.dp))
        TortugaButton(text.save, store::save, Modifier.layoutProbe("collections.editor.save"), style = ButtonStyle.PRIMARY, enabled = !state.saving)
    }
}

/** A folder's page: its fields, its sources and Add source. */
@Composable
private fun ColumnScope.FolderPage(folder: FolderEditorUiState, first: FocusRequester, store: CollectionEditorStore) {
    val text = T.collections.editor
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val reorder = rememberReorderState(GroupGap)
    PageColumn {
        EditorField(
            folder.title, text.folderTitleLabel, null, "collections.folder.title", store::setFolderTitle, Modifier.focusRequester(first),
            keyboardOnFocus = folder.title.isEmpty(),
        )
        EditorField(folder.coverUrl, text.coverLabel, text.coverHint, "collections.folder.cover", store::setCoverUrl, keyboardType = KeyboardType.Uri)
        SettingToggleItem(folder.hideTitle, { store.toggle(folder.hideTitle.id, it) }, groupShape(0, 1), Modifier.layoutProbe("collections.folder.hideTitle"))
        Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            GroupHeader(text.sourcesHeader)
            if (folder.sources.isEmpty()) SettingsNote(text.noSources)
            val count = folder.sources.size + 1
            folder.sources.forEachIndexed { index, row ->
                EditorRow(
                    title = row.title,
                    detail = row.detail,
                    note = row.note,
                    probe = "collections.folder.source.$index",
                    index = index,
                    count = folder.sources.size,
                    // Two sources may read alike: the row's place keys it.
                    key = "source:$index",
                    shapeIndex = index to count,
                    reorder = reorder,
                    draggable = !tv && folder.sources.size > 1,
                    canMoveUp = row.canMoveUp,
                    canMoveDown = row.canMoveDown,
                    moveUpLabel = row.moveUpLabel,
                    moveDownLabel = row.moveDownLabel,
                    dragLabel = row.dragLabel,
                    moreLabel = row.moreLabel,
                    removeLabel = row.removeLabel,
                    onClick = null,
                    onMoveUp = { store.moveSourceUp(index) },
                    onMoveDown = { store.moveSourceDown(index) },
                    onMoveTo = { to -> store.moveSourceTo(index, to) },
                    onRemove = { store.removeSource(index) },
                )
            }
            GroupRow(
                text.addSource,
                onClick = store::openPicker,
                modifier = Modifier.layoutProbe("collections.folder.addSource"),
                shape = groupShape(count - 1, count),
                icon = TortugaIcons.Add,
            )
        }
    }
}

/** Add source: each group under its heading, its choices (a check on the ones the folder has), a spinner, its notes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.PickerPage(picker: SourcePickerUiState, first: FocusRequester, onPick: (String) -> Unit) {
    val firstGroup = picker.groups.indexOfFirst { it.choices.isNotEmpty() }
    PageColumn {
        picker.groups.forEachIndexed { groupIndex, group ->
            Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                GroupHeader(group.title)
                group.choices.forEachIndexed { index, choice ->
                    val focus = if (groupIndex == firstGroup && index == 0) Modifier.focusRequester(first) else Modifier
                    GroupRow(
                        choice.title,
                        onClick = { onPick(choice.key) },
                        modifier = focus.layoutProbe("collections.picker.$groupIndex.$index"),
                        shape = groupShape(index, group.choices.size),
                        subtitle = choice.detail,
                        trailing = if (choice.added) ({ Icon(TortugaIcons.Check, contentDescription = null) }) else null,
                    )
                }
                if (group.loading) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                }
                group.notes.forEach { SettingsNote(it) }
            }
        }
    }
}

/** A list by its link: the field, why the last link was refused, and Add (a spinner while the link is looked up). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.ListUrlPage(form: ListUrlForm, first: FocusRequester, onSubmit: (String) -> Unit) {
    val link = remember { TextFieldState() }
    val submit = { if (!form.resolving && link.text.isNotBlank()) onSubmit(link.text.toString().trim()) }
    PageColumn {
        LinkInput(link, form.hint, form.error != null, submit, Modifier.focusRequester(first).layoutProbe("collections.picker.url"))
        form.error?.let { InlineError(it, Modifier.layoutProbe("collections.picker.url.error", reportsFocus = false)) }
    }
    OverlayActions {
        if (form.resolving) LoadingIndicator(Modifier.size(32.dp))
        TortugaButton(form.addLabel, { submit() }, Modifier.layoutProbe("collections.picker.url.add"), style = ButtonStyle.PRIMARY, enabled = !form.resolving)
    }
}

/** A page's content, scrolling when taller than the sheet, with room for a focused control's growth. */
@Composable
private fun ColumnScope.PageColumn(content: @Composable () -> Unit) {
    val reach = LocalTortugaDimens.current.focusReach
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = reach),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

/**
 * A row of the editor's folders or a folder's sources ([OrderedRow]): [title] with [detail] and, in the error colour's
 * place of the muted line, [note] (why it can't show now); its menu Move up, Move down and [removeLabel]; a press runs
 * [onClick] (null: the press opens the menu).
 */
@Composable
private fun EditorRow(
    title: String,
    detail: String?,
    note: String?,
    probe: String,
    index: Int,
    count: Int,
    key: String,
    shapeIndex: Pair<Int, Int>,
    reorder: ReorderState,
    draggable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveUpLabel: String,
    moveDownLabel: String,
    dragLabel: String,
    moreLabel: String,
    removeLabel: String,
    onClick: (() -> Unit)?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveTo: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val home = T.settings.home
    val actions = OrderedRowActions(
        moveUp = ItemMenuEntry(home.moveUp, "up", icon = TortugaIcons.ArrowUpward, onClick = onMoveUp),
        moveDown = ItemMenuEntry(home.moveDown, "down", icon = TortugaIcons.ArrowDownward, onClick = onMoveDown),
        moveUpName = moveUpLabel,
        moveDownName = moveDownLabel,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        remove = ItemMenuEntry(removeLabel, "remove", destructive = true, icon = TortugaIcons.Delete, onClick = onRemove),
    )
    OrderedRow(
        actions = actions,
        title = title,
        moreLabel = moreLabel,
        probe = probe,
        index = index,
        shape = groupShape(shapeIndex.first, shapeIndex.second),
        onClick = onClick,
        modifier = Modifier.reorderItem(reorder, key, index),
        surfaceModifier = Modifier.layoutProbe(probe),
        contentTitle = true,
    ) {
        if (draggable) {
            DragHandleIcon(dragLabel, Modifier.layoutProbe("$probe.handle").reorderHandle(reorder, key, index, count) { _, to -> onMoveTo(to) })
        }
        Column(Modifier.weight(1f).padding(start = if (draggable) 0.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RowText(title, detail, contentTitle = true, contentSubtitle = true)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TortugaColors.Error) }
        }
    }
}

/**
 * One single-line field: [label] over it, [hint] while it is empty. It starts from [value] and keeps its own text, each
 * change going to [onChange]. Up and Down move focus to the control above or below (a single-line field would move its
 * cursor), and Enter moves on. A page opens with focus on its first field; [keyboardOnFocus] off (a page that opens on
 * a title already given: the user came to look before typing) raises the keyboard only on a tap, so it doesn't cover
 * the page as it opens. Not on TV: there the keyboard already waits for Select ([rememberKeyboardOnSelect]), which
 * raises it by giving the focused field its input session, and a field that raises it only on a tap never would.
 */
@Composable
private fun EditorField(
    value: String,
    label: String,
    hint: String?,
    probe: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOnFocus: Boolean = true,
) {
    val state = remember { TextFieldState(value) }
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val showOnFocus = remember { keyboardOnFocus || tv }
    val keyboard = rememberKeyboardOnSelect()
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        readOnly = keyboard.readOnly,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
            showKeyboardOnFocus = showOnFocus,
        ),
        onKeyboardAction = { focusManager.moveFocus(FocusDirection.Down) },
        modifier = modifier
            .fillMaxWidth()
            .then(keyboard.modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    else -> false
                }
            }
            .remoteLeavesField(state)
            .inViewAboveKeyboard()
            .layoutProbe(probe),
    )
    OnTextChange(state, onChange)
}

/** A link's field: [hint] while it is empty; Enter and the keyboard's Go submit it ([onSubmit]). */
@Composable
internal fun LinkInput(link: TextFieldState, label: String, error: Boolean, onSubmit: () -> Unit, modifier: Modifier, hint: String? = null) {
    val keyboard = rememberKeyboardOnSelect()
    OutlinedTextField(
        state = link,
        lineLimits = TextFieldLineLimits.SingleLine,
        readOnly = keyboard.readOnly,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        isError = error,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        onKeyboardAction = { onSubmit() },
        modifier = modifier
            .fillMaxWidth()
            .then(keyboard.modifier)
            .onPreviewKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (enter && event.type == KeyEventType.KeyDown) onSubmit()
                enter
            }
            .remoteLeavesField(link)
            .inViewAboveKeyboard(),
    )
}
