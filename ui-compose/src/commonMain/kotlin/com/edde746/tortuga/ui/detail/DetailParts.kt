package com.edde746.tortuga.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Scale
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.detail.DetailDownload
import com.edde746.tortuga.presentation.detail.DetailDownloadState
import com.edde746.tortuga.presentation.detail.DetailHeader
import com.edde746.tortuga.presentation.detail.DetailPlayerApp
import com.edde746.tortuga.presentation.detail.DetailPrimaryAction
import com.edde746.tortuga.presentation.detail.DetailRating
import com.edde746.tortuga.presentation.detail.DetailUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.DownloadMark
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ItemMenu
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ItemMenuSection
import com.edde746.tortuga.ui.components.SectionedItemMenu
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.icon
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.TrimToArt
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Brings the episodes to [shown] once ([scrollTo] with its index among them), then tells the store
 * ([DetailIntents.episodeShown]). The episodes are searched for it only then, not as the page composes again.
 */
@Composable
internal fun ShownEpisode(shown: String?, state: DetailUiState, intents: DetailIntents, scrollTo: suspend (Int) -> Unit) {
    LaunchedEffect(shown) {
        if (shown == null) return@LaunchedEffect
        val index = state.episodes.indexOfFirst { it.videoId == shown }
        if (index < 0) return@LaunchedEffect
        scrollTo(index)
        intents.episodeShown()
    }
}

/**
 * Scrolls the list to item [index] with its middle at the middle of what shows of the list; at the list's ends it stops
 * there. Waits for the item's first layout.
 */
internal suspend fun LazyListState.centre(index: Int) {
    scrollToItem(index)
    val item = snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }.filterNotNull().first()
    val info = layoutInfo
    scrollBy(item.offset + item.size / 2f - (info.viewportStartOffset + info.viewportEndOffset) / 2f)
}

/**
 * TV's episodes keep the focused card at their row's middle, as Android TV's own lists do (the row's ends stop it), so
 * a card brought to the middle ([centre]) stays there as focus lands on it.
 */
internal object CentredInView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset + size / 2f - containerSize / 2f
}

internal const val FOCUS_PLAY = "play"

internal const val FOCUS_LIBRARY = "library"

internal const val MENU_PROBE = "detail.menu.item"

internal const val FOCUS_DOWNLOAD = "download"

internal const val FOCUS_TRAILER = "trailer"

internal const val FOCUS_CHOOSE_STREAM = "chooseStream"

internal const val FOCUS_WATCHED = "watched"

internal const val FOCUS_KIDS_BLOCK = "kidsBlock"

internal const val FOCUS_MORE = "more"

internal const val FOCUS_RATE = "rate"

internal const val FOCUS_WATCHLIST = "watchlist"

internal fun episodeFocusKey(videoId: String): String = "episode:$videoId"

/** Play/Resume is at least this wide in the details' column of wide layouts and TV. */
internal val PlayMinWidth = 160.dp

/** A logo is drawn at most this many times its own pixels: more than that blurs. */
private const val MAX_LOGO_UPSCALE = 2f

/**
 * "Showing the details saved with your downloads.": a banner across the column while the title shown is the one saved
 * with its downloads (offline, or no addon could load it). Harness name: `detail.offlineNote`.
 */
@Composable
internal fun OfflineNote(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHighest, MaterialTheme.shapes.large)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .layoutProbe("detail.offlineNote"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TortugaIcons.CloudOff, contentDescription = null, Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * The title's playback in a player app ([DetailUiState.playerApp]), across the column under the actions (not a dialog):
 * "Playing in mpv · 12:34 of 1:45:00" ("Paused in mpv · …" while paused) while a player that reports back plays it,
 * or "Opened in VLC. Finished watching …?" with Mark as watched and Not yet, which answer it
 * ([DetailIntents.answerPlayerApp]). The answers are buttons a remote reaches with Down from the actions; once one is
 * pressed the line goes, and focus goes back to Play (else the library button) rather than to the navigation.
 *
 * Harness names: `detail.playerApp`, `detail.playerApp.markWatched`, `detail.playerApp.notYet`.
 */
@Composable
internal fun PlayerAppLine(line: DetailPlayerApp, focus: FocusMemory, intents: DetailIntents) {
    val answer = { finished: Boolean ->
        intents.answerPlayerApp(finished)
        focus.focus(FOCUS_PLAY) || focus.focus(FOCUS_LIBRARY)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHighest, MaterialTheme.shapes.large)
            .layoutProbe("detail.playerApp", reportsFocus = false)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TortugaIcons.OpenInNew, contentDescription = null, Modifier.size(20.dp))
            Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (line.markWatchedText != null || line.notYetText != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.markWatchedText?.let {
                    TortugaButton(
                        it,
                        { answer(true) },
                        Modifier.rememberFocus(focus, FOCUS_MARK_WATCHED).layoutProbe("detail.playerApp.markWatched"),
                        icon = TortugaIcons.Check,
                    )
                }
                line.notYetText?.let {
                    TortugaButton(
                        it,
                        { answer(false) },
                        Modifier.rememberFocus(focus, FOCUS_NOT_YET).layoutProbe("detail.playerApp.notYet"),
                        style = ButtonStyle.TEXT,
                    )
                }
            }
        }
    }
}

