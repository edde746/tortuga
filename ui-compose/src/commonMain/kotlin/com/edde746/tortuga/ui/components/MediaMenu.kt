package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.ui.icons.TortugaIcons

/**
 * A media item (a poster, a Continue Watching card, a library item) with its menu: [content] draws the item, its
 * `onMenu` opening the menu (give it to the item's `onLongClick` and [menuGestures]), and the menu lists [entries], the
 * store's, in their order ([ItemMenu] with `media`: on TV a bottom sheet titled [title]). Picking one calls [onPick]
 * with its action. Its entries are named `<probe>.menu.<action>` (`board.cw.0.menu.removeFromContinueWatching`).
 */
@Composable
fun MediaMenu(
    title: String,
    entries: List<MediaMenuEntry>,
    onPick: (MediaAction) -> Unit,
    probe: String,
    content: @Composable (onMenu: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        content { menu = true }
        val items = remember(entries, onPick) { entries.map { it.itemEntry(onPick) } }
        ItemMenu(expanded = menu, onDismiss = { menu = false }, entries = items, probe = "$probe.menu", title = title, media = true, contentTitle = true)
    }
}

/**
 * An entry of a media item's menu as an [ItemMenuEntry]: the store's label, its action's [icon], named after its action
 * ([MediaAction.id]: `markWatched`, `removeFromContinueWatching`), red where the action is [MediaAction.destructive].
 * Picking it calls [onPick] with its action.
 */
fun MediaMenuEntry.itemEntry(onPick: (MediaAction) -> Unit): ItemMenuEntry =
    ItemMenuEntry(label, action.id, destructive = action.destructive, icon = action.icon) { onPick(action) }

/** The icon of a media menu's entry, by what it does (Apple's symbols). */
private val MediaAction.icon: ImageVector
    get() = when (this) {
        MediaAction.PLAY_FROM_START -> TortugaIcons.Replay
        MediaAction.MARK_WATCHED, MediaAction.MARK_SEASON_WATCHED -> TortugaIcons.VisibilityOutlined
        MediaAction.MARK_UNWATCHED, MediaAction.MARK_SEASON_UNWATCHED -> TortugaIcons.Visibility
        MediaAction.REMOVE_FROM_CONTINUE_WATCHING -> TortugaIcons.RemoveCircle
        MediaAction.DETAILS -> TortugaIcons.Info
        MediaAction.CHOOSE_STREAM -> TortugaIcons.StreamList
        MediaAction.DOWNLOAD -> TortugaIcons.Download
        MediaAction.PAUSE_DOWNLOAD -> TortugaIcons.Pause
        MediaAction.RESUME_DOWNLOAD -> TortugaIcons.PlayArrow
        MediaAction.RETRY_DOWNLOAD -> TortugaIcons.Refresh
        MediaAction.CANCEL_DOWNLOAD -> TortugaIcons.Close
        MediaAction.DELETE_DOWNLOAD -> TortugaIcons.Delete
        MediaAction.TRAILER -> TortugaIcons.Trailer
        MediaAction.ADD_TO_LIBRARY -> TortugaIcons.Add
        MediaAction.REMOVE_FROM_LIBRARY -> TortugaIcons.Check
        MediaAction.ADD_TO_WATCHLIST, MediaAction.WATCHLISTS -> TortugaIcons.BookmarkBorder
        MediaAction.REMOVE_FROM_WATCHLIST -> TortugaIcons.Bookmark
    }
