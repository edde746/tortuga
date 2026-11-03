package com.edde746.tortuga.playback.resolve

/**
 * A live stream's HLS media playlist as its rewind window: its segments' durations in order
 * (`#EXTINF`), its target duration (`#EXT-X-TARGETDURATION`) and whether it ended (`#EXT-X-ENDLIST`: an event that ended).
 * A sliding playlist (its `#EXT-X-MEDIA-SEQUENCE` moving on as old segments drop off) and an EVENT one
 * (`#EXT-X-PLAYLIST-TYPE:EVENT`, which keeps every segment since the event began) read the same: the window is what it
 * lists now, from its first segment's start to its newest segment's end, the live edge.
 */
data class LivePlaylist(val segmentSeconds: List<Double>, val targetSeconds: Double, val ended: Boolean) {
    /** How far the window reaches behind the live edge: its segments' durations summed. */
    val windowSeconds: Double get() = segmentSeconds.sum()

    /**
     * Where a file opened [behindSeconds] behind the live edge starts: at the start of the segment that holds that
     * point, never closer to the end than [JOIN_SEGMENTS] segments (lavf's own join, as the HLS spec advises: a player
     * closer to the end runs out of segments) and never before the first.
     */
    fun join(behindSeconds: Double): LiveJoin {
        var count = 0
        var behind = 0.0
        while (count < segmentSeconds.size && (count < JOIN_SEGMENTS || behind < behindSeconds)) {
            behind += segmentSeconds[segmentSeconds.size - 1 - count]
            count++
        }
        return LiveJoin(segmentsFromEnd = count, behindSeconds = behind)
    }

    companion object {
        /** lavf's `live_start_index` default (−3): a live HLS file opens this many segments before its playlist's end. */
        const val JOIN_SEGMENTS = 3

        /** [text] as a media playlist, or null when it is none: no `#EXTM3U` header, or no segment. */
        fun parse(text: String): LivePlaylist? {
            val lines = text.removePrefix(BOM).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.startsWith(HEADER) != true) return null
            var target: Double? = null
            var ended = false
            var duration: Double? = null
            val segments = mutableListOf<Double>()
            for (line in lines) {
                when {
                    line.startsWith(TARGET) -> target = line.removePrefix(TARGET).trim().toDoubleOrNull()
                    line.startsWith(SEGMENT) -> duration = line.removePrefix(SEGMENT).substringBefore(',').trim().toDoubleOrNull()
                    line == END -> ended = true
                    // A segment's URI, after its #EXTINF.
                    !line.startsWith("#") -> duration?.let {
                        segments += it
                        duration = null
                    }
                }
            }
            if (segments.isEmpty()) return null
            return LivePlaylist(segments, target ?: segments.max(), ended)
        }

        /**
         * A master playlist's first variant's URI as it is written (relative or absolute), or null when [text] is no
         * master playlist. The variants of a live stream share one window.
         */
        fun firstVariant(text: String): String? {
            val lines = text.removePrefix(BOM).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.startsWith(HEADER) != true) return null
            val variant = lines.indexOfFirst { it.startsWith(VARIANT) }.takeIf { it >= 0 } ?: return null
            return lines.drop(variant + 1).firstOrNull { !it.startsWith("#") }
        }

        private const val BOM = "\uFEFF"
        private const val HEADER = "#EXTM3U"
        private const val TARGET = "#EXT-X-TARGETDURATION:"
        private const val SEGMENT = "#EXTINF:"
        private const val END = "#EXT-X-ENDLIST"
        private const val VARIANT = "#EXT-X-STREAM-INF"
    }
}

/**
 * Where a live file opens in its playlist: [segmentsFromEnd] segments before its end (lavf's `live_start_index` is
 * minus that), which puts its first frame [behindSeconds] behind the live edge.
 */
data class LiveJoin(val segmentsFromEnd: Int, val behindSeconds: Double)
