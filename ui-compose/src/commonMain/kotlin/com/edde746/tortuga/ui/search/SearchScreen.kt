package com.edde746.tortuga.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.search.RecentSearch
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.presentation.search.SearchGroup
import com.edde746.tortuga.presentation.search.SearchGroupStatus
import com.edde746.tortuga.presentation.search.SearchStore
import com.edde746.tortuga.presentation.search.SearchUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.board.AddonLabel
import com.edde746.tortuga.ui.board.FailedShelf
import com.edde746.tortuga.ui.board.LoadingShelf
import com.edde746.tortuga.ui.board.MetaPoster
import com.edde746.tortuga.ui.board.rememberShelfNavigation
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.HeaderAction
import com.edde746.tortuga.ui.components.HeaderHeight
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.OfflineMessage
import com.edde746.tortuga.ui.components.SectionTitle
import com.edde746.tortuga.ui.components.ShelfRow
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.shelfSpacing
import com.edde746.tortuga.ui.focus.FOCUS_RETRY_FRAMES
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * Search for [app]'s profile, over a store the navigator keeps for the session (Search is a tab: shown again, it has
 * the query and results it was left with). The field's text lives here, beside the store: typing goes to the store as
 * it happens, and what the store sets on its own (a picked recent search, a `stremio:///search` link) is written back
 * into the field, so the cursor never jumps under the user's fingers.
 */
@Composable
fun SearchRoute(app: AppSession) {
    val navigator = app.navigator
    // Once per appearance: the entry still draws while it animates out after its route left the stack, and an entry
    // first composed after that gets no store and draws nothing.
    val store = remember {
        navigator.retain(
            Route.Search,
            key = SearchStore.KEY,
            create = app::searchStore,
            release = SearchStore::release,
        )
    } ?: return
    val state by store.state.collectAsState()
    val field = rememberTextFieldState(store.state.value.query)
    val linked by navigator.searchQuery.collectAsState()
    val picked by navigator.searchFocus.collectAsState()
    LaunchedEffect(store, linked) {
        if (linked == null) return@LaunchedEffect
        navigator.takeSearchQuery()?.let { query ->
            field.setTextAndPlaceCursorAtEnd(query)
            store.search(query)
        }
    }
    OnTextChange(field, store::setQuery)
    SearchScreen(
        state = state,
        field = field,
        fieldFocusAsked = picked,
        onFieldFocusTaken = { navigator.takeSearchFocus() },
        onSubmit = store::submit,
        onClear = {
            field.clearText()
            store.setQuery("")
        },
        onPickRecent = { index ->
            store.pickRecent(index)
            field.setTextAndPlaceCursorAtEnd(store.state.value.query)
        },
        onRemoveRecent = store::removeRecent,
        onClearRecent = store::clearRecent,
        onRetry = store::retry,
        onLoadMore = store::loadNextPage,
        onOpenItem = store::openItem,
        onItemMenu = store::menuAction,
        onOpenDownloads = store::openDownloads,
    )
}

