package com.edde746.tortuga.model

import kotlinx.serialization.Serializable

/** Resource names of the addon protocol (stremio-core `constants.rs`). */
object Resources {
    const val CATALOG = "catalog"
    const val META = "meta"
    const val STREAM = "stream"
    const val SUBTITLES = "subtitles"
    const val ADDON_CATALOG = "addon_catalog"

    /** The watch-activity events ([com.edde746.tortuga.addons.AddonEvents]): sent, never asked for content. */
    const val PLAYER = "player"
    const val LIBRARY = "library"
}

/** Extra property names with a meaning to the client. */
object Extras {
    const val SEARCH = "search"
    const val SKIP = "skip"
    const val GENRE = "genre"
    const val VIDEO_HASH = "videoHash"
    const val VIDEO_SIZE = "videoSize"
    const val FILENAME = "filename"

    /** A title's id, to a catalog that lists titles like it (an addon protocol extension; Detail's "More like this"). */
    const val SIMILAR = "similar"

    /** A guide catalog's day, `2026-09-12`: a UTC calendar day ([ManifestCatalog.declaresDate]). */
    const val DATE = "date"

    /** The newest episodes of the series whose ids it lists (new-episode badges). */
    const val LAST_VIDEOS_IDS = "lastVideosIds"

    /** The episodes around now, future ones included, of the series whose ids it lists (the calendar). */
    const val CALENDAR_VIDEOS_IDS = "calendarVideosIds"
}

/** One `name=value` pair of a request's extra segment. Names may repeat (multi-select extras). */
@Serializable
data class ExtraValue(val name: String, val value: String)

/**
 * What to request from an addon: `{resource}/{type}/{id}[/{extra}].json` (stremio-core `ResourcePath`).
 * [extra] keeps its order; the transport encodes it in that order.
 */
@Serializable
data class ResourcePath(
    val resource: String,
    val type: String,
    val id: String,
    val extra: List<ExtraValue> = emptyList(),
)

/** A [ResourcePath] addressed to one addon, identified by its transport URL ([base], ending in `/manifest.json`). */
data class ResourceRequest(val base: TransportUrl, val path: ResourcePath)

/**
 * `ExtraExt::extend_one` (stremio-core `types/addon/request.rs`): sets [value] for the extra property [prop]. With an
 * `optionsLimit` of 1 it replaces the property's value; above 1 it toggles: a value already set goes, a new one goes
 * first and the oldest beyond the limit go. A null [value] removes every value of a property that isn't required. The
 * other properties keep their order after it. Unlike stremio-core, a required property never loses its last value
 * (toggling it off changes nothing), so a required extra is never left out of a request.
 */
fun List<ExtraValue>.withExtra(prop: ExtraProp, value: String?): List<ExtraValue> {
    val (own, others) = partition { it.name == prop.name }
    val replaced = when {
        value != null && prop.optionsLimit == 1 -> listOf(ExtraValue(prop.name, value))
        value != null && prop.optionsLimit > 1 ->
            if (own.any { it.value == value }) {
                own.filter { it.value != value }.takeUnless { it.isEmpty() && prop.isRequired } ?: own
            } else {
                (listOf(ExtraValue(prop.name, value)) + own).take(prop.optionsLimit)
            }
        value == null && !prop.isRequired -> emptyList()
        prop.optionsLimit == 0 -> emptyList()
        else -> own
    }
    return replaced + others
}
