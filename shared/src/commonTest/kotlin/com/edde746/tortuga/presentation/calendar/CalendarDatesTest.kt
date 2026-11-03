package com.edde746.tortuga.presentation.calendar

import kotlin.test.Test
import kotlin.test.assertEquals

/** [nextLocalMidnight] where the time zone's offset changes at midnight (summer time). */
class CalendarDatesTest {
    @Test
    fun theNextDayStartsWhenTheWallClockFirstReadsIt() {
        val day = 20_000L
        val hour = 3_600_000L
        // UTC+1 to UTC+2 at local midnight: the day starts at 01:00 on the new offset.
        val forward = day * MILLIS_PER_DAY - hour
        val springing = { millis: Long -> if (millis < forward) 3_600 else 7_200 }
        assertEquals(forward, nextLocalMidnight(forward - 2 * hour, springing))
        // UTC+2 to UTC+1 at local midnight: 23:00 comes twice, and the day starts at the second midnight.
        val back = day * MILLIS_PER_DAY - 2 * hour
        val falling = { millis: Long -> if (millis < back) 7_200 else 3_600 }
        assertEquals(day * MILLIS_PER_DAY - hour, nextLocalMidnight(back - 2 * hour, falling))
        assertEquals(day * MILLIS_PER_DAY - hour, nextLocalMidnight(back + hour / 2, falling))
    }
}
