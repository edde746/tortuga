package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.format.formatTimecode
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.playback.session.episodeCode
import com.edde746.tortuga.playback.session.nextUnwatched
import com.edde746.tortuga.presentation.board.itemStateText
import com.edde746.tortuga.presentation.board.shownProgress
import com.edde746.tortuga.presentation.board.timeLeft
import com.edde746.tortuga.presentation.menu.mediaMenu
import com.edde746.tortuga.presentation.menu.menuDownload
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.time.Instant

internal fun DetailStore.primaryAction(meta: MetaItem, item: LibraryItem?, marks: WatchedBitField, now: Instant): DetailPrimaryAction? {
    // stremio-core's guessed stream: the default video, else the title's own id for a movie or a live title.
    val live = meta.preview.isLive
    val single = meta.preview.behaviorHints.defaultVideoId ?: meta.id.takeIf { meta.videos.isEmpty() || live }
    if (single != null) {
        val video = meta.video(single)
        return (if (live) null else resumeAction(single, item, video))
            ?: video?.let(::playAction)
            ?: DetailPrimaryAction(T.detail.play, single, resume = false, episodeTitle = null, progress = null)
    }
    val state = item?.state
    val pointer = state?.videoId?.let(meta::video)
    if (state != null && pointer != null) {
        val watched = marks.isWatched(pointer.id)
        if (!watched) resumeAction(pointer.id, item, pointer)?.let { return it }
        if (!watched && state.flaggedWatched == 0L && pointer.isOut(now)) return playAction(pointer)
        meta.nextUnwatched(pointer.id, marks, now)?.let { return playAction(it) }
    }
    if (meta.videos.none { it.seriesInfo != null }) return null
    // Read lazily: on a long series the episode Play plays is found near the start, not after a pass over them all.
    val regular = meta.videos.asSequence().filter { it.seriesInfo != null && it.seriesInfo.season != 0 }.ifEmpty { meta.videos.asSequence() }
    val out = regular.filter { it.isOut(now) }
    return playAction(out.firstOrNull { !marks.isWatched(it.id) } ?: out.firstOrNull() ?: regular.first())
}

/** Resuming [videoId] from the library's position, when it has one to resume from (as the player would). */
internal fun DetailStore.resumeAction(videoId: String, item: LibraryItem?, video: Video?): DetailPrimaryAction? {
    val seconds = PlaybackSession.resumePoint(item, videoId)
    if (seconds <= 0 || item == null) return null
    val time = formatTimecode(seconds)
    val code = video?.episodeCode()
    return DetailPrimaryAction(
        // Only where it resumes from: the play symbol and the watched fill say it resumes, and the shorter label
        // fits a phone's row beside its round buttons. Screen readers still hear "Resume …".
        label = if (code != null) T.detail.infoJoin(code, time) else time,
        videoId = videoId,
        resume = true,
        episodeTitle = video?.title?.trim()?.takeIf { it.isNotEmpty() },
        progress = item.shownProgress(),
        spokenLabel = if (code != null) T.detail.resumeEpisode(code, time) else T.detail.resume(time),
    )
}

internal fun DetailStore.playAction(video: Video): DetailPrimaryAction {
    val code = video.episodeCode()
    return DetailPrimaryAction(
        // An episode's code alone, as a resume's: the play symbol says it plays. Screen readers still hear "Play …".
        label = code ?: T.detail.play,
        videoId = video.id,
        resume = false,
        episodeTitle = video.title.trim().takeIf { it.isNotEmpty() },
        progress = null,
        spokenLabel = if (code != null) T.detail.playEpisode(code) else T.detail.play,
    )
}

/**
 * Everything [episode] builds [video]'s row from: equal inputs build an equal row, so a page that updates builds again
 * only the rows whose inputs changed (a long season holds a thousand episodes).
 *
 * @property locale the interface's language, which its texts are in.
 * @property runtime the title's runtime as an episode's line shows it ([DetailText.runtimePart]).
 * @property item the library item where it points at this video (its progress, where Play resumes it), else null.
 * @property download its download, where the title can be downloaded here and the episode is out.
 * @property offersDownloads the title can be downloaded here, so the episode's download is part of its row and its menu.
 * @property chooseStream its menu offers Choose a stream (auto-play is on; with it off the episode's press opens the
 *   list, unless the episode resumes: then it replays the stream it last played from).
 * @property seasonWatched its season's mark, null without seasons.
 * @property blurUnwatched the profile hides unwatched episodes' stills and descriptions ([ProfileSetting.BlurUnwatched]).
 */
