package com.edde746.tortuga.data.settings

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What Play, Resume and an episode do on Detail, and what the next episode plays from when no stream of its binge
 * group is offered (`ProfileSetting.StreamAutoPlay`). Streams the client can't play are never picked.
 */
@Serializable
enum class AutoPlayMode {
    /** The stream list opens (resuming replays the remembered stream, as before auto-play existed). */
    @SerialName("off")
    OFF,

    /**
     * The first stream that passes the stream filters, in the list's order: addons as installed, then each one's order;
     * with a sort ([StreamSortKey]), the first of every addon's streams in the sorted order.
     */
    @SerialName("firstMatch")
    FIRST_MATCH,

    /**
     * The same stream as last time, filters or not (it was chosen before): the one the video last played from (stream
     * history, synced) while its addon still offers it; else, for an episode, the stream its addon offers now in the
     * binge group the title last played from; else [FIRST_MATCH].
     */
    @SerialName("reuseLastLink")
    REUSE_LAST_LINK,
}

/**
 * A resolution bucket of the stream filter, read from a stream's text by `StreamBadgeParser` ([key] is how
 * `ProfileSetting.HiddenStreamQualities` stores it). [SD] is 480p and below; [UNKNOWN] is a stream whose text names no
 * resolution.
 */
enum class StreamQuality(val key: String) {
    UHD_4K("4k"),
    QHD_1440("1440p"),
    FHD_1080("1080p"),
    HD_720("720p"),
    SD("sd"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromKey(key: String): StreamQuality? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A key of the stream lists' sort (`ProfileSetting.StreamSortKeys`, stored as [key]), read from each stream's text
 * (`StreamBadgeParser`); a stream whose text doesn't say goes after the ones that do.
 */
enum class StreamSortKey(val key: String) {
    /** Highest first: 4K, 1440p, 1080p, 720p, SD. */
    RESOLUTION("resolution"),

    /** Best first: remux, Blu-ray, WEB-DL, WEBRip, HDTV, HDRip, DVD, DVDRip, TV rip. */
    SOURCE("source"),

    /** The file's size, largest first (the addon's `videoSize`, else the size its text names). */
    SIZE_LARGEST("sizeLargest"),

    /** The file's size, smallest first. */
    SIZE_SMALLEST("sizeSmallest"),

    /** Streams an addon marks instant (they play at once) first, streams it marks not ready last. */
    CACHED("cached"),
    ;

    companion object {
        fun fromKey(key: String): StreamSortKey? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A dynamic range bucket of the stream filter (`ProfileSetting.HiddenStreamRanges`): [DOLBY_VISION] is a stream whose
 * text names Dolby Vision, whatever else it names; [HDR] one naming HDR10+, HDR10, HDR or HLG; [SDR] one naming none.
 */
enum class StreamRange(val key: String) {
    SDR("sdr"),
    HDR("hdr"),
    DOLBY_VISION("dv"),
    ;

    companion object {
        fun fromKey(key: String): StreamRange? = entries.firstOrNull { it.key == key }
    }
}

/** A video codec of the stream filter (`ProfileSetting.HiddenStreamCodecs`); [UNKNOWN] is a stream whose text names none. */
enum class StreamCodec(val key: String) {
    AVC("avc"),
    HEVC("hevc"),
    AV1("av1"),
    VP9("vp9"),
    XVID("xvid"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromKey(key: String): StreamCodec? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A list of a stream setting's values as their keys. A key this version doesn't know (a value a later version added)
 * is dropped instead of failing the whole list, so the rest still applies.
 */
internal class KeyedListSerializer<T : Any>(private val fromKey: (String) -> T?, private val keyOf: (T) -> String) : KSerializer<List<T>> {
    private val keys = ListSerializer(String.serializer())
    override val descriptor = keys.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = keys.serialize(encoder, value.map(keyOf))

    override fun deserialize(decoder: Decoder): List<T> = keys.deserialize(decoder).mapNotNull(fromKey)
}
