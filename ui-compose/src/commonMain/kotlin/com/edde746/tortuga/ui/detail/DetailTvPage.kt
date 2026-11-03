package com.edde746.tortuga.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEnterExitScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.Bitmap
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.pxOrElse
import coil3.transform.Transformation
import com.edde746.tortuga.presentation.detail.DetailAction
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.tvActions
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.app.SCREEN_FADE_IN_MILLIS
import com.edde746.tortuga.ui.components.DownloadBadge
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ItemMenu
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.fadeStops
import com.edde746.tortuga.ui.components.itemEntry
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.components.playKey
import com.edde746.tortuga.ui.components.shelfBottom
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.StayInViewSpec
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.focus.tvRow
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.image.toComposeBitmap
import com.edde746.tortuga.ui.navigation.clipStart
import com.edde746.tortuga.ui.navigation.extendStart
import com.edde746.tortuga.ui.navigation.navigationInset
import com.edde746.tortuga.ui.navigation.navigationOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import coil3.size.Size as ImageSize

/**
 * Android TV's Detail page, after Prime Video's: one page of sections over the title's colour
 * backdrop (its poster where it has none, as an anime entry's card while its title loads), top to bottom: the title
 * block (the logo, the metadata line and the ratings, three lines of description
 * ending in More, "With" and the first three cast names), the action row ([TvActionRow]), a series' season chips (a
 * long season's range chips under them, [SeasonChips]), the episode row with the focused episode's details above it
 * ([TvEpisodeDetails], [TvEpisodeRow]), More like this ([RelatedShelf]: the Board's shelf) and Cast & crew
 * ([TvCastRow]). Up and Down move between the sections, Left and Right within one ([tvRow]: past a row's end focus
 * stays, past its start it goes to the side navigation); each section takes focus at its own element (the button,
 * chip, episode, card or name it had last, else the primary action, Mark as watched, the selected season and range,
 * [DetailUiState.currentEpisode], the first card, the first name), never the one nearest the key's direction. The
 * title block's two focus stops, More and the user's rating, sit at the ends of lines of text, so where they lie moves
 * with the text's wrap; Up and Down reach them in a fixed order instead ([TvHero]). Up from the seasons, and from the
 * first section under the title block on a title without episodes, goes to the title block's last row: the player
 * app's answers where they show, else the actions; Up from More like this goes to the episode row on a series, as Up
 * from Cast & crew goes to More like this where it has cards. The page moves a section at a time ([TvSections]): the
 * title block's place while it or the action row has focus, the seasons at the top under the title's name while the
 * seasons or the episodes have it (the episode's details then open above the row and the backdrop dims), and down to
 * More like this and to Cast & crew where they don't show there. More opens the rest of the title's information in the
 * side panel ([TvMorePanel]), the user's rating at the ratings' end the rating picker in it (its stars focused; focus
 * back on the rating once it closes). Back leaves the page from anywhere in one press (the app's own Back).
 */
