package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionFolder
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.CollectionSources
import com.edde746.tortuga.collections.CollectionViewMode
import com.edde746.tortuga.collections.SourceState
import com.edde746.tortuga.collections.note
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.board.FeedRow
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.marked
import com.edde746.tortuga.presentation.board.snapshot
import com.edde746.tortuga.presentation.menu.DownloadMarks
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.trakt.TraktLists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The folder [folderId] of the profile's collection [collectionId] ([FolderUiState]): its sources' titles, each source
 * as [CollectionSources.resolve] finds it against the enabled [installedAddons], the connected [trackerSources] and
 * Trakt's public lists where the build has them ([traktLists]).
 *
 * - The collection's view mode picks the layout: tabs over a grid ([FolderLayout.TABS]), or rows as Home shows its
 *   catalogs ([FolderLayout.ROWS], also for Follow the app). Tabs start with All when the collection shows it and the
 *   folder has more than one source: the ready sources' titles taken in turn, one from each, each title once;
 *   [gridNearEnd] loads each source's next page there.
 * - Each source pages as a Home row does ([FeedRow]: an addon catalog by `skip`, a tracker list by its cursor), and its
 *   titles leave out the same as Home's rows: the hidden watched titles ([hiddenTitles]) and, on a kids profile, what
 *   the gate refuses ([kids], [KidsGate.Paging]). A grid tab's sources load when the tab is selected; rows load the first
 *   [BoardStore.INITIAL_ROWS] at once and the others as [rowVisible] reports them.
 * - A source that can't show has a note in place of its titles ([FolderNote]): its addon to install ([noteActions]:
 *   Settings → Addons), its tracker to connect (Settings → Trackers); one that answered with no titles says so.
 * - It follows what changes: the collection edited or deleted elsewhere (deleted: [FolderUiState.gone]), an addon
 *   installed or removed, a tracker connected or disconnected; a source whose feed is unchanged keeps what it loaded.
 * - Cards carry Home's menu ([menuAction]) and marks: watched, in the library, downloaded, on a Watchlist.
 *
 * Call everything on the main thread; [release] ends it.
 */
