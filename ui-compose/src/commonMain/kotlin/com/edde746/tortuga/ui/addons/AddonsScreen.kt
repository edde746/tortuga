package com.edde746.tortuga.ui.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.addons.AddonBrowseBody
import com.edde746.tortuga.presentation.addons.AddonBrowseItem
import com.edde746.tortuga.presentation.addons.AddonBrowseUiState
import com.edde746.tortuga.presentation.addons.AddonDetailsUiState
import com.edde746.tortuga.presentation.addons.AddonInstallAction
import com.edde746.tortuga.presentation.addons.AddonInstallStep
import com.edde746.tortuga.presentation.addons.AddonInstallUiState
import com.edde746.tortuga.presentation.addons.AddonPreviewUiState
import com.edde746.tortuga.presentation.addons.AddonRemoveDialog
import com.edde746.tortuga.presentation.addons.AddonRow
import com.edde746.tortuga.presentation.addons.AddonsBody
import com.edde746.tortuga.presentation.addons.AddonsSection
import com.edde746.tortuga.presentation.addons.AddonsStore
import com.edde746.tortuga.presentation.addons.AddonsUiState
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.Badge
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.ErrorState
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.MenuChip
import com.edde746.tortuga.ui.components.MenuChoice
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OrderedRow
import com.edde746.tortuga.ui.components.OrderedRowActions
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.RowSwitch
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.TortugaToggleSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.fadeStops
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusGrowth
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.focus.remoteScroll
import com.edde746.tortuga.ui.focus.scrollPastControls
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.settings.DragHandleIcon
import com.edde746.tortuga.ui.settings.InlineError
import com.edde746.tortuga.ui.settings.NoteLine
import com.edde746.tortuga.ui.settings.NoticeBanner
import com.edde746.tortuga.ui.settings.SettingToggleItem
import com.edde746.tortuga.ui.settings.SettingsIcons
import com.edde746.tortuga.ui.settings.rememberReorderState
import com.edde746.tortuga.ui.settings.reorderHandle
import com.edde746.tortuga.ui.settings.reorderItem
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * Settings' Addons section over [store], the Settings session's (`SettingsSession.addons`): kept with Settings, a tab,
 * so an open install or details sheet survives a trip to the browser (Configure). Its heading's actions are
 * [AddonsActions].
 */
@Composable
fun AddonsSettingsRoute(store: AddonsStore) {
    val state by store.state.collectAsState()
    // "Link copied." under the addon's link, until its details close.
    var copiedFor by remember { mutableStateOf<String?>(null) }
    val intents = remember(store) {
        AddonsIntents(
            moveUp = store::moveUp,
            moveDown = store::moveDown,
            moveTo = store::moveTo,
            configure = store::configure,
            setEnabled = store::setEnabled,
            setWatchActivity = store::setWatchActivity,
            remove = store::remove,
            confirmRemove = store::confirmRemove,
            dismissRemove = store::dismissRemove,
            dismissNotice = store::dismissNotice,
            openDetails = store::openDetails,
            closeDetails = {
                copiedFor = null
                store.closeDetails()
            },
            revealLink = store::revealLink,
            hideLink = store::hideLink,
            copyLink = {
                copiedFor = store.state.value.details?.id
                store.copyLink()
            },
            updateInstallLink = store::updateInstallLink,
            submitInstallLink = store::submitInstallLink,
            confirmInstall = store::confirmInstall,
            setInstallConsent = store::setInstallConsent,
            closeInstall = store::closeInstall,
            configurePreview = store::configurePreview,
            showSection = store::showSection,
            selectBrowseCatalog = store::selectBrowseCatalog,
            selectBrowseType = store::selectBrowseType,
            retryBrowse = store::retryBrowse,
            previewBrowseItem = store::previewBrowseItem,
        )
    }
    AddonsPane(state = state, intents = intents, linkCopied = copiedFor != null && copiedFor == state.details?.id)
}

/**
 * The Addons section's actions in its heading row, where a section's heading puts them: the type filter of the
 * installed addons (a dropdown chip, [AddonsUiState.typeChip], when they serve more than one type) and "+", which opens
 * the install sheet. Harness names: `addons.filter` with `addons.filter.option.<i>`, `addons.install`.
 */