@Composable
internal fun TvDetailPage(state: DetailUiState, header: DetailHeader, focus: FocusMemory, intents: DetailIntents) {
    val dimens = LocalTortugaDimens.current
    val scope = rememberCoroutineScope()
    val row = rememberEpisodeRow(state.selectedSeason) { state.currentEpisodeIndex }
    // The action row, which Up from the rows under the title block enters as a move from below does ([tvRow]'s enter);
    // the episode row and More like this, which Up from the section under each enters the same way.
    val actions = remember { FocusRequester() }
    val episodeRow = remember { FocusRequester() }
    val relatedRow = remember { FocusRequester() }
    val toTitleBlock: FocusEnterExitScope.() -> Unit = { if (!focus.focusAnswer()) actions.requestFocus(FocusDirection.Up) }
    ShownEpisode(state.shownEpisode, state, intents) { index ->
        row.target = index
        row.list.centre(index)
    }
    // Where the page is: 0 the title block, then in order the episodes (a series), More like this and Cast & crew, each
    // where the page has it; animated between them.
    val page = remember { Animatable(0f) }
    var stop by remember { mutableIntStateOf(0) }
    LaunchedEffect(stop) { page.animateTo(stop.toFloat(), tween(PAGE_MILLIS, easing = FastOutSlowInEasing)) }
    val episodes = state.listsEpisodes
    val related = state.related
    // Up from Cast & crew goes to More like this where it has a card to take focus, else past it (still loading, or
    // nothing found) to the episode row or the title block.
    val relatedCards = related?.cards?.isNotEmpty() == true
    val cast = state.listsCast
    val relatedStop = if (episodes) 2 else 1
    val castStop = relatedStop + if (related != null) 1 else 0
    // Up into the section at the page's stop [at], by [enter] (true once focus is there): one the page has moved up past
    // isn't placed and can't take focus, so the page moves back to it first and focus follows once it is placed.
    fun upInto(at: Int, enter: () -> Boolean): FocusEnterExitScope.() -> Unit = {
        if (!enter()) {
            cancelFocusChange()
            stop = at
            scope.launch {
                repeat(FOCUS_FRAMES) {
                    withFrameNanos { }
                    if (enter()) return@launch
                }
            }
        }
    }
    // The episode the row's own entry lands on ([TvEpisodeRow]), the card More like this's entry does ([ShelfRow]).
    val toEpisodes = upInto(1) { state.episodes.getOrNull(row.target)?.let { focus.focus(episodeFocusKey(it.videoId)) } == true }
    val toRelated = upInto(relatedStop) { relatedRow.requestFocus(FocusDirection.Up) }
    var panel by remember { mutableStateOf(false) }
    // The episode's details, More like this and Cast & crew, out of sight below the title block as the page opens, are
    // built once it has faded in: a TV box building them during the fade drops its frames. Kept once built, so a page
    // returned to has them at once (focus may come back to one of them).
    var below by rememberSaveable { mutableStateOf(false) }
    if (!below) LaunchedEffect(Unit) {
        Animatable(0f).animateTo(1f, tween(SCREEN_FADE_IN_MILLIS, easing = LinearEasing))
        below = true
    }
    Box(Modifier.fillMaxSize()) {
        TvBackdrop(header.background ?: header.poster, dim = { page.value.coerceAtMost(1f) * EPISODES_DIM })
        TvSections(
            progress = { page.value },
            hasEpisodes = episodes,
            // Only once the page has moved off the title block, which shows the name itself.
            smallTitle = {
                if (stop != 0) Text(
                    header.title,
                    Modifier.padding(horizontal = dimens.screenPadding).widthIn(max = TvTextWidth),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold).contentDirection(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            hero = {
                TvHero(state, header, focus, intents, actions, onMore = { panel = true }, Modifier.onFocusChanged { if (it.hasFocus) stop = 0 })
            },
            seasons = {
                if (episodes) SeasonChips(
                    state,
                    focus,
                    intents,
                    selectedRange = state.rangeOf(row.target),
                    // As focus passes along the ranges: the row to the range's first episode, unless it is in the range.
                    onRange = { index ->
                        val range = state.episodeRanges[index]
                        if (row.target !in range.start until range.end) {
                            row.target = range.start
                            scope.launch { row.list.centre(range.start) }
                        }
                    },
                    nameStart = dimens.screenPadding,
                    Modifier.onFocusChanged { if (it.hasFocus) stop = 1 },
                    up = toTitleBlock,
                )
            },
            details = {
                if (below && episodes) state.episodes.getOrNull(row.target)?.let { TvEpisodeDetails(it) }
            },
            episodes = {
                if (episodes) TvEpisodeRow(state, focus, intents, row, scope, Modifier.focusRequester(episodeRow).onFocusChanged { if (it.hasFocus) stop = 1 })
            },
            related = {
                if (below && related != null) RelatedShelf(
                    related,
                    focus,
                    intents,
                    Modifier
                        .focusRequester(relatedRow)
                        .tvRow(up = if (episodes) toEpisodes else toTitleBlock)
                        .onFocusChanged { if (it.hasFocus) stop = relatedStop },
                )
            },
            cast = {
                if (below && cast) TvCastRow(
                    header,
                    focus,
                    intents,
                    up = when {
                        relatedCards -> toRelated
                        episodes -> toEpisodes
                        else -> toTitleBlock
                    },
                    Modifier.onFocusChanged { if (it.hasFocus) stop = castStop },
                )
            },
        )
    }
    if (panel) TvMorePanel(header, onDismiss = { panel = false }, onOpenLink = { panel = false; intents.openLink(it) })
}

/** How long the page takes to move to another section. */
private const val PAGE_MILLIS = 320

/**
 * How much darker the backdrop gets once the page has moved down to the episodes: the picture at 30 %, as Home's
 * backdrop, so the season names across its top read at 4.5:1 or more over its bright side ([SeasonChips]).
 */
private const val EPISODES_DIM = 0.7f

/** The title block's text column: the description, the metadata line and the episode's details are at most this wide. */
private val TvTextWidth = 560.dp

/**
 * The logo's largest size: the title block stays short enough for the seasons, and the episode cards where they fit
 * whole, to show under it.
 */
private val TvLogoBox = DpSize(360.dp, 96.dp)

/** Above the title block. */
private val HeroTop = 32.dp

/** The title's name at the top once the page moves off the title block, and the gap under it to the next section. */
private val SmallTitleTop = 20.dp
private val SmallTitleGap = 12.dp

/** The gaps between the sections. */
private val HeroGap = 8.dp
private val SeasonsGap = 16.dp
private val DetailsGap = 12.dp
private val CastGap = 20.dp
private val PageBottom = 32.dp

/** An episode card's width (16:9) and the gap between the cards. */
private val CardWidth = 192.dp
private val CardGap = 16.dp

/** A section's place on the page ([TvSections]). */
private enum class Section { SMALL_TITLE, HERO, SEASONS, DETAILS, EPISODES, RELATED, CAST }

/**
 * The page's sections laid out down the screen and moved as one by [progress] (0 the title block; then in order the
 * episodes, More like this ([related]) and Cast & crew, each where the page has it), read only while placing, so a move
 * re-places the sections without composing or measuring them again. With episodes ([hasEpisodes]) the episode's
 * [details] open between the seasons and the row as the page moves to them (the row moving down as they fade in). Once
 * the page moves off the title block, the title block fades out while [smallTitle] (the name, one line) fades in at the
 * top. Each stop puts its section [SmallTitleGap] under the name's line (at the system's font size), as far as the
 * page's end allows. At a stop a section shows only where it rests whole on screen, under the name's line and above the
 * screen's bottom edge (the episode row and More like this, away from their own stops, down to their cards' bottom: the
 * room for a focused card's growth under them is empty there), and
 * fades in and out between stops; one the page moves up to the name's line fades out on the way and is then not placed
 * (nothing shows under the name), and one resting past the bottom edge is placed below it, unseen, so a key press
 * still reaches it and the page moves to it.
 */
@Composable
private fun TvSections(
    progress: () -> Float,
    hasEpisodes: Boolean,
    smallTitle: @Composable () -> Unit,
    hero: @Composable () -> Unit,
    seasons: @Composable () -> Unit,
    details: @Composable () -> Unit,
    episodes: @Composable () -> Unit,
    related: @Composable () -> Unit,
    cast: @Composable () -> Unit,
) {
    val titleLine = with(LocalDensity.current) { MaterialTheme.typography.titleMedium.lineHeight.roundToPx() }
    val shelfBottom = LocalTortugaDimens.current.shelfBottom
    Layout(
        contents = listOf(smallTitle, hero, seasons, details, episodes, related, cast),
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val free = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { slot -> slot.firstOrNull()?.measure(free) }
        fun height(section: Section) = placeables[section.ordinal]?.height ?: 0
        val heroHeight = height(Section.HERO)
        val seasonsTop = heroHeight + HeroGap.roundToPx()
        val seasonsEnd = seasonsTop + height(Section.SEASONS)
        val detailsTop = seasonsEnd + SeasonsGap.roundToPx()
        val detailsRoom = if (placeables[Section.DETAILS.ordinal] != null) height(Section.DETAILS) + DetailsGap.roundToPx() else 0
        val episodes = placeables[Section.EPISODES.ordinal] != null
        val related = placeables[Section.RELATED.ordinal] != null
        val cast = placeables[Section.CAST.ordinal] != null
        // The row and what follows it, the details open by [open] (0 to 1).
        fun rowTop(open: Float) = detailsTop + (detailsRoom * open).roundToInt()
        // The end of the episode row, else of the title block: where the next section follows.
        fun aboveEnd(open: Float) = if (episodes) rowTop(open) + height(Section.EPISODES) else heroHeight
        fun relatedTop(open: Float) = aboveEnd(open) + CastGap.roundToPx()
        fun castTop(open: Float) = if (related) relatedTop(open) + height(Section.RELATED) + CastGap.roundToPx() else aboveEnd(open) + CastGap.roundToPx()
        val end = when {
            cast -> castTop(1f) + height(Section.CAST)
            related -> relatedTop(1f) + height(Section.RELATED)
            episodes -> rowTop(1f) + height(Section.EPISODES)
            else -> heroHeight
        } + PageBottom.roundToPx()
        val furthest = (end - constraints.maxHeight).coerceAtLeast(0)
        val titleTop = SmallTitleTop.roundToPx()
        val titleBottom = titleTop + max(titleLine, height(Section.SMALL_TITLE))
        val headroom = titleBottom + SmallTitleGap.roundToPx()
        // Each stop's offset, in order: the title block (whole where it fits, else moved up until its end, the actions,
        // shows: the largest text), the episodes, More like this, Cast & crew.
        val stops = buildList {
            add((heroHeight + PageBottom.roundToPx() - constraints.maxHeight).coerceIn(0, furthest))
            if (episodes) add((seasonsTop - headroom).coerceIn(last(), furthest.coerceAtLeast(last())))
            if (related) add((relatedTop(1f) - headroom).coerceIn(last(), furthest.coerceAtLeast(last())))
            if (cast) add((castTop(1f) - headroom).coerceIn(last(), furthest.coerceAtLeast(last())))
        }
        val episodesStop = 1
        val relatedStop = if (episodes) 2 else 1
        layout(constraints.maxWidth, constraints.maxHeight) {
            val at = progress().coerceIn(0f, (stops.size - 1).toFloat())
            val from = at.toInt().coerceAtMost(stops.size - 1)
            val to = (from + 1).coerceAtMost(stops.size - 1)
            val offset = (stops[from] + (stops[to] - stops[from]) * (at - from)).roundToInt()
            val open = if (hasEpisodes) at.coerceAtMost(1f) else 0f
            // The name shows as the page moves off the title block: as the episodes open, else (a title without them)
            // as it moves to Cast & crew, where it moves at all.
            val named = when {
                hasEpisodes -> open
                stops.last() > stops.first() -> at.coerceAtMost(1f)
                else -> 0f
            }
            // The stops at which the name shows: every one after the title block's, where the page moves at all.
            val nameShows = hasEpisodes || stops.last() > stops.first()
            // Whether a section [size] tall from [top] (on the page, the details open by the argument) rests whole on
            // screen at stop [index]: under the name's line where the name shows, above the screen's bottom edge.
            fun rests(index: Int, size: Int, top: (Float) -> Int): Boolean {
                val y = top(if (hasEpisodes && index > 0) 1f else 0f) - stops[index]
                return (index == 0 || !nameShows || y >= headroom) && y + size <= constraints.maxHeight
            }
            // How far a section at [y] on screen is hidden under the name: none at its stop's line, all at the name's.
            fun under(y: Int) = named * ((headroom - y).toFloat() / (headroom - titleBottom)).coerceIn(0f, 1f)
            // [empty]: the room at the section's bottom that stays empty while focus is elsewhere (a row's under its
            // cards, for a focused card's growth), so it may lie past the bottom edge at the stops other than [own].
            fun Placeable.placeSection(top: (Float) -> Int, shown: Float = 1f, empty: Int = 0, own: Int = -1) {
                val y = top(open) - offset
                fun whole(index: Int) = rests(index, height - if (index == own) 0 else empty, top)
                val restsFrom = if (whole(from)) 1f else 0f
                val resting = restsFrom + ((if (whole(to)) 1f else 0f) - restsFrom) * (at - from)
                val hidden = under(y)
                when {
                    hidden >= 1f -> Unit
                    resting > 0f -> placeWithLayer(0, y) { alpha = shown * resting * (1f - hidden) }
                    y + height > constraints.maxHeight -> place(0, max(y, constraints.maxHeight))
                }
            }
            placeables[Section.SMALL_TITLE.ordinal]?.placeWithLayer(0, titleTop) { alpha = named }
            placeables[Section.HERO.ordinal]?.placeWithLayer(0, -offset) { alpha = 1f - named }
            placeables[Section.SEASONS.ordinal]?.placeSection({ seasonsTop })
            placeables[Section.DETAILS.ordinal]?.placeSection({ detailsTop }, open)
            placeables[Section.EPISODES.ordinal]?.placeSection(::rowTop, empty = CardGrowthRoom.roundToPx(), own = episodesStop)
            placeables[Section.RELATED.ordinal]?.placeSection(::relatedTop, empty = shelfBottom.roundToPx(), own = relatedStop)
            placeables[Section.CAST.ordinal]?.placeSection(::castTop)
        }
    }
}

/**
 * The title block and the action row: the logo (else the name) in [TvLogoBox], the metadata line with the ratings after
 * it (plain, taking no focus; the user's own score at its end once there is one, [UserRatingItem]), the description's
 * first three lines with More at the end of the last ([TvDescription]), "With" and the first three cast names, then the
 * actions. A preview ([DetailUiState.preview]) is the title and the metadata line alone. The title's playback in a
 * player app and the saved title's note follow the actions, as on every page. Where the text wraps decides where More
 * lies (two lines of description put it right of Play's column), so Up and Down go in one order whatever the layout:
 * Up from any action to More; Up from More keeps focus there, as Up from the actions does without More (the side
 * navigation is Left's). The player app's answers come by Down from the actions and take it on Mark as watched (else
 * Not yet); Up from them goes back to the actions ([actions], entered as a move from below enters them), whatever the
 * buttons' and labels' widths.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvHero(state: DetailUiState, header: DetailHeader, focus: FocusMemory, intents: DetailIntents, actions: FocusRequester, onMore: () -> Unit, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    Column(modifier.fillMaxWidth().padding(top = HeroTop), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = dimens.screenPadding).widthIn(max = TvTextWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TitleArt(header, centred = false, box = TvLogoBox, onNameLift = {})
            val metrics = ratingMetrics(tv = true)
            val userRating = state.userRating
            if (state.showsMetaLine) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                    verticalArrangement = Arrangement.spacedBy(metrics.lineGap),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    header.infoLine?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TortugaColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.layoutProbe("detail.infoLine"),
                        )
                    }
                    for (rating in header.ratings) RatingItem(rating, metrics, Modifier.layoutProbe("detail.rating.${rating.source.key}"))
                    if (userRating != null) UserRatingItem(userRating, metrics)
                }
            }
            if (state.preview) {
                // What a long wait waits for, in the actions' place.
                state.loadingNote?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted, modifier = Modifier.layoutProbe("detail.loadingNote"))
                }
                return@Column
            }
            if (header.hasMore) TvDescription(header.overview, focus, onMore)
            header.castLine?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.layoutProbe("detail.castLine"))
            }
        }
        if (state.preview) return@Column
        TvActionRow(state, focus, intents, Modifier.padding(top = 8.dp).focusRequester(actions))
        if (state.playerApp != null || state.offlineNote != null) {
            Column(Modifier.padding(horizontal = dimens.screenPadding).widthIn(max = TvTextWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.playerApp?.let {
                    Box(Modifier.tvRow(up = { actions.requestFocus(FocusDirection.Up) }, enter = { focus.focusAnswer() })) { PlayerAppLine(it, focus, intents) }
                }
                state.offlineNote?.let { OfflineNote(it) }
            }
        }
    }
}

/**
 * The description's first three lines with More (`detail.more`, opening the side panel) after the end of the last, a
 * small pill centred on that line ([MorePadding] taller than it); a last line too long to leave it room is laid out
 * narrower, so More always ends it. More alone where the addon gives no description.
 */
@Composable
private fun TvDescription(text: String?, focus: FocusMemory, onMore: () -> Unit) {
    val more: @Composable () -> Unit = {
        // Inline in the description's last line: its words in the description's size, the pill no taller than the line
        // and a little padding. Focus fills it, as the chips (no growth, so it never rises over the line above).
        TortugaSurface(
            onClick = onMore,
            modifier = Modifier
                .tvRow(toNavigation = false, up = { cancelFocusChange() })
                .rememberFocus(focus, FOCUS_MORE)
                .layoutProbe("detail.more"),
            shape = CircleShape,
            color = TortugaColors.PictureButton,
            contentAlignment = Alignment.Center,
            grows = false,
        ) {
            Text(
                T.detail.showMore,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = MorePadding),
            )
        }
    }
    if (text == null) {
        more()
        return
    }
    val style = MaterialTheme.typography.bodyMedium.contentDirection()
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val gap = MoreGap.roundToPx()
        val button = subcompose("more", more).single().measure(Constraints())
        var lines: TextLayoutResult? = null
        fun description(slot: String, width: Int) = subcompose(slot) {
            Text(text, style = style, color = TortugaColors.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis, onTextLayout = { lines = it })
        }.single().measure(Constraints(maxWidth = width.coerceAtLeast(0)))
        fun lastLineEnd(layout: TextLayoutResult): Float = with(layout) {
            if (layoutDirection == LayoutDirection.Ltr) getLineRight(lineCount - 1) else size.width - getLineLeft(lineCount - 1)
        }
        var placed = description("wide", constraints.maxWidth)
        if (lastLineEnd(lines!!) + gap + button.width > constraints.maxWidth) placed = description("narrow", constraints.maxWidth - button.width - gap)
        val layout = lines!!
        val lastLine = layout.lineCount - 1
        val buttonX = lastLineEnd(layout).roundToInt() + gap
        val lineMiddle = ((layout.getLineTop(lastLine) + layout.getLineBottom(lastLine)) / 2).roundToInt()
        val buttonY = lineMiddle - button.height / 2
        val top = (-buttonY).coerceAtLeast(0)
        val height = maxOf(placed.height, buttonY + button.height) + top
        layout(constraints.maxWidth, height) {
            placed.placeRelative(0, top)
            button.placeRelative(buttonX, buttonY + top)
        }
    }
}