/** Focus to the player app's first answer ([PlayerAppLine]: Mark as watched, else Not yet); false while none shows. */
internal fun FocusMemory.focusAnswer(): Boolean = focus(FOCUS_MARK_WATCHED) || focus(FOCUS_NOT_YET)

private const val FOCUS_MARK_WATCHED = "playerApp.markWatched"

private const val FOCUS_NOT_YET = "playerApp.notYet"

/**
 * The title's logo when the addon has one, else its name (the name too while the logo loads, and if it fails). The
 * logo is cut to its art ([TrimToArt]) and aspect-fit as large as [box] allows (narrow windows: 80 % of the page's
 * width and [NarrowLogoHeight]; wide ones: the details' column up to [InfoMaxWidth], as tall as a clear logo is there,
 * up to [WideLogoHeight]; TV's title block: [TvLogoBox]; each less what the page took off
 * its top), at most [MAX_LOGO_UPSCALE] times its own pixels, at the bottom of a box that tall from the start (on narrow
 * windows the name's too), so the lines under it stay put as it loads. The name is never cut: it wraps onto as many
 * lines as it takes, and a name taller than the box (a long one, large text) grows it downwards, moving the lines under
 * it down; the page scrolls to them. [onNameLift] gets how far the name's first line ends above the bottom of [box] (0
 * for one line, and once the logo replaces the name): the narrow page lifts the backdrop's fade by as much
 * ([Backdrop]), so each line of the name sits on it. [centred]: centred in it. Harness names: `detail.title` (the box),
 * `detail.logo` (the logo once it has loaded).
 */
@Composable
internal fun TitleArt(header: DetailHeader, centred: Boolean, box: DpSize, onNameLift: (Dp) -> Unit) {
    val layout = LocalWindowLayout.current
    val tv = layout.formFactor == FormFactor.TV
    val style = when {
        tv -> MaterialTheme.typography.headlineLarge
        layout.widthClass == WidthClass.COMPACT -> MaterialTheme.typography.headlineMedium
        else -> MaterialTheme.typography.displaySmall
    }
    val logo = header.logo
    // The logo's own pixels once it has loaded.
    var pixels by remember(logo) { mutableStateOf<Size?>(null) }
    var failed by remember(logo) { mutableStateOf(false) }
    val alignment = if (centred) Alignment.BottomCenter else Alignment.BottomStart
    Box(
        Modifier.fillMaxWidth().then(if (centred || logo != null) Modifier.heightIn(min = box.height) else Modifier).layoutProbe("detail.title"),
        contentAlignment = alignment,
    ) {
        val shown = logo != null && !failed
        if (!shown || pixels == null) {
            val density = LocalDensity.current
            Text(
                header.title,
                style = style.copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start).contentDirection(),
                // At the box's bottom; a name taller than the box grows it down from its top.
                onTextLayout = { name ->
                    val bottom = minOf(name.size.height.toFloat(), with(density) { box.height.toPx() })
                    onNameLift(with(density) { if (name.lineCount == 1) 0.dp else (bottom - name.getLineBottom(0)).toDp() })
                },
            )
            DisposableEffect(Unit) { onDispose { onNameLift(0.dp) } }
        }
        if (shown) {
            val context = LocalPlatformContext.current
            // Decoded to cover the box, so the art cut from the canvas still has the pixels to fill it.
            val request = remember(logo, context) { ImageRequest.Builder(context).data(logo).transformations(TrimToArt).scale(Scale.FILL).build() }
            val size = pixels?.let { logoSize(it, box, LocalDensity.current) }
            AsyncImage(
                model = request,
                // Named once it shows: until then the name above is the title a screen reader hears.
                contentDescription = if (size != null) header.title else null,
                // Loaded at the largest size, unseen; then sized to the logo's own shape.
                modifier = if (size != null) Modifier.size(size).layoutProbe("detail.logo") else Modifier.size(box).alpha(0f),
                alignment = alignment,
                contentScale = ContentScale.Fit,
                onState = {
                    when (it) {
                        is AsyncImagePainter.State.Success -> pixels = it.painter.intrinsicSize
                        is AsyncImagePainter.State.Error -> failed = true
                        else -> Unit
                    }
                },
            )
        }
    }
}

