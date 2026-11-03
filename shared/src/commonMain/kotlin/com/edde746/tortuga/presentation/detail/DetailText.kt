package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.presentation.calendar.MILLIS_PER_DAY
import com.edde746.tortuga.presentation.calendar.civilDate
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.time.Instant

/** The Detail screen's formatting of addon values. */
internal object DetailText {
    private val MINUTES = Regex("""^\s*(\d{1,5})\s*min\.?\s*$""", RegexOption.IGNORE_CASE)

    /** `142 min` (Cinemeta's form) as `2 h 22 min`; any other text as the addon wrote it. */
    fun runtime(text: String?): String? = runtime(text) { it }

    /** [runtime] as a part of an info line ([join]): the addon's own text isolated, this app's `2 h 22 min` as it is. */
    fun runtimePart(text: String?): String? = runtime(text, ::isolate)

    private inline fun runtime(text: String?, addonText: (String) -> String): String? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val minutes = runtimeMinutes(trimmed) ?: return addonText(trimmed)
        return minutes(minutes)
    }

    /** The minutes of a runtime in Cinemeta's form (`142 min`); null for any other text. */
    fun runtimeMinutes(text: String?): Int? = text?.let { MINUTES.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    /** [minutes] as `2 h 22 min`, `2 h` or `22 min`. */
    fun minutes(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> T.detail.runtime.minutes(rest.toString())
            rest == 0 -> T.detail.runtime.hours(hours.toString())
            else -> T.detail.runtime.hoursMinutes(hours.toString(), rest.toString())
        }
    }

    /** `May 6, 2019`: the calendar date [epochDay] (days since 1970-01-01). */
    fun date(epochDay: Long): String {
        val (year, month, day) = civilDate(epochDay)
        return T.detail.date(monthName(month), day.toString(), year.toString())
    }

    /** The [date] of [instant] in UTC, the date addons mean by a release time. */
    fun date(instant: Instant): String = date(instant.toEpochMilliseconds().floorDiv(MILLIS_PER_DAY))

    /**
     * `1994 · 2 h 22 min`; null for no parts. Callers isolate the parts that are content (an addon's release info or
     * runtime text, genres): each pair is joined again, so isolating here would nest.
     */
    fun join(parts: List<String>): String? = parts.reduceOrNull { first, second -> T.detail.infoJoin(first, second) }

    private fun monthName(month: Int): String = with(T.detail.month) {
        when (month) {
            1 -> jan
            2 -> feb
            3 -> mar
            4 -> apr
            5 -> may
            6 -> jun
            7 -> jul
            8 -> aug
            9 -> sep
            10 -> oct
            11 -> nov
            else -> dec
        }
    }

}
