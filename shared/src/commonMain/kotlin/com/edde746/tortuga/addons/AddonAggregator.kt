package com.edde746.tortuga.addons

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.Subtitles
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Queries every applicable addon in parallel: at most [concurrency] requests at once, each limited to
 * [perAddonTimeout] once it starts. Every flow emits the full list of per-addon states in plan (installed) order:
 * first every addon's, [Loadable.Loading] but where the transport's cache holds the answer, then again after each
 * other addon settles, and completes when all have. One addon's failure only affects its own entry. Collecting again
 * queries again (through the transport's cache).
 *
 * A cached answer (decoded already) is prepared in the collector's context and comes in the first emission: a store
 * on the main thread gets it with no thread switch, before its next frame. The other requests start at once on the
 * transport's parse dispatcher, where their answers are read, decoded and prepared; [streams] with a `prepare`
 * (badges, per stream) fetches and prepares cached answers there too. The states are emitted in the collector's
 * context, so a store on the main thread receives finished values.
 */
class AddonAggregator(
    private val transport: AddonTransport,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val perAddonTimeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(concurrency > 0) { "concurrency must be positive" }
    }

    /** Each addon's answer for the title, with the addon that sent it ([MetaItem.addon]). */
    fun meta(addons: List<Descriptor>, type: String, id: String): Flow<List<AddonLoadable<MetaItem>>> =
        load(AggrRequest.meta(type, id).plan(addons), ContentKind.META) { addon, meta -> meta.copy(addon = addon) }

    /**
     * The title's meta from [addons]: the first answer in the installed order, taken once every addon before it has
     * answered (one still loading may have the title); null when none has it. It is the one Detail shows once the
     * answers are in, Detail showing the first ready answer in that order. The addons after it aren't waited for.
     */
    suspend fun firstMeta(addons: List<Descriptor>, type: String, id: String): MetaItem? =
        meta(addons, type, id)
            .firstOrNull { states -> states.takeWhile { it.state !is Loadable.Ready }.none { it.state == Loadable.Loading } }
            ?.firstNotNullOfOrNull { (it.state as? Loadable.Ready)?.content }

    fun streams(addons: List<Descriptor>, type: String, id: String): Flow<List<AddonLoadable<List<Stream>>>> =
        load(AggrRequest.streams(type, id).plan(addons), ContentKind.STREAMS)

    /**
     * [streams] with each answer turned into [prepare]'s value (rows, badges) as it arrives, always off the main thread.
     * [fresh] (the user's refresh) asks every addon past the transport's cache ([AddonTransport.resource]).
     */
    fun <R> streams(
        addons: List<Descriptor>,
        type: String,
        id: String,
        fresh: Boolean = false,
        prepare: (Descriptor, List<Stream>) -> R,
    ): Flow<List<AddonLoadable<R>>> =
        load(AggrRequest.streams(type, id).plan(addons), ContentKind.STREAMS, cachedHere = false, prepare, fresh)

    /**
     * The streams of [videoId] of [meta], as [streams] gives them, after the ones [meta] carries for it
     * ([MetaItem.carriedStreams], stremio-core's `meta_streams`): those come first, already answered, as the answer of
     * the addon that sent [meta]. Every stream addon is still asked, that one too when it has streams.
     */
    fun streams(addons: List<Descriptor>, meta: MetaItem, videoId: String): Flow<List<AddonLoadable<List<Stream>>>> {
        val rest = streams(addons, meta.type, videoId)
        val (addon, request, streams) = carried(meta, videoId) ?: return rest
        return combine(flowOf(AddonLoadable(addon, request, Loadable.Ready(streams))), rest) { head, tail -> listOf(head) + tail }
    }

    /**
     * [streams] of [meta]'s video with [prepare]; the carried streams are prepared off the main thread too. [fresh] asks
     * every addon past the cache; the carried streams are [meta]'s own, as they were.
     */
    fun <R> streams(
        addons: List<Descriptor>,
        meta: MetaItem,
        videoId: String,
        fresh: Boolean = false,
        prepare: (Descriptor, List<Stream>) -> R,
    ): Flow<List<AddonLoadable<R>>> {
        val rest = streams(addons, meta.type, videoId, fresh, prepare)
        val (addon, request, streams) = carried(meta, videoId) ?: return rest
        val head = flow<AddonLoadable<R>> {
            emit(AddonLoadable(addon, request, Loadable.Loading))
            emit(AddonLoadable(addon, request, Loadable.Ready(withContext(transport.parseDispatcher) { prepare(addon, streams) })))
        }
        return combine(head, rest) { first, tail -> listOf(first) + tail }
    }

    /** [meta]'s streams for [videoId] with the addon that sent [meta]; null when it carries none or no addon sent it. */
    private fun carried(meta: MetaItem, videoId: String): Carried? {
        val addon = meta.addon ?: return null
        val streams = meta.carriedStreams(videoId).ifEmpty { return null }
        return Carried(addon, ResourceRequest(addon.transport, ResourcePath(Resources.STREAM, meta.type, videoId)), streams)
    }

    private data class Carried(val addon: Descriptor, val request: ResourceRequest, val streams: List<Stream>)

    fun subtitles(
        addons: List<Descriptor>,
        type: String,
        id: String,
        extra: List<ExtraValue> = emptyList(),
    ): Flow<List<AddonLoadable<List<Subtitles>>>> = load(AggrRequest.subtitles(type, id, extra).plan(addons), ContentKind.SUBTITLES)

    fun <T> load(plan: List<PlannedRequest>, kind: ContentKind<T>): Flow<List<AddonLoadable<T>>> = load(plan, kind) { _, content -> content }

    /** [load] with each answer turned into [prepare]'s value (say, cards) with the addon that sent it; a cheap [prepare]. */
    fun <T, R> load(plan: List<PlannedRequest>, kind: ContentKind<T>, prepare: (Descriptor, T) -> R): Flow<List<AddonLoadable<R>>> =
        load(plan, kind, cachedHere = true, prepare)

    /**
     * [request] alone, to an addon that need not be installed (Discover on a link to any catalog: stremio-core loads
     * any request): the states [load] gives one request, each answer turned into [prepare]'s value (a cheap one).
     * [addonName] names the addon in the logs.
     */
    fun <T, R> load(request: ResourceRequest, addonName: String, kind: ContentKind<T>, prepare: (T) -> R): Flow<Loadable<R>> = flow {
        val cached = transport.cached(request)
        if (cached != null) {
            emit(answer(addonName, request, kind, cached, prepare))
            return@flow
        }
        emit(Loadable.Loading)
        emit(withContext(transport.parseDispatcher) { fetch(addonName, request, kind, fresh = false, prepare) })
    }

    /** [cachedHere]: a cached answer is prepared in the collector's context, in the first emission. [fresh]: past the cache. */
    private fun <T, R> load(
        plan: List<PlannedRequest>,
        kind: ContentKind<T>,
        cachedHere: Boolean,
        prepare: (Descriptor, T) -> R,
        fresh: Boolean = false,
    ): Flow<List<AddonLoadable<R>>> = channelFlow {
        val states = plan.map { planned ->
            val cached = if (cachedHere && !fresh) transport.cached(planned.request) else null
            val state: Loadable<R> = if (cached != null) answer(planned, kind, cached, prepare) else Loadable.Loading
            AddonLoadable(planned.addon, planned.request, state)
        }.toMutableList()
        val pending = plan.indices.filter { states[it].state == Loadable.Loading }
        val lock = Mutex()
        send(states.toList())
        val permits = Semaphore(concurrency)
        for (index in pending) {
            launch(transport.parseDispatcher) {
                val planned = plan[index]
                val state = permits.withPermit { fetch(planned.addon.manifest.name, planned.request, kind, fresh) { prepare(planned.addon, it) } }
                update(lock, states, index, state)
            }
        }
    }

    private suspend fun <T> ProducerScope<List<AddonLoadable<T>>>.update(
        lock: Mutex,
        states: MutableList<AddonLoadable<T>>,
        index: Int,
        state: Loadable<T>,
    ) = lock.withLock {
        states[index] = states[index].copy(state = state)
        send(states.toList())
    }

    private suspend fun <T, R> fetch(addonName: String, request: ResourceRequest, kind: ContentKind<T>, fresh: Boolean, prepare: (T) -> R): Loadable<R> {
        val response = try {
            withTimeoutOrNull(perAddonTimeout) { transport.resource(request, fresh) }
                ?: return Loadable.Error(AddonError.Timeout)
        } catch (e: AddonException) {
            Log.w(TAG, e.cause) { "$addonName: ${describe(request)} failed: ${e.error}" }
            return Loadable.Error(e.error)
        }
        return answer(addonName, request, kind, response, prepare)
    }

    private fun <T, R> answer(planned: PlannedRequest, kind: ContentKind<T>, response: AddonResponse, prepare: (Descriptor, T) -> R): Loadable<R> =
        answer(planned.addon.manifest.name, planned.request, kind, response) { prepare(planned.addon, it) }

    private fun <T, R> answer(addonName: String, request: ResourceRequest, kind: ContentKind<T>, response: AddonResponse, prepare: (T) -> R): Loadable<R> {
        if (response.dropped > 0) {
            Log.w(TAG) { "$addonName: ${describe(request)}: ${response.dropped} malformed element(s) dropped, the first: ${response.firstDrop}" }
        }
        if (kind.isEmpty(response.content)) return Loadable.Error(AddonError.EmptyContent)
        val content = kind.extract(response.content)
            ?: return Loadable.Error(AddonError.UnexpectedResponse(kind.name, response.content.kindName))
        return Loadable.Ready(prepare(content), response.dropped, response.sent)
    }

    private fun describe(request: ResourceRequest) = request.path.let { "${it.resource}/${it.type}/${it.id}" }

    companion object {
        const val DEFAULT_CONCURRENCY = 8
        val DEFAULT_TIMEOUT: Duration = 30.seconds
        private const val TAG = "addons"
    }
}

