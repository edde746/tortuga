package com.edde746.tortuga.presentation.settings

/**
 * Home's rows as the profile arranged them (Settings → Home; per profile, synced, as [note] says): Continue Watching,
 * which stays first and can only be hidden, then every other row Home can show, in Home's order, each shown or
 * hidden and moved up or down (or dragged into place); the profile's collections among them, each edited or deleted.
 *
 * @param continueWatching enabled once the stored layout is read, whether or not the addons are.
 * @param collections the header's collection actions: New, Import and Export.
 * @param body what shows under [rowsHeader] ([HomeSettingsBody]).
 * @param canReset the layout differs from Home's default.
 * @param deleteDialog a collection's Delete asks first ([HomeSettingsStore.askDelete]); null while nothing is asked.
 * @param page the page open over Settings → Home: the import's ([HomeSettingsStore.collectionImport]) or the
 *   editor's ([HomeSettingsStore.collectionEditor]); null for none ([HomeSettingsStore.closePage]).
 * @param notice that the export couldn't be saved ([HomeSettingsStore.exportFailed]) until
 *   [HomeSettingsStore.dismissNotice]; null otherwise.
 */
data class HomeSettingsUiState(
    val note: String,
    val continueWatching: SettingToggleRow,
    val collections: HomeCollectionActions,
    val rowsHeader: String,
    val body: HomeSettingsBody,
    val resetLabel: String,
    val canReset: Boolean,
    val deleteDialog: CollectionDeleteDialog? = null,
    val page: HomeSettingsPage? = null,
    val notice: String? = null,
)

/**
 * The collection actions of Settings → Home: "New collection" ([HomeSettingsStore.newCollection]), "Import
 * collections" ([HomeSettingsStore.openImport]) and "Export collections" ([HomeSettingsStore.export]), enabled while
 * the profile has a collection ([canExport]).
 */
data class HomeCollectionActions(val newLabel: String, val importLabel: String, val exportLabel: String, val canExport: Boolean)

/**
 * Delete's question for the collection [collectionId] ("Delete “Studios”?", that it goes on every device of the
 * profile): [HomeSettingsStore.confirmDelete] deletes it, [HomeSettingsStore.dismissDelete] doesn't.
 */
data class CollectionDeleteDialog(
    val collectionId: String,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
)

/** A page over Settings → Home ([HomeSettingsUiState.page]). */
enum class HomeSettingsPage {
    /** Import collections ([HomeSettingsStore.collectionImport]). */
    IMPORT,

    /** A new or existing collection's editor ([HomeSettingsStore.collectionEditor]). */
    EDITOR,
}

/**
 * A collection export the platform saves or shares: [text] (Nuvio's format) as UTF-8 in a file named [fileName]
 * (`tortuga-collections-<yyyy-mm-dd>.json`).
 */
data class CollectionExport(val fileName: String, val text: String)

/**
 * The rest of the page after Continue Watching, the first that applies: that the installed addons can't be read
 * ([Empty]); a spinner in place of the rows and Reset while the installed addons or the stored layout aren't read
 * ([Loading]); that there is no row besides Continue Watching, no addon with a catalog for Home ([Empty]); else the
 * rows ([Rows]). Reset follows [Empty] and [Rows].
 */
sealed interface HomeSettingsBody {
    data object Loading : HomeSettingsBody

    /** [text] says why there is no row to arrange. */
    data class Empty(val text: String) : HomeSettingsBody

    /**
     * [rows]: the profile's collections, the addon catalogs and the connected trackers' lists, in Home's order, never
     * none. A tracker's lists join once read (one whose lists can't be read has none here; Home keeps their place).
     */
    data class Rows(val rows: List<HomeRowSetting>) : HomeSettingsBody
}

/**
 * One row of Home: "Popular · Movies" from "Cinemeta", "Watchlist" from "Trakt", "Studios" from "Collection · 4 folders".
 *
 * @param id what the intents take; stable while the row exists (not the addon's link).
 * @param shown on Home; off, Home neither shows nor loads it.
 * @param moveUpLabel the name of its Move up button, with the row's title: "Move Popular · Movies up".
 * @param moveDownLabel the name of its Move down button: "Move Popular · Movies down".
 * @param dragLabel the name of its drag handle: "Reorder Popular · Movies".
 * @param moreLabel the name of its menu button: "More actions for Popular · Movies", the title isolated.
 * @param collectionId a collection's id: its menu adds Edit ([HomeSettingsStore.editCollection]) and Delete
 *   ([HomeSettingsStore.askDelete]), labelled [editLabel] and [deleteLabel]; null (and they null) for any other row.
 */
data class HomeRowSetting(
    val id: String,
    val title: String,
    val sourceName: String,
    val shown: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val moveUpLabel: String,
    val moveDownLabel: String,
    val dragLabel: String,
    val moreLabel: String,
    val collectionId: String? = null,
    val editLabel: String? = null,
    val deleteLabel: String? = null,
)
