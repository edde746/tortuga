package com.edde746.tortuga.app

/**
 * The test builds' crash (Android's CrashTrigger, the desktop harness launcher's `--crash-after`): its message carries
 * [PROBE_URL], an addon URL with an API key, which no crash report may hold.
 */
class DevCrashException : IllegalStateException("Crash probe: the stream at $PROBE_URL failed") {
    companion object {
        /** The key is made up, 52 capitals and digits; the harness looks for [PROBE_KEY] in what was sent. */
        const val PROBE_KEY = "TORTUGACRASHPROBEABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABC"
        const val PROBE_URL = "https://addon.example/apikey=$PROBE_KEY/stream/movie/tt0111161.json"
    }
}

/** Apple's debug builds' crash: Swift can't throw a Kotlin exception, so it calls [crash], which throws one. */
object DevCrash {
    fun crash(): Unit = throw DevCrashException()
}