/** A logo of [pixels] aspect-fit into [limit], drawn at most [MAX_LOGO_UPSCALE] times its own size. */
private fun logoSize(pixels: Size, limit: DpSize, density: Density): DpSize {
    if (!pixels.isSpecified || pixels.width <= 0f || pixels.height <= 0f) return limit
    return with(density) {
        val scale = minOf(limit.width.toPx() / pixels.width, limit.height.toPx() / pixels.height, MAX_LOGO_UPSCALE)
        DpSize((pixels.width * scale).toDp(), (pixels.height * scale).toDp())
    }
}

/**
 * A rating: its mark ([RatingMetrics.text]'s font size tall, as wide as its own aspect ratio makes it) centred on the
 * score beside it; one accessibility element, read as [DetailRating.label]. [link]: the look of a rating that opens a
 * page.
 */
@Composable
internal fun RatingItem(rating: DetailRating, metrics: RatingMetrics, modifier: Modifier, link: LinkLook? = null) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = rating.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.innerGap),
    ) {
        val logo = rating.logo.vector
        val height = with(LocalDensity.current) { metrics.text.fontSize.toDp() }
        Image(logo, contentDescription = null, Modifier.size(height * (logo.viewportWidth / logo.viewportHeight), height))
        Text(
            rating.text,
            style = metrics.text,
            color = link?.content ?: TortugaColors.Text,
            textDecoration = if (link?.underlined == true) TextDecoration.Underline else null,
            maxLines = 1,
        )
    }
}

/** The sizes of the ratings row: compact on phones, tablets and desktop, a step larger on TV, read from the couch. */
internal class RatingMetrics(
    val text: TextStyle,
    /** Between a mark and its score. */
    val innerGap: Dp,
    /** Between one rating and the next. */
    val gap: Dp,
    /** Between the lines of a wrapped row. */
    val lineGap: Dp,
)

@Composable
internal fun ratingMetrics(tv: Boolean): RatingMetrics {
    val type = MaterialTheme.typography
    return if (tv) {
        RatingMetrics(
            text = type.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            innerGap = 8.dp,
            gap = 24.dp,
            lineGap = 10.dp,
        )
    } else {
        RatingMetrics(
            text = type.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            innerGap = 6.dp,
            gap = 16.dp,
            lineGap = 8.dp,
        )
    }
}

/**
 * Play/Resume ([DetailUiState.primaryAction]), the page's one primary button: [label] after the play symbol ("Play"; an
 * episode's code alone, "S1E5"; a resume's only where it resumes from, "S1E5 · 32:44"; "Finding a stream…" while its
 * stream is found), the watched part filled inside it, at least [minWidth] wide; its label shrinks a little, then is cut
 * short, where the row gives it less room ([TortugaButton]). It rests in the primary fill; focus draws a ring around it
 * and grows it on TV. Screen readers hear [spokenLabel] ("Play S1E5", "Resume S1E5 · 32:44"), as they can't see the
 * symbol or the fill. Harness name: `detail.play`.
 */
@Composable
internal fun PlayButton(
    action: DetailPrimaryAction,
    focus: FocusMemory,
    onPlay: () -> Unit,
    minWidth: Dp,
    label: String = action.label,
    spokenLabel: String = action.spokenLabel,
) {
    TortugaButton(
        label,
        onPlay,
        Modifier
            .widthIn(min = minWidth)
            .rememberFocus(focus, FOCUS_PLAY)
            .semantics { contentDescription = spokenLabel }
            .layoutProbe("detail.play"),
        style = ButtonStyle.PRIMARY,
        icon = TortugaIcons.PlayArrow,
        progress = action.progress?.toFloat(),
    )
}

