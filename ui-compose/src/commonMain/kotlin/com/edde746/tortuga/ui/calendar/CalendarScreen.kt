package com.edde746.tortuga.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.calendar.CalendarAgendaDay
import com.edde746.tortuga.presentation.calendar.CalendarBody
import com.edde746.tortuga.presentation.calendar.CalendarDay
import com.edde746.tortuga.presentation.calendar.CalendarEpisode
import com.edde746.tortuga.presentation.calendar.CalendarStore
import com.edde746.tortuga.presentation.calendar.CalendarUiState
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.GroupSpacing
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.detail.EpisodeListRow
import com.edde746.tortuga.ui.detail.EpisodeThumbnail
import com.edde746.tortuga.ui.detail.RowInset
import com.edde746.tortuga.ui.detail.ThumbnailShape
import com.edde746.tortuga.ui.detail.listThumbnailWidth
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.StayInViewSpec
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlinx.coroutines.launch

/** The Calendar screen of [app] ([Route.Calendar]): its [CalendarStore] lives while the route is on the back stack. */
@Composable
fun CalendarRoute(app: AppSession) {
    val navigator = app.navigator
    // Once per appearance: the entry still draws while it animates out after its route left the stack (its store
    // released), and an entry first composed after that gets no store and draws nothing.
    val store = remember(app) {
        navigator.retain(Route.Calendar, CalendarStore.KEY, create = { app.calendarStore() }, release = CalendarStore::release)
    } ?: return
    val state by store.state.collectAsState()
    val intents = remember(store) { CalendarIntents(store::previousMonth, store::nextMonth, store::thisMonth, store::open) }
    CalendarScreen(state, intents, onBack = if (navigator.canGoBack) ({ navigator.back() }) else null)
}

/** What the Calendar screen asks of [CalendarStore]. */
class CalendarIntents(
    val previousMonth: () -> Unit,
    val nextMonth: () -> Unit,
    val thisMonth: () -> Unit,
    val open: (CalendarEpisode) -> Unit,
)

/**
 * The Calendar (pushed over Home or Library): the month's name with Today (away from this month, back to it) and the arrows to the
 * previous and next months, the month's grid of days, Monday first, today's number in a primary circle and up to three
 * dots under a day with episodes, then the month's days with episodes, each under its heading ("Today", "Tomorrow",
 * "Thursday, October 29") over its episodes in Detail's episode rows ([EpisodeListRow], no card): the episode's still
 * (else its series' landscape art, sharp, [CalendarEpisode.art]), its series, its line ("S1E3 · Title · 46 min") and
 * two lines of its description, the still and the description blurred while the profile hides spoilers. On TV the
 * focused row's still takes the focus ring and grows, as Detail's episode cards do there; elsewhere the row takes
 * Detail's fill. The month's bar, the grid, the headings and the stills share one leading edge. A press on a day with
 * episodes scrolls its heading to the top, and from a remote or a keyboard moves focus to its first episode; a press
 * on an episode opens its series on it. Without days with episodes, under the grid a spinner while the addons answer,
 * else the store's text saying why ([CalendarUiState.body]). At a large font scale a row's text goes under its still,
 * whole. Back leaves it.
 *
 * Focus: on TV every day takes it, so the remote walks the grid a day or a week at a time; elsewhere the days with
 * episodes do. The month's bar and the grid are each entered where focus left them, else on their first control (Today,
 * else Previous month) and their entry day ([entryDay]), never on the element nearest the move. The screen
 * opens on the entry day, else the first episode, else the month's bar.
 *
 * Harness names: `calendar.header`, `calendar.list`, `calendar.month`, `calendar.today`, `calendar.previous`,
 * `calendar.next`, `calendar.grid`, `calendar.day.<day>`, `calendar.agenda.<day>`, `calendar.episode.<video id>`,
 * `calendar.loading`, `calendar.empty`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(state: CalendarUiState, intents: CalendarIntents, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val dimens = LocalTortugaDimens.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = rememberFocusMemory()
    val inputMode = LocalInputModeManager.current
    val everyDay = LocalWindowLayout.current.formFactor == FormFactor.TV
    val entry = entryDay(state, everyDay)
    val onDay = { day: Int ->
        val episode = state.agenda.firstOrNull { it.day == day }?.let { episodeKey(it, it.episodes.first()) }
        scope.launch {
            list.animateScrollToItem(agendaIndex(state, day))
            // A remote or a keyboard goes on to the day's first episode; a touch or a click leaves focus alone.
            if (episode != null && inputMode.inputMode == InputMode.Keyboard) {
                repeat(EPISODE_FOCUS_FRAMES) {
                    if (focus.focus(episode)) return@launch
                    withFrameNanos { }
                }
            }
        }
        Unit
    }
    // A day or an episode already wholly in view keeps its place as focus lands on it: Android TV's pivot would scroll
    // the month's bar away on opening.
    val platformScroll = LocalBringIntoViewSpec.current
    val stayInView = remember(platformScroll) { StayInViewSpec(platformScroll) }
    Column(modifier.fillMaxSize().padding(top = statusBarOverlap)) {
        ScreenHeader(state.title, Modifier.contentColumn().layoutProbe("calendar.header", reportsFocus = false), onBack = onBack)
        CompositionLocalProvider(LocalBringIntoViewSpec provides stayInView) {
            LazyColumn(
                modifier = Modifier.contentColumn().fillMaxSize().layoutProbe("calendar.list", reportsFocus = false),
                state = list,
                // The rows reach their inset past the screen's padding, so their stills line up with the grid.
                contentPadding = PaddingValues(start = dimens.screenPadding - RowInset, end = dimens.screenPadding - RowInset, bottom = dimens.sectionSpacing),
            ) {
                item(key = "month") { MonthBar(state, intents, focus, Modifier.padding(horizontal = RowInset)) }
                item(key = "grid") { MonthGrid(state, focus, everyDay, entry, onDay, Modifier.padding(horizontal = RowInset)) }
                when (val body = state.body) {
                    CalendarBody.Loading -> item(key = "loading") { LoadingState(Modifier.fillParentMaxHeight(0.4f).layoutProbe("calendar.loading")) }
                    is CalendarBody.Empty -> item(key = "empty") {
                        EmptyState(body.text, Modifier.fillParentMaxHeight(0.4f).layoutProbe("calendar.empty"), icon = TortugaIcons.CalendarToday)
                    }
                    is CalendarBody.Agenda -> for (day in body.days) {
                        item(key = "day:${day.day}") { DayHeading(day) }
                        items(day.episodes, key = { episode -> episodeKey(day, episode) }) { episode ->
                            EpisodeRow(episode, focus, episodeKey(day, episode), artworkFocus = everyDay) { intents.open(episode) }
                        }
                    }
                }
            }
        }
    }
    val first = state.agenda.firstOrNull()
    val fallback = entry?.let { dayKey(it.day) }
        ?: first?.let { episodeKey(it, it.episodes.first()) }
        ?: if (state.currentMonth) PREVIOUS_KEY else THIS_MONTH_KEY
    RestoreFocus(focus, ready = true, fallback = fallback)
}

private fun episodeKey(day: CalendarAgendaDay, episode: CalendarEpisode) = "episode:${day.day}:${episode.metaId}:${episode.videoId}"

private fun dayKey(day: Int) = "day:$day"

/**
 * The day focus enters the grid on: the first with episodes from today on (from the 1st away from this month), else
 * the month's first with episodes; where [everyDay] takes focus and none has episodes, today, else the 1st.
 */
