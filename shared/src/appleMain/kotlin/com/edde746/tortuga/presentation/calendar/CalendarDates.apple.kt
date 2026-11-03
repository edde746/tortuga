package com.edde746.tortuga.presentation.calendar

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeZoneForSecondsFromGMT

internal actual fun localUtcOffsetSeconds(epochMillis: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)).toInt()

internal actual fun localizedDate(epochDay: Long, skeleton: String, locale: String): String {
    val formatter = NSDateFormatter().apply {
        this.locale = NSLocale(localeIdentifier = locale)
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        setLocalizedDateFormatFromTemplate(skeleton)
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochDay * 86_400.0))
}
