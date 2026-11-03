package com.edde746.tortuga.presentation.library

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.posterShapeOf
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.presentation.board.resumes
import com.edde746.tortuga.presentation.board.shownProgress
import com.edde746.tortuga.presentation.board.typeLabel
import com.edde746.tortuga.presentation.board.typeOrder
import com.edde746.tortuga.presentation.board.videoWatched
import com.edde746.tortuga.presentation.menu.DownloadMarks
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.presentation.menu.choosesStream
import com.edde746.tortuga.presentation.menu.mediaMenu
import com.edde746.tortuga.presentation.menu.menuDownload
import com.edde746.tortuga.strings.T
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
 * The Library and Continue Watching screens' store (stremio-core `LibraryWithFilters` with
 * `NotRemovedFilter` or `ContinueWatchingFilter`), following its [items] as they change (here or by sync).
 *
 * - The type filter offers All plus the types present, in Stremio's type order ([typeOrder]: movie, series, channel,
 *   tv, then others by name, `other` last); a selected type stays selected when its last item goes, as in stremio-core,
 *   and keeps its chip while the list says there is nothing of it.
 * - Sorts are stremio-core's plus "Recently added"; changing either starts over at the first page.
 * - Pages hold [PAGE_SIZE] items (stremio-core `CATALOG_PAGE_SIZE`).
 * - [remove] takes an item out of the library, or out of Continue Watching (its position is cleared and its last
 *   watched time moves to now, as Stremio's dismiss does); [markWatched] is Stremio's `LibraryItemMarkAsWatched`, in
 *   Continue Watching the mark of the video to resume. Both are entries of the item's menu ([LibraryRow.menu],
 *   [menuAction]), whose download entries follow this device's downloads ([MediaActions.downloadMarks]): a movie's, and
 *   in Continue Watching the video to resume's.
 *   [actions] carries them out, and says so over the app's screens when one can't be.
 * - On a kids profile ([kids]) an item shows once its verdict is known and it passes.
 * - A series' row counts its new episodes ([newEpisodes], [LibraryRow.newEpisodes]).
 * - The Library's rows show their names under the artwork as [posterCaptions] says ([LibraryRow.caption]); Continue
 *   Watching's always do.
 * - The screen has the Calendar button ([openCalendar]).
 *
 * Call everything on the main thread; [release] ends it.
 */
