package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.BoundTracker
import com.edde746.tortuga.trackers.TrackerContentType
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.WatchlistLine
import com.edde746.tortuga.trackers.WatchlistState
import com.edde746.tortuga.trackers.catalog.WatchlistMembership

// The Watchlist action: a title put on, or taken off, the lists the profile's trackers keep (Trakt's and MDBList's
// watchlist, Simkl's plan to watch, MyAnimeList's plan to watch and AniList's planning, the anime lists for anime
// only), apart from the library, which is the app's own.

/**
 * A title's Watchlist entry in a card's menu ([mediaMenu]): with one tracker's list ([service]), Add to or Remove from
 * that list by [listed] (unknown counts as not listed); with several ([service] null), "Tracker watchlists", which
 * opens the sheet ([WatchlistSheet]).
 */
data class MenuWatchlist(val service: TrackerService?, val listed: Boolean)

/** A title the Watchlist sheet is about: its type, id and name, and its meta where the screen has it. */
class WatchlistRequest(val type: String, val id: String, val name: String, val meta: MetaItem? = null)

/**
 * The Watchlist sheet ([com.edde746.tortuga.app.AppSessionState.watchlist]) of a title whose type several of the
 * profile's trackers take: one row per tracker, each toggled on its own
 * ([com.edde746.tortuga.app.AppSession.toggleWatchlist]).
 *
 * @param title "Tracker watchlists".
 * @param hint that the lists are the services' and the library stays as it is.
 * @param rows in the services' order; empty while the trackers that take the title are found.
 */
data class WatchlistSheet(val title: String, val hint: String, val rows: List<WatchlistRow>)

/**
 * One tracker's row of the [WatchlistSheet]: its list's [name] ("Trakt watchlist", "Simkl plan to watch") with a check
 * while the title is [listed] (never while that is unknown), and [status] while a change isn't through: "Saving…",
 * "Not sent yet, will retry", "Not saved". [busy]: a write is on its way; [attention]: a change isn't saved yet.
 */
data class WatchlistRow(
    val service: TrackerService,
    val name: String,
    val listed: Boolean,
    val status: String?,
    val busy: Boolean,
    val attention: Boolean,
)

/** [service]'s list as the user knows it from the service: "Trakt watchlist", "AniList planning". */
fun watchlistName(service: TrackerService): String = with(T.watchlist) {
    when (service) {
        TrackerService.TRAKT -> trakt
        TrackerService.SIMKL -> simkl
        TrackerService.MDBLIST -> mdblist
        TrackerService.MYANIMELIST -> myAnimeList
        TrackerService.ANILIST -> aniList
    }
}

/** The Watchlist action's words for [lines]: Add to or Remove from the one list, else "Tracker watchlists". */
internal fun watchlistLabel(lines: List<WatchlistLine>): String = when (val line = lines.singleOrNull()) {
    null -> T.watchlist.lists
    else -> if (line.listed == true) T.watchlist.remove(watchlistName(line.service)) else T.watchlist.add(watchlistName(line.service))
}

/** The sheet's rows for [lines]. */
internal fun watchlistSheet(lines: List<WatchlistLine>): WatchlistSheet = WatchlistSheet(
    title = T.watchlist.lists,
    hint = T.watchlist.hint,
    rows = lines.map { line ->
        WatchlistRow(
            service = line.service,
            name = watchlistName(line.service),
            listed = line.listed == true,
            status = when (line.state) {
                WatchlistState.IDLE -> null
                WatchlistState.SAVING -> T.watchlist.saving
                WatchlistState.QUEUED -> T.watchlist.queued
                WatchlistState.FAILED -> T.watchlist.failed
            },
            busy = line.state == WatchlistState.SAVING,
            attention = line.state == WatchlistState.QUEUED || line.state == WatchlistState.FAILED,
        )
    },
)

/**
 * What the cards' menus offer of the profile's tracker lists, as known when it was made ([MediaActions.watchlistMarks]).
 * A card says only its type and id, so its content type is read from them: an anime list's id (`kitsu:`, `mal:`,
 * `anilist:`, `anidb:`) is anime, which the anime lists take; a movie or series by any other id is not (its Detail page,
 * which resolves the title, offers them where it is).
 */
internal class WatchlistMarks(private val trackers: List<BoundTracker>) {
    /** [type]/[id]'s entry: null where no tracker's list takes such a title. */
    fun menu(type: String, id: String): MenuWatchlist? {
        val contentType = when {
            ANIME_PREFIXES.any(id::startsWith) -> TrackerContentType.ANIME
            type == MOVIE -> TrackerContentType.MOVIE
            type == SERIES -> TrackerContentType.SERIES
            else -> return null
        }
        val takers = trackers.filter { it.takes(contentType) && (!it.tracker.needsAnimeIds || contentType == TrackerContentType.ANIME) }
        val one = takers.singleOrNull() ?: return if (takers.isEmpty()) null else MenuWatchlist(null, listed = false)
        val listed = one.watchlist?.membership?.listed(setOf(WatchlistMembership.titleKey(type, id))) == true
        return MenuWatchlist(one.service, listed)
    }

    internal companion object {
        val NONE = WatchlistMarks(emptyList())
        private val ANIME_PREFIXES = listOf("kitsu:", "mal:", "anilist:", "anidb:")
        private const val MOVIE = "movie"
        private const val SERIES = "series"
    }
}
