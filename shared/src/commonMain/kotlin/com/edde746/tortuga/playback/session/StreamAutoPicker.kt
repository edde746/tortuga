package com.edde746.tortuga.playback.session

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/**
 * Auto-play ([AutoPlayMode]): picks the stream a video plays from without showing the stream list, for
 * Play, Resume and an episode on Detail and for the next episode without a binge match. It asks every stream addon,
 * as the list would, after the streams the title carries for the video ([AddonAggregator.streams] with the meta), and
 * decides as soon as the answers in hand settle it ([AutoPickRule]), cancelling the rest. With auto-play off,
 * [lastPlayed] finds the stream a video last played from the same way. Only stream lists are fetched; no stream URL is
 * touched before the pick plays, and a link the stream history saved is never played as it was saved.
 */
internal class StreamAutoPicker(
    private val aggregator: AddonAggregator,
    private val streamHistory: StreamHistoryRepository,
) {
    /**
     * The request auto-play makes for [videoId] of [meta] with [mode], [filter] and [sort], or null when nothing
     * qualifies (the stream list then opens). [mode] [AutoPlayMode.OFF] picks nothing. [answers]: the addons' answers
     * already asked for (Detail's Play asks as the page opens), else they are asked now.
     */
    suspend fun pick(
        profileId: String,
        meta: MetaItem,
        videoId: String,
        addons: List<Descriptor>,
        mode: AutoPlayMode,
        filter: StreamFilter,
        sort: StreamSort,
        answers: Flow<List<AddonLoadable<List<Stream>>>>? = null,
    ): PlayRequest? {
        if (mode == AutoPlayMode.OFF) return null
        var remembered: StreamChoice? = null
        var titleLast: StreamChoice? = null
        if (mode == AutoPlayMode.REUSE_LAST_LINK) {
            remembered = streamHistory.lastStream(profileId, meta.id, videoId)?.let { StreamChoice.fromHistory(it, addonName = "") }
            titleLast = streamHistory.latestForMeta(profileId, meta.id)?.takeIf { it.videoId != videoId }
                ?.let { StreamChoice.fromHistory(it, addonName = "") }
        }
        val decision = (answers ?: aggregator.streams(addons, meta, videoId))
            .map { states -> AutoPickRule.decide(states, filter, remembered, titleLast, sort) }
            .firstOrNull { it != AutoPickRule.Decision.Pending }
        val picked = decision as? AutoPickRule.Decision.Picked ?: return null
        return PlayRequest(meta, videoId, picked.choice, picked.alternatives, picked.how)
    }

    /**
     * Auto-play off's replay of [videoId] of [meta] (Resume, a Continue Watching card, Play from the beginning, the next
     * episode without a binge match): the stream it last played from, as the stream history has it from any of the
     * profile's devices, found again in its addon's answer now ([AutoPickRule.lastLink]); only that addon is asked. The
     * request plays the link that answer gives, with the addon's other streams that pass [filter] after it. Null when
     * the history has none, its addon isn't installed or fails, or its answer no longer has that release: the stream
     * list then opens. An addon's link can be good only for the device and network that asked for it, or only for a
     * while (an addon's `/playback/…` links), so the saved one is never played. [answers]: every addon's
     * answers already asked for (Detail's Play), of which that addon's are read, else it is asked now.
     */
    suspend fun lastPlayed(
        profileId: String,
        meta: MetaItem,
        videoId: String,
        addons: List<Descriptor>,
        filter: StreamFilter,
        answers: Flow<List<AddonLoadable<List<Stream>>>>? = null,
    ): PlayRequest? {
        val remembered = streamHistory.lastStream(profileId, meta.id, videoId)?.let { StreamChoice.fromHistory(it, addonName = "") } ?: return null
        val addon = addons.filter { it.transportUrl == remembered.addonUrl }.ifEmpty { return null }
        val states = answers?.map { all -> all.filter { it.addon.transportUrl == remembered.addonUrl } } ?: aggregator.streams(addon, meta, videoId)
        val decision = states.map { AutoPickRule.lastLink(it, filter, remembered) }.firstOrNull { it != AutoPickRule.Decision.Pending }
        val picked = decision as? AutoPickRule.Decision.Picked ?: return null
        return PlayRequest(meta, videoId, picked.choice, picked.alternatives)
    }
}

/**
 * Auto-play's choice over the stream addons' answers so far, in the installed order (the title's carried streams
 * first, as their addon's answer):
 * 1. with a [remembered] stream (reuse last link), that stream as its addon offers it now ([StreamChoice.indexOfRemembered]:
 *    the same release, with the link this answer gives), playable, filters or not;
 * 2. else, with [titleLast] (reuse last link: the stream another video of the title played from last, an episode
 *    before this one), the stream its addon offers now in the same `behaviorHints.bingeGroup`, playable, filters or
 *    not (the next episode's binge pick, for any episode);
 * 3. else the first playable stream that passes the filters, in the list's order; an addon still answering before it
 *    leaves the choice open, a failed one is skipped; with a [sort] ([StreamSort]), the first of every addon's in the
 *    sorted order, once every addon has answered (any could have a better one);
 * 4. else, once every addon answered, none.
 * While the addon of 1 or 2 is still answering, nothing is decided; its answers are both its carried streams and its
 * `/stream` answer when it sent the title too.
 *
 * The alternatives are the other playable streams that pass the filters, known by then, in the list's order (sorted
 * by [sort]): the ones listed after the pick first, then the ones before (as a pick in the list gets them).
 */
