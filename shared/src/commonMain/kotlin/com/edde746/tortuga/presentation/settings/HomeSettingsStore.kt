package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionsRepository
import com.edde746.tortuga.collections.NuvioCollections
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.board.addonHomeKeyPrefix
import com.edde746.tortuga.presentation.board.catalog
import com.edde746.tortuga.presentation.board.catalogRowTitle
import com.edde746.tortuga.presentation.board.collectionHomeKey
import com.edde746.tortuga.presentation.board.homeRowKeys
import com.edde746.tortuga.presentation.board.rowKeys
import com.edde746.tortuga.presentation.board.trackerHomeKey
import com.edde746.tortuga.presentation.board.trackerOfHomeKey
import com.edde746.tortuga.presentation.board.trackerRowTitle
import com.edde746.tortuga.presentation.calendar.isoDate
import com.edde746.tortuga.presentation.calendar.localDay
import com.edde746.tortuga.presentation.collections.CollectionEditorStore
import com.edde746.tortuga.presentation.collections.CollectionImportStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Settings → Home: every row Home can show, as Home arranges them ([HomeLayout.arrange] over Home's
 * default order: the profile's collections, the connected trackers' lists, then the installed addons' catalogs without
 * required extras), each shown or hidden and moved; Continue Watching, first, only shown or hidden. Changes are stored
 * per profile at once ([ProfileSetting.HomeRows], synced) and the page follows the stored layout, including changes
 * synced from another device. Storing drops the keys of rows that are gone (an addon removed, a catalog its addon
 * dropped, a tracker the profile disconnected, a collection deleted), but keeps those of an installed addon that is
 * turned off, for when it comes back on, and of a connected tracker whose lists this device doesn't read (no provider
 * for it in this build, or unreadable now): another device may show them. Reset to default clears the layout. When the
 * installed addons can't be read, the page says so ([HomeSettingsBody.Empty]).
 *
 * The profile's [collections] are rows too ("Collection · 4 folders"), edited in the collection editor
 * ([editCollection], [newCollection]: [collectionEditor]) and deleted after a question ([askDelete]); [openImport] opens
 * the import ([collectionImport]), and [export] gives them as a file in Nuvio's format. A page open over Settings →
 * Home ([HomeSettingsUiState.page]) is made by [newImport] or [newEditor] and released when it closes ([closePage]).
 *
 * Call everything on the main thread; [release] ends it.
 */
