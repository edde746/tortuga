package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.detail.DetailAction
import com.edde746.tortuga.presentation.detail.DetailActionFit
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.detail.DetailEpisode
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailRating
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.presentation.detail.fitActions
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.adaptive.largeFontScale
import com.edde746.tortuga.ui.components.DownloadBadge
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ItemMenu
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.LocalPosterTemplate
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.aspectRatio
import com.edde746.tortuga.ui.components.eased
import com.edde746.tortuga.ui.components.fadeStops
import com.edde746.tortuga.ui.components.icon
import com.edde746.tortuga.ui.components.itemEntry
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.focus.DirectionalMoves
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.StayInViewSpec
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.image.TrimToArt
import com.edde746.tortuga.ui.navigation.NavigationBackdrop
import com.edde746.tortuga.ui.navigation.extendStart
import com.edde746.tortuga.ui.navigation.navigationInset
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Room above the details for the back button laid over the page. */
private val BackButtonSpace = 68.dp

/** Windows at least this wide put the details and the episodes side by side over a full-screen backdrop. */
internal val WideLayoutWidth = 840.dp

/** The details' column on wide layouts is at most this wide. */
private val InfoMaxWidth = 560.dp

/** The episodes' column on wide windows is at most this wide (longer rows read badly). */
private val EpisodesMaxWidth = 960.dp

/** Play/Resume is at least this wide in the centred action row of narrow windows. */
private val CentredPlayMinWidth = 140.dp

/** The title's box on narrow windows (a logo centred in it, at most 80 % of the page's width): its height. */
private val NarrowLogoHeight = 140.dp

/** Between the narrow page's logo and each line under it. */
private val NarrowHeadlineGap = 14.dp

/** The narrow page's picture reaches this far under the metadata line's top, behind the line. */
private val PictureUnderText = 34.dp

/** The logo's largest height in the details' column of wide windows. */
private val WideLogoHeight = 180.dp

/**
 * The height of a clear logo from fanart.tv (Cinemeta's: an 800 × 310 canvas) drawn whole, aspect-fit, in [limit]: the
 * box main's layouts gave such a logo.
 */
private fun clearLogoHeight(limit: DpSize): Dp = minOf(limit.height, limit.width * (310f / 800f))

/**
 * A clear logo's transparent bottom, as a share of its height (Chainsmoker Cat's art ends 66 px above its canvas's
 * bottom). Logos are cut to their art ([TrimToArt]): every layout raises the title's box by this much of a clear logo's
 * height, so the art ends where such a logo's art did; the lines under it follow at the headline's spacing.
 */
private const val CLEAR_LOGO_BOTTOM = 66f / 310f

/**
 * Phones and narrow windows: one list. The backdrop across the top, its picture reaching behind the metadata line, then
 * black under the actions and the description; then the season chips and the episodes; then More like this, the links
 * and the information. Beside a rail the list reaches under it, so the backdrop runs to the window's edge
 * ([navigationInset]), its rows padded back beside it. A name of more than one line lifts the backdrop's fade over the
 * title's box by as far as its first line ends above the box's bottom, as laid out ([TitleArt], [Backdrop]).
 */
