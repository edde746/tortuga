package com.edde746.tortuga.playback

/**
 * How Tortuga tells that a stream answered with an error video: a short clip of a message
 * ("Wrong IP", "Downloading", "Failed access", "Payment required") that an addon, its service or a proxy serves in
 * place of the file when it can't give it. The player shows such a clip as an error, never as the video
 * ([PlaybackFailure.ErrorVideo]), and nothing about it is recorded: no progress, watched mark, duration, last stream,
 * scrobble or up next.
 *
 * Three signs, each enough on its own and none tripping on a real video:
 * - [Url]: the final URL, after Tortuga's own resolve, has the path of an error video as addons and proxies serve
 *   them ([knownUrl]), known before the file opens.
 * - [Size]: the server's file is at most [MAX_SERVED_BYTES] and the addon's `videoSize` is at least [SIZE_RATIO] times
 *   larger, known before the file opens. The clips are 12 KB to 3.7 MB; a real video is larger than 8 MiB, and an
 *   addon that sends a season pack's size as `videoSize` sends at most a few dozen episodes' worth.
 * - [Duration]: the file runs at most [MAX_SECONDS] while this video ran [MIN_KNOWN_SECONDS] or more when it was last
 *   played (the library's duration for it), known once the player reports the file's length, which mpv can do after
 *   the file's first frame. Every known clip runs 8 to 121 s and is an MP4 file or a VOD playlist, whose length mpv
 *   knows as it opens it. The meta's `runtime` doesn't count: it is the series' usual length (specials, double
 *   episodes and shorts differ), and the UI tests play 10-60 s clips as Cinemeta titles that run for hours.
 * A video with none of them (a YouTube clip, a trailer, a short film, a stream without `videoSize` played for the first
 * time) plays and is recorded as any other.
 */
sealed interface ErrorVideoSign {
    /** The final URL is a known error video ([knownUrl]). */
    data object Url : ErrorVideoSign

    /** The server's file is [servedBytes] where the addon's `videoSize` said [hintedBytes]. */
    data class Size(val servedBytes: Long, val hintedBytes: Long) : ErrorVideoSign

    /** The file runs [seconds] where this video ran [knownSeconds] when it was last played. */
    data class Duration(val seconds: Double, val knownSeconds: Double) : ErrorVideoSign

    companion object {
        /** Every known error video is 3.7 MB or less (a slate as HLS); real videos are larger. */
        const val MAX_SERVED_BYTES = 8L * 1024 * 1024

        /** How many times larger than the served file the addon's `videoSize` is at least. */
        const val SIZE_RATIO = 50

        /** Every known error video runs 121 s or less (a slate as HLS: 120.96 s). */
        const val MAX_SECONDS = 150.0

        /** A video shorter than this can't be told from an error video by its length. */
        const val MIN_KNOWN_SECONDS = 600.0

        /**
         * What the first answer of a resolve shows: the final [url], else the [servedBytes] the server reported against
         * the addon's [hintedBytes]. [head] is the start of the body: a playlist or a page (`#`, `<`, `{`) is no video
         * file, and its size says nothing.
         */
        fun ofResolve(url: io.ktor.http.Url, servedBytes: Long?, hintedBytes: Long?, head: ByteArray): ErrorVideoSign? = when {
            knownUrl(url) -> Url
            servedBytes == null || hintedBytes == null || servedBytes <= 0 -> null
            head.isNotEmpty() && head[0].toInt().toChar() in TEXT_STARTS -> null
            servedBytes <= MAX_SERVED_BYTES && hintedBytes / SIZE_RATIO >= servedBytes -> Size(servedBytes, hintedBytes)
            else -> null
        }

        /** A file of [seconds] where the video ran [knownSeconds] (the library's, null when unknown). */
        fun ofDuration(seconds: Double, knownSeconds: Double?): ErrorVideoSign? {
            if (knownSeconds == null || !lengthTells(knownSeconds) || !seconds.isFinite() || seconds <= 0) return null
            return if (seconds <= MAX_SECONDS) Duration(seconds, knownSeconds) else null
        }

        /** A file's length can tell an error video ([ofDuration]) where the video ran [knownSeconds] (null: unknown). */
        internal fun lengthTells(knownSeconds: Double?): Boolean = knownSeconds != null && knownSeconds >= MIN_KNOWN_SECONDS

        /**
         * The URL's path is one an error video has: a message's name as the file name (`downloading`,
         * `failed_access`, `payment_required`, `403`, …) in the folders addons serve these clips from (`/videos/`,
         * `/static/`, `/static/exceptions/`, `/v0/store/_/static/`). An addon that sends its clips from the playback
         * URL itself has nothing in the URL: [Size] or [Duration] tells them.
         */
        fun knownUrl(url: io.ktor.http.Url): Boolean = PATHS.any { it.containsMatchIn(url.encodedPath) }

        private val TEXT_STARTS = setOf('#', '<', '{')
        private val PATHS = listOf(
            Regex(
                """/videos/(downloading|download_failed|failed_(access|rar|too_big|opening|unexpected|infringement|unavailable)|limits_exceeded|blocked_access)_v\d+\.mp4$""",
            ),
            Regex("""/videos/(not_ready|expired_api_key|not_premium|access_denied|two_factor_auth|error)\.mp4$"""),
            Regex("""/v0/store/_/static/[^/]+\.mp4$"""),
            Regex("""/static/exceptions/[^/]+\.mp4$"""),
            Regex(
                """^/static/(download_failed|downloading|unavailable_for_legal_reasons|store_limit_exceeded|content_proxy_limit_reached|no_matching_file|payment_required|200|401|403|429|500)\.mp4$""",
            ),
        )
    }
}
