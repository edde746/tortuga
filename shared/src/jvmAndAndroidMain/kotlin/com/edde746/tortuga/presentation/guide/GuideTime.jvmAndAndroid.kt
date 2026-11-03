package com.edde746.tortuga.presentation.guide

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

internal actual fun localizedTime(epochMillis: Long, offsetSeconds: Int, locale: String): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.forLanguageTag(locale))
        .format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)))

internal actual fun localizedWeekday(isoDayOfWeek: Int, locale: String): String =
    DayOfWeek.of(isoDayOfWeek).getDisplayName(TextStyle.SHORT, Locale.forLanguageTag(locale))