@Composable
internal fun NarrowPage(state: DetailUiState, header: DetailHeader, focus: FocusMemory, intents: DetailIntents, width: Dp, height: Dp) {
    val dimens = LocalTortugaDimens.current
    val compact = LocalWindowLayout.current.widthClass == WidthClass.COMPACT
    // About 16:10, never more than most of a landscape window.
    val heroHeight = minOf(width * 0.62f, height * 0.6f, 420.dp)
    val backdrop = header.background
    // Over a backdrop the title's box rises from the picture's 16:10 bottom by a clear logo's transparent bottom; under
    // the back button it gives up its top instead.
    val box = DpSize((width - dimens.screenPadding * 2) * 0.8f, NarrowLogoHeight)
    val rise = if (backdrop != null) clearLogoHeight(box) * CLEAR_LOGO_BOTTOM else 0.dp
    val wholeLogoTop = (heroHeight - NarrowLogoHeight).coerceAtLeast(BackButtonSpace)
    val titleTop = (wholeLogoTop - rise).coerceAtLeast(BackButtonSpace)
    val titleBox = box.copy(height = wholeLogoTop + NarrowLogoHeight - rise - titleTop)
    // The metadata line's top: the picture reaches on behind it.
    val textTop = titleTop + titleBox.height + NarrowHeadlineGap
    // How far the name's first line ends above the title box's bottom, as laid out (TitleArt): 0 for one line, a logo.
    var nameLift by remember { mutableStateOf(0.dp) }
    val thumbnailWidth = listThumbnailWidth()
    val inset = navigationInset
    val listState = rememberLazyListState()
    val backButtonSpace = with(LocalDensity.current) { BackButtonSpace.toPx() }
    // The list opens at its top, on the current episode's season (and range), but for an anime list entry's first
    // episode: that comes to the top under the back button (the hero and the seasons, items 0 and 1, come first).
    val entry = state.shownEpisode.takeIf { state.shownEpisodeFromEntry }
    ShownEpisode(entry, state, intents) { index ->
        listState.scrollToItem(2 + index - state.listedStart)
        listState.scrollBy(-backButtonSpace)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().extendStart(inset),
        state = listState,
        contentPadding = PaddingValues(start = inset, bottom = dimens.sectionSpacing * 2),
    ) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth()) {
                if (backdrop != null) {
                    Backdrop(backdrop, titleTop, titleTop + titleBox.height, nameLift, textTop, Modifier.fillMaxWidth().height(textTop + PictureUnderText))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (backdrop != null) titleTop else BackButtonSpace)
                        .padding(horizontal = dimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(NarrowHeadlineGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (backdrop == null) PosterFallback(header, if (compact) 120.dp else 160.dp)
                    Headline(state, header, focus, intents, centred = true, titleBox, onNameLift = { nameLift = it })
                }
            }
        }
        if (state.hasEpisodes) {
            item(key = "seasons") {
                SeasonChips(
                    state,
                    focus,
                    intents,
                    selectedRange = state.listedRange,
                    // The page stays where it is; the episodes under the chips change.
                    onRange = intents.pickRange,
                    nameStart = dimens.screenPadding,
                    Modifier.padding(top = dimens.sectionSpacing + 8.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(state.listedEpisodes, key = { _, episode -> episode.videoId }) { index, episode ->
                EpisodeRow(episode, state.listedStart + index, focus, intents, thumbnailWidth, Modifier.padding(horizontal = dimens.screenPadding - RowInset))
            }
        }
        // A preview shows the header alone: the rest comes with the title.
        if (!state.preview) {
            state.related?.let { related ->
                item(key = "related") {
                    RelatedShelf(related, focus, intents, Modifier.padding(top = dimens.sectionSpacing + 8.dp))
                }
            }
            item(key = "about") {
                Information(
                    header,
                    focus,
                    intents.openLink,
                    Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding).padding(top = dimens.sectionSpacing + 8.dp),
                )
            }
        }
    }
}

