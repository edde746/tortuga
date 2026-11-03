package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.session.episodeLine
import com.edde746.tortuga.presentation.board.shownProgress
import com.edde746.tortuga.presentation.detail.DetailText
import com.edde746.tortuga.presentation.detail.hidesSpoilers
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The Calendar screen (stremio-core `models/calendar.rs`): a month of the episodes the installed addons date for the
 * library's titles ([EpisodeFeed] on the calendar extra), this month here first. An episode is on the day of its UTC
 * release date, the day addons mean (Cinemeta dates every episode at 05:00 UTC); "today" is the device's, and moves on
 * at its midnight while the screen is open, the month shown with it when it showed this month. An episode two addons
 * both list shows once, the first one's. On a kids profile ([kids]) a series' episodes show once its verdict is known
 * and it passes. With the profile's [settings] blurring unwatched episodes, an episode's still and description are
 * blurred while the episode is neither watched nor started ([hidesSpoilers]), as on Detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarStore internal constructor(
    feed: EpisodeFeed,
    library: Flow<List<LibraryItem>>,
    private val host: CalendarHost,
    private val clock: Clock,
    parentScope: CoroutineScope,
    /** The profile's kids rules; without them every series stays as it is. */
    private val kids: KidsGate? = null,
    /** The profile's settings, for [ProfileSettings.blurUnwatched]; without them no still is blurred. */
    settings: Flow<ProfileSettings>? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var answers: EpisodeFeedState? = null
    private var hasTitles: Boolean? = null
    private var shown: Month = Month.of(today())

    /** The answers' metas, each with its videos by release day ([byReleaseDay]): sorted once per meta while answered. */
    private var dated: List<Pair<MetaItem, List<Video>>> = emptyList()

    /** The library's marks by series id while the profile blurs unwatched episodes' stills; null while it doesn't. */
    private var blurring: Map<String, SeriesMarks>? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<CalendarUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            library.map { items -> items.any { it.onCalendar } }.distinctUntilChanged().collect {
                hasTitles = it
                publish()
            }
        }
        scope.launch {
            feed.state.collect {
                answers = it
                publish()
            }
        }
        kids?.let { gate -> scope.launch { gate.changes.collect { publish() } } }
        settings?.let { profile ->
            scope.launch {
                profile.map { it.blurUnwatched }.distinctUntilChanged()
                    .flatMapLatest { on -> if (on) library.map(::seriesMarks) else flowOf(null) }
                    .distinctUntilChanged()
                    .collect {
                        blurring = it
                        publish()
                    }
            }
        }
        scope.launch {
            while (true) {
                val now = clock.now().toEpochMilliseconds()
                val month = Month.of(localDay(now, ::localUtcOffsetSeconds))
                delay(nextLocalMidnight(now, ::localUtcOffsetSeconds) - now)
                if (shown == month) shown = Month.of(today())
                publish()
            }
        }
    }

    fun previousMonth() {
        shown = shown.plus(-1)
        publish()
    }

    fun nextMonth() {
        shown = shown.plus(1)
        publish()
    }

    /** Back to this month here. */
    fun thisMonth() {
        shown = Month.of(today())
        publish()
    }

    fun open(episode: CalendarEpisode) = host.openEpisode(episode.type, episode.metaId, episode.videoId)

    fun release() {
        scope.cancel()
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): CalendarUiState {
        val today = today()
        val first = shown.firstDay
        val end = shown.plus(1).firstDay
        val metas = answers?.metas.orEmpty()
        dated = metas.map { meta -> dated.firstOrNull { it.first === meta } ?: (meta to byReleaseDay(meta)) }
        // An episode two addons both list is the first one's: the metas already gone through, by series id.
        val before = HashMap<String, MutableList<MetaItem>>()
        val byDay = HashMap<Long, MutableList<CalendarEpisode>>()
        for ((meta, videos) in dated) {
            if (kids != null && !kids.shows(meta.type, meta.id, meta.preview)) continue
            val earlier = before.getOrPut(meta.id) { mutableListOf() }
            val marks = blurring?.let { it[meta.id] ?: SeriesMarks(null, null) }
            val watched = marks?.watched?.let { WatchedBitField.of(it, meta) }
            for (video in videos.subList(videos.countWhile { it.releaseDay < first }, videos.countWhile { it.releaseDay < end })) {
                if (earlier.none { it.indexOfVideo(video.id) >= 0 }) {
                    val blurred = marks != null &&
                        hidesSpoilers(blurUnwatched = true, watched = watched?.isWatched(video.id) == true, started = marks.started == video.id)
                    byDay.getOrPut(video.releaseDay) { mutableListOf() } += episode(meta, video, blurred)
                }
            }
            earlier += meta
        }
        val days = (first until end).map { day ->
            val count = byDay[day]?.size ?: 0
            val label = dayLabel(day, today)
            CalendarDay(
                day = (day - first + 1).toInt(),
                today = day == today,
                episodes = count,
                accessibilityLabel = if (count == 0) label else listOf(label, T.calendar.episodes(count)).joinToString(T.detail.listSeparator),
            )
        }
        val agenda = byDay.keys.sorted().map { day ->
            CalendarAgendaDay((day - first + 1).toInt(), dayLabel(day, today), day == today, byDay.getValue(day))
        }
        // A kids profile's series waiting for their verdicts count as loading.
        val loading = (answers?.loading ?: true) || (kids != null && metas.any { kids.verdictNow(it.type, it.id, it.preview) == null })
        return CalendarUiState(
            title = T.calendar.title,
            month = localizedDate(first, "yMMMM", T.locale),
            weekdays = (0 until 7).map { localizedDate(A_MONDAY + it, "EEE", T.locale) },
            leadingDays = (first - A_MONDAY).mod(7L).toInt(),
            days = days,
            body = when {
                agenda.isNotEmpty() -> CalendarBody.Agenda(agenda)
                hasTitles == false -> CalendarBody.Empty(T.calendar.emptyLibrary)
                answers?.asked == false -> CalendarBody.Empty(T.calendar.noAddon)
                loading -> CalendarBody.Loading
                else -> CalendarBody.Empty(T.calendar.emptyMonth)
            },
            currentMonth = shown == Month.of(today),
        )
    }

    private fun episode(meta: MetaItem, video: Video, blurred: Boolean): CalendarEpisode {
        val overview = video.overview?.trim()?.takeIf { it.isNotEmpty() }
        // Addons give no runtime per video: an episode's is its series' (Detail's episode line has it so).
        val runtime = if (video.seriesInfo != null) DetailText.runtimePart(meta.preview.runtime) else null
        return CalendarEpisode(
            type = meta.type,
            metaId = meta.id,
            videoId = video.id,
            series = meta.name,
            detail = DetailText.join(listOfNotNull(video.episodeLine(), runtime)),
            overview = overview,
            still = video.thumbnail,
            art = meta.preview.background ?: meta.preview.poster,
            blurred = blurred,
            hiddenOverviewLabel = if (blurred && overview != null) T.detail.overviewHidden else null,
        )
    }

    /**
     * A series' marks as the blur reads them: its watched episodes ([LibraryItemState.watchedVideos]) and the episode
     * it has a position in ([shownProgress]), if any. Equal while only the position moves within that episode.
     */
    private data class SeriesMarks(val watched: String?, val started: String?)

    private fun seriesMarks(items: List<LibraryItem>): Map<String, SeriesMarks> =
        items.associate { it.id to SeriesMarks(it.state.watchedVideos, it.state.videoId?.takeIf { _ -> it.shownProgress() != null }) }

    /** [meta]'s dated videos (the first of each id, dated or not) by their release day, in [MetaItem.videos] order within a day. */
    private fun byReleaseDay(meta: MetaItem): List<Video> =
        meta.videos.distinctBy { it.id }.filter { it.released != null }.sortedBy { it.releaseDay }

    private fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> T.calendar.today
        today + 1 -> T.calendar.tomorrow
        else -> localizedDate(day, "MMMMEEEEd", T.locale)
    }

    /** Today here: the device's date now, as days since 1970-01-01. */
    private fun today(): Long = localDay(clock.now().toEpochMilliseconds(), ::localUtcOffsetSeconds)

    private data class Month(val year: Long, val month: Int) {
        val firstDay: Long get() = epochDay(year, month, 1)

        fun plus(months: Int): Month {
            val index = year * 12 + (month - 1) + months
            return Month(index.floorDiv(12L), index.mod(12L).toInt() + 1)
        }

        companion object {
            fun of(day: Long): Month = civilDate(day).let { (year, month, _) -> Month(year, month) }
        }
    }

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "calendar"

        /** 1970-01-05, a Monday: the weeks start on Mondays (stremio-core's month grid). */
        private const val A_MONDAY = 4L
    }
}

/** The day of a dated video's UTC release date, as days since 1970-01-01. */
private val Video.releaseDay: Long
    get() = checkNotNull(released) { "an undated video" }.toEpochMilliseconds().floorDiv(MILLIS_PER_DAY)
