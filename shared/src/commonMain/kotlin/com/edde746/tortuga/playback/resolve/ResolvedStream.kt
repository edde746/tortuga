package com.edde746.tortuga.playback.resolve

import com.edde746.tortuga.playback.ErrorVideoSign

/**
 * What the player and the subtitle lookup need from a picked stream.
 *
 * [toString] leaves out the URL and header values: they carry API keys.
 *
 * @param url the final URL, after every redirect. The player opens this one, so it doesn't repeat the addon's slow
 *   first request.
 * @param headers `proxyHeaders.request` cleaned for the player (`LoadRequest.headers`, mpv's `http-header-fields`) and
 *   a download's requests, `User-Agent` included under that spelling: mpv sends a `User-Agent` from
 *   `http-header-fields` instead of its own. Without `Authorization`, `Cookie` and `Proxy-Authorization` once the
 *   resolve's redirects left the stream URL's scheme, host and port ([StreamResolver.redirectHeaders]): those are for
 *   that origin alone.
 * @param size total size in bytes, from `Content-Range` (or `Content-Length`), else the addon's `videoSize`.
 * @param videoHash OpenSubtitles hash, 16 lowercase hex digits: the addon's `videoHash`, else computed; null when the
 *   server's answers didn't allow computing it.
 * @param filename the addon's `filename`, else the response's `Content-Disposition`, else the final URL's last path
 *   segment when it looks like a file name.
 * @param errorVideo the server answered with an error video instead of the file, as the first answer shows (its final
 *   URL or its size, [ErrorVideoSign.ofResolve]); null when nothing says so.
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val size: Long?,
    val videoHash: String?,
    val filename: String?,
    val errorVideo: ErrorVideoSign? = null,
) {
    /** The `User-Agent` the addon asked for, if any. */
    val userAgent: String? get() = headers[StreamHeaders.USER_AGENT]

    override fun toString(): String =
        "ResolvedStream(url=<redacted>, headers=${headers.keys}, size=$size, videoHash=$videoHash, filename=$filename, " +
            "errorVideo=$errorVideo)"
}
