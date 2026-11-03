package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.menu.MediaMenuHost
import com.edde746.tortuga.trackers.TrackerService

/**
 * The Board: Continue Watching, then the lists of the profile's connected trackers
 * (Trakt's watchlist, recommendations and trending, MDBList's lists, …), then the first page of every
 * catalog without required extras of the installed addons, in addon order (stremio-core `CatalogsWithExtra` and
 * `ContinueWatchingPreview`).
 *
 * @param continueWatching most recently watched first; empty hides the row. Its heading is
 *   `T.browse.continueWatching.title`. Shown with the [rows][BoardBody.Rows] and while [offline][BoardBody.Offline].
 * @param body what shows under the shortcuts.
 * @param shortcuts Home's shortcuts, in order ([BoardStore.openShortcut]): Watch together ("3 watching together"
 *   while this device is in a room), Downloads where the device has them ("Downloading 2, 45%" while some of the
 *   profile's are on their way: queued, resolving or downloading, waiting for Wi-Fi included; paused, failed and
 *   completed ones aren't), Library, TV guide where an installed addon has a guide catalog, Calendar, then one per list
 *   of the connected trackers.
 */
data class BoardUiState(
    val continueWatching: List<ContinueWatchingCard>,
    val body: BoardBody,
    val shortcuts: List<BoardShortcut> = emptyList(),
)

/**
 * What the Board shows under its shortcuts, the first that holds: [Offline] while the device is offline (with Continue
 * Watching, where it has cards; the rows load again once it is back), [Loading] until the addons and the layout are
 * read, [Empty] when there is nothing to show at all, else the [Rows] with Continue Watching.
 */
sealed interface BoardBody {
    data class Offline(val offline: OfflineState) : BoardBody

    data object Loading : BoardBody

    /** [text]: why there is nothing to show (no catalog has items and nothing is in progress, or the addons can't be read). */
    data class Empty(val text: String) : BoardBody

    /**
     * [rows]: one per catalog. A catalog that answered with nothing is left out, so indices shift as rows settle:
     * intents take an index into the [rows] they were rendered from.
     */
    data class Rows(val rows: List<BoardRow>) : BoardBody
}

/**
 * One of Home's shortcuts, in [BoardUiState.shortcuts]' order.
 *
 * @param key `watchTogether`, `downloads`, `library`, `guide`, `calendar`, or `tracker.<service id>.<list id>` for a
 *   tracker's list: stable across updates, the views' identifiers.
 * @param kind what the shortcut opens, which picks its icon in a view.
 * @param label "Watch together", "Downloads", "Library", "TV guide", "Watchlist"; a tracker's list that would read as
 *   another shortcut does names its service ("Watchlist · Trakt").
 * @param tracker the service a tracker's list comes from, whose mark leads its chip in a view; null for the app's
 *   screens.
 */
data class BoardShortcut(
    val key: String,
    val kind: BoardShortcutKind,
    val label: String,
    val accessibilityLabel: String = label,
    val tracker: TrackerService? = null,
)

/** What a [BoardShortcut] opens: one of the app's screens, or a connected tracker's list. */
enum class BoardShortcutKind { WATCH_TOGETHER, DOWNLOADS, LIBRARY, GUIDE, CALENDAR, TRACKER_LIST }

/**
 * One catalog of the Board: an addon's catalog, or a connected tracker's list ([fromTracker]), whose items open
 * through the installed addons by their id like any other; or one of the profile's collections, its [folders] in
 * place of items.
 *
 * @param key stable across updates (not the transport URL): a list key for views; a collection's is
 *   `collection/<collection id>`.
 * @param title "Popular · Movies", "Watchlist", "Trending · Series"; a collection's own title.
 * @param sourceName where the row comes from: the addon's name ("Cinemeta") or the tracker service's ("Trakt");
 *   "Collection" for a collection.
 * @param status [RowStatus.Idle] until the row is near the screen ([BoardStore.rowVisible]); a collection's is always
 *   ready.
 * @param items the pages loaded, each id once; on a remote's UI at most [BoardStore.REMOTE_ROW_ITEMS] of them. None for
 *   a collection.
 * @param seeAll where the row offers its catalog in Discover ([BoardStore.seeAll]); [SeeAll.NONE] for a collection.
 * @param folders a collection's folder tiles, in its order ([BoardStore.openFolder]); empty for any other row.
 * @param folderShape the shape of every one of [folders]' tiles (the collection's).
 */
data class BoardRow(
    val key: String,
    val title: String,
    val sourceName: String,
    val fromTracker: Boolean,
    val status: RowStatus,
    val items: List<MetaCard>,
    val seeAll: SeeAll,
    val folders: List<FolderTile> = emptyList(),
    val folderShape: PosterShape = PosterShape.POSTER,
)

/**
 * A folder of a collection's row: a tile in the row's [BoardRow.folderShape] with its cover (a still image), or its title on a
 * tinted tile without one.
 *
 * @param showTitle the title shows under the tile (the folder doesn't hide it).
 * @param note what its sources lack when none of them can show ("Needs the addon AIOMetadata", "Connect Trakt"): its
 *   first source's; null when one can.
 */
data class FolderTile(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val showTitle: Boolean,
    val note: String?,
)

/**
 * Where a Home row offers its catalog in Discover. A remote's UI (Android TV, Fire TV, Apple TV) has no way to the
 * heading from the cards but Up, which goes to the row above, so its rows end in a card instead.
 */
enum class SeeAll {
    /** A button in the row's heading: touch and pointer UIs, every row. */
    HEADING,

    /**
     * A card after the row's last item, the size of its cards: a remote's UI, the row cut at
     * [BoardStore.REMOTE_ROW_ITEMS] or its catalog having pages not loaded.
     */
    CARD,

    /** Nowhere: a remote's UI, the row showing its whole catalog (or nothing yet). */
    NONE,
}

/** Where a Board row stands. */
sealed interface RowStatus {
    /** Not requested yet. */
    data object Idle : RowStatus

    data object Loading : RowStatus

    /** [loadingMore]: its next page is on its way ([BoardStore.loadNextPage]). */
    data class Ready(val loadingMore: Boolean) : RowStatus

    /** [text]: why the catalog failed, worded. */
    data class Failed(val text: String) : RowStatus
}

/** What the Board asks of the app around it; called on the main thread. */
interface BoardHost : MediaMenuHost {
    /**
     * Resume a Continue Watching item at [videoId]: its Detail screen, playing that video as Detail's Resume does
     * (auto-play's pick, else the stream it last played from, else its stream list).
     */
    fun openVideo(type: String, id: String, videoId: String)

    /** Open Discover on one catalog: a row's "See all". */
    fun openDiscover(link: DeepLink.Discover)

    /** Open Library on Downloads: the offline state's action, the Downloads shortcut. */
    fun openDownloads()

    /** Open Watch together over Home: its shortcut. */
    fun openWatchTogether()

    /** Open Library on its library section: its shortcut. */
    fun openLibrary()

    /** Open the TV guide over Home: its shortcut. */
    fun openGuide()

    /** Open the Calendar over Home: its shortcut. */
    fun openCalendar()

    /** Open the folder [folderId] of the collection [collectionId] over Home: its tile. */
    fun openCollectionFolder(collectionId: String, folderId: String)
}
