package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.addons.message
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Where a row of titles comes from. */
sealed interface RowFeed {
    /** An addon's catalog, asked as [planned] (its first page; the next ones by [RowPages]). */
    data class Addon(val planned: PlannedRequest) : RowFeed

    /** A tracker's list: [catalog]'s pages from [source] (a connected account's, or Trakt's public lists). */
    data class Tracker(val source: TrackerCatalogSource, val catalog: TrackerCatalog) : RowFeed
}

/**
 * One [feed]'s titles as Home's rows show them, kept by Home's rows and a collection's folders: its first page
 * ([load]), the pages after it ([more]), without the titles the screen leaves out ([leftOut]: the hidden watched
 * titles) and, on a kids profile ([kids]), as its gate's paging lets them through ([paging]). Without a [feed] (a
 * tracker whose lists couldn't be read, a collection's row) it has no titles and loads nothing. [onChange] follows
 * each answer. Call everything on the main thread.
 */
internal open class FeedRow(
    var feed: RowFeed?,
    private val scope: CoroutineScope,
    private val aggregator: AddonAggregator,
    private val kids: KidsGate?,
    private val leftOut: () -> Set<String>,
    private val onChange: () -> Unit,
) {
    /** [RowStatus.Idle] until requested; [RowStatus.Ready]'s `loadingMore` is [more]'s, read when published. */
    var status: RowStatus = RowStatus.Idle

    /** The first page's items. */
    var items: List<MetaCard> = emptyList()

    /** Items the addon sent on the first page, malformed ones included; a tracker list's next cursor. */
    private var firstSize = 0
    private var next: String? = null

    /** The pages after the first. */
    val more = RowPages(scope, aggregator, onChange)
    private var job: Job? = null

    /** Its last answer was empty (also while it loads again). */
    var empty = false

    /** How it pages on a kids profile, and what its last walk over its cards found ([KidsGate.Paging]). */
    val paging = KidsGate.Paging<MetaCard>(kids)

    /** Requests its first page again, dropping what it loaded: nothing without a [feed]. */
    fun load() {
        cancel()
        val feed = feed ?: return
        status = RowStatus.Loading
        job = when (feed) {
            is RowFeed.Addon -> scope.launch {
                aggregator.load(listOf(feed.planned), ContentKind.METAS) { _, items -> items.toCards() }.collect { states ->
                    val state = states.single().state
                    if (state == Loadable.Loading) return@collect
                    status = if (state is Loadable.Error) RowStatus.Failed(state.error.message) else RowStatus.Ready(loadingMore = false)
                    empty = (state as? Loadable.Error)?.error == AddonError.EmptyContent
                    items = if (state is Loadable.Ready) state.content else emptyList()
                    firstSize = (state as? Loadable.Ready)?.sent ?: 0
                    more.clear()
                    onChange()
                }
            }
            is RowFeed.Tracker -> scope.launch {
                try {
                    val page = feed.source.page(feed.catalog)
                    val cards = page.items.toCards()
                    status = RowStatus.Ready(loadingMore = false)
                    empty = cards.isEmpty()
                    items = cards
                    next = page.next
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "${feed.source.service.id} list ${feed.catalog.kind} failed" }
                    status = RowStatus.Failed(trackerErrorText(feed.source.service, e))
                    items = emptyList()
                    next = null
                }
                more.clear()
                onChange()
            }
        }
    }

    /** Its pages' cards, in order, without the titles left out ([leftOut]). */
    fun cards(): List<MetaCard> {
        val leftOut = leftOut()
        return more.after(items).let { all -> if (leftOut.isEmpty()) all else all.filterNot { it.id in leftOut } }
    }

    /** The cards it shows: on a kids profile those its last walk let through ([emptyAfterFill]), else [cards]. */
    fun shown(): List<MetaCard> = paging.walk?.shown ?: cards()

    /**
     * Its status as shown with [shown] cards: a kids row whose cards wait for their verdicts or a page to fill it, or a
     * row whose cards are all left out while its next page loads, is loading; a ready row's `loadingMore` is [more]'s.
     */
    fun shownStatus(shown: List<MetaCard>): RowStatus = when (val status = status) {
        is RowStatus.Ready -> if ((kids != null || leftOut().isNotEmpty()) && shown.isEmpty()) RowStatus.Loading else RowStatus.Ready(more.loading)
        else -> status
    }

    /**
     * Loads its next page when more than half of its first page is [leftOut] and no page after the first was asked
     * for since the row loaded: at most one page per load of the row. True when it asked.
     */
    fun fillLeftOut(): Boolean {
        val leftOut = leftOut()
        if (leftOut.isEmpty() || status !is RowStatus.Ready || more.asked > 0 || more.loading) return false
        if (items.count { it.id in leftOut } * 2 <= items.size) return false
        return loadNext()
    }

    /** Ready with no page on its way and every card [leftOut]: the screen leaves it out. */
    fun emptyOfLeftOut(): Boolean =
        feed != null && leftOut().isNotEmpty() && status is RowStatus.Ready && !more.loading && cards().isEmpty()

    /** Loads its feed's page after those loaded, when it has one and none is loading; true when it asked. */
    fun loadNext(): Boolean = when (val feed = feed) {
        is RowFeed.Addon -> more.loadNext(feed.planned, items, firstSize)
        is RowFeed.Tracker -> more.loadNext(feed.source, feed.catalog, next)
        null -> false
    }

    /**
     * Walks its cards ([KidsGate.Paging.walk]) and, while it is ready with no page on its way, fills
     * ([KidsGate.Paging.fill]). True when it shows none and none can come on a kids profile: the screen leaves it out.
     * Without a gate, false.
     */
    fun emptyAfterFill(): Boolean {
        val gate = kids ?: return false
        if (feed == null) return false
        val walk = paging.walk(cards()) ?: return false
        if (paging.fill(more.asked, busy = status !is RowStatus.Ready || more.loading, ::loadNext)) return false
        return gate.active.value && status is RowStatus.Ready && !walk.judging && !more.loading && walk.shown.isEmpty()
    }

    /** Its feed has a page after those loaded. */
    fun hasNextPage(): Boolean = when (val feed = feed) {
        is RowFeed.Addon -> more.hasNext(feed.planned, firstSize)
        is RowFeed.Tracker -> more.hasNext(next)
        null -> false
    }

    /** Stops its requests. */
    fun cancel() {
        job?.cancel()
        more.cancel()
    }

    private companion object {
        const val TAG = "rows"
    }
}
