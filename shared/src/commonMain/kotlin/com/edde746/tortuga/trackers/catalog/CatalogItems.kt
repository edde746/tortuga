package com.edde746.tortuga.trackers.catalog

import com.edde746.tortuga.model.ImdbId

/** Stremio ids and types for tracker catalog items. */
internal object CatalogItems {
    const val MOVIE = "movie"
    const val SERIES = "series"

    /**
     * The id Cinemeta-style addons resolve: the IMDb id, else `tmdb:<id>` (the TMDB addons' form). `null` when the
     * item has neither, and no addon could open it.
     */
    fun stremioId(imdb: String?, tmdb: Long?): String? = when {
        imdb != null && ImdbId.parse(imdb) != null -> imdb
        tmdb != null && tmdb > 0 -> "tmdb:$tmdb"
        else -> null
    }

    /**
     * An anime-list id in the form Stremio anime addons take (`kitsu:`, `mal:`, `anilist:`, `anidb:`; the Anime
     * Kitsu addon lists all four prefixes), for items without an IMDb or TMDB id. Kitsu first: most anime addons key
     * on it.
     */
    fun animeId(kitsu: Long? = null, mal: Long? = null, anilist: Long? = null, anidb: Long? = null): String? = when {
        kitsu != null && kitsu > 0 -> "kitsu:$kitsu"
        mal != null && mal > 0 -> "mal:$mal"
        anilist != null && anilist > 0 -> "anilist:$anilist"
        anidb != null && anidb > 0 -> "anidb:$anidb"
        else -> null
    }

    /** `2010`, or null for a missing or nonsensical year. */
    fun year(value: Long?): String? = value?.takeIf { it in 1800..9999 }?.toString()

    /** The protocol-less image URLs some services send (`walter-r2.trakt.tv/…`) made absolute. */
    fun absoluteUrl(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://$url"
    }
}
