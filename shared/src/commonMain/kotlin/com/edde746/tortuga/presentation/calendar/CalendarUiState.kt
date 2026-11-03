package com.edde746.tortuga.presentation.calendar

/**
 * The Calendar screen: one month of the library's series' episodes, as their addons date them
 * ([com.edde746.tortuga.model.Video.released], a UTC date: addons mean a day, not a time).
 *
 * @param month the month shown, in the interface language ("October 2026").
 * @param weekdays the weekdays' short names, Monday first, as [days] lay out.
 * @param leadingDays the blank cells before the 1st in a grid of weeks from Monday.
 * @param days every day of [month].
 * @param body what shows under the grid ([CalendarBody]).
 * @param currentMonth [month] is this month here (the device's date): no way back to it is needed.
 */
data class CalendarUiState(
    val title: String,
    val month: String,
    val weekdays: List<String>,
    val leadingDays: Int,
    val days: List<CalendarDay>,
    val body: CalendarBody,
    val currentMonth: Boolean,
) {
    /** The days of [month] with episodes, in order: [body]'s [CalendarBody.Agenda], else none. */
    val agenda: List<CalendarAgendaDay> get() = (body as? CalendarBody.Agenda)?.days.orEmpty()
}

/**
 * What shows under the month's grid, the first that applies: the days of the month with episodes, which stay while
 * another addon still answers ([Agenda]); why there are none: nothing on the calendar in the library, no installed
 * addon with a calendar for the library's series ([Empty]); a spinner while an addon (on a kids profile, a series'
 * verdict) has still to answer ([Loading]); else that the month has none ([Empty]).
 */
sealed interface CalendarBody {
    data object Loading : CalendarBody

    /** [text] says why there is nothing to show. */
    data class Empty(val text: String) : CalendarBody

    /** [days]: the days of the month with episodes, in order, never none. */
    data class Agenda(val days: List<CalendarAgendaDay>) : CalendarBody
}

/**
 * @param day the day of the month, 1 to 31.
 * @param today it is today here.
 * @param episodes how many episodes come out that day.
 * @param accessibilityLabel the day for assistive technologies: its date and its episodes ("Tuesday, October 27, 2
 *   episodes").
 */
data class CalendarDay(val day: Int, val today: Boolean, val episodes: Int, val accessibilityLabel: String)

/**
 * @param label "Today", "Tomorrow" or the date ("Thursday, October 29").
 */
data class CalendarAgendaDay(val day: Int, val label: String, val today: Boolean, val episodes: List<CalendarEpisode>)

/**
 * An episode on the calendar, drawn as Detail draws an episode's row; opening it shows its series' Detail at the
 * episode ([CalendarStore.open]).
 *
 * @param series the series' name.
 * @param detail the line under it: "S1E3 · Title" (the title isolated), the code or the title alone, then for an episode
 *   the series' runtime as Detail's episode line has it ("S1E3 · Title · 46 min"); null for a video with none of these.
 * @param overview the episode's description; null without one.
 * @param still the episode's own still; null without one.
 * @param art the series' landscape art, its background, else its poster: shown where there is no [still] or it fails to
 *   load; null without either.
 * @param blurred the profile blurs unwatched episodes and this one is neither watched nor started: [still] shows
 *   blurred past recognition and [overview] past reading ([hiddenOverviewLabel]), as on Detail; [art] stays sharp.
 * @param hiddenOverviewLabel while [blurred], what screen readers say in place of [overview]: "Description hidden";
 *   null while it shows, and for an episode without one.
 */
data class CalendarEpisode(
    val type: String,
    val metaId: String,
    val videoId: String,
    val series: String,
    val detail: String?,
    val overview: String?,
    val still: String?,
    val art: String?,
    val blurred: Boolean,
    val hiddenOverviewLabel: String?,
)

/** What the Calendar screen asks of the app around it; called on the main thread. */
interface CalendarHost {
    /** Open the Detail screen of [id] of [type], showing its episode [videoId]. */
    fun openEpisode(type: String, id: String, videoId: String)
}