/** The trailer: one opens at once; several open a menu of them ([TrailerMenu]). */
@Composable
internal fun TrailerButton(state: DetailUiState, focus: FocusMemory, intents: DetailIntents) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TortugaIconButton(
            TortugaIcons.Trailer,
            state.trailerActionText,
            { state.singleTrailer?.let { intents.openTrailer(it.id) } ?: run { menu = true } },
            Modifier.rememberFocus(focus, FOCUS_TRAILER).layoutProbe("detail.trailer"),
            style = IconButtonStyle.OVER_PICTURE,
        )
        TrailerMenu(state, menu, { menu = false }, intents)
    }
}

/** Several trailers' menu (`detail.trailer.item.<n>`), opened from the trailer's button or ⋯; none for one trailer. */
@Composable
internal fun TrailerMenu(state: DetailUiState, expanded: Boolean, onDismiss: () -> Unit, intents: DetailIntents) {
    if (state.trailers.size < 2) return
    ItemMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        entries = state.trailers.map { trailer -> ItemMenuEntry(trailer.label) { intents.openTrailer(trailer.id) } },
        probe = "detail.trailer.item",
        title = state.trailerActionText,
    )
}

/**
 * The title's download ([DetailDownload]): a round button with the state's icon (the download arrow, a clock while
 * queued, pause, an error mark, a done mark once downloaded) and, while it downloads with a known size, its progress as
 * a ring inside the edge; its label for screen readers. A press acts at once ([DetailIntents.pressDownload]) or, when
 * [DetailDownload.opensMenu], opens its menu ([DownloadMenu]); a long press, a right click or the Menu key open the
 * menu either way. Harness name: `detail.download`.
 */
@Composable
internal fun DownloadButton(download: DetailDownload, focus: FocusMemory, intents: DetailIntents) {
    var menu by remember { mutableStateOf(false) }
    val size = LocalTortugaDimens.current.iconButtonSize
    Box {
        TortugaSurface(
            onClick = { if (download.opensMenu) menu = true else intents.pressDownload() },
            onLongClick = { menu = true },
            modifier = Modifier.size(size).rememberFocus(focus, FOCUS_DOWNLOAD).menuGestures { menu = true }.layoutProbe("detail.download"),
            shape = CircleShape,
            color = TortugaColors.PictureButton,
            selected = download.state == DetailDownloadState.DOWNLOADED,
            contentAlignment = Alignment.Center,
            hitTarget = true,
        ) {
            DownloadMark(download.state.icon, download.progress?.toFloat(), size = size - 6.dp, iconSize = size * 0.5f, contentDescription = download.label)
        }
        DownloadMenu(download, menu, { menu = false }, intents)
    }
}

/**
 * The download's menu ("Download season…" choices, Keep synced, managing the downloads), opened from its button or ⋯;
 * its entries are `detail.download.<entry id>`.
 */
@Composable
internal fun DownloadMenu(download: DetailDownload, expanded: Boolean, onDismiss: () -> Unit, intents: DetailIntents) {
    SectionedItemMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        sections = download.menu.map { section ->
            ItemMenuSection(
                section.title,
                section.entries.map { entry -> ItemMenuEntry(entry.label, entry.id, entry.selected, entry.destructive) { intents.downloadAction(entry.id) } },
            )
        },
        probe = "detail.download",
        title = download.menuTitle,
    )
}

