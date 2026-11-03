package com.edde746.tortuga.presentation.discover

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.CatalogPaging
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.presentation.board.catalogRowTitle
import com.edde746.tortuga.presentation.board.marked
import com.edde746.tortuga.presentation.board.rowTitle
import com.edde746.tortuga.presentation.board.snapshot
import com.edde746.tortuga.presentation.board.title
import com.edde746.tortuga.presentation.board.toCards
import com.edde746.tortuga.presentation.board.trackerCatalogName
import com.edde746.tortuga.presentation.board.trackerErrorText
import com.edde746.tortuga.presentation.board.trackerOfLink
import com.edde746.tortuga.presentation.board.typeLabel
import com.edde746.tortuga.presentation.board.typeOrder
import com.edde746.tortuga.presentation.menu.DownloadMarks
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Discover's store, stremio-core's `CatalogWithFilters` for meta catalogs, plus the lists of the
 * profile's connected trackers:
 *
 * - The catalogs are every catalog of the installed addons whose required extras have options, then each connected
 *   tracker's lists ([trackerSources]; a list of both types is offered under each, with that type's items); the types
 *   are theirs, ordered as stremio-core orders them. Picking a type opens its first catalog, picking a catalog opens
 *   it with the first option of each required extra, and a filter option replaces (or, for multi-value extras,
 *   toggles) that extra's value. Tracker lists have no filters.
 * - It opens on [link] (a Board row's "See all", a genre link of a meta item), else on the first catalog of the first
 *   type; [open] moves to a linked catalog later. A link opens any addon catalog, as stremio-core's `selected_update`
 *   loads any request: also one Discover doesn't list (a required extra without options, an addon not installed).
 *   That one shows under its type with none of the type's catalogs picked, titled with its manifest's name (its id
 *   when the addon isn't installed); it has its manifest's filters and pages where its addon is installed, none
 *   otherwise. A link to a tracker list waits for the tracker's lists to be read.
 * - Addon pages follow stremio-core's rule ([CatalogPaging]): `skip` is the number of items the addon sent so far, and
 *   there is a next page while every page so far had items. Tracker pages follow the service's cursor. An item on two
 *   pages is listed once.
 * - The selection follows the installed addons and the connected trackers; a selected catalog that goes away falls
 *   back to the first (a catalog a link opened stays). When the installed addons can't be read, Discover says so
 *   instead ([DiscoverBody.Empty]).
 * - While the device is offline ([network]) Discover says so ([DiscoverBody.Offline]), pointing to Downloads where
 *   the device has them ([offersDownloads]); once it is back, a page and tracker lists that failed load again.
 * - Every card follows its title's library state ([marks]) and its download's on this device
 *   ([MediaActions.downloadMarks]): watched, in the library, downloaded. [actions] carries out its menu's entries
 *   ([MetaCard.menu]), and says so over the app's screens when one can't be.
 * - On a kids profile ([kids]) the cards pass the gate in order ([KidsGate.Paging]): a card shows once its verdict is
 *   known and it passes, and only the ratings of the cards the grid is about to show are looked up, further each time
 *   it is scrolled to its end showing as many as it wants ([loadNextPage]). A grid that went through its cards showing
 *   too few loads the next page by itself (at most [KidsGate.FILL_PAGES] after the first) unless a lookup failed
 *   (until the gate asks again).
 * - The grid leaves out the titles of [hiddenTitles] (the finished ones while "Hide watched titles" is on). When more
 *   than half of a catalog's first page is left out, its second page loads by itself, once per catalog opened; further
 *   pages come as the grid is scrolled to its end. Its cards show their captions as [posterCaptions] says
 *   ([MetaCard.caption]).
 *
 * Call everything on the main thread; [release] ends it.
 */
class DiscoverStore internal constructor(
    private val installedAddons: Flow<List<Descriptor>>,
    private val aggregator: AddonAggregator,
    private val trackerSources: Flow<List<TrackerCatalogSource>>,
    link: DeepLink.Discover?,
    private val host: DiscoverHost,
    /** The app's scope, whose child [scope] is Discover's. */
    private val parentScope: CoroutineScope,
    private val network: StateFlow<NetworkState>,
    private val offersDownloads: Boolean,
    /** The profile's titles in the library or watched, by id. */
    private val marks: Flow<Map<String, TitleMark>>,
    private val actions: MediaActions,
    /** The transport URLs of the installed addons that are turned off, which a link's catalog never asks. */
    private val disabledAddons: () -> Set<String> = { emptySet() },
    /** The profile's kids rules; without them every card stays as it is. */
    private val kids: KidsGate? = null,
    /** The titles the grid leaves out, by id; none while the switch is off. */
    private val hiddenTitles: Flow<Set<String>> = flowOf(emptySet()),
    /** The cards show their titles under the artwork ([MetaCard.caption]). */
    private val posterCaptions: Flow<Boolean> = flowOf(true),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** A catalog to pick: an addon's, or a tracker list in one type. */
    private sealed interface Entry {
        val type: String
        val title: String
        val sourceName: String
        val selection: Selection
    }

    /**
     * An addon's catalog: one Discover lists, or one a link opened, whose [addon] and [catalog] are null when the addon
     * isn't installed (or doesn't declare it). [request] is the one it opens with.
     */
    private class AddonEntry(val addon: Descriptor?, val catalog: ManifestCatalog?, val request: ResourceRequest) : Entry {
        override val type: String get() = request.path.type
        override val title: String get() = catalog?.title ?: request.path.id
        override val sourceName: String get() = addon?.manifest?.name.orEmpty()
        override val selection: Selection get() = Selection.Addon(request)

        /** [other] asks for this catalog. */
        fun names(other: ResourceRequest) =
            other.base == request.base && other.path.type == request.path.type && other.path.id == request.path.id
    }

    private class TrackerEntry(val source: TrackerCatalogSource, val catalog: TrackerCatalog, override val type: String) : Entry {
        override val title: String get() = trackerCatalogName(catalog)
        override val sourceName: String get() = serviceName(source.service)
        override val selection: Selection get() = Selection.Tracker(source.service, catalog.id, type)
    }

    /** What is shown: an addon catalog's request (with its filters), or a tracker list in one type. */
    private sealed interface Selection {
        data class Addon(val request: ResourceRequest) : Selection

        data class Tracker(val service: TrackerService, val catalogId: String, val type: String) : Selection
    }

    /** One page: an addon catalog's [request], or a tracker list's page at [cursor]. */
    private class Page(val request: ResourceRequest?, val cursor: String?) {
        var loading = true
        var cards: List<MetaCard> = emptyList()

        /** Why it failed, worded; null otherwise. */
        var error: String? = null

        /** Top-level items the addon sent, malformed ones included: what `skip` counts. Null unless ready. */
        var size: Int? = null

        /** A tracker page's next cursor; null on the last. */
        var next: String? = null
        var job: Job? = null
    }

    /** A connected tracker's lists, once read (null while reading, or when they couldn't be read). */
    private class TrackerLists(val source: TrackerCatalogSource) {
        var catalogs: List<TrackerCatalog>? = null
        var listing = true
        var job: Job? = null
    }

    private var pendingLink: DeepLink.Discover? = link
    private var addonsKnown = false
    private var addonsUnreadable = false
    private var addons: List<Descriptor> = emptyList()
    private var addonCatalogs: List<DiscoverCatalog> = emptyList()

    /** The catalog a link opened that Discover doesn't list, while it is selected. */
    private var linked: AddonEntry? = null
    private var trackers: List<TrackerLists> = emptyList()
    private var trackersKnown = false
    private var entries: List<Entry> = emptyList()

    /** What the pickers' indices refer to. */
    private var types: List<Entry> = emptyList()
    private var ofType: List<Entry> = emptyList()
    private var filters: List<DiscoverFilter> = emptyList()

    private var selected: Entry? = null

    /** The first page's selection (an addon request without `skip`). */
    private var selection: Selection? = null
    private val pages = mutableListOf<Page>()

    /** How the grid pages on a kids profile, and what its last walk over the cards found ([KidsGate.Paging]). */
    private var paging = KidsGate.Paging<MetaCard>(kids)

    private var online = network.value.online
    private var titleMarks: Map<String, TitleMark> = emptyMap()
    private var downloads: DownloadMarks? = null
    private var watchlists: WatchlistMarks? = null

    /** The titles the grid leaves out ([hiddenTitles]). */
    private var leftOut: Set<String> = emptySet()

    /** The cards show their captions ([posterCaptions]). */
    private var captions = true

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<DiscoverUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            readingAddons(TAG) { installedAddons.collect(::onAddons) } ?: run {
                addonsUnreadable = true
                publish()
            }
        }
        scope.launch { trackerSources.collect(::onTrackers) }
        kids?.let { gate -> scope.launch { gate.changes.collect { publish() } } }
        scope.launch {
            network.collect { state ->
                val back = state.online && !online
                online = state.online
                if (back) reloadFailed()
                publish()
            }
        }
        scope.launch {
            marks.distinctUntilChanged().collect {
                titleMarks = it
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
    }

    /** Opens the first catalog of the type at [index] of [DiscoverUiState.types]. */
    fun selectType(index: Int) {
        types.getOrNull(index)?.let { select(it.selection) }
    }

    /** Opens the catalog at [index] of [DiscoverUiState.catalogs]. */
    fun selectCatalog(index: Int) {
        ofType.getOrNull(index)?.let { select(it.selection) }
    }

    /** Picks option [optionIndex] of the filter at [filterIndex] of [DiscoverUiState.filters]. */
    fun selectFilter(filterIndex: Int, optionIndex: Int) {
        filters.getOrNull(filterIndex)?.options?.getOrNull(optionIndex)?.let { select(Selection.Addon(it.request)) }
    }

    /**
     * Opens [link]'s catalog, as at the start: a link followed while Discover is open (a genre of a meta item). A link
     * to a tracker list that isn't connected changes nothing.
     */
    fun open(link: DeepLink.Discover) {
        if (!addonsKnown || waitsForTracker(link)) {
            pendingLink = link
            publish()
            return
        }
        resolve(link)?.let(::select)
    }

    /**
     * Loads the next page, when there is one and nothing is loading ([DiscoverMore.Available]), as a kids
     * profile's paging allows ([KidsGate.Paging.end]).
     */
    fun loadNextPage() {
        if (paging.end(::appendPage)) publish()
    }

    /** Asks for the page after those loaded, when there is one; true when it did. */
    private fun appendPage(): Boolean {
        val page = nextPage() ?: return false
        pages += page
        load(page)
        return true
    }

    /** Loads the failed page again: the first ([DiscoverBody.Failed]) or the last ([DiscoverMore.Failed]). */
    fun retry() {
        val page = pages.lastOrNull() ?: return
        if (page.error == null) return
        load(page)
        publish()
    }

    fun openItem(card: MetaCard) = host.openMeta(card.type, card.id, card.preview)

    /** Carries out an entry of [card]'s menu ([MetaCard.menu]). */
    fun menuAction(card: MetaCard, action: MediaAction) = actions.perform(card.snapshot(), videoId = null, action, host)

    /** Opens Library on Downloads ([DiscoverBody.Offline]'s action). */
    fun openDownloads() = host.openDownloads()

    fun release() {
        scope.cancel()
    }

    private fun onAddons(addons: List<Descriptor>) {
        this.addons = addons
        addonCatalogs = DiscoverCatalogs.selectable(addons)
        addonsKnown = true
        onCatalogs()
    }

    /** The connected trackers changed: keeps the lists read for those still connected, reads the new ones'. */
    private fun onTrackers(sources: List<TrackerCatalogSource>) {
        val previous = trackers
        trackers = sources.map { source -> previous.firstOrNull { it.source === source } ?: TrackerLists(source).also(::list) }
        previous.filter { it !in trackers }.forEach { it.job?.cancel() }
        trackersKnown = true
        onCatalogs()
    }

    /** Reads a tracker's lists. */
    private fun list(lists: TrackerLists) {
        lists.listing = true
        lists.job = scope.launch {
            val source = lists.source
            lists.catalogs = try {
                source.catalogs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "${source.service.id} lists failed" }
                null
            }
            lists.listing = false
            onCatalogs()
        }
    }

    /** The network is back: the page that failed loads again, and so do the tracker lists that couldn't be read. */
    private fun reloadFailed() {
        trackers.filter { !it.listing && it.catalogs == null }.forEach(::list)
        retry()
    }

    /** The catalogs changed: the pickers follow, and the selection moves only if its catalog went away. */
    private fun onCatalogs() {
        entries = addonCatalogs.map { AddonEntry(it.addon, it.catalog, it.request) } + trackers.flatMap { lists ->
            lists.catalogs.orEmpty().flatMap { catalog ->
                (catalog.type?.let(::listOf) ?: MIXED_TYPES).map { TrackerEntry(lists.source, catalog, it) }
            }
        }
        linked = linked?.let { unlisted(it.request) }
        if (!addonsKnown) {
            publish()
            return
        }
        val link = pendingLink
        if (link != null) {
            val target = resolve(link)
            if (target != null) {
                pendingLink = null
                select(target)
                return
            }
            if (waitsForTracker(link)) {
                select(null)
                return
            }
            pendingLink = null
        }
        select(selection?.takeIf { find(it) != null } ?: firstOfTypes(entries).firstOrNull()?.selection)
    }

    /**
     * The selection [link] names: an addon catalog (one Discover doesn't list becomes [linked]), or a connected
     * tracker's list.
     */
    private fun resolve(link: DeepLink.Discover): Selection? {
        val service = trackerOfLink(link.transportUrl)
        if (service == null) {
            // A turned-off addon is asked for nothing: a link to its catalog opens Discover as no link does.
            if (link.transportUrl in disabledAddons()) return null
            val request = DiscoverCatalogs.resolve(addons, link) ?: run {
                Log.w(TAG) { "A Discover link names no addon's transport URL" }
                return null
            }
            if (entries.none { it is AddonEntry && it.names(request) }) linked = unlisted(request)
            return Selection.Addon(request)
        }
        return entries.firstOrNull { it is TrackerEntry && it.source.service == service && it.catalog.id == link.catalogId && it.type == link.type }
            ?.selection
    }

    /** [request]'s catalog as one Discover doesn't list, with its installed addon and manifest catalog where there are. */
    private fun unlisted(request: ResourceRequest): AddonEntry {
        val declared = DiscoverCatalogs.declared(addons, request)
        return AddonEntry(declared?.addon, declared?.catalog, request)
    }

    /** [link] names a tracker whose lists aren't known yet: the connected trackers, or its lists, are being read. */
    private fun waitsForTracker(link: DeepLink.Discover): Boolean {
        val service = trackerOfLink(link.transportUrl) ?: return false
        return !trackersKnown || trackers.any { it.source.service == service && it.listing }
    }

    private fun find(selection: Selection): Entry? = when (selection) {
        is Selection.Addon -> entries.firstOrNull { it is AddonEntry && it.names(selection.request) }
            ?: linked?.takeIf { it.names(selection.request) }
        is Selection.Tracker -> entries.firstOrNull { it.selection == selection }
    }

    /** Shows [selection]'s first page (unless it is shown already) and derives the pickers for it. */
    private fun select(selection: Selection?) {
        val changed = selection != this.selection
        this.selection = selection
        types = firstOfTypes(entries)
        val entry = selection?.let(::find)
        selected = entry
        if (entry !== linked) linked = null
        ofType = if (entry == null) entries else entries.filter { it.type == entry.type }
        val catalog = (entry as? AddonEntry)?.catalog
        filters = if (selection is Selection.Addon && catalog != null) DiscoverCatalogs.filters(catalog, selection.request) else emptyList()
        if (changed) {
            pages.forEach { it.job?.cancel() }
            pages.clear()
            paging = KidsGate.Paging<MetaCard>(kids)
            when (selection) {
                is Selection.Addon -> Page(selection.request, cursor = null)
                is Selection.Tracker -> Page(request = null, cursor = null)
                null -> null
            }?.let { page ->
                pages += page
                load(page)
            }
        }
        publish()
    }

    private fun nextPage(): Page? {
        val last = pages.lastOrNull() ?: return null
        return when (val entry = selected) {
            is AddonEntry -> {
                val request = (selection as? Selection.Addon)?.request ?: return null
                val catalog = entry.catalog ?: return null
                CatalogPaging.nextPage(catalog, request, pages.map { it.size })?.let { Page(it, cursor = null) }
            }
            is TrackerEntry -> last.next?.takeIf { !last.loading && last.error == null }?.let { Page(request = null, cursor = it) }
            null -> null
        }
    }

    private fun load(page: Page) {
        val entry = selected ?: return
        page.job?.cancel()
        page.loading = true
        page.error = null
        page.job = when (entry) {
            is AddonEntry -> {
                val request = page.request ?: return
                scope.launch {
                    aggregator.load(request, entry.addon?.manifest?.name ?: UNINSTALLED, ContentKind.METAS) { it.toCards() }.collect { state ->
                        if (state == Loadable.Loading) return@collect
                        page.loading = false
                        val error = (state as? Loadable.Error)?.error
                        page.error = error?.takeIf { it != AddonError.EmptyContent }?.message
                        val cards = if (state is Loadable.Ready) state.content else emptyList()
                        val listed = pages.takeWhile { it !== page }.flatMapTo(HashSet()) { earlier -> earlier.cards.map { it.id } }
                        // The page's size counts what the addon sent (paging goes by it), before toCards drops repeats;
                        // a page of titles listed already is the end, as an empty one is (an addon that ignores skip
                        // answers every page with its first).
                        page.size = (state as? Loadable.Ready)?.let { ready -> if (cards.any { it.id !in listed }) ready.sent else 0 }
                        page.cards = cards
                        publish()
                    }
                }
            }
            is TrackerEntry -> scope.launch {
                try {
                    val answer = entry.source.page(entry.catalog, page.cursor)
                    // A list of both types shows the selected type's items.
                    page.cards = answer.items.filter { it.type == entry.type }.toCards()
                    page.next = answer.next
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "${entry.source.service.id} list ${entry.catalog.kind} failed" }
                    page.error = trackerErrorText(entry.source.service, e)
                }
                page.loading = false
                publish()
                // A page with none of the selected type's items: the next one may have some.
                if (page.error == null && page.cards.isEmpty() && page.next != null && page === pages.lastOrNull() && appendPage()) publish()
            }
        }
    }

    /**
     * Walks the cards ([KidsGate.Paging.walk]) and fills ([KidsGate.Paging.fill]): a page on its way, or a failed one
     * (it stays until retried), holds the filling up. A first page mostly [leftOut] loads the second ([fillLeftOut]).
     */
    private fun publish() {
        paging.walk(cards())
        paging.fill(asked = pages.size - 1, busy = pages.any { it.loading || it.error != null }, ::appendPage)
        fillLeftOut()
        mutableState.value = buildState()
    }

    /** The pages' cards, each id once, without the titles the grid leaves out ([leftOut]). */
    private fun cards(): List<MetaCard> {
        val all = pages.flatMap { it.cards }.distinctBy { it.id }
        return if (leftOut.isEmpty()) all else all.filterNot { it.id in leftOut }
    }

    /**
     * Loads the second page when the first, loaded, has more than half of its cards [leftOut]: at most one page for
     * the catalog opened, so the grid isn't left nearly empty.
     */
    private fun fillLeftOut() {
        val first = pages.singleOrNull() ?: return
        if (leftOut.isEmpty() || first.loading || first.error != null) return
        if (first.cards.count { it.id in leftOut } * 2 <= first.cards.size) return
        appendPage()
    }

    private fun buildState(): DiscoverUiState {
        val selected = selected
        val first = pages.firstOrNull()
        val last = pages.lastOrNull()
        val waiting = pendingLink?.let(::waitsForTracker) == true
        val walk = paging.walk
        // On a kids profile, the cards its walk shows.
        val items = (walk?.shown ?: cards()).marked(titleMarks, downloads, watchlists, captions)
        // A kids profile's cards waiting for their verdicts.
        val judging = walk?.judging == true
        return DiscoverUiState(
            title = selected?.let { entry ->
                when (entry) {
                    is AddonEntry -> entry.catalog?.let(::catalogRowTitle) ?: rowTitle(entry.title, entry.type)
                    is TrackerEntry -> rowTitle(entry.title, entry.type)
                }
            },
            types = types.takeIf { it.isNotEmpty() }?.let { list ->
                DiscoverPicker(
                    label = T.browse.discover.type,
                    selection = selected?.let { typeLabel(it.type) }.orEmpty(),
                    isRequired = true,
                    multiSelect = false,
                    options = list.map { DiscoverOption(typeLabel(it.type), null, it.type == selected?.type) },
                )
            },
            catalogs = ofType.takeIf { it.isNotEmpty() }?.let { list ->
                DiscoverPicker(
                    label = T.browse.discover.catalog,
                    selection = selected?.title.orEmpty(),
                    isRequired = true,
                    multiSelect = false,
                    options = list.map { DiscoverOption(it.title, it.sourceName, it === selected) },
                )
            },
            filters = filters.map { filter ->
                val request = (selection as? Selection.Addon)?.request
                val values = request?.path?.extra.orEmpty().filter { it.name == filter.prop.name }.map { it.value }
                DiscoverPicker(
                    label = extraLabel(filter.prop.name),
                    // Several values (genres, as the addon names them) isolated each; one shows alone.
                    selection = if (values.isEmpty()) T.browse.discover.all else values.singleOrNull() ?: values.joinToString(T.detail.listSeparator) { isolate(it) },
                    isRequired = filter.prop.isRequired,
                    multiSelect = filter.prop.optionsLimit > 1,
                    options = filter.options.map { DiscoverOption(it.value ?: T.browse.discover.all, null, it.selected) },
                )
            },
            body = body(items, first, last, waiting, judging),
        )
    }

    /** [DiscoverBody]'s precedence over the pages loaded; [items] the cards shown. */
    private fun body(items: List<MetaCard>, first: Page?, last: Page?, waiting: Boolean, judging: Boolean): DiscoverBody {
        if (!online) return DiscoverBody.Offline(OfflineState.of(offersDownloads))
        val loading = !addonsUnreadable && (!addonsKnown || waiting || first?.loading == true || (judging && items.isEmpty()))
        if (items.isEmpty() && loading) return DiscoverBody.Loading
        first?.error?.let { return DiscoverBody.Failed(it) }
        val emptyText = when {
            addonsUnreadable && items.isEmpty() -> T.addons.unreadable
            addonsKnown && !waiting && entries.isEmpty() && selected == null -> T.browse.discover.noCatalogs
            // Every page answered, none with an item of the type (a tracker list's pages may all lack it), none left.
            items.isEmpty() && !judging && paging.walk?.stopped != true && pages.isNotEmpty() && pages.none { it.loading || it.error != null } &&
                nextPage() == null -> T.browse.discover.empty
            else -> null
        }
        if (emptyText != null) return DiscoverBody.Empty(emptyText)
        val moreError = last?.error?.takeIf { pages.size > 1 }
        val more = when {
            moreError != null -> DiscoverMore.Failed(moreError)
            pages.size > 1 && last?.loading == true -> DiscoverMore.Loading
            paging.goesFurther(hasNext = nextPage() != null) -> DiscoverMore.Available
            else -> DiscoverMore.None
        }
        return DiscoverBody.Grid(items, more)
    }

    /** An extra's name as a filter label: "Genre"; others as the addon wrote them. */
    private fun extraLabel(name: String): String = when (name) {
        Extras.GENRE -> T.browse.discover.genre
        else -> name.replaceFirstChar(Char::uppercaseChar)
    }

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "discover"

        /** Items from the end of the grid at which the screen asks for the next page ([loadNextPage]): about two rows. */
        const val LOAD_AHEAD: Int = 12

        private const val TAG = "discover"

        /** An addon that isn't installed, in the logs (its transport URL may carry a secret). */
        private const val UNINSTALLED = "an addon not installed"

        /** The types a tracker list of both types (a watchlist) is offered under. */
        private val MIXED_TYPES = listOf("movie", "series")

        /** The first catalog of each type, the types in Stremio's order ([typeOrder]). */
        private fun firstOfTypes(entries: List<Entry>): List<Entry> = entries.distinctBy { it.type }.sortedWith(compareBy(typeOrder) { it.type })
    }
}