/**
 * Wide windows: the backdrop behind the whole screen, near-black under the details and clearing past them, and fading
 * to black at the bottom. The details in a column on the left (at most [InfoMaxWidth]), More like this in it before the
 * information, its cards scrolling out of sight at the column's edges ([bleed]); a series' episodes in a column on the
 * right, the backdrop dimmed under them. A movie's details start lower, under the backdrop's picture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WidePage(state: DetailUiState, header: DetailHeader, focus: FocusMemory, intents: DetailIntents, width: Dp, height: Dp) {
    val dimens = LocalTortugaDimens.current
    // The focused control's growth and ring stay inside the scrolling column.
    val reach = dimens.focusReach
    val series = state.hasEpisodes
    val usable = width - dimens.screenPadding * 2
    val gap = 48.dp
    val infoWidth = if (series) (usable * 0.4f).coerceIn(340.dp, InfoMaxWidth) else usable.coerceAtMost(InfoMaxWidth)
    val paneWidth = (usable - infoWidth - gap).coerceAtMost(EpisodesMaxWidth)
    val thumbnailWidth = 176.dp
    val top = BackButtonSpace
    // A movie's details start lower, under the backdrop's picture. A logo's box is as tall as main made a clear logo's,
    // raised by its transparent bottom; under the top it gives up its top instead.
    val limit = DpSize(infoWidth.coerceAtMost(InfoMaxWidth), WideLogoHeight)
    val box = limit.copy(height = clearLogoHeight(limit))
    val rise = if (header.logo != null) box.height * CLEAR_LOGO_BOTTOM else 0.dp
    val wholeLogoTop = if (series) top else (height * 0.3f).coerceAtLeast(top)
    val detailsTop = (wholeLogoTop - rise).coerceAtLeast(top)
    val titleBox = box.copy(height = wholeLogoTop + box.height - rise - detailsTop)
    val platformScroll = LocalBringIntoViewSpec.current
    val stayInView = remember(platformScroll) { StayInViewSpec(platformScroll) }
    Box(Modifier.fillMaxSize()) {
        WideBackdrop(header.background, navigationInset + dimens.screenPadding + infoWidth, softEdge = (width * 0.25f).coerceIn(200.dp, 400.dp), dim = series, Modifier.fillMaxSize())
        CompositionLocalProvider(LocalBringIntoViewSpec provides stayInView) {
            Column(
                Modifier
                    .padding(start = dimens.screenPadding - reach)
                    .width(infoWidth + reach * 2)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = reach)
                    .padding(top = detailsTop, bottom = dimens.sectionSpacing * 2),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (header.background == null) PosterFallback(header, 200.dp)
                Headline(state, header, focus, intents, centred = false, titleBox)
                if (!state.preview) {
                    state.related?.let { related ->
                        // The shelf's own padding is the column's, which holds the focused card's growth and ring.
                        CompositionLocalProvider(LocalTortugaDimens provides dimens.copy(screenPadding = reach)) {
                            RelatedShelf(related, focus, intents, Modifier.bleed(reach))
                        }
                    }
                    Information(header, focus, intents.openLink)
                }
            }
        }
        if (series) {
            EpisodesPane(
                state,
                focus,
                intents,
                thumbnailWidth,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = dimens.screenPadding - RowInset, top = 16.dp)
                    .width(paneWidth + RowInset * 2)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * Phones and narrow windows: the title's backdrop across the top, 45 % black at its top (behind the back button), clear
 * from the back button's room down to the title's box ([titleTop]), 80 % black at the metadata line's top ([textTop]:
 * the line reads at about 5:1 over a white picture) and black at its bottom edge, every fade eased so none shows an
 * edge. [lift] (a name of more than one line: how far its first line ends above the box's bottom, [boxBottom]) moves
 * the fade's part over the box up by as much, unsqueezed: the darkness the fade has at the box's bottom comes at the end
 * of the name's first line, and its 80 % stays at the metadata line's top. Where that takes its start above the end of
 * the back button's room, the moved fade shows from there down only, the 45 % at the top eased into the darkness it has
 * there. So the first line sits on the darkness a one-line name's does, every line under it on more. Beside a rail it
 * runs under it, dimmed there ([NavigationBackdrop]). Harness name: `detail.backdrop`.
 */
@Composable
private fun Backdrop(url: String, titleTop: Dp, boxBottom: Dp, lift: Dp, textTop: Dp, modifier: Modifier) {
    val black = TortugaColors.Background
    NavigationBackdrop(modifier.layoutProbe("detail.backdrop")) {
        RemoteImage(url, contentDescription = null, modifier = Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().drawWithCache {
                val bars = BackButtonSpace.toPx() / size.height
                val title = titleTop.toPx() / size.height
                val text = textTop.toPx() / size.height
                var atBars = 0f
                val box = if (lift <= 0.dp) {
                    fadeStops(black, title, text, from = 0f, to = 0.8f)
                } else {
                    val darkness = 0.8f * eased((boxBottom - titleTop) / (textTop - titleTop))
                    val start = (titleTop - lift).toPx() / size.height
                    val end = (boxBottom - lift).toPx() / size.height
                    // The moved fade whole; above the bars only its darkness there, which the top fade eases into.
                    val moved = fadeStops(black, start, end, from = 0f, to = darkness)
                    if (start < bars) atBars = darkness * eased((bars - start) / (end - start))
                    arrayOf(bars to black.copy(alpha = atBars)) + moved.filter { it.first > bars } +
                        fadeStops(black, end, text, from = darkness, to = 0.8f)
                }
                val stops = fadeStops(black, 0f, bars, from = 0.45f, to = atBars) + box + fadeStops(black, text, 1f, from = 0.8f, to = 1f)
                val brush = Brush.verticalGradient(*stops)
                onDrawBehind { drawRect(brush) }
            },
        )
    }
}

/**
 * Wide windows: the title's backdrop behind the whole page, as the Apple TV app lays it. From the leading edge
 * the picture stays near-black as far as the details' column reaches ([infoEnd] from the window's leading edge, which
 * the backdrop runs to under the side navigation: [NavigationBackdrop]), so every line of it reads on black, then
 * clears over [softEdge]; the bottom fades to black too. [dim] darkens the rest of the picture (under a series'
 * episodes).
 */
