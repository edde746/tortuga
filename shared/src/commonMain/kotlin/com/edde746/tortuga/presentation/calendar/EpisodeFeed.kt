package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.ContentKind
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.ResourceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the installed addons answer for the profile's library titles by id, as stremio-core asks: the calendar's
 * episodes ([com.edde746.tortuga.model.Extras.CALENDAR_VIDEOS_IDS], `models/calendar.rs`) or the newest episodes for the
 * new-episode badges ([com.edde746.tortuga.model.Extras.LAST_VIDEOS_IDS], `models/ctx/update_notifications.rs`), for
 * the [LIMIT] most recently changed library items it [follows] ([AggrRequest.CatalogsFiltered]).
 *
 * It asks only while something collects [state] and the app is in the foreground: at once, then every [REFRESH]
 * (stremio-core's interval for the last videos), and again as soon as the request changes (a series added or removed, an
 * addon installed or removed). The answers outlive their collectors for [REFRESH], so a screen opened again asks
 * nothing; an addon whose new answer fails keeps its last one. Nothing runs in the background.
 *
 * @param library the profile's library, most recently changed first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeFeed internal constructor(
    private val extraName: String,
    private val follows: (LibraryItem) -> Boolean,
    library: Flow<List<LibraryItem>>,
    addons: Flow<List<Descriptor>>,
    foreground: Flow<Boolean>,
    private val aggregator: AddonAggregator,
    scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Answers(val plan: List<PlannedRequest>, val at: TimeMark, val metas: Map<ResourceRequest, List<MetaItem>>)

    /** The last complete answers; read and written only by [state]'s one upstream collector. */
    private var last: Answers? = null

    val state: SharedFlow<EpisodeFeedState> = combine(library, addons) { items, installed ->
        AggrRequest.CatalogsFiltered(extraName, items.filter(follows).map { it.id to it.type }, LIMIT).plan(installed)
    }
        .distinctUntilChanged()
        .combine(foreground) { plan, shown -> plan.takeIf { shown } }
        .flatMapLatest { plan -> if (plan == null) emptyFlow() else answers(plan) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    private fun answers(plan: List<PlannedRequest>): Flow<EpisodeFeedState> = flow {
        if (plan.isEmpty()) {
            emit(EpisodeFeedState(emptyList(), asked = false, loading = false))
            return@flow
        }
        while (true) {
            val kept = last?.takeIf { it.plan == plan }
            if (kept != null) {
                emit(EpisodeFeedState(plan.flatMap { kept.metas[it.request].orEmpty() }, asked = true, loading = false))
                val wait = REFRESH - kept.at.elapsedNow()
                if (wait.isPositive()) delay(wait)
            }
            val previous = last?.metas.orEmpty()
            var metas = emptyMap<ResourceRequest, List<MetaItem>>()
            aggregator.load(plan, ContentKind.METAS_DETAILED).collect { states ->
                metas = states.mapNotNull { answer ->
                    val content = (answer.state as? Loadable.Ready)?.content ?: previous[answer.request]
                    content?.let { answer.request to it }
                }.toMap()
                val loading = states.any { it.state == Loadable.Loading }
                emit(EpisodeFeedState(plan.flatMap { metas[it.request].orEmpty() }, asked = true, loading = loading))
            }
            last = Answers(plan, timeSource.markNow(), metas)
        }
    }

    companion object {
        /** stremio-core's `CALENDAR_ITEMS_COUNT` and `NOTIFICATION_ITEMS_COUNT`. */
        const val LIMIT = 100

        /** stremio-core's `REQUEST_LAST_VIDEOS_EVERY`. */
        val REFRESH: Duration = 6.hours
    }
}

/**
 * @param metas every answer's metas, in the installed order of the addons that sent them.
 * @param asked an addon was asked: false when no installed addon takes the extra for the library's titles, or the
 *   library has none it follows.
 * @param loading an addon has still to answer.
 */
data class EpisodeFeedState(val metas: List<MetaItem>, val asked: Boolean, val loading: Boolean)