class FolderStore internal constructor(
    private val profileId: String,
    private val collectionId: String,
    private val folderId: String,
    private val collections: Flow<List<Collection>>,
    private val installedAddons: Flow<List<Descriptor>>,
    private val trackerSources: Flow<List<TrackerCatalogSource>>,
    private val traktLists: TraktLists?,
    private val aggregator: AddonAggregator,
    private val library: LibraryRepository,
    private val actions: MediaActions,
    private val host: FolderHost,
    parentScope: CoroutineScope,
    /** A note's button opens Settings (not on a kids profile, whose Settings have no Addons or Trackers). */
    private val noteActions: Boolean,
    /** The profile's kids rules; without them every card stays as it is. */
    private val kids: KidsGate? = null,
    /** The titles the sources leave out, by id; none while "Hide watched titles" is off. */
    private val hiddenTitles: Flow<Set<String>> = flowOf(emptySet()),
    /** The cards show their titles under the artwork ([MetaCard.caption]). */
    private val posterCaptions: Flow<Boolean> = flowOf(true),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** One source of the folder as resolved now, and its titles' row while it is ready. */
    private class Source(val source: CollectionSource, val state: SourceState, val row: FeedRow?) {
        /** Ready, and shows no title and none can come. */
        var none = false
    }

    private var collection: Collection? = null
    private var folder: CollectionFolder? = null
    private var gone = false
    private var addons: List<Descriptor>? = null
    private var trackers: List<TrackerCatalogSource>? = null
    private var sources: List<Source> = emptyList()
    private var selected = 0
    private var leftOut: Set<String> = emptySet()
    private var captions = true
    private var marks: Map<String, TitleMark> = emptyMap()
    private var downloads: DownloadMarks? = null
    private var watchlists: WatchlistMarks? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<FolderUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            collections.collect { list ->
                val collection = list.firstOrNull { it.id == collectionId }
                val folder = collection?.folders?.firstOrNull { it.id == folderId }
                if (collection == null || folder == null) {
                    gone = true
                    sources.forEach { it.row?.cancel() }
                    sources = emptyList()
                    return@collect publish()
                }
                this@FolderStore.collection = collection
                this@FolderStore.folder = folder
                resolve()
            }
        }
        scope.launch {
            installedAddons.collect {
                addons = it
                resolve()
            }
        }
        scope.launch {
            trackerSources.collect {
                trackers = it
                resolve()
            }
        }
        kids?.let { gate -> scope.launch { gate.changes.collect { publish() } } }
        scope.launch {
            hiddenTitles.distinctUntilChanged().collect {
                leftOut = it
                publish()
            }
        }
        scope.launch {
            posterCaptions.distinctUntilChanged().collect {
                captions = it
                publish()
            }
        }
        scope.launch {
            library.observeMarks(profileId).distinctUntilChanged().collect {
                marks = it
                publish()
            }
        }
        actions.downloadMarks?.let { flow ->
            scope.launch {
                flow.collect {
                    downloads = it
                    publish()
                }
            }
        }
        actions.watchlistMarks?.let { flow ->
            scope.launch {
                flow.collect {
                    watchlists = it
                    publish()
                }
            }
        }
    }

    /** Shows the tab at [index] of [FolderUiState.tabs], loading its sources. */
    fun selectTab(index: Int) {
        if (index !in tabs().indices || index == selected) return
        selected = index
        publish()
    }

    /** The row at [index] of [FolderUiState.rows] is on screen: loads it and the one after it. */
    fun rowVisible(index: Int) {
        if (layout() != FolderLayout.ROWS) return
        val asked = (index..index + BoardStore.PREFETCH_ROWS).map { sources.getOrNull(it)?.let(::load) == true }
        if (true in asked) publish()
    }

    /** The row at [index] of [FolderUiState.rows] is scrolled to its end: loads its next page, as a kids profile allows. */
    fun loadNextPage(index: Int) {
        val row = sources.getOrNull(index)?.row?.takeIf { it.status is RowStatus.Ready } ?: return
        if (row.paging.end(row::loadNext)) publish()
    }

    /** The grid is scrolled near its end: its source (each source of All) loads its next page. */
    fun gridNearEnd() {
        var changed = false
        for (source in tabSources(selected)) {
            val row = source.row?.takeIf { it.status is RowStatus.Ready } ?: continue
            changed = row.paging.end(row::loadNext) || changed
        }
        if (changed) publish()
    }

    /**
     * Loads again what failed: the row at [index] of [FolderUiState.rows] ([FolderLayout.ROWS]), or the sources of the
     * tab at [index] ([FolderLayout.TABS]).
     */
    fun retry(index: Int) {
        val failed = (if (layout() == FolderLayout.ROWS) listOfNotNull(sources.getOrNull(index)) else tabSources(index))
            .mapNotNull { it.row }.filter { it.status is RowStatus.Failed }
        failed.forEach { it.load() }
        if (failed.isNotEmpty()) publish()
    }

    /** Opens what fixes the note of the row at [index] ([FolderLayout.ROWS]) or of the tab at [index] ([FolderLayout.TABS]). */
    fun noteAction(index: Int) {
        val source = if (layout() == FolderLayout.ROWS) sources.getOrNull(index) else tabSources(index).firstOrNull()
        when (source?.let { action(it.state) }) {
            FolderNoteAction.CONNECT_TRACKER -> host.openTrackers()
            FolderNoteAction.OPEN_ADDONS -> host.openAddons()
            null -> Unit
        }
    }

    fun openItem(card: MetaCard) = host.openMeta(card.type, card.id, card.preview)

    /** Carries out an entry of [card]'s menu ([MetaCard.menu]). */
    fun menuAction(card: MetaCard, action: MediaAction) = actions.perform(card.snapshot(), videoId = null, action, host)

    fun release() {
        scope.cancel()
    }

    /**
     * The folder, the addons or the trackers changed: each source resolved again, keeping the row of one whose feed is
     * unchanged. Nothing before all three are read.
     */
    private fun resolve() {
        val folder = folder ?: return publish()
        val addons = addons ?: return publish()
        val trackers = trackers ?: return publish()
        val previous = sources
        sources = folder.sources.map { source ->
            val state = CollectionSources.resolve(source, addons, trackers, traktLists)
            val feed = (state as? SourceState.Ready)?.feed
            val row = feed?.let { previous.firstOrNull { it.row?.feed == feed }?.row }
                ?: feed?.let { FeedRow(it, scope, aggregator, kids, { leftOut }, ::publish) }
            Source(source, state, row)
        }
        val kept = sources.mapNotNullTo(HashSet()) { it.row }
        previous.mapNotNull { it.row }.filter { it !in kept }.forEach { it.cancel() }
        selected = selected.coerceIn(0, (tabs().size - 1).coerceAtLeast(0))
        if (layout() == FolderLayout.ROWS) sources.take(BoardStore.INITIAL_ROWS).forEach(::load)
        publish()
    }

    /** Requests [source]'s first page unless it is requested already; true when it did. */
    private fun load(source: Source): Boolean {
        val row = source.row?.takeIf { it.status == RowStatus.Idle } ?: return false
        row.load()
        return true
    }

    private fun layout(): FolderLayout =
        if (collection?.viewMode == CollectionViewMode.TABS) FolderLayout.TABS else FolderLayout.ROWS

    /** All's tab leads when the collection shows it and the folder has more than one source. */
    private fun hasAll(): Boolean = collection?.showAllTab == true && sources.size > 1

    private fun tabs(): List<FolderTab> {
        if (layout() != FolderLayout.TABS) return emptyList()
        val titles = titles()
        val all = if (hasAll()) listOf(FolderTab(ALL, T.collections.folder.all)) else emptyList()
        return all + sources.indices.map { FolderTab("source/$it", titles[it]) }
    }

    /** The sources the tab at [index] shows: All's are every source. */
    private fun tabSources(index: Int): List<Source> {
        if (layout() != FolderLayout.TABS) return emptyList()
        if (!hasAll()) return listOfNotNull(sources.getOrNull(index))
        return if (index == 0) sources else listOfNotNull(sources.getOrNull(index - 1))
    }

    /** Requests the selected tab's sources (grid) and publishes, each source's walk and fill done first. */
    private fun publish() {
        if (layout() == FolderLayout.TABS) tabSources(selected).forEach(::load)
        for (source in sources) {
            val row = source.row ?: continue
            row.fillLeftOut()
            val emptyAfterFill = row.emptyAfterFill()
            source.none = row.status is RowStatus.Ready && !row.more.loading && (row.empty || row.cards().isEmpty() || emptyAfterFill)
        }
        mutableState.value = buildState()
    }

    private fun buildState(): FolderUiState {
        val collection = collection
        val folder = folder
        val layout = layout()
        if (collection == null || folder == null || addons == null || trackers == null) {
            return FolderUiState(
                title = folder?.title.orEmpty(), backdropUrl = collection?.backdropUrl, layout = layout, tabs = emptyList(),
                selectedTab = 0, grid = if (layout == FolderLayout.TABS) FolderGrid(RowStatus.Loading, emptyList(), null) else null,
                rows = emptyList(), gone = gone,
            )
        }
        val titles = titles()
        return FolderUiState(
            title = folder.title,
            backdropUrl = collection.backdropUrl,
            layout = layout,
            tabs = tabs(),
            selectedTab = selected,
            grid = if (layout == FolderLayout.TABS) grid(tabSources(selected), all = hasAll() && selected == 0) else null,
            rows = if (layout == FolderLayout.ROWS) {
                sources.mapIndexed { i, source ->
                    val (status, items, note) = titlesOf(source)
                    FolderRow("source/$i", titles[i], sourceOrigin(source.source, source.state), status, items, note)
                }
            } else {
                emptyList()
            },
            gone = gone,
        )
    }

    /** A tab's grid: its one source's titles, or All's merge of its [sources]. */
    private fun grid(sources: List<Source>, all: Boolean): FolderGrid {
        if (!all) {
            val source = sources.singleOrNull() ?: return FolderGrid(RowStatus.Loading, emptyList(), null)
            val (status, items, note) = titlesOf(source)
            return FolderGrid(status, items, note)
        }
        val ready = sources.filter { it.row != null }
        if (ready.isEmpty()) return FolderGrid(RowStatus.Ready(loadingMore = false), emptyList(), note(sources.first().state))
        val rows = ready.mapNotNull { it.row }
        val items = merge(rows.map { it.shown() }).marked(marks, downloads, watchlists, captions)
        // Settled: every ready source showed it has no titles, or failed.
        val settled = ready.all { it.none || it.row?.status is RowStatus.Failed }
        val status = when {
            items.isNotEmpty() -> RowStatus.Ready(loadingMore = rows.any { it.more.loading })
            !settled -> RowStatus.Loading
            rows.all { it.status is RowStatus.Failed } -> rows.first().status
            else -> RowStatus.Ready(loadingMore = false)
        }
        return FolderGrid(status, items, if (settled && items.isEmpty() && status is RowStatus.Ready) emptyNote() else null)
    }

    /** [source]'s status, cards and note, as its grid tab or row shows them. */
    private fun titlesOf(source: Source): Triple<RowStatus, List<MetaCard>, FolderNote?> {
        val row = source.row ?: return Triple(RowStatus.Ready(loadingMore = false), emptyList(), note(source.state))
        if (source.none) return Triple(RowStatus.Ready(loadingMore = false), emptyList(), emptyNote())
        val items = row.shown().marked(marks, downloads, watchlists, captions)
        return Triple(row.shownStatus(items), items, null)
    }

    /** The titles of [lists] taken in turn, one from each, each title once. */
    private fun merge(lists: List<List<MetaCard>>): List<MetaCard> {
        val seen = HashSet<String>()
        val merged = ArrayList<MetaCard>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (list in lists) {
                val card = list.getOrNull(i) ?: continue
                if (seen.add(card.id)) merged += card
            }
        }
        return merged
    }

    /** What a source that can't show lacks, and the button that fixes it. */
    private fun note(state: SourceState): FolderNote? {
        val text = state.note ?: return null
        val action = action(state)
        val label = when (action) {
            FolderNoteAction.CONNECT_TRACKER -> T.collections.folder.goToTrackers
            FolderNoteAction.OPEN_ADDONS -> T.collections.folder.goToAddons
            null -> null
        }
        return FolderNote(text, action, label)
    }

    private fun action(state: SourceState): FolderNoteAction? = when {
        !noteActions -> null
        state is SourceState.NeedsTracker -> FolderNoteAction.CONNECT_TRACKER
        state is SourceState.NeedsAddon || state is SourceState.NeedsCatalog -> FolderNoteAction.OPEN_ADDONS
        else -> null
    }

    private fun emptyNote() = FolderNote(T.collections.folder.empty, action = null, actionLabel = null)

    private fun titles(): List<String> = sourceTitles(sources.map { it.source to it.state })

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "folder"

        private const val ALL = "all"
    }
}
