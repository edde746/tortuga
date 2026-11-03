package com.edde746.tortuga

import android.content.Context
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.crash.CrashReports
import com.edde746.tortuga.ui.crash.SentryReports
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.Job

/**
 * Sends [reports] through Sentry's Android SDK while they are on ([SentryReports.follow]): uncaught exceptions as they
 * happen, native crashes (libmpv's, through the SDK's NDK part) and ANRs at the next launch. No screenshots or view
 * hierarchies.
 */
internal fun AppGraph.followCrashReports(context: Context, reports: CrashReports): Job =
    SentryReports.follow(reports, scope) { configure ->
        SentryAndroid.init(context.applicationContext) { options ->
            configure(options)
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
        }
    }
