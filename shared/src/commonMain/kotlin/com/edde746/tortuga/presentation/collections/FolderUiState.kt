package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.menu.MediaMenuHost

/**
 * A collection's folder ([com.edde746.tortuga.presentation.navigation.Route.CollectionFolder], [FolderStore]): its
 * sources' titles as tabs over a grid ([FolderLayout.TABS]) or as rows ([FolderLayout.ROWS]).
 *
 * @param title the folder's title; empty until the collection is read.
 * @param backdropUrl the collection's backdrop, shown behind the page; null for none.
 * @param tabs [FolderLayout.TABS]: All first when the collection shows it and the folder has more than one source,
 *   then one per source; empty for [FolderLayout.ROWS].
 * @param selectedTab the index in [tabs] of the tab whose titles [grid] shows ([FolderStore.selectTab]).
 * @param grid [FolderLayout.TABS]: the selected tab's titles; null for [FolderLayout.ROWS].
 * @param rows [FolderLayout.ROWS]: one per source, in the folder's order; empty for [FolderLayout.TABS].
 * @param gone the folder or its collection was deleted (here or on another device): the screen goes back.
 */
data class FolderUiState(
    val title: String,
    val backdropUrl: String?,
    val layout: FolderLayout,
    val tabs: List<FolderTab>,
    val selectedTab: Int,
    val grid: FolderGrid?,
    val rows: List<FolderRow>,
    val gone: Boolean,
)

/** How a folder shows its sources: the collection's view mode (Follow the app shows rows, as Home does). */
enum class FolderLayout { TABS, ROWS }

/** A tab of a [FolderLayout.TABS] folder: "All", or a source's title ("Popular · Movies", "Watchlist"). */
data class FolderTab(val key: String, val title: String)

/**
 * The selected tab's titles: a source's pages, or All's (the ready sources' titles taken in turn, one from each, each
 * title once). [FolderStore.gridNearEnd] loads the next pages.
 *
 * @param note in place of the titles: what the tab's source lacks ("Needs the addon AIOMetadata", "Connect Trakt"), or
 *   that it has no titles; All's when none of its sources can show (its first source's). Null otherwise.
 */
data class FolderGrid(val status: RowStatus, val items: List<MetaCard>, val note: FolderNote?)

/**
 * One source of a [FolderLayout.ROWS] folder, Home's row look; [RowStatus.Idle] until [FolderStore.rowVisible].
 *
 * @param title "Popular · Movies", "Watchlist", a list's name.
 * @param sourceName where it comes from: the addon's name ("Cinemeta"), the tracker's ("Trakt").
 * @param note in place of the items: what the source lacks, or that it has no titles ([FolderGrid.note]).
 */
data class FolderRow(
    val key: String,
    val title: String,
    val sourceName: String,
    val status: RowStatus,
    val items: List<MetaCard>,
    val note: FolderNote?,
)

/**
 * Why a source shows no titles, and what fixes it.
 *
 * @param actionLabel the button that does [action] ([FolderStore.noteAction]): "Go to Trackers", "Go to Addons"; null
 *   with no [action].
 */
data class FolderNote(val text: String, val action: FolderNoteAction?, val actionLabel: String?)

/** What a [FolderNote]'s button opens. */
enum class FolderNoteAction {
    /** Settings → Trackers: the source's tracker isn't connected. */
    CONNECT_TRACKER,

    /** Settings → Addons: the source's addon isn't installed, is turned off, or lacks the catalog. */
    OPEN_ADDONS,
}

/** What a folder asks of the app around it; called on the main thread. */
interface FolderHost : MediaMenuHost {
    /** Open Settings → Trackers: a note's [FolderNoteAction.CONNECT_TRACKER]. */
    fun openTrackers()

    /** Open Settings → Addons: a note's [FolderNoteAction.OPEN_ADDONS]. */
    fun openAddons()
}
