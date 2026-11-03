package com.edde746.tortuga.presentation.discover

import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.presentation.menu.MediaMenuHost

/**
 * Discover: one catalog, picked by type, catalog and the catalog's filters, paged as the user
 * scrolls (stremio-core `CatalogWithFilters`).
 *
 * @param title the selected catalog, "Popular · Movies"; null before one is selected.
 * @param types every type some catalog has, in Stremio's type order (all, movie, series, channel, tv, then others by
 *   name, `other` last); null while the installed addons aren't read or no addon has a catalog.
 * @param catalogs the catalogs of the selected type: the addons' catalogs in addon order, then the connected trackers'
 *   lists of that type (a list of both types, such as a watchlist, is offered under movies and
 *   under series, with that type's items).
 * @param filters the selected catalog's extras that have options (genre, year…), in the manifest's order.
 * @param body what shows under the filters.
 */
data class DiscoverUiState(
    val title: String?,
    val types: DiscoverPicker?,
    val catalogs: DiscoverPicker?,
    val filters: List<DiscoverPicker>,
    val body: DiscoverBody,
)

/**
 * What Discover shows under its filters, the first that holds: [Offline] while the device is offline (the catalog loads
 * again once it is back), [Loading] while the first page (or the addon list) loads and no card is there yet, [Failed]
 * when the first page failed, [Empty] when there is nothing to show and nothing loading, else the [Grid].
 */
sealed interface DiscoverBody {
    data class Offline(val offline: OfflineState) : DiscoverBody

    data object Loading : DiscoverBody

    /** [text]: why the first page failed, worded ([DiscoverStore.retry] tries again). */
    data class Failed(val text: String) : DiscoverBody

    /** [text]: the catalog is empty, or no addon has a catalog. */
    data class Empty(val text: String) : DiscoverBody

    /** [items]: every page loaded so far, each id once; [more]: where the next page stands. */
    data class Grid(val items: List<MetaCard>, val more: DiscoverMore) : DiscoverBody
}

/** Where the [grid][DiscoverBody.Grid]'s next page stands, the first that holds: [Failed], [Loading], [Available], [None]. */
sealed interface DiscoverMore {
    /** Nothing after the cards shown. */
    data object None : DiscoverMore

    /**
     * [DiscoverStore.loadNextPage] has something to load: a page, or on a kids profile the cards after those its grid
     * went through.
     */
    data object Available : DiscoverMore

    data object Loading : DiscoverMore

    /** [text]: why the last page failed, worded ([DiscoverStore.retry] tries again). */
    data class Failed(val text: String) : DiscoverMore
}

/**
 * One choice of the selection: the type, the catalog or one filter.
 *
 * @param label what is being picked: "Type", "Catalog", "Genre".
 * @param selection the chosen options' labels ("Drama", "All").
 * @param isRequired a value is always selected (no "All" option).
 * @param multiSelect several options can be on at once (the extra's `optionsLimit` is above 1); picking an option
 *   toggles it.
 */
data class DiscoverPicker(
    val label: String,
    val selection: String,
    val isRequired: Boolean,
    val multiSelect: Boolean,
    val options: List<DiscoverOption>,
) {
    /**
     * Its closed chip: the type, the catalog and an extra the addon requires show their selection; a filter its name
     * until it narrows the catalog ("Genre", then "Drama").
     */
    val chip: FilterChip
        get() = if (isRequired) FilterChip.choice(label, selection) else FilterChip.narrowing(label, selection, narrowed = options.firstOrNull()?.selected == false)
}

/**
 * One option of a [DiscoverPicker].
 *
 * @param detail a second line: the addon or tracker service of a catalog ("Cinemeta", "Trakt").
 */
data class DiscoverOption(
    val label: String,
    val detail: String?,
    val selected: Boolean,
)

/** What Discover asks of the app around it; called on the main thread. */
interface DiscoverHost : MediaMenuHost {
    /** Open Library on Downloads: the offline state's action. */
    fun openDownloads()
}