@Composable
private fun WideBackdrop(url: String?, infoEnd: Dp, softEdge: Dp, dim: Boolean, modifier: Modifier) {
    val black = TortugaColors.Background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    NavigationBackdrop(modifier) {
        RemoteImage(url, contentDescription = null, modifier = Modifier.matchParentSize())
        // The scrims in one layer, each drawn only where it isn't clear.
        Box(
            Modifier.matchParentSize().drawWithCache {
                val end = (infoEnd + softEdge).toPx()
                val solid = infoEnd / (infoEnd + softEdge)
                // Near-black across the column (a trace of the picture left in it), easing to the column's far edge,
                // then an eased fade to clear, or to a series' dim, folded in: black at a over black at dim is black at
                // 1 - (1 - a)(1 - dim).
                val dimAlpha = if (dim) SERIES_DIM else 0f
                val stops = (fadeStops(black, 0f, solid, from = 0.96f, to = COLUMN_SCRIM) + fadeStops(black, solid, 1f, from = COLUMN_SCRIM))
                    .map { (at, color) -> at to color.copy(alpha = 1 - (1 - color.alpha) * (1 - dimAlpha)) }
                    .toTypedArray()
                val across = if (rtl) {
                    Brush.horizontalGradient(*stops, startX = size.width, endX = size.width - end)
                } else {
                    Brush.horizontalGradient(*stops, startX = 0f, endX = end)
                }
                // Past the fade only a series' dim is left.
                val acrossWidth = if (dim) size.width else minOf(end, size.width)
                val acrossLeft = if (rtl) size.width - acrossWidth else 0f
                // The lower part sinks into the page: clear down to 45 %, black at the bottom edge.
                val bottomTop = size.height * 0.45f
                val bottom = Brush.verticalGradient(*arrayOf(0f to Color.Transparent) + fadeStops(black, 1f, 0.45f, from = 1f), startY = 0f, endY = size.height)
                onDrawBehind {
                    drawRect(across, topLeft = Offset(acrossLeft, 0f), size = Size(acrossWidth, size.height))
                    drawRect(bottom, topLeft = Offset(0f, bottomTop), size = Size(size.width, size.height - bottomTop))
                }
            },
        )
    }
}

/**
 * How dark a series' backdrop is under its episodes: the picture at 30 %, as Home's backdrop, so the season names
 * heading the episodes' column read at 4.5:1 or more over its bright side ([SeasonChips]).
 */
private const val SERIES_DIM = 0.7f

/** How dark the wide backdrop stays at the details' column's far edge, where its scrim starts to clear. */
private const val COLUMN_SCRIM = 0.86f

/** The poster ([LocalPosterTemplate]'s, else the addon's), only for a title without a backdrop. */
@Composable
private fun PosterFallback(header: DetailHeader, width: Dp) {
    val poster = LocalPosterTemplate.current?.url(header.type, header.id, header.imdbId) ?: header.poster ?: return
    Box(Modifier.width(width).aspectRatio(header.posterShape.aspectRatio).clip(MaterialTheme.shapes.medium).background(TortugaColors.SurfaceHigh)) {
        RemoteImage(poster, contentDescription = null, modifier = Modifier.matchParentSize(), fallbackUrl = header.poster)
    }
}

/**
 * The title, the metadata line and the ratings row, the actions, the episode the primary action plays, the saved
 * title's note ([OfflineNote]) and the description. [centred] (narrow windows): the title, the metadata line, the
 * ratings and the actions centred, the description across the page. The title fills [titleBox] ([TitleArt]; how far
 * its name's first line ends above the box's bottom goes to [onNameLift]). The title, the metadata block (the line and
 * the ratings, closer together: the line's own leading makes up the rest) and the actions are the headline's 14 dp
 * apart, the actions that far under the block's last line, the metadata line where there are no ratings: no row keeps
 * room for one that isn't there. A preview ([DetailBody.Preview]) is the title, the metadata line and the ratings
 * alone, where the whole headline has them.
 */
@Composable
private fun Headline(
    state: DetailUiState,
    header: DetailHeader,
    focus: FocusMemory,
    intents: DetailIntents,
    centred: Boolean,
    titleBox: DpSize,
    onNameLift: (Dp) -> Unit = {},
) {
    val alignment = if (centred) Alignment.CenterHorizontally else Alignment.Start
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = alignment) {
        TitleArt(header, centred, titleBox, onNameLift)
        if (state.showsMetaLine) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = alignment) {
                MetaLine(header, centred)
                RatingsRow(state, header.ratings, focus, intents, centred)
            }
        }
        if (state.preview) {
            // What a long wait waits for, in the actions' place.
            state.loadingNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start).contentDirection(),
                    color = TortugaColors.TextMuted,
                    modifier = Modifier.layoutProbe("detail.loadingNote"),
                )
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = alignment) {
            ActionRow(state, focus, intents, centred)
            state.primaryAction?.episodeTitle?.let {
                // Whole at a large font scale, on as many lines as it takes.
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start).contentDirection(),
                    color = TortugaColors.TextMuted,
                    maxLines = if (largeFontScale()) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.playerApp?.let { PlayerAppLine(it, focus, intents) }
        state.offlineNote?.let { OfflineNote(it) }
        header.overview?.let { Overview(it, focus) }
    }
}

