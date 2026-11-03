package com.edde746.tortuga.ui.guide

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.guide.GuideBody
import com.edde746.tortuga.presentation.guide.GuideChannel
import com.edde746.tortuga.presentation.guide.GuideProgramme
import com.edde746.tortuga.presentation.guide.GuideStore
import com.edde746.tortuga.presentation.guide.GuideUiState
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.ErrorState
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.LocalFocusVisibility
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/** The TV guide of [app] ([Route.Guide]): its [GuideStore] lives while the route is on the back stack. */
@Composable
fun GuideRoute(app: AppSession) {
    val navigator = app.navigator
    // Once per appearance, as KeepSyncedRoute's: an entry composed after its route left the stack draws nothing.
    val store = remember(app) { navigator.retain(Route.Guide, GuideStore.KEY, create = app::guideStore, release = GuideStore::release) } ?: return
    val state by store.state.collectAsState()
    val intents = remember(store) { GuideIntents(store::selectDay, store::selectCatalog, store::play, store::loadMore, store::retry) }
    GuideScreen(state, intents, onBack = if (navigator.canGoBack) ({ navigator.back() }) else null)
}

/** What the guide asks of [GuideStore]. */
class GuideIntents(
    val selectDay: (epochDay: Long) -> Unit,
    val selectCatalog: (key: String) -> Unit,
    val play: (channelId: String) -> Unit,
    val loadMore: () -> Unit,
    val retry: () -> Unit,
)

/**
 * How the guide is laid out, by the window: for a remote ([TV]); for touch or a pointer with the details over the
 * channels (tablets, the desktop: [WIDE]); for a phone's narrow window, the details in a sheet ([PHONE]). [minute] is
 * a minute's width on the time axis, [channelWidth] the channel column's.
 */
private enum class GuideForm(val minute: Dp, val channelWidth: Dp) {
    TV(4.dp, 176.dp),
    WIDE(4.dp, 176.dp),
    PHONE(3.dp, 56.dp),
}

/** A channel and its programme the details show ([programme] null: a channel without programmes). */
private class Shown(val channel: GuideChannel, val programme: GuideProgramme?)

/**
 * The TV guide (pushed over Home by its shortcut): the days, then the catalogs when there are several, as chips; the
 * shown programme's details (its picture, else its channel's logo; on now, its times and channel; title; genres,
 * runtime and ratings; synopsis; cast and directors); then the time ruler over the channels, each a row of its
 * programmes laid out on the day's time, the one on air resting a step lighter, a line at now. A channel without
 * programmes is one cell that says so. Channels load on as the list nears its end.
 *
 * On TV focus is a fill and the details show the focused programme: the D-pad walks a channel's programmes with Left
 * and Right, the time following the focused one, and the channels with Up and Down; Select on any programme plays its
 * channel. It opens on the first channel's programme on now (on another day, its first), and Down from the chips comes
 * back to the programme focused last, else to that one, never to whichever lies nearest (a programme composed beside
 * the time shown).
 *
 * On phones, tablets and the desktop a programme is picked: the guide opens with the first channel's programme on now
 * picked, and a tap or a click picks another, which takes the primary fill. On tablets and the desktop the details over
 * the channels show it, with Play; a tap or a click on the picked one plays its channel too. On a phone the details
 * aren't over the channels: a tap opens them in a sheet, with Play. A drag sideways (Shift with the wheel, a trackpad's
 * sideways swipe) moves the time while the channel column stays; the channels scroll up and down. A Now chip before
 * the days brings the time back to half an hour before now, on today. The arrow keys move as the D-pad does, and the
 * programme they focus is picked, the time following it.
 *
 * Harness names: `guide.header`, `guide.now`, `guide.day.<index>`, `guide.catalog.<index>`, `guide.details`,
 * `guide.sheet`, `guide.play`, `guide.grid`, `guide.channel.<channel index>`, `guide.logo.<channel index>` (the
 * channel column's cell), `guide.programme.<channel index>.<programme index>`, `guide.empty.<channel index>`, and
 * `guide.selected` on the picked programme's cell too.
 */
