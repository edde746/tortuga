package com.edde746.tortuga.crash

import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.log.Redactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * This build's crash-report endpoint: a Sentry DSN, or blank for a build that never sends a report. Only the release
 * workflow builds with one; a DSN ships in the binary, as in every app that reports to Sentry.
 */
data class CrashReportConfig(val dsn: String = "") {
    override fun toString(): String = "CrashReportConfig(dsn=${if (dsn.isBlank()) "none" else "set"})"
}

/**
 * When this app process sends crash reports, and where to. The product reports to its build's DSN
 * ([BuildCrashReportConfig]), a development session only to the endpoint its launch names; on each device only while
 * [DeviceSetting.CrashReports] is on, which it is by default.
 *
 * The platform starts its crash reporter (Sentry) whenever [active] has a DSN and closes it whenever it has none, so a
 * device that turned reports off records no crash to send later, and drops any event that comes while it has none. It
 * also sends each of [failures] as an error report. Every report says it comes from [releaseName] in [environment], and
 * its text goes through [scrub].
 */
class CrashReports internal constructor(
    settings: DeviceSettingsRepository,
    dsn: String,
    /** `tortuga@<version>`, Sentry's release: one per version on every platform (`release` clashes with ObjC's). */
    val releaseName: String,
    /** [PRODUCTION] or [DEVELOPMENT]. */
    val environment: String,
    scope: CoroutineScope,
    failures: Flow<Throwable>,
) {
    /**
     * The DSN while this device sends crash reports; null while the setting is off, before it is read, and always in
     * a build without one.
     */
    val active: StateFlow<String?> = if (dsn.isBlank()) {
        MutableStateFlow<String?>(null).asStateFlow()
    } else {
        settings.observe().map { if (it.crashReports) dsn else null }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    /**
     * Each exception the app survived ([FailureBoundary]: a coroutine that threw in the product) while [active] has a
     * DSN, which the platform sends as an error report, scrubbed as a crash's; none while this device's reports are
     * off, and none in a build without a DSN.
     */
    val failures: Flow<Throwable> = failures.filter { active.value != null }

    companion object {
        /** The release builds' environment. */
        const val PRODUCTION: String = "production"

        /** A development session's environment. */
        const val DEVELOPMENT: String = "development"

        /**
         * A crash report's free text (exception messages, breadcrumbs, thread names, file paths): the user's [home]
         * directory becomes `~`, so no account name is sent, then [Redactor.redact] removes every URL beyond its origin
         * (addon URLs carry API keys in their paths) and every key-shaped value.
         */
        fun scrub(text: String, home: String?): String {
            var out = text
            if (home != null && home.length > 1) {
                out = out.replace(home, "~", ignoreCase = true)
                val other = if ('\\' in home) home.replace('\\', '/') else home.replace('/', '\\')
                out = out.replace(other, "~", ignoreCase = true)
            }
            return Redactor.redact(out)
        }
    }
}

/**
 * This process's crash reports: to [dsn] (by default this build's) and labelled [environment], while this device's
 * setting is on, with the exceptions its failure boundary survived.
 */
fun AppBootstrap.crashReports(
    dsn: String = BuildCrashReportConfig.config.dsn,
    environment: String = CrashReports.PRODUCTION,
): CrashReports = CrashReports(graph.data.deviceSettings, dsn, "tortuga@${graph.appVersion}", environment, graph.scope, failures.failures)