/** Release, runtime and genres on one line. Harness name: `detail.infoLine`. */
@Composable
private fun MetaLine(header: DetailHeader, centred: Boolean) {
    val line = header.infoLine ?: return
    Text(
        line,
        style = MaterialTheme.typography.bodyLarge,
        color = TortugaColors.TextMuted,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.layoutProbe("detail.infoLine"),
    )
}

/**
 * The ratings row under the metadata line: each score after its source's mark, drawn plain (no fill, no border), in
 * the order the store gives them, wrapping onto more lines where the page is narrow; centred under a [centred]
 * headline, else from the leading edge. An item is one element for accessibility, read as its [DetailRating.label].
 * The IMDb item opens IMDb, shown as a link ([linkLook]), and Down from it goes to the primary action (else the library
 * toggle) wherever it lies in the row; the other items open nothing and take no focus. The user's own score, once
 * there is one, ends the row ([UserRatingItem]). (TV's title block draws the ratings itself: [TvDetailPage].) Harness
 * names: `detail.rating.<source key>`, `detail.userRating`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingsRow(state: DetailUiState, ratings: List<DetailRating>, focus: FocusMemory, intents: DetailIntents, centred: Boolean) {
    val userRating = state.userRating?.takeIf { it.text != null }
    if (ratings.isEmpty() && userRating == null) return
    val metrics = ratingMetrics(tv = false)
    val below = if (state.primaryAction != null) FOCUS_PLAY else FOCUS_LIBRARY
    // As wide as the content, so each line is centred in it directly: a row as wide as its widest line, centred in
    // turn, rounds twice and can sit a pixel or two off centre.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(metrics.gap, if (centred) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(metrics.lineGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (rating in ratings) {
            val probe = Modifier.layoutProbe("detail.rating.${rating.source.key}")
            val linkId = rating.linkId
            if (linkId != null) {
                RatingLink(
                    rating,
                    metrics,
                    onClick = { intents.openLink(linkId) },
                    modifier = probe
                        .rememberFocus(focus, "rating:${rating.source.key}")
                        .focusProperties { down = focus.requester(below) },
                )
            } else {
                RatingItem(rating, metrics, probe)
            }
        }
        if (userRating != null) UserRatingItem(userRating, metrics)
    }
}

/** A rating that opens a page ([onClick]): a [RatingItem] shown as a link ([linkLook]). */
@Composable
private fun RatingLink(rating: DetailRating, metrics: RatingMetrics, onClick: () -> Unit, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val look = linkLook(interactions)
    RatingItem(
        rating,
        metrics,
        modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .overlayOpener()
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
        look,
    )
}

/**
 * The one row of actions, never wrapping: Play/Resume (the watched part filled inside it; "Finding a stream…" while
 * auto-play looks) as wide as its label (at least [PlayMinWidth], [CentredPlayMinWidth] when [centred]), then round
 * buttons at their full size, translucent over the backdrop ([IconButtonStyle.OVER_PICTURE]), for choosing the stream
 * (with auto-play on), the library (+ / ✓), the watched mark (an eye, filled once watched), the download
 * ([DownloadButton]) and the trailer ([TrailerButton]). Where the row has no room for them all (a phone, a narrow
 * window, the details' column beside a series' episodes), the most important stay ([fitActions], the rule every
 * UI shares) and a round ⋯ at the row's end ([OverflowButton]) opens the rest; Play's label is cut short only when Play
 * and ⋯ alone don't fit. [centred] (narrow windows): the row is one compact group, which the headline centres.
 */