@Composable
fun GuideScreen(state: GuideUiState, intents: GuideIntents, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val layout = LocalWindowLayout.current
    val form = when {
        layout.formFactor == FormFactor.TV -> GuideForm.TV
        layout.widthClass == WidthClass.COMPACT -> GuideForm.PHONE
        else -> GuideForm.WIDE
    }
    val focus = rememberFocusMemory()
    // The programme focused last (TV) or picked (elsewhere): its channel's id and its own, null for a channel without.
    var picked by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var sheet by remember { mutableStateOf(false) }
    val toNow = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val onNow: () -> Unit = {
        val today = state.days.firstOrNull { it.today }
        if (today == null || today.selected) toNow.tryEmit(Unit) else intents.selectDay(today.epochDay)
    }
    Column(modifier.fillMaxSize().padding(top = statusBarOverlap)) {
        ScreenHeader(T.guide.title, Modifier.layoutProbe("guide.header", reportsFocus = false), onBack = onBack)
        Choices(state, focus, intents, onNow = if (form == GuideForm.TV) null else onNow)
        when (val body = state.body) {
            GuideBody.Loading -> LoadingState()
            is GuideBody.Failed -> ErrorState(body.text, onRetry = intents.retry)
            is GuideBody.Empty -> EmptyState(body.text, icon = TortugaIcons.Schedule)
            is GuideBody.Grid -> {
                // On TV a programme focused on another day or in another catalog shows nothing, not its channel as
                // empty; elsewhere the first channel's programme the guide opens on stands in for it.
                val shown = shown(state, picked, opening = form != GuideForm.TV)
                if (form != GuideForm.PHONE) {
                    Details(shown, onPlay = shown?.takeIf { form == GuideForm.WIDE }?.let { s -> { intents.play(s.channel.id) } })
                }
                Grid(
                    state, form, focus, intents, toNow,
                    picked = shown.takeIf { form != GuideForm.TV },
                    onFocused = { channel, programme -> picked = channel.id to programme?.id },
                    onPress = { channel, programme ->
                        val current = shown(state, picked, opening = true)
                        val isPicked = current?.channel?.id == channel.id && current.programme?.id == programme?.id
                        if (form == GuideForm.TV || (form == GuideForm.WIDE && isPicked)) {
                            intents.play(channel.id)
                        } else {
                            picked = channel.id to programme?.id
                            if (form == GuideForm.PHONE) sheet = true
                        }
                    },
                )
                if (form == GuideForm.PHONE && sheet && shown != null) {
                    DetailsSheet(
                        shown,
                        onPlay = {
                            sheet = false
                            intents.play(shown.channel.id)
                        },
                        onDismiss = { sheet = false },
                    )
                }
            }
        }
    }
    RestoreFocus(focus, ready = state.body is GuideBody.Grid, fallback = state.channels.firstOrNull()?.let { startKey(it) })
}

/**
 * The channel and programme [picked] names when the day has them; else, with [opening], the first channel's programme
 * the guide opens on (on now, else the next, else its first).
 */
private fun shown(state: GuideUiState, picked: Pair<String, String?>?, opening: Boolean): Shown? {
    val channel = state.channels.firstOrNull { it.id == picked?.first }
    val programme = channel?.programmes?.firstOrNull { it.id == picked?.second }
    if (channel != null && (programme != null || picked?.second == null)) return Shown(channel, programme)
    if (!opening) return null
    val first = state.channels.firstOrNull() ?: return null
    return Shown(first, first.startIndex?.let { first.programmes[it] })
}

/** [onNow]'s chip (touch and pointer), then the day chips, then the catalog chips when there is more than one. */
@Composable
private fun Choices(state: GuideUiState, focus: FocusMemory, intents: GuideIntents, onNow: (() -> Unit)?) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = LocalTortugaDimens.current.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNow != null) {
            item(key = "now") { TortugaChip(T.guide.now, selected = false, onClick = onNow, modifier = Modifier.layoutProbe("guide.now")) }
            item(key = "nowGap") { Spacer(Modifier.width(16.dp)) }
        }
        itemsIndexed(state.days, key = { _, day -> "day:${day.epochDay}" }) { index, day ->
            TortugaChip(
                day.label, day.selected, { intents.selectDay(day.epochDay) },
                Modifier.rememberFocus(focus, "day:${day.epochDay}").layoutProbe("guide.day.$index"),
            )
        }
        if (state.catalogs.size > 1) {
            item(key = "gap") { Spacer(Modifier.width(16.dp)) }
            itemsIndexed(state.catalogs, key = { _, catalog -> "catalog:${catalog.key}" }) { index, catalog ->
                TortugaChip(
                    catalog.label, catalog.selected, { intents.selectCatalog(catalog.key) },
                    Modifier.rememberFocus(focus, "catalog:${catalog.key}").layoutProbe("guide.catalog.$index"),
                    contentLabel = true,
                )
            }
        }
    }
}