@Composable
fun AddonsActions(store: AddonsStore) {
    val state by store.state.collectAsState()
    state.typeChip?.let { chip ->
        val filters = state.typeFilters
        MenuChip(
            chip,
            choices = filters.map { MenuChoice(it.label, it.selected) },
            onPick = { store.filterType(filters[it].id) },
            probe = "addons.filter",
        )
    }
    TortugaIconButton(TortugaIcons.Add, state.installLabel, store::startInstall, Modifier.layoutProbe("addons.install"))
}

/** What the Addons section asks of [AddonsStore]; the route builds it from the store's methods. */
class AddonsIntents(
    val moveUp: (id: String) -> Unit,
    val moveDown: (id: String) -> Unit,
    val moveTo: (id: String, index: Int) -> Unit,
    val configure: (id: String) -> Unit,
    val setEnabled: (id: String, enabled: Boolean) -> Unit,
    val setWatchActivity: (id: String, allowed: Boolean) -> Unit,
    val remove: (id: String) -> Unit,
    val confirmRemove: () -> Unit,
    val dismissRemove: () -> Unit,
    val dismissNotice: () -> Unit,
    val openDetails: (id: String) -> Unit,
    val closeDetails: () -> Unit,
    val revealLink: () -> Unit,
    val hideLink: () -> Unit,
    val copyLink: () -> Unit,
    val updateInstallLink: (text: String) -> Unit,
    val submitInstallLink: () -> Unit,
    val confirmInstall: () -> Unit,
    val setInstallConsent: (agreed: Boolean) -> Unit,
    val closeInstall: () -> Unit,
    val configurePreview: () -> Unit,
    val showSection: (section: AddonsSection) -> Unit,
    val selectBrowseCatalog: (id: String) -> Unit,
    val selectBrowseType: (id: String) -> Unit,
    val retryBrowse: () -> Unit,
    val previewBrowseItem: (id: String) -> Unit,
)

/**
 * The profile's addons in the user's order, under Settings' Addons heading (its actions, the type filter
 * and "+", are [AddonsActions]): each with its logo, version, types and what it provides, and its switch (the built-in
 * one has none): an addon turned off stays in its place with its menu, dimmed. Reordered by the drag handle
 * (mouse, touch), the row's menu (Move up, Move down: ⋮, a right click or the Menu key), on TV by the ↑ and ↓ buttons
 * beside each row, and by screen readers through the row's actions; removed from its menu or with Delete, always after
 * a confirmation (the built-in one can't be removed); configured in the browser from its menu or its details.
 * Installing ("+") takes an addon link (`https://…/manifest.json` or `stremio://…`), shows the fetched manifest and
 * installs on confirmation, in a sheet; an addon with settings can be configured from there first, and a new addon
 * that asks for the user's watch activity installs only once that agreement's tick box is ticked. An addon's details
 * (a sheet as well, over the addon's backdrop where it has one) hold its switch, whether it receives the user's watch
 * activity (an addon that asks for it), and its link, shown only on request.
 *
 * Where the build has Browse ([AddonsUiState.browse]), chips switch between the installed addons and Browse: the
 * addons an installed addon's catalog lists, by catalog and type, each opening the same install preview.
 *
 * Harness names: `addons.column` (the section's column), `addons.section.<0|1>` (Installed, Browse), `addons.row.<i>`
 * (opens details) with `.switch`, `.handle` (drag), `.more` (⋮), TV's `.up` and `.down`, and its menu
 * `.menu.<up|down|configure|remove>`; `addons.browse.catalog.<i>`, `addons.browse.type.<i>`, `addons.browse.item.<i>`
 * (opens the preview); `addons.install.<link|submit|summary|consent|confirm|configure|cancel|done>`,
 * `addons.details.<facts|note|enabled|watchActivity|reveal|hide|copy|configure|remove|close>` (`facts`: on TV, the
 * built-in addon's while they are a stop),
 * `addons.remove.<confirm|cancel>`.
 */
