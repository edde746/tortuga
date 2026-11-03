package com.edde746.tortuga.playback.session

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlinx.coroutines.flow.last

/**
 * Chooses the stream another episode of the series (the next one, or the previous one when asked) plays from
 * without asking:
 * 1. the episode's stream with the current stream's `behaviorHints.bingeGroup`, from the addon that returned the
 *    current stream (stremio-core asks only that addon too: binge groups are the addon's own names): the streams the
 *    title carries for the episode when that addon sent the title, then its `/stream` answer; that addon's other
 *    streams that pass the profile's filters ([StreamFilter]) are the ones to fall back on;
 * 2. else, with auto-play off, the release the episode played from before (on any device), found again in its addon's
 *    answer now and played with the link that answer gives ([StreamAutoPicker.lastPlayed]); with auto-play on,
 *    auto-play's pick for it ([StreamAutoPicker]: the filters, and the remembered release only while still offered);
 * 3. else none, and the user picks.
 *
 * Only stream lists are fetched; no stream URL is touched before the pick is played.
 */
internal class BingePicker(
    private val aggregator: AddonAggregator,
    private val autoPicker: StreamAutoPicker,
) {
    suspend fun pick(
        profileId: String,
        meta: MetaItem,
        video: Video,
        current: StreamChoice,
        addons: List<Descriptor>,
        settings: ProfileSettings,
    ): PlayRequest? {
        val addon = addons.firstOrNull { it.transportUrl == current.addonUrl }
        val carried = meta.addon?.takeIf { it.transportUrl == current.addonUrl }
            ?.let { sent -> meta.carriedStreams(video.id).map { StreamChoice(it, sent.transportUrl, sent.manifest.name) }.filter { it.playable } }
        val choices = carried.orEmpty() + addon?.let { streams(it, meta.type, video.id) }.orEmpty()
        val filter = StreamFilter.of(settings)
        val group = current.stream.behaviorHints.bingeGroup
        val binge = group?.let { choices.firstOrNull { choice -> choice.stream.behaviorHints.bingeGroup == it } }
        if (binge != null) return PlayRequest(meta, video.id, binge, choices.filter { it !== binge && filter.accepts(it.stream) })

        if (settings.streamAutoPlay != AutoPlayMode.OFF) {
            return autoPicker.pick(profileId, meta, video.id, addons, settings.streamAutoPlay, filter, StreamSort.of(settings))
        }
        return autoPicker.lastPlayed(profileId, meta, video.id, addons, filter)
    }

    /** The addon's playable streams for [videoId], in its order; none when it fails. */
    private suspend fun streams(addon: Descriptor, type: String, videoId: String): List<StreamChoice> {
        val state = aggregator.streams(listOf(addon), type, videoId).last().firstOrNull()?.state
        val streams = (state as? Loadable.Ready)?.content.orEmpty()
        return streams.map { StreamChoice(it, addon.transportUrl, addon.manifest.name) }.filter { it.playable }
    }
}
