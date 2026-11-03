package com.edde746.tortuga.watch.media

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.videoIsTitle

/**
 * What a guest's player plays for the room's media: [videoId] of [meta], under the guest's own ids.
 *
 * @property minimal [meta] is [RoomMedia]'s own (none of the guest's addons had the title): the host's title and
 *   episode line only.
 */
data class RoomTitle(val meta: MetaItem, val videoId: String, val minimal: Boolean = false)

/**
 * Finds the guest's meta for a room's media, from the profile's installed addons, as Detail picks a meta
 * ([AddonAggregator.firstMeta]):
 * 1. the meta of the room's type and id, with the room's video;
 * 2. else the meta of the room's IMDb id ([RoomMedia.imdbId]), with its video of the room's season and episode (or the
 *    title itself, for a title played as a whole);
 * 3. else a meta made of the room's media ([RoomMedia]'s title and episode line), so playback still starts.
 */
class RoomTitleResolver internal constructor(
    private val aggregator: AddonAggregator,
    private val installedAddons: suspend () -> List<Descriptor>,
) {
    suspend fun resolve(media: RoomMedia): RoomTitle {
        val addons = installedAddons()
        aggregator.firstMeta(addons, media.type, media.metaId)?.let { return RoomTitle(it, media.videoId) }
        val imdb = media.imdbVideo()
        if (imdb != null) {
            val meta = aggregator.firstMeta(addons, imdb.type, imdb.metaId)
            if (meta != null) videoOf(meta, media, imdb)?.let { return RoomTitle(meta, it) }
        }
        return RoomTitle(media.minimalMeta(), media.videoId, minimal = true)
    }

    /** [meta]'s video for the room's, asked about as [imdb]: by season and episode, else by id; null when it has none. */
    private fun videoOf(meta: MetaItem, media: RoomMedia, imdb: AddonVideo): String? {
        if (videoIsTitle(imdb.metaId, imdb.videoId)) return meta.id
        val season = media.season ?: return null
        val episode = media.episode ?: return null
        val placed = meta.videos.firstOrNull { it.seriesInfo == SeriesInfo(season, episode) }
            ?: meta.videos.firstOrNull { it.id == imdb.videoId }
        return placed?.id
    }
}
