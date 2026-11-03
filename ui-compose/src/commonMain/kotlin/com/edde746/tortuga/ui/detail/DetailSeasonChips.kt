package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.detail.DetailSeason
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.ItemMenu
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.itemEntry
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.widestLook
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.tvRow
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.clipStart
import com.edde746.tortuga.ui.navigation.navigationOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import kotlinx.coroutines.flow.first

/**
 * A series' seasons as a row of chips (`detail.season.<i>`) and, for a season of more than 50 episodes, its ranges'
 * chips in a row under them (`detail.range.<i>`, [DetailUiState.episodeRanges]), on every layout: TV's page, the
 * one-column page (the rows scroll sideways) and the episodes' column beside the details. Harness name of the two rows
 * together: `detail.seasons`. The rows open with the selected season's chip in view (the last season of a long title
 * is past the row's end), their first name at [nameStart] from the rows' start edge. Nothing is drawn behind the rows:
 * where they lie over the picture, the page's own backdrop dim keeps the names readable ([UNSELECTED_ALPHA]).
 *
 * A season's chip selects that season; a range's runs [onRange] with the range's index, and the [selectedRange]'s chip
 * shows selected: TV's row comes to the range's first episode, a list shows the range ([DetailUiState.listedRange]). On
 * TV (a D-pad) a chip acts as focus moves onto it, as Prime Video's, so the next season is one Right; a move into either
 * row from above or below lands on its selected chip, so passing through never selects another ([tvRow]), and Up from
 * the seasons goes where [up] puts focus (TV's title block). Elsewhere a tap, a click or Select picks it. Each season's
 * menu (a held Select on TV, a long press on touch, a right click, the Menu key) is its [DetailSeason.menu]
 * (`detail.menu.item.<action>`): it marks the season or the whole title watched.
 */
@Composable
internal fun SeasonChips(
    state: DetailUiState,
    focus: FocusMemory,
    intents: DetailIntents,
    selectedRange: Int,
    onRange: (Int) -> Unit,
    nameStart: Dp,
    modifier: Modifier = Modifier,
    up: (FocusEnterExitScope.() -> Unit)? = null,
) {
    val seasons = state.seasons
    val ranges = state.episodeRanges
    if (seasons.isEmpty() && ranges.isEmpty()) return
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val padding = PaddingValues(horizontal = nameStart - ChipInset, vertical = 4.dp)
    Column(
        modifier.fillMaxWidth().clipStart(navigationOverlap).layoutProbe("detail.seasons"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (seasons.isNotEmpty()) {
            val selected = state.selectedSeasonIndex
            val list = rememberLazyListState()
            LaunchedEffect(Unit) { list.showWhole(selected) }
            LazyRow(
                state = list,
                modifier = Modifier.fillMaxWidth().then(if (tv) Modifier.tvRow(up = up) { focus.focus("season:${seasons[selected].season}") } else Modifier),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(seasons, key = { _, season -> season.season }) { index, season ->
                    SeasonChip(season, index, tv, focus, intents)
                }
            }
        }
        if (ranges.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().then(if (tv) Modifier.tvRow { focus.focus("range:$selectedRange") } else Modifier),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(ranges) { index, range ->
                    DetailChip(
                        range.label,
                        selected = index == selectedRange,
                        onSelect = { onRange(index) },
                        selectsOnFocus = tv,
                        modifier = Modifier.rememberFocus(focus, "range:$index").layoutProbe("detail.range.$index"),
                    )
                }
            }
        }
    }
}

/** Scrolls the row to show item [index] whole, once the row has its first layout, where it doesn't already. */
private suspend fun LazyListState.showWhole(index: Int) {
    val info = snapshotFlow { layoutInfo }.first { it.totalItemsCount > 0 }
    val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (shown == null || shown.offset + shown.size > info.viewportEndOffset) scrollToItem(index)
}

/** A season's chip ([DetailChip]), its menu ([DetailSeason.menu]) marking it or the whole title watched. */
@Composable
private fun SeasonChip(season: DetailSeason, index: Int, tv: Boolean, focus: FocusMemory, intents: DetailIntents) {
    var menu by remember { mutableStateOf(false) }
    Box {
        DetailChip(
            season.label,
            selected = season.selected,
            onSelect = { if (!season.selected) intents.selectSeason(season.season) },
            selectsOnFocus = tv,
            modifier = Modifier.rememberFocus(focus, "season:${season.season}").menuGestures { menu = true }.layoutProbe("detail.season.$index"),
            watched = season.watched,
            stateText = season.stateText,
            onLongClick = { menu = true },
        )
        val entries = remember(season.menu, intents) {
            season.menu.map { entry -> entry.itemEntry { action -> intents.seasonMenu(season.season, action) } }
        }
        ItemMenu(expanded = menu, onDismiss = { menu = false }, entries = entries, probe = MENU_PROBE, title = season.label)
    }
}

/**
 * A chip of the seasons' rows, as tvOS draws its tabs over a picture: no fill at rest, the primary fill while it has
 * focus (no growth, no ring: the row keeps one height); hover and press tint it. The selected one's name is in the
 * content colour and SemiBold, the others' at [UNSELECTED_ALPHA] and Regular, each as wide as the wider of the two, the
 * name centred, so a pick moves no chip of the row; a check marks a watched season, which screen readers hear as its
 * state ([stateText], "Watched"). [onSelect] runs on a tap, a click or Select, and with [selectsOnFocus] (TV) as focus
 * moves onto it.
 */
@Composable
private fun DetailChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    selectsOnFocus: Boolean,
    modifier: Modifier,
    watched: Boolean = false,
    stateText: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    TortugaSurface(
        onClick = onSelect,
        modifier = modifier
            .heightIn(min = LocalTortugaDimens.current.chipHeight)
            .then(if (selectsOnFocus) Modifier.onFocusChanged { if (it.isFocused) onSelect() } else Modifier)
            .then(if (stateText != null) Modifier.semantics { stateDescription = stateText } else Modifier),
        shape = CircleShape,
        color = Color.Transparent,
        role = Role.Tab,
        selected = selected,
        onLongClick = onLongClick,
        contentAlignment = Alignment.Center,
        grows = false,
    ) {
        val content = if (selected) LocalContentColor.current else LocalContentColor.current.copy(alpha = UNSELECTED_ALPHA)
        val style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal)
        val selectedStyle = style.copy(fontWeight = FontWeight.SemiBold)
        val measurer = rememberTextMeasurer()
        Row(
            Modifier.padding(horizontal = ChipInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (watched) Icon(TortugaIcons.Check, contentDescription = null, Modifier.size(18.dp), tint = content)
            Text(
                label,
                Modifier.widthIn(max = ChipMaxWidth).widestLook(measurer, label, style, 0, selectedStyle, 0),
                style = if (selected) selectedStyle else style,
                color = content,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How bright an unselected chip's name is: #EDEDED at 65 %, the selected name's 100 % and SemiBold about 2.4:1 against
 * it over black. Over the picture it reads at 4.5:1 or more because the backdrop is dimmed where the rows lie: the
 * wide page's series dim ([SERIES_DIM]), the TV page's bottom fade and its episodes dim ([TvBackdrop]).
 */
private const val UNSELECTED_ALPHA = 0.65f

/** How far a chip's focus fill reaches past its name; the first name lines up with what it sits over. */
private val ChipInset = 12.dp

/** The widest a chip's name (a season's, a range's, TV's cast names) gets before it is cut short. */
internal val ChipMaxWidth = 280.dp
