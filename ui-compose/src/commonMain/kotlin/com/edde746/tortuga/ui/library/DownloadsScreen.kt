package com.edde746.tortuga.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.downloads.DownloadAction
import com.edde746.tortuga.presentation.downloads.DownloadActionItem
import com.edde746.tortuga.presentation.downloads.DownloadDeleteDialog
import com.edde746.tortuga.presentation.downloads.DownloadGroup
import com.edde746.tortuga.presentation.downloads.DownloadRow
import com.edde746.tortuga.presentation.downloads.DownloadsBody
import com.edde746.tortuga.presentation.downloads.DownloadsUiState
import com.edde746.tortuga.presentation.downloads.KeepSyncedLink
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.adaptive.largeFontScale
import com.edde746.tortuga.ui.components.ArtworkRow
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.Chevron
import com.edde746.tortuga.ui.components.DownloadMark
import com.edde746.tortuga.ui.components.DownloadProgressBar
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ItemMenu
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ItemMenuSection
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.SectionedItemMenu
import com.edde746.tortuga.ui.components.TimedToast
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.components.icon
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.players.menuSection
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * Library's Downloads section ([DownloadsUiState]): the Library's tabs ([FilterBar], the tabs alone), then the offline
 * note, the storage line with Pause all and Resume all, the row to the Keep synced screen while the profile has rules
 * ([DownloadsUiState.keepSynced], [onOpenKeepSynced]), and the profile's downloads: each series under its poster and
 * name (its episodes by season, then its videos without an episode number), each movie as one row; the store's empty
 * text instead when there are none. A row shows its artwork, title, a line of its code, status and bytes (with the
 * speed while it downloads), a progress bar while it transfers and why it failed; a press opens it ([onOpen]: plays a
 * complete download). Its actions ([DownloadRow.actions]) are a menu in their order: the ⋮ button at its end
 * (focusable, a remote's Right from the row), a long press, a right click or the Menu key; "Play with…"
 * ([DownloadAction.PLAY_WITH]) is a section there of [DownloadsUiState.playWith]'s players ([onPlayWith]: its id and
 * the player's). A series' ⋮ deletes its downloads ([onDeleteGroup]) once the store's question
 * ([DownloadsUiState.deleteDialog]) is answered: [onConfirmDeleteGroup] or [onDismissDeleteGroup]. An action that
 * failed ([DownloadsUiState.notice]) shows as a toast at the bottom, then [onNoticeShown].
 *
 * Harness names: `downloads.list` (the column), `downloads.offline`, `downloads.storage`, `downloads.pauseAll`,
 * `downloads.resumeAll`, `downloads.keepSynced`, `downloads.empty`, `downloads.notice`, `downloads.group.<key>` with its
 * `.menu` and `.delete`, the question's `downloads.deleteGroup.<cancel|confirm>`, `downloads.season.<key>.<season>`,
 * `downloads.videos.<key>` (the heading over a series' videos without an episode number), `downloads.row.<id>` with its
 * `.menu`, a row's actions `downloads.action.<play|pause|resume|retry|pickStream|cancel|delete|openDetail>` and its
 * players `downloads.action.playWith.<player id>`.
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpen: (Long) -> Unit,
    onPerform: (Long, DownloadAction) -> Unit,
    onPlayWith: (id: Long, playerId: String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onConfirmDeleteGroup: () -> Unit,
    onDismissDeleteGroup: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onOpenKeepSynced: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberFocusMemory()
    val intents = remember(onOpen, onPerform, onPlayWith, onDeleteGroup, onPauseAll, onResumeAll, onOpenKeepSynced) {
        DownloadsIntents(onOpen, onPerform, onPlayWith, onDeleteGroup, onPauseAll, onResumeAll, onOpenKeepSynced)
    }
    Column(modifier.fillMaxSize().padding(top = statusBarOverlap)) {
        FilterBar(null, tabs, selectedTab, onSelectTab, onSelectType = {}, onSelectSort = {}, onOpenCalendar = null)
        Box(Modifier.fillMaxSize()) {
            when (val body = state.body) {
                DownloadsBody.Loading -> LoadingState()
                is DownloadsBody.Empty -> DownloadsList(state, focus, intents, firstFilm = null) {
                    item(key = "empty") { EmptyState(body.text, Modifier.fillParentMaxHeight(0.7f).layoutProbe("downloads.empty"), icon = TortugaIcons.Download) }
                }
                is DownloadsBody.Groups -> DownloadsList(state, focus, intents, firstFilm = body.groups.first().movie?.let(::rowKey)) {
                    body.groups.forEach { group -> downloadGroup(group, state.playWith, focus, intents) }
                }
            }
            state.notice?.let {
                TimedToast(it, "downloads.notice", onNoticeShown, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalTortugaDimens.current.sectionSpacing))
            }
        }
    }
    state.deleteDialog?.let { DownloadDeleteOverlay(it, "downloads.deleteGroup", onConfirmDeleteGroup, onDismissDeleteGroup) }
    val first = when (val body = state.body) {
        DownloadsBody.Loading, is DownloadsBody.Empty -> null
        is DownloadsBody.Groups -> body.groups.first().let { group ->
            (group.movie ?: group.seasons.firstOrNull()?.episodes?.firstOrNull() ?: group.videos.firstOrNull())?.let(::rowKey)
        }
    }
    val fallback = first ?: when {
        state.keepSynced != null -> FOCUS_KEEP_SYNCED
        state.pauseAllText != null -> FOCUS_PAUSE_ALL
        state.resumeAllText != null -> FOCUS_RESUME_ALL
        else -> null
    }
    RestoreFocus(focus, ready = state.body != DownloadsBody.Loading && fallback != null, fallback = fallback)
}

private class DownloadsIntents(
    val open: (Long) -> Unit,
    val perform: (Long, DownloadAction) -> Unit,
    val playWith: (Long, String) -> Unit,
    val deleteGroup: (String) -> Unit,
    val pauseAll: () -> Unit,
    val resumeAll: () -> Unit,
    val openKeepSynced: () -> Unit,
)

private const val FOCUS_PAUSE_ALL = "pauseAll"
private const val FOCUS_RESUME_ALL = "resumeAll"
private const val FOCUS_KEEP_SYNCED = "keepSynced"

private fun rowKey(row: DownloadRow) = "row:${row.id}"

/** A row's inset on every side of its artwork and text, and the gap between them. */
internal val RowInset = 12.dp

/** An artwork's corners in a row. */
private val ArtworkRadius = 6.dp

/** The toolbar and its rows over [body]: the downloads, or that there are none. [firstFilm]: the first group's film's row. */
@Composable
private fun DownloadsList(state: DownloadsUiState, focus: FocusMemory, intents: DownloadsIntents, firstFilm: String?, body: LazyListScope.() -> Unit) {
    val dimens = LocalTortugaDimens.current
    LazyColumn(
        modifier = Modifier.contentColumn().fillMaxSize().layoutProbe("downloads.list", reportsFocus = false),
        state = rememberLazyListState(),
        // Room at the top for a focused button's growth (TV).
        contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.focusReach, bottom = dimens.sectionSpacing),
    ) {
        state.offlineNote?.let { note -> item(key = "offline") { OfflineNote(note) } }
        // Down from the toolbar: the Keep synced row while it shows, else a film's row first in the list.
        val below = if (state.keepSynced != null) FOCUS_KEEP_SYNCED else firstFilm
        item(key = "toolbar") { Toolbar(state, focus, intents, below) }
        state.keepSynced?.let { link -> item(key = "keepSynced") { KeepSyncedLinkRow(link, focus, intents.openKeepSynced) } }
        body()
    }
}