@Composable
fun AddonsPane(
    state: AddonsUiState,
    intents: AddonsIntents,
    linkCopied: Boolean,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTortugaDimens.current
    val focus = rememberFocusMemory()
    val overlayOpen = state.install.step != AddonInstallStep.Closed || state.details != null || state.removeDialog != null
    // Browse while it shows; null on the installed addons.
    val browse = state.browse?.takeIf { state.section == AddonsSection.BROWSE }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.contentColumn().fillMaxSize().layoutProbe("addons.column", reportsFocus = false).padding(horizontal = dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.browse?.let { all ->
                val sections = listOf(AddonsSection.INSTALLED to all.installedLabel, AddonsSection.BROWSE to all.label)
                ChoiceChips(sections.map { (section, label) -> label to (section == state.section) }, "addons.section", focus, contentLabels = false) {
                    intents.showSection(sections[it].first)
                }
            }
            if (browse != null) {
                BrowseList(browse, intents, focus, Modifier.weight(1f))
            } else {
                state.notice?.let { NoticeBanner(it, T.addons.close, intents.dismissNotice) }
                when (val body = state.body) {
                    AddonsBody.Loading -> LoadingState()
                    is AddonsBody.Empty -> EmptyState(body.text, icon = TortugaIcons.Extension)
                    is AddonsBody.Rows -> AddonList(body.addons, intents, Modifier.weight(1f)) { row, index ->
                        Modifier.rememberFocus(focus, row.id).layoutProbe("addons.row.$index")
                    }
                }
            }
        }
        state.removeDialog?.let { RemoveOverlay(it, intents) }
        state.details?.let { DetailsSheet(it, linkCopied, intents) }
        if (state.install.step != AddonInstallStep.Closed) InstallSheet(state.install, intents)
    }
    RestoreFocus(
        focus,
        ready = !overlayOpen && if (browse != null) browse.body != AddonBrowseBody.Loading else state.body is AddonsBody.Rows,
        fallback = if (browse != null) {
            when (val body = browse.body) {
                AddonBrowseBody.Loading, is AddonBrowseBody.Failed -> null
                is AddonBrowseBody.Results -> body.items.firstOrNull()?.let(::browseKey)
            } ?: "addons.section.1"
        } else {
            when (val body = state.body) {
                AddonsBody.Loading, is AddonsBody.Empty -> null
                is AddonsBody.Rows -> body.addons.first().id
            }
        },
    )
}

/**
 * Chips alone in their row (scrolling sideways when they don't fit), `<probe>.<i>` each and remembered in [focus]
 * under that name: each choice's label and whether it is selected. Nothing for fewer than two. [contentLabels]: whether
 * the labels are content text (an addon's catalog names), which takes its direction from its first letter
 * ([contentDirection]).
 */
@Composable
private fun ChoiceChips(choices: List<Pair<String, Boolean>>, probe: String, focus: FocusMemory, contentLabels: Boolean, onSelect: (Int) -> Unit) {
    if (choices.size <= 1) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEachIndexed { index, (label, selected) ->
            TortugaChip(label, selected, { onSelect(index) }, Modifier.rememberFocus(focus, "$probe.$index").layoutProbe("$probe.$index"), contentLabel = contentLabels)
        }
    }
}

/** Where [focus] remembers Browse's [item]. */
private fun browseKey(item: AddonBrowseItem) = "browse.${item.id}"

/**
 * Browse: the catalogs and types to choose from, then the selected catalog's addons as one connected group (each
 * opening its install preview), or its loading or error state.
 */
@Composable
private fun BrowseList(browse: AddonBrowseUiState, intents: AddonsIntents, focus: FocusMemory, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(browse.catalogs.map { it.label to it.selected }, "addons.browse.catalog", focus, contentLabels = true) {
            intents.selectBrowseCatalog(browse.catalogs[it].id)
        }
        ChoiceChips(browse.types.map { it.label to it.selected }, "addons.browse.type", focus, contentLabels = false) { intents.selectBrowseType(browse.types[it].id) }
        when (val body = browse.body) {
            AddonBrowseBody.Loading -> LoadingState(Modifier.weight(1f))
            is AddonBrowseBody.Failed -> ErrorState(body.text, Modifier.weight(1f), onRetry = intents.retryBrowse, retryLabel = browse.retryLabel)
            is AddonBrowseBody.Results -> {
                // Another catalog or type starts at the top.
                val list = remember(browse.catalogs, browse.types) { LazyListState() }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    // The list clips above the first row: room there for a focused row's ring.
                    contentPadding = PaddingValues(top = dimens.focusReach, bottom = dimens.sectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    itemsIndexed(body.items, key = { _, item -> item.id }) { index, item ->
                        BrowseRow(
                            item,
                            groupShape(index, body.items.size),
                            { intents.previewBrowseItem(item.id) },
                            Modifier.rememberFocus(focus, browseKey(item)).layoutProbe("addons.browse.item.$index"),
                        )
                    }
                }
            }
        }
    }
}

