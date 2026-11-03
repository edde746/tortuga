package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SubtitleMode
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.resolve.StreamHeaders
import com.edde746.tortuga.playback.subtitles.SubtitleRequest
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate

/*
 * What every playback of a video follows, in Tortuga's player (PlaybackSession) and in a player app
 * (`PlayerAppHandoff`): where it starts, what the player is handed, and how progress and the credits are recorded.
 */

/** The video [PlayRequest.videoId] of the request's meta, when the meta lists it. */
internal fun PlayRequest.video(): Video? = meta.videos.firstOrNull { it.id == videoId }

/** The title the player shows: "Chernobyl S1E2" (the name isolated beside the code), "The Shawshank Redemption". */
internal fun PlayRequest.playerTitle(): String {
    val name = meta.name.takeIf { it.isNotBlank() }
    val code = video()?.episodeCode() ?: return name.orEmpty()
    return listOfNotNull(name?.let(::isolate), code).joinToString(" ")
}

/**
 * Where [PlayRequest.videoId] starts: the request's own position, else the library's resume point in [item]; a trailer
 * and a live title at 0.
 */
internal fun PlayRequest.startPosition(item: LibraryItem?): Double = when {
    purpose == PlayPurpose.TRAILER || meta.preview.isLive -> 0.0
    else -> startPositionSeconds?.coerceAtLeast(0.0) ?: PlaybackSession.resumePoint(item, videoId)
}

/**
 * The seek a video starting at [startSeconds] records first: when [item] holds another position for [videoId]
 * (starting over after the credits, or from another device's position), the resume point follows the start, and
 * the progress recorded next counts from there. Null when there is nothing to move.
 */
internal fun startSeek(item: LibraryItem?, videoId: String, startSeconds: Double): PlaybackPosition? {
    val stored = item?.state ?: return null
    val start = PlaybackSession.millis(startSeconds)
    if (stored.videoId != videoId || stored.timeOffset < PlaybackSession.MIN_RESUME_MILLIS || stored.timeOffset == start) return null
    return PlaybackPosition(start, stored.duration)
}

/** The request headers the player sends for [this]: the addon's, with Tortuga's [userAgent] when it set none. */
internal fun ResolvedStream.playerHeaders(userAgent: String): Map<String, String> =
    if (this.userAgent == null) headers + (StreamHeaders.USER_AGENT to userAgent) else headers

/**
 * The subtitle addons' query for [choice] of this request, resolved as [stream]: the addon's `videoHash`, `videoSize`
 * and `filename` first, else what the resolver found (stremio-core `subtitles_update`).
 */
internal fun PlayRequest.subtitleRequest(choice: StreamChoice, stream: ResolvedStream): SubtitleRequest = SubtitleRequest(
    type = meta.type,
    videoId = videoId,
    videoHash = stream.videoHash,
    videoSize = choice.stream.behaviorHints.videoSize ?: stream.size,
    filename = stream.filename,
    streamSubtitles = choice.stream.subtitles,
    streamAddonName = choice.addonName,
)

/**
 * Playback at [positionMillis] of [durationMillis] counts the video as watched: it reached [thresholdPercent] of it
 * (the profile's `WatchedThresholdPercent`), or [creditsStartMillis], the start of its credits to the end (from its
 * chapters, `SkipSegments`; a player app reports none), whichever comes first. Position, not time played: a seek
 * there counts, and so does a resumed play.
 */
internal fun countsAsWatched(positionMillis: Long, durationMillis: Long, thresholdPercent: Int, creditsStartMillis: Long?): Boolean =
    durationMillis > 0 &&
        (positionMillis * 100 >= durationMillis * thresholdPercent || (creditsStartMillis != null && positionMillis >= creditsStartMillis))

/**
 * Leaving a video at [positionSeconds] of [durationSeconds] counts as having finished it: past
 * [PlaybackSession.CREDITS_THRESHOLD] of it, or past [creditsStart], the start of its credits to the end (from its
 * chapters, `SkipSegments`; a player app reports none). Before that a movie stays resumable, watched or not.
 */
internal fun pastCredits(positionSeconds: Double, durationSeconds: Double, creditsStart: Double? = null): Boolean =
    durationSeconds > 0 &&
        (positionSeconds > durationSeconds * PlaybackSession.CREDITS_THRESHOLD || (creditsStart != null && positionSeconds >= creditsStart))

/**
 * A video of [meta] left past its credits before it counts as watched (a threshold past the credits): the library's
 * pointer moves to [next] at its start (stremio-core `advance_to_video`), past it when it is watched, or a movie (or an
 * episode with nothing out after it) goes back to its start. An episode that counts as watched moved on as it was
 * recorded ([LibraryRepository.recordProgress]).
 */
internal suspend fun LibraryRepository.moveOn(profileId: String, meta: MetaItem, next: Video?) {
    if (next != null) advanceToVideo(profileId, meta, next.id) else rewind(profileId, meta.id)
}

/**
 * Records [videoId] of [meta] at [timeMs] of [durationMs] ([LibraryRepository.recordProgress]), [watched] when the
 * playback counts it as watched ([countsAsWatched]); an episode that comes to count moves the library's pointer on in
 * that write. Crossing into watched pushes to sync at once, as [flush] does.
 */
internal suspend fun LibraryRepository.saveProgress(
    profileId: String,
    meta: MetaItem,
    videoId: String,
    timeMs: Long,
    durationMs: Long,
    watched: Boolean,
    sync: SyncTrigger,
    flush: Boolean,
) {
    val update = recordProgress(profileId, meta.id, videoId, timeMs, durationMs, meta, watched)
    if (flush || update?.crossedWatchedThreshold == true) sync.flushNow()
}

/**
 * The profile's language preferences as canonical tags; [subtitleWanted] is what the subtitle addons are asked for.
 * [alwaysSubtitles]: the profile shows subtitles always, SDH first ([SubtitleMode.ALWAYS]), not only following the audio.
 * [secondSubtitle]: the second subtitle's language; null for none.
 */
internal class LanguagePreferences(
    val audio: List<String>,
    val subtitles: List<String>,
    val secondSubtitle: String?,
    val subtitleWanted: List<String>,
    val alwaysSubtitles: Boolean,
) {
    companion object {
        fun of(settings: ProfileSettings): LanguagePreferences {
            val subtitles = settings.subtitleLanguages.mapNotNull(LanguageTags::canonical).distinct()
            val second = LanguageTags.canonical(settings.secondSubtitleLanguage)
            return LanguagePreferences(
                audio = settings.audioLanguages.mapNotNull(LanguageTags::canonical).distinct(),
                subtitles = subtitles,
                secondSubtitle = second,
                // With no subtitle language set, the interface's language fills the menu (nothing is selected).
                subtitleWanted = (subtitles.ifEmpty { listOfNotNull(LanguageTags.canonical(T.locale)) } + listOfNotNull(second)).distinct(),
                alwaysSubtitles = settings.showSubtitles == SubtitleMode.ALWAYS,
            )
        }
    }
}
