package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.edde746.tortuga.presentation.detail.DetailRelated
import com.edde746.tortuga.ui.board.LoadingShelf
import com.edde746.tortuga.ui.board.MetaPoster
import com.edde746.tortuga.ui.components.HeaderHeight
import com.edde746.tortuga.ui.components.SectionTitle
import com.edde746.tortuga.ui.components.ShelfRow
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * "More like this" ([DetailRelated]): titles like this one as a catalog's shelf on the Board ([ShelfRow]), with the
 * Board's cards ([MetaPoster]: the poster, the title and year on one line each, the watched mark, the card's menu).
 * While the sources are asked, the Board's loading shelf (blank cards, nothing to focus); when they answered with no
 * titles, the heading and one line saying so; the page shows no row at all when none answered ([DetailUiState.related]
 * null). A card opens its title on a Detail page of its own ([DetailIntents.openRelated]); its menu's entries go to
 * [DetailIntents.relatedMenu]. Focus on a card is remembered ([focus]), so coming back from that page lands on it.
 * Harness names: `detail.related` (the row), `detail.related.loading`, `detail.related.empty`,
 * `detail.related.item.<i>` and its menu's `detail.related.item.<i>.menu.<action>`.
 */
@Composable
internal fun RelatedShelf(related: DetailRelated, focus: FocusMemory, intents: DetailIntents, modifier: Modifier = Modifier) {
    val row = modifier.layoutProbe("detail.related")
    val empty = related.emptyText
    when {
        related.loading -> LoadingShelf(related.title, row.layoutProbe("detail.related.loading"))
        empty != null -> {
            val dimens = LocalTortugaDimens.current
            Column(row.fillMaxWidth().padding(horizontal = dimens.screenPadding)) {
                Box(Modifier.height(HeaderHeight).semantics { heading() }, contentAlignment = Alignment.CenterStart) { SectionTitle(related.title) }
                Spacer(Modifier.height(dimens.shelfHeaderGap))
                Text(
                    empty,
                    style = MaterialTheme.typography.bodyMedium.contentDirection(),
                    color = TortugaColors.TextMuted,
                    modifier = Modifier.layoutProbe("detail.related.empty"),
                )
            }
        }
        else -> ShelfRow(related.title, related.cards, key = { it.id }, modifier = row) { position, card ->
            MetaPoster(
                card = card,
                onClick = { intents.openRelated(card) },
                onMenu = { action -> intents.relatedMenu(card, action) },
                probe = "detail.related.item.$position",
                modifier = Modifier.rememberFocus(focus, "related:${card.id}"),
            )
        }
    }
}

/**
 * Lays [this] out [by] wider on each side than the room it is given, centred on it: a shelf in the wide page's details
 * column, whose padding ([by]) holds a focused control's growth and ring, reaches the column's scrolling edges, so its
 * cards scroll out of sight there rather than at the column's text.
 */
internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}