/** An addon Browse lists, laid out as an installed one: a click previews its install. Its focus is the fill alone. */
@Composable
private fun BrowseRow(item: AddonBrowseItem, shape: Shape, onClick: () -> Unit, modifier: Modifier) {
    TortugaSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, grows = false) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AddonLogo(item.logo, 40.dp)
            AddonSummary(item.name, item.note, item.types, item.description, Modifier.weight(1f))
        }
    }
}

/** The addons as one connected group, in the user's order. */
@Composable
private fun AddonList(
    addons: List<AddonRow>,
    intents: AddonsIntents,
    modifier: Modifier,
    rowModifier: (AddonRow, Int) -> Modifier,
) {
    val reorder = rememberReorderState(GroupGap)
    // Dragging needs a pointer; TV remotes move an addon by its ↑ and ↓ buttons.
    val draggable = LocalWindowLayout.current.formFactor != FormFactor.TV && addons.size > 1
    val dimens = LocalTortugaDimens.current
    Column(
        // The scroll clips above the first row: room there for a focused control's ring.
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = dimens.sectionSpacing, top = dimens.focusReach),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        addons.forEachIndexed { index, row ->
            // Keyed by addon, so focus stays on a moved addon and it can be moved again at once.
            key(row.id) {
                AddonRowItem(
                    row = row,
                    index = index,
                    shape = groupShape(index, addons.size),
                    intents = intents,
                    dragHandle = if (draggable) {
                        {
                            DragHandleIcon(row.dragLabel, Modifier.layoutProbe("addons.row.$index.handle").reorderHandle(reorder, row.id, index, addons.size, intents.moveTo))
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.reorderItem(reorder, row.id, index),
                    surfaceModifier = rowModifier(row, index),
                )
            }
        }
    }
}

/**
 * An addon's row ([OrderedRow]): a click opens its details; its switch ([AddonSwitch]) turns it on and off, except the
 * built-in one's, which has none (its "Built in" note says why). Off, its logo and text are dimmed (as Detail's
 * episodes not out yet are) and it keeps its place and its menu. It moves up and down (its menu, or TV's ↑ and ↓), and
 * its menu holds Configure and Remove as the addon allows (the built-in one can't be removed); Remove asks for
 * confirmation first.
 *
 * A row narrower than [StackedBelow] (phones) gives its text the whole width: the logo leads the name's line, the facts
 * and the description run under them, and the drag handle, the switch and ⋮ are a line of their own at the bottom.
 * Wider (tablets, desktop, TV) everything sits on one line: handle, logo, text, switch, ⋮.
 */
@Composable
private fun AddonRowItem(
    row: AddonRow,
    index: Int,
    shape: Shape,
    intents: AddonsIntents,
    dragHandle: (@Composable () -> Unit)?,
    modifier: Modifier,
    surfaceModifier: Modifier,
) {
    val actions = OrderedRowActions(
        moveUp = ItemMenuEntry(T.addons.moveUp, "up", icon = TortugaIcons.ArrowUpward) { intents.moveUp(row.id) },
        moveDown = ItemMenuEntry(T.addons.moveDown, "down", icon = TortugaIcons.ArrowDownward) { intents.moveDown(row.id) },
        moveUpName = row.moveUpLabel,
        moveDownName = row.moveDownLabel,
        canMoveUp = row.canMoveUp,
        canMoveDown = row.canMoveDown,
        remove = if (row.removable) {
            ItemMenuEntry(T.addons.details.remove, "remove", destructive = true, icon = TortugaIcons.Delete) { intents.remove(row.id) }
        } else {
            null
        },
        more = if (row.configurable) {
            listOf(ItemMenuEntry(T.addons.details.configure, "configure", icon = TortugaIcons.Tune) { intents.configure(row.id) })
        } else {
            emptyList()
        },
    )
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    BoxWithConstraints(modifier) {
        val stacked = !tv && maxWidth < StackedBelow
        OrderedRow(
            actions = actions,
            title = row.name,
            moreLabel = row.moreLabel,
            probe = "addons.row.$index",
            index = index,
            shape = shape,
            onClick = { intents.openDetails(row.id) },
            surfaceModifier = surfaceModifier,
            contentTitle = true,
            control = when {
                row.canDisable -> {
                    { AddonSwitch(row, "addons.row.$index.switch") { intents.setEnabled(row.id, it) } }
                }
                // Beside the row on TV: the built-in addon's row keeps the switch's room, so the group's rows stay aligned.
                tv -> {
                    { Spacer(Modifier.size(SwitchSize)) }
                }
                else -> null
            },
            controlsBelow = if (stacked) ({ dragHandle?.invoke() }) else null,
        ) {
            val dim = Modifier.alpha(if (row.enabled) 1f else 0.6f)
            if (stacked) {
                AddonSummary(row.name, row.note, row.facts, row.description, Modifier.weight(1f).then(dim), logo = { AddonLogo(row.logo, 40.dp) })
            } else {
                dragHandle?.invoke()
                AddonLogo(row.logo, 40.dp, dim)
                AddonSummary(row.name, row.note, row.facts, row.description, Modifier.weight(1f).then(dim))
            }
        }
    }
}

/**
 * The narrowest addon row that keeps its controls beside its text: there the handle, logo, switch, ⋮ and their gaps take
 * 252 dp and leave the text 228 dp. A phone's row (288 dp on a 320 dp wide screen, 379 dp on a 411 dp one) would leave
 * it 36 to 127 dp, a few characters a line.
 */
private val StackedBelow = 480.dp

/** An addon row's switch: Material's 52 × 32 dp switch with room around it for a finger and its focus fill. */
private val SwitchSize = DpSize(68.dp, 48.dp)

/** On TV, the room between a block of text that is a stop of its own ([remoteScroll]) and its lines: its ring's inset. */
private val StopInset = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * An addon's switch ([AddonRow.enabled]), named [AddonRow.enabledLabel] for screen readers: its own focus target, clear
 * at rest in the colour of what is around it (inside the row on phones, tablets and desktop, black on a focused row;
 * beside the row on TV, as Streams' Download is), the primary fill while focused, growing on TV.
 */
@Composable
private fun AddonSwitch(row: AddonRow, probe: String, onChange: (Boolean) -> Unit) {
    TortugaToggleSurface(
        checked = row.enabled,
        onCheckedChange = onChange,
        modifier = Modifier
            .size(SwitchSize)
            .focusGrowth(CircleShape)
            .semantics { contentDescription = row.enabledLabel }
            .layoutProbe(probe),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = LocalContentColor.current,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RowSwitch(row.enabled) }
    }
}

/**
 * An addon's name (up to two lines) with its [note] beside it or, where it doesn't fit there, under it, wrapping; its
 * [facts] on one line that wraps between words (the row's version, types and what it provides; Browse's types); its
 * description (up to two lines). With [logo] (a narrow row) the logo leads the name's line, and the facts and the
 * description take the whole width under them. The name and the description are the addon's own: they take their
 * direction from their first letter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonSummary(
    name: String,
    note: String?,
    facts: String,
    description: String?,
    modifier: Modifier,
    logo: (@Composable () -> Unit)? = null,
) {
    val muted = LocalContentColor.current.copy(alpha = 0.6f)
    val title = @Composable { placement: Modifier ->
        FlowRow(placement, horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium.contentDirection(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            note?.let { Badge(it, maxLines = Int.MAX_VALUE) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (logo != null) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                logo()
                title(Modifier.weight(1f))
            }
        } else {
            title(Modifier)
        }
        Text(facts, style = MaterialTheme.typography.labelMedium, color = muted)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.contentDirection(), color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An addon's logo, or the addon icon while there is none or it loads. */
@Composable
private fun AddonLogo(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    RemoteImage(
        url = url,
        contentDescription = null,
        modifier = modifier.size(size).clip(shape).background(TortugaColors.SurfaceHighest, shape),
        contentScale = ContentScale.Fit,
        fallback = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(TortugaIcons.Extension, contentDescription = null, tint = TortugaColors.TextMuted)
            }
        },
    )
}

