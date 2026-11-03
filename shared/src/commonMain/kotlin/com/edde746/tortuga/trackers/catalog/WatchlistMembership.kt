package com.edde746.tortuga.trackers.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What one sign-in's watchlist ([TrackerWatchlist]: Trakt's and MDBList's watchlist, Simkl's plan-to-watch,
 * MyAnimeList's plan-to-watch, AniList's planning) is known to hold, without asking the service: the pages the
 * Board and Discover read of it ([ListedCatalogSource]) and the user's own writes. A title on a page read is on the
 * list; one missing from every page of every one of the service's watchlist rows, each read to its end, is not; any
 * other title is unknown. A write the app made is newer than every page read before it; one still on its way or
 * waiting for a retry ([wrote] `pending`) wins over every page, which can't show it yet. Thread-safe.
 */
internal class WatchlistMembership {
    /** A row's pages read since its first: the titles on them, and whether the last one was the row's end. */
    private data class Read(val keys: Set<String>, val complete: Boolean, val seq: Long)

    /** The user's write of [listed] for the title [keys] names. */
    private data class Fact(val keys: Set<String>, val listed: Boolean, val pending: Boolean, val seq: Long)

    private data class Known(
        val rows: Set<String>? = null,
        val reads: Map<String, Read> = emptyMap(),
        val facts: List<Fact> = emptyList(),
        val seq: Long = 0,
    )

    private val known = MutableStateFlow(Known())

    /** Changes whenever what is known does: follow it and ask [listed] again. */
    val changes: StateFlow<Any> get() = known

    /** The service's rows were listed: [catalogs]' watchlist rows are all a complete answer has to cover. */
    fun listed(catalogs: List<TrackerCatalog>) {
        known.update { it.copy(rows = catalogs.filter { c -> c.kind in WATCHLIST_KINDS }.mapTo(HashSet()) { c -> c.id }) }
    }

    /** A page of [catalog] was read: its first ([first]) starts the row's titles over, a later one adds to them. */
    fun read(catalog: TrackerCatalog, first: Boolean, page: TrackerCatalogPage) {
        if (catalog.kind !in WATCHLIST_KINDS) return
        val keys = page.items.mapTo(HashSet()) { titleKey(it.type, it.id) }
        known.update { k ->
            val before = k.reads[catalog.id]?.takeIf { !first }?.keys.orEmpty()
            val seq = k.seq + 1
            k.copy(reads = k.reads + (catalog.id to Read(before + keys, complete = page.next == null, seq = seq)), seq = seq)
        }
    }

    /** The user put the title [keys] names on the list ([listed]) or took it off; [pending] while it isn't through. */
    fun wrote(keys: Set<String>, listed: Boolean, pending: Boolean) {
        known.update { k ->
            val seq = k.seq + 1
            k.copy(facts = k.facts.filter { it.keys.none(keys::contains) } + Fact(keys, listed, pending, seq), seq = seq)
        }
    }

    /** The user's write for the title [keys] names didn't go through: what the pages say counts again. */
    fun forget(keys: Set<String>) {
        known.update { k -> k.copy(facts = k.facts.filter { it.keys.none(keys::contains) }, seq = k.seq + 1) }
    }

    /** Whether the title [keys] names is on the list; null while that isn't known. */
    fun listed(keys: Set<String>): Boolean? {
        val k = known.value
        val fact = k.facts.lastOrNull { it.keys.any(keys::contains) }
        if (fact?.pending == true) return fact.listed
        val on = k.reads.values.filter { read -> read.keys.any(keys::contains) }.maxByOrNull { it.seq }
        val rows = k.rows
        val page: Pair<Boolean, Long>? = when {
            on != null -> true to on.seq
            rows != null && rows.isNotEmpty() && rows.all { k.reads[it]?.complete == true } -> false to rows.minOf { k.reads.getValue(it).seq }
            else -> null
        }
        return when {
            fact != null && (page == null || fact.seq > page.second) -> fact.listed
            else -> page?.first
        }
    }

    internal companion object {
        /** The kinds of row that are a [TrackerWatchlist]'s list. */
        val WATCHLIST_KINDS = setOf(TrackerCatalog.Kind.WATCHLIST, TrackerCatalog.Kind.PLAN_TO_WATCH, TrackerCatalog.Kind.PLANNING)

        /**
         * The key a title of [type] goes by on the lists, from its Stremio id as the lists' items name it
         * ([CatalogItems.stremioId], [CatalogItems.animeId]): a TMDB id with its type (a movie's and a show's numbers
         * overlap), any other id as it is.
         */
        fun titleKey(type: String, id: String): String =
            if (id.startsWith(TMDB)) "$TMDB${if (type == CatalogItems.MOVIE) CatalogItems.MOVIE else CatalogItems.SERIES}:${id.removePrefix(TMDB)}" else id

        /** Every key the resolved title [target] goes by on the lists. */
        fun titleKeys(target: WatchlistTarget): Set<String> = buildSet {
            target.ids.imdb?.let(::add)
            target.ids.tmdb?.let { add(titleKey(if (target.isMovie) CatalogItems.MOVIE else CatalogItems.SERIES, "$TMDB$it")) }
            target.anime?.let { anime ->
                listOfNotNull(
                    CatalogItems.animeId(kitsu = anime.kitsu?.toLong()),
                    CatalogItems.animeId(mal = anime.mal?.toLong()),
                    CatalogItems.animeId(anilist = anime.anilist?.toLong()),
                    CatalogItems.animeId(anidb = anime.anidb?.toLong()),
                ).forEach(::add)
            }
        }

        private const val TMDB = "tmdb:"
    }
}

/**
 * [source], each of its watchlist rows' pages also noted in [membership]: the Board and Discover read the lists
 * through it, so what they show tells which titles are on them.
 */
internal class ListedCatalogSource(
    private val source: TrackerCatalogSource,
    private val membership: WatchlistMembership,
) : TrackerCatalogSource {
    override val service get() = source.service

    override suspend fun catalogs(): List<TrackerCatalog> = source.catalogs().also(membership::listed)

    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage =
        source.page(catalog, cursor).also { membership.read(catalog, first = cursor == null, page = it) }

    override suspend fun publicList(input: String): TrackerCatalog? = source.publicList(input)
}
