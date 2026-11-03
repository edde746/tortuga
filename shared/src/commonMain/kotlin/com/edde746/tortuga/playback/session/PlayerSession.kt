package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SkipMode
import com.edde746.tortuga.input.VideoClickAction
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.ErrorVideoSign
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.SkipKind
import com.edde746.tortuga.playback.SkipSegment
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.streams.StreamText
import com.edde746.tortuga.playback.subtitles.ExternalSubtitle
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.DurationUnit
import kotlin.time.TimeMark

/**
 * What the player store needs from the playback session (`PlaybackSession`): the session owns the
 * engine (it loads streams, listens to it, saves progress, resolves and re-resolves), and the store only renders it
 * and turns input into engine commands and these actions.
 *
 * Failures: the session decides what is shown. A mid-play expiry it recovers from by re-resolving never reaches
 * [SessionInfo.failure]; a failure it gives up on does.
 */
interface PlayerSession {
    /** The engine's latest state, as the session's engine listener received it. */
    val engineState: StateFlow<EngineState>

    val info: StateFlow<SessionInfo>

    /** Try the current stream again (re-resolve from the original URL and reload at the last position). */
    fun retry()

    /** Play the next of [SessionInfo.alternativeStreams]. */
    fun playNextStream()

    /** Ask for another stream for the video in its Streams sheet, the player paused (after an error video). */
    fun chooseStream()

    /**
     * Play the error video the stream sent ([SessionInfo.errorVideo]) from its start, for its message: nothing is
     * recorded, and its end shows the error again.
     */
    fun watchErrorVideo()

    /** Play [SessionInfo.nextEpisode]. */
    fun playNextEpisode()

    /** Play the episode before this one ([SessionInfo.hasPreviousEpisode]), its stream picked as the next episode's is. */
    fun playPreviousEpisode()

    /**
     * The user moved the position to [seconds]; the store calls this right before it sends the seek to the engine,
     * so the session saves the progress up to the old position and records the jump as a seek, not as time watched.
     */
    fun onSeek(seconds: Double)

    /**
     * The user picked track [id] of [kind] (null: none, subtitles off); the session stops choosing that kind for the
     * current file and carries the pick to the title's next files.
     */
    fun onTrackSelected(kind: TrackKind, id: Long?)

    /**
     * The user picked subtitle track [id] as the second subtitle (null: off); the session stops choosing the second
     * subtitle for the current file and carries the pick to the title's next files, as [onTrackSelected] does.
     */
    fun onSecondSubtitleSelected(id: Long?)

    /**
     * The user picked [SessionInfo.moreSubtitles]' subtitle at [url]: the session adds it to the file and selects it
     * once the engine lists it, as a pick of the user's ([onTrackSelected]).
     */
    fun addSubtitle(url: String)

    /**
     * The user turned auto-play of the next episode on or off in the player: [NextEpisode.autoplay] follows at once, and
     * the profile keeps it (its binge watching setting).
     */
    fun setAutoPlayNext(enabled: Boolean)

    /**
     * Moves a live playback with a rewind window ([SessionInfo.liveWindow]) to [behindSeconds] behind the live edge
     * (0: the edge, "go to live", where an open lands, a few segments back): by a seek within what the engine has read
     * ahead of the position or holds behind it, else by opening the stream again at the segment that holds it
     * ([LiveWindow]). Nothing about it is saved.
     */
    fun seekLive(behindSeconds: Double)
}

/**
 * @param title the headline, formatted by the session (the movie or series name).
 * @param subtitle the secondary line, formatted by the session (`S1E2 · Pilot`), or null.
 * @param artworkUrl the image the OS media controls show ([com.edde746.tortuga.presentation.player.NowPlaying]): the
 *   episode's thumbnail, else the meta's backdrop, else its poster; null when it has none.
 * @param posterUrl the meta's poster (an episode's series poster), the image Discord shows
 *   ([com.edde746.tortuga.presentation.player.DiscordActivity]); null when it has none.
 * @param resolving the session is still turning the picked stream into a playable URL (the addon's service may be
 *   fetching it); shown apart from buffering.
 * @param episode the video is one of a series' episodes: the controls show the previous and next episode buttons,
 *   enabled by [hasPreviousEpisode] and [nextEpisode].
 * @param trailer the video is a trailer of the title ([PlayPurpose.TRAILER]).
 * @param nextEpisode what plays next, when there is a next episode.
 * @param hasPreviousEpisode there is an episode before this one ([PlayerSession.playPreviousEpisode]).
 * @param alternativeStreams how many other streams the session can still try after the current one fails.
 * @param stream the stream playing, for the info panel; null before one is picked.
 * @param skipping what the profile has the player do with intros, recaps and credits ([SkipModes]); a change to the
 *   profile's settings reaches it while the video plays.
 * @param videoClick what a click on the video does with a mouse: the profile's setting ([ProfileSetting.VideoClick]),
 *   followed while the video plays as [skipping] is.
 * @param moreSubtitles the subtitles found for the open file and not added to it ([PlayerSession.addSubtitle]), in
 *   the order found; empty before the file opens and its subtitles are found.
 * @param errorVideo the stream sent an error video instead of the video ([ErrorVideoSign]): set from when it is told
 *   until another stream, a retry or another video, while [failure] shows it and while its message plays
 *   ([PlayerSession.watchErrorVideo]); up next never follows it.
 * @param live the playback is live: the title is (`behaviorHints.isLive`, the `tv` type), or the engine's reports say the
 *   open file is a live stream (while it can't seek in it, its duration grows past the first one reported or, without
 *   one, its position runs on; a duration that comes where there was none is the file's length, not growth; a video
 *   from a server without Range support can't be seeked in either, but its duration stays put, and a still image has
 *   no duration and its position stays put), latched for the file. Nothing seeks outside [liveWindow], nothing about
 *   the position is saved, and no credits, skip or up next show.
 * @param liveWindow the live file's rewind window, read from its HLS playlist: set while the playlist reaches at least
 *   [LiveWindow.MIN_SECONDS] back and hasn't ended (`#EXT-X-ENDLIST`), from the file's first frame; null otherwise.
 * @param judging the open file isn't known to be the video yet: from its load until its first frame (or its end) and
 *   its length were checked against an error video's (`PlaybackSession.judge`). The engine reports the file loaded,
 *   its length and its end before that, so up next waits for it: an error video opened at the video's resume point,
 *   past its own end, never brings it up.
 */
