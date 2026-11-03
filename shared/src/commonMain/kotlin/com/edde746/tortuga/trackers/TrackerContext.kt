package com.edde746.tortuga.trackers

import kotlinx.serialization.Serializable

/** Movie- or show-level ids that Trakt, Simkl and MDBList accept. */
@Serializable
data class ExternalIds(
    /** `tt0944947`. */
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
) {
    val isEmpty: Boolean get() = imdb == null && tmdb == null && tvdb == null

    /**
     * One id in a fixed preference order, as a stable name for the remote row.
     * One rather than all: which ids an item has varies with the addon and with the mapping datasets, and a key that
     * moved with them would stop matching rows already queued.
     */
    fun rowIdentity(): String? = when {
        imdb != null -> "imdb=$imdb"
        tmdb != null -> "tmdb=$tmdb"
        tvdb != null -> "tvdb=$tvdb"
        else -> null
    }
}

/** Anime-list ids, from the Fribb and Anime-Lists datasets or the Stremio id itself (`kitsu:`, `mal:`, …). */
@Serializable
data class AnimeIds(
    val mal: Int? = null,
    val anilist: Int? = null,
    val simkl: Int? = null,
    val kitsu: Int? = null,
    val anidb: Int? = null,
) {
    val isEmpty: Boolean get() = mal == null && anilist == null && simkl == null && kitsu == null && anidb == null
}

/** What was played or marked: a movie, or one episode. */
enum class TrackerMediaKind { MOVIE, EPISODE }

/** The per-profile content filters of a tracker account: movie / series / anime. */
enum class TrackerContentType { MOVIE, SERIES, ANIME }

/**
 * The item a tracker write is about, built once per playback or mark by the coordinator from the Stremio ids and the
 * [TrackerIdResolver]'s answer. It is stored with a queued write, so a replay writes exactly this item without
 * resolving it again.
 *
 * Numbers are in the numbering each kind of service uses: [season]/[episode] are the external ids' (IMDb/TVDB), which
 * can differ from the Stremio id's own (`kitsu:12268:1` is `tt5626028` S2E1); [animeEpisode] and [animeProgress]
 * are the anime list entry's.
 */
@Serializable
data class TrackerContext(
    /** The Stremio meta id (`tt0944947`, `tmdb:1399`, `kitsu:1376`). Logged and keyed on, never sent. */
    val metaId: String,
    /** The Stremio video id (a movie's is its meta id; `tt0944947:1:2`, `kitsu:1376:5`). */
    val videoId: String,
    val kind: TrackerMediaKind,
    /** Which of an account's content filters applies. */
    val contentType: TrackerContentType,
    val ids: ExternalIds = ExternalIds(),
    val anime: AnimeIds? = null,
    /** External numbering; set for [TrackerMediaKind.EPISODE] when the external ids know the episode. */
    val season: Int? = null,
    val episode: Int? = null,
    /** The episode's number on the anime list entry. */
    val animeEpisode: Int? = null,
    /**
     * The absolute progress a watched write claims on the anime list entry (episodes watched, this one included);
     * falls back to [animeEpisode]. Series-progress trackers write it; claims only move forward.
     */
    val animeProgress: Int? = null,
) {
    val isMovie: Boolean get() = kind == TrackerMediaKind.MOVIE
}

/** What a rating is about. Movies and shows rate everywhere; seasons and episodes where the service supports it. */
enum class RatingKind { MOVIE, SHOW, SEASON, EPISODE }

/** The target of a rating read or write; stored with a queued rating, so a replay rates exactly this. */
@Serializable
data class RatingTarget(
    val kind: RatingKind,
    val ids: ExternalIds = ExternalIds(),
    val anime: AnimeIds? = null,
    /** External numbering, for [RatingKind.SEASON] and [RatingKind.EPISODE]. */
    val season: Int? = null,
    val episode: Int? = null,
)
