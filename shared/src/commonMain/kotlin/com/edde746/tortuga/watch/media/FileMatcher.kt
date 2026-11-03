package com.edde746.tortuga.watch.media

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.playback.session.LocalVideos
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlinx.coroutines.flow.last

/**
 * A file of the room's media on this device: [choice] for [title], or the download [local] (from its [choice]). The
 * `ref` of the Open Watch Party SDK's candidates and choice: two are the same file when they play the same stream for
 * the same video, whatever meta object they carry.
 */
class RoomFile internal constructor(val title: RoomTitle, val choice: StreamChoice, val local: LocalVideo?) {
    override fun equals(other: Any?): Boolean =
        other is RoomFile && other.title.meta.id == title.meta.id && other.title.videoId == title.videoId && other.choice == choice

    override fun hashCode(): Int = (title.meta.id.hashCode() * 31 + title.videoId.hashCode()) * 31 + choice.hashCode()

    override fun toString(): String = "RoomFile(${title.videoId}, $choice, local=${local != null})"
}

/**
 * A guest's [files] of the room's media, and whether some addon failed while it was asked ([searchFailed]): no files then
 * doesn't mean the addons have none.
 */
class RoomFiles internal constructor(val files: List<RoomFile>, val searchFailed: Boolean)

/**
 * A guest's files of the room's media, in the order it prefers them (watch together's resolver core; the SDK opens
 * them in turn and decides by duration):
 *
 * 1. The guest's own download of the room's video ([localVideos]), under the room's ids or its mapped ones.
 * 2. The streams the profile's installed addons offer for the room's type and video id (the download's own stream left
 *    out); when none of them offers a playable one, the same request under the room's mapped ids ([RoomMedia.imdbId],
 *    [RoomMedia.tmdbId]), the IMDb form first.
 * 3. The streams are ranked ([CandidateRanking]): the host's video hash, file name or size first, then the closest
 *    size, then the profile's own order (auto-play's pick, the last link, the filters). Streams an addon marks not
 *    ready are left out.
 *
 * Only stream lists are fetched; nothing is opened here. An addon that fails is reported ([RoomFiles.searchFailed]), so
 * the guest is told the search failed rather than that it has no stream.
 */
class FileMatcher internal constructor(
    private val profileId: String,
    private val aggregator: AddonAggregator,
    private val installedAddons: suspend () -> List<Descriptor>,
    private val streamHistory: StreamHistoryRepository,
    private val settings: SettingsRepository,
    /** The profile's downloads on this device; null without downloads. */
    private val localVideos: LocalVideos? = null,
) {
    /** This profile's files of [media], played as [title], in the order the guest prefers them. */
    suspend fun files(media: RoomMedia, title: RoomTitle): RoomFiles {
        if (media.metaId.isEmpty()) return RoomFiles(emptyList(), searchFailed = false)
        val local = download(media)
        val own = listOfNotNull(local?.let { RoomFile(title, it.stream, it) })
        val host = media.fingerprint ?: return RoomFiles(own, searchFailed = false)
        val search = streams(media, host)
        val streams = search.choices.filterNot { local != null && it.sameRelease(local.stream) }
        return RoomFiles(own + streams.map { RoomFile(title, it, local = null) }, search.failed)
    }

    /** The guest's download of the room's video, under the room's ids or its mapped ones; null when it has none. */
    private suspend fun download(media: RoomMedia): LocalVideo? {
        val downloads = localVideos ?: return null
        for (video in listOf(media.ownVideo) + media.mappedVideos()) {
            downloads.localVideo(video.metaId, video.videoId)?.let { return it }
        }
        return null
    }

    private suspend fun streams(media: RoomMedia, host: FileFingerprint): StreamSearch {
        val addons = installedAddons()
        var failed = false
        for (video in listOf(media.ownVideo) + media.mappedVideos()) {
            val states = aggregator.streams(addons, video.type, video.videoId).last()
            failed = failed || states.any { it.state is Loadable.Error }
            if (states.none(::offersPlayable)) continue
            val loaded = settings.settings(profileId)
            val remembered = streamHistory.lastStream(profileId, video.metaId, video.videoId)
                ?.let { StreamChoice.fromHistory(it, addonName = "") }
            return StreamSearch(CandidateRanking.rank(states, host, StreamFilter.of(loaded), StreamSort.of(loaded), loaded.streamAutoPlay, remembered), failed)
        }
        return StreamSearch(emptyList(), failed)
    }

    private fun offersPlayable(state: AddonLoadable<List<Stream>>): Boolean =
        (state.state as? Loadable.Ready)?.content?.any { it.playability is StreamPlayability.Playable } == true

    /** The ranked [choices] of a search, and whether an addon failed in it. */
    private class StreamSearch(val choices: List<StreamChoice>, val failed: Boolean)
}
