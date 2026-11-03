package com.edde746.tortuga.presentation.navigation

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The app's back stacks, one per top-level destination ([stacks]), the shown one ([backStack]; each UI renders it in its
 * own navigation container, with the top entry as a sheet where [layout] says so), and what lives as long as their
 * entries. Stores never see this: their host interfaces call the app, which calls these. Main thread only.
 *
 * - A destination's stack has the destination's route at its bottom and exists from the first time the destination
 *   shows. Picking another destination in the chrome ([pick]) shows its stack as it was left; picking the shown one
 *   pops its stack to its root. A screen opens on the shown stack ([navigate]), and is on one stack at a time: opened
 *   while another destination's stack holds it, it leaves that stack and opens anew. A destination's route is never
 *   pushed: opening it shows its stack ([navigate], [select], [openDiscover], [openSearch], [openLibrary]).
 * - The player and a sheet ([RoutePresentation.SHEET]) are only ever on the shown stack: showing another destination
 *   takes them, and whatever lies over them, off the stack left (the player's route leaving ends the playback). Closing
 *   the player returns to the screen under it, on the stack it opened on.
 * - Back ([back]) pops the shown stack; at the root of a destination other than Home it shows Home's stack.
 * - [retain] keeps an object (a screen's store) for as long as its route is on any stack, across the screen leaving
 *   the view hierarchy while another screen or destination is shown, and releases it once the route has left every
 *   stack. A route on no stack gets nothing. A destination's route stays at the bottom of its stack until [close] (the
 *   session ends: a profile switch, a sign-out), so a destination shown again finds its screens as they were.
 * - Items a screen was opened with ([openStreams], [openDetail]) are kept while a route needs them, so a route stays
 *   plain `type`/`id` values; so are the catalog Discover opens on ([openDiscover]: its transport URL is a secret, kept
 *   out of routes), the query Search opens with ([openSearch]) and the section Library opens on ([openLibrary]).
 * - A sheet is only ever the top entry: whatever opens next replaces it ([navigate]).
 * - The app keeps the stacks on the device as they change ([SavedStacks.of]: the player, sheets and Watch together
 *   left out), and the profile's next session starts on them ([SavedStacks.restore]).
 *
 * @param start the stacks to start on: a profile's restored ones ([SavedStacks.restore]), or one first screen
 *   ([NavigationStacks.of]).
 */
class TortugaNavigator(start: NavigationStacks) {
    /** Starts on [start] alone ([NavigationStacks.of]). */
    constructor(start: Route) : this(NavigationStacks.of(start))

    private val state = MutableStateFlow(start)
    private val shownStack = MutableStateFlow(start.current)

    /** Every destination's stack and the one shown. */
    val stacks: StateFlow<NavigationStacks> = state.asStateFlow()

    /** The shown destination's stack, bottom to top; never empty. */
    val backStack: StateFlow<List<Route>> = shownStack.asStateFlow()

    /** The top screen. */
    val current: Route get() = shownStack.value.last()

    /** [back] would change the screen: the shown stack's top isn't its root, or the shown destination isn't Home. */
    val canGoBack: Boolean get() = shownStack.value.size > 1 || state.value.shown != TopLevelDestination.BOARD

    /** The shown stack as the UIs draw it: the screens and the sheet over them ([BackStackLayout.of] of [backStack]). */
    val layout: BackStackLayout get() = BackStackLayout.of(shownStack.value)

    private val metas = HashMap<String, MetaItem>()
    private val previews = HashMap<Route.Detail, MetaItemPreview>()
    private val notices = HashMap<Route.Streams, String>()
    private val retained = HashMap<Route, MutableMap<String, Retained<*>>>()
    private val removalListeners = mutableListOf<(Route) -> Unit>()

    /**
     * Opens [route] on top of the shown stack, unless it is already the top. A sheet on top ([layout]) closes first:
     * [route] replaces it, or, when [route] is the screen under the sheet, closing the sheet is all (a stream picked in
     * the Streams sheet over the player plays in that player). A destination's route shows its stack as it was left. A
     * screen is on one destination's stack at a time: [route] on another's leaves it, with the screens over it, and
     * opens anew, its kept objects made again (a Detail opened to play plays again).
     */
    fun navigate(route: Route) {
        TopLevelDestination.of(route)?.let { return show(it) }
        val stacks = state.value
        val current = stacks.current
        val base = if (layout.sheet != null) current.dropLast(1) else current
        val next = if (base.last() == route) base else base + route
        if (next == current) return
        val left = stacks.stacks.filter { (destination, stack) -> destination != stacks.shown && route in stack }
            .mapValues { (destination, stack) -> stack.take(stack.indexOf(route)).ifEmpty { listOf(destination.route) } }
        if (left.isNotEmpty()) retained.remove(route)?.values?.forEach { it.release() }
        commit(NavigationStacks(stacks.shown, stacks.stacks + left + (stacks.shown to next)))
    }

    /**
     * Pops the shown stack's top screen; at the root of a destination other than Home, shows Home's stack as it was
     * left. False (and nothing changes) at Home's root.
     */
    fun back(): Boolean {
        val shown = state.value
        when {
            shown.current.size > 1 -> change(shown.current.dropLast(1))
            shown.shown != TopLevelDestination.BOARD -> show(TopLevelDestination.BOARD)
            else -> return false
        }
        return true
    }

    /** Replaces the shown stack's top screen with [route]. */
    fun replace(route: Route) = change(shownStack.value.dropLast(1) + route)

    /** Pops [route] (and anything above it) off the shown stack; false when it isn't on it or is its root. */
    fun pop(route: Route): Boolean {
        val stack = shownStack.value
        val index = stack.lastIndexOf(route)
        if (index <= 0) return false
        change(stack.take(index))
        return true
    }

    /**
     * Opens [route] ([navigate]) from a catalog item: [preview], the item as its catalog sent it, is kept while the
     * route is on a stack ([preview]), so its Detail draws its header from it while the title loads.
     */
    fun openDetail(route: Route.Detail, preview: MetaItemPreview?) {
        if (preview != null) previews[route] = preview
        navigate(route)
    }

    /** Replaces the shown stack's top screen with [route], whose header shows from [preview] ([openDetail]'s). */
    fun replaceDetail(route: Route.Detail, preview: MetaItemPreview?) {
        if (preview != null) previews[route] = preview
        replace(route)
    }

    /** The catalog item [route] was opened from ([openDetail]), if any. */
    fun preview(route: Route.Detail): MetaItemPreview? = previews[route]

    /**
     * Whether [route] is out of view, as the stacks change: a screen lies over it on the shown stack (the player,
     * another screen opened from it; a sheet doesn't hide it), or it is on another destination's stack only. False while
     * [route] is on no stack.
     */
    fun covered(route: Route): Flow<Boolean> = state.map { stacks ->
        val shown = stacks.current
        val index = shown.lastIndexOf(route)
        if (index < 0) route in stacks else shown.subList(index + 1, shown.size).any { it.presentation == RoutePresentation.SCREEN }
    }.distinctUntilChanged()

    /**
     * Shows [destination] at its root: its stack popped to the destination's route, with [screen] over it in the same
     * change (Watch together over Home: [com.edde746.tortuga.app.AppSession.openWatchTogether]). A link or another
     * screen opening a destination does this; the chrome [pick]s.
     */
    fun select(destination: TopLevelDestination, screen: Route? = null) =
        show(destination, listOf(destination.route) + listOfNotNull(screen))

    /**
     * The user picked [destination] in the app's navigation (the tab bar, the sidebar, the rail, the Mac's and iPad's
     * menus): another destination shows its stack as it was left, the shown one pops its stack to its root. Search asks
     * for its field's focus ([searchFocus]) when that leaves its own screen on top.
     */
    fun pick(destination: TopLevelDestination) {
        val shown = state.value
        if (destination == shown.shown) show(destination, listOf(destination.route)) else show(destination)
        if (destination == TopLevelDestination.SEARCH && current == Route.Search) pendingSearchFocus.value = true
    }

    /**
     * Opens the streams of [videoId] of [meta] as a sheet over the current screen (Detail's play and episode list, the
     * player's "choose a stream" for the next episode), replacing a sheet already open. [notice] says on top of the
     * list why it opened instead of a stream playing (auto-play found none, or its pick didn't start). With
     * [download] the list is for downloading ([Route.Streams.download]); with [fromStart] its pick plays from 0
     * ([Route.Streams.fromStart]).
     */
    fun openStreams(meta: MetaItem, videoId: String, notice: String? = null, download: Boolean = false, fromStart: Boolean = false) {
        metas[metaKey(meta.type, meta.id)] = meta
        val route = Route.Streams(meta.type, meta.id, videoId, download, fromStart)
        if (notice != null) notices[route] = notice else notices.remove(route)
        navigate(route)
    }

    /** The item a [Route.Streams] was opened with. */
    fun meta(type: String, id: String): MetaItem? = metas[metaKey(type, id)]

    /** The notice a [Route.Streams] was opened with ([openStreams]), if any. */
    fun streamsNotice(route: Route.Streams): String? = notices[route]

    private val pendingDiscoverLink = MutableStateFlow<DeepLink.Discover?>(null)
    private val pendingSearchQuery = MutableStateFlow<String?>(null)
    private val pendingSearchFocus = MutableStateFlow(false)
    private val pendingLibrarySection = MutableStateFlow<LibrarySection?>(null)

    /**
     * The catalog Discover is to open ([openDiscover]) until its screen takes it ([takeDiscoverLink]). A flow, so a
     * Discover screen already built picks up a link followed later.
     */
    val discoverLink: StateFlow<DeepLink.Discover?> = pendingDiscoverLink.asStateFlow()

    /** The query Search is to search ([openSearch]) until its screen takes it ([takeSearchQuery]). */
    val searchQuery: StateFlow<String?> = pendingSearchQuery.asStateFlow()

    /** Opens Discover at its root on [link]'s catalog: a Board row's "See all", a genre link. */
    fun openDiscover(link: DeepLink.Discover) {
        pendingDiscoverLink.value = link
        select(TopLevelDestination.DISCOVER)
    }

    /** The catalog [openDiscover] asked for, once: the Discover screen opens it. */
    fun takeDiscoverLink(): DeepLink.Discover? = pendingDiscoverLink.value.also { pendingDiscoverLink.value = null }

    /** Opens Search at its root on [query]: a `stremio:///search` link (a cast member of a meta item). */
    fun openSearch(query: String) {
        pendingSearchQuery.value = query
        select(TopLevelDestination.SEARCH)
    }

    /** The query [openSearch] asked for, once: the Search screen searches it. */
    fun takeSearchQuery(): String? = pendingSearchQuery.value.also { pendingSearchQuery.value = null }

    /**
     * Search's field is to take focus, the software keyboard up where there is one, until its screen takes it
     * ([takeSearchFocus]) or leaves the top: the user picked Search ([pick]), also while it is shown. Search shown
     * otherwise (Back from a screen opened from it, the app starting on it, a pick that shows a screen opened from it)
     * leaves its field be. TVs leave it too: their field takes focus as Search opens, and the keyboard waits for Select.
     */
    val searchFocus: StateFlow<Boolean> = pendingSearchFocus.asStateFlow()

    /** Whether [pick] asked for Search's field's focus, once: the Search screen focuses it. */
    fun takeSearchFocus(): Boolean = pendingSearchFocus.value.also { pendingSearchFocus.value = false }

    /**
     * The section Library is to show ([openLibrary]) until its screen takes it ([takeLibrarySection]). A flow, so a
     * Library screen already shown switches to it.
     */
    val librarySection: StateFlow<LibrarySection?> = pendingLibrarySection.asStateFlow()

    /** Opens Library at its root on [section], as its tab does: Home's offline state opens Downloads. */
    fun openLibrary(section: LibrarySection) {
        pendingLibrarySection.value = section
        select(TopLevelDestination.LIBRARY)
    }

    /** The section [openLibrary] asked for, once: the Library screen shows it. */
    fun takeLibrarySection(): LibrarySection? = pendingLibrarySection.value.also { pendingLibrarySection.value = null }

    /**
     * The object kept for [route] under [key]: created by [create] on first use, released by [release] once the route
     * has left every stack, which for a destination's route is at [close]: a destination shown again finds its store as
     * it was (Discover's catalog and its pages, Search's query and results, Library's filters and sort, a Detail pushed
     * on it). Null while [route] is on no stack: a popped route's screen still draws while it animates out, and the
     * navigation container can even compose it for the first time then; an object made for it would outlive the route,
     * over what the navigator no longer keeps for it (the item [openStreams] opened it with). A screen calls it once per
     * appearance of its route (Compose: `remember(route) { … }`), so the one it got keeps drawing while it animates out,
     * and draws nothing when it got none.
     */
    fun <S : Any> retain(route: Route, key: String, create: () -> S, release: (S) -> Unit): S? {
        if (route !in state.value) return null
        val forRoute = retained.getOrPut(route) { HashMap() }
        @Suppress("UNCHECKED_CAST")
        (forRoute[key] as Retained<S>?)?.let { return it.value }
        return create().also { forRoute[key] = Retained(it, release) }
    }

    /** What [retain] keeps for [route] under [key], if anything (diagnostics and test reports). */
    fun retained(route: Route, key: String): Any? = retained[route]?.get(key)?.value

    /** Calls [listener] with every route that leaves the last stack it was on. */
    fun addRemovalListener(listener: (Route) -> Unit) {
        removalListeners += listener
    }

    /** Stops calling [listener] ([addRemovalListener]). */
    fun removeRemovalListener(listener: (Route) -> Unit) {
        removalListeners -= listener
    }

    /** Releases everything retained; the navigator is done. */
    fun close() {
        val all = retained.values.flatMap { it.values }
        retained.clear()
        all.forEach { it.release() }
        metas.clear()
        previews.clear()
        notices.clear()
        pendingDiscoverLink.value = null
        pendingSearchQuery.value = null
        pendingSearchFocus.value = false
        pendingLibrarySection.value = null
    }

    /** Replaces the shown stack with [next]. */
    private fun change(next: List<Route>) {
        val stacks = state.value
        commit(NavigationStacks(stacks.shown, stacks.stacks + (stacks.shown to next)))
    }

    /**
     * Shows [destination]'s stack: [stack] when given, else as it was left (its route alone the first time). The stack
     * left keeps what lies under its player or sheet.
     */
    private fun show(destination: TopLevelDestination, stack: List<Route>? = null) {
        val before = state.value
        val stacks = before.stacks.toMutableMap()
        if (destination != before.shown) {
            val left = before.current
            val transient = left.indexOfFirst { it == Route.Player || it.presentation == RoutePresentation.SHEET }
            if (transient > 0) stacks[before.shown] = left.take(transient)
        }
        stacks[destination] = stack ?: stacks[destination] ?: listOf(destination.route)
        commit(NavigationStacks(destination, stacks))
    }

    /** Sets the stacks to [next], then releases and reports what left them all. */
    private fun commit(next: NavigationStacks) {
        val before = state.value
        if (next == before) return
        state.value = next
        shownStack.value = next.current
        val live = next.routes
        val gone = before.routes - live
        for (route in retained.keys.filter { it !in live }) retained.remove(route)?.values?.forEach { it.release() }
        if (next.current.last() != Route.Search) pendingSearchFocus.value = false
        metas.keys.retainAll(live.mapNotNull { (it as? Route.Streams)?.let { s -> metaKey(s.type, s.id) } }.toSet())
        previews.keys.retainAll(live)
        notices.keys.retainAll(live)
        for (route in gone) removalListeners.toList().forEach { it(route) }
    }

    private class Retained<S : Any>(val value: S, private val onRelease: (S) -> Unit) {
        fun release() = onRelease(value)
    }

    private companion object {
        fun metaKey(type: String, id: String) = "$type/$id"
    }
}

/**
 * The back stack of each top-level destination shown so far, bottom to top, and the [shown] one
 * ([TortugaNavigator.stacks]). A destination's stack has its route at the bottom; a development session started on
 * another screen has that screen at the bottom of Home's until Home is picked while it shows.
 */
data class NavigationStacks(val shown: TopLevelDestination, val stacks: Map<TopLevelDestination, List<Route>>) {
    /** [destination]'s stack; null until the destination first shows. */
    fun stack(destination: TopLevelDestination): List<Route>? = stacks[destination]

    /** The shown destination's stack. */
    val current: List<Route> get() = stacks.getValue(shown)

    /**
     * The stack [route] is on, for its screen: the shown one when it holds [route], else another destination's (whose
     * screens stay drawn while another destination shows); null when none does.
     */
    fun stackOf(route: Route): List<Route>? = current.takeIf { route in it } ?: stacks.values.firstOrNull { route in it }

    /** Every route on a stack. */
    internal val routes: Set<Route> get() = stacks.values.flatMapTo(HashSet()) { it }

    /** [route] is on a stack. */
    operator fun contains(route: Route): Boolean = stacks.values.any { route in it }

    companion object {
        /**
         * [start] alone, shown: a destination's route as that destination's stack; any other (a development session's
         * first screen) as the bottom of Home's until Home is picked while it shows, which pops it to the Board.
         */
        fun of(start: Route): NavigationStacks =
            (TopLevelDestination.of(start) ?: TopLevelDestination.BOARD).let { NavigationStacks(it, mapOf(it to listOf(start))) }
    }
}
