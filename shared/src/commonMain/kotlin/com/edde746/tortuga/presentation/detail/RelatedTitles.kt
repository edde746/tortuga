package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.ids.MetaIds
import com.edde746.tortuga.trackers.trakt.TraktRelated
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last

/**
 * Detail's "More like this": titles like the one shown, from two sources asked at once.
 *
 * - The profile's addons' `similar` catalogs (an addon protocol extension: a catalog of the title's type whose one
 *   required extra is `similar`, asked `catalog/{type}/{id}/similar={title id}.json`), as that protocol reads them: the
 *   first catalog in the installed order that answers with titles, best first. Any title can have them.
 * - Trakt's related titles ([TraktRelated]), for a movie or a series with an IMDb id: its own id, else the meta's
 *   `imdb_id` or IMDb page. A title without one has the addons alone.
 *
 * The row waits for both, so nothing moves once it shows: the addon's titles first (the catalog the user installed),
 * then Trakt's, each title once (by its id or its IMDb id, whichever source named it first), never the title itself,
 * at most [LIMIT]. A source that fails or isn't asked counts as not answering; one that answers with no titles has
 * answered. Caching is each source's: the addon transport's cache, [TraktRelated]'s.
 */
internal class RelatedTitles(private val aggregator: AddonAggregator, private val trakt: TraktRelated?) {
    /** What the sources came to for a title. */
    sealed interface Result {
        /** A source is still being asked. */
        data object Loading : Result

        /** At least one source answered: [titles], merged, maybe none. */
        data class Answered(val titles: List<MetaItemPreview>) : Result

        /** No source applies to the title, or none answered: no row. */
        data object None : Result
    }

    /** [meta]'s related titles from [addons] and Trakt: [Result.Loading] while they are asked, then what they came to. */
    fun related(addons: List<Descriptor>, meta: MetaItem): Flow<Result> = flow {
        val similar = AggrRequest.AllCatalogs(listOf(ExtraValue(Extras.SIMILAR, meta.id)), meta.type).plan(addons)
        val imdb = imdbId(meta)
        val trakt = trakt?.takeIf { imdb != null && TraktRelated.kind(meta.type) != null }
        if (similar.isEmpty() && trakt == null) return@flow emit(Result.None)
        emit(Result.Loading)
        val (fromAddons, fromTrakt) = coroutineScope {
            val fromTrakt = async { trakt?.related(meta.type, imdb!!) }
            val fromAddons = if (similar.isEmpty()) null else firstAnswer(aggregator.load(similar, ContentKind.METAS).last().map { it.state })
            fromAddons to fromTrakt.await()
        }
        if (fromAddons == null && fromTrakt == null) return@flow emit(Result.None)
        emit(Result.Answered(merge(setOfNotNull(meta.id, imdb), fromAddons.orEmpty(), fromTrakt.orEmpty())))
    }

    internal companion object {
        const val LIMIT = 20

        /** The title's IMDb id: its own id when that is one, else its meta's `imdb_id` or IMDb page. */
        fun imdbId(meta: MetaItem): String? = meta.id.takeIf { ImdbId.parse(it) != null } ?: MetaIds.of(meta, meta.id).imdb

        /**
         * The first catalog's titles that has some, in [states]' (the installed) order; an empty list when the catalogs
         * answered with none; null when none answered (each failed).
         */
        fun firstAnswer(states: List<Loadable<List<MetaItemPreview>>>): List<MetaItemPreview>? {
            states.firstNotNullOfOrNull { (it as? Loadable.Ready)?.content?.takeIf(List<MetaItemPreview>::isNotEmpty) }?.let { return it }
            return if (states.any { it is Loadable.Ready || (it as? Loadable.Error)?.error == AddonError.EmptyContent }) emptyList() else null
        }

        /**
         * [first]'s titles, then [then]'s, each once (a title whose id or IMDb id is already in, or in [self], is
         * left out), at most [limit].
         */
        fun merge(self: Set<String>, first: List<MetaItemPreview>, then: List<MetaItemPreview>, limit: Int = LIMIT): List<MetaItemPreview> {
            val seen = self.toMutableSet()
            val merged = mutableListOf<MetaItemPreview>()
            for (title in first + then) {
                if (merged.size == limit) break
                val ids = listOfNotNull(title.id, title.imdbId)
                if (ids.any { it in seen }) continue
                seen += ids
                merged += title
            }
            return merged
        }
    }
}
