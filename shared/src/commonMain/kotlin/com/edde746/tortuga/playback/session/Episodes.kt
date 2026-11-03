package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.time.Instant

/**
 * The video after [videoId] (stremio-core `MetaItem::next_video`): the next entry of [MetaItem.videos], which are in
 * season and episode order with specials last, unless it isn't released by [now] or it would move from a regular
 * season into the specials (season 0). Live items ([com.edde746.tortuga.model.MetaItemPreview.isLive]) have none.
 */
internal fun MetaItem.nextVideo(videoId: String, now: Instant): Video? {
    if (preview.isLive) return null
    val position = indexOfVideo(videoId)
    if (position < 0) return null
    return videoAfter(position, now)
}

/** The first video after [videoId], by [nextVideo], that [marks] hasn't watched; null when none is out by [now]. */
internal fun MetaItem.nextUnwatched(videoId: String, marks: WatchedBitField, now: Instant): Video? {
    if (preview.isLive) return null
    var position = indexOfVideo(videoId)
    if (position < 0) return null
    // One pass on from the video: each step is [nextVideo]'s from the one before.
    while (true) {
        val next = videoAfter(position, now) ?: return null
        if (!marks.isWatched(next.id)) return next
        position++
    }
}

/** [nextVideo] of the video at [position] of [MetaItem.videos]. */
private fun MetaItem.videoAfter(position: Int, now: Instant): Video? {
    val next = videos.getOrNull(position + 1) ?: return null
    val currentSeason = videos[position].seriesInfo?.season ?: 0
    val nextSeason = next.seriesInfo?.season ?: 0
    val released = next.released?.let { it <= now } ?: true
    return next.takeIf { (nextSeason != 0 || currentSeason == nextSeason) && released }
}

/**
 * The video before [videoId]: the previous entry of [MetaItem.videos], the one whose [nextVideo] this video is (its
 * release aside, as it is older): never from the specials (season 0) back into a regular season. Live items have none.
 */
internal fun MetaItem.previousVideo(videoId: String): Video? {
    if (preview.isLive) return null
    val position = indexOfVideo(videoId)
    if (position < 1) return null
    val previous = videos[position - 1]
    val currentSeason = videos[position].seriesInfo?.season ?: 0
    val previousSeason = previous.seriesInfo?.season ?: 0
    return previous.takeIf { currentSeason != 0 || previousSeason == currentSeason }
}

/**
 * [videoId] is one of the meta's episodes, the player's previous and next episode buttons show for it: an entry of
 * [MetaItem.videos] with an episode number or among several videos. Not a movie (no videos, or its one video) nor a
 * live item.
 */
internal fun MetaItem.isEpisode(videoId: String): Boolean {
    if (preview.isLive) return false
    val video = video(videoId) ?: return false
    return video.seriesInfo != null || videos.size > 1
}

/** The video's episode code, `S1E2`, or null when it isn't an episode. */
internal fun Video.episodeCode(): String? =
    seriesInfo?.let { T.session.episode(it.season.toString(), it.episode.toString()) }

/**
 * The second line under the title: `S1E2 · Pilot` (the title isolated), the code or the title alone, or null for
 * neither.
 */
internal fun Video.episodeLine(): String? {
    val code = episodeCode()
    val title = title.trim().takeIf { it.isNotEmpty() }
    return when {
        code != null && title != null -> T.session.episodeTitle(code, isolate(title))
        else -> code ?: title
    }
}