/** "You're offline. Downloads continue once you're back online.", on top while offline. */
@Composable
private fun OfflineNote(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(TortugaColors.SurfaceHighest, MaterialTheme.shapes.large)
            .padding(horizontal = RowInset, vertical = 10.dp)
            .layoutProbe("downloads.offline"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TortugaIcons.CloudOff, contentDescription = null, Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * The row to the Keep synced screen: "Keep synced", "2 series" and a chevron, above the downloads. Focus comes back to
 * it from that screen.
 */
@Composable
private fun KeepSyncedLinkRow(link: KeepSyncedLink, focus: FocusMemory, onOpen: () -> Unit) {
    GroupRow(
        link.title,
        onOpen,
        Modifier.padding(bottom = 8.dp).rememberFocus(focus, FOCUS_KEEP_SYNCED).layoutProbe("downloads.keepSynced"),
        value = link.value,
        icon = TortugaIcons.Sync,
        trailing = { Chevron() },
    )
}

/**
 * The storage used and free, then Pause all and Resume all while they apply, at the end. Down from them lands on what
 * is under them ([below]): the Keep synced row, else the first title when it is a film (its row, not the ⋮ beside it
 * that a wider label would put under them); else a series' heading by Compose's own pick, its ⋮ being the heading's
 * one stop ([GroupHeading]).
 */
@Composable
private fun Toolbar(state: DownloadsUiState, focus: FocusMemory, intents: DownloadsIntents, below: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Down && below != null) focus.focus(below) } }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.storage,
            style = MaterialTheme.typography.bodyMedium,
            color = TortugaColors.TextMuted,
            modifier = Modifier.weight(1f).padding(start = RowInset).layoutProbe("downloads.storage"),
        )
        state.pauseAllText?.let {
            TortugaButton(
                it,
                intents.pauseAll,
                Modifier.rememberFocus(focus, FOCUS_PAUSE_ALL).layoutProbe("downloads.pauseAll"),
                style = ButtonStyle.SECONDARY,
                icon = TortugaIcons.Pause,
            )
        }
        state.resumeAllText?.let {
            TortugaButton(
                it,
                intents.resumeAll,
                Modifier.rememberFocus(focus, FOCUS_RESUME_ALL).layoutProbe("downloads.resumeAll"),
                style = ButtonStyle.SECONDARY,
                icon = TortugaIcons.PlayArrow,
            )
        }
    }
}