@Composable
private fun ActionRow(state: DetailUiState, focus: FocusMemory, intents: DetailIntents, centred: Boolean) {
    val buttonSize = LocalTortugaDimens.current.iconButtonSize
    SubcomposeLayout { constraints ->
        val gap = ActionGap.roundToPx()
        val play = state.primaryAction?.let { action ->
            subcompose("play") { PlayButton(action, focus, intents.play, if (centred) CentredPlayMinWidth else PlayMinWidth) }.single()
        }
        // Play at its label's full width, then as many round buttons as fit beside it, each after a gap.
        val slots = when {
            !constraints.hasBoundedWidth -> Int.MAX_VALUE
            play == null -> (constraints.maxWidth + gap) / (buttonSize.roundToPx() + gap)
            else -> (constraints.maxWidth - play.maxIntrinsicWidth(constraints.maxHeight)) / (buttonSize.roundToPx() + gap)
        }
        val fit = state.fitActions(slots)
        val buttons = subcompose("buttons") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ActionGap)) {
                for (action in fit.shown) {
                    key(action) {
                        when (action) {
                            DetailAction.CHOOSE_STREAM -> TortugaIconButton(
                                TortugaIcons.StreamList,
                                checkNotNull(state.chooseStreamText),
                                intents.chooseStream,
                                Modifier.rememberFocus(focus, FOCUS_CHOOSE_STREAM).layoutProbe("detail.chooseStream"),
                                style = IconButtonStyle.OVER_PICTURE,
                            )
                            DetailAction.LIBRARY -> TortugaIconButton(
                                if (state.inLibrary) TortugaIcons.Check else TortugaIcons.Add,
                                state.libraryActionText,
                                intents.toggleLibrary,
                                Modifier.rememberFocus(focus, FOCUS_LIBRARY).layoutProbe("detail.library"),
                                style = IconButtonStyle.OVER_PICTURE,
                                selected = state.inLibrary,
                            )
                            DetailAction.WATCHED -> TortugaIconButton(
                                if (state.watched) TortugaIcons.Visibility else TortugaIcons.VisibilityOutlined,
                                state.watchedActionText,
                                { intents.markWatched(!state.watched) },
                                Modifier.rememberFocus(focus, FOCUS_WATCHED).layoutProbe("detail.watched"),
                                style = IconButtonStyle.OVER_PICTURE,
                                selected = state.watched,
                            )
                            DetailAction.DOWNLOAD -> DownloadButton(checkNotNull(state.download), focus, intents)
                            DetailAction.TRAILER -> TrailerButton(state, focus, intents)
                            DetailAction.SHARE -> TortugaIconButton(
                                TortugaIcons.Share,
                                checkNotNull(state.share).label,
                                intents.share,
                                Modifier.rememberFocus(focus, "share").layoutProbe("detail.share"),
                                style = IconButtonStyle.OVER_PICTURE,
                            )
                            // fitActions keeps these behind ⋯; drawn as the TV row's buttons should a fit ever show them.
                            DetailAction.WATCHLIST -> WatchlistButton(checkNotNull(state.watchlist), focus, intents)
                            DetailAction.RATE -> RateButton(checkNotNull(state.userRating), focus, intents)
                            DetailAction.KIDS_BLOCK -> TortugaIconButton(
                                TortugaIcons.Block,
                                checkNotNull(state.kidsBlockAction),
                                intents.blockOnProfile,
                                Modifier.rememberFocus(focus, FOCUS_KIDS_BLOCK).layoutProbe("detail.kidsBlockAction"),
                                style = IconButtonStyle.OVER_PICTURE,
                            )
                        }
                    }
                }
                if (fit.more.isNotEmpty()) OverflowButton(fit.more, state, focus, intents)
            }
        }.single().measure(Constraints())
        val playPlaced = play?.measure(Constraints(maxWidth = (constraints.maxWidth - buttons.width - gap).coerceAtLeast(0)))
        val start = if (playPlaced == null) 0 else playPlaced.width + gap
        val height = maxOf(playPlaced?.height ?: 0, buttons.height)
        layout(constraints.constrainWidth(start + buttons.width), height) {
            playPlaced?.placeRelative(0, (height - playPlaced.height) / 2)
            buttons.placeRelative(start, (height - buttons.height) / 2)
        }
    }
}

/** The gap between the action row's controls. */
private val ActionGap = 10.dp

/** Which of ⋯'s menus is open: its own, the download's or the trailers' it leads to, or the rating picker. */
private enum class OverflowMenu { NONE, ACTIONS, DOWNLOAD, TRAILERS, RATING }

/**
 * The actions the action row has no room for ([DetailActionFit.more]) behind a round ⋯ at its end (`detail.overflow`,
 * read "More actions"): a menu of their words and icons in the row's order (`detail.overflow.<action>`), each doing
 * what its button does, then the trackers' watchlists where one keeps the title ([DetailAction.WATCHLIST],
 * `detail.overflow.watchlist`, "Add to Trakt watchlist" or "Tracker watchlists", a filled bookmark while it is on a
 * list), the user's rating where a tracker rates the title ([DetailAction.RATE],
 * `detail.overflow.rate`, "Rate this title" or "Your rating: 8", opening the rating picker, [UserRatingSheet]), and
 * Block on this profile last where the title has it ([DetailAction.KIDS_BLOCK], `detail.overflow.kidsBlock`). The
 * download's entry presses it, or, where its button opens a menu, opens that menu ([DownloadMenu]) from ⋯ in this
 * one's place; several trailers' entry opens theirs ([TrailerMenu]) the same way.
 */