/** Between the description's last word and More. */
private val MoreGap = 12.dp

/** Above and below More's words: its pill stands this far past the description's line. */
private val MorePadding = 1.dp

/**
 * The actions across the page ([tvActions], the order every TV follows; nothing behind ⋯) inside the screen's padding:
 * Play, main's button ([PlayButton]: "S2E5 · 32:44", "S1E1", "Play", or "No streams found" once every stream addon
 * answered none, [DetailPrimaryAction.noStreams], where pressing it still opens the stream list) in the room the round
 * buttons after it leave, then round buttons over the backdrop (the trackers' watchlists, [DetailAction.WATCHLIST],
 * `detail.watchlist`, the user's rating, [DetailAction.RATE], `detail.rate`, and Block on this profile last,
 * [DetailAction.KIDS_BLOCK], `detail.kidsBlockAction`, where the title has them),
 * which rest translucent and take the primary fill with focus. Under the row, one line drawn past the buttons without
 * moving them: Play's episode's title ("We Gotta Go Now")
 * from Play's start edge, as main's page has it, or while a round button has focus that button's action, centred
 * under it where that keeps it in the row's lane (from the row's start to its end or the text column's,
 * [TvTextWidth], whichever is further), else moved along until it is. Either is cut short with an ellipsis past the
 * lane's width. The row keeps room for the line at any font size. A move into the row from above or below lands on the
 * button that had focus in it last, else Play, else the library's; Up from any of them goes to More ([TvHero]), and
 * Down to the player app's answers where they show.
 */
