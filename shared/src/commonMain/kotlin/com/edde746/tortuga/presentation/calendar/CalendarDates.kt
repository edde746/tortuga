package com.edde746.tortuga.presentation.calendar

/** The device time zone's offset from UTC at [epochMillis] in seconds, summer time included: `7200` for UTC+2. */
internal expect fun localUtcOffsetSeconds(epochMillis: Long): Int

/** The local calendar day (days since 1970-01-01) of [epochMillis] at [offsetSeconds] east of UTC. */
internal fun localDay(epochMillis: Long, offsetSeconds: (Long) -> Int): Long =
    (epochMillis + offsetSeconds(epochMillis) * 1000L).floorDiv(MILLIS_PER_DAY)

/**
 * The first instant after [epochMillis] of the next local day ([localDay]): its midnight at the offset before it or at
 * the one after it, whichever comes first in that day, so a change of offset at midnight (summer time) moves it.
 */
internal fun nextLocalMidnight(epochMillis: Long, offsetSeconds: (Long) -> Int): Long {
    val tomorrow = localDay(epochMillis, offsetSeconds) + 1
    val before = tomorrow * MILLIS_PER_DAY - offsetSeconds(epochMillis) * 1000L
    val after = tomorrow * MILLIS_PER_DAY - offsetSeconds(before) * 1000L
    return listOf(before, after).filter { it > epochMillis && localDay(it, offsetSeconds) >= tomorrow }.minOrNull()
        ?: maxOf(after, epochMillis + 1)
}

/** [epochDay] (days since 1970-01-01) as an ISO 8601 date, `2026-09-12`. */
internal fun isoDate(epochDay: Long): String {
    val (year, month, day) = civilDate(epochDay)
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

/**
 * The date [epochDay] (days since 1970-01-01) in [locale]'s words and order for the CLDR [skeleton] (`yMMMM`:
 * "October 2026", `MMMMEEEEd`: "Tuesday, October 27", `EEE`: "Tue"), from the platform's CLDR data (JDK/ICU,
 * `NSDateFormatter`).
 */
internal expect fun localizedDate(epochDay: Long, skeleton: String, locale: String): String

internal const val MILLIS_PER_DAY = 86_400_000L

/** Year, month (1–12) and day of the proleptic Gregorian calendar for days since 1970-01-01 (H. Hinnant). */
internal fun civilDate(epochDay: Long): Triple<Long, Int, Int> {
    val z = epochDay + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val shiftedMonth = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * shiftedMonth + 2) / 5 + 1).toInt()
    val month = (if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9).toInt()
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}

/** Days since 1970-01-01 of [year]-[month]-[day], [civilDate]'s inverse (H. Hinnant). */
internal fun epochDay(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400L)
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
