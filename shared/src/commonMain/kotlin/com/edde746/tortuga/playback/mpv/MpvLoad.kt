package com.edde746.tortuga.playback.mpv

import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.resolve.LivePlaylist
import kotlin.math.roundToLong

/**
 * What a [LoadRequest] sets in mpv for its file, one table for every engine. Every engine sets each value as mpv's
 * property right before a plain `loadfile <url> replace`, none as a per-file option of the loadfile: mpv drops a file's
 * per-file options on the files it opens from that file's playlist (an HLS open that fails falls back to the
 * playlist's entries), which would then start at the beginning, without the file's title, demuxer and headers.
 * - The file's own values ([fileProperties]) and its headers ([headerFields]) go on every load, so no file's value
 *   carries into the next.
 * - The decoding and cache options ([decodingOptions]): Android and the desktop write each only when it differs from
 *   mpv's (a `hwdec` write re-creates the playing file's decoder); Apple, whose requests are all async, writes them on
 *   every load, ahead of the loadfile that replaces the playing file.
 *
 * `http-header-fields` is a string list (`,`-separated). mpv unescapes only a backslash directly before the separator,
 * so each item escapes its separators and nothing else (verified against mpv 0.41's parser).
 */
object MpvLoad {
    /** The file's decoding and cache options, from this device's settings ([PlaybackOptions]). */
    fun decodingOptions(options: PlaybackOptions): Map<String, String> = mapOf(
        "hwdec" to options.hwdec,
        "demuxer-max-bytes" to options.demuxerMaxBytes.toString(),
        "demuxer-max-back-bytes" to options.demuxerMaxBackBytes.toString(),
    )

    /**
     * The file's own values as mpv's properties, every one on every load: its title (`force-media-title`), where it
     * starts (`start`, seconds), the lavf demuxer it opens with instead of probing (`demuxer-lavf-format`) and lavf's
     * options, here the hls demuxer's first segment of a live playlist (`demuxer-lavf-o`, a live rewind's
     * `live_start_index`). Where the request has none, the property's own "unset": `start=none`, an empty string for
     * the others.
     *
     * A live file ([LoadRequest.live]) goes on after a stall only with [LIVE_BUFFER_SECONDS] buffered
     * (`cache-pause-wait`): it arrives at 1×, so mpv's own value (go on at 1 s) rides the live edge with a segment to
     * spare and stalls on every late segment. It starts so too (`cache-pause-initial`) unless it joins its playlist
     * [LivePlaylist.JOIN_SEGMENTS] or more segments before the end ([LoadRequest.liveStartIndex], a reopen): that join
     * reads the hold-back at once and plays from its first frame, where the live window places it. mpv's initial wait
     * counts only what lies past what its decoders have read, which three 2-s segments never fill, so it would wait
     * for the edge to move on and play that much further behind it. A first open (lavf's own join, or a stream that
     * isn't HLS) and a join closer to the end wait. Any other file keeps mpv's values and starts at its first frame.
     */
    fun fileProperties(request: LoadRequest): Map<String, String> = mapOf(
        "force-media-title" to request.title.orEmpty(),
        "start" to (request.startSeconds.takeIf { it > 0 }?.let(::seconds) ?: "none"),
        "demuxer-lavf-format" to request.demuxerFormat.orEmpty(),
        "demuxer-lavf-o" to (request.liveStartIndex?.let { "live_start_index=$it" } ?: ""),
        "cache-pause-initial" to
            if (request.live && request.liveStartIndex.let { it == null || it > -LivePlaylist.JOIN_SEGMENTS }) "yes" else "no",
        "cache-pause-wait" to if (request.live) LIVE_BUFFER_SECONDS else "1",
    )

    /** Three 2-s segments: the hold-back HLS asks of a client (RFC 8216, 6.3.3). */
    private const val LIVE_BUFFER_SECONDS = "6"

    /** [headers] as mpv's `http-header-fields` value, in their order; empty for none, which clears the last file's. */
    fun headerFields(headers: Map<String, String>): String =
        headers.entries.joinToString(STRING_LIST_SEPARATOR) { (name, value) -> "$name: $value".replace(STRING_LIST_SEPARATOR, "\\$STRING_LIST_SEPARATOR") }

    private const val STRING_LIST_SEPARATOR = ","

    /** Positive [value] seconds with three decimals: `61.500`. */
    private fun seconds(value: Double): String {
        val millis = (value * 1000).roundToLong()
        return "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"
    }
}