@Composable
private fun OverflowButton(actions: List<DetailAction>, state: DetailUiState, focus: FocusMemory, intents: DetailIntents) {
    var menu by remember { mutableStateOf(OverflowMenu.NONE) }
    val close = { menu = OverflowMenu.NONE }
    Box {
        TortugaIconButton(
            TortugaIcons.MoreHoriz,
            T.detail.moreActions,
            { menu = OverflowMenu.ACTIONS },
            Modifier.rememberFocus(focus, "overflow").layoutProbe("detail.overflow"),
            style = IconButtonStyle.OVER_PICTURE,
        )
        ItemMenu(
            expanded = menu == OverflowMenu.ACTIONS,
            onDismiss = close,
            entries = actions.map { action ->
                when (action) {
                    DetailAction.CHOOSE_STREAM ->
                        ItemMenuEntry(state.chooseStreamText.orEmpty(), "chooseStream", icon = TortugaIcons.StreamList, onClick = intents.chooseStream)
                    DetailAction.LIBRARY -> ItemMenuEntry(
                        state.libraryActionText,
                        "library",
                        icon = if (state.inLibrary) TortugaIcons.Check else TortugaIcons.Add,
                        onClick = intents.toggleLibrary,
                    )
                    DetailAction.WATCHED -> ItemMenuEntry(
                        state.watchedActionText,
                        "watched",
                        icon = if (state.watched) TortugaIcons.Visibility else TortugaIcons.VisibilityOutlined,
                    ) { intents.markWatched(!state.watched) }
                    DetailAction.DOWNLOAD -> {
                        val download = checkNotNull(state.download)
                        ItemMenuEntry(download.label, "download", icon = download.state.icon) {
                            if (download.opensMenu) menu = OverflowMenu.DOWNLOAD else intents.pressDownload()
                        }
                    }
                    DetailAction.TRAILER -> ItemMenuEntry(state.trailerActionText, "trailer", icon = TortugaIcons.Trailer) {
                        state.singleTrailer?.let { intents.openTrailer(it.id) } ?: run { menu = OverflowMenu.TRAILERS }
                    }
                    DetailAction.SHARE -> ItemMenuEntry(checkNotNull(state.share).label, "share", icon = TortugaIcons.Share, onClick = intents.share)
                    DetailAction.WATCHLIST -> checkNotNull(state.watchlist).let { watchlist ->
                        ItemMenuEntry(watchlist.label, "watchlist", icon = watchlist.icon, onClick = intents.watchlist)
                    }
                    DetailAction.RATE -> ItemMenuEntry(checkNotNull(state.userRating).label, "rate", icon = TortugaIcons.Star) { menu = OverflowMenu.RATING }
                    DetailAction.KIDS_BLOCK ->
                        ItemMenuEntry(checkNotNull(state.kidsBlockAction), "kidsBlock", icon = TortugaIcons.Block, onClick = intents.blockOnProfile)
                }
            },
            probe = "detail.overflow",
            title = T.detail.moreActions,
        )
        state.download?.let { DownloadMenu(it, menu == OverflowMenu.DOWNLOAD, close, intents) }
        TrailerMenu(state, menu == OverflowMenu.TRAILERS, close, intents)
        state.userRating?.takeIf { menu == OverflowMenu.RATING }?.let { UserRatingSheet(it, intents.rate, onDismiss = close) }
    }
}

