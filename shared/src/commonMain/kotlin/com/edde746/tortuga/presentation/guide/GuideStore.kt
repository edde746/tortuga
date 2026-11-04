package com.edde746.tortuga.presentation.guide

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Programme
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.presentation.calendar.localDay
import com.edde746.tortuga.presentation.detail.DetailText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The TV guide ([GuideUiState]), by the addon SDK's `docs/epg.md` and stremio-core's `LiveTvGuide`:
 * - Its catalogs are the guide catalogs of the installed addons ([Guides.catalogs]); it opens on the first, on today.
 * - A day is local: its window runs from its local midnight to the next, and it asks for every UTC day (`date`) that
 *   window overlaps, merging their channels ([Guides.schedule]). A page may answer `metasDetailed` or plain `metas`.
 * - A catalog declaring `skip` has pages: [loadMore] asks each UTC day for the page after its last, `skip` being the
 *   channels that day's pages sent; an empty page ends that day's, a short one doesn't.
 * - While pages are on their way the channels shown stay; they change once all have come.
 * - Every minute it moves its now on; every [REFRESH] it asks for the day's pages again, as the SDK says clients do.
 * - A channel plays by its own id, never a programme's ([play]).
 */
class GuideStore internal constructor(
    private val installedAddons: Flow<List<Descriptor>>,
    private val aggregator: AddonAggregator,
    private val host: GuideHost,
    parentScope: CoroutineScope,
    private val now: () -> Instant,
    /** The device time zone's offset from UTC at an instant (milliseconds since the epoch), in seconds. */
    private val utcOffset: (Long) -> Int,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /**
     * One page of the day: its UTC [date], its [request], and its answer, or [Loadable.Loading]. [previous] is the
     * answer a refresh asks again for, which stays should the new one fail.
     */
    private class Page(val date: String, val skip: Int, val request: ResourceRequest, val previous: Loadable.Ready<List<MetaItem>>? = null) {
        var state: Loadable<List<MetaItem>> = Loadable.Loading
    }

    private var addonsKnown = false
    private var addonsUnreadable = false
    private var guides: List<GuideCatalog> = emptyList()
    private var selectedKey: String? = null
    private var day: Long = localDay(now().toEpochMilliseconds(), utcOffset)
    private var pages: List<Page> = emptyList()
    private var loads = SupervisorJob(scope.coroutineContext[Job])
    private var loadedAt: Instant? = null

    /** The day's schedule as last merged: kept while pages are on their way. */
    private var channels: List<GuideChannel> = emptyList()

    /** The marks last made ([marks]), by their window's start and language. */
    private var madeMarks: Pair<Pair<Long, String>, List<GuideMark>>? = null

    private val mutableState = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            readingAddons(TAG) { installedAddons.collect(::onAddons) } ?: run {
                addonsUnreadable = true
                publish()
            }
        }
        scope.launch {
            while (true) {
                delay(MINUTE_MILLIS - now().toEpochMilliseconds().mod(MINUTE_MILLIS))
                val loaded = loadedAt
                if (loaded != null && now() - loaded >= REFRESH && pages.none { it.state == Loadable.Loading }) refresh()
                publish()
            }
        }
        publish()
    }

    private val selected: GuideCatalog? get() = guides.firstOrNull { it.key == selectedKey }

    /** Shows the guide catalog [key] of [GuideUiState.catalogs]. */
    fun selectCatalog(key: String) {
        if (key == selectedKey || guides.none { it.key == key }) return
        selectedKey = key
        load()
    }

    /** Shows [epochDay] of [GuideUiState.days]. */
    fun selectDay(epochDay: Long) {
        if (epochDay == day) return
        day = epochDay
        load()
    }

    /** Asks each UTC day with more pages for its next one ([GuideUiState.hasMore]). */
    fun loadMore() {
        val next = nextPages()
        if (next.isEmpty()) return
        pages = pages + next
        next.forEach(::fetch)
        publish()
    }

    /** Asks again for the pages that failed. */
    fun retry() {
        val failed = pages.filter { it.state is Loadable.Error && it.state != Loadable.Error(AddonError.EmptyContent) }
        failed.forEach {
            it.state = Loadable.Loading
            fetch(it)
        }
        if (failed.isNotEmpty()) publish()
    }

    /** Plays the channel [channelId] of [GuideUiState.channels] ([GuideHost.playChannel]). */
    fun play(channelId: String) {
        val channel = channels.firstOrNull { it.id == channelId } ?: return
        host.playChannel(channel.preview)
    }

    fun release() {
        scope.cancel()
    }

    private fun onAddons(addons: List<Descriptor>) {
        val previous = selected
        guides = Guides.catalogs(addons)
        addonsKnown = true
        // The selected installation's catalog stays selected, its key moved should another installation come or go.
        val same = previous?.let { guides.firstOrNull(it::sameAs) }
        if (previous != null && same != null && same.addon == previous.addon && same.catalog == previous.catalog) {
            selectedKey = same.key
            publish()
            return
        }
        selectedKey = same?.key ?: guides.firstOrNull { it.key == previous?.key }?.key ?: guides.firstOrNull()?.key
        load()
    }

    /** The selected day's window: its local midnight to the next. */
    private fun window(): Pair<Long, Long> = Guides.dayStart(day, utcOffset) to Guides.dayStart(day + 1, utcOffset)

    /** Asks for the first page of every UTC day of the selected day, the channels shown going. */
    private fun load() {
        loads.cancel()
        loads = SupervisorJob(scope.coroutineContext[Job])
        channels = emptyList()
        val guide = selected
        val (start, end) = window()
        pages = guide?.let { Guides.utcDates(start, end).map { date -> Page(date, 0, guide.request(date)) } }.orEmpty()
        loadedAt = now()
        pages.forEach(::fetch)
        publish()
    }

    /** Asks for the day's pages again, the channels shown staying until they have come. */
    private fun refresh() {
        loads.cancel()
        loads = SupervisorJob(scope.coroutineContext[Job])
        pages = pages.map { Page(it.date, it.skip, it.request, it.state as? Loadable.Ready ?: it.previous) }
        loadedAt = now()
        pages.forEach(::fetch)
    }

    private fun fetch(page: Page) {
        val guide = selected ?: return
        scope.launch(loads) {
            aggregator.load(page.request, guide.addon.manifest.name, ContentKind.GUIDE) { it }.collect { state ->
                if (state == Loadable.Loading) return@collect
                page.state = if (state is Loadable.Error) page.previous ?: state else state
                if (pages.none { it.state == Loadable.Loading }) merge()
                publish()
            }
        }
    }

    /** Each UTC day's next page: for a catalog with pages, after a day's last page came with channels. */
    private fun nextPages(): List<Page> {
        val guide = selected ?: return emptyList()
        if (!guide.pages || pages.any { it.state == Loadable.Loading }) return emptyList()
        return pages.groupBy { it.date }.mapNotNull { (date, own) ->
            val last = own.last().state as? Loadable.Ready ?: return@mapNotNull null
            if (last.content.isEmpty()) return@mapNotNull null
            val skip = own.sumOf { (it.state as? Loadable.Ready)?.sent ?: 0 }
            Page(date, skip, guide.request(date, skip))
        }
    }

    private fun merge() {
        val (start, end) = window()
        val ready = pages.mapNotNull { (it.state as? Loadable.Ready)?.content }
        val offset = utcOffset
        val locale = T.locale
        channels = Guides.schedule(ready, Instant.fromEpochMilliseconds(start), Instant.fromEpochMilliseconds(end)).map { schedule ->
            val preview = schedule.channel
            GuideChannel(
                id = preview.id,
                type = preview.type,
                name = preview.name,
                logo = preview.logo ?: preview.poster,
                programmes = schedule.programmes.map { (video, programme) -> programme(video.id, video.title, video.overview, video.thumbnail, programme, preview.name, offset, locale) },
                onNow = null,
                next = null,
                startIndex = null,
                preview = preview,
            )
        }
    }

    private fun programme(
        id: String,
        title: String,
        overview: String?,
        thumbnail: String?,
        programme: Programme,
        channelName: String,
        offset: (Long) -> Int,
        locale: String,
    ): GuideProgramme {
        fun time(instant: Instant): String = instant.toEpochMilliseconds().let { localizedTime(it, offset(it), locale) }
        // The addon's words (genres, runtime text, release info, ratings) isolated, as the info line joins them.
        val info = programme.genres.map(::isolate) + listOfNotNull(
            DetailText.runtimePart(programme.runtime),
            programme.releaseInfo?.let(::isolate),
        ) + programme.ratings.map { isolate(it.value) }
        val startText = time(programme.start)
        val timeText = T.guide.timeRange(startText, time(programme.end))
        return GuideProgramme(
            id = id,
            title = title,
            startMillis = programme.start.toEpochMilliseconds(),
            endMillis = programme.end.toEpochMilliseconds(),
            timeText = timeText,
            startText = startText,
            cellTimeText = if (programme.end - programme.start <= START_ONLY) startText else timeText,
            overview = overview?.trim()?.takeIf { it.isNotEmpty() },
            thumbnail = thumbnail,
            infoText = DetailText.join(info),
            creditsText = DetailText.join(
                listOfNotNull(
                    programme.cast.takeIf { it.isNotEmpty() }?.let { names -> T.guide.cast(names.joinToString(T.detail.listSeparator) { isolate(it) }) },
                    programme.directors.takeIf { it.isNotEmpty() }?.let { names -> T.guide.directors(names.joinToString(T.detail.listSeparator) { isolate(it) }) },
                ),
            ),
            onAir = false,
            past = false,
            headline = headline(timeText, channelName, onAir = false),
        )
    }

    /** [GuideProgramme.headline]: "On now" while on air, its time, its channel's name isolated. */
    private fun headline(timeText: String, channelName: String, onAir: Boolean): String =
        checkNotNull(DetailText.join(listOfNotNull(T.guide.onNow.takeIf { onAir }, timeText, isolate(channelName))))

    /** This programme as it stands at now: [onAir] and [past] and its headline; itself while they stay. */
    private fun GuideProgramme.at(onAir: Boolean, past: Boolean, channelName: String): GuideProgramme =
        if (onAir == this.onAir && past == this.past) this else copy(onAir = onAir, past = past, headline = headline(timeText, channelName, onAir))

    private fun publish() {
        val nowMillis = now().toEpochMilliseconds()
        val today = localDay(nowMillis, utcOffset)
        val (start, end) = window()
        val loading = pages.any { it.state == Loadable.Loading }
        val firstLoading = pages.any { it.skip == 0 && it.state == Loadable.Loading }
        val shown = channels.map { channel ->
            val (onNow, next) = Guides.nowAndNext(channel.programmes, nowMillis)
            val programmes = channel.programmes.mapIndexed { i, programme -> programme.at(i == onNow, programme.endMillis <= nowMillis, channel.name) }
            val startIndex = onNow ?: next ?: if (programmes.isEmpty()) null else 0
            val same = onNow == channel.onNow && next == channel.next && startIndex == channel.startIndex &&
                programmes.indices.all { programmes[it] === channel.programmes[it] }
            if (same) channel else channel.copy(programmes = programmes, onNow = onNow, next = next, startIndex = startIndex)
        }
        channels = shown
        val failure = pages.filter { it.skip == 0 }.mapNotNull { (it.state as? Loadable.Error)?.error }.firstOrNull { it != AddonError.EmptyContent }
        val body = when {
            addonsUnreadable -> GuideBody.Failed(T.guide.failed(T.addons.unreadable))
            !addonsKnown -> GuideBody.Loading
            guides.isEmpty() -> GuideBody.Empty(T.guide.noGuide)
            shown.isNotEmpty() -> GuideBody.Grid(shown)
            firstLoading -> GuideBody.Loading
            failure != null && pages.none { it.state is Loadable.Ready } -> GuideBody.Failed(T.guide.failed(failure.message))
            else -> GuideBody.Empty(T.guide.noChannels)
        }
        mutableState.value = GuideUiState(
            catalogs = guides.map { GuideCatalogChoice(it.key, T.guide.catalog(isolate(it.catalog.name ?: it.catalog.id), isolate(it.addon.manifest.name)), it.key == selectedKey) },
            days = (today - 1..today + DAYS_AHEAD).map { GuideDay(it, dayLabel(it, today), it == day, it == today) },
            dayStartMillis = start,
            dayEndMillis = end,
            marks = marks(start, end),
            nowMillis = nowMillis,
            nowInDay = nowMillis in start until end,
            openingMillis = openingMillis(nowMillis, start, end, shown),
            body = body,
            hasMore = !loading && nextPages().isNotEmpty(),
            loadingMore = pages.any { it.skip > 0 && it.state == Loadable.Loading },
        )
    }

    /** The half hours from [start] to [end], kept while the window and the language stay. */
    private fun marks(start: Long, end: Long): List<GuideMark> {
        val locale = T.locale
        val kept = madeMarks
        if (kept != null && kept.first == start to locale) return kept.second
        val made = (start until end step HALF_HOUR_MILLIS).map { GuideMark(it, localizedTime(it, utcOffset(it), locale)) }
        madeMarks = (start to locale) to made
        return made
    }

    /** [GuideUiState.openingMillis]. */
    private fun openingMillis(nowMillis: Long, start: Long, end: Long, shown: List<GuideChannel>): Long {
        val opening = if (nowMillis in start until end) nowMillis - BEFORE_NOW_MILLIS else shown.firstOrNull()?.programmes?.firstOrNull()?.startMillis ?: start
        return opening.coerceAtLeast(start)
    }

    private fun dayLabel(epochDay: Long, today: Long): String = when (epochDay) {
        today -> T.guide.today
        today - 1 -> T.guide.yesterday
        today + 1 -> T.guide.tomorrow
        else -> T.guide.day(localizedWeekday(Guides.isoDayOfWeek(epochDay), T.locale), Guides.dayOfMonth(epochDay).toString())
    }

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "guide"

        /** How often the open guide asks for its pages again (stremio-core's `RefreshLive`). */
        val REFRESH = 15.minutes

        /** The days after today the guide offers. */
        const val DAYS_AHEAD = 6

        /** A programme this long or shorter says only its start in its cell ([GuideProgramme.cellTimeText]). */
        private val START_ONLY = 30.minutes

        private const val MINUTE_MILLIS = 60_000L
        private const val HALF_HOUR_MILLIS = 30 * MINUTE_MILLIS

        /** How long before now the time shown starts as the guide opens today ([GuideUiState.openingMillis]). */
        private const val BEFORE_NOW_MILLIS = 30 * MINUTE_MILLIS
        private const val TAG = "guide"
    }
}
