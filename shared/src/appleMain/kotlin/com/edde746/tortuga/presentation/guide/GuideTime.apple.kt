package com.edde746.tortuga.presentation.guide

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

internal actual fun localizedTime(epochMillis: Long, offsetSeconds: Int, locale: String): String {
    val formatter = NSDateFormatter().apply {
        this.locale = NSLocale(localeIdentifier = locale)
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(offsetSeconds.toLong())
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

internal actual fun localizedWeekday(isoDayOfWeek: Int, locale: String): String {
    val formatter = NSDateFormatter().apply { this.locale = NSLocale(localeIdentifier = locale) }
    // The symbols start on Sunday.
    return checkNotNull(formatter.shortWeekdaySymbols?.get(isoDayOfWeek % 7) as? String) { "no weekday names in $locale" }
}