class HomeSettingsStore internal constructor(
    private val profileId: String,
    private val installedAddons: Flow<List<Descriptor>>,
    private val trackerSources: Flow<List<TrackerCatalogSource>>,
    /** The profile's tracker connections on any device ([com.edde746.tortuga.data.tracker.TrackerAccountRepository]). */
    private val connectedServices: Flow<Set<TrackerService>>,
    private val settings: SettingsRepository,
    private val collections: CollectionsRepository,
    parentScope: CoroutineScope,
    /** The import page's store. */
    private val newImport: () -> CollectionImportStore,
    /** The editor's store for the collection with the id (null: a new one); it calls back to close. */
    private val newEditor: (collectionId: String?, close: () -> Unit) -> CollectionEditorStore,
    /** Now, in milliseconds since the epoch, and the device time zone's offset from UTC then: the export's date. */
    private val now: () -> Long,
    private val utcOffset: (Long) -> Int,
    /** The transport URLs of the installed addons that are turned off, whose rows aren't here but keep their place. */
    private val disabledAddons: () -> Set<String> = { emptySet() },
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /**
     * One row Home can show: [id] for the views, [key] in the layout, which holds the addon's link; [collection] for a
     * collection's.
     */
    private class Entry(val id: String, val key: String, val title: String, val sourceName: String, val collection: Collection? = null)

    /** One connected tracker: its lists once read; null while being read or when they couldn't be. */
    private inner class TrackerSection(val source: TrackerCatalogSource) {
        var entries: List<Entry>? = null
        val job: Job = scope.launch {
            val service = source.service
            entries = try {
                source.catalogs().distinctBy { it.id }.map { catalog ->
                    Entry("tracker/${service.id}/${catalog.id}", trackerHomeKey(service, catalog), trackerRowTitle(catalog), serviceName(service))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "${service.id} lists failed" }
                null
            }
            publish()
        }
    }

    private var addonEntries: List<Entry>? = null
    private var addonsUnreadable = false
    private var collectionEntries: List<Entry>? = null
    private var trackers: List<TrackerSection> = emptyList()
    private var connected: Set<TrackerService>? = null
    private var layout: HomeLayout? = null

    /** Writes of this page still running: the stored layout is behind [layout] until they're done. */
    private var writing = 0

    /** The collection whose deletion is being asked. */
    private var deleting: Collection? = null
    private var notice: String? = null

    /** The import page's store while it is open ([HomeSettingsPage.IMPORT]). */
    var collectionImport: CollectionImportStore? = null
        private set

    /** The editor's store while it is open ([HomeSettingsPage.EDITOR]). */
    var collectionEditor: CollectionEditorStore? = null
        private set

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<HomeSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            readingAddons(TAG) {
                installedAddons.collect { addons ->
                    val plan = AggrRequest.board().plan(addons)
                    val ids = rowKeys(plan)
                    val keys = homeRowKeys(plan)
                    addonEntries = plan.mapIndexed { i, planned ->
                        Entry(ids[i], keys[i], planned.catalog()?.let(::catalogRowTitle) ?: ids[i], planned.addon.manifest.name)
                    }
                    publish()
                }
            } ?: run {
                addonsUnreadable = true
                publish()
            }
        }
        scope.launch {
            collections.observe(profileId).collect { list ->
                collectionEntries = list.map {
                    Entry("collection/${it.id}", collectionHomeKey(it.id), it.title, T.collections.rowSource(it.folders.size), it)
                }
                publish()
            }
        }
        scope.launch {
            trackerSources.collect { sources ->
                val previous = trackers
                trackers = sources.map { source -> previous.firstOrNull { it.source === source } ?: TrackerSection(source) }
                previous.filter { it !in trackers }.forEach { it.job.cancel() }
                publish()
            }
        }
        scope.launch {
            connectedServices.collect { connected = it }
        }
        scope.launch {
            settings.observe(profileId).collect {
                if (writing == 0 && it.homeRows != layout) {
                    layout = it.homeRows
                    publish()
                }
            }
        }
    }

    /**
     * Switches the row [key] ([SettingToggleRow.id]) on or off: Continue Watching, which is no row of the addons', so it
     * changes once the layout is read, whether or not they are.
     */
    fun toggle(key: String, checked: Boolean) {
        when (key) {
            "continueWatching" -> edit { layout, _ -> layout.withHidden(HomeLayout.CONTINUE_WATCHING, !checked) }
            else -> error("Home settings have no switch $key")
        }
    }

    /** Shows or hides the row [id] ([HomeRowSetting.id]). */
    fun setShown(id: String, shown: Boolean) = edit { layout, arranged ->
        val entry = arranged.firstOrNull { it.id == id } ?: return@edit layout
        layout.withHidden(entry.key, !shown)
    }

    fun moveUp(id: String) = move(id) { it - 1 }

    fun moveDown(id: String) = move(id) { it + 1 }

    /** Moves the row [id] to [index] of [HomeSettingsBody.Rows.rows] (dragging). */
    fun moveTo(id: String, index: Int) = move(id) { index }

    /** Back to Home's default: every row shown, in the default order (on every device of the profile). */
    fun reset() {
        if (layout == null || layout == HomeLayout()) return
        layout = HomeLayout()
        publish()
        writing++
        scope.launch {
            try {
                settings.reset(profileId, ProfileSetting.HomeRows)
            } finally {
                writing--
            }
        }
    }

    /** Opens the editor on a new collection. */
    fun newCollection() = openEditor(null)

    /** Opens the editor on the collection [collectionId] ([HomeRowSetting.collectionId]). */
    fun editCollection(collectionId: String) {
        if (collectionEntries?.any { it.collection?.id == collectionId } == true) openEditor(collectionId)
    }

    /** Opens the import page. */
    fun openImport() {
        closePage()
        collectionImport = newImport()
        publish()
    }

    /** Closes the page open over Settings → Home, releasing its store: what the editor didn't save is dropped. */
    fun closePage() {
        collectionImport?.release()
        collectionImport = null
        collectionEditor?.release()
        collectionEditor = null
        publish()
    }

    /** Asks whether to delete the collection [collectionId] ([HomeSettingsUiState.deleteDialog]). */
    fun askDelete(collectionId: String) {
        deleting = collectionEntries?.firstNotNullOfOrNull { entry -> entry.collection?.takeIf { it.id == collectionId } } ?: return
        publish()
    }

    /** Deletes the collection asked about, on every device of the profile; its row leaves Home's layout. */
    fun confirmDelete() {
        val collection = deleting ?: return
        deleting = null
        publish()
        scope.launch { collections.delete(profileId, collection.id) }
    }

    fun dismissDelete() {
        deleting = null
        publish()
    }

    /**
     * The profile's collections as a file in Nuvio's format ([NuvioCollections.encode]), in Home's order, named
     * `tortuga-collections-<yyyy-mm-dd>.json` with today's date here; null while the profile has none
     * ([HomeCollectionActions.canExport]).
     */
    fun export(): CollectionExport? {
        val entries = collectionEntries?.takeIf { it.isNotEmpty() } ?: return null
        val layout = layout ?: HomeLayout()
        val byKey = entries.associateBy { it.key }
        val ordered = layout.arrange(byKey.keys.toList()).mapNotNull { byKey.getValue(it).collection }
        val millis = now()
        return CollectionExport("tortuga-collections-${isoDate(localDay(millis, utcOffset))}.json", NuvioCollections.encode(ordered))
    }

    /** The platform couldn't save [export]'s file: the page says so ([HomeSettingsUiState.notice]). */
    fun exportFailed() {
        notice = T.settings.data.saveFailed
        publish()
    }

    fun dismissNotice() {
        notice = null
        publish()
    }

    fun release() {
        collectionImport?.release()
        collectionEditor?.release()
        scope.cancel()
    }

    private fun openEditor(collectionId: String?) {
        closePage()
        lateinit var editor: CollectionEditorStore
        editor = newEditor(collectionId) { if (collectionEditor === editor) closePage() }
        collectionEditor = editor
        publish()
    }

    private fun move(id: String, target: (Int) -> Int) = edit { layout, arranged ->
        val from = arranged.indexOfFirst { it.id == id }
        if (from < 0) return@edit layout
        val to = target(from).coerceIn(0, arranged.lastIndex)
        if (to == from) return@edit layout
        val keys = arranged.map { it.key }.toMutableList().apply { add(to, removeAt(from)) }
        layout.reordered(keys, ::kept)
    }

    /**
     * Stores what [change] makes of the layout, given the rows as arranged now, without the keys of rows that are
     * gone. Nothing changes before the layout is read. Until the installed addons and the collections are read, or when
     * the addons can't be, there are no rows to change and none is known gone: only Continue Watching changes, and every
     * key stays.
     */
    private fun edit(change: (HomeLayout, List<Entry>) -> HomeLayout) {
        val current = layout ?: return
        val entries = entries()
        val changed = change(current, entries?.let { arrange(current, it) }.orEmpty())
        val next = entries?.let { changed.pruned(it.mapTo(HashSet()) { entry -> entry.key }, ::kept) } ?: changed
        if (next == current) return
        layout = next
        publish()
        writing++
        scope.launch {
            try {
                settings.set(profileId, ProfileSetting.HomeRows, next)
            } finally {
                writing--
            }
        }
    }

    /**
     * A key of no row here that may come back: a row of an installed addon that is turned off (its place and
     * visibility wait for it to come back on), or a list of a tracker connected to the profile whose lists this
     * device hasn't read. Until the connections are read, every tracker's.
     */
    private fun kept(key: String): Boolean {
        if (disabledAddons().any { key.startsWith(addonHomeKeyPrefix(it)) }) return true
        val service = trackerOfHomeKey(key) ?: return false
        if (connected?.contains(service) == false) return false
        return trackers.none { it.source.service == service && it.entries != null }
    }

    /** Every row Home can show, in Home's default order; null until the installed addons and the collections are read. */
    private fun entries(): List<Entry>? {
        val addons = addonEntries ?: return null
        val collections = collectionEntries ?: return null
        return collections + trackers.flatMap { it.entries.orEmpty() } + addons
    }

    private fun arrange(layout: HomeLayout, entries: List<Entry>): List<Entry> {
        val byKey = entries.associateBy { it.key }
        return layout.arrange(byKey.keys.toList()).map(byKey::getValue)
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): HomeSettingsUiState {
        val text = T.settings.home
        val layout = layout ?: HomeLayout()
        val entries = entries()
        val arranged = entries?.let { arrange(layout, it) }.orEmpty()
        return HomeSettingsUiState(
            note = text.note,
            continueWatching = SettingToggleRow(
                "continueWatching",
                T.browse.continueWatching.title,
                text.continueWatchingHint,
                !layout.isHidden(HomeLayout.CONTINUE_WATCHING),
                enabled = this.layout != null,
            ),
            collections = HomeCollectionActions(
                newLabel = text.newCollection,
                importLabel = text.importCollections,
                exportLabel = text.exportCollections,
                canExport = !collectionEntries.isNullOrEmpty(),
            ),
            rowsHeader = text.rowsHeader,
            body = when {
                addonsUnreadable -> HomeSettingsBody.Empty(T.addons.unreadable)
                entries == null || this.layout == null -> HomeSettingsBody.Loading
                arranged.isEmpty() -> HomeSettingsBody.Empty(text.empty)
                else -> HomeSettingsBody.Rows(
                    arranged.mapIndexed { index, entry ->
                        // The title is an addon's catalog name, a tracker list's or a collection's: content, in its own direction.
                        val name = isolate(entry.title)
                        val collection = entry.collection
                        HomeRowSetting(
                            id = entry.id,
                            title = entry.title,
                            sourceName = entry.sourceName,
                            shown = !layout.isHidden(entry.key),
                            canMoveUp = index > 0,
                            canMoveDown = index < arranged.lastIndex,
                            moveUpLabel = text.moveUpNamed(name),
                            moveDownLabel = text.moveDownNamed(name),
                            dragLabel = text.dragToReorderNamed(name),
                            moreLabel = T.detail.moreActionsNamed(name),
                            collectionId = collection?.id,
                            editLabel = collection?.let { text.edit },
                            deleteLabel = collection?.let { text.delete },
                        )
                    },
                )
            },
            resetLabel = text.reset,
            canReset = layout != HomeLayout(),
            deleteDialog = deleting?.let {
                CollectionDeleteDialog(it.id, text.deleteTitle(isolate(it.title)), text.deleteMessage, text.delete, T.settings.cancel)
            },
            page = when {
                collectionImport != null -> HomeSettingsPage.IMPORT
                collectionEditor != null -> HomeSettingsPage.EDITOR
                else -> null
            },
            notice = notice,
        )
    }

    private companion object {
        const val TAG = "homeSettings"
    }
}
