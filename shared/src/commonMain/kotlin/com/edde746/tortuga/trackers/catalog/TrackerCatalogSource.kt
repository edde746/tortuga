package com.edde746.tortuga.trackers.catalog

import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerCredentials
import com.edde746.tortuga.trackers.TrackerService

/**
 * One list of a connected tracker account, shown as a row next to the addon catalogs.
 *
 * @param id stable within the account and service (`watchlist`, `trending.movie`, `list.1234`; a public list's own id:
 *   `1234`, `user/slug`).
 * @param type the Stremio type of the row's items (`movie`, `series`), or `null` when the row mixes both (watchlists,
 *   MDBList lists).
 * @param name the list's own name for [Kind.LIST] (MDBList's lists) and [Kind.PUBLIC_LIST]; the other kinds are titled
 *   by the app.
 */
data class TrackerCatalog(
    val service: TrackerService,
    val id: String,
    val kind: Kind,
    val type: String?,
    val name: String? = null,
) {
    enum class Kind {
        /** Trakt's and MDBList's watchlist. */
        WATCHLIST,

        /** Trakt's personal recommendations. */
        RECOMMENDED,

        /** Trakt's trending movies or shows. */
        TRENDING,

        /** Simkl's plan-to-watch. */
        PLAN_TO_WATCH,

        /** MyAnimeList's plan-to-watch and AniList's planning list. */
        PLANNING,

        /** Anime airing now, by MyAnimeList's ranking or AniList's popularity. */
        AIRING,

        /** A list the user made (MDBList). */
        LIST,

        /**
         * A public list by its URL, readable by anyone: Trakt's numeric list id
         * ([com.edde746.tortuga.trackers.trakt.TraktLists]), MDBList's `user/slug` or numeric id.
         */
        PUBLIC_LIST,
    }
}

/**
 * One page of a [TrackerCatalog]. Items are Stremio meta previews whose ids the installed addons resolve (`tt…`,
 * `tmdb:…`, `mal:…`, `anilist:…`): the row shows the tracker's name and poster, the item's page loads through the
 * addons. Items without an id any addon could take are left out.
 *
 * @param next the cursor of the next page, `null` on the last one.
 */
class TrackerCatalogPage(val items: List<MetaItemPreview>, val next: String?)

/** The read side of one connected tracker account. Calls throw [com.edde746.tortuga.trackers.TrackerException]. */
interface TrackerCatalogSource {
    val service: TrackerService

    /** The rows this account offers, in display order (MDBList's lists are the account's own). */
    suspend fun catalogs(): List<TrackerCatalog>

    /** The page of [catalog] at [cursor] (`null`: the first). */
    suspend fun page(catalog: TrackerCatalog, cursor: String? = null): TrackerCatalogPage

    /**
     * The public list [input] names (its page's link, or its id) as a [TrackerCatalog.Kind.PUBLIC_LIST] with its name;
     * null when [input] names no list of this service's, and for a service without public lists by link (Simkl, MAL,
     * AniList).
     *
     * @throws com.edde746.tortuga.trackers.TrackerException when the service can't be asked or has no such list.
     */
    suspend fun publicList(input: String): TrackerCatalog? = null
}

/**
 * A [com.edde746.tortuga.trackers.TrackerProvider] whose service also has a read side. The five services' sources
 * also implement [TrackerWatchlist] for the list they read.
 */
interface TrackerCatalogProvider {
    /** The read side of the account behind [credentials]; a new instance per connection. */
    fun catalog(credentials: TrackerCredentials): TrackerCatalogSource
}