/**
 * The information block: release date, runtime, country, language and awards, then the genres, cast, directors,
 * writers and the addon's other link categories in its order, each name a text link (a genre opens Discover, a person
 * Search, a page the browser). Each category's links are one focus group as wide as the column ([focusEntry]): Up and
 * Down go through its lines, then on to the next category, landing on the link that had focus in it last, else its
 * first, whatever the names' widths and wrap (a category of one short name could lose to the one after it). Harness
 * names of the links: `detail.link.<group>.<index>`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Information(header: DetailHeader, focus: FocusMemory, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    if (!header.hasInformation) return
    val dimens = LocalTortugaDimens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            header.factsTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.sectionTitleSize, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        // Released and Runtime can be our own wording ("May 6, 2019", "2 h 22 min"); the other facts are the addon's.
        val ours = T.detail.facts.let { setOf(it.released, it.runtime) }
        for (fact in header.informationFacts) {
            val style = MaterialTheme.typography.bodyMedium
            InformationRow(fact.label) { Text(fact.value, style = if (fact.label in ours) style else style.contentDirection()) }
        }
        header.linkGroups.forEachIndexed { groupIndex, group ->
            InformationRow(group.title) {
                FlowRow(Modifier.fillMaxWidth().focusEntry(group.links.firstOrNull()?.let { focus.requester(it.id) }), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.links.forEachIndexed { index, link ->
                        TextLink(
                            link.label,
                            separator = if (index < group.links.lastIndex) T.detail.listSeparator else null,
                            onClick = { onOpenLink(link.id) },
                            modifier = Modifier.rememberFocus(focus, link.id).layoutProbe("detail.link.$groupIndex.$index"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A line of the information block: [label] in a column of its own, then [value]. [label] is one of our plain labels or
 * an addon's own link category, so it takes its direction from its first letter.
 */
@Composable
private fun InformationRow(label: String, value: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium.contentDirection(), color = TortugaColors.TextMuted, modifier = Modifier.width(112.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

/**
 * A name that opens [onClick], styled as a link ([linkLook]). [separator] follows it (", " before the next name), in
 * the name's colour so it reads on the focus fill too.
 */
@Composable
private fun TextLink(text: String, separator: String?, onClick: () -> Unit, modifier: Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val look = linkLook(interactions)
    Row(modifier.pointerHoverIcon(PointerIcon.Hand).overlayOpener().clickable(interactionSource = interactions, indication = null, onClick = onClick)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.contentDirection(),
            color = look.content,
            textDecoration = if (look.underlined) TextDecoration.Underline else null,
            maxLines = 1,
            // The fill reaches past the name without moving it.
            modifier = Modifier.linkFill(look.fill, LinkFillReach),
        )
        if (separator != null) Text(separator, style = MaterialTheme.typography.bodyMedium, color = look.content, maxLines = 1)
    }
}

/** How far a focused link's fill reaches past its name, sideways and up and down. */
internal val LinkFillReach = DpOffset(4.dp, 2.dp)

/**
 * How a link (a [TextLink], the IMDb rating) shows the state of its [interactions]: the page's text colour, no fill and
 * no border. Hovered, pressed or focused it is [underlined][LinkLook.underlined]; on TV focus lays the primary
 * [fill][LinkLook.fill] behind it instead ([linkFill]), its [content][LinkLook.content] dark, without growing it. Each
 * look follows only what it shows: TV only focus, elsewhere no fill.
 */
@Composable
internal fun linkLook(interactions: MutableInteractionSource): LinkLook {
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val focused by interactions.collectIsFocusedAsState()
    val focusShown = focused && isFocusVisible()
    if (!tv) {
        val hovered by interactions.collectIsHoveredAsState()
        val pressed by interactions.collectIsPressedAsState()
        return LinkLook(NoFill, TortugaColors.Text, underlined = hovered || pressed || focusShown)
    }
    val fill = animateColorAsState(if (focusShown) TortugaColors.Primary else TortugaColors.Primary.copy(alpha = 0f), tween(durationMillis = 120), label = "linkFill")
    return LinkLook(fill, if (focusShown) TortugaColors.OnPrimary else TortugaColors.Text, underlined = false)
}

/** A link's fill where links have none. */
private val NoFill: State<Color> = object : State<Color> {
    override val value = Color.Transparent
}

/** A link's look in its state ([linkLook]). [fill] is read while drawing, so its animation only redraws. */
internal class LinkLook(val fill: State<Color>, val content: Color, val underlined: Boolean)

/** [fill] behind this element in 6 dp corners, reaching [reach] past it without moving it. */
internal fun Modifier.linkFill(fill: State<Color>, reach: DpOffset): Modifier = drawBehind {
    val color = fill.value
    if (color.alpha == 0f) return@drawBehind
    val past = Offset(reach.x.toPx(), reach.y.toPx())
    drawRoundRect(color, topLeft = -past, size = Size(size.width + past.x * 2, size.height + past.y * 2), cornerRadius = CornerRadius(6.dp.toPx()))
}

/** How many frames a row brought to the middle has to attach and take focus. */
internal const val FOCUS_FRAMES = 10
