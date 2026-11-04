package com.edde746.tortuga.ui.crash

import com.edde746.tortuga.crash.CrashReports
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Crash reports through Sentry's Java SDK, on Android (sentry-android, whose NDK part also catches native crashes, as
 * libmpv's) and the desktop (sentry-java). [follow] starts the SDK while [CrashReports.active] has a DSN and closes it
 * when it has none. Only crashes and the exceptions the shared core survived ([CrashReports.failures], as error events)
 * are reported: no sessions, tracing, profiling, replays, client reports or logs, and every event and breadcrumb
 * passes [CrashReportScrubber] before it is kept or sent.
 */
object SentryReports {
    /**
     * Calls [start] (the platform's `SentryAndroid.init` or `Sentry.init`, given what to set on its options) each time
     * [reports] turns on, and closes Sentry each time it turns off, and sends each of [CrashReports.failures], until
     * [scope] ends.
     */
    fun follow(reports: CrashReports, scope: CoroutineScope, start: (configure: (SentryOptions) -> Unit) -> Unit): Job =
        scope.launch {
            // The DSN Sentry runs with: set once [start] returned for it, null once Sentry closed.
            val running = MutableStateFlow<String?>(null)
            launch {
                reports.failures.collect { failure ->
                    // One that comes as reports turn on waits for Sentry to start (before, Sentry drops it); one that
                    // comes as they turn off goes unsent.
                    if (running.value != reports.active.value) combine(running, reports.active) { ran, on -> ran == on }.first { it }
                    if (running.value != null) Sentry.captureException(failure)
                }
            }
            reports.active.collect { dsn ->
                if (dsn == null) Sentry.close() else start { options -> configure(options, dsn, reports) }
                running.value = dsn
            }
        }

    /**
     * The options every platform reports with: [dsn], [CrashReports.releaseName] and [CrashReports.environment], no
     * personal data, no sessions, tracing or client reports, and the scrubber, which also drops an event that comes
     * once [reports] is off.
     */
    fun configure(options: SentryOptions, dsn: String, reports: CrashReports) {
        options.dsn = dsn
        options.release = reports.releaseName
        options.environment = reports.environment
        options.isSendDefaultPii = false
        // The JVM prints an uncaught exception's stack trace when no handler is set; Sentry's handler does so too.
        options.isPrintUncaughtStackTrace = true
        options.isAttachServerName = false
        options.isEnableAutoSessionTracking = false
        options.isSendClientReports = false
        options.isSendModules = false
        options.isEnableUserInteractionBreadcrumbs = false
        options.tracesSampleRate = null
        val scrubber = CrashReportScrubber(System.getProperty("user.home"))
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
            if (reports.active.value == null) null else scrubber.event(event)
        }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ -> scrubber.breadcrumb(breadcrumb) }
    }
}

/**
 * What a crash report may hold. No user (Sentry's Android SDK puts an installation id there, and an IP would go
 * there), no device id or name, app hash, host name or request. Every free text, exception messages, the message,
 * thread names (also in the trace context's data), tags, extras and breadcrumbs' messages and data, passes
 * [CrashReports.scrub]: the user's [home] directory becomes `~`, and URLs keep only their origin (addon URLs carry
 * API keys in their paths) and keys go. HTTP breadcrumbs lose their URL, query and fragment altogether.
 */
class CrashReportScrubber(private val home: String?) {
    fun event(event: SentryEvent): SentryEvent {
        event.user = null
        event.serverName = null
        event.request = null
        event.contexts.device?.id = null
        event.contexts.device?.name = null
        event.contexts.app?.deviceAppHash = null
        event.message?.let { message ->
            message.formatted = message.formatted?.let(::text)
            message.message = message.message?.let(::text)
            message.params = message.params?.map(::text)
        }
        event.logger = event.logger?.let(::text)
        event.transaction = event.transaction?.let(::text)
        event.exceptions?.forEach { it.value = it.value?.let(::text) }
        event.threads?.forEach { it.name = it.name?.let(::text) }
        event.contexts.trace?.let { trace ->
            trace.description = trace.description?.let(::text)
            trace.data?.toList()?.forEach { (key, value) -> if (value is String) trace.setData(key, text(value)) }
        }
        event.tags = event.tags?.mapValuesTo(LinkedHashMap()) { (_, value) -> text(value) }
        event.extras = event.extras?.mapValuesTo(LinkedHashMap()) { (_, value) -> if (value is String) text(value) else value }
        event.breadcrumbs?.forEach(::breadcrumb)
        return event
    }

    fun breadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = breadcrumb.message?.let(::text)
        if (breadcrumb.type == "http" || breadcrumb.category?.startsWith("http") == true) HTTP_URL_KEYS.forEach(breadcrumb::removeData)
        for ((key, value) in breadcrumb.data.toList()) if (value is String) breadcrumb.setData(key, text(value))
        return breadcrumb
    }

    private fun text(value: String): String = CrashReports.scrub(value, home)

    private companion object {
        val HTTP_URL_KEYS = listOf("url", "http.query", "http.fragment")
    }
}
