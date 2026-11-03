package com.edde746.tortuga.log

import kotlin.concurrent.Volatile

/**
 * The logging facade for shared code. Every message and every error's text passes through [Redactor] before a
 * [Sink] sees it, so sinks (console, logcat, OSLog) never receive a transport URL, stream URL or key. Messages are
 * built lazily and skipped below [minLevel].
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** Receives redacted log records. [error] is the redacted stack trace of the throwable, if any. */
    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: String?)
    }

    /** Prints to standard output; the default until the app installs its platform sink. */
    val ConsoleSink: Sink = Sink { level, tag, message, error ->
        println("${level.name[0]}/$tag: $message" + if (error != null) "\n$error" else "")
    }

    @Volatile
    var sink: Sink = ConsoleSink

    @Volatile
    var minLevel: Level = Level.INFO

    fun d(tag: String, error: Throwable? = null, message: () -> String) = log(Level.DEBUG, tag, error, message)
    fun i(tag: String, error: Throwable? = null, message: () -> String) = log(Level.INFO, tag, error, message)
    fun w(tag: String, error: Throwable? = null, message: () -> String) = log(Level.WARN, tag, error, message)
    fun e(tag: String, error: Throwable? = null, message: () -> String) = log(Level.ERROR, tag, error, message)

    fun log(level: Level, tag: String, error: Throwable?, message: () -> String) {
        if (level < minLevel) return
        sink.write(level, tag, Redactor.redact(message()), error?.let { Redactor.redact(it.stackTraceToString()) })
    }
}
