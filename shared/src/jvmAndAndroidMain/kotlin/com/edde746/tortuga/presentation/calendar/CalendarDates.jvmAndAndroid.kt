package com.edde746.tortuga.presentation.calendar

import java.time.Instant
import java.time.ZoneId

internal actual fun localUtcOffsetSeconds(epochMillis: Long): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds
