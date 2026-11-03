package com.edde746.tortuga.presentation.search

import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.presentation.menu.MediaMenuHost

/**
 * Search: every catalog that takes a `search` extra (and needs nothing else), queried as the
 * user types (stremio-core `CatalogsWithExtra` with a `search` extra).
 *
 * @param query the text as typed.
 * @param searching a typed query waits for its debounce, or some catalog hasn't answered: the field's spinner.
 * @param body what shows under the field.
 */
data class SearchUiState(
    val query: String,
    val searching: Boolean,
    val body: SearchBody,
)

/** One of [SearchBody.Recent.recent]: the [query] as searched, and its remove button's [removeLabel] ("Remove chernobyl"). */
data class RecentSearch(val query: String, val removeLabel: String)

/**
 * What Search shows under its field, the first that holds: [Offline] while the device is offline (the results are asked
 * again once it is back); with a blank query the [Recent] searches, or the [Hint] when there are none; [Loading] until a
 * query's first results; [Empty] when every catalog answered and none found anything; else the [Results].
 */
sealed interface SearchBody {
    data class Offline(val offline: OfflineState) : SearchBody

    /** [recent]: this profile's recent searches on this device, newest first, never none. Heading: `T.browse.search.recent`. */
    data class Recent(val recent: List<RecentSearch>) : SearchBody

    /** A blank query and no recent search: the hint, `T.browse.search.hint`. */
    data object Hint : SearchBody

    data object Loading : SearchBody

    /** [text]: "No results for “…”", or no addon can search, or the addons can't be read. */
    data class Empty(val text: String) : SearchBody

    /**
     * [groups]: the results for the last query searched, one per catalog in addon order; a catalog with no results is
     * left out. They stay while a new query waits for its debounce.
     */
    data class Results(val groups: List<SearchGroup>) : SearchBody
}

/**
 * The results of one search catalog.
 *
 * @param key stable across updates (not the transport URL): a list key for views.
 * @param title the catalog's type ("Movies"), with the catalog's name when the addon searches several catalogs of
 *   that type ("Popular · Movies").
 * @param items the pages loaded, each id once.
 */
data class SearchGroup(
    val key: String,
    val title: String,
    val addonName: String,
    val status: SearchGroupStatus,
    val items: List<MetaCard>,
)

/** Where a [SearchGroup] stands: every catalog is asked as the query is searched. */
sealed interface SearchGroupStatus {
    data object Loading : SearchGroupStatus

    /** [loadingMore]: its next page is on its way ([SearchStore.loadNextPage]). */
    data class Ready(val loadingMore: Boolean) : SearchGroupStatus

    /** [text]: why the catalog failed, worded. */
    data class Failed(val text: String) : SearchGroupStatus
}

/** What Search asks of the app around it; called on the main thread. */
interface SearchHost : MediaMenuHost {
    /** Open Library on Downloads: the offline state's action. */
    fun openDownloads()
}
