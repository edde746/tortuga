package com.edde746.tortuga.watch.media

import com.edde746.tortuga.model.ImdbEpisode
import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.videoIsTitle

/** A video as addons are asked about it: [type] and [videoId] for its streams, [type] and [metaId] for its meta. */
internal data class AddonVideo(val type: String, val metaId: String, val videoId: String)

/** The room's video under the host's own ids. */
internal val RoomMedia.ownVideo: AddonVideo get() = AddonVideo(type, metaId, videoId)

/**
 * The room's video under its mapped ids, for a guest whose addons don't answer the host's own: the IMDb form (`tt…`,
 * `tt…:season:episode`), then the TMDB form (`tmdb:…`, `tmdb:…:season:episode`), typed as Cinemeta-style addons take
 * them: `series` for an episode, `movie` (or the room's `series`) for a title played as a whole.
 *
 * None for a video that isn't the title itself and has no mapped season and episode: the bare title id would ask for
 * another video. A form that is the host's own request is left out, and so are mapped ids that aren't well formed
 * (they come from another participant).
 */
internal fun RoomMedia.mappedVideos(): List<AddonVideo> {
    val season = season
    val episode = episode
    val placed = season != null && episode != null && season >= 0 && episode >= 0
    val mappedType = when {
        placed -> SERIES
        videoIsTitle(metaId, videoId) -> if (type == SERIES) SERIES else MOVIE
        else -> return emptyList()
    }
    val suffix = if (placed) ":$season:$episode" else ""
    return listOfNotNull(imdbTitle(), tmdbTitle())
        .map { title -> AddonVideo(mappedType, title, title + suffix) }
        .filterNot { it.type == type && it.videoId == videoId }
}

/** The room's video under its IMDb id, when it has one ([mappedVideos]). */
internal fun RoomMedia.imdbVideo(): AddonVideo? = mappedVideos().firstOrNull { ImdbId.parse(it.metaId) != null }

/**
 * A meta for the room's title when none of the guest's addons has one: the host's title and, for a video that isn't
 * the title itself, that one video, titled with the host's episode line (the guest's player shows the line the
 * host's shows) and placed on IMDb when the room says where, so the guest's trackers can identify it.
 */
internal fun RoomMedia.minimalMeta(): MetaItem {
    val imdb = imdbTitle()
    val season = season
    val episode = episode
    val video = if (videoIsTitle(metaId, videoId)) {
        null
    } else {
        Video(
            id = videoId,
            title = episodeLine.orEmpty(),
            imdb = if (imdb != null && season != null && episode != null) ImdbEpisode(imdb, season, episode) else null,
        )
    }
    return MetaItem(MetaItemPreview(id = metaId, type = type, name = title, imdbId = imdb), listOfNotNull(video))
}

/** [RoomMedia.imdbId] when it is an IMDb title id. */
private fun RoomMedia.imdbTitle(): String? = imdbId?.takeIf { ImdbId.parse(it) != null }

/** [RoomMedia.tmdbId] as a Stremio id (`tmdb:1399`) when it is a TMDB id. */
private fun RoomMedia.tmdbTitle(): String? =
    (tmdbId?.let { StremioId.parse("$TMDB_PREFIX$it") } as? StremioId.Tmdb)?.takeIf { it.season == null }?.let { "$TMDB_PREFIX${it.id}" }

private const val MOVIE = "movie"
private const val SERIES = "series"
private const val TMDB_PREFIX = "tmdb:"
