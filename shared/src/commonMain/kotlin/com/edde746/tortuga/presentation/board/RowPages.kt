package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.CatalogPaging
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cards from a Home or Search row's end at which the screen asks for the row's next page ([RowPages]): about a TV
 * screen's width ahead.
 */
const val SHELF_LOAD_AHEAD: Int = 8

/**
 * The pages after the first of a Home or Search row (stremio-core `CatalogsWithExtra`'s `LoadNextPage`), one at a
 * time: an addon catalog's by `skip` ([CatalogPaging]: the items the addon sent so far, while every page had some), a
 * tracker list's by the service's cursor. A page that fails is dropped, so asking again (the row scrolled to its end
 * once more) loads it again; an empty addon page, one that adds no title the row doesn't have (Cinemeta's search
 * answers every skip with its first page), or a tracker page without a next cursor, ends the row. [onChange] follows
 * each answer. Call everything on the main thread.
 */
internal class RowPages(
    private val scope: CoroutineScope,
    private val aggregator: AddonAggregator,
    private val onChange: () -> Unit,
) {
    /** The items of the pages loaded, in order. */
    private var cards: List<MetaCard> = emptyList()

    /** The items each addon page sent. */
    private val sizes = mutableListOf<Int>()

    /** A tracker list's cursor for the page after those loaded; [turned] once a page is loaded. */
    private var cursor: String? = null
    private var turned = false
    private var job: Job? = null

    /** A page is on its way. */
    var loading = false
        private set

    /** The pages asked for since the first, those that failed included. */
    var asked = 0
        private set

    /** [first] (the row's first page) followed by the items of the pages loaded, each id once. */
    fun after(first: List<MetaCard>): List<MetaCard> = if (cards.isEmpty()) first else (first + cards).distinctBy { it.id }

    /** [planned]'s catalog, whose first page had [firstSize] items as the addon sent them, has a page after those loaded. */
    fun hasNext(planned: PlannedRequest, firstSize: Int): Boolean {
        val catalog = planned.catalog() ?: return false
        return CatalogPaging.nextPage(catalog, planned.request, listOf(firstSize) + sizes) != null
    }

    /** A tracker's list, whose first page ended at [firstNext] (null: it was the last), has a page after those loaded. */
    fun hasNext(firstNext: String?): Boolean = (if (turned) cursor else firstNext) != null

    /**
     * Loads the page after those loaded of [planned]'s catalog, whose first page was [first] and had [firstSize] items
     * as the addon sent them; nothing while a page loads or when there is no next page. True when it asked.
     */
    fun loadNext(planned: PlannedRequest, first: List<MetaCard>, firstSize: Int): Boolean {
        if (loading) return false
        val catalog = planned.catalog() ?: return false
        val request = CatalogPaging.nextPage(catalog, planned.request, listOf(firstSize) + sizes) ?: return false
        loading = true
        asked++
        job = scope.launch {
            aggregator.load(request, planned.addon.manifest.name, ContentKind.METAS) { it.toCards() }.collect { state ->
                when (state) {
                    Loadable.Loading -> return@collect
                    is Loadable.Ready -> {
                        val known = (first + cards).mapTo(HashSet()) { it.id }
                        val fresh = state.content.filter { it.id !in known }
                        // A page of titles the row has already is its end, as an empty one is.
                        sizes += if (fresh.isEmpty()) 0 else state.sent
                        cards = cards + fresh
                    }
                    // The end; any other failure leaves the page out (the aggregator logs it).
                    is Loadable.Error -> if (state.error == AddonError.EmptyContent) sizes += 0
                }
                loading = false
                onChange()
            }
        }
        return true
    }

    /**
     * Loads the page after those loaded of a tracker's [catalog], whose first page ended at [firstNext] (null: it was
     * the last); nothing while a page loads or after the last. True when it asked.
     */
    fun loadNext(source: TrackerCatalogSource, catalog: TrackerCatalog, firstNext: String?): Boolean {
        if (loading) return false
        val at = (if (turned) cursor else firstNext) ?: return false
        loading = true
        asked++
        job = scope.launch {
            try {
                val page = source.page(catalog, at)
                cards = cards + page.items.toCards()
                cursor = page.next
                turned = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "${source.service.id} list ${catalog.kind} page failed" }
            }
            loading = false
            onChange()
        }
        return true
    }

    /** Stops the page on its way, if any. */
    fun cancel() {
        job?.cancel()
        job = null
        loading = false
    }

    /** Forgets the pages loaded: the row's first page loaded again. */
    fun clear() {
        cancel()
        cards = emptyList()
        sizes.clear()
        cursor = null
        asked = 0
        turned = false
    }

    private companion object {
        const val TAG = "rows"
    }
}
