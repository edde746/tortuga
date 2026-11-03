package com.edde746.tortuga.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.edde746.tortuga.presentation.detail.DetailWatchlist
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe

/** The watchlist action's bookmark: filled while the title is on a tracker's list. */
internal val DetailWatchlist.icon: ImageVector
    get() = if (listed) TortugaIcons.Bookmark else TortugaIcons.BookmarkBorder

/**
 * The TV row's watchlist ([com.edde746.tortuga.presentation.detail.DetailAction.WATCHLIST]; off a TV it is behind ⋯):
 * a round bookmark button read [DetailWatchlist.label], selected while the title is on a list, toggling it with one
 * tracker or opening the trackers' watchlists sheet with several. Harness name: `detail.watchlist`.
 */
@Composable
internal fun WatchlistButton(watchlist: DetailWatchlist, focus: FocusMemory, intents: DetailIntents) {
    TortugaIconButton(
        watchlist.icon,
        watchlist.label,
        intents.watchlist,
        Modifier.rememberFocus(focus, FOCUS_WATCHLIST).layoutProbe("detail.watchlist"),
        style = IconButtonStyle.OVER_PICTURE,
        selected = watchlist.listed,
    )
}
