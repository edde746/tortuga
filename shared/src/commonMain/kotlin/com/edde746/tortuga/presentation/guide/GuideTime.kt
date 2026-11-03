package com.edde746.tortuga.presentation.guide

/**
 * The time of day of [epochMillis] at [offsetSeconds] from UTC in [locale]'s short form, from its CLDR data (JDK/ICU,
 * `NSDateFormatter`): `6:00 PM` in English, `18:00` in German.
 */
internal expect fun localizedTime(epochMillis: Long, offsetSeconds: Int, locale: String): String

/** [isoDayOfWeek]'s (1 Monday … 7 Sunday) short name in [locale]: `Thu`, `Do.`, `木`. */
internal expect fun localizedWeekday(isoDayOfWeek: Int, locale: String): String
