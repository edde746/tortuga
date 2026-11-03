package com.edde746.tortuga.playback.resolve

/**
 * A picked stream, as plain values from the addon's `Stream` (the model types belong to `model`).
 *
 * The URL and header values embed API keys, so [toString] leaves them out.
 *
 * @param url the stream's `url`. Some addons return a lazy link here: the first request makes their service fetch the
 *   file, then redirects to a CDN URL.
 * @param requestHeaders `behaviorHints.proxyHeaders.request`, sent on every resolve request and passed to the player.
 * @param videoHash `behaviorHints.videoHash` (16 lowercase hex digits): the stream's hash, so the resolver computes none.
 * @param videoSize `behaviorHints.videoSize`, in bytes (above 0): the size when the server reports none. The hash still
 *   needs the file, and is computed from the size the server reports.
 * @param filename `behaviorHints.filename` (never blank).
 */
data class StreamRequest(
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
) {
    override fun toString(): String =
        "StreamRequest(url=<redacted>, requestHeaders=${requestHeaders.keys}, videoHash=$videoHash, " +
            "videoSize=$videoSize, filename=$filename)"
}