/**
 * The payload a request expects (stremio-core's `TryFrom<ResourceResponse>` targets). An answer of the kind with
 * nothing in it (a list without items, a `meta` without an item) is [AddonError.EmptyContent].
 */
class ContentKind<T> private constructor(
    val name: String,
    internal val extract: (ResourceContent) -> T?,
    internal val isEmpty: (ResourceContent) -> Boolean,
) {
    companion object {
        val METAS: ContentKind<List<MetaItemPreview>> =
            ContentKind("metas", { (it as? ResourceContent.Metas)?.items }, { it is ResourceContent.Metas && it.items.isEmpty() })
        val META: ContentKind<MetaItem> = ContentKind("meta", { (it as? ResourceContent.Meta)?.item }, { it == ResourceContent.NoMeta })
        /** A catalog's full metas (`metasDetailed`), with their videos: the answer to an ids extra ([AggrRequest.CatalogsFiltered]). */
        val METAS_DETAILED: ContentKind<List<MetaItem>> = ContentKind(
            "metasDetailed",
            { (it as? ResourceContent.MetasDetailed)?.items },
            { it is ResourceContent.MetasDetailed && it.items.isEmpty() },
        )
        val STREAMS: ContentKind<List<Stream>> =
            ContentKind("streams", { (it as? ResourceContent.Streams)?.items }, { it is ResourceContent.Streams && it.items.isEmpty() })
        val SUBTITLES: ContentKind<List<Subtitles>> =
            ContentKind("subtitles", { (it as? ResourceContent.Subtitles)?.items }, { it is ResourceContent.Subtitles && it.items.isEmpty() })

        /** An addon catalog's addons (`addon_catalog`). */
        val ADDONS: ContentKind<List<Descriptor>> =
            ContentKind("addons", { (it as? ResourceContent.Addons)?.items }, { it is ResourceContent.Addons && it.items.isEmpty() })

        /**
         * A guide catalog's page with a `date` ([com.edde746.tortuga.model.ManifestCatalog.declaresDate]): channels with
         * that day's programmes (`metasDetailed`), or plain `metas`, channels without programmes (stremio-core
         * `lift_plain_metas`).
         */
        val GUIDE: ContentKind<List<MetaItem>> = ContentKind(
            "metasDetailed",
            {
                when (it) {
                    is ResourceContent.MetasDetailed -> it.items
                    is ResourceContent.Metas -> it.items.map(::MetaItem)
                    else -> null
                }
            },
            { (it is ResourceContent.MetasDetailed && it.items.isEmpty()) || (it is ResourceContent.Metas && it.items.isEmpty()) },
        )
    }
}

internal val ResourceContent.kindName: String
    get() = when (this) {
        is ResourceContent.Metas -> "metas"
        is ResourceContent.MetasDetailed -> "metasDetailed"
        is ResourceContent.Meta, ResourceContent.NoMeta -> "meta"
        is ResourceContent.Streams -> "streams"
        is ResourceContent.Subtitles -> "subtitles"
        is ResourceContent.Addons -> "addons"
    }
