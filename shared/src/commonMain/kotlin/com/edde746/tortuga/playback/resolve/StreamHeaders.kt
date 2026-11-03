package com.edde746.tortuga.playback.resolve

/**
 * The stream's `proxyHeaders` for the player. `request` is cleaned once for both the resolve requests and the player
 * (mpv's `http-header-fields`):
 * - names are trimmed and must be RFC 9110 tokens (ASCII); values are trimmed and may hold only tabs and printable
 *   ASCII: a CR or LF would inject header lines, and OkHttp (Android, Windows, Linux) refuses any other character
 *   with an exception that fails the whole resolve. A header that breaks either rule is dropped;
 * - framing and routing headers are dropped: a fixed `Range` would break seeking, `Host` would break the CDN host a
 *   link redirects to, and the HTTP stack owns the rest;
 * - names are unique case-insensitively (the last one wins), and `User-Agent` is spelled canonically, so a player
 *   that sets its own agent can look it up.
 */
internal object StreamHeaders {
    const val USER_AGENT = "User-Agent"

    private val dropped = setOf(
        "host", "range", "content-length", "transfer-encoding", "connection", "upgrade", "keep-alive", "te",
        "trailer", "proxy-connection",
    )

    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val byLowercaseName = LinkedHashMap<String, Pair<String, String>>()
        for ((rawName, rawValue) in headers) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (!name.isToken() || value.any { it != '\t' && it !in ' '..'~' }) continue
            val key = name.lowercase()
            if (key in dropped) continue
            byLowercaseName.remove(key)
            byLowercaseName[key] = (if (key == "user-agent") USER_AGENT else name) to value
        }
        return byLowercaseName.values.associate { it }
    }

    /**
     * The lavf demuxer `proxyHeaders.response` asks for (mpv's per-file `demuxer-lavf-format`), or null. Stremio's
     * streaming server sends these headers to the player in place of the origin's; Tortuga's mpv talks to the origin,
     * so only a header whose effect an option reproduces can count. That is `Content-Type`, for HLS alone: FFmpeg
     * refuses a playlist whose URL has no `.m3u8`/`.m3u` extension unless its type is an HLS one, and mpv then plays
     * the playlist's entries as separate files. FFmpeg never reads `Content-Disposition` or `Access-Control-*`; the
     * length, range and encoding headers describe what the origin really sends, which no option can change.
     */
    fun demuxerFormat(response: Map<String, String>): String? {
        val type = response.entries.lastOrNull { it.key.trim().equals("content-type", ignoreCase = true) }?.value
        return "hls".takeIf { type?.substringBefore(';')?.trim()?.lowercase() in HLS_TYPES }
    }

    private fun String.isToken() = isNotEmpty() && all { it.isLetterOrDigit() && it.code < 128 || it in TOKEN_SYMBOLS }

    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

    /** The types FFmpeg's HLS probe accepts in place of the extension (libavformat/hls.c `hls_probe`). */
    private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "audio/mpegurl", "application/x-mpegurl", "audio/x-mpegurl")
}