@Composable
private fun TvActionRow(state: DetailUiState, focus: FocusMemory, intents: DetailIntents, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    // The focused button's key, null while focus is elsewhere. Its place is looked up in the buttons shown now: a button
    // arriving before it (Play, after the library's answer) moves it along.
    var labelled by remember { mutableStateOf<String?>(null) }
    val home = if (state.primaryAction != null) FOCUS_PLAY else FOCUS_LIBRARY
    val buttons = buildList {
        state.primaryAction?.let { action ->
            add(
                TvAction(FOCUS_PLAY, label = null) {
                    PlayButton(action, focus, intents.play, PlayMinWidth, label = action.tvLabel, spokenLabel = action.tvSpokenLabel)
                },
            )
        }
        for (action in state.tvActions) {
            when (action) {
                DetailAction.CHOOSE_STREAM -> add(
                    TvAction(FOCUS_CHOOSE_STREAM, state.chooseStreamText) {
                        TortugaIconButton(
                            TortugaIcons.StreamList,
                            checkNotNull(state.chooseStreamText),
                            intents.chooseStream,
                            Modifier.rememberFocus(focus, FOCUS_CHOOSE_STREAM).layoutProbe("detail.chooseStream"),
                            style = IconButtonStyle.OVER_PICTURE,
                        )
                    },
                )
                DetailAction.TRAILER -> add(TvAction(FOCUS_TRAILER, state.trailerActionText) { TrailerButton(state, focus, intents) })
                DetailAction.LIBRARY -> add(
                    TvAction(FOCUS_LIBRARY, state.libraryActionText) {
                        TortugaIconButton(
                            if (state.inLibrary) TortugaIcons.Check else TortugaIcons.Add,
                            state.libraryActionText,
                            intents.toggleLibrary,
                            Modifier.rememberFocus(focus, FOCUS_LIBRARY).layoutProbe("detail.library"),
                            style = IconButtonStyle.OVER_PICTURE,
                            selected = state.inLibrary,
                        )
                    },
                )
                DetailAction.WATCHED -> add(
                    TvAction(FOCUS_WATCHED, state.watchedActionText) {
                        TortugaIconButton(
                            if (state.watched) TortugaIcons.Visibility else TortugaIcons.VisibilityOutlined,
                            state.watchedActionText,
                            { intents.markWatched(!state.watched) },
                            Modifier.rememberFocus(focus, FOCUS_WATCHED).layoutProbe("detail.watched"),
                            style = IconButtonStyle.OVER_PICTURE,
                            selected = state.watched,
                        )
                    },
                )
                DetailAction.DOWNLOAD -> {
                    val download = checkNotNull(state.download)
                    add(TvAction(FOCUS_DOWNLOAD, download.label) { DownloadButton(download, focus, intents) })
                }
                // TVs offer no Share (tvActions).
                DetailAction.SHARE -> Unit
                DetailAction.WATCHLIST -> {
                    val watchlist = checkNotNull(state.watchlist)
                    add(TvAction(FOCUS_WATCHLIST, watchlist.label) { WatchlistButton(watchlist, focus, intents) })
                }
                DetailAction.RATE -> {
                    val rating = checkNotNull(state.userRating)
                    add(TvAction(FOCUS_RATE, rating.label) { RateButton(rating, focus, intents) })
                }
                DetailAction.KIDS_BLOCK -> {
                    val label = checkNotNull(state.kidsBlockAction)
                    add(
                        TvAction(FOCUS_KIDS_BLOCK, label) {
                            TortugaIconButton(
                                TortugaIcons.Block,
                                label,
                                intents.blockOnProfile,
                                Modifier.rememberFocus(focus, FOCUS_KIDS_BLOCK).layoutProbe("detail.kidsBlockAction"),
                                style = IconButtonStyle.OVER_PICTURE,
                            )
                        },
                    )
                }
            }
        }
    }
    val labelStyle = MaterialTheme.typography.labelMedium
    val lineStyle = MaterialTheme.typography.bodySmall
    val labelledIndex = buttons.indexOfFirst { it.key == labelled }
    val focusedLabel = buttons.getOrNull(labelledIndex)?.label
    val episodeTitle = state.primaryAction?.episodeTitle
    val labelRoom = with(LocalDensity.current) {
        max(ActionLabelRoom.roundToPx(), ActionLabelGap.roundToPx() + max(labelStyle.lineHeight.roundToPx(), lineStyle.lineHeight.roundToPx()))
    }
    Layout(
        content = {
            // Keyed: a button arriving before the others (Play, with the library's answer) leaves theirs, and their
            // focus, in place.
            buttons.forEachIndexed { index, button ->
                key(button.key) {
                    Box(
                        Modifier.onFocusChanged {
                            if (it.hasFocus) {
                                last = button.key
                                labelled = button.key
                            } else if (labelled == button.key) {
                                labelled = null
                            }
                        },
                    ) { button.content() }
                }
            }
            when {
                focusedLabel != null -> Text(focusedLabel, style = labelStyle, color = TortugaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                episodeTitle != null -> Text(episodeTitle, style = lineStyle.contentDirection(), color = TortugaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = modifier
            .padding(horizontal = dimens.screenPadding)
            .tvRow(up = { if (!focus.focus(FOCUS_MORE)) cancelFocusChange() }, down = { focus.focusAnswer() }) {
                val key = last ?: home
                if (!focus.focus(key)) focus.focus(home)
            },
    ) { measurables, constraints ->
        val gap = TvActionGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // The round buttons at their own size, then the primary action (first) in the room they leave.
        val primary = if (state.primaryAction != null) 1 else 0
        val placed = arrayOfNulls<Placeable>(buttons.size)
        var used = gap * (buttons.size - 1).coerceAtLeast(0)
        for (index in primary until buttons.size) {
            placed[index] = measurables[index].measure(loose).also { used += it.width }
        }
        if (primary == 1) placed[0] = measurables[0].measure(loose.copy(maxWidth = (constraints.maxWidth - used).coerceAtLeast(0)))
        val row = placed.requireNoNulls()
        val width = (row.sumOf { it.width } + gap * (row.size - 1).coerceAtLeast(0)).coerceAtMost(constraints.maxWidth)
        val height = row.maxOfOrNull { it.height } ?: 0
        val lane = minOf(constraints.maxWidth, max(width, TvTextWidth.roundToPx()))
        val label = measurables.getOrNull(buttons.size)?.measure(Constraints(maxWidth = lane))
        layout(width, height + labelRoom) {
            var x = 0
            row.forEachIndexed { index, button ->
                button.placeRelative(x, (height - button.height) / 2)
                if (index == labelledIndex && focusedLabel != null && label != null) {
                    label.placeRelative((x + (button.width - label.width) / 2).coerceIn(0, lane - label.width), height + ActionLabelGap.roundToPx())
                }
                x += button.width + gap
            }
            // Play's episode's title, from Play's start edge.
            if (focusedLabel == null) label?.placeRelative(0, height + ActionLabelGap.roundToPx())
        }
    }
}

/** A button of [TvActionRow]: its focus key, the line under the row while it has focus (none for Play), the button. */
private class TvAction(val key: String, val label: String?, val content: @Composable () -> Unit)

/** Between the action row's buttons. */
private val TvActionGap = 12.dp

/** Under the action row: room for its line (more where the system's font size takes it), the line 8 dp under it. */
private val ActionLabelRoom = 26.dp
private val ActionLabelGap = 8.dp

/** The episode row's place: its list, and the episode a D-pad's move into it lands on. */
@Stable
private class EpisodeRow(val list: LazyListState, saved: MutableIntState) {
    /** The episode a move into the row lands on: the current one at first, then the last focused or a range's first. */
    var target by saved
}

/**
 * The selected season's [EpisodeRow], kept per season across the player and back; [currentIndex] (a search of a long
 * season's episodes) is read only as a season's row is first made.
 */
@Composable
private fun rememberEpisodeRow(season: Int?, currentIndex: () -> Int): EpisodeRow {
    val list = rememberSaveable(season, saver = LazyListState.Saver) { LazyListState() }
    val target = rememberSaveable(season) { mutableIntStateOf(currentIndex()) }
    return remember(list, target) { EpisodeRow(list, target) }
}

/**
 * The episode the row's focus is on (else the one a move into it would land on), large above the row: "5. We Gotta Go
 * Now", its date, runtime and time left, its download's state, three lines of its description. Always as tall as three
 * lines of description, so the row under it holds still as focus moves along it. Harness name: `detail.episodeDetails`.
 */
@Composable
private fun TvEpisodeDetails(episode: DetailEpisode) {
    val dimens = LocalTortugaDimens.current
    Column(
        Modifier.padding(horizontal = dimens.screenPadding).widthIn(max = TvTextWidth).layoutProbe("detail.episodeDetails"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            episode.numberedTitle,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                episode.detailLine.orEmpty(),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = TortugaColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.download?.let { download -> download.label?.let { DownloadBadge(download.state, it, download.progress?.toFloat()) } }
        }
        EpisodeOverview(
            episode.overview.orEmpty(),
            episode.hiddenOverviewLabel,
            style = MaterialTheme.typography.bodyMedium.contentDirection(),
            color = { TortugaColors.TextMuted },
            maxLines = 3,
            minLines = 3,
        )
    }
}

/**
 * The selected season's episodes as a row of 16:9 cards ([TvEpisodeCard]), the focused one kept in the middle of the
 * row ([CentredInView]; the row's ends stop it). A move into the row with a D-pad lands on its [EpisodeRow.target]
 * ([DetailUiState.currentEpisode] at first), brought to the middle first when it isn't composed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvEpisodeRow(state: DetailUiState, focus: FocusMemory, intents: DetailIntents, row: EpisodeRow, scope: CoroutineScope, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides CentredInView) {
        LazyRow(
            state = row.list,
            modifier = modifier
                .fillMaxWidth()
                .clipStart(navigationOverlap)
                .tvRow {
                    val target = row.target
                    val key = state.episodes.getOrNull(target)?.let { episodeFocusKey(it.videoId) }
                    if (key != null && !focus.focus(key)) {
                        // Off screen, so not composed: brought to the middle, then focused.
                        cancelFocusChange()
                        scope.launch {
                            row.list.centre(target)
                            repeat(FOCUS_FRAMES) {
                                withFrameNanos { }
                                if (focus.focus(key)) return@launch
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = CardGrowthRoom),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            itemsIndexed(state.episodes, key = { _, episode -> episode.videoId }) { index, episode ->
                TvEpisodeCard(episode, index, focus, intents, onFocused = { row.target = index })
            }
        }
    }
}

/** Above and below the cards: room for a focused card's growth. */
private val CardGrowthRoom = 6.dp

/**
 * An episode's card: its 16:9 thumbnail ([EpisodeThumbnail]: progress, a check once watched) with its code at the
 * bottom start, dimmed until it is out, in the corners of Home's posters; screen readers name it by its numbered title,
 * its state the progress and "Watched" ([DetailEpisode.stateText]).
 * Focus looks as on Home's and Discover's posters ([focusRing]: the ring on the card's edge, and on TV the growth).
 * Select and the remote's Play/Pause ([playKey]) play it (auto-play's pick, else its streams); a held Select or the
 * Menu key opens its media menu ([DetailEpisode.menu]). Harness name: `detail.episode.<i>`.
 */
@Composable
private fun TvEpisodeCard(episode: DetailEpisode, index: Int, focus: FocusMemory, intents: DetailIntents, onFocused: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val cardFocus = remember { FocusRequester() }
    val corner = MaterialTheme.shapes.medium
    Box(Modifier.width(CardWidth)) {
        Box(
            Modifier
                .fillMaxWidth()
                .rememberFocus(focus, episodeFocusKey(episode.videoId))
                .onFocusChanged { if (it.isFocused) onFocused() }
                .focusRequester(cardFocus)
                .menuGestures { menu = true }
                .playKey { intents.openEpisode(episode.videoId) }
                .layoutProbe("detail.episode.$index")
                .focusRing(corner)
                .clip(corner)
                .semantics {
                    contentDescription = episode.numberedTitle
                    episode.stateText?.let { stateDescription = it }
                }
                .overlayOpener()
                .combinedClickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                    onLongClick = { menu = true },
                    onClick = {
                        cardFocus.requestFocus()
                        intents.openEpisode(episode.videoId)
                    },
                ),
        ) {
            EpisodeThumbnail(
                episode.thumbnail,
                episode.blurred,
                Modifier.fillMaxWidth().alpha(if (episode.upcoming) 0.6f else 1f),
                watched = episode.watched,
                progress = episode.progress?.toFloat(),
            )
            episode.code?.let { code ->
                Text(
                    code,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = TortugaColors.OnVideo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, CodeScrim)))
                        .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 10.dp),
                )
            }
        }
        val entries = remember(episode.menu, intents) {
            episode.menu.map { entry -> entry.itemEntry { action -> intents.episodeMenu(episode.videoId, action) } }
        }
        ItemMenu(expanded = menu, onDismiss = { menu = false }, entries = entries, probe = MENU_PROBE, title = episode.numberedTitle, media = true)
    }
}