/**
 * Search: the field on top, then the recent searches while it is blank (each can be searched
 * again or removed, or all cleared), else the results grouped by catalog as shelves, each loading or failing on its
 * own and asking for its next page as its end comes into view ([onLoadMore], the heading's loading indicator showing
 * while it comes). Picking Search in the navigation, again while it shows too ([fieldFocusAsked]:
 * [com.edde746.tortuga.presentation.navigation.TortugaNavigator.pick]), puts the cursor in the field, the software
 * keyboard up; by touch nothing else focuses the field, so Back from Detail and a start on Search leave the keyboard
 * down. With keys the field takes focus when the screen opens, on TV without raising the keyboard until it is pressed
 * (TVs leave [fieldFocusAsked] be); Down leaves it for the results, Enter searches at once and keeps the query as a
 * recent search. Offline ([SearchBody.Offline]) the offline state ([OfflineMessage]: Go to Downloads,
 * [onOpenDownloads]) takes the results' place. A result's menu ([MetaCard.menu]) gives the picked entry's action to
 * [onItemMenu].
 *
 * @param field the field's text, which the caller keeps and sets ([onClear], [onPickRecent]).
 * @param onFieldFocusTaken called once the field has acted on [fieldFocusAsked].
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    field: TextFieldState,
    fieldFocusAsked: Boolean,
    onFieldFocusTaken: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onPickRecent: (Int) -> Unit,
    onRemoveRecent: (Int) -> Unit,
    onClearRecent: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: (Int) -> Unit,
    onOpenItem: (MetaCard) -> Unit,
    onItemMenu: (MetaCard, MediaAction) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberFocusMemory()
    Column(modifier.fillMaxSize().padding(top = statusBarOverlap)) {
        SearchField(
            field = field,
            loading = state.searching,
            onSubmit = onSubmit,
            onClear = onClear,
            modifier = Modifier.rememberFocus(focus, FIELD).layoutProbe("search.field"),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            when (val body = state.body) {
                is SearchBody.Offline -> OfflineMessage(body.offline, onOpenDownloads, actionModifier = Modifier.rememberFocus(focus, "offline"))
                is SearchBody.Recent -> RecentSearches(body.recent, focus, onPickRecent, onRemoveRecent, onClearRecent)
                SearchBody.Hint -> EmptyState(T.browse.search.hint, icon = TortugaIcons.Search)
                SearchBody.Loading -> LoadingState()
                is SearchBody.Empty -> EmptyState(body.text, icon = TortugaIcons.Search)
                is SearchBody.Results -> Results(body.groups, focus, onRetry, onLoadMore, onOpenItem, onItemMenu)
            }
        }
    }
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(fieldFocusAsked) {
        if (!fieldFocusAsked || tv) return@LaunchedEffect
        // The field composes with the screen: a pick from another destination finds it a frame or more later.
        for (frame in 0 until FOCUS_RETRY_FRAMES) {
            withFrameNanos { }
            if (focus.focus(FIELD)) {
                // Already focused, with the keyboard put away, the field raises it again.
                keyboard?.show()
                break
            }
        }
        onFieldFocusTaken()
    }
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    RestoreFocus(focus, ready = true, fallback = FIELD.takeUnless { touch })
}

/**
 * The search field and, over its end while it has text, the button that clears it. The button is drawn over the field,
 * not inside it: the field's key handling sees every key of what is inside its decoration (Select raising the TV
 * keyboard, Enter searching, Android's own D-pad handling of text fields), so a remote couldn't press it there. A
 * remote reaches it with the arrow toward it at the text's end, and goes back to the field with the opposite arrow.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchField(
    field: TextFieldState,
    loading: Boolean,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTortugaDimens.current
    val focusManager = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // TV: focus lands here on open, and the keyboard waits for Select.
    val keyboard = rememberKeyboardOnSelect()
    val fieldFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().padding(start = dimens.screenPadding, end = dimens.screenPadding, top = 12.dp, bottom = 8.dp)) {
        Box(Modifier.contentColumn()) {
            OutlinedTextField(
                state = field,
                // The caller's probe and focus memory get the field itself, not the box it shares with the button.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(modifier)
                    .focusRequester(fieldFocus)
                    .then(keyboard.modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // A hardware Enter; the soft keyboard's search key comes through onKeyboardAction.
                            Key.Enter, Key.NumPadEnter -> {
                                onSubmit()
                                true
                            }
                            // A single-line field would move the cursor; Down leaves it for what is under it (D-pad).
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            // At the text's end, the arrow toward the clear button goes to it (a remote has no Tab).
                            // Focus search doesn't find it: its bounds lie inside the field's.
                            Key.DirectionRight, Key.DirectionLeft -> {
                                val towardClear = if (rtl) Key.DirectionLeft else Key.DirectionRight
                                val atEnd = field.selection.collapsed && field.selection.end == field.text.length
                                if (event.key == towardClear && field.text.isNotEmpty() && atEnd) {
                                    clearFocus.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                    .remoteLeavesField(field),
                placeholder = { Text(T.browse.search.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(TortugaIcons.Search, contentDescription = null) },
                trailingIcon = if (field.text.isEmpty() && !loading) {
                    null
                } else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (loading) LoadingIndicator(Modifier.size(LoadingIndicatorSize))
                            // The clear button's room: the button is drawn over it.
                            if (field.text.isNotEmpty()) Spacer(Modifier.padding(end = 4.dp).size(dimens.iconButtonSize))
                        }
                    }
                },
                lineLimits = TextFieldLineLimits.SingleLine,
                readOnly = keyboard.readOnly,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TortugaColors.Primary,
                    unfocusedBorderColor = TortugaColors.TextFaint,
                    cursorColor = TortugaColors.Primary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { onSubmit() },
            )
            if (field.text.isNotEmpty()) {
                // Where the field puts its trailing slot (material3's text field layout): at its end, centred.
                Box(Modifier.align(Alignment.CenterEnd).minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                    // The field's trailing icon colour.
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (loading) Spacer(Modifier.size(LoadingIndicatorSize))
                            TortugaIconButton(
                                TortugaIcons.Close,
                                contentDescription = T.browse.search.clearQuery,
                                // The button goes with the text: focus goes back to the field.
                                onClick = {
                                    onClear()
                                    fieldFocus.requestFocus()
                                },
                                // The arrow back goes to the field: focus search doesn't count the field, which
                                // contains the button, as beside it.
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .focusRequester(clearFocus)
                                    .focusProperties { start = fieldFocus }
                                    .layoutProbe("search.clear"),
                                style = IconButtonStyle.PLAIN,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(
    recent: List<RecentSearch>,
    focus: FocusMemory,
    onPick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    LazyColumn(
        modifier = Modifier.contentColumn().fillMaxSize(),
        // Room at every edge for a focused row's growth (TV): nothing clips it.
        contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = dimens.focusReach),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth().height(HeaderHeight).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(T.browse.search.recent, Modifier.weight(1f))
                HeaderAction(T.browse.search.clearRecent, onClear, Modifier.rememberFocus(focus, "recent:clear").layoutProbe("search.recent.clear"))
            }
        }
        itemsIndexed(recent) { index, item ->
            val query = item.query
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TortugaSurface(
                    onClick = { onPick(index) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).rememberFocus(focus, "recent:$query").layoutProbe("search.recent.$index"),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                    contentAlignment = Alignment.CenterStart,
                    grows = false,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(TortugaIcons.History, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.6f))
                        Text(query, style = MaterialTheme.typography.bodyLarge.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TortugaIconButton(
                    TortugaIcons.Close,
                    contentDescription = item.removeLabel,
                    onClick = { onRemove(index) },
                    modifier = Modifier.layoutProbe("search.recent.$index.remove"),
                    style = IconButtonStyle.PLAIN,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Results(
    groups: List<SearchGroup>,
    focus: FocusMemory,
    onRetry: () -> Unit,
    onLoadMore: (Int) -> Unit,
    onOpenItem: (MetaCard) -> Unit,
    onItemMenu: (MetaCard, MediaAction) -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    val listState = rememberLazyListState()
    val navigation = rememberShelfNavigation(listState)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().then(navigation.modifier),
        contentPadding = PaddingValues(top = 4.dp, bottom = dimens.sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.shelfSpacing),
    ) {
        itemsIndexed(groups, key = { _, group -> group.key }) { groupIndex, group ->
            val shelf = navigation.item(groupIndex)
            val currentIndex by rememberUpdatedState(groupIndex)
            val status = group.status
            when {
                group.items.isNotEmpty() -> ShelfRow(
                    title = group.title,
                    items = group.items,
                    key = { it.id },
                    modifier = shelf,
                    action = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (status is SearchGroupStatus.Ready && status.loadingMore) LoadingIndicator(Modifier.size(24.dp))
                            AddonLabel(group.addonName)
                        }
                    },
                    onNearEnd = { onLoadMore(currentIndex) },
                ) { index, card ->
                    MetaPoster(
                        card = card,
                        onClick = { onOpenItem(card) },
                        onMenu = { action -> onItemMenu(card, action) },
                        probe = "search.group.$groupIndex.item.$index",
                        modifier = Modifier.rememberFocus(focus, "result:${group.key}|${card.id}"),
                    )
                }
                status is SearchGroupStatus.Failed -> FailedShelf(
                    title = group.title,
                    message = status.text,
                    onRetry = onRetry,
                    modifier = shelf,
                    retryModifier = Modifier.rememberFocus(focus, "retry:${group.key}").layoutProbe("search.group.$groupIndex.retry"),
                )
                else -> LoadingShelf(group.title, shelf, trailing = { AddonLabel(group.addonName) })
            }
        }
    }
}

private const val FIELD = "field"

/** The loading indicator's size in the field's trailing slot, and the room the clear button keeps for it. */
private val LoadingIndicatorSize = 28.dp
