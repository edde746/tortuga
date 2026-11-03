package com.edde746.tortuga.trackers

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.StremioId

/**
 * A Stremio video to identify for the trackers.
 *
 * @param metaId the meta id (`tt0944947`, `tmdb:1399`, `kitsu:1376`, `mal:21`, `anilist:21`, `anidb:69`).
 * @param type the Stremio type (`movie`, `series`, `anime`, …).
 * @param videoId the video id; a movie's is its meta id. For a whole title (a manual mark of a movie or show from the
 *   library) it is the meta id too.
 * @param meta the addon's meta when at hand (the player and Detail have it; a library row doesn't): its links, its
 *   `imdb_id` and per-video ids fill ids the Stremio id doesn't carry.
 */
data class TrackerItem(
    val metaId: String,
    val type: String,
    val videoId: String,
    val meta: MetaItem? = null,
) {
    /** [metaId] parsed; `null` for an addon's own namespace. */
    internal val stremioId: StremioId? = StremioId.parse(metaId)
}

/**
 * What the [TrackerIdResolver] found for a [TrackerItem].
 *
 * @param ids movie- or show-level external ids.
 * @param anime anime-list ids, when the item is an anime the datasets (or the Stremio id) know.
 * @param season the episode in the external ids' numbering (IMDb/TVDB), for Trakt, Simkl and MDBList.
 * @param episode as [season].
 * @param animeEpisode the episode's number on the anime list entry, for MyAnimeList and AniList.
 * @param animeProgress episodes watched on the entry, this one included, when known (otherwise [animeEpisode]).
 */
data class ResolvedIds(
    val ids: ExternalIds,
    val anime: AnimeIds? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val animeEpisode: Int? = null,
    val animeProgress: Int? = null,
)

/** Maps Stremio ids to the ids each tracker service takes. Implemented in `trackers/ids`. */
interface TrackerIdResolver {
    /**
     * The ids for [item], or `null` when no service could identify it. With [animeIds] (an anime tracker is active),
     * anime ids are resolved too, which may download the mapping datasets; without it they are left out unless the
     * Stremio id itself is one.
     *
     * Throws only for failures worth a warning (a dataset download failing); an unknown item is `null`.
     */
    suspend fun resolve(item: TrackerItem, animeIds: Boolean): ResolvedIds?
}