/** Under an episode card's code: enough black that the white code reads over a white thumbnail. */
private val CodeScrim = Color.Black.copy(alpha = 0.7f)

/**
 * Cast & crew: the cast, the directors and the writers, each name once ([DetailHeader.castAndCrew]), as a row of
 * names over the backdrop, each opening Search for it. They rest translucent and take the primary fill with focus,
 * without growth, as the season chips do. A move into the row lands on the name that had focus last, else the first;
 * Up leaves it as [up] puts focus (the title block's last row, on a title without episodes), else for the row above.
 * Harness names: `detail.cast.<i>`.
 */
@Composable
private fun TvCastRow(header: DetailHeader, focus: FocusMemory, intents: DetailIntents, up: (FocusEnterExitScope.() -> Unit)?, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    val names = header.castAndCrew
    var last by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            T.detail.castAndCrew,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = dimens.screenPadding).widthIn(max = TvTextWidth),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .clipStart(navigationOverlap)
                .tvRow(up = up) {
                    val key = names.getOrNull(last)?.let { "cast:${it.id}" }
                    if (key == null || !focus.focus(key)) focus.focus("cast:${names.first().id}")
                },
            contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = CardGrowthRoom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(names, key = { _, link -> link.id }) { index, link ->
                TortugaSurface(
                    onClick = { intents.openLink(link.id) },
                    modifier = Modifier
                        .heightIn(min = dimens.chipHeight)
                        .rememberFocus(focus, "cast:${link.id}")
                        .onFocusChanged { if (it.isFocused) last = index }
                        .layoutProbe("detail.cast.$index"),
                    shape = CircleShape,
                    color = TortugaColors.PictureButton,
                    contentAlignment = Alignment.Center,
                    grows = false,
                ) {
                    Text(
                        link.label,
                        style = MaterialTheme.typography.labelLarge.contentDirection(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = ChipMaxWidth).padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The rest of the title's information in the side panel (`detail.morePanel`), opened by More: the whole description,
 * then the information block ([Information]: the facts, the genres, cast, directors and writers as links). The panel
 * scrolls its content; focus starts on its first link without moving the description out of view ([StayInViewSpec]).
 * Opening a link closes the panel ([onOpenLink]); Back closes it, focus going back to More.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvMorePanel(header: DetailHeader, onDismiss: () -> Unit, onOpenLink: (String) -> Unit) {
    TortugaSheet(onDismiss = onDismiss, title = header.title, probe = "detail.morePanel", contentTitle = true) {
        val panelFocus = rememberFocusMemory()
        val platformScroll = LocalBringIntoViewSpec.current
        val stayInView = remember(platformScroll) { StayInViewSpec(platformScroll) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides stayInView) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = LocalTortugaDimens.current.sectionSpacing / 2),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                header.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium.contentDirection(), color = TortugaColors.TextMuted) }
                Information(header, panelFocus, onOpenLink)
            }
        }
    }
}

