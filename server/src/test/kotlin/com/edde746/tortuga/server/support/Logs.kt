package com.edde746.tortuga.server.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Every log line written while [block] runs, at DEBUG, each with its key-value pairs, MDC and stack trace. */
fun captureLogs(block: () -> Unit): List<String> {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val level = root.level
    // DEBUG, the most an operator sets (`TORTUGA_LOG_LEVEL`); Ktor's own TRACE lines echo request URLs.
    root.level = Level.DEBUG
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
        root.level = level
    }
    return appender.list.map { event ->
        buildString {
            append(event.formattedMessage)
            event.keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
            event.mdcPropertyMap.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            event.throwableProxy?.let { append('\n').append(ThrowableProxyUtil.asString(it)) }
        }
    }
}