/** "Remove Example Streams?": Cancel has focus first. */
@Composable
private fun RemoveOverlay(dialog: AddonRemoveDialog, intents: AddonsIntents) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = intents.dismissRemove, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(dialog.cancelLabel, intents.dismissRemove, Modifier.focusRequester(cancelFocus).layoutProbe("addons.remove.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(dialog.confirmLabel, intents.confirmRemove, Modifier.layoutProbe("addons.remove.confirm"), style = ButtonStyle.DESTRUCTIVE)
        }
    }
}

/**
 * An addon's details in a [TortugaSheet], and its link behind "Show link" (a configured addon's link carries its
 * accounts' keys). The built-in addon's note is a line of its own under the heading and description, never beside the
 * name, which keeps the heading's width. Its switches come before its facts, in view as the details open on TV. The
 * details scroll; the link's card and the actions stay whole under them. On TV the remote reaches every line of them:
 * a move past their first or last switch scrolls them first ([scrollPastControls]); the built-in addon's, which have no
 * switch, are a stop of their own while they scroll ([remoteScroll]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsSheet(details: AddonDetailsUiState, linkCopied: Boolean, intents: AddonsIntents) {
    val closeFocus = remember { FocusRequester() }
    val facts = rememberScrollState()
    val scope = rememberCoroutineScope()
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val switches = details.canDisable || details.watchActivity != null
    val reach = when {
        tv && switches -> Modifier.scrollPastControls(facts)
        tv && facts.viewportSize > 0 && facts.maxValue > 0 -> Modifier.layoutProbe("addons.details.facts").remoteScroll(facts, scope, MaterialTheme.shapes.medium)
        else -> Modifier
    }
    TortugaSheet(onDismiss = intents.closeDetails, initialFocus = closeFocus, probe = "addons.details", label = details.name) {
        Column(
            Modifier.weight(1f, fill = false).then(reach).verticalScroll(facts).then(if (tv && !switches) Modifier.padding(StopInset) else Modifier),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailsHeader(details)
            details.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium.contentDirection()) }
            details.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted, modifier = Modifier.layoutProbe("addons.details.note")) }
            DetailsSwitches(details, intents)
            Field(details.typesLabel, listOf(details.types))
            Field(details.resourcesLabel, listOf(details.resources))
            if (details.catalogs.isNotEmpty()) Field(details.catalogsLabel, details.catalogs)
            details.contact?.let { Field(details.contactLabel, listOf(it), content = true) }
        }
        ShareLink(details, linkCopied, intents)
        OverlayActions(primary = closeFocus) {
            if (details.configurable) {
                TortugaButton(
                    details.configureLabel, { intents.configure(details.id) }, Modifier.layoutProbe("addons.details.configure"),
                    icon = TortugaIcons.Tune,
                )
            }
            if (details.removable) {
                TortugaButton(
                    details.removeLabel,
                    {
                        intents.closeDetails()
                        intents.remove(details.id)
                    },
                    Modifier.layoutProbe("addons.details.remove"),
                    style = ButtonStyle.DESTRUCTIVE,
                    icon = TortugaIcons.Delete,
                )
            }
            TortugaButton(T.addons.close, intents.closeDetails, Modifier.focusRequester(closeFocus).layoutProbe("addons.details.close"), style = ButtonStyle.PRIMARY)
        }
    }
}

/** A labelled detail, one line per value; [content] values are the addon's own (its contact), in their own direction. */
@Composable
private fun Field(label: String, values: List<String>, content: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = TortugaColors.TextMuted)
        val style = MaterialTheme.typography.bodyMedium
        values.forEach { Text(it, style = if (content) style.contentDirection() else style) }
    }
}