internal data class EpisodeInputs(
    val video: Video,
    val locale: String,
    val runtime: String?,
    val item: LibraryItem?,
    val watched: Boolean,
    val upcoming: Boolean,
    val picking: Boolean,
    val nextUp: Boolean,
    val download: Download?,
    val offersDownloads: Boolean,
    val chooseStream: Boolean,
    val seasonWatched: Boolean?,
    val blurUnwatched: Boolean,
)

/** [video]'s [EpisodeInputs]: [nextUp] is the video Play plays, [download] the video's among the title's downloads. */
internal fun DetailStore.episodeInputs(
    video: Video,
    runtime: String?,
    item: LibraryItem?,
    marks: WatchedBitField,
    now: Instant,
    nextUp: String?,
    download: Download?,
    offersDownloads: Boolean,
    chooseStream: Boolean,
    seasonWatched: Boolean?,
    blurUnwatched: Boolean,
): EpisodeInputs {
    val upcoming = !video.isOut(now)
    return EpisodeInputs(
        video = video,
        locale = T.locale,
        runtime = runtime,
        item = item?.takeIf { it.state.videoId == video.id },
        watched = marks.isWatched(video.id),
        upcoming = upcoming,
        picking = picking(video.id),
        nextUp = video.id == nextUp,
        download = if (offersDownloads && !upcoming) download else null,
        offersDownloads = offersDownloads,
        chooseStream = chooseStream,
        seasonWatched = seasonWatched,
        blurUnwatched = blurUnwatched,
    )
}

internal fun DetailStore.episode(inputs: EpisodeInputs): DetailEpisode {
    val video = inputs.video
    val item = inputs.item
    val watched = inputs.watched
    val upcoming = inputs.upcoming
    val progress = item?.shownProgress()
    val number = video.seriesInfo?.episode?.toString()
    val ownTitle = video.title.trim()
    val title = ownTitle.ifEmpty { number?.let { T.detail.episodeNumbered(it) }.orEmpty() }
    val date = video.released?.let(DetailText::date)?.let { if (upcoming) T.detail.airs(it) else it }
    // Addons give no runtime per video; a series' runtime is its episodes' (Cinemeta's, from IMDb).
    val runtime = if (video.seriesInfo != null) inputs.runtime else null
    val download = inputs.download
    // Where Play starts it (the library's position, PlaybackSession.resumePoint): how much is left from there, and
    // whether its menu offers Play from the beginning.
    val resumeAt = PlaybackSession.resumePoint(item, video.id)
    val state = item?.state
    val left = if (resumeAt > 0 && state != null && state.duration > 0) state.duration - state.timeOffset else 0L
    val numberedTitle = if (number != null && ownTitle.isNotEmpty()) T.detail.numberedTitle(number, isolate(ownTitle)) else title
    val infoLine = if (inputs.picking) T.detail.findingStream else DetailText.join(listOfNotNull(date, runtime))
    val offersDownload = inputs.offersDownloads && !upcoming
    val overview = video.overview?.trim()?.takeIf { it.isNotEmpty() }
    val blurred = hidesSpoilers(inputs.blurUnwatched, watched, started = progress != null)
    return DetailEpisode(
        videoId = video.id,
        code = video.episodeCode(),
        number = number,
        title = title,
        numberedTitle = numberedTitle,
        moreLabel = T.detail.moreActionsNamed(isolate(numberedTitle)),
        infoLine = infoLine,
        overview = overview,
        thumbnail = video.thumbnail,
        progress = progress,
        blurred = blurred,
        watched = watched,
        upcoming = upcoming,
        nextUp = inputs.nextUp,
        watchedActionText = if (watched) T.detail.markUnwatched else T.detail.markWatched,
        detailLine = DetailText.join(listOfNotNull(infoLine, if (left > 0) timeLeft(left) else null)),
        stateText = itemStateText(badge = null, detail = null, progress = progress, watched = watched),
        download = if (offersDownload) DetailDownloads.episode(download) else null,
        menu = mediaMenu(
            inLibrary = null,
            watched = watched,
            inProgress = resumeAt > 0,
            seasonWatched = inputs.seasonWatched,
            chooseStream = inputs.chooseStream,
            download = if (offersDownload) menuDownload(download) else null,
            trailer = trailers(video.trailerStreams).isNotEmpty(),
        ),
        hiddenOverviewLabel = if (blurred && overview != null) T.detail.overviewHidden else null,
    )
}

internal fun Video.isOut(now: Instant): Boolean = released?.let { it <= now } ?: true

/**
 * Whether an episode's still and description are hidden: with [blurUnwatched] on ([ProfileSetting.BlurUnwatched]), one
 * neither [watched] nor [started] (the library's position in it: [shownProgress]), upcoming ones included.
 */
internal fun hidesSpoilers(blurUnwatched: Boolean, watched: Boolean, started: Boolean): Boolean = blurUnwatched && !watched && !started
