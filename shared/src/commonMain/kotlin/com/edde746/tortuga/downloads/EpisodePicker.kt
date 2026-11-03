package com.edde746.tortuga.downloads

import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.session.AutoPickRule
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * "Download season…" and Keep synced's choices: which episodes ([episodes], [nextUnwatched]) and each one's stream
 * ([stream]), picked without asking. Only stream lists are fetched; no stream URL is touched before its download runs.
 */
internal class EpisodePicker(private val env: DownloadEnv) {
    /**
     * The episodes [pick] chooses of [meta]'s [season] (every regular season's for null, specials left out), released
     * by [now], in order: all of them, the unwatched ones (the library's marks in [item]), or the next 5 or 10 unwatched
     * from the library's pointer on ([nextUnwatched]).
     */
    fun episodes(meta: MetaItem, season: Int?, pick: SeasonPick, item: LibraryItem?, now: Instant): List<Video> {
        val scope = released(meta, now).filter { video ->
            val number = video.seriesInfo?.season ?: return@filter false
            if (season != null) number == season else number != 0
        }
        val marks = WatchedBitField.of(item?.state?.watchedVideos, meta)
        return when (pick) {
            SeasonPick.ALL -> scope
            SeasonPick.UNWATCHED -> scope.filterNot { marks.isWatched(it.id) }
            SeasonPick.NEXT_5 -> nextUnwatched(meta, scope, item, marks).take(5)
            SeasonPick.NEXT_10 -> nextUnwatched(meta, scope, item, marks).take(10)
        }
    }

    /**
     * The unwatched episodes of [scope] from the library's pointer on (the video [item] last played, itself included
     * while unwatched), in order; from the first unwatched one when there is no pointer or [scope] has none after it.
     */
    fun nextUnwatched(meta: MetaItem, scope: List<Video>, item: LibraryItem?, marks: WatchedBitField): List<Video> {
        val unwatched = scope.filterNot { marks.isWatched(it.id) }
        val pointer = item?.state?.videoId?.let(meta::indexOfVideo)?.takeIf { it >= 0 } ?: return unwatched
        return unwatched.filter { meta.indexOfVideo(it.id) >= pointer }.ifEmpty { unwatched }
    }

    /** Every regular season's unwatched released episodes from the pointer on (Keep synced's order). */
    fun syncCandidates(meta: MetaItem, item: LibraryItem?, now: Instant): List<Video> {
        val scope = released(meta, now).filter { (it.seriesInfo?.season ?: 0) != 0 }
        return nextUnwatched(meta, scope, item, WatchedBitField.of(item?.state?.watchedVideos, meta))
    }

    /**
     * The stream [videoId] of [meta] downloads from, picked without asking: the stream of [reference]'s addon with its
     * `bingeGroup` (as the binge picker finds the next episode's), else the profile's auto-play rule (first match when
     * auto-play is off) with its filters; the streams [meta] carries for the video count as its addon's, first.
     * Streams an addon marks not ready are never picked (picking one makes its service fetch the file). Null when
     * nothing qualifies.
     */
    suspend fun stream(
        profileId: String,
        meta: MetaItem,
        videoId: String,
        reference: StreamChoice?,
        addons: List<Descriptor>,
        settings: ProfileSettings,
    ): StreamChoice? {
        val group = reference?.stream?.behaviorHints?.bingeGroup
        if (group != null && reference != null) {
            val sameAddon = addons.filter { it.transportUrl == reference.addonUrl }
            val match = env.aggregator.streams(sameAddon, meta, videoId).last().filter { it.addon.transportUrl == reference.addonUrl }
                .firstNotNullOfOrNull { answer ->
                    val streams = (answer.state as? Loadable.Ready)?.content.orEmpty()
                    streams.firstOrNull { it.behaviorHints.bingeGroup == group && pickable(it) }?.let { StreamChoice(it, answer.addon.transportUrl, answer.addon.manifest.name) }
                }
            if (match != null) return match
        }
        val mode = settings.streamAutoPlay
        val remembered = if (mode == AutoPlayMode.REUSE_LAST_LINK) {
            env.streamHistory.lastStream(profileId, meta.id, videoId)?.let { StreamChoice.fromHistory(it, addonName = "") }
        } else {
            null
        }
        val filter = StreamFilter.of(settings)
        val sort = StreamSort.of(settings)
        val decision = env.aggregator.streams(addons, meta, videoId)
            .map { states -> AutoPickRule.decide(states.map { it.withoutUncached() }, filter, remembered, sort = sort) }
            .firstOrNull { it != AutoPickRule.Decision.Pending }
        return (decision as? AutoPickRule.Decision.Picked)?.choice
    }

    private fun released(meta: MetaItem, now: Instant) = meta.videos.filter { it.released?.let { date -> date <= now } ?: true }

    private fun AddonLoadable<List<Stream>>.withoutUncached(): AddonLoadable<List<Stream>> {
        val ready = state as? Loadable.Ready ?: return this
        return copy(state = ready.copy(content = ready.content.filter(::pickable)))
    }

    /** Not marked as not ready by its addon, and playable. */
    private fun pickable(stream: Stream): Boolean =
        stream.playability is StreamPlayability.Playable && StreamBadgeParser.parse(stream).cached != false
}
