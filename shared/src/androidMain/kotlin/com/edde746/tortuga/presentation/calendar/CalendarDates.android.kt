package com.edde746.tortuga.presentation.calendar

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.util.Date

internal actual fun localizedDate(epochDay: Long, skeleton: String, locale: String): String =
    DateFormat.getInstanceForSkeleton(skeleton, ULocale.forLanguageTag(locale))
        .apply { timeZone = TimeZone.GMT_ZONE }
        .format(Date(epochDay * 86_400_000L))