/**
 * The title's backdrop behind the whole page, in colour, under the side navigation to the window's edge: baked with
 * its scrims into one opaque image at half the screen's resolution ([TvBackdropArtwork]), so the page moving over it
 * costs a low-end TV's GPU one image a frame. [dim] (0 to 1) darkens it as the page moves down to the episodes.
 * Harness name: `detail.backdrop`.
 */
@Composable
private fun TvBackdrop(url: String?, dim: () -> Float) {
    val context = LocalPlatformContext.current
    val background = TortugaColors.Background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dimens = LocalTortugaDimens.current
    val inset = navigationInset
    val density = LocalDensity.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.extendStart(inset).fillMaxSize().onSizeChanged { area = it }.layoutProbe("detail.backdrop")) {
        if (url != null && area != IntSize.Zero) {
            // Where the text column ends, as a share of the backdrop's width from its start edge.
            val textEnd = with(density) { (inset + dimens.screenPadding + TvTextWidth).toPx() } / area.width
            val request = remember(url, area, textEnd, rtl, background) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(area.width / BACKDROP_SCALE, area.height / BACKDROP_SCALE)
                    .transformations(TvBackdropArtwork(background, textEnd.coerceIn(0.1f, 0.9f), rtl))
                    .build()
            }
            AsyncImage(
                request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - dim()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

/** The backdrop's resolution: a half, under its scrims. */
private const val BACKDROP_SCALE = 2

/**
 * The backdrop as one opaque image of the requested size: the artwork in colour over [background], cropped to fill it
 * from its top end (left in [rtl]); over it black at [EDGE_SCRIM] at the start edge (where the side navigation's dim
 * ends) easing to [TEXT_SCRIM] where the text column ends ([textEnd], a share of the width), then to [FAR_SCRIM] at the
 * far edge, and from [FADE_TOP] of the height down to black at the bottom; every fade eased ([fadeStops]).
 */
private class TvBackdropArtwork(private val background: Color, private val textEnd: Float, private val rtl: Boolean) : Transformation() {
    override val cacheKey = "detailTvBackdrop(${background.toArgb()},$textEnd,$rtl)"

    override suspend fun transform(input: Bitmap, size: ImageSize): Bitmap {
        val width = size.width.pxOrElse { input.width }
        val height = size.height.pxOrElse { input.height }
        val artwork = input.toComposeBitmap()
        val baked = ImageBitmap(width, height, hasAlpha = false)
        val area = Size(width.toFloat(), height.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(baked), area) {
            drawRect(background)
            val scale = max(area.width / artwork.width, area.height / artwork.height)
            val drawn = IntSize((artwork.width * scale).roundToInt(), (artwork.height * scale).roundToInt())
            drawImage(artwork, dstOffset = IntOffset(if (rtl) 0 else width - drawn.width, 0), dstSize = drawn)
            val across = fadeStops(background, 0f, textEnd, from = EDGE_SCRIM, to = TEXT_SCRIM) +
                fadeStops(background, textEnd, 1f, from = TEXT_SCRIM, to = FAR_SCRIM)
            val brush = if (rtl) Brush.horizontalGradient(*across, startX = area.width, endX = 0f) else Brush.horizontalGradient(*across, startX = 0f, endX = area.width)
            drawRect(brush)
            val fadeTop = area.height * FADE_TOP
            drawRect(
                Brush.verticalGradient(*fadeStops(background, 1f, 0f, from = 1f), startY = fadeTop, endY = area.height),
                topLeft = Offset(0f, fadeTop),
                size = Size(area.width, area.height - fadeTop),
            )
        }
        return baked.toCoilBitmap()
    }
}

/** The backdrop's scrim across the page: at the start edge, where the text column ends, and at the far edge. */
private const val EDGE_SCRIM = 0.95f
private const val TEXT_SCRIM = 0.8f
private const val FAR_SCRIM = 0.1f

/**
 * Where the backdrop's fade to black at the bottom starts, a share of the height (Home's backdrop's): on the title
 * block's stop the season chips' row under the action row lies far enough into it that its names read at 4.5:1 or more
 * over the picture's bright side ([SeasonChips]).
 */
private const val FADE_TOP = 0.3f
