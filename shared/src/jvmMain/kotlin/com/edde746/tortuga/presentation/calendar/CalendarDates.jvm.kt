package com.edde746.tortuga.presentation.calendar

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

internal actual fun localizedDate(epochDay: Long, skeleton: String, locale: String): String {
    val javaLocale = Locale.forLanguageTag(locale)
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(skeleton, IsoChronology.INSTANCE, javaLocale)
    return DateTimeFormatter.ofPattern(widened(pattern, skeleton), javaLocale).format(LocalDate.ofEpochDay(epochDay))
}

/**
 * [pattern] with its month and weekday names as wide as [skeleton] asks, as ICU and Apple give them: the JDK answers a
 * skeleton its CLDR data has no pattern for with a nearby one's and keeps that one's widths (`MMMMEEEEd` in English:
 * `E, MMM d`, "Thu, Oct 29", where ICU gives "Thursday, October 29"). A numeric month or weekday stays numeric; quoted
 * text stays as it is.
 */
private fun widened(pattern: String, skeleton: String): String {
    val wideMonth = "MMMM" in skeleton
    val wideWeekday = "EEEE" in skeleton
    return FIELD_OR_QUOTE.replace(pattern) { match ->
        val run = match.value
        when (run[0]) {
            'M', 'L' -> if (wideMonth && run.length == 3) run + run[0] else run
            'E' -> if (wideWeekday) "EEEE" else run
            'c' -> if (wideWeekday && run.length == 3) "cccc" else run
            else -> run
        }
    }
}

private val FIELD_OR_QUOTE = Regex("'[^']*'|M+|L+|E+|c+")
