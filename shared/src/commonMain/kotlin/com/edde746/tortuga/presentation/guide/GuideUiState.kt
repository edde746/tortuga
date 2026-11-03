package com.edde746.tortuga.presentation.guide

import com.edde746.tortuga.model.MetaItemPreview

/**
 * The TV guide (stremio-core `LiveTvGuide`): the channels of one guide catalog of the installed addons and their
 * programmes over one day of the device's time zone. Times are milliseconds since the epoch.
 *
 * @param catalogs every guide catalog of the installed addons, in their order; the guide shows the selected one.
 * @param days yesterday to six days ahead; the guide shows the selected one.
 * @param dayStartMillis the selected day's local midnight; [dayEndMillis] the next one.
 * @param marks the day's half hours, from its start: a time ruler's.
 * @param nowMillis when the state was made: the guide's now, moved on every minute; [nowInDay] it falls in the day shown.
 * @param openingMillis where the time shown starts as the guide opens the day: half an hour before now on today, else
 *   the first channel's first programme, else the day's start; never before the day's start.
 * @param body the channels, or what shows instead of them.
 * @param hasMore the catalog has pages not loaded yet ([GuideStore.loadMore]); [loadingMore] while they come.
 */
data class GuideUiState(
    val catalogs: List<GuideCatalogChoice> = emptyList(),
    val days: List<GuideDay> = emptyList(),
    val dayStartMillis: Long = 0,
    val dayEndMillis: Long = 0,
    val marks: List<GuideMark> = emptyList(),
    val nowMillis: Long = 0,
    val nowInDay: Boolean = false,
    val openingMillis: Long = 0,
    val body: GuideBody = GuideBody.Loading,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
) {
    /** The channels shown: [body]'s [GuideBody.Grid], else none. */
    val channels: List<GuideChannel> get() = (body as? GuideBody.Grid)?.channels.orEmpty()
}

/**
 * What the guide shows under its catalog and day choices, the first that applies: that the installed addons can't be
 * read ([Failed]); a spinner while they aren't known yet ([Loading]); that no installed addon has a guide catalog
 * ([Empty]); the day's channels, which stay while more pages come ([Grid]); a spinner while the day's first pages are
 * on their way ([Loading]); that every first page failed, [GuideStore.retry] asking again ([Failed]); else that the
 * catalog has no channels for the day ([Empty]).
 */
sealed interface GuideBody {
    data object Loading : GuideBody

    /** [text] says what failed. */
    data class Failed(val text: String) : GuideBody

    /** [text] says why there is nothing to show. */
    data class Empty(val text: String) : GuideBody

    /** [channels]: in the catalog's order, its pages' channels each once; never none. */
    data class Grid(val channels: List<GuideChannel>) : GuideBody
}

/** A guide catalog: [label] "Channels · Example Live TV"; [key] stable across updates. */
data class GuideCatalogChoice(val key: String, val label: String, val selected: Boolean)

/** A day of the guide: [label] "Today", "Tomorrow", "Yesterday" or "Thu 30"; [epochDay] days since 1970-01-01. */
data class GuideDay(val epochDay: Long, val label: String, val selected: Boolean, val today: Boolean)

/** A half hour of the day: when it starts and its time, `6:30 PM` in the device's time zone. */
data class GuideMark(val millis: Long, val label: String)

/**
 * A channel: [logo] its logo, else its poster. [onNow] and [next] index [programmes] (ordered by start): the programme
 * on air at [GuideUiState.nowMillis] and the one after it; null for none. [startIndex] is the programme the guide first
 * puts the focus on: [onNow], else [next], else the first; null without programmes.
 */
data class GuideChannel(
    val id: String,
    val type: String,
    val name: String,
    val logo: String?,
    val programmes: List<GuideProgramme>,
    val onNow: Int?,
    val next: Int?,
    val startIndex: Int?,
    internal val preview: MetaItemPreview,
)

/**
 * A programme and its details: [timeText] "6:00 PM – 6:45 PM" in the device's time zone; [startText] its start alone
 * ("6:00 PM"); [cellTimeText] what its cell in the grid says of its time, the range, or for a programme of half an
 * hour or less [startText], which its cell has room for (the details give the range; a cell showing too little of
 * itself for the range says [startText] too); [infoText] its genres, runtime, year and ratings
 * ("News · 45 min · 2026 · PG"); [creditsText] its cast, then its directors ("With Jane Doe, John Doe · Directed by
 * John Doe"). [onAir]: it is its channel's [GuideChannel.onNow] (on from its start, off at its end);
 * [past]: it has ended by [GuideUiState.nowMillis]. [headline] heads its details: "On now · 6:00 PM – 6:45 PM · Channel"
 * on air, else "6:00 PM – 6:45 PM · Channel", the channel's name isolated.
 */
data class GuideProgramme(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timeText: String,
    val startText: String,
    val cellTimeText: String,
    val overview: String?,
    val thumbnail: String?,
    val infoText: String?,
    val creditsText: String?,
    val onAir: Boolean,
    val past: Boolean,
    val headline: String,
)

/** What the guide asks of the app around it; called on the main thread. */
interface GuideHost {
    /**
     * Plays [channel] by its own id, whatever programme is on: its Detail, playing as a `?autoPlay=true` link does (the
     * stream it last played from, else the first that passes the filters, else its stream list).
     */
    fun playChannel(channel: MetaItemPreview)
}