private fun entryDay(state: CalendarUiState, everyDay: Boolean): CalendarDay? {
    val fromToday = state.days.dropWhile { state.currentMonth && !it.today }
    return fromToday.firstOrNull { it.episodes > 0 }
        ?: state.days.firstOrNull { it.episodes > 0 }
        ?: fromToday.firstOrNull()?.takeIf { everyDay }
}

/** The list's index of [day]'s heading: after the month bar and the grid, and each earlier day's heading and rows. */
private fun agendaIndex(state: CalendarUiState, day: Int): Int =
    2 + state.agenda.takeWhile { it.day != day }.sumOf { 1 + it.episodes.size }

/**
 * The month's name, Today away from this month, and the arrows; the name wraps rather than push the arrows out. Focus
 * enters it on the control it left, else Today, else Previous month. Today pressed from a remote or a
 * keyboard hands focus to Previous month, the bar's first control once it leaves: dropped, Android would give it to
 * the screen's first element, the back button.
 */
@Composable
private fun MonthBar(state: CalendarUiState, intents: CalendarIntents, focus: FocusMemory, modifier: Modifier) {
    val inputMode = LocalInputModeManager.current
    Row(
        modifier.fillMaxWidth().padding(bottom = 8.dp).focusEntry(focus.requester(if (state.currentMonth) PREVIOUS_KEY else THIS_MONTH_KEY)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            state.month,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).semantics { heading() }.layoutProbe("calendar.month"),
        )
        if (!state.currentMonth) {
            val thisMonth = {
                if (inputMode.inputMode == InputMode.Keyboard) focus.focus(PREVIOUS_KEY)
                intents.thisMonth()
            }
            TortugaButton(T.calendar.today, thisMonth, Modifier.rememberFocus(focus, THIS_MONTH_KEY).layoutProbe("calendar.today"), style = ButtonStyle.TEXT)
        }
        TortugaIconButton(TortugaIcons.ChevronLeft, T.calendar.previousMonth, intents.previousMonth, Modifier.rememberFocus(focus, PREVIOUS_KEY).layoutProbe("calendar.previous"), style = IconButtonStyle.PLAIN)
        TortugaIconButton(TortugaIcons.ChevronRight, T.calendar.nextMonth, intents.nextMonth, Modifier.rememberFocus(focus, NEXT_KEY).layoutProbe("calendar.next"), style = IconButtonStyle.PLAIN)
    }
}