/**
 * [shown]'s details beside its picture (TV, tablets, the desktop); with [onPlay], Play at their end. They are as tall
 * as their lines at the app's text size: on now and the times, the title, the info, [SYNOPSIS_LINES] of synopsis, cast
 * and directors (132 dp at the default size), so a synopsis on two lines never pushes the last line out, and the
 * height holds whichever programme they show.
 */
@Composable
private fun Details(shown: Shown?, onPlay: (() -> Unit)?) {
    val dimens = LocalTortugaDimens.current
    val type = MaterialTheme.typography
    val height = with(LocalDensity.current) {
        type.labelLarge.lineHeight.toDp() + type.titleLarge.lineHeight.toDp() + type.bodyMedium.lineHeight.toDp() * (1 + SYNOPSIS_LINES) +
            type.bodySmall.lineHeight.toDp() + DETAILS_LINE_GAP * 4
    }
    Row(
        Modifier.fillMaxWidth().height(height).padding(horizontal = dimens.screenPadding).layoutProbe("guide.details", reportsFocus = false),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (shown == null) return@Row
        Picture(shown, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
        DetailsText(shown, synopsisLines = SYNOPSIS_LINES, Modifier.weight(1f))
        if (onPlay != null) {
            TortugaButton(
                T.detail.play, onPlay, Modifier.align(Alignment.CenterVertically).layoutProbe("guide.play"),
                style = ButtonStyle.PRIMARY, icon = TortugaIcons.PlayArrow,
            )
        }
    }
}

/** A phone's details of [shown]: its picture, its details whole, then Play. */
@Composable
private fun DetailsSheet(shown: Shown, onPlay: () -> Unit, onDismiss: () -> Unit) {
    TortugaSheet(onDismiss = onDismiss, probe = "guide.sheet", label = shown.programme?.title ?: shown.channel.name) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Picture(shown, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            DetailsText(shown, synopsisLines = Int.MAX_VALUE)
        }
        TortugaButton(T.detail.play, onPlay, Modifier.layoutProbe("guide.play"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.PlayArrow, fill = true)
    }
}

/** The programme's thumbnail, else its channel's logo fitted on the surface colour. */
@Composable
private fun Picture(shown: Shown, modifier: Modifier) {
    val thumbnail = shown.programme?.thumbnail
    RemoteImage(
        thumbnail ?: shown.channel.logo,
        contentDescription = null,
        modifier = modifier.clip(MaterialTheme.shapes.medium),
        contentScale = if (thumbnail != null) ContentScale.Crop else ContentScale.Fit,
        blank = TortugaColors.SurfaceHigh,
    )
}

/** On now, the times and the channel; the title; genres, runtime and ratings; the synopsis; cast and directors. */
@Composable
private fun DetailsText(shown: Shown, synopsisLines: Int, modifier: Modifier = Modifier) {
    val (channel, programme) = shown.channel to shown.programme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DETAILS_LINE_GAP)) {
        val muted = TortugaColors.TextMuted
        val line = programme?.headline ?: channel.name
        Text(line, style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(programme?.title ?: channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (programme == null) {
            Text(T.guide.noProgrammes, style = MaterialTheme.typography.bodyMedium, color = muted)
            return@Column
        }
        programme.infoText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        programme.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = synopsisLines, overflow = TextOverflow.Ellipsis) }
        programme.creditsText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The time shown: [shown] minutes into the day at the grid's start, read while laying out and drawing, so a move lays
 * the rows out again without composing them. Focus and the Now chip move it in [SCROLL_MILLIS] ([animateTo]); a drag
 * moves it at once ([dragBy]). [target] is where it is going.
 */
@Stable
private class TimeAxis(start: Float) {
    var target by mutableFloatStateOf(start)
        private set
    var shown by mutableFloatStateOf(start)
        private set
    private var move: Job? = null

    fun animateTo(scope: CoroutineScope, minutes: Float) {
        target = minutes
        move?.cancel()
        move = scope.launch { animate(shown, minutes, animationSpec = tween(SCROLL_MILLIS)) { value, _ -> shown = value } }
    }

    /** Moves by [minutes] at once, within the day's 0 to [latest]; returns how far it moved. */
    fun dragBy(minutes: Float, latest: Float): Float {
        move?.cancel()
        val next = (shown + minutes).coerceIn(0f, latest.coerceAtLeast(shown))
        val moved = next - shown
        shown = next
        target = next
        return moved
    }
}

/**
 * The ruler and the channels. The time shown starts half an hour before now on today, else at the first channel's
 * first programme. On TV it moves when focus lands on a programme that doesn't show well ([scrollFor]); elsewhere a
 * drag moves it, the arrow keys' focus as on TV, and [toNow] back to its start on today. [picked] (not on TV) is the
 * programme that takes the primary fill.
 */
@Composable
private fun Grid(
    state: GuideUiState,
    form: GuideForm,
    focus: FocusMemory,
    intents: GuideIntents,
    toNow: Flow<Unit>,
    picked: Shown?,
    onFocused: (GuideChannel, GuideProgramme?) -> Unit,
    onPress: (GuideChannel, GuideProgramme?) -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    val inputMode = LocalInputModeManager.current
    val visibility = LocalFocusVisibility.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = dimens.screenPadding).layoutProbe("guide.grid", reportsFocus = false)) {
        val visible = ((maxWidth - form.channelWidth) / form.minute).coerceAtLeast(60f)
        val dayMinutes = (state.dayEndMillis - state.dayStartMillis) / MINUTE_MILLIS.toFloat()
        val latest = (dayMinutes - visible).coerceAtLeast(0f)
        val axis = remember(state.dayStartMillis) { TimeAxis(minutes(state, state.openingMillis).coerceIn(0f, latest)) }
        val current by rememberUpdatedState(state)
        val bound by rememberUpdatedState(latest)
        LaunchedEffect(axis) { toNow.collect { axis.animateTo(scope, minutes(current, current.openingMillis).coerceIn(0f, bound)) } }
        val time = { axis.shown }
        // The programmes composed: the time shown and at least as much again either side, so the arrow keys find the
        // next one before the time moves to it; in steps of the time shown, so a drag composes the rows again only as
        // it crosses one.
        val step by remember(axis, visible) { derivedStateOf { floor(axis.shown / visible).toInt() } }
        val window = ((step - 1) * visible)..((step + 3) * visible)
        val minutePx = with(LocalDensity.current) { form.minute.toPx() }
        val drag = rememberScrollableState { delta -> -axis.dragBy(-delta / minutePx, latest) * minutePx }
        val list = rememberLazyListState()
        LaunchedEffect(list, state.hasMore) {
            if (!state.hasMore) return@LaunchedEffect
            snapshotFlow { list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - LOAD_AHEAD } }.first { it }
            intents.loadMore()
        }
        Column(Modifier.fillMaxSize().then(if (form == GuideForm.TV) Modifier else Modifier.scrollable(drag, Orientation.Horizontal))) {
            Ruler(state, form, time)
            LazyColumn(
                Modifier.fillMaxSize().focusEntry(state.channels.firstOrNull()?.let { focus.requester(startKey(it)) }),
                state = list,
                contentPadding = PaddingValues(bottom = dimens.sectionSpacing),
            ) {
                itemsIndexed(state.channels, key = { _, channel -> channel.id }) { index, channel ->
                    ChannelRow(
                        state, form, index, channel, focus, time, window,
                        picked = picked?.takeIf { it.channel.id == channel.id }?.let { it.programme?.id ?: EMPTY_PICKED },
                        onFocused = { programme ->
                            // Away from TV focus counts only where the keys moved it: a click focuses what it presses.
                            if (form == GuideForm.TV || (inputMode.inputMode != InputMode.Touch && visibility.visible)) {
                                onFocused(channel, programme)
                                if (programme != null) {
                                    val to = scrollFor(minutes(state, programme.startMillis), minutes(state, programme.endMillis), axis.target, visible)
                                    axis.animateTo(scope, to.coerceIn(0f, latest))
                                }
                            }
                        },
                        onPress = { programme -> onPress(channel, programme) },
                    )
                }
            }
        }
    }
}