internal object AutoPickRule {
    sealed interface Decision {
        data object Pending : Decision

        data object None : Decision

        data class Picked(val choice: StreamChoice, val alternatives: List<StreamChoice>, val how: AutoPlayPick) : Decision
    }

    fun decide(
        states: List<AddonLoadable<List<Stream>>>,
        filter: StreamFilter,
        remembered: StreamChoice?,
        titleLast: StreamChoice? = null,
        sort: StreamSort = StreamSort.ADDON_ORDER,
    ): Decision {
        if (remembered != null) offeredAgain(states, filter, remembered, sort)?.let { return it }
        val group = titleLast?.stream?.behaviorHints?.bingeGroup
        if (titleLast != null && group != null) {
            offeredBy(states, filter, sort, titleLast.addonUrl) { streams ->
                streams.firstOrNull { it.behaviorHints.bingeGroup == group && it.playability is StreamPlayability.Playable }
            }?.let { return it }
        }
        if (!sort.addonOrder) {
            if (states.any { it.state == Loadable.Loading }) return Decision.Pending
            val passing = states.flatMap { addonState ->
                (addonState.state as? Loadable.Ready)?.content.orEmpty().mapNotNull { stream ->
                    if (stream.playability !is StreamPlayability.Playable) return@mapNotNull null
                    val badges = StreamBadgeParser.parse(stream)
                    if (filter.accepts(stream, badges)) stream to badges else null
                }
            }
            val best = sort.sorted(passing, { it.first }, { it.second }).firstOrNull() ?: return Decision.None
            return picked(states, filter, sort, best.first, AutoPlayPick.FIRST_MATCH)
        }
        for (addonState in states) {
            when (val state = addonState.state) {
                Loadable.Loading -> return Decision.Pending
                is Loadable.Error -> continue
                is Loadable.Ready -> {
                    val match = state.content.firstOrNull { it.playability is StreamPlayability.Playable && filter.accepts(it) }
                    if (match != null) return picked(states, filter, sort, match, AutoPlayPick.FIRST_MATCH)
                }
            }
        }
        return Decision.None
    }

    /** Rule 1 alone (auto-play off's replay, [StreamAutoPicker.lastPlayed]): none where [decide] would go on to 2. */
    fun lastLink(states: List<AddonLoadable<List<Stream>>>, filter: StreamFilter, remembered: StreamChoice): Decision =
        offeredAgain(states, filter, remembered, StreamSort.ADDON_ORDER) ?: Decision.None

    private fun offeredAgain(states: List<AddonLoadable<List<Stream>>>, filter: StreamFilter, remembered: StreamChoice, sort: StreamSort): Decision? =
        offeredBy(states, filter, sort, remembered.addonUrl) { it.getOrNull(StreamChoice.indexOfRemembered(it, remembered.addonUrl, remembered)) }

    /**
     * The last-stream pick [find] makes among the streams of the addon at [addonUrl] (each of its answers: the title's
     * carried streams, its `/stream` answer), when it is playable; pending while that addon answers and none has it;
     * null when it isn't installed, failed, or [find] has nothing playable.
     */
    private fun offeredBy(
        states: List<AddonLoadable<List<Stream>>>,
        filter: StreamFilter,
        sort: StreamSort,
        addonUrl: String,
        find: (List<Stream>) -> Stream?,
    ): Decision? {
        var answering = false
        for (addonState in states) {
            if (addonState.addon.transportUrl != addonUrl) continue
            when (val state = addonState.state) {
                Loadable.Loading -> answering = true
                is Loadable.Ready -> find(state.content)?.takeIf { it.playability is StreamPlayability.Playable }
                    ?.let { return picked(states, filter, sort, it, AutoPlayPick.LAST_LINK) }
                is Loadable.Error -> {}
            }
        }
        return if (answering) Decision.Pending else null
    }

    private fun picked(states: List<AddonLoadable<List<Stream>>>, filter: StreamFilter, sort: StreamSort, pick: Stream, how: AutoPlayPick): Decision.Picked {
        var chosen: StreamChoice? = null
        val listed = mutableListOf<StreamChoice>()
        for (addonState in states) {
            val streams = (addonState.state as? Loadable.Ready)?.content ?: continue
            for (stream in streams) {
                val choice = StreamChoice(stream, addonState.addon.transportUrl, addonState.addon.manifest.name)
                when {
                    stream === pick && chosen == null -> chosen = choice.also { listed += it }
                    !choice.playable || !filter.accepts(stream) -> {}
                    else -> listed += choice
                }
            }
        }
        val choice = checkNotNull(chosen) { "the pick is one of the answers" }
        val ordered = sort.sorted(listed, { it.stream }) { StreamBadgeParser.parse(it.stream) }
        val at = ordered.indexOfFirst { it === choice }
        return Decision.Picked(choice, ordered.drop(at + 1) + ordered.take(at), how)
    }
}