/**
 * The weekdays' names, then the month's weeks: [onDay] for a day with episodes. With [everyDay] each day takes focus,
 * else the days with episodes; focus enters on the day it left, else [entry].
 */
@Composable
private fun MonthGrid(state: CalendarUiState, focus: FocusMemory, everyDay: Boolean, entry: CalendarDay?, onDay: (Int) -> Unit, modifier: Modifier) {
    val cells: List<CalendarDay?> = List(state.leadingDays) { null } + state.days
    Column(
        modifier.fillMaxWidth().padding(bottom = 8.dp).layoutProbe("calendar.grid", reportsFocus = false).focusEntry(entry?.let { focus.requester(dayKey(it.day)) }),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            for (weekday in state.weekdays) {
                Text(
                    weekday,
                    style = MaterialTheme.typography.labelMedium,
                    color = TortugaColors.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {},
                )
            }
        }
        for (week in cells.chunked(DAYS_IN_WEEK)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (index in 0 until DAYS_IN_WEEK) {
                    val day = week.getOrNull(index)
                    Box(Modifier.weight(1f)) { if (day != null) DayCell(day, everyDay || day.episodes > 0, focus, onDay) }
                }
            }
        }
    }
}

/**
 * A day: its number (today's in a primary circle) and a dot per episode, up to three; with [focusable] a button, which
 * does [onDay] when the day has episodes. On a focused day's light fill the colours follow its dark content.
 */
@Composable
private fun DayCell(day: CalendarDay, focusable: Boolean, focus: FocusMemory, onDay: (Int) -> Unit) {
    val body: @Composable () -> Unit = {
        val content = LocalContentColor.current
        val onLight = content.luminance() < 0.5f
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = day.accessibilityLabel },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                Modifier.sizeIn(minWidth = 32.dp, minHeight = 32.dp)
                    .then(if (day.today) Modifier.background(if (onLight) TortugaColors.OnPrimary else TortugaColors.Primary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.day.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (day.today || day.episodes > 0) FontWeight.SemiBold else FontWeight.Normal),
                    color = when {
                        day.today -> if (onLight) TortugaColors.Primary else TortugaColors.OnPrimary
                        day.episodes > 0 -> content
                        else -> content.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(minOf(day.episodes, MAX_DOTS)) { Box(Modifier.size(5.dp).background(content, CircleShape)) }
            }
        }
    }
    val probe = Modifier.layoutProbe("calendar.day.${day.day}")
    if (focusable) {
        TortugaSurface(
            onClick = { if (day.episodes > 0) onDay(day.day) },
            modifier = Modifier.rememberFocus(focus, dayKey(day.day)).then(probe),
            shape = DayShape,
            color = Color.Transparent,
            grows = false,
        ) { body() }
    } else {
        Box(probe) { body() }
    }
}

/** A day's heading over its episodes, from the stills' edge: today's in the primary colour. */
@Composable
private fun DayHeading(day: CalendarAgendaDay) {
    Text(
        day.label,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (day.today) TortugaColors.Primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = RowInset, top = GroupSpacing, bottom = 6.dp).semantics { heading() }.layoutProbe("calendar.agenda.${day.day}"),
    )
}

/**
 * An episode as Detail's episode row ([EpisodeListRow]): its still ([EpisodeThumbnail]: the episode's own, blurred
 * while [CalendarEpisode.blurred], else its series' art), its series, its line, its description (blurred past reading
 * while the profile hides spoilers). No ⋮: a press opens its series on it, where its menu is. With [artworkFocus] (TV)
 * the still shows focus as Detail's episode cards there do ([focusRing]: its ring, and the growth).
 */
@Composable
private fun EpisodeRow(episode: CalendarEpisode, focus: FocusMemory, key: String, artworkFocus: Boolean, onOpen: () -> Unit) {
    val width = listThumbnailWidth()
    EpisodeListRow(
        title = episode.series,
        infoLine = episode.detail,
        overview = episode.overview,
        hiddenOverviewLabel = episode.hiddenOverviewLabel,
        onClick = onOpen,
        modifier = Modifier.rememberFocus(focus, key).layoutProbe("calendar.episode.${episode.videoId}"),
        artworkFocus = artworkFocus,
    ) { focused ->
        EpisodeThumbnail(
            episode.still,
            episode.blurred,
            Modifier.width(width).then(if (artworkFocus) Modifier.focusRing(ThumbnailShape, focused) else Modifier),
            fallbackUrl = episode.art,
        )
    }
}

private const val DAYS_IN_WEEK = 7
private const val MAX_DOTS = 3
private val DayShape = RoundedCornerShape(12.dp)

// The month bar's controls in the screen's focus memory.
private const val THIS_MONTH_KEY = "thisMonth"
private const val PREVIOUS_KEY = "previous"
private const val NEXT_KEY = "next"

/** Frames a day's press waits for its first episode's row to compose before giving up moving focus there. */
private const val EPISODE_FOCUS_FRAMES = 30