/**
 * The addon's switches, settings rows in one group: Use this addon, with what being off means while it is, unless it
 * is built in; and, for an addon that asks for it, whether it receives the user's watch activity, with what that is.
 */
@Composable
private fun DetailsSwitches(details: AddonDetailsUiState, intents: AddonsIntents) {
    val enabled = SettingToggleRow("enabled", details.enabledLabel, details.enabledNote, details.enabled).takeIf { details.canDisable }
    val activity = details.watchActivity?.let { SettingToggleRow("watchActivity", it.label, it.explanation, it.checked) }
    val count = listOfNotNull(enabled, activity).size
    if (count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        enabled?.let { row ->
            SettingToggleItem(row, { intents.setEnabled(details.id, it) }, groupShape(0, count), Modifier.layoutProbe("addons.details.enabled"))
        }
        activity?.let { row ->
            SettingToggleItem(row, { intents.setWatchActivity(details.id, it) }, groupShape(count - 1, count), Modifier.layoutProbe("addons.details.watchActivity"))
        }
    }
}

/**
 * The add-on's share card: its title and warning, the link when there is one, Reveal or Hide, Copy, and "Link copied"
 * after Copy, which TalkBack speaks as it shows, wherever its focus is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareLink(details: AddonDetailsUiState, linkCopied: Boolean, intents: AddonsIntents) {
    val share = details.share
    Column(
        Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(SettingsIcons.Link, contentDescription = null, tint = TortugaColors.TextMuted)
            Text(share.title, style = MaterialTheme.typography.titleMedium)
        }
        Text(share.warning, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted)
        share.link?.let { link ->
            // Five lines at most; a longer link (a configuration in it) scrolls, so the card stays whole on short windows.
            SelectionContainer(Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState())) {
                Text(
                    link,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace).contentDirection(),
                    modifier = Modifier.layoutProbe("addons.details.link"),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (share.revealed) {
                TortugaButton(share.hideLabel, intents.hideLink, Modifier.layoutProbe("addons.details.hide"), icon = TortugaIcons.VisibilityOff)
            } else {
                TortugaButton(share.revealLabel, intents.revealLink, Modifier.layoutProbe("addons.details.reveal"), icon = TortugaIcons.Visibility)
            }
            TortugaButton(share.copyLabel, intents.copyLink, Modifier.layoutProbe("addons.details.copy"), icon = TortugaIcons.ContentCopy)
            if (linkCopied) {
                Text(T.addons.details.linkCopied, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = TortugaColors.TextMuted)
            }
        }
    }
}

/**
 * Installing from a link, in a [TortugaSheet]: the link, then the fetched manifest to confirm, then the outcome. On TV
 * the keyboard waits for the link's field to be pressed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallSheet(install: AddonInstallUiState, intents: AddonsIntents) {
    val primary = remember { FocusRequester() }
    TortugaSheet(onDismiss = intents.closeInstall, title = install.title, initialFocus = primary, probe = "addons.install.sheet") {
        when (val step = install.step) {
            is AddonInstallStep.EnterLink -> {
                val submit = { if (step.canSubmit) intents.submitInstallLink() }
                // The field keeps its own text (the store's state arrives a frame later, and typing must not wait for it).
                // A link that fails comes back here with the error and the store's link (the one typed, or none for a
                // link followed into the app meanwhile): the field starts again from it, a change not sent back as if
                // typed, which clears the error. The loading step between may never have been drawn (a refused
                // connection fails at once), so the error's arrival is the sign.
                val link = remember { TextFieldState(step.link) }
                val keyboard = rememberKeyboardOnSelect()
                var given by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(step.error) {
                    if (step.error != null && link.text.toString() != step.link) {
                        given = step.link
                        link.setTextAndPlaceCursorAtEnd(step.link)
                    }
                }
                OutlinedTextField(
                    state = link,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    readOnly = keyboard.readOnly,
                    label = { Text(install.linkHint) },
                    isError = step.error != null,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    onKeyboardAction = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(primary)
                        .then(keyboard.modifier)
                        .onPreviewKeyEvent { event ->
                            val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (enter && event.type == KeyEventType.KeyDown) submit()
                            enter
                        }
                        .remoteLeavesField(link)
                        .inViewAboveKeyboard()
                        .layoutProbe("addons.install.link"),
                )
                OnTextChange(link) { text ->
                    if (text != given) intents.updateInstallLink(text)
                    given = null
                }
                step.error?.let { InlineError(it) }
                val submitFocus = remember { FocusRequester() }
                OverlayActions(primary = submitFocus) {
                    TortugaButton(T.addons.cancel, intents.closeInstall, Modifier.layoutProbe("addons.install.cancel"), style = ButtonStyle.TEXT)
                    TortugaButton(install.submitLabel, intents.submitInstallLink, Modifier.focusRequester(submitFocus).layoutProbe("addons.install.submit"), style = ButtonStyle.PRIMARY, enabled = step.canSubmit)
                }
            }
            AddonInstallStep.Loading -> InstallProgress()
            is AddonInstallStep.Installing -> {
                PreviewSummary(step.preview)
                InstallProgress()
            }
            is AddonInstallStep.Preview -> {
                val preview = step.preview
                // A new install waiting for the tick: the tick box has focus first, as the button can't take it yet.
                val tickFirst = preview.watchActivity != null && !preview.canConfirm
                val summary = rememberScrollState()
                val scope = rememberCoroutineScope()
                // On TV the summary, while taller than its room, is a stop of its own (Up from the tick box or the
                // buttons) whose Down scrolls it: its last line, the catalogs, is never out of the remote's reach.
                val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
                val stop = tv && summary.viewportSize > 0 && summary.maxValue > 0
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .layoutProbe("addons.install.summary")
                        .then(if (stop) Modifier.remoteScroll(summary, scope, MaterialTheme.shapes.medium) else Modifier)
                        .verticalScroll(summary)
                        .then(if (tv) Modifier.padding(StopInset) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PreviewSummary(preview)
                    preview.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    preview.warning?.let { NoteLine(it, SettingsIcons.Warning) }
                    step.error?.let { InlineError(it) }
                }
                // Under the scrolling summary, beside the button it unlocks: focus on it at the start scrolls nothing
                // away (on TV the summary's top, the addon's name, stays in view).
                preview.watchActivity?.let { consent ->
                    SettingToggleItem(
                        SettingToggleRow("consent", consent.label, consent.explanation, consent.checked),
                        intents.setInstallConsent,
                        groupShape(0, 1),
                        (if (tickFirst) Modifier.focusRequester(primary) else Modifier).layoutProbe("addons.install.consent"),
                        tickBox = true,
                    )
                }
                OverlayActions {
                    TortugaButton(preview.cancelLabel, intents.closeInstall, Modifier.layoutProbe("addons.install.cancel"), style = ButtonStyle.TEXT)
                    preview.configureLabel?.let { label ->
                        TortugaButton(label, intents.configurePreview, Modifier.layoutProbe("addons.install.configure"), icon = TortugaIcons.Tune)
                    }
                    when (val action = preview.action) {
                        AddonInstallAction.None -> Unit
                        is AddonInstallAction.Confirm -> TortugaButton(
                            action.label,
                            intents.confirmInstall,
                            (if (tickFirst) Modifier else Modifier.focusRequester(primary)).layoutProbe("addons.install.confirm"),
                            style = ButtonStyle.PRIMARY,
                            enabled = preview.canConfirm,
                        )
                    }
                }
            }
            is AddonInstallStep.Installed -> {
                Text(step.result, style = MaterialTheme.typography.bodyLarge)
                OverlayActions {
                    TortugaButton(T.addons.install.done, intents.closeInstall, Modifier.focusRequester(primary).layoutProbe("addons.install.done"), style = ButtonStyle.PRIMARY)
                }
            }
            AddonInstallStep.Closed -> Unit
        }
    }
    // Each step's main control takes focus as the step appears (not as its content changes: a typed letter, a tick).
    LaunchedEffect(install.step::class) {
        withFrameNanos { }
        primary.requestFocus()
    }
}

/** The spinner of a step that waits: the manifest's fetch, an install. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallProgress() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(48.dp)) }
}

/** The fetched manifest: logo, name, version, description, types, what it provides and its catalogs. */
@Composable
private fun PreviewSummary(preview: AddonPreviewUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AddonLogo(preview.logo, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(preview.name, style = MaterialTheme.typography.titleLarge.contentDirection())
                Text(preview.version, style = MaterialTheme.typography.titleSmall, color = TortugaColors.TextMuted)
            }
        }
        preview.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium.contentDirection()) }
        Field(T.addons.details.types, listOf(preview.types))
        Field(T.addons.details.resources, listOf(preview.resources))
        if (preview.catalogs.isNotEmpty()) Field(T.addons.details.catalogs, preview.catalogs)
    }
}

/**
 * The details' heading: logo, name and version, over the addon's backdrop where it has one (its picture across the top,
 * fading into the sheet under the lines, as Detail's does into the page).
 */
@Composable
private fun DetailsHeader(details: AddonDetailsUiState) {
    val backdrop = details.background
    Box(Modifier.fillMaxWidth()) {
        if (backdrop != null) {
            val sheet = TortugaColors.Surface
            val shape = MaterialTheme.shapes.large
            RemoteImage(backdrop, contentDescription = null, modifier = Modifier.matchParentSize().clip(shape), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(*fadeStops(sheet, 1f, 0f, from = 1f, to = 0.3f))))
        }
        Row(
            Modifier.padding(top = if (backdrop != null) 88.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AddonLogo(details.logo, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(details.name, style = MaterialTheme.typography.headlineSmall.contentDirection())
                Text(details.version, style = MaterialTheme.typography.titleSmall, color = TortugaColors.TextMuted)
            }
        }
    }
}
