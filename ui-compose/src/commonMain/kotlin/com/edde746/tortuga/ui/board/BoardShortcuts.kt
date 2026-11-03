package com.edde746.tortuga.ui.board

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.board.BoardShortcut
import com.edde746.tortuga.presentation.board.BoardShortcutKind
import com.edde746.tortuga.presentation.board.BoardUiState
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.detail.mark
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.clipStart
import com.edde746.tortuga.ui.navigation.navigationOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens

/**
 * Home's shortcuts ([BoardUiState.shortcuts]: Watch together, Downloads, Library, the TV guide, the Calendar, the
 * trackers' lists) as one line of the app's solid chips ([TortugaChip], none selected: each opens a screen, none is a
 * choice), from the shelves' leading edge, 8 dp apart, scrolling sideways. No arrow: the chip the screen's edge cuts
 * shows the row goes on, and a swipe or the D-pad moves it. A chip runs [onOpen] with its key; screen readers hear its
 * [BoardShortcut.accessibilityLabel].
 *
 * A chip that opens one of the app's screens leads with that feature's icon, the one it has elsewhere in the app
 * ([icon]); a tracker's list with its service's mark ([BoardShortcut.tracker], the one Settings → Trackers shows), in
 * one colour as the icons are. Either is drawn in the label's colour (black with the label on a focused chip, a TV's
 * included), the chip as tall as without it.
 *
 * With a remote or a keyboard each chip is a focus stop, focused as every chip (the primary fill and a ring inside its
 * edge, its size kept), and part of the Board's focus memory ([focus]), so coming back from the screen one opened finds
 * focus on it. Up or Down into the row lands on the first chip; Down from it goes on to [below] (the next shelf's first
 * card) while it is composed, not to the card under the chip. Harness names: `board.shortcuts` (the row),
 * `board.shortcut.<key>`, `board.shortcut.<key>.icon` (its icon).
 */
@Composable
internal fun BoardShortcuts(
    shortcuts: List<BoardShortcut>,
    focus: FocusMemory,
    onOpen: (String) -> Unit,
    below: String?,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        // Chips scrolled away end at the TV column's edge, as the shelves' cards.
        modifier = modifier
            .fillMaxWidth()
            .layoutProbe("board.shortcuts", reportsFocus = false)
            .clipStart(navigationOverlap)
            .onPreviewKeyEvent { event -> event.isDown() && below != null && focus.focus(below) }
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) {
                        focus.focus(shortcutKey(shortcuts.first()))
                    }
                }
            }
            .focusGroup(),
        contentPadding = PaddingValues(horizontal = LocalTortugaDimens.current.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(shortcuts, key = { it.key }) { shortcut ->
            TortugaChip(
                shortcut.label,
                selected = false,
                onClick = { onOpen(shortcut.key) },
                modifier = Modifier
                    .semantics { contentDescription = shortcut.accessibilityLabel }
                    .rememberFocus(focus, shortcutKey(shortcut))
                    .layoutProbe("board.shortcut.${shortcut.key}"),
                leadingIcon = shortcut.tracker?.mark ?: shortcut.kind.icon,
                iconProbe = "board.shortcut.${shortcut.key}.icon",
            )
        }
    }
}

/** A shortcut in the Board's focus memory. */
private fun shortcutKey(shortcut: BoardShortcut) = "shortcut:${shortcut.key}"

/**
 * A shortcut's icon: its feature's everywhere in the app (Watch together's people, the download arrow, Library's
 * destination, the guide's clock, the Calendar's page). A tracker's list has none of its own: no one glyph fits a
 * watchlist, a trending list and a list of the user's, so it shows its service's mark.
 */
private val BoardShortcutKind.icon: ImageVector?
    get() = when (this) {
        BoardShortcutKind.WATCH_TOGETHER -> TortugaIcons.Groups
        BoardShortcutKind.DOWNLOADS -> TortugaIcons.Download
        BoardShortcutKind.LIBRARY -> TortugaIcons.VideoLibrary
        BoardShortcutKind.GUIDE -> TortugaIcons.Schedule
        BoardShortcutKind.CALENDAR -> TortugaIcons.CalendarToday
        BoardShortcutKind.TRACKER_LIST -> null
    }