class LibraryStore internal constructor(
    private val mode: LibraryMode,
    /** The mode's items, most recently changed first: the library (added, not removed), or Continue Watching. */
    private val items: Flow<List<LibraryItem>>,
    private val host: LibraryHost,
    /** The app's scope, whose child [scope] is the screen's. */
    private val parentScope: CoroutineScope,
    /** The item menus' actions. */
    private val actions: MediaActions,
    /** The count of new episodes of each library series that has any, by id. */
    private val newEpisodes: Flow<Map<String, Int>>,
    /** The profile's kids rules; without them every item stays as it is. */
    private val kids: KidsGate? = null,
    /** The Library's rows show their names under the artwork ([LibraryRow.caption]). */
    private val posterCaptions: Flow<Boolean> = flowOf(true),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** The mode's items, most recently changed first. */
    private var all: List<LibraryItem> = emptyList()
    private var known = false
    private var type: String? = null
    private var sort = LibrarySort.LAST_WATCHED
    private var pages = 1
    private var downloads: DownloadMarks? = null
    private var watchlists: WatchlistMarks? = null
    private var newEpisodeCounts: Map<String, Int> = emptyMap()
    private var captions = true

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            items.collect {
                all = it
                known = true
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
        kids?.let { gate -> scope.launch { gate.changes.collect { publish() } } }
        scope.launch {
            newEpisodes.collect {
                newEpisodeCounts = it
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

    /** Shows only items of [type]; null shows every type. */
    fun selectType(type: String?) {
        this.type = type
        pages = 1
        publish()
    }

    fun selectSort(sort: LibrarySort) {
        this.sort = sort
        pages = 1
        publish()
    }

    /** Adds the next page to [LibraryBody.Grid.items] when [LibraryBody.Grid.hasMore]. */
    fun loadNextPage() {
        val more = when (val body = mutableState.value.body) {
            LibraryBody.Loading, is LibraryBody.Empty -> false
            is LibraryBody.Grid -> body.hasMore
        }
        if (!more) return
        pages++
        publish()
    }

    /**
     * Opens the item: in Continue Watching it resumes its video ([LibraryHost.openVideo]); otherwise, or when the
     * library doesn't know the video, its Detail screen opens, which offers to resume it.
     */
    fun open(id: String) {
        val item = all.firstOrNull { it.id == id } ?: return
        val videoId = item.state.videoId?.takeIf { mode == LibraryMode.CONTINUE_WATCHING }
        if (videoId != null) host.openVideo(item.type, item.id, videoId) else host.openMeta(item.type, item.id, preview = null)
    }

    /** Opens the Calendar over the screen ([LibraryHost.openCalendar]): its Calendar button. */
    fun openCalendar() = host.openCalendar()

    /**
     * Carries out an entry of the item's menu ([LibraryRow.menu]): in Continue Watching, marks and the download are its
     * video's; Play from the beginning is the video in progress's.
     */
    fun menuAction(id: String, action: MediaAction) {
        val item = all.firstOrNull { it.id == id } ?: return
        val continueWatching = mode == LibraryMode.CONTINUE_WATCHING
        val videoId = if (continueWatching || action == MediaAction.PLAY_FROM_START) item.state.videoId else null
        actions.perform(MetaSnapshot(item.id, item.type, item.name, item.poster, item.posterShape), videoId, action, host)
    }

    fun release() {
        scope.cancel()
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): LibraryUiState {
        val visible = if (kids == null) all else all.filter { kids.shows(it.type, it.id, preview = null) }
        // A kids profile's items waiting for their verdicts.
        val judging = kids != null && all.any { kids.verdictNow(it.type, it.id, preview = null) == null }
        val filtered = visible.filter { type == null || it.type == type }
        val sorted = filtered.sortedWith(comparator(sort))
        val shown = sorted.take(pages * PAGE_SIZE)
        return LibraryUiState(
            title = if (mode == LibraryMode.LIBRARY) T.library.title else T.library.continueWatching,
            types = listOf(LibraryTypeOption(null, T.browse.type.all, type == null)) +
                (visible.map { it.type } + listOfNotNull(type)).distinct().sortedWith(typeOrder).map { LibraryTypeOption(it, typeLabel(it), it == type) },
            sorts = LibrarySort.entries.map { LibrarySortOption(it, sortLabel(it), it == sort) },
            body = when {
                !known -> LibraryBody.Loading
                filtered.isNotEmpty() -> LibraryBody.Grid(shown.map(::row), hasMore = sorted.size > shown.size)
                judging -> LibraryBody.Loading
                visible.isNotEmpty() -> LibraryBody.Empty(T.library.emptyType)
                mode == LibraryMode.LIBRARY -> LibraryBody.Empty(T.library.empty)
                else -> LibraryBody.Empty(T.library.emptyContinueWatching)
            },
            continueWatching = mode == LibraryMode.CONTINUE_WATCHING,
        )
    }

    private fun row(item: LibraryItem): LibraryRow {
        val progress = item.shownProgress()
        val continueWatching = mode == LibraryMode.CONTINUE_WATCHING
        return LibraryRow(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            posterShape = posterShapeOf(item.posterShape),
            progress = progress,
            watched = item.isWatched,
            menu = mediaMenu(
                inLibrary = !item.removed,
                watched = if (continueWatching) item.videoWatched() else item.isWatched,
                inProgress = item.resumes(),
                continueWatching = continueWatching,
                chooseStream = choosesStream(item.type),
                download = downloads.menuDownload(item.type, item.id, if (continueWatching) item.state.videoId else null),
                watchlist = watchlists?.menu(item.type, item.id),
            ),
            newEpisodes = newEpisodeCounts[item.id] ?: 0,
            caption = continueWatching || captions,
        )
    }

    companion object {
        /** The Library's key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "library"

        /** Continue Watching's key on the navigator, under the Library's route (test reports read it there). */
        const val CONTINUE_WATCHING_KEY: String = "continueWatching"

        /** stremio-core `CATALOG_PAGE_SIZE`. */
        const val PAGE_SIZE: Int = 100

        /** Items left below the view when the screen asks for the next page ([loadNextPage]). */
        const val LOAD_AHEAD: Int = 20

        private fun sortLabel(sort: LibrarySort): String = when (sort) {
            LibrarySort.LAST_WATCHED -> T.library.sort.lastWatched
            LibrarySort.ADDED -> T.library.sort.added
            LibrarySort.NAME -> T.library.sort.name
            LibrarySort.NAME_REVERSE -> T.library.sort.nameReverse
            LibrarySort.TIMES_WATCHED -> T.library.sort.timesWatched
            LibrarySort.WATCHED -> T.library.sort.watched
            LibrarySort.NOT_WATCHED -> T.library.sort.notWatched
        }

        /** stremio-core `Sort::sort_items` (a stable sort over the most recently changed first); missing times count as oldest. */
        private fun comparator(sort: LibrarySort): Comparator<LibraryItem> = when (sort) {
            LibrarySort.LAST_WATCHED -> compareByDescending<LibraryItem, Long?>(nullsFirst()) { it.state.lastWatched }
            LibrarySort.ADDED -> compareByDescending<LibraryItem, Long?>(nullsFirst()) { it.ctime }
            LibrarySort.NAME -> compareBy { it.name.lowercase() }
            LibrarySort.NAME_REVERSE -> compareByDescending { it.name.lowercase() }
            LibrarySort.TIMES_WATCHED -> compareByDescending { it.state.timesWatched }
            LibrarySort.WATCHED -> compareByDescending<LibraryItem> { it.isWatched }
                .thenByDescending<LibraryItem, Long?>(nullsFirst()) { it.state.lastWatched }
                .thenByDescending<LibraryItem, Long?>(nullsFirst()) { it.ctime }
            LibrarySort.NOT_WATCHED -> compareBy<LibraryItem> { it.isWatched }
                .thenBy<LibraryItem, Long?>(nullsFirst()) { it.state.lastWatched }
                .thenBy<LibraryItem, Long?>(nullsFirst()) { it.ctime }
        }
    }
}
