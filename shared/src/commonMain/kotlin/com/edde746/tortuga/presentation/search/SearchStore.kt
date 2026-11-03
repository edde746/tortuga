package com.edde746.tortuga.presentation.search

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.RecentSearches
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.presentation.board.RowPages
import com.edde746.tortuga.presentation.board.catalog
import com.edde746.tortuga.presentation.board.catalogRowTitle
import com.edde746.tortuga.presentation.board.marked
import com.edde746.tortuga.presentation.board.rowKeys
import com.edde746.tortuga.presentation.board.snapshot
import com.edde746.tortuga.presentation.board.toCards
import com.edde746.tortuga.presentation.board.typeLabel
import com.edde746.tortuga.presentation.menu.DownloadMarks
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Search's store (stremio-core `CatalogsWithExtra` with a `search` extra):
 *
 * - A typed query is searched once typing pauses for [DEBOUNCE_MILLIS]; [submit] searches at once. A blank query
 *   clears the results and shows the recent searches.
 * - Every catalog of [AggrRequest.search] is asked in parallel; results are grouped by catalog in addon order, each
 *   group loading, failing or filling on its own. A group with no results is left out. A group scrolled to its end
 *   loads its next page ([loadNextPage], stremio-core's `LoadNextPage`; [RowPages]) where its catalog declares `skip`.
 *   When the installed addons can't be read, a search says so ([SearchBody.Empty]).
 * - Recent searches are the queries the user committed to: submitted, picked from the recents, opened from a link, or
 *   whose result was opened. They are device-local and per profile ([RecentSearches]: a device setting, never synced,
 *   at most [RecentSearches.LIMIT]).
 * - A `stremio:///search?search=…` link opens it with [search].
 * - While the device is offline ([network]) Search says so ([SearchBody.Offline]), pointing to Downloads where the
 *   device has them ([offersDownloads]); once it is back, a search whose catalogs failed is asked again.
 * - Every result follows its title's library state ([marks]) and its download's on this device
 *   ([MediaActions.downloadMarks]): watched, in the library, downloaded. [actions] carries out its menu's entries
 *   ([MetaCard.menu]), and says so over the app's screens when one can't be. Its cards show their captions as
 *   [posterCaptions] says ([MetaCard.caption]).
 * - On a kids profile ([kids]) a group's results pass the gate in order ([KidsGate.walk]): a result shows once its
 *   verdict is known and it passes, and only the ratings of the results a group is about to show are looked up,
 *   further each time it is scrolled to its end showing as many as it wants ([loadNextPage]). A group that went
 *   through its results showing too few loads its next pages by itself (at most [KidsGate.FILL_PAGES]) unless a
 *   lookup failed (until the gate asks again); one left with none is left out, and a search all of whose results are
 *   blocked finds nothing.
 *
 * Call everything on the main thread; [release] ends it.
 */
class SearchStore internal constructor(
    private val installedAddons: Flow<List<Descriptor>>,
    private val aggregator: AddonAggregator,
    private val deviceSettings: DeviceSettingsRepository,
    profileId: String,
    private val host: SearchHost,
    initialQuery: String?,
    /** The app's scope, whose child [scope] is Search's. */
    private val parentScope: CoroutineScope,
    private val network: StateFlow<NetworkState>,
    private val offersDownloads: Boolean,
    /** The profile's titles in the library or watched, by id. */
    private val marks: Flow<Map<String, TitleMark>>,
    private val actions: MediaActions,
    /** The profile's kids rules; without them every result stays as it is. */
    private val kids: KidsGate? = null,
    /** The results show their titles under the artwork ([MetaCard.caption]). */
    private val posterCaptions: Flow<Boolean> = flowOf(true),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val recentSetting = RecentSearches(profileId)

    /** The text as typed. */
    private var query = ""

    /** The query the results are for; null while the query is blank. */
    private var searched: String? = null
    private var addons: List<Descriptor>? = null
    private var addonsUnreadable = false
    private var plan: List<PlannedRequest> = emptyList()
    private var keys: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var results: List<AddonLoadable<List<MetaCard>>> = emptyList()
    private var resultsKnown = false

    /** Each catalog's pages after the first, as [plan]. */
    private var pages: List<RowPages> = emptyList()

    /** How each group pages on a kids profile, as [plan], and what each one's last walk found ([KidsGate.Paging]). */
    private var paging: List<KidsGate.Paging<MetaCard>> = emptyList()

    /** The plan's indices of the groups in [SearchBody.Results.groups], in order: what [loadNextPage]'s index refers to. */
    private var shown: List<Int> = emptyList()
    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    /** The recent searches, newest first, whatever the screen shows ([SearchBody.Recent] only while the query is blank). */
    var recent: List<String> = emptyList()
        private set
    private var recentLoaded = false

    /** Changes made before the stored list was read (a link's query is remembered as the store starts), applied to it once it is. */
    private val pendingRecentEdits = mutableListOf<(List<String>) -> List<String>>()

    /** The list to store; the latest one wins. */
    private val recentToSave = MutableStateFlow<List<String>?>(null)
    private var online = network.value.online
    private var titleMarks: Map<String, TitleMark> = emptyMap()
    private var downloads: DownloadMarks? = null
    private var watchlists: WatchlistMarks? = null

    /** The results show their captions ([posterCaptions]). */
    private var captions = true

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            recent = try {
                deviceSettings.value(recentSetting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The database read again what found it busy: this failure stays, and the searches start from none.
                Log.e("search", e) { "reading the recent searches failed (${e::class.simpleName})" }
                emptyList()
            }
            recentLoaded = true
            pendingRecentEdits.forEach { editRecent(it) }
            pendingRecentEdits.clear()
            publish()
        }
        // Each value is the whole list: one whose write fails is superseded by the next edit's.
        scope.launch {
            recentToSave.filterNotNull().collect {
                try {
                    deviceSettings.set(recentSetting, it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("search", e) { "saving the recent searches failed (${e::class.simpleName})" }
                }
            }
        }
        scope.launch {
            readingAddons("search") {
                installedAddons.collect {
                    addons = it
                    searched?.let(::start)
                    publish()
                }
            } ?: run {
                addonsUnreadable = true
                publish()
            }
        }
        scope.launch {
            network.collect { state ->
                val back = state.online && !online
                online = state.online
                if (back && results.any { it.state.failed() }) retry()
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
            posterCaptions.distinctUntilChanged().collect {
                captions = it
                publish()
            }
        }
        kids?.let { gate -> scope.launch { gate.changes.collect { publish() } } }
        initialQuery?.let(::search)
    }

    /** The query as typed; searched once typing pauses. */
    fun setQuery(text: String) {
        if (text == query) return
        query = text
        debounceJob?.cancel()
        debounceJob = null
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> clearResults()
            trimmed != searched -> debounceJob = scope.launch {
                delay(DEBOUNCE_MILLIS)
                debounceJob = null
                start(trimmed)
                publish()
            }
        }
        publish()
    }

    /** Searches the query now (the keyboard's search key) and remembers it. */
    fun submit() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        debounceJob?.cancel()
        debounceJob = null
        if (trimmed != searched) start(trimmed)
        remember(trimmed)
        publish()
    }

    /** Sets the query to [text] and searches it now: a `stremio:///search` link, or a recent search. */
    fun search(text: String) {
        setQuery(text)
        submit()
    }

    /** Searches the recent search at [index] of [SearchBody.Recent.recent]. */
    fun pickRecent(index: Int) {
        recent.getOrNull(index)?.let(::search)
    }

    /** Forgets the recent search at [index] of [SearchBody.Recent.recent]. */
    fun removeRecent(index: Int) {
        val removed = recent.getOrNull(index) ?: return
        editRecent { list -> list.filterNot { it == removed } }
        publish()
    }

    fun clearRecent() {
        editRecent { emptyList() }
        publish()
    }

    /** Asks every catalog again (answers still fresh in the response cache come from there). */
    fun retry() {
        searched?.let(::start)
        publish()
    }

    /**
     * The group at [index] of [SearchBody.Results.groups] is scrolled to its end: loads its next page, when it has one and
     * none is loading, as a kids profile's paging allows ([KidsGate.Paging.end]).
     */
    fun loadNextPage(index: Int) {
        val at = shown.getOrNull(index) ?: return
        val first = results.getOrNull(at)?.state as? Loadable.Ready ?: return
        if (paging[at].end { pages[at].loadNext(plan[at], first.content, first.sent) }) publish()
    }

    /** Opens a result; its query becomes a recent search. */
    fun openItem(card: MetaCard) {
        searched?.let(::remember)
        publish()
        host.openMeta(card.type, card.id, card.preview)
    }

    /** Carries out an entry of [card]'s menu ([MetaCard.menu]). */
    fun menuAction(card: MetaCard, action: MediaAction) = actions.perform(card.snapshot(), videoId = null, action, host)

    /** Opens Library on Downloads ([SearchBody.Offline]'s action). */
    fun openDownloads() = host.openDownloads()

    fun release() {
        scope.cancel()
    }

    private fun remember(query: String) {
        editRecent { list -> listOf(query) + list }
    }

    private fun editRecent(edit: (List<String>) -> List<String>) {
        if (!recentLoaded) {
            pendingRecentEdits += edit
            return
        }
        recent = recentSetting.normalize(edit(recent))
        recentToSave.value = recent
    }

    private fun start(query: String) {
        searchJob?.cancel()
        pages.forEach { it.cancel() }
        pages = emptyList()
        searched = query
        results = emptyList()
        resultsKnown = false
        val addons = addons ?: return // searched once the installed addons are read
        plan = AggrRequest.search(query).plan(addons)
        pages = plan.map { RowPages(scope, aggregator, ::publish) }
        paging = plan.map { KidsGate.Paging<MetaCard>(kids) }
        keys = rowKeys(plan)
        titles = groupTitles(plan)
        searchJob = scope.launch {
            aggregator.load(plan, ContentKind.METAS) { _, items -> items.toCards() }.collect { states ->
                results = states
                resultsKnown = true
                publish()
            }
        }
    }

    private fun clearResults() {
        searchJob?.cancel()
        pages.forEach { it.cancel() }
        pages = emptyList()
        searched = null
        plan = emptyList()
        results = emptyList()
        resultsKnown = false
    }

    /** The type, or "catalog · type" where one addon searches several catalogs of a type. */
    private fun groupTitles(plan: List<PlannedRequest>): List<String> = plan.map { planned ->
        val path = planned.request.path
        val siblings = plan.count { it.request.base == planned.request.base && it.request.path.type == path.type }
        val catalog = planned.catalog()
        if (siblings > 1 && catalog != null) catalogRowTitle(catalog) else typeLabel(path.type)
    }

    private fun publish() {
        walk()
        mutableState.value = buildState()
    }

    /** Walks each group's results ([KidsGate.Paging.walk]) and fills it ([KidsGate.Paging.fill]). */
    private fun walk() {
        results.forEachIndexed { index, result ->
            val state = result.state as? Loadable.Ready ?: return@forEachIndexed
            val more = pages[index]
            paging[index].walk(more.after(state.content))
            paging[index].fill(more.asked, busy = more.loading) { more.loadNext(plan[index], state.content, state.sent) }
        }
    }

    private fun buildState(): SearchUiState {
        val groups = mutableListOf<SearchGroup>()
        val shown = mutableListOf<Int>()
        results.forEachIndexed { index, result ->
            val name = result.addon.manifest.name
            val group = when (val state = result.state) {
                Loadable.Loading -> SearchGroup(keys[index], titles[index], name, SearchGroupStatus.Loading, items = emptyList())
                is Loadable.Error ->
                    if (state.error == AddonError.EmptyContent) {
                        null
                    } else {
                        SearchGroup(keys[index], titles[index], name, SearchGroupStatus.Failed(state.error.message), items = emptyList())
                    }
                is Loadable.Ready -> {
                    val more = pages[index]
                    val walk = paging[index].walk
                    // On a kids profile, the results its walk shows.
                    val items = (walk?.shown ?: more.after(state.content)).marked(titleMarks, downloads, watchlists, captions)
                    when {
                        walk == null || items.isNotEmpty() ->
                            SearchGroup(keys[index], titles[index], name, SearchGroupStatus.Ready(more.loading), items = items)
                        // A kids group whose results wait for their verdicts or a page to fill it.
                        more.loading || walk.judging ->
                            SearchGroup(keys[index], titles[index], name, SearchGroupStatus.Loading, items = emptyList())
                        else -> null
                    }
                }
            }
            if (group != null) {
                groups += group
                shown += index
            }
        }
        this.shown = shown
        val searched = searched
        val answering = searched != null && !addonsUnreadable && (!resultsKnown || results.any { it.state == Loadable.Loading })
        val loading = debounceJob != null || answering
        return SearchUiState(
            query = query,
            searching = loading && query.isNotBlank(),
            body = when {
                !online -> SearchBody.Offline(OfflineState.of(offersDownloads))
                query.isBlank() && recent.isNotEmpty() -> SearchBody.Recent(recent.map { RecentSearch(it, T.browse.search.removeRecentNamed(isolate(it))) })
                query.isBlank() -> SearchBody.Hint
                groups.isEmpty() && loading -> SearchBody.Loading
                searched == null || loading || groups.isNotEmpty() -> SearchBody.Results(groups)
                addonsUnreadable -> SearchBody.Empty(T.addons.unreadable)
                plan.isEmpty() -> SearchBody.Empty(T.browse.search.noCatalogs)
                else -> SearchBody.Empty(T.browse.search.noResults(isolate(searched)))
            },
        )
    }

    /** A catalog's answer failed (an empty answer isn't a failure). */
    private fun Loadable<*>.failed(): Boolean = this is Loadable.Error && error != AddonError.EmptyContent

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "search"

        /** How long typing has to pause before the query is searched. */
        const val DEBOUNCE_MILLIS = 400L
    }
}