/** The half hours' times over the channels' programmes, moving with them. */
@Composable
private fun Ruler(state: GuideUiState, form: GuideForm, time: () -> Float) {
    Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        Spacer(Modifier.width(form.channelWidth))
        Layout(
            content = {
                state.marks.forEach { mark ->
                    Text(mark.label, style = MaterialTheme.typography.labelMedium, color = TortugaColors.TextMuted, maxLines = 1)
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
        ) { measurables, constraints ->
            val minute = form.minute.toPx()
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val start = time()
                placeables.forEachIndexed { i, placeable ->
                    val x = (minutes(state, state.marks[i].millis) - start) * minute
                    if (x > -placeable.width && x < constraints.maxWidth) placeable.place(x.roundToInt(), (constraints.maxHeight - placeable.height) / 2)
                }
            }
        }
    }
}

/**
 * A channel: its logo and name (on a phone its logo alone, its name in small type while the logo is missing, loading or
 * failed), then its programmes in [window] (minutes into the day) placed on the time shown. [picked]: the id of its
 * programme that is picked, [EMPTY_PICKED] for the channel itself when it has none; null when nothing of it is.
 */
@Composable
private fun ChannelRow(
    state: GuideUiState,
    form: GuideForm,
    index: Int,
    channel: GuideChannel,
    focus: FocusMemory,
    time: () -> Float,
    window: ClosedFloatingPointRange<Float>,
    picked: String?,
    onFocused: (GuideProgramme?) -> Unit,
    onPress: (GuideProgramme?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = CELL_GAP / 2).layoutProbe("guide.channel.$index", reportsFocus = false),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(form.channelWidth).padding(end = if (form == GuideForm.PHONE) 8.dp else 12.dp).layoutProbe("guide.logo.$index", reportsFocus = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (form == GuideForm.PHONE) {
                RemoteImage(channel.logo, contentDescription = channel.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) {
                    Text(
                        channel.name, Modifier.fillMaxSize().wrapContentHeight(), style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                RemoteImage(channel.logo, contentDescription = null, modifier = Modifier.size(40.dp), contentScale = ContentScale.Fit)
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (channel.programmes.isEmpty()) {
            Cell(
                title = channel.name, time = T.guide.noProgrammes, start = T.guide.noProgrammes, onAir = false, past = false,
                picked = picked == EMPTY_PICKED, minute = form.minute,
                modifier = Modifier.weight(1f).fillMaxHeight().rememberFocus(focus, emptyKey(channel)).layoutProbe("guide.empty.$index")
                    .onFocusChanged { if (it.isFocused) onFocused(null) },
                onClick = { onPress(null) },
            )
            return@Row
        }
        val shownProgrammes = channel.programmes.withIndex().filter { (_, p) -> minutes(state, p.endMillis) > window.start && minutes(state, p.startMillis) < window.endInclusive }
        Layout(
            content = {
                // Keyed: as the time shown moves, programmes come into and leave the window, and focus stays on its own.
                shownProgrammes.forEach { (i, programme) ->
                    key(programme.id) {
                        val start = minutes(state, programme.startMillis)
                        Cell(
                            title = programme.title, time = programme.cellTimeText, start = programme.startText, onAir = programme.onAir,
                            past = programme.past,
                            picked = picked == programme.id, minute = form.minute,
                            modifier = Modifier.rememberFocus(focus, programmeKey(channel, programme)).layoutProbe("guide.programme.$index.$i")
                                .onFocusChanged { if (it.isFocused) onFocused(programme) },
                            onClick = { onPress(programme) },
                            textShift = { (time() - start).coerceAtLeast(0f) },
                            nowAt = if (programme.onAir) minutes(state, state.nowMillis) - start else null,
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds().nowLine(state, form.minute, time),
        ) { measurables, constraints ->
            val minute = form.minute.toPx()
            val gap = CELL_GAP.roundToPx()
            val placeables = measurables.mapIndexed { n, measurable ->
                val p = shownProgrammes[n].value
                val width = ((minutes(state, p.endMillis) - minutes(state, p.startMillis)) * minute).roundToInt() - gap
                measurable.measure(Constraints.fixed(width.coerceAtLeast(gap), constraints.maxHeight))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val start = time()
                placeables.forEachIndexed { n, placeable ->
                    placeable.place(((minutes(state, shownProgrammes[n].value.startMillis) - start) * minute).roundToInt(), 0)
                }
            }
        }
    }
}

/**
 * A programme (or a channel without any): a plain fill, the one on air a step lighter, the past ones' text muted;
 * focused or [picked], the primary fill (picked and focused, the focus fill alone: no ring). Its text starts
 * [textShift] minutes in, so a programme that began before the time shown keeps its title in view; one showing too
 * little of itself for that ([MIN_TEXT_WIDTH]) shows no text, not a cut word. Its time is [time], or [start] where the
 * room it shows can't hold [time] whole. The programme on air draws the line at now [nowAt] minutes in, over its fill
 * and under its text, in its text's colour (black on the primary fill). [minute] is a minute's width.
 */
@Composable
private fun Cell(
    title: String,
    time: String,
    start: String,
    onAir: Boolean,
    past: Boolean,
    picked: Boolean,
    minute: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    textShift: () -> Float = { 0f },
    nowAt: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val pickedFill = picked && !(focused && isFocusVisible())
    TortugaSurface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (picked) Modifier.layoutProbe("guide.selected", reportsFocus = false) else Modifier),
        shape = MaterialTheme.shapes.small,
        color = when {
            pickedFill -> MaterialTheme.colorScheme.primary
            onAir -> TortugaColors.SurfaceHighest
            else -> TortugaColors.SurfaceHigh
        },
        contentColor = if (pickedFill) TortugaColors.OnPrimary else MaterialTheme.colorScheme.onSurface,
        grows = false,
    ) {
        val content = LocalContentColor.current
        Column(
            Modifier.fillMaxHeight()
                .drawBehind {
                    val x = (nowAt ?: return@drawBehind) * minute.toPx()
                    drawLine(content, Offset(x, 0f), Offset(x, size.height), strokeWidth = NOW_LINE.toPx())
                }
                .padding(horizontal = 10.dp)
                .layout { measurable, constraints ->
                    val shift = (textShift() * minute.toPx()).roundToInt().coerceIn(0, constraints.maxWidth)
                    val room = constraints.maxWidth - shift
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
                    layout(constraints.maxWidth, placeable.height) { if (room >= MIN_TEXT_WIDTH.roundToPx()) placeable.place(shift, 0) }
                },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (past) content.copy(alpha = 0.6f) else content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val muted = content.copy(alpha = 0.6f)
            Layout(
                content = {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, softWrap = false)
                    Text(start, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            ) { measurables, constraints ->
                val whole = measurables[0].measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                val shown = if (whole.width <= constraints.maxWidth) whole else measurables[1].measure(constraints.copy(minWidth = 0))
                layout(shown.width, shown.height) { shown.place(0, 0) }
            }
        }
    }
}

/**
 * A line across the row at now, behind the programmes: it shows between them and where the channel has none, and the
 * programme on air draws its own part ([Cell]).
 */
private fun Modifier.nowLine(state: GuideUiState, minute: Dp, time: () -> Float): Modifier = drawBehind {
    if (!state.nowInDay) return@drawBehind
    val x = (minutes(state, state.nowMillis) - time()) * minute.toPx()
    if (x in 0f..size.width) drawLine(TortugaColors.Primary, Offset(x, 0f), Offset(x, size.height), strokeWidth = NOW_LINE.toPx())
}

/** Minutes from the day's start to [millis]. */
private fun minutes(state: GuideUiState, millis: Long): Float = (millis - state.dayStartMillis) / MINUTE_MILLIS.toFloat()

/**
 * Where the time shown starts once a programme from [start] to [end] (minutes into the day) has focus, from [scroll]
 * over [visible] minutes: where it is while the programme shows whole or for an hour at least; else just far enough to
 * show it whole, or, longer than the time shown, from its start (coming from the left: up to its end).
 */
internal fun scrollFor(start: Float, end: Float, scroll: Float, visible: Float): Float {
    val shows = minOf(end, scroll + visible) - maxOf(start, scroll)
    if (shows >= minOf(end - start, WELL_SHOWN)) return scroll
    return if (start < scroll) maxOf(start, end - visible) else minOf(start, end - visible)
}

/** The focus key of the programme the guide opens on in [channel]: on now, else the next, else its first. */
private fun startKey(channel: GuideChannel): String {
    val programme = channel.startIndex?.let { channel.programmes[it] } ?: return emptyKey(channel)
    return programmeKey(channel, programme)
}

private fun programmeKey(channel: GuideChannel, programme: GuideProgramme) = "programme:${channel.id}:${programme.id}"

private fun emptyKey(channel: GuideChannel) = "channel:${channel.id}"

/** [ChannelRow]'s `picked` for a channel without programmes, picked itself. */
private const val EMPTY_PICKED = ""

private const val MINUTE_MILLIS = 60_000L
private const val WELL_SHOWN = 60f
private const val SCROLL_MILLIS = 200
private const val LOAD_AHEAD = 4
private val ROW_HEIGHT: Dp = 60.dp
private val RULER_HEIGHT: Dp = 28.dp
private const val SYNOPSIS_LINES = 2
private val DETAILS_LINE_GAP: Dp = 2.dp
private val CELL_GAP: Dp = 4.dp
private val MIN_TEXT_WIDTH: Dp = 48.dp
private val NOW_LINE: Dp = 2.dp