/**
 * One title's downloads: a movie as one row (its poster as the artwork when it has none of its own); a series as its
 * heading, then each season's title over its episodes as one connected group of rows, then the same for its videos
 * without an episode number. [playWith] goes to the rows' menus.
 */
private fun LazyListScope.downloadGroup(group: DownloadGroup, playWith: PlayWithMenu?, focus: FocusMemory, intents: DownloadsIntents) {
    val movie = group.movie
    if (movie != null) {
        item(key = "movie:${group.key}") {
            DownloadRowItem(movie, movie.artwork ?: group.poster, poster = true, groupShape(0, 1), playWith, focus, intents, Modifier.padding(top = GroupSpacing))
        }
        return
    }
    item(key = "group:${group.key}") { GroupHeading(group, intents) }
    group.seasons.forEach { season ->
        item(key = "season:${group.key}:${season.season}") {
            GroupHeader(season.title, Modifier.layoutProbe("downloads.season.${group.key}.${season.season}"))
        }
        itemsIndexed(season.episodes, key = { _, row -> rowKey(row) }) { index, row ->
            DownloadRowItem(
                row,
                row.artwork,
                poster = false,
                groupShape(index, season.episodes.size),
                playWith,
                focus,
                intents,
                Modifier.padding(top = if (index > 0) GroupGap else 0.dp),
            )
        }
    }
    if (group.videos.isNotEmpty()) {
        item(key = "videos:${group.key}") { GroupHeader(group.videosTitle, Modifier.layoutProbe("downloads.videos.${group.key}")) }
    }
    itemsIndexed(group.videos, key = { _, row -> rowKey(row) }) { index, row ->
        DownloadRowItem(
            row,
            row.artwork,
            poster = false,
            groupShape(index, group.videos.size),
            playWith,
            focus,
            intents,
            Modifier.padding(top = if (index > 0) GroupGap else 0.dp),
        )
    }
}

/** Between one title's downloads and the next. */
private val GroupSpacing = 16.dp

/**
 * A series' heading: its poster, its name and summary ("3 episodes · 4.2 GB"), and a ⋮ to delete its downloads (asked
 * first). At a large font scale the name and summary go under the poster, whole ([ArtworkRow]). The heading is one
 * focus group as wide as the rows, so Up from the series' first episode and Down from the title above land on its ⋮,
 * which alone at the row's end would lose to a full-width row further on.
 */
@Composable
private fun GroupHeading(group: DownloadGroup, intents: DownloadsIntents) {
    var menu by remember { mutableStateOf(false) }
    val probe = "downloads.group.${group.key}"
    val stacked = largeFontScale()
    ArtworkRow(
        stacked,
        gap = RowInset,
        artwork = { Artwork(group.poster, poster = true) },
        // The poster, and a stacked heading's text, start at the rows' inset. The heading's name is its bounds only:
        // focus is its ⋮'s.
        modifier = Modifier.fillMaxWidth().padding(top = GroupSpacing).focusGroup().layoutProbe(probe, reportsFocus = false).padding(start = RowInset),
        trailing = {
            Box {
                TortugaIconButton(TortugaIcons.MoreVert, group.moreLabel, { menu = true }, Modifier.layoutProbe("$probe.menu"), style = IconButtonStyle.PLAIN)
                ItemMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    entries = listOf(ItemMenuEntry(group.deleteText, "delete", destructive = true) { intents.deleteGroup(group.key) }),
                    probe = probe,
                    title = group.title,
                    contentTitle = true,
                )
            }
        },
    ) {
        Text(
            group.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold).contentDirection(),
            maxLines = if (stacked) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        group.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted, maxLines = if (stacked) Int.MAX_VALUE else 1) }
    }
}

/**
 * The question before several downloads go at once ([DownloadDeleteDialog]: a series' group here, a season's or a whole
 * title's in Detail): Cancel has focus first. Harness names: `<probe>.cancel`, `<probe>.confirm`.
 */
