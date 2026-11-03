package com.edde746.tortuga.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.menu.WatchlistRow
import com.edde746.tortuga.presentation.menu.WatchlistSheet
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.RowGroup
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.detail.mark
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * The trackers' watchlists for one title ([WatchlistSheet]), a [TortugaSheet] (on TV the side panel) as the rating
 * picker is: a hint line, then a row per tracker with its mark and its list's name, a check while the title is on it,
 * and where its write is ([WatchlistRow.status]; in the error colour while a change isn't saved). Each row puts the
 * title on its list or takes it off ([onToggle]), the sheet staying open while the trackers answer. Focus starts on the
 * first row. Harness names: `app.watchlist.sheet`, `app.watchlist.<service id>`.
 */
@Composable
internal fun WatchlistSheet(sheet: WatchlistSheet, onToggle: (TrackerService) -> Unit, onDismiss: () -> Unit) {
    val initial = remember { FocusRequester() }
    TortugaSheet(onDismiss = onDismiss, title = sheet.title, initialFocus = initial, probe = "app.watchlist.sheet") {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(sheet.hint, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
            RowGroup {
                sheet.rows.forEachIndexed { index, row ->
                    val focus = if (index == 0) Modifier.focusRequester(initial) else Modifier
                    WatchlistLine(row, groupShape(index, sheet.rows.size), focus) { onToggle(row.service) }
                }
            }
        }
    }
}

/** One tracker's row: its mark and list's name, then where its write is and a check while the title is on the list. */
@Composable
private fun WatchlistLine(row: WatchlistRow, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    GroupRow(
        row.name,
        onClick = onClick,
        modifier = modifier.layoutProbe("app.watchlist.${row.service.id}"),
        shape = shape,
        logo = rememberVectorPainter(row.service.mark),
        trailing = {
            row.status?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.attention) TortugaColors.Error else LocalContentColor.current.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
            if (row.listed) Icon(TortugaIcons.Check, contentDescription = null, Modifier.size(22.dp))
        },
    )
}