data class SessionInfo(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val posterUrl: String? = null,
    val resolving: Boolean = false,
    val episode: Boolean = false,
    val trailer: Boolean = false,
    val nextEpisode: NextEpisode? = null,
    val hasPreviousEpisode: Boolean = false,
    val alternativeStreams: Int = 0,
    val failure: PlaybackFailure? = null,
    val stream: PlayingStream? = null,
    val skipping: SkipModes = SkipModes(),
    val videoClick: VideoClickAction = VideoClickAction.PAUSE,
    val moreSubtitles: List<ExternalSubtitle> = emptyList(),
    val errorVideo: ErrorVideoSign? = null,
    val live: Boolean = false,
    val liveWindow: LiveWindow? = null,
    val judging: Boolean = false,
)

/**
 * A live stream's rewind window: how far its HLS playlist reaches behind the live edge (the
 * end of its newest segment), and where the open file joined it, from which the engine's position places it. The edge
 * moves on with the clock; so does a paused position's distance from it.
 *
 * @param windowSeconds the playlist's segments summed, at its latest read.
 * @param segmentSeconds the playlist's target segment duration (`#EXT-X-TARGETDURATION`).
 * @param joinedBehindSeconds how far behind the edge the open file's first segment started when it was opened.
 * @param joinedPositionSeconds the engine's position at the open file's first frame.
 * @param joinedAt when the open file was asked to open: the edge has moved on by the time since.
 */
data class LiveWindow(
    val windowSeconds: Double,
    val segmentSeconds: Double,
    val joinedBehindSeconds: Double,
    val joinedPositionSeconds: Double,
    val joinedAt: TimeMark,
) {
    /**
     * How far the engine's [positionSeconds] is behind the live edge now: never less than 0, and past [windowSeconds]
     * once the window slid past it (a paused position).
     */
    fun behind(positionSeconds: Double): Double =
        (joinedBehindSeconds + joinedAt.elapsedNow().toDouble(DurationUnit.SECONDS) - (positionSeconds - joinedPositionSeconds)).coerceAtLeast(0.0)

    /**
     * [behindSeconds] reads as live: within [EDGE_SEGMENTS] segments of the edge, where a file opened at the edge lands
     * ([com.edde746.tortuga.playback.resolve.LivePlaylist.JOIN_SEGMENTS], one segment more for the playlist's age).
     */
    fun atEdge(behindSeconds: Double): Boolean = behindSeconds <= EDGE_SEGMENTS * segmentSeconds

    companion object {
        /** The shortest window that rewinds: a playlist that reaches less far back plays as live without one. */
        const val MIN_SECONDS = 30.0

        const val EDGE_SEGMENTS = 4
    }
}

/**
 * The profile's skipping settings ([ProfileSetting.SkipIntros], [ProfileSetting.SkipRecaps],
 * [ProfileSetting.SkipCredits]): what the player does with each kind of [SkipSegment]. Previews follow [credits].
 */
data class SkipModes(
    val intros: SkipMode = SkipMode.BUTTON,
    val recaps: SkipMode = SkipMode.BUTTON,
    val credits: SkipMode = SkipMode.BUTTON,
) {
    fun of(kind: SkipKind): SkipMode = when (kind) {
        SkipKind.INTRO -> intros
        SkipKind.RECAP -> recaps
        SkipKind.CREDITS, SkipKind.PREVIEW -> credits
    }

    companion object {
        fun of(settings: ProfileSettings) = SkipModes(settings.skipIntros, settings.skipRecaps, settings.skipCredits)
    }
}

/**
 * The stream playing, as the info panel names it: [name] (the addon's `name` on one line, tidied as the stream list
 * shows it, else null), its [addonName], and how auto-play picked it, null when it didn't (the user or the stream
 * history did).
 *
 * @param downloaded it plays from its download on this device (the info panel says "Downloaded"); [name] and
 *   [addonName] are the stream it was downloaded from.
 */
data class PlayingStream(val addonName: String, val name: String?, val autoPlay: AutoPlayPick?, val downloaded: Boolean = false) {
    internal companion object {
        fun of(choice: StreamChoice, autoPlay: AutoPlayPick?, downloaded: Boolean = false) = PlayingStream(
            addonName = choice.addonName,
            name = StreamText.name(choice.stream.name),
            autoPlay = autoPlay,
            downloaded = downloaded,
        )
    }
}

/**
 * The next episode as up next's button shows it, formatted by the session: [title] is its name, or its code when it
 * has none; [subtitle] its code ("S1E2") when [title] is its name.
 * @param autoplay the profile plays the next episode on its own (binge watching); without it the button doesn't count
 *   down and the next episode only plays when asked.
 * @param countdownSeconds how long the button counts down before the next episode plays (the profile's
 *   [ProfileSetting.UpNextCountdownSeconds]); 0 plays it at once.
 */
data class NextEpisode(val title: String, val subtitle: String? = null, val autoplay: Boolean = true, val countdownSeconds: Int = 10)
