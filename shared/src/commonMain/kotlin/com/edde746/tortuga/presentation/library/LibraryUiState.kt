package com.edde746.tortuga.presentation.library

import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.newEpisodesBadge
import com.edde746.tortuga.presentation.board.newEpisodesDescription
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.presentation.menu.MediaMenuHost
import com.edde746.tortuga.strings.T

/** Which items a [LibraryStore] lists (stremio-core `LibraryWithFilters` with its two filters). */
enum class LibraryMode {
    /** The user's library: items added and not removed. */
    LIBRARY,

    /** Items with a position to resume, in the library or only played. */
    CONTINUE_WATCHING,
}

/** stremio-core's library `Sort`, plus [ADDED]. */
enum class LibrarySort {
    /** Most recently watched first (the default). */
    LAST_WATCHED,

    /** Most recently added (created) first. */
    ADDED,
    NAME,
    NAME_REVERSE,

    /** Watched most often first. */
    TIMES_WATCHED,

    /** Watched items first, each group most recently watched first. */
    WATCHED,

    /** Unwatched items first, each group least recently watched first. */
    NOT_WATCHED,
}

/**
 * The Library or the Continue Watching screen. Every text is ready to show.
 *
 * @param types the type filter: All, then the types present and the selected one, in Stremio's type order (movies and
 *   series first).
 * @param body what shows under the filters ([LibraryBody]).
 * @param continueWatching the Continue Watching screen: opening an item resumes its video ([LibraryStore.open]), so a
 *   TV remote's Play/Pause opens it too, as on Home's Continue Watching cards.
 */
data class LibraryUiState(
    val title: String,
    val types: List<LibraryTypeOption>,
    val sorts: List<LibrarySortOption>,
    val body: LibraryBody,
    val continueWatching: Boolean = false,
) {
    /** The filters have something to act on: more than one type, or items to sort. */
    val showsFilters: Boolean
        get() = when (body) {
            LibraryBody.Loading -> false
            is LibraryBody.Empty -> types.size > 1
            is LibraryBody.Grid -> true
        }

    /** The type filter shows with the sort: there is more than one type. */
    val showsTypes: Boolean
        get() = showsFilters && types.size > 1

    /** The type filter's chip: "Type" while every type shows, else the type ("Movies"); null without types. */
    val typeChip: FilterChip?
        get() = types.firstOrNull { it.selected }?.let { FilterChip.narrowing(T.browse.discover.type, it.label, narrowed = it.type != null) }

    /** The sort's chip: the order ("Name"), heard as "Sort by: Name"; null while there are no sorts. */
    val sortChip: FilterChip?
        get() = sorts.firstOrNull { it.selected }?.let { FilterChip(T.library.sortBy, it.label, T.library.sortByNamed(it.label), narrowed = false) }
}

/**
 * What shows under the filters, the first that applies: a spinner while the library hasn't been read ([Loading]); the
 * items that match ([Grid]); a spinner while a kids profile's items wait for their verdicts, as one may match once
 * judged ([Loading]); else why nothing matches: nothing of the selected type, an empty library or nothing to continue
 * ([Empty]).
 */
sealed interface LibraryBody {
    data object Loading : LibraryBody

    /** [text] says why nothing matches. */
    data class Empty(val text: String) : LibraryBody

    /**
     * @param items the first pages of the filtered, sorted items, never none ([LibraryStore.loadNextPage] adds a page).
     * @param hasMore more items than [items] match.
     */
    data class Grid(val items: List<LibraryRow>, val hasMore: Boolean) : LibraryBody
}

/** A type to filter by; a null [type] is every type. */
data class LibraryTypeOption(val type: String?, val label: String, val selected: Boolean)

data class LibrarySortOption(val sort: LibrarySort, val label: String, val selected: Boolean)

/**
 * One library item.
 *
 * @param progress the resume position, 0 to 1, when there is one.
 * @param watched watched at least once.
 * @param menu the item's menu (long press, right click): [LibraryStore.menuAction] carries an entry out.
 * @param newEpisodes the series' episodes out since it was last watched, as its addons list them (the badge).
 * @param caption its name shows under the artwork; false shows the artwork alone (Settings → Appearance → Hide titles
 *   under posters; Continue Watching's rows always show it), while screen readers still read it.
 */
data class LibraryRow(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val progress: Double?,
    val watched: Boolean,
    val menu: List<MediaMenuEntry>,
    val newEpisodes: Int = 0,
    val caption: Boolean = true,
) {
    /** The corner's count of [newEpisodes] ("2"); null with none. */
    val badge: String?
        get() = newEpisodesBadge(newEpisodes)

    /** What [badge] says, for assistive technologies ("2 new episodes"); null with none. */
    val badgeDescription: String?
        get() = newEpisodesDescription(newEpisodes)
}

/** What the Library screens ask of the app around them; called on the main thread. */
interface LibraryHost : MediaMenuHost {
    /**
     * Resume a Continue Watching item at [videoId]: its Detail screen, playing that video as Detail's Resume does
     * (auto-play's pick, else the stream it last played from, else its stream list).
     */
    fun openVideo(type: String, id: String, videoId: String)

    /** Open the Calendar over the Library screen: its Calendar button. */
    fun openCalendar()
}
