package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionFolder
import com.edde746.tortuga.collections.CollectionSources
import com.edde746.tortuga.collections.SourceState
import com.edde746.tortuga.collections.note
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.presentation.downloads.active
import com.edde746.tortuga.presentation.guide.Guides
import com.edde746.tortuga.presentation.menu.DownloadMarks
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.trakt.TraktLists
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Board's store (stremio-core `CatalogsWithExtra` with no extra, and
 * `ContinueWatchingPreview`).
 *
 * - Rows are the lists of the profile's connected trackers ([trackerSources]), each
 *   service's in the order it gives them, then the catalogs of [AggrRequest.board] (no required extras) of the
 *   installed addons, in addon order: Home's default order, which the profile's [homeLayout] rearranges and thins
 *   ([HomeLayout.arrange]; Settings → Home). Nothing is requested before the layout is read, and a hidden row is never
 *   requested. All follow what changes (a tracker connected or disconnected, the installed list, the layout); a row
 *   whose catalog is unchanged keeps what it loaded. A tracker whose lists can't be read shows as one failed row, which
 *   [retryRow] reads again.
 * - Rows load lazily, as stremio-core's `LoadRange` does: the first [INITIAL_ROWS] at once, the others when
 *   [rowVisible] reports them, each row reported keeping the [PREFETCH_ROWS] shown after it requested: whichever rows
 *   those become as rows answer empty, arrive or go, so a long Board doesn't fire every request at once and the row
 *   after one on screen loads without another scroll.
 * - Each row loads, fails or fills on its own; a catalog that answers with nothing is left out, as Stremio does. A
 *   tracker list's items are the service's previews under ids the installed addons resolve: opening one goes through
 *   the addons, like any other item. When the installed addons can't be read, the Board says so instead
 *   ([BoardBody.Empty]).
 * - A row scrolled to its end loads its next page ([loadNextPage], stremio-core's `LoadNextPage`; [RowPages]): an
 *   addon catalog that declares `skip`, a tracker list with a next cursor.
 * - On a remote's UI ([remote]: Android TV, Fire TV, Apple TV) a row shows at most [REMOTE_ROW_ITEMS] items and pages
 *   only until it has them; a See all card ends it ([SeeAll.CARD], [seeAll]) when it has more loaded or its catalog
 *   has pages not loaded (an addon catalog that declares `skip` whose pages all had items, a tracker list with a next
 *   cursor). Elsewhere every row offers See all in its heading ([SeeAll.HEADING]).
 * - Continue Watching follows its items ([continueWatchingItems]) and stays first; the layout can hide it
 *   ([HomeLayout.CONTINUE_WATCHING]). A library write that leaves its cards as they were publishes nothing. Each card
 *   counts its series' new episodes ([newEpisodes], [ContinueWatchingCard.newEpisodes]).
 * - Every card follows its title's library state ([LibraryRepository.observeMarks]) and its download's on this device
 *   ([MediaActions.downloadMarks]): watched, in the library, downloaded. [actions] carries out its menu's entries
 *   ([MetaCard.menu], [ContinueWatchingCard.menu]), and says so over the app's screens when one can't be.
 * - While the device is offline ([network]) the Board says so ([BoardBody.Offline]), pointing to Downloads where
 *   the device has them ([offersDownloads]); once it is back, the rows that failed load again.
 * - Home's shortcuts ([BoardUiState.shortcuts], [openShortcut]): Watch together, not on a kids profile ([kidsProfile]),
 *   saying how many are in this device's room while it is in one ([watchingTogether]); Downloads where the device has
 *   them ([offersDownloads]), saying how many are on their way and how far while the profile has some
 *   ([profileDownloads]); Library; TV guide where an installed addon has a guide catalog
 *   ([com.edde746.tortuga.presentation.guide.GuideStore]), not on a kids profile ([kidsProfile]: its live channels
 *   never pass); Calendar;
 *   then each list of the connected trackers, the rows Home's layout hides included, opening as its row's See all
 *   does, carrying its service ([BoardShortcut.tracker]), which its label names when another shortcut reads the same.
 *   The downloads' progress moves in whole percents, so a running transfer republishes the Board at most a hundred times.
 * - On a kids profile ([kids]) a row's cards pass its gate in order ([KidsGate.Paging]): a row shows only what the
 *   profile may see, once each card's verdict is known, and has the ratings of only the cards it is about to show
 *   looked up, further each time it is scrolled to its end showing as many as it wants ([loadNextPage]). A row that
 *   went through its cards showing too few loads its next pages itself (at most [KidsGate.FILL_PAGES]) unless a
 *   lookup failed (until the gate asks again), and one left with none is left out. Continue Watching is filtered
 *   alike. A kids profile with Home rows preset ([KidsGate.homeCatalogs]) shows those catalogs, of the installed
 *   addons that have them, instead of the trackers' lists and the addons' catalogs.
 * - The catalog and tracker rows leave out the titles of [hiddenTitles] (the finished ones while "Hide watched
 *   titles" is on; Continue Watching keeps them). A row whose first page has more than half of its cards left out
 *   loads its next page itself, once per load of the row (unless a page after the first was asked for already), so it
 *   isn't left nearly empty; further pages come as it is scrolled to its end. A row left with no card is left out.
 * - The catalog and tracker rows' cards show their captions as [posterCaptions] says ([MetaCard.caption]); Continue
 *   Watching's always do.
 * - The profile's [collections] are rows of folder tiles ([BoardRow.folders]), first in Home's default order (after
 *   Continue Watching), arranged and hidden by the layout like the others; one without folders is left out, and a kids
 *   profile with Home rows preset shows none. A folder whose sources can't show (an addon not installed, a tracker not
 *   connected: [CollectionSources.resolve] against the installed addons, the connected trackers and [traktLists])
 *   carries its first source's note ([FolderTile.note]); [openFolder] opens one. The Board shows its rows once the
 *   collections are read too.
 *
 * Answers and library items become cards on [parseDispatcher]. Call everything on the main thread; [release] ends it.
 */
class BoardStore internal constructor(
    private val profileId: String,
    private val installedAddons: Flow<List<Descriptor>>,
    private val aggregator: AddonAggregator,
    private val library: LibraryRepository,
    /** Continue Watching's items, most recently changed first, at most [CONTINUE_WATCHING_LIMIT]; read while it shows. */
    private val continueWatchingItems: Flow<List<LibraryItem>>,
    private val trackerSources: Flow<List<TrackerCatalogSource>>,
    private val homeLayout: Flow<HomeLayout>,
    private val host: BoardHost,
    /** The app's scope, whose child [scope] is the Board's. */
    private val parentScope: CoroutineScope,
    private val network: StateFlow<NetworkState>,
    private val offersDownloads: Boolean,
    private val profileDownloads: Flow<List<Download>>,
    private val watchingTogether: Flow<Int>,
    /** The profile runs as a kids profile (`isKidsProfile`): no Watch together or TV guide shortcut. */
    private val kidsProfile: Flow<Boolean>,
    private val parseDispatcher: CoroutineDispatcher,
    private val actions: MediaActions,
    private val remote: Boolean,
    /** The profile's kids rules; without them every card stays as it is. */
    private val kids: KidsGate? = null,
    /** The count of new episodes of each library series that has any, by id ([ContinueWatchingCard.newEpisodes]). */
    private val newEpisodes: Flow<Map<String, Int>> = flowOf(emptyMap()),
    /** The titles the rows leave out, by id ([hiddenWatchedTitles]); none while the switch is off. */
    private val hiddenTitles: Flow<Set<String>> = flowOf(emptySet()),
    /** The catalog and tracker rows' cards show their titles under the artwork ([MetaCard.caption]). */
    private val posterCaptions: Flow<Boolean> = flowOf(true),
    /** The profile's collections ([com.edde746.tortuga.collections.CollectionsRepository.observe]). */
    private val collections: Flow<List<Collection>> = flowOf(emptyList()),
    /** Trakt's public lists, where this build has Trakt: a collection's Trakt lists show without a Trakt account. */
    private val traktLists: TraktLists? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /**
     * One row of the Board and its pages ([FeedRow]); [homeKey] is its key in the profile's [HomeLayout]. Without a
     * feed: a connected tracker whose lists couldn't be read ([lists]: [retryRow] reads them again), or a [collection]
     * with its folders' [tiles].
     */
    private inner class Row(
        var key: String,
        var homeKey: String,
        feed: RowFeed?,
        val lists: TrackerSection? = null,
        var collection: Collection? = null,
    ) : FeedRow(feed, scope, aggregator, kids, { leftOut }, ::publish) {
        var tiles: List<FolderTile> = emptyList()

        /** [cut]'s last answer and the items it was cut from: an unchanged row stays the same list. */
        private var cutFrom: List<MetaCard>? = null
        private var cutItems: List<MetaCard> = emptyList()

        /** [items] cut to [REMOTE_ROW_ITEMS]. */
        fun cut(items: List<MetaCard>): List<MetaCard> {
            if (items.size <= REMOTE_ROW_ITEMS) return items
            if (items !== cutFrom) {
                cutFrom = items
                cutItems = items.take(REMOTE_ROW_ITEMS)
            }
            return cutItems
        }
    }

    /** One connected tracker: its lists as rows, once read. */
    private inner class TrackerSection(val source: TrackerCatalogSource) {
        var rows: List<Row> = emptyList()
        var listing = true
        var job: Job? = null

        /** Reads the service's lists (a network call for the services whose users make their own). */
        fun list() {
            job?.cancel()
            listing = true
            job = scope.launch {
                val service = source.service
                rows = try {
                    source.catalogs().distinctBy { it.id }.map { catalog ->
                        rows.firstOrNull { (it.feed as? RowFeed.Tracker)?.catalog == catalog }
                            ?: Row("tracker/${service.id}/${catalog.id}", trackerHomeKey(service, catalog), RowFeed.Tracker(source, catalog))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "${service.id} lists failed" }
                    listOf(
                        Row("tracker/${service.id}", trackerListsHomeKey(service), feed = null, lists = this@TrackerSection).apply {
                            status = RowStatus.Failed(trackerErrorText(service, e))
                        },
                    )
                }
                listing = false
                rebuildRows()
            }
        }

        fun cancel() {
            job?.cancel()
            rows.forEach { it.cancel() }
        }
    }

    private var trackers: List<TrackerSection> = emptyList()
    private var addonRows: List<Row> = emptyList()
    private var collectionRows: List<Row> = emptyList()

    /** The enabled addons as last read: what a collection's folders resolve their sources against. */
    private var addons: List<Descriptor> = emptyList()
    private var collectionsKnown = false

    /** The rows Home shows, in its order: what [INITIAL_ROWS] counts. */
    private var rows: List<Row> = emptyList()

    /** The rows in [BoardBody.Rows], in order: what the intents' indices refer to and [PREFETCH_ROWS] counts. */
    private var shown: List<Row> = emptyList()

    /** The rows [rowVisible] reported, while they are on the Board: [loadAhead] requests the rows after them. */
    private val onScreen = mutableSetOf<Row>()
    private var addonsKnown = false
    private var addonsUnreadable = false

    /** An installed addon has a guide catalog: the TV guide shortcut shows, unless on a kids profile. */
    private var hasGuide = false

    /** The profile's layout; null until read. */
    private var layout: HomeLayout? = null

    /** Some row the Board has is hidden by [layout]. */
    private var layoutHidesRows = false

    /** The rows are a kids profile's preset ([KidsGate.homeCatalogs]): the trackers' lists stay out. */
    private var preset = false

    private var continueWatching: List<ContinueWatchingCard> = emptyList()
    private var continueWatchingKnown = false
    private var continueWatchingJob: Job? = null
    private var online = network.value.online
    private var marks: Map<String, TitleMark> = emptyMap()

    /** The titles the rows leave out ([hiddenTitles]). */
    private var leftOut: Set<String> = emptySet()

    /** The rows' cards show their captions ([posterCaptions]). */
    private var captions = true
    private var downloads: DownloadMarks? = null
    private var watchlists: WatchlistMarks? = null
    private var onTheirWay: String? = null

    /** How many are in this device's watch-together room; 0 outside one. */
    private var watching = 0

    /** The profile runs as a kids profile ([kidsProfile]), and counts as one until that is known. */
    private var runsAsKids = true

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<BoardUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            readingAddons(TAG) {
                // A kids profile's preset rows replace the catalogs; nothing is requested before the profile is read.
                installedAddons.combine(kids?.homeCatalogs ?: flowOf(emptyList()), ::onAddons).collect()
            } ?: run {
                addonsUnreadable = true
                publish()
            }
        }
        scope.launch { trackerSources.collect(::onTrackers) }
        scope.launch {
            collections.collect {
                onCollections(it)
                collectionsKnown = true
                rebuildRows()
            }
        }
        kids?.let { gate -> scope.launch { gate.changes.collect { publish() } } }
        scope.launch {
            homeLayout.collect {
                layout = it
                followContinueWatching(!it.isHidden(HomeLayout.CONTINUE_WATCHING))
                rebuildRows()
            }
        }
        scope.launch {
            network.collect { state ->
                val back = state.online && !online
                online = state.online
                if (back) reloadFailed()
                publish()
            }
        }
        scope.launch {
            library.observeMarks(profileId).distinctUntilChanged().collect {
                marks = it
                publish()
            }
        }
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
        scope.launch {
            profileDownloads.map(::onTheirWay).distinctUntilChanged().collect {
                onTheirWay = it
                publish()
            }
        }
        scope.launch {
            watchingTogether.distinctUntilChanged().collect {
                watching = it
                publish()
            }
        }
        scope.launch {
            kidsProfile.distinctUntilChanged().collect {
                runsAsKids = it
                publish()
            }
        }
    }

    /**
     * The row at [index] of [BoardBody.Rows] is on screen: loads it, and from now on the [PREFETCH_ROWS] shown rows
     * after it ([loadAhead]).
     */
    fun rowVisible(index: Int) {
        val row = shown.getOrNull(index) ?: return
        onScreen += row
        val loaded = load(row, force = false)
        if (loadAhead() || loaded) publish()
    }

    /** Loads the row at [index] of [BoardBody.Rows] again (a tracker whose lists failed: reads them again). */
    fun retryRow(index: Int) {
        val row = shown.getOrNull(index) ?: return
        val lists = row.lists
        if (lists != null) {
            row.status = RowStatus.Loading
            publish()
            lists.list()
            return
        }
        load(row, force = true)
        publish()
    }

    /**
     * The row at [index] of [BoardBody.Rows] is scrolled to its end: loads its next page, when it has one and none
     * is loading (on a [remote]'s UI, while it shows fewer than [REMOTE_ROW_ITEMS] items), as a kids profile's paging
     * allows ([KidsGate.Paging.end]).
     */
    fun loadNextPage(index: Int) {
        val row = shown.getOrNull(index)?.takeIf { it.status is RowStatus.Ready } ?: return
        if (remote && row.shown().size >= REMOTE_ROW_ITEMS) return
        if (row.paging.end(row::loadNext)) publish()
    }

    /** The row at [index] of [BoardBody.Rows] in Discover: its See all, in its heading or its card ([BoardRow.seeAll]). */
    fun seeAll(index: Int) {
        val row = shown.getOrNull(index) ?: return
        when (val feed = row.feed) {
            is RowFeed.Addon -> {
                val request = feed.planned.request
                host.openDiscover(DeepLink.Discover(request.base.value, request.path.type, request.path.id))
            }
            // A list of both types opens on the type of its first item.
            is RowFeed.Tracker -> {
                val type = feed.catalog.type ?: row.items.firstOrNull()?.type ?: return
                host.openDiscover(trackerDiscoverLink(feed.source.service, feed.catalog, type))
            }
            null -> Unit
        }
    }

    /** Opens the folder [folderId] of the collection whose row is at [rowIndex] of [BoardBody.Rows] ([BoardRow.folders]). */
    fun openFolder(rowIndex: Int, folderId: String) {
        val collection = shown.getOrNull(rowIndex)?.collection ?: return
        if (collection.folders.any { it.id == folderId }) host.openCollectionFolder(collection.id, folderId)
    }

    fun openItem(card: MetaCard) = host.openMeta(card.type, card.id, card.preview)

    /** Resumes [card]'s video ([BoardHost.openVideo]); a card whose video the library doesn't know opens its Detail. */
    fun openContinueWatching(card: ContinueWatchingCard) {
        val videoId = card.videoId
        if (videoId != null) host.openVideo(card.type, card.id, videoId) else host.openMeta(card.type, card.id, preview = null)
    }

    /** Carries out an entry of [card]'s menu ([MetaCard.menu]). */
    fun menuAction(card: MetaCard, action: MediaAction) = actions.perform(card.snapshot(), videoId = null, action, host)

    /** Carries out an entry of [card]'s menu ([ContinueWatchingCard.menu]): its marks and its download are its video's. */
    fun continueWatchingAction(card: ContinueWatchingCard, action: MediaAction) = actions.perform(card.snapshot(), card.videoId, action, host)

    /** Opens Library on Downloads: [BoardBody.Offline]'s action. */
    fun openDownloads() = host.openDownloads()

    /**
     * Opens the shortcut [key] of [BoardUiState.shortcuts]: Watch together, Downloads, Library, the TV guide, the
     * Calendar, or a tracker's list in Discover as its row's See all does (a list of both types on its first item's type,
     * movies before it has items).
     */
    fun openShortcut(key: String) {
        when (key) {
            WATCH_TOGETHER -> host.openWatchTogether()
            DOWNLOADS -> host.openDownloads()
            LIBRARY -> host.openLibrary()
            GUIDE -> host.openGuide()
            CALENDAR -> host.openCalendar()
            else -> {
                val (row, list) = trackerLists().firstOrNull { (_, list) -> shortcutKey(list) == key } ?: return
                val type = list.catalog.type ?: row.items.firstOrNull()?.type ?: "movie"
                host.openDiscover(trackerDiscoverLink(list.source.service, list.catalog, type))
            }
        }
    }

    fun release() {
        scope.cancel()
    }

    private fun onAddons(addons: List<Descriptor>, preset: List<HomeCatalog>) {
        this.preset = preset.isNotEmpty()
        this.addons = addons
        val plan = if (preset.isEmpty()) AggrRequest.board().plan(addons) else presetPlan(addons, preset)
        val keys = rowKeys(plan)
        val homeKeys = homeRowKeys(plan)
        val previous = addonRows.associateByTo(mutableMapOf<ResourceRequest, Row>()) { (it.feed as RowFeed.Addon).planned.request }
        addonRows = plan.mapIndexed { i, planned ->
            (previous.remove(planned.request) ?: Row(keys[i], homeKeys[i], RowFeed.Addon(planned))).also {
                it.key = keys[i]
                it.homeKey = homeKeys[i]
                it.feed = RowFeed.Addon(planned)
            }
        }
        previous.values.forEach { it.cancel() }
        addonsKnown = true
        hasGuide = Guides.catalogs(addons).isNotEmpty()
        rebuildRows()
    }

    /** The profile's collections changed: one row each, but for those without folders. */
    private fun onCollections(collections: List<Collection>) {
        val previous = collectionRows.associateBy { it.collection?.id }
        collectionRows = collections.filter { it.folders.isNotEmpty() }.map { collection ->
            (previous[collection.id] ?: Row("collection/${collection.id}", collectionHomeKey(collection.id), feed = null)).also {
                it.collection = collection
                it.status = RowStatus.Ready(loadingMore = false)
            }
        }
    }

    /** The [preset] rows as requests to the installed addons that have their catalogs, in the preset's order. */
    private fun presetPlan(addons: List<Descriptor>, preset: List<HomeCatalog>): List<PlannedRequest> = preset.mapNotNull { home ->
        val addon = addons.firstOrNull { it.transportUrl == home.addonUrl } ?: return@mapNotNull null
        if (addon.manifest.catalogs.none { it.type == home.type && it.id == home.id }) return@mapNotNull null
        PlannedRequest(addon, ResourceRequest(addon.transport, ResourcePath(Resources.CATALOG, home.type, home.id, home.extra)))
    }

    /** The connected trackers changed: keeps the lists of those still connected, reads the new ones'. */
    private fun onTrackers(sources: List<TrackerCatalogSource>) {
        val previous = trackers
        trackers = sources.map { source -> previous.firstOrNull { it.source === source } ?: TrackerSection(source).also { it.list() } }
        previous.filter { it !in trackers }.forEach { it.cancel() }
        rebuildRows()
    }

    /**
     * The rows or the layout changed: arranges the rows by the layout (once read), requests the first [INITIAL_ROWS]
     * (those not requested yet) and publishes. A row the layout just hid stops loading; it loads when shown again.
     */
    private fun rebuildRows() {
        val layout = layout ?: return publish()
        val all = (if (preset) emptyList() else collectionRows + trackers.flatMap { it.rows }) + addonRows
        val byKey = all.associateBy { it.homeKey }
        val arranged = layout.arrange(byKey.keys.toList()).map(byKey::getValue)
        rows = arranged.filterNot { layout.isHidden(it.homeKey) }
        onScreen.retainAll(rows)
        layoutHidesRows = rows.size < arranged.size
        for (row in arranged) {
            if (row.status == RowStatus.Loading && layout.isHidden(row.homeKey) && row.lists == null) {
                row.cancel()
                row.status = RowStatus.Idle
            }
        }
        // The addons, the trackers or the collections changed: what each folder's sources lack.
        val sources = trackers.map { it.source }
        for (row in rows) row.collection?.let { collection -> row.tiles = collection.folders.map { tile(it, sources) } }
        rows.take(INITIAL_ROWS).forEach { load(it, force = false) }
        publish()
    }

    /** [folder]'s tile: its note is its first source's while none of them can show. */
    private fun tile(folder: CollectionFolder, trackers: List<TrackerCatalogSource>): FolderTile {
        val states = folder.sources.map { CollectionSources.resolve(it, addons, trackers, traktLists) }
        val note = if (states.any { it is SourceState.Ready }) null else states.firstOrNull()?.note
        return FolderTile(folder.id, folder.title, folder.coverUrl, showTitle = !folder.hideTitle, note = note)
    }

    /** The network is back: the rows that failed load again (tracker lists that couldn't be read are read again). */
    private fun reloadFailed() {
        for (row in rows.filter { it.status is RowStatus.Failed }) {
            val lists = row.lists
            if (lists != null) {
                row.status = RowStatus.Loading
                lists.list()
            } else {
                load(row, force = true)
            }
        }
    }

    /** Follows Continue Watching while the layout shows it; a hidden one isn't read. */
    private fun followContinueWatching(shown: Boolean) {
        if (shown == (continueWatchingJob != null)) return
        if (shown) {
            continueWatchingKnown = false
            continueWatchingJob = scope.launch {
                combine(
                    continueWatchingItems
                        .map { items -> items.map { it.toContinueWatchingCard() } }
                        .distinctUntilChanged()
                        .flowOn(parseDispatcher),
                    newEpisodes,
                ) { cards, counts -> if (counts.isEmpty()) cards else cards.map { it.copy(newEpisodes = counts[it.id] ?: 0) } }
                    .distinctUntilChanged()
                    .collect { cards ->
                        continueWatching = cards
                        continueWatchingKnown = true
                        publish()
                    }
            }
        } else {
            continueWatchingJob?.cancel()
            continueWatchingJob = null
            continueWatching = emptyList()
            continueWatchingKnown = true
        }
    }

    /**
     * Requests the [PREFETCH_ROWS] shown rows after each row on screen that aren't requested yet; true when it did. The
     * rows after one change as rows answer empty, arrive or go, so every [publish] asks.
     */
    private fun loadAhead(): Boolean {
        var asked = false
        for (i in shown.indices) {
            if (shown[i] !in onScreen) continue
            for (next in i + 1..minOf(i + PREFETCH_ROWS, shown.lastIndex)) asked = load(shown[next], force = false) || asked
        }
        return asked
    }

    /** Requests [row]'s first page unless it is already requested and not [force]d; true when it did. */
    private fun load(row: Row, force: Boolean): Boolean {
        if (row.status != RowStatus.Idle && !force) return false
        row.load()
        return true
    }

    /** Requests the rows now after those on screen ([loadAhead]) and publishes. */
    private fun publish() {
        rows.forEach { it.fillLeftOut() }
        shown = rows.filterNot { it.empty || it.emptyOfLeftOut() || it.emptyAfterFill() }
        loadAhead()
        mutableState.value = buildState()
    }

    private fun buildState(): BoardUiState {
        val boardRows = shown.map { row ->
            val collection = row.collection
            if (collection != null) {
                return@map BoardRow(
                    key = row.key,
                    title = collection.title,
                    sourceName = T.collections.row,
                    fromTracker = false,
                    status = RowStatus.Ready(loadingMore = false),
                    items = emptyList(),
                    seeAll = SeeAll.NONE,
                    folders = row.tiles,
                    folderShape = collection.shape,
                )
            }
            // Kept marked, so an unchanged first page stays the same list.
            row.items = row.items.marked(marks, downloads, watchlists, captions)
            val feed = row.feed
            val service = row.lists?.source?.service
            // On a kids profile, the cards its walk shows ([FeedRow.emptyAfterFill]); the titles left out stay out.
            val items = row.shown().marked(marks, downloads, watchlists, captions)
            BoardRow(
                key = row.key,
                title = when (feed) {
                    is RowFeed.Addon -> feed.planned.catalog()?.let(::catalogRowTitle) ?: row.key
                    is RowFeed.Tracker -> trackerRowTitle(feed.catalog)
                    null -> service?.let(::serviceName) ?: row.key
                },
                sourceName = when (feed) {
                    is RowFeed.Addon -> feed.planned.addon.manifest.name
                    is RowFeed.Tracker -> serviceName(feed.source.service)
                    null -> service?.let(::serviceName).orEmpty()
                },
                fromTracker = feed !is RowFeed.Addon,
                // Rows left with none are left out.
                status = row.shownStatus(items),
                items = if (remote) row.cut(items) else items,
                seeAll = when {
                    !remote -> SeeAll.HEADING
                    items.size > REMOTE_ROW_ITEMS || row.hasNextPage() || row.paging.walk?.stopped == true -> SeeAll.CARD
                    else -> SeeAll.NONE
                },
            )
        }
        // Every row left out has answered empty or is hidden, so an empty list with nothing in progress is final.
        val settled = addonsKnown && layout != null && collectionsKnown && continueWatchingKnown && trackers.none { it.listing }
        continueWatching = continueWatching.withMenuMarks(downloads, watchlists)
        val continueCards = if (kids == null) continueWatching else continueWatching.filter { kids.shows(it.type, it.id, preview = null) }
        val empty = settled && boardRows.isEmpty() && continueCards.isEmpty()
        return BoardUiState(
            continueWatching = continueCards,
            body = when {
                !online -> BoardBody.Offline(OfflineState.of(offersDownloads))
                addonsUnreadable -> BoardBody.Empty(T.addons.unreadable)
                !addonsKnown || layout == null || !collectionsKnown -> BoardBody.Loading
                !empty -> BoardBody.Rows(boardRows)
                layoutHidesRows -> BoardBody.Empty(T.browse.board.allHidden)
                else -> BoardBody.Empty(T.browse.board.empty)
            },
            shortcuts = shortcuts(),
        )
    }

    /**
     * [downloads] summed up for Home's Downloads shortcut, how many are on their way and how far ("Downloading 2, 45%",
     * "Downloading 1" while none has a known size): null while none is.
     */
    private fun onTheirWay(downloads: List<Download>): String? {
        val active = downloads.filter { it.status.active }
        if (active.isEmpty()) return null
        val sized = active.filter { (it.totalBytes ?: 0) > 0 }
        // Whole percents in integers: a fraction times 100 can land just under the percent it is (0.29 gives 28.99…).
        val percent = sized.takeIf { it.isNotEmpty() }
            ?.let { list -> (list.sumOf { it.bytes } * 100 / list.sumOf { checkNotNull(it.totalBytes) }).toInt().coerceIn(0, 100) }
        val count = active.size.toString()
        return percent?.let { T.browse.board.downloading(count, it.toString()) } ?: T.browse.board.downloadingUnsized(count)
    }

    /** [BoardUiState.shortcuts]: a tracker's list whose title another shortcut has too names its service. */
    private fun shortcuts(): List<BoardShortcut> {
        val fixed = listOfNotNull(
            if (!runsAsKids) {
                BoardShortcut(WATCH_TOGETHER, BoardShortcutKind.WATCH_TOGETHER, if (watching > 0) T.watch.player.watching(watching) else T.watch.title)
            } else {
                null
            },
            if (offersDownloads) BoardShortcut(DOWNLOADS, BoardShortcutKind.DOWNLOADS, onTheirWay ?: T.downloads.title) else null,
            BoardShortcut(LIBRARY, BoardShortcutKind.LIBRARY, T.nav.destination.library),
            if (hasGuide && !runsAsKids) BoardShortcut(GUIDE, BoardShortcutKind.GUIDE, T.guide.title) else null,
            BoardShortcut(CALENDAR, BoardShortcutKind.CALENDAR, T.calendar.title),
        )
        val lists = trackerLists().map { (_, list) -> list to trackerRowTitle(list.catalog) }
        val counts = (fixed.map { it.label } + lists.map { it.second }).groupingBy { it }.eachCount()
        return fixed + lists.map { (list, title) ->
            // Its row's title ("Trending · Movies"), which may start with the list's own name: isolated whole.
            val label = if (counts.getValue(title) > 1) T.browse.rowTitle(isolate(title), serviceName(list.source.service)) else title
            BoardShortcut(shortcutKey(list), BoardShortcutKind.TRACKER_LIST, label, tracker = list.source.service)
        }
    }

    /** The connected trackers' lists in order, those Home's layout hides included; a tracker whose lists failed has none. */
    private fun trackerLists(): List<Pair<Row, RowFeed.Tracker>> =
        trackers.flatMap { it.rows }.mapNotNull { row -> (row.feed as? RowFeed.Tracker)?.let { row to it } }

    private fun shortcutKey(list: RowFeed.Tracker): String = "tracker.${list.source.service.id}.${list.catalog.id}"

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "board"

        /** Rows requested before any is reported visible. */
        const val INITIAL_ROWS = 3

        /** Shown rows requested after one on screen. */
        const val PREFETCH_ROWS = 1

        /** The items a row shows on a remote's UI, before its See all card. */
        const val REMOTE_ROW_ITEMS = 20

        /** stremio-core's `CATALOG_PREVIEW_SIZE`. */
        const val CONTINUE_WATCHING_LIMIT = 100

        private const val TAG = "board"

        private const val WATCH_TOGETHER = "watchTogether"
        private const val DOWNLOADS = "downloads"
        private const val LIBRARY = "library"
        private const val GUIDE = "guide"
        private const val CALENDAR = "calendar"
    }
}