/** The description, three lines until More opens the rest (Less closes it again). */
@Composable
private fun Overview(text: String, focus: FocusMemory) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    // Across the page, from its leading edge, also under a centred headline.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.contentDirection(),
            color = TortugaColors.TextMuted,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TortugaSurface(
                onClick = { expanded = !expanded },
                // The label lines up with the text; its hover and focus fill reach past it.
                modifier = Modifier.offset(x = (-8).dp).rememberFocus(focus, FOCUS_MORE).layoutProbe("detail.more"),
                shape = MaterialTheme.shapes.small,
                color = Color.Transparent,
                hitTarget = true,
            ) {
                Text(
                    if (expanded) T.detail.showLess else T.detail.showMore,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * A series' episodes beside its details (wide windows): the season chips over the season's episode rows (a long
 * season's listed range, [DetailUiState.listedEpisodes]), each range's list from its top. A move into the rows with the
 * arrow keys or a D-pad lands on [DetailUiState.currentEpisode] where they hold it (brought to the middle first when it
 * isn't composed).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodesPane(state: DetailUiState, focus: FocusMemory, intents: DetailIntents, thumbnailWidth: Dp, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    val scope = rememberCoroutineScope()
    val selected = state.selectedSeason
    val listState = rememberSaveable(selected, state.listedRange, saver = LazyListState.Saver) { LazyListState() }
    val episodes = state.listedEpisodes
    Column(modifier) {
        SeasonChips(state, focus, intents, selectedRange = state.listedRange, onRange = intents.pickRange, nameStart = RowInset, Modifier.padding(bottom = 4.dp))
        ShownEpisode(state.shownEpisode, state, intents) { index ->
            listState.centre(index - state.listedStart)
        }
        val current = state.currentEpisode
        val currentIndex = episodes.indexOfFirst { it.videoId == current }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusProperties {
                    onEnter = {
                        val key = current?.let(::episodeFocusKey)
                        if (key != null && currentIndex >= 0 && requestedFocusDirection in DirectionalMoves && !focus.focus(key)) {
                            // Off screen, so not composed: brought to the middle, then focused.
                            cancelFocusChange()
                            scope.launch {
                                listState.centre(currentIndex)
                                repeat(FOCUS_FRAMES) {
                                    withFrameNanos { }
                                    if (focus.focus(key)) return@launch
                                }
                            }
                        }
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(bottom = dimens.sectionSpacing * 2),
        ) {
            itemsIndexed(episodes, key = { _, episode -> episode.videoId }) { index, episode ->
                EpisodeRow(episode, state.listedStart + index, focus, intents, thumbnailWidth)
            }
        }
    }
}

/**
 * An episode as a free-flowing row ([EpisodeListRow]): the 16:9 thumbnail (its progress along the bottom, a check once
 * watched), "3. Title", the date and runtime, its download's state ([DownloadBadge]: "Downloaded", "45%"), two lines of
 * synopsis, and its ⋮. A click plays auto-play's pick or opens its streams; its menu (long press, right click, the Menu
 * key, its ⋮ button) lists the episode's media menu ([DetailEpisode.menu]: Play from the beginning, its marks and its
 * season's, Choose a stream, its download's entries, its trailer), named `detail.menu.item.<action>`. Episodes not out
 * yet are dimmed. Screen readers read the row as one element, its texts then its state: the progress and "Watched"
 * ([DetailEpisode.stateText]). Harness names: `detail.episode.<i>` with its `.menu` and `.download` (the badge).
 */
@Composable
private fun EpisodeRow(
    episode: DetailEpisode,
    index: Int,
    focus: FocusMemory,
    intents: DetailIntents,
    thumbnailWidth: Dp,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val probe = "detail.episode.$index"
    Box(modifier) {
        EpisodeListRow(
            title = episode.numberedTitle,
            infoLine = episode.infoLine,
            overview = episode.overview,
            hiddenOverviewLabel = episode.hiddenOverviewLabel,
            onClick = { intents.openEpisode(episode.videoId) },
            modifier = Modifier
                .rememberFocus(focus, episodeFocusKey(episode.videoId))
                .menuGestures { menu = true }
                .layoutProbe(probe)
                .semantics { episode.stateText?.let { stateDescription = it } },
            dimmed = episode.upcoming,
            onLongClick = { menu = true },
            trailing = {
                TortugaIconButton(
                    TortugaIcons.MoreVert,
                    episode.moreLabel,
                    { menu = true },
                    Modifier.layoutProbe("$probe.menu"),
                    style = IconButtonStyle.PLAIN,
                    size = 32.dp,
                )
            },
            badge = {
                episode.download?.let { download ->
                    download.label?.let { DownloadBadge(download.state, it, download.progress?.toFloat(), Modifier.layoutProbe("$probe.download")) }
                }
            },
        ) {
            EpisodeThumbnail(
                episode.thumbnail,
                episode.blurred,
                Modifier.width(thumbnailWidth),
                watched = episode.watched,
                progress = episode.progress?.toFloat(),
            )
        }
        val entries = remember(episode.menu, intents) {
            episode.menu.map { entry -> entry.itemEntry { action -> intents.episodeMenu(episode.videoId, action) } }
        }
        ItemMenu(
            expanded = menu,
            onDismiss = { menu = false },
            entries = entries,
            probe = MENU_PROBE,
            title = episode.numberedTitle,
            media = true,
        )
    }
}
