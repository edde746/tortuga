package com.edde746.tortuga

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.edde746.tortuga.app.DevCrashException

/**
 * The test builds' crash, for the crash-report harness: `am broadcast -n
 * com.edde746.tortuga.dev/com.edde746.tortuga.CrashTrigger` (the shell holds DUMP, which guards it) throws a
 * [DevCrashException] on the main thread; `--es kind native` instead ends the process with SIGSEGV, as a crash in
 * native code (libmpv's) would, which the next launch reports.
 */
class CrashTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val native = intent.getStringExtra("kind") == "native"
        Handler(Looper.getMainLooper()).post {
            if (native) Process.sendSignal(Process.myPid(), SIGSEGV)
            throw DevCrashException()
        }
    }

    private companion object {
        const val SIGSEGV = 11
    }
}