@Composable
internal fun DownloadDeleteOverlay(dialog: DownloadDeleteDialog, probe: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = onDismiss, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(dialog.cancelLabel, onDismiss, Modifier.focusRequester(cancelFocus).layoutProbe("$probe.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(dialog.confirmLabel, onConfirm, Modifier.layoutProbe("$probe.confirm"), style = ButtonStyle.DESTRUCTIVE)
        }
    }
}

/**
 * One download: its artwork ([poster]: a movie's 2:3 poster, else an episode's 16:9 thumbnail), its title, a line of
 * its code, status and bytes, its progress bar while it isn't complete and why it failed, its status mark at the end;
 * then the ⋮ that opens its actions, beside the row (so a remote reaches it with Right). At a large font scale the
 * text goes under the artwork, whole, the status mark at the artwork's end ([ArtworkRow]). The menu's "Play with…" is a
 * section of [playWith]'s players where the row's actions put it.
 */
@Composable
private fun DownloadRowItem(
    row: DownloadRow,
    artwork: String?,
    poster: Boolean,
    shape: Shape,
    playWith: PlayWithMenu?,
    focus: FocusMemory,
    intents: DownloadsIntents,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val probe = "downloads.row.${row.id}"
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TortugaSurface(
            onClick = { intents.open(row.id) },
            onLongClick = { menu = true },
            modifier = Modifier.weight(1f).rememberFocus(focus, rowKey(row)).menuGestures { menu = true }.layoutProbe(probe),
            shape = shape,
            grows = false,
        ) {
            val stacked = largeFontScale()
            ArtworkRow(
                stacked,
                gap = RowInset,
                artwork = { Artwork(artwork, poster) },
                modifier = Modifier.padding(RowInset),
                trailing = { DownloadMark(row.status.icon, row.progress?.toFloat()?.takeIf { row.status == DownloadStatus.DOWNLOADING }) },
            ) {
                val muted = LocalContentColor.current.copy(alpha = 0.6f)
                Text(row.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = if (stacked) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(row.subtitle, row.statusText, row.bytesText).reduce(T.detail::infoJoin),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = if (stacked) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = row.progress
                if (progress != null && row.status != DownloadStatus.COMPLETED) DownloadProgressBar(progress.toFloat(), Modifier.padding(top = 4.dp))
                row.failureText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TortugaColors.Error, maxLines = if (stacked) Int.MAX_VALUE else 3) }
            }
        }
        Box {
            TortugaIconButton(
                TortugaIcons.MoreVert,
                row.moreLabel,
                { menu = true },
                Modifier.rememberFocus(focus, "menu:${row.id}").layoutProbe("$probe.menu"),
                style = IconButtonStyle.PLAIN,
            )
            // The actions in their order, Play with… a section of the players between the ones before it and after it.
            val split = row.actions.indexOfFirst { it.action == DownloadAction.PLAY_WITH }
            fun section(items: List<DownloadActionItem>) = ItemMenuSection(
                null,
                items.map { item -> ItemMenuEntry(item.label, item.action.id, destructive = item.destructive) { intents.perform(row.id, item.action) } },
            )
            SectionedItemMenu(
                expanded = menu,
                onDismiss = { menu = false },
                sections = if (split < 0) {
                    listOf(section(row.actions))
                } else {
                    listOfNotNull(
                        section(row.actions.take(split)),
                        playWith?.menuSection { playerId -> intents.playWith(row.id, playerId) },
                        section(row.actions.drop(split + 1)),
                    )
                },
                probe = "downloads.action",
                title = row.title,
            )
        }
    }
}


/** A row's artwork, blurred past recognition while [blurred], with the film icon while it loads or when there is none. */
@Composable
internal fun Artwork(url: String?, poster: Boolean, blurred: Boolean = false) {
    val width = artworkWidth(poster)
    val shape = RoundedCornerShape(ArtworkRadius)
    RemoteImage(
        url,
        contentDescription = null,
        modifier = Modifier
            .width(width)
            .aspectRatio(if (poster) 2f / 3f else 16f / 9f)
            .clip(shape)
            .background(TortugaColors.SurfaceHighest, shape),
        blurred = blurred,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(TortugaIcons.Movie, contentDescription = null, Modifier.size(20.dp), tint = TortugaColors.TextFaint)
        }
    }
}

@Composable
private fun artworkWidth(poster: Boolean): Dp {
    val layout = LocalWindowLayout.current
    return when {
        layout.formFactor == FormFactor.TV -> if (poster) 64.dp else 176.dp
        layout.widthClass == WidthClass.COMPACT -> if (poster) 48.dp else 112.dp
        else -> if (poster) 56.dp else 144.dp
    }
}
