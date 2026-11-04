package com.edde746.tortuga.playback.session

import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.Chapter
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.ErrorVideoSign
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.MediaAccessibilityPreferences
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.PlayerEngineListener
import com.edde746.tortuga.playback.SkipSegments
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.resolve.ExpiryDecision
import com.edde746.tortuga.playback.resolve.ExpiryPolicy
import com.edde746.tortuga.playback.resolve.ExpiryState
import com.edde746.tortuga.playback.resolve.LivePlaylist
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.resolve.ResolveResult
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.resolve.StreamHeaders
import com.edde746.tortuga.playback.resolve.StreamResolver
import com.edde746.tortuga.playback.subtitles.ExternalSubtitle
import com.edde746.tortuga.playback.subtitles.FoundSubtitles
import com.edde746.tortuga.playback.subtitles.SubtitleResolver
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.playback.withoutSecrets
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Plays videos of one profile in one engine: the player screen's [PlayerSession].
 *
 * [start] takes a video and the picked stream, with the other streams to fall back on (each once):
 * - The stream resolves ([SessionInfo.resolving] while it does; at start alongside the reads of the profile's settings
 *   and the library; another attempt or [close] cancels it, its HTTP request included), then loads with the final
 *   URL, the addon's headers plus a `User-Agent`, and the resume point: the library's position for this video, unless
 *   it is past the credits ([CREDITS_THRESHOLD]). The load doesn't wait for the stream's hash: when it takes the tail
 *   request ([StreamResolver.resolveHead]), that runs while the engine opens the file, and the external subtitles'
 *   lookup and the file's report as open ([openedFile]) wait for it; a tail request that fails leaves the file without
 *   a hash.
 * - Each file loads with this device's decoding and cache options as they are when it loads ([PlaybackOptions]): a
 *   setting changed meanwhile applies from the next file (a retry, another stream, the next episode), never to the
 *   file playing.
 * - The engine draws subtitles in this device's subtitle style ([subtitleStyle]) from the start, and in a changed one
 *   as soon as it changes, on the file playing too.
 * - Once tracks are known, audio and subtitles are chosen by the profile's languages and Show subtitles setting (read
 *   as the file starts) and the system's caption and audio-description settings ([TrackChooser],
 *   [mediaAccessibility], chosen again on the file playing when those change); external subtitles ([SubtitleResolver])
 *   are added once the file is open, the best in the picked language (below) and the profile's; the others wait in
 *   [SessionInfo.moreSubtitles] until the user adds one ([addSubtitle]). A kind the user picks is left alone for the
 *   file, and the pick (subtitles off included) carries to the title's next files ([trackCarries]): the next or
 *   previous episode, another stream, a reload, the title played again in a later player. Each file takes the track
 *   that matches the pick best, chosen again as added subtitles arrive until the user picks in it; a file with nothing
 *   in the picked language follows the languages.
 * - The second subtitle (shown at the top of the picture) is chosen the same way, apart: the user's pick carried, else
 *   the profile's second subtitle language, never the first subtitle's track; the addons are asked for its language too.
 * - Once the file opened, the stream is remembered in the stream history.
 * - Progress: the library item is created or refreshed from the meta at start; progress is saved every
 *   [SessionConfig.progressIntervalMillis] while playing and on pause, seek, end, stream switch and [close], with
 *   whether the video counts as watched ([countsAsWatched]: the profile's watched threshold by position, or the start
 *   of the credits that run to the end when its chapters name them, [SkipSegments]), and once more the moment it comes
 *   to count, so the mark is in the library even if the app is killed next. Sync ([SyncTrigger]) pushes at once on
 *   pause, the end, a failure, leaving the video and crossing into watched, and 2 s after the last of a run of seeks;
 *   while playing, the engine holds the rest to its playing interval.
 * - An episode that counts as watched moves the library's pointer on in the write that counts it
 *   ([LibraryRepository.recordProgress], [com.edde746.tortuga.data.library.settled]): to the next unwatched episode at
 *   its start (stremio-core `advance_to_video`), which Continue Watching shows however playback then ends, or with none
 *   out yet the series leaves Continue Watching. Its progress and seeks aren't recorded after that. A rewatched episode
 *   leaves the pointer where it is. A movie stays where it is left until past its credits ([pastCredits]), where
 *   leaving takes it back to its start; an episode left there before it counts (a threshold past the credits) moves on
 *   all the same ([moveOn]).
 * - The profile's settings the player follows while the video plays (auto-play of the next episode, its countdown,
 *   the skipping modes, what a click on the video does) reach [SessionInfo] as they change.
 * - The next episode comes from `meta.videos` ([nextVideo]). Its stream is picked once the current file opened
 *   ([BingePicker]: the same binge group, else the stream history or, with auto-play on, auto-play's pick); with none,
 *   [playNextEpisode] asks through [SessionEvent.ChooseStream]. The previous episode ([previousVideo]) is picked the
 *   same way when [playPreviousEpisode] asks for it.
 * - A stream auto-play picked ([PlayRequest.autoPlay]) that fails before it ever opened asks for another the same way.
 * - Mid-play expiry follows [ExpiryPolicy]: an HTTP 403/404/410 after the file opened re-resolves the original URL
 *   once and resumes at the last position. That budget comes back after [SessionConfig.expiryResetMillis] of
 *   playback. Any other failure is surfaced in [SessionInfo.failure] without a URL in it; [retry] resolves the stream
 *   again and [playNextStream] moves to the next alternative, both at the last position.
 * - An end of the file is the stream failing, not the video's end, when the stream was cut short ([cutShort]): the
 *   engine logged an HTTP error for it, or it ended short of [CREDITS_THRESHOLD] of its length. [engineState] and
 *   every rule here then see the engine's failure ([EngineErrorKind.CUT_SHORT]) instead of the end, which [ExpiryPolicy]
 *   recovers from as from an expiry: nothing counts the video watched, tells observers it ended or plays the next
 *   episode, and the progress saved is where it stopped.
 * - An error video (an addon's clip of a message, sent instead of the video: [ErrorVideoSign]) is never played
 *   or recorded as the video. One the resolve tells (its final URL or its size) doesn't load; one the engine tells
 *   (its length against the length recorded for the video) is paused once the engine reports its length, which can be
 *   after its first frame ([judge]). Either is [SessionInfo.failure] ([PlaybackFailure.ErrorVideo]) with
 *   [SessionInfo.errorVideo] set, so the player shows it as an error and up next never follows it. [watchErrorVideo]
 *   plays it from its start for its message, recording nothing, and shows the error again at its end; [chooseStream]
 *   asks for another stream; [retry] resolves the stream again.
 * - One rule decides what a file records ([records]): a file of a request that isn't a probe or a trailer, once the
 *   engine showed it and its length was checked ([EngineFile.accepted]), and that isn't an error video. Nothing else
 *   writes progress, seeks, watched marks, the stored duration, the pointer's moves past the credits, the stream
 *   history or the resume point's move at start, or tells observers (the trackers). Watch together hears of every
 *   accepted file but a trailer's ([openedFile]): a guest's probe opens only for it to read the file's duration.
 * - Observers ([observe]; the trackers) hear each video's start once its file opened, its playing
 *   changes, its position (every second moved, and seeks) and its end, as [PlaybackObserver] events.
 * - A probe ([PlayPurpose.PROBE]: a watch-together guest's candidate, opened to read its duration) records none of the
 *   above: no library item or progress, no stream history, no observer events. [keepProbe] makes the open file a
 *   watched one from then on. A trailer ([PlayPurpose.TRAILER]) records nothing either, and isn't the video: it starts
 *   at 0, its length isn't checked against the video's, it is never [openedFile], it asks no subtitle addon and has no
 *   previous or next episode.
 * - A download ([PlayRequest.local]) plays its file: nothing resolves and nothing expires (an engine error is the
 *   failure, and [retry] loads the file again), its saved subtitles are added once it is open instead of the addons'
 *   ones, and the stream history keeps the stream it was downloaded from. Everything else is as for a stream. The next
 *   episode plays from its download when it has one ([LocalVideos]) before the binge pick, and the download playing is
 *   reported ([LocalVideos.playbackStarted]) so auto-remove leaves it alone.
 * - A live file ([SessionInfo.live]) saves nothing about its position. Its HLS playlist is read once it is accepted, and
 *   again every target duration until it ends ([LivePlaylist]): a playlist that reaches [LiveWindow.MIN_SECONDS] back
 *   is its rewind window ([SessionInfo.liveWindow]). [seekLive] moves within it, the edge being where an open lands
 *   ([LivePlaylist.join]): a seek ahead within what the engine read ahead short of its last segment, or back within
 *   what it holds read behind the position ([EngineState.cacheStartSeconds]), else the stream opened again at the
 *   segment that holds the point ([reopenLive]), the same file to the session (nothing recorded again; its tracks
 *   chosen again with the user's picks carried, its subtitles added again). The window, and whether a file is a live
 *   stream, are each file's [LiveFollower]'s.
 *
 * What it records goes through a [PlaybackRecorder], as a player app's hand-off does: library writes run one at a time
 * in call order, so progress, seeks and the pointer's moves land as they happened; [close] finishes them even after the
 * player screen is gone. Call everything on the main thread; the engine may call its listener from any thread.
 */
class PlaybackSession internal constructor(
    private val engine: PlayerEngine,
    private val profileId: String,
    private val resolver: StreamResolver,
    private val subtitles: SubtitleResolver,
    private val binge: BingePicker,
    private val library: LibraryRepository,
    streamHistory: StreamHistoryRepository,
    private val settings: SettingsRepository,
    private val playbackOptions: suspend () -> PlaybackOptions,
    private val subtitleStyle: Flow<SubtitleStyle>,
    /** The system's caption and audio-description settings ([com.edde746.tortuga.playback.SystemMediaAccessibility]). */
    private val mediaAccessibility: StateFlow<MediaAccessibilityPreferences>,
    private val installedAddons: suspend () -> List<Descriptor>,
    private val sync: SyncTrigger,
    private val config: SessionConfig,
    private val clock: Clock,
    parentScope: CoroutineScope,
    /** This device's downloads for the profile; null without downloads (Apple TV). */
    private val localVideos: LocalVideos? = null,
    /** The clock a live window's edge moves on with ([LiveWindow.joinedAt]). */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** The profile's hand picks: what came of a picked stream ([PlayRequest.pickId], [PickWatch]) and the next stream's pick. */
    private val picks: StreamPicks? = null,
) : PlayerSession {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private val mutableEngineState = MutableStateFlow(EngineState())
    override val engineState: StateFlow<EngineState> = mutableEngineState.asStateFlow()

    private val mutableInfo = MutableStateFlow(SessionInfo(title = ""))
    override val info: StateFlow<SessionInfo> = mutableInfo.asStateFlow()

    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()

    private val mutableOpenedFile = MutableStateFlow<OpenedFile?>(null)

    /**
     * The file open in the engine, once it opened; null between files and for a trailer (a watch-together host's room
     * follows it).
     */
    val openedFile: StateFlow<OpenedFile?> = mutableOpenedFile.asStateFlow()

    /** The engine the player store sends its commands to. */
    val playerEngine: PlayerEngine get() = engine

    /**
     * The user's track picks by title (meta id), carried to the title's next files ([TrackCarry]): this player's, and the
     * app's other players' while it runs ([com.edde746.tortuga.app.PlaybackController] hands them all the same map).
     */
    internal var trackCarries: MutableMap<String, TrackCarry> = mutableMapOf()

    private val states = Channel<EngineState>(Channel.UNLIMITED)
    private val recorder: PlaybackRecorder

    // What plays.
    private var request: PlayRequest? = null
    private var current: StreamChoice? = null
    private val alternatives = ArrayDeque<StreamChoice>()

    /** [current]'s download, while it plays from the device. */
    private var currentLocal: LocalVideo? = null

    /** The download reported playing ([LocalVideos.playbackStarted]). */
    private var playingDownload: Long? = null
    private var prefs: LanguagePreferences? = null
    private var nextVideo: Video? = null
    private var nextPick: Deferred<NextPick>? = null
    private var previousVideo: Video? = null

    /** The request's own stream opened in the engine at least once. */
    private var opened = false

    /** The next file loads paused ([PlayRequest.startPaused]): the first file of a request only, not a reload. */
    private var holdNextLoad = false
    private var openedSerial = 0

    /** The request is a probe not kept yet ([PlayPurpose.PROBE]): nothing is recorded for it. */
    private var probing = false

    /** The library's duration for the request's video, in seconds, when it has one ([ErrorVideoSign.ofDuration]). */
    private var knownDuration: Double? = null

    /** The resume point's move at start ([startSeek]), recorded once the request's file is accepted ([record]). */
    private var pendingStartSeek: PlaybackPosition? = null

    /** The error video the current stream sent ([showErrorVideo]), for [watchErrorVideo]. */
    private var sentErrorVideo: SentErrorVideo? = null

    /** The stream list was asked for after the request's auto-played stream failed to start. */
    private var askedForStream = false

    /** What the request's video records ([PlaybackRecorder.video]); null without a request. */
    private var recording: PlaybackRecorder.VideoRecording? = null

    /** What came of the stream playing, when it was picked by hand ([PlayRequest.pickId]); null otherwise. */
    private var pickWatch: PickWatch? = null

    /** A stall's count to a bad pick ([PickWatch.STALL]). */
    private var stall: Job? = null

    /** The profile's watched threshold ([countsAsWatched]), as its settings are now. */
    private var watchedThresholdPercent = ProfileSetting.WatchedThresholdPercent.default

    // The file in the engine, and the jobs around it.
    private var file: EngineFile? = null

    /** The load whose end was its stream cut short ([judgeEnd]); null before one. */
    private var cutShortLoad: Long? = null
    private var expiry: ExpiryState = ExpiryState.FirstResolution
    private var attempt: Job? = null
    private var ticker: Job? = null
    private var seekSettle: Job? = null
    private var seekFlush: Job? = null
    private var subtitleLookup: SubtitleLookup? = null
    private val styleUpdates: Job
    private val settingsUpdates: Job
    private val accessibilityUpdates: Job

    /** Where the current video is: the last position the engine reported, or where it was sent. */
    private var position = 0.0

    /** The file's length as the engine reported it: 0 until it does, from each load on ([load]). */
    private var duration = 0.0

    /** A seek or start position the engine hasn't reached yet; its reports are ignored until it does. */
    private var pendingPosition: Double? = null

    private var closed = false
    private var reportedPlaying = false

    // What observers were told about the current video.
    private var observedVideo = false
    private var observedPositionMillis = 0L

    init {
        engine.setListener(object : PlayerEngineListener {
            override fun onState(state: EngineState) {
                states.trySend(state)
            }
        })
        scope.launch {
            for (reported in states) {
                val state = judgeEnd(reported)
                mutableEngineState.value = state
                onEngineState(state)
            }
        }
        recorder = PlaybackRecorder(profileId, library, streamHistory, sync, config.progressIntervalMillis, scope, TAG)
        styleUpdates = scope.launch { subtitleStyle.distinctUntilChanged().collect(engine::setSubtitleStyle) }
        settingsUpdates = scope.launch { settings.observe(profileId).collect(::followSettings) }
        accessibilityUpdates = scope.launch { mediaAccessibility.drop(1).collect { chooseTracksAgain() } }
    }

    /**
     * Plays [request]: the video from its picked stream, falling back on its alternatives when asked. A video
     * already playing in this session is saved and left first.
     */
    fun start(request: PlayRequest) {
        check(!closed) { "the session is closed" }
        leaveVideo()
        begin(request)
    }

    /**
     * Shows [title] (and [subtitle], [artworkUrl]) while a stream is still to be found for it ([resolving]): a
     * watch-together guest's player while it looks for a file matching the host's. The video playing, if any, is
     * saved, paused and left first: the engine holds it until [start] plays the file once there is one.
     */
    fun prepare(title: String, subtitle: String?, artworkUrl: String?, resolving: Boolean) {
        if (closed) return
        leaveVideo()
        engine.setPaused(true)
        attempt?.cancel()
        request = null
        recording = null
        current = null
        currentLocal = null
        setPlayingDownload(null)
        alternatives.clear()
        nextVideo = null
        nextPick?.cancel()
        nextPick = null
        previousVideo = null
        mutableInfo.value = SessionInfo(
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            resolving = resolving,
            skipping = mutableInfo.value.skipping,
            videoClick = mutableInfo.value.videoClick,
        )
    }

    override fun retry() {
        val choice = current ?: return
        if (closed) return
        expiry = ExpiryState.FirstResolution
        val local = currentLocal
        restart { open(choice, local, position) }
    }

    override fun playNextStream() {
        if (closed) return
        val choice = alternatives.removeFirstOrNull() ?: return
        val request = request
        val from = current
        leavePick()
        // Going on to the next stream is a pick of it over the one that played (while suggestions record picks).
        if (picks != null && request != null && from != null && request.purpose == PlayPurpose.WATCH) {
            pickWatch = picks.playerPick(request.meta, request.videoId, from, choice, alternatives.toList())?.let(::watchPick)
        }
        switchStream(choice)
        restart { resolveAndLoad(choice, position) }
    }

    override fun chooseStream() {
        val request = request ?: return
        if (closed) return
        engine.setPaused(true)
        mutableEvents.tryEmit(SessionEvent.ChooseStream(request.meta, request.videoId, null))
    }

    override fun watchErrorVideo() {
        val sent = sentErrorVideo ?: return
        if (closed || current != sent.choice) return
        restart { load(sent.choice, sent.stream, 0.0, errorVideo = sent.sign) }
    }

    override fun playNextEpisode() {
        val request = request ?: return
        val next = nextVideo ?: return
        if (closed) return
        // Like stremio-core's NextVideo: the pointer moves to the next episode now, whatever the position.
        playEpisode(request, next, nextPick ?: pickEpisode(request, next), advance = true)
    }

    override fun playPreviousEpisode() {
        val request = request ?: return
        val previous = previousVideo ?: return
        if (closed) return
        // The library's pointer follows once the previous episode plays (its progress), not before.
        playEpisode(request, previous, pickEpisode(request, previous), advance = false)
    }

    /**
     * Leaves the video for [video], another episode of [request]'s series: played from [pick]'s request, else asked
     * for in the stream list ([SessionEvent.ChooseStream]) with the player paused. [advance] moves the library's
     * pointer to [video] at once.
     */
    private fun playEpisode(request: PlayRequest, video: Video, pick: Deferred<NextPick>, advance: Boolean) {
        saveProgress()
        stopObserved()
        if (advance && !probing) recorder.write { library.advanceToVideo(profileId, request.meta, video.id) }
        closeFile()
        updateInfo { it.copy(resolving = true, failure = null) }
        restart {
            val picked = pick.await()
            val nextRequest = picked.request
            if (nextRequest != null) {
                // A room's session opens every episode paused, as it opened the first.
                begin(nextRequest.copy(startPaused = request.startPaused))
            } else {
                updateInfo { it.copy(resolving = false) }
                engine.setPaused(true)
                val notice = if (picked.autoPlay) T.streams.autoPlay.noMatch else null
                mutableEvents.tryEmit(SessionEvent.ChooseStream(request.meta, video.id, notice))
            }
        }
    }

    override fun onSeek(seconds: Double) {
        val recording = recording ?: return
        if (file?.loaded != true || closed) return
        saveProgress()
        val target = if (duration > 0) seconds.coerceIn(0.0, duration) else seconds.coerceAtLeast(0.0)
        position = target
        expectPosition(target)
        if (observedVideo && records(file)) {
            val at = observedPosition()
            recorder.notifyObservers { onSeeked(at) }
        }
        if (!records(file) || recording.movedOn || live(file)) return
        recording.recordSeek(millis(target), millis(duration))
        // The new position goes to sync once the seeking stops, so a held seek key pushes once.
        seekFlush?.cancel()
        seekFlush = scope.launch {
            delay(SEEK_FLUSH_DELAY)
            recorder.write { sync.flushNow() }
        }
    }

    override fun onTrackSelected(kind: TrackKind, id: Long?) {
        val file = file ?: return
        val metaId = request?.meta?.id ?: return
        val track = mutableEngineState.value.tracks.firstOrNull { it.kind == kind && it.id == id }
        val carry = trackCarries[metaId] ?: TrackCarry()
        when (kind) {
            TrackKind.AUDIO -> {
                file.userAudio = true
                // The subtitles follow the audio the user hears now, unless picked too.
                file.audioId = id
                if (track != null) trackCarries[metaId] = carry.copy(audio = track)
            }
            TrackKind.SUBTITLE -> {
                file.userSubtitle = true
                file.subtitleId = id
                file.requestedSubtitle = null
                if (id == null || track != null) trackCarries[metaId] = carry.copy(subtitles = TrackCarry.Subtitles(track))
            }
            TrackKind.VIDEO -> {}
        }
    }

    override fun onSecondSubtitleSelected(id: Long?) {
        val file = file ?: return
        val metaId = request?.meta?.id ?: return
        val track = mutableEngineState.value.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.id == id }
        file.userSecondSubtitle = true
        file.secondSubtitleId = id
        if (id == null || track != null) {
            trackCarries[metaId] = (trackCarries[metaId] ?: TrackCarry()).copy(secondSubtitle = TrackCarry.Subtitles(track))
        }
    }

    override fun addSubtitle(url: String) {
        val file = file?.takeIf { it.subtitlesAdded } ?: return
        val lookup = subtitleLookup?.takeIf { it.choice == file.choice } ?: return
        val found = lookup.found ?: return
        val subtitle = found.more.firstOrNull { it.url == url } ?: return
        // Added for good: a reload of the stream adds it again.
        val now = FoundSubtitles(found.added + subtitle, found.more - subtitle)
        lookup.found = now
        updateInfo { it.copy(moreSubtitles = now.more) }
        file.userSubtitle = true
        file.requestedSubtitle = subtitle
        engine.addSubtitle(subtitle.url, subtitle.title, subtitle.language)
    }

    override fun setAutoPlayNext(enabled: Boolean) {
        if (closed) return
        updateInfo { it.copy(nextEpisode = it.nextEpisode?.copy(autoplay = enabled)) }
        recorder.write { settings.set(profileId, ProfileSetting.BingeWatching, enabled) }
    }

    override fun seekLive(behindSeconds: Double) {
        val file = file ?: return
        val window = mutableInfo.value.liveWindow ?: return
        if (closed) return
        when (val move = file.live.seek(window, behindSeconds, mutableEngineState.value.takeIf { it.loadId == file.loadId })) {
            is LiveMove.Seek -> engine.seek(move.seconds, exact = false)
            is LiveMove.Reopen -> reopenLive(file, move.behindSeconds)
            null -> {}
        }
    }

    /** Tells [observer] about each video's life in this session from now on (the trackers). */
    internal fun observe(observer: PlaybackObserver) = recorder.observe(observer)

    /**
     * Keeps the probe playing ([PlayPurpose.PROBE]) as the video watched: the library item is created or refreshed
     * from its meta and, once its file is open (now, or when it opens), it is recorded as any file is (the stream
     * history, progress, observers). Nothing happens when the request isn't a probe.
     */
    fun keepProbe() {
        val request = request ?: return
        val recording = recording ?: return
        if (!probing || closed || request.purpose != PlayPurpose.PROBE) return
        probing = false
        this.request = request.copy(purpose = PlayPurpose.WATCH)
        recording.saveItem()
        val file = file?.takeIf { it.accepted } ?: return
        record(request, file)
    }

    /**
     * Leaves the video as [start] does (its progress, a movie's reset past its credits), releases the engine and ends
     * the session. The last writes complete in the background.
     */
    fun close() {
        if (closed) return
        closed = true
        engine.setListener(null)
        styleUpdates.cancel()
        settingsUpdates.cancel()
        accessibilityUpdates.cancel()
        leaveVideo()
        setPlayingDownload(null)
        attempt?.cancel()
        nextPick?.cancel()
        engine.release()
        states.close()
        recorder.close()
    }

    // Starting and switching

    private fun begin(request: PlayRequest) {
        stopObserved()
        this.request = request
        pickWatch = request.pickId?.let(::watchPick)
        val meta = request.meta
        val video = meta.videos.firstOrNull { it.id == request.videoId }
        nextVideo = if (request.purpose == PlayPurpose.TRAILER) null else meta.nextVideo(request.videoId, clock.now())
        nextPick?.cancel()
        nextPick = null
        previousVideo = if (request.purpose == PlayPurpose.TRAILER) null else meta.previousVideo(request.videoId)
        expiry = ExpiryState.FirstResolution
        alternatives.clear()
        alternatives += request.alternatives.filter { it.playable && it != request.stream }.distinct()
        current = request.stream
        currentLocal = request.local
        setPlayingDownload(request.local?.downloadId)
        opened = false
        holdNextLoad = request.startPaused
        probing = request.purpose != PlayPurpose.WATCH
        askedForStream = false
        val recording = recorder.video(meta, request.videoId)
        this.recording = recording
        subtitleLookup = null
        sentErrorVideo = null
        knownDuration = null
        pendingStartSeek = null
        position = 0.0
        duration = 0.0
        mutableInfo.value = SessionInfo(
            live = meta.preview.isLive,
            title = meta.name.ifBlank { video?.title.orEmpty() },
            subtitle = if (request.purpose == PlayPurpose.TRAILER) listOfNotNull(video?.episodeCode(), T.detail.trailer).joinToString(" · ") else video?.episodeLine(),
            artworkUrl = listOf(video?.thumbnail, meta.preview.background, meta.preview.poster).firstOrNull { !it.isNullOrBlank() },
            posterUrl = meta.preview.poster?.takeIf { it.isNotBlank() },
            resolving = request.local == null,
            episode = request.purpose != PlayPurpose.TRAILER && meta.isEpisode(request.videoId),
            trailer = request.purpose == PlayPurpose.TRAILER,
            nextEpisode = null,
            hasPreviousEpisode = previousVideo != null,
            alternativeStreams = alternatives.size,
            stream = PlayingStream.of(request.stream, request.autoPlay, downloaded = request.local != null),
            skipping = mutableInfo.value.skipping,
            videoClick = mutableInfo.value.videoClick,
        )
        restart {
            // The stream resolves while the settings and the library are read: nothing here changes what it asks.
            val resolving = if (request.local == null) async { resolve(request.stream) } else null
            val loaded = settings.settings(profileId)
            prefs = LanguagePreferences.of(loaded)
            watchedThresholdPercent = loaded.watchedThresholdPercent
            nextVideo?.let { next -> updateInfo { it.copy(nextEpisode = nextEpisode(next, loaded)) } }
            updateInfo { it.copy(skipping = SkipModes.of(loaded), videoClick = loaded.videoClick) }
            val probe = request.purpose != PlayPurpose.WATCH
            if (!probe) recording.saveItem()
            val item = library.item(profileId, meta.id)
            val start = request.startPosition(item)
            // A trailer isn't the video: the video's length says nothing about it.
            knownDuration = item?.state?.takeIf { request.purpose != PlayPurpose.TRAILER && it.videoId == request.videoId && it.duration > 0 }?.let { it.duration / 1000.0 }
            // Starting over after the credits (or from another device's position): the resume point follows.
            if (!probe) pendingStartSeek = startSeek(item, request.videoId, start)
            position = start
            if (resolving == null) {
                open(request.stream, request.local, start)
            } else {
                loadResolved(request.stream, resolving.await(), start)
            }
        }
    }

    private fun switchStream(choice: StreamChoice) {
        current = choice
        currentLocal = null
        setPlayingDownload(null)
        expiry = ExpiryState.FirstResolution
        subtitleLookup = null
        sentErrorVideo = null
        nextPick?.cancel()
        nextPick = null
        updateInfo {
            it.copy(alternativeStreams = alternatives.size, failure = null, errorVideo = null, stream = PlayingStream.of(choice, autoPlay = null))
        }
    }

    /**
     * Leaves the current video: its last progress (a seek still settling counts where it was sent) and, past its
     * credits ([pastCredits]), the pointer's move on ([moveOn]: a movie back to its start, an episode that doesn't count
     * as watched yet under a threshold past the credits to the next one). An episode that counts as watched moved on
     * already.
     */
    private fun leaveVideo() {
        leavePick()
        val recording = recording ?: return
        stopObserved()
        val file = file
        if (file != null && records(file)) {
            pendingPosition = null
            saveProgress()
            recording.leave(pastCredits = !live(file) && pastCredits(position, duration, file.creditsStart), next = nextVideo)
        }
        closeFile()
    }

    /** The profile's settings changed (or were first read): what the player follows from them applies at once. */
    private fun followSettings(settings: ProfileSettings) {
        watchedThresholdPercent = settings.watchedThresholdPercent
        updateInfo { info ->
            info.copy(
                skipping = SkipModes.of(settings),
                videoClick = settings.videoClick,
                nextEpisode = info.nextEpisode?.copy(autoplay = settings.bingeWatching, countdownSeconds = settings.upNextCountdownSeconds),
            )
        }
    }

    /** Forgets the file in the engine: its reports no longer count. */
    private fun closeFile() {
        file?.hashing?.cancel()
        file?.live?.close()
        file?.lengthWait?.cancel()
        file = null
        mutableOpenedFile.value = null
        ticker?.cancel()
        ticker = null
        pendingPosition = null
        seekSettle?.cancel()
        seekFlush?.cancel()
        reportPlaying(false)
        updateInfo { it.copy(moreSubtitles = emptyList(), liveWindow = null) }
    }

    /** Tells sync when playback starts or stops, once per change. */
    private fun reportPlaying(playing: Boolean) {
        if (playing == reportedPlaying) return
        reportedPlaying = playing
        sync.setPlaying(playing)
    }

    private fun restart(block: suspend CoroutineScope.() -> Unit) {
        attempt?.cancel()
        attempt = scope.launch(block = block)
    }

    /** Tells the downloads which one plays now, if any ([LocalVideos.playbackStarted]). */
    private fun setPlayingDownload(downloadId: Long?) {
        val previous = playingDownload
        if (previous == downloadId) return
        playingDownload = downloadId
        val downloads = localVideos ?: return
        previous?.let(downloads::playbackEnded)
        downloadId?.let(downloads::playbackStarted)
    }

    // Resolving and loading

    /** Opens [choice] at [startAt]: from the device when it is [local]'s, else resolved first. */
    private suspend fun open(choice: StreamChoice, local: LocalVideo?, startAt: Double) {
        if (local != null) load(choice, local.resolved(), startAt, local) else resolveAndLoad(choice, startAt)
    }

    private suspend fun resolveAndLoad(choice: StreamChoice, startAt: Double) =
        loadResolved(choice, resolve(choice), startAt)

    /** Resolves [choice] ([SessionInfo.resolving] while it does); cancelling the attempt cancels it. */
    private suspend fun resolve(choice: StreamChoice): ResolveResult {
        val streamRequest = choice.request() ?: return ResolveResult.Failed(ResolveFailure.UnsupportedUrl)
        sentErrorVideo = null
        updateInfo { it.copy(resolving = true, failure = null, errorVideo = null) }
        return resolver.resolveHead(streamRequest)
    }

    /** Loads [choice] as [result] resolved it, or shows why it couldn't. */
    private suspend fun loadResolved(choice: StreamChoice, result: ResolveResult, startAt: Double) {
        when (result) {
            is ResolveResult.Resolved -> load(choice, result.stream, startAt, pendingHash = result.pendingHash)
            is ResolveResult.Failed -> fail(PlaybackFailure.Resolve(result.failure))
        }
    }

    /**
     * Opens [stream] at [startAt], unless the resolve found it is an error video ([showErrorVideo]); [errorVideo] opens
     * one anyway, for its message ([watchErrorVideo]): it is never recorded.
     */
    private suspend fun load(
        choice: StreamChoice,
        stream: ResolvedStream,
        startAt: Double,
        local: LocalVideo? = null,
        pendingHash: (suspend () -> String?)? = null,
        errorVideo: ErrorVideoSign? = null,
    ) {
        // Read before anything about the file changes: a cancelled attempt leaves the session as it was.
        val options = playbackOptions()
        val request = request ?: return
        if (errorVideo == null) stream.errorVideo?.let { return showErrorVideo(SentErrorVideo(choice, stream, it, startAt)) }
        val last = mutableEngineState.value
        closeFile()
        val live = LiveFollower(resolver, stream, config.userAgent, scope, timeSource, ::showLiveWindow)
        val file = EngineFile(choice, stream, local, options, startAt = startAt, errorVideo = errorVideo, live = live)
        this.file = file
        position = startAt
        duration = 0.0
        if (startAt > 0) expectPosition(startAt)
        updateInfo { it.copy(resolving = false, failure = null, live = live(file), judging = true) }
        val hold = holdNextLoad
        holdNextLoad = false
        file.loadId = engine.load(loadRequest(request, file, startAt))
        // mpv keeps `pause` across files: a new file starts playing, unless the request's first file opens paused.
        // Written after the load: at the end of a file mpv (keep-open) pauses again on its own until a load replaces
        // it, so a play written before the load could be undone and carried into the next file paused.
        if (hold) engine.setPaused(true) else if (last.paused) engine.setPaused(false)
        // A download's subtitles were saved with it: the addons aren't asked; nor for an error video's message.
        when {
            errorVideo != null -> {}
            pendingHash != null -> hash(file, pendingHash)
            local == null -> lookUpSubtitles(request, choice, stream)
        }
    }

    /**
     * [file]'s hash, asked for ([pendingHash]: the tail request) while the engine opens the file; then its subtitles
     * are looked up, and it is reported open once it is. Leaving the file cancels it ([closeFile]).
     */
    private fun hash(file: EngineFile, pendingHash: suspend () -> String?) {
        // Started once it is the file's, so its end always finds itself there.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val hash = pendingHash()
            if (this@PlaybackSession.file !== file) return@launch
            file.resolved = file.resolved.copy(videoHash = hash)
            file.hashing = null
            val request = request ?: return@launch
            lookUpSubtitles(request, file.choice, file.resolved)
            if (file.accepted) reportOpened(request, file)
        }
        file.hashing = job
        job.start()
    }

    private fun lookUpSubtitles(request: PlayRequest, choice: StreamChoice, stream: ResolvedStream) {
        if (subtitleLookup?.choice == choice) return
        val prefs = prefs ?: return
        val lookup = SubtitleLookup(choice)
        subtitleLookup = lookup
        val query = request.subtitleRequest(choice, stream)
        // A language the user picked in another file of the title is the most wanted one: carried() finds it then.
        val carry = trackCarries[request.meta.id]
        val picked = listOf(carry?.subtitles, carry?.secondSubtitle).mapNotNull { it?.track?.language?.let(LanguageTags::canonical) }
        val wanted = (picked + prefs.subtitleWanted).distinct()
        scope.launch {
            val addons = if (request.purpose == PlayPurpose.TRAILER) emptyList() else readingAddons(TAG) { installedAddons() }.orEmpty()
            val found = subtitles.resolve(query, addons, wanted)
            if (subtitleLookup !== lookup) return@launch
            lookup.found = found
            file?.takeIf { it.loaded }?.let(::addSubtitles)
        }
    }

    private fun addSubtitles(file: EngineFile) {
        if (file.subtitlesAdded) return
        val local = file.local
        if (local != null) {
            file.subtitlesAdded = true
            for (subtitle in local.subtitles) engine.addSubtitle(subtitle.path, subtitle.title, subtitle.language)
            return
        }
        val found = subtitleLookup?.takeIf { it.choice == file.choice }?.found ?: return
        file.subtitlesAdded = true
        for (subtitle in found.added) engine.addSubtitle(subtitle.url, subtitle.title, subtitle.language)
        updateInfo { it.copy(moreSubtitles = found.more) }
    }

    // The engine's reports

    /**
     * [state] as the session takes it: the current file's end, when its stream was [cutShort], as the engine's failure
     * ([EngineErrorKind.CUT_SHORT], with the HTTP status the engine logged) instead of an end. The engine stays at that
     * end after the session left the file, so every later report of that load ([cutShortLoad]) is the failure too.
     */
    private fun judgeEnd(state: EngineState): EngineState {
        if (!state.eof) return state
        if (state.loadId != cutShortLoad) {
            val file = file
            if (file == null || state.loadId != file.loadId || !cutShort(file, state)) return state
            cutShortLoad = state.loadId
        }
        val why = state.httpErrorStatus?.let { " after HTTP error $it" }.orEmpty()
        val message = "the file ended at ${state.positionSeconds} s of ${state.durationSeconds} s$why"
        return state.copy(eof = false, error = EngineError(message, state.httpErrorStatus, EngineErrorKind.CUT_SHORT))
    }

    /**
     * Whether [file]'s end ([state]) is its stream cut short: FFmpeg ends a read the server refused, or a connection
     * that closed early, as the file's end. So an end after an HTTP error the engine logged for the stream, wherever it
     * comes (a seek the dead link refused ends at the length), is the stream failing. So is one short of
     * [CREDITS_THRESHOLD] of the length: a length a container gets wrong runs past the last frame by seconds (a
     * subtitle or audio track that ends later), by a few percent where it is estimated from the bitrate, and past that
     * threshold the video counts as finished anyway (a movie left there starts over). An unknown length says nothing,
     * and a file that ended without an HTTP error within [ENDED_AT_START_SECONDS] of where it started ends there,
     * whatever its length says: the fresh link's reload at the point where the last one stopped found the file's real
     * end. A download has no link to fail, an error video opened for its message plays to its own end, and a live
     * stream's end is the stream's.
     */
    private fun cutShort(file: EngineFile, state: EngineState): Boolean {
        if (file.local != null || file.errorVideo != null || live(file)) return false
        if (state.httpErrorStatus != null) return true
        val at = state.positionSeconds
        if (file.startAt > 0 && at - file.startAt < ENDED_AT_START_SECONDS) return false
        return state.durationSeconds > 0 && at < state.durationSeconds * CREDITS_THRESHOLD
    }

    private fun onEngineState(state: EngineState) {
        val file = file ?: return
        // An earlier load's reports (the last file's, or this file's before a live reopen) until the engine takes this one.
        if (state.loadId != file.loadId) return
        val error = state.error
        if (error != null) {
            if (file.errorHandledIn != state.loadId) {
                file.errorHandledIn = state.loadId
                onEngineError(file, error)
            }
            return
        }
        if (!state.loaded) return
        followPosition(state)
        followChapters(file, state)
        if (!file.loaded) onLoaded(file)
        if (!file.judged) judge(file, state)
        // Judged an error video: the session let it go.
        if (this.file !== file) return
        // Live once the engine's reports say so; a live file's window is read once it is accepted.
        if (file.accepted && file.live.detect(state)) updateInfo { it.copy(live = true) }
        if (file.accepted && live(file) && file.local == null) file.live.followWindow(state)
        followPlayback(file, state)
        saveOnceWatched(file)
        observePosition()
        chooseTracks(file, state.tracks)
    }

    private fun onLoaded(file: EngineFile) {
        file.loaded = true
        updateInfo { it.copy(resolving = false, failure = null) }
        addSubtitles(file)
    }

    /**
     * Checks [file]'s length once the engine shows it (its first frame, or its end when it opened past it): an error
     * video by its length ([ErrorVideoSign.ofDuration]) is let go and shown as one ([showErrorVideo]); any other file
     * is accepted ([accept]). An error video opened for its message ([watchErrorVideo]) is neither.
     *
     * Only a length the engine reported for this file counts: mpv hands its client queued events (`file-loaded`,
     * `playback-restart`) before property changes, so the report of the first frame can come before the file's
     * duration. Where a length can tell ([ErrorVideoSign.lengthTells]: the library has the video at
     * [ErrorVideoSign.MIN_KNOWN_SECONDS] or more), the file plays on, neither recorded nor reported open, until the
     * engine reports one, and is accepted without one after [LENGTH_WAIT]: every known error video has a length mpv
     * knows as it opens the file, so a file still without one then is a live stream or another file mpv can't measure.
     * From the load until the check, [SessionInfo.judging] holds: up next waits for it, since an error video opened
     * past its end (at the video's resume point) shows its length and end before its first frame.
     */
    private fun judge(file: EngineFile, state: EngineState) {
        if (state.playbackRestarts == 0 && !state.eof) return
        if (file.errorVideo != null) {
            file.judged = true
            updateInfo { it.copy(judging = false) }
            return
        }
        val length = state.durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (length == null && ErrorVideoSign.lengthTells(knownDuration)) {
            if (file.lengthWait == null) {
                file.lengthWait = scope.launch {
                    delay(LENGTH_WAIT)
                    if (this@PlaybackSession.file === file && !file.judged) accept(file)
                }
            }
            return
        }
        ErrorVideoSign.ofDuration(length ?: 0.0, knownDuration)?.let {
            file.judged = true
            return showErrorVideo(SentErrorVideo(file.choice, file.resolved, it, file.startAt))
        }
        accept(file)
    }

    /**
     * [file] plays the request's video ([EngineFile.accepted]): reported open unless a trailer, and recorded unless a
     * probe or a trailer.
     */
    private fun accept(file: EngineFile) {
        val request = request ?: return
        file.judged = true
        updateInfo { it.copy(judging = false) }
        file.lengthWait?.cancel()
        file.accepted = true
        if (file.choice == request.stream) opened = true
        if (file.hashing == null) reportOpened(request, file)
        if (!probing) record(request, file)
    }

    /** The open file's live window ([LiveFollower]) as [SessionInfo.liveWindow]. */
    private fun showLiveWindow(window: LiveWindow?) {
        if (mutableInfo.value.liveWindow != window) updateInfo { it.copy(liveWindow = window) }
    }

    /**
     * Opens [file]'s live stream again [behindSeconds] behind the edge, at the segment that holds that point
     * ([LiveFollower.reopen]). It stays the session's file: nothing is recorded again, its tracks are chosen again (the
     * user's picks carried) and its subtitles added again once it opens.
     */
    private fun reopenLive(file: EngineFile, behindSeconds: Double) {
        val request = request ?: return
        val join = file.live.reopen(behindSeconds) ?: return
        file.loaded = false
        file.subtitlesAdded = false
        file.tracks = emptyList()
        file.userAudio = false
        file.userSubtitle = false
        file.audioChosenFrom = null
        file.subtitlesChosenFrom = null
        file.loadId = engine.load(loadRequest(request, file, startAt = 0.0, liveStartIndex = -join.segmentsFromEnd))
    }

    /**
     * The engine's request for [file] of [request] from [startAt]; [liveStartIndex] opens a live one at that segment.
     * It is live ([LoadRequest.live]) when the title is, or once this file played live (a rewind's reopen).
     */
    private fun loadRequest(request: PlayRequest, file: EngineFile, startAt: Double, liveStartIndex: Int? = null): LoadRequest {
        val local = file.local
        return LoadRequest(
            url = file.resolved.url,
            options = file.options,
            title = request.playerTitle(),
            headers = if (local != null) emptyMap() else file.resolved.playerHeaders(config.userAgent),
            startSeconds = startAt,
            demuxerFormat = if (local != null) null else StreamHeaders.demuxerFormat(file.choice.stream.behaviorHints.proxyHeaders?.response.orEmpty()),
            liveStartIndex = liveStartIndex,
            live = request.meta.preview.isLive || file.live.detected,
        )
    }

    /**
     * [file] as the one open ([openedFile]): once it is loaded and its hash, if one was asked for, is in. Never a
     * trailer's: watch together's room doesn't follow one.
     */
    private fun reportOpened(request: PlayRequest, file: EngineFile) {
        if (request.purpose == PlayPurpose.TRAILER) return
        mutableOpenedFile.value = OpenedFile(request.meta, request.videoId, file.choice, file.resolved, ++openedSerial, file.local)
    }

    /**
     * What a watched file records once open: its start to observers, its stream in the stream history, its progress
     * (now and every [SessionConfig.progressIntervalMillis] while playing), and the next episode's pick.
     */
    private fun record(request: PlayRequest, file: EngineFile) {
        val recording = recording ?: return
        pendingStartSeek?.let { seek ->
            pendingStartSeek = null
            if (!live(file)) recording.recordSeek(seek.positionMillis, seek.durationMillis)
        }
        if (!observedVideo) {
            observedVideo = true
            observedPositionMillis = millis(position)
            val at = observedPosition()
            recorder.notifyObservers { onVideoStarted(request.meta, request.videoId, at, file.playing) }
        }
        if (!file.remembered) {
            file.remembered = true
            recording.recordStream(file.choice)
        }
        saveProgress()
        ticker?.cancel()
        ticker = recorder.progressTicker { if (file.playing) saveProgress() }
        val next = nextVideo
        if (next != null && nextPick == null) nextPick = pickEpisode(request, next)
    }

    private fun followPosition(state: EngineState) {
        if (state.durationSeconds.isFinite() && state.durationSeconds > 0) duration = state.durationSeconds
        val reported = state.positionSeconds.takeIf { it.isFinite() && it >= 0 } ?: return
        val expected = pendingPosition
        if (expected != null) {
            if (abs(reported - expected) > SEEK_SETTLED_SECONDS) return
            pendingPosition = null
            seekSettle?.cancel()
        }
        position = reported
    }

    /** Where the file's credits to the end start, as its chapters (once known) say. */
    private fun followChapters(file: EngineFile, state: EngineState) {
        if (state.chapters == file.chapters && duration == file.chaptersDuration) return
        file.chapters = state.chapters
        file.chaptersDuration = duration
        file.creditsStart = SkipSegments.creditsStart(SkipSegments.of(state.chapters, duration))
    }

    private fun followPlayback(file: EngineFile, state: EngineState) {
        // An error video's message played to its end: the error again, not up next.
        if (state.eof && file.errorVideo != null) sentErrorVideo?.let { return showErrorVideo(it) }
        if (state.eof && !file.ended) {
            file.ended = true
            if (observedVideo) {
                val at = observedPosition()
                recorder.notifyObservers { onEnded(at) }
            }
            if (duration > 0) position = duration
        } else if (!state.eof) {
            file.ended = false
        }
        val playing = !state.paused && !state.eof
        if (playing != file.playing) {
            file.playing = playing
            reportPlaying(playing)
            if (!playing) saveProgress(flush = true)
            if (observedVideo) {
                val at = observedPosition()
                recorder.notifyObservers { onPlayingChanged(playing, at) }
            }
        }
        followPick(state, playing)
        // Healthy playback after a re-resolve gives the expiry budget back.
        if (expiry == ExpiryState.ReResolved && playing && !state.buffering) {
            val played = position - file.healthyAt
            if (played > 0 && played <= HEALTHY_STEP_SECONDS) file.healthySeconds += played
            if (file.healthySeconds * 1000 >= config.expiryResetMillis) expiry = ExpiryState.FirstResolution
        }
        file.healthyAt = position
    }

    /** The watch of the pick [pickId]: what comes of it goes to [picks]. */
    private fun watchPick(pickId: String) = PickWatch { outcome -> picks?.outcome(pickId, outcome) }

    /** What the picked stream playing does: plays on, ends, or stalls ([PickWatch.STALL]) while it should play. */
    private fun followPick(state: EngineState, playing: Boolean) {
        val watch = pickWatch?.takeUnless { it.settled } ?: return
        if (state.eof) return watch.ended()
        if (playing && !state.buffering) watch.playing(position) else watch.held()
        if (state.buffering && !state.paused) {
            if (stall == null) {
                stall = scope.launch {
                    delay(PickWatch.STALL)
                    watch.failed()
                }
            }
        } else {
            stall?.cancel()
            stall = null
        }
    }

    /** The picked stream playing is left: another stream, another video, or the player closes. */
    private fun leavePick() {
        pickWatch?.left()
        pickWatch = null
        stall?.cancel()
        stall = null
    }

    /**
     * Chooses [file]'s audio and subtitles from the engine's track list ([tracks]), each kind until the user picks one.
     * A kind is chosen again when its tracks change, not when only their selection does: the engine's first report of a
     * file can come before its tracks (mpv hands its client queued events before property changes), and the file's own
     * follow.
     */
    private fun chooseTracks(file: EngineFile, tracks: List<Track>) {
        val prefs = prefs ?: return
        if (tracks.isEmpty() || tracks == file.tracks) return
        file.tracks = tracks
        val system = mediaAccessibility.value
        // The user's picks in another file of this title come first; what they don't settle follows the languages.
        val carry = request?.meta?.id?.let(trackCarries::get)
        val audioTracks = tracks.ofKind(TrackKind.AUDIO)
        if (!file.userAudio && audioTracks.isNotEmpty() && audioTracks != file.audioChosenFrom) {
            file.audioChosenFrom = audioTracks
            val carried = carry?.audio?.let { TrackChooser.carried(tracks, it) }
            val audio = (carried ?: TrackChooser.audio(tracks, prefs.audio, descriptions = system.audioDescriptions))?.id
            if (audio != null && tracks.none { it.kind == TrackKind.AUDIO && it.id == audio && it.selected }) {
                engine.selectTrack(TrackKind.AUDIO, audio)
            }
            file.audioId = audio
        }
        file.requestedSubtitle?.let { requested ->
            // The subtitle the user added: selected as the engine lists it, and carried as a pick of theirs.
            val added = tracks.lastOrNull {
                it.kind == TrackKind.SUBTITLE && it.external && it.title == requested.title && LanguageTags.canonical(it.language) == requested.language
            } ?: return
            file.requestedSubtitle = null
            file.subtitleId = added.id
            engine.selectTrack(TrackKind.SUBTITLE, added.id)
            request?.meta?.id?.let { trackCarries[it] = (carry ?: TrackCarry()).copy(subtitles = TrackCarry.Subtitles(added)) }
        }
        // Captions: the profile shows subtitles always, or the system's captions are on.
        val captions = prefs.alwaysSubtitles || system.captions
        if (!file.userSubtitle) chooseSubtitle(file, tracks, carry, captions)
        chooseSecondSubtitle(file, tracks, carry, captions)
    }

    /** [chooseTracks]' first subtitle, until the user picks one. */
    private fun chooseSubtitle(file: EngineFile, tracks: List<Track>, carry: TrackCarry?, captions: Boolean) {
        val prefs = prefs ?: return
        fun preferred(): Long? {
            val audio = tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.id == file.audioId }
                ?: tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.selected }
            return TrackChooser.subtitle(tracks, audio?.language, prefs.subtitles, captions)?.id
        }
        val picked = carry?.subtitles
        val wanted: Long? = when {
            picked == null -> preferred()
            picked.track == null -> null
            // Added subtitles arrive after the file opens: the choice is made again as they do.
            else -> TrackChooser.carried(tracks, picked.track)?.id ?: preferred()
        }
        val subtitleTracks = tracks.ofKind(TrackKind.SUBTITLE)
        if (wanted == file.subtitleId && subtitleTracks == file.subtitlesChosenFrom) return
        file.subtitlesChosenFrom = subtitleTracks
        file.subtitleId = wanted
        if (tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.selected }?.id == wanted) return
        // mpv refuses the second subtitle's track as the first: the second lets it go, and is chosen again after.
        if (wanted != null && tracks.any { it.kind == TrackKind.SUBTITLE && it.id == wanted && it.secondary }) {
            engine.selectSecondSubtitle(null)
            file.secondSubtitleId = null
            file.secondChosenFrom = null
        }
        engine.selectTrack(TrackKind.SUBTITLE, wanted)
    }

    /**
     * [chooseTracks]' second subtitle, until the user picks one: the pick carried from another file of the title, else
     * the profile's second subtitle language ([TrackChooser.second]); never the first subtitle's track.
     */
    private fun chooseSecondSubtitle(file: EngineFile, tracks: List<Track>, carry: TrackCarry?, captions: Boolean) {
        val prefs = prefs ?: return
        if (file.userSecondSubtitle) return
        val first = file.subtitleId
        val chosenFrom = tracks.ofKind(TrackKind.SUBTITLE) to first
        if (chosenFrom == file.secondChosenFrom) return
        file.secondChosenFrom = chosenFrom
        val others = tracks.filter { it.kind == TrackKind.SUBTITLE && it.id != first }
        val picked = carry?.secondSubtitle
        val wanted: Long? = when {
            picked == null -> TrackChooser.second(others, prefs.secondSubtitle, captions)?.id
            picked.track == null -> null
            else -> (TrackChooser.carried(others, picked.track) ?: TrackChooser.second(others, prefs.secondSubtitle, captions))?.id
        }
        file.secondSubtitleId = wanted
        if (tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.secondary }?.id != wanted) engine.selectSecondSubtitle(wanted)
    }

    /**
     * The system's caption or audio-description settings changed: the open file's tracks are chosen again from its
     * last track list, as at its start; a kind the user picked in it stays as picked.
     */
    private fun chooseTracksAgain() {
        val file = file ?: return
        val tracks = file.tracks
        file.tracks = emptyList()
        file.audioChosenFrom = null
        file.subtitlesChosenFrom = null
        file.secondChosenFrom = null
        chooseTracks(file, tracks)
    }

    /** The tracks of [kind], their selection left out. */
    private fun List<Track>.ofKind(kind: TrackKind): List<Track> = filter { it.kind == kind }.map { it.copy(selected = false, secondary = false) }

    // Failures

    private fun onEngineError(file: EngineFile, error: EngineError) {
        val shown = error.withoutSecrets()
        Log.w(TAG) { "engine error (${error.kind}, HTTP ${error.httpStatus}): ${error.message}" }
        // A file on the device has no link to expire: its error is the failure.
        if (file.local != null) return fail(PlaybackFailure.Engine(shown))
        val step = ExpiryPolicy.onEngineError(expiry, shown, loaded = file.loaded, lastPositionSeconds = position)
        expiry = step.state
        when (val decision = step.decision) {
            is ExpiryDecision.ReResolve -> {
                saveProgress()
                closeFile()
                val choice = file.choice
                updateInfo { it.copy(resolving = true) }
                restart { reResolve(choice) }
            }
            is ExpiryDecision.Surface -> fail(decision.failure)
            ExpiryDecision.Ignore, is ExpiryDecision.Reload -> {}
        }
    }

    private suspend fun reResolve(choice: StreamChoice) {
        val result = resolve(choice)
        val step = ExpiryPolicy.onReResolved(expiry, result)
        expiry = step.state
        when (val decision = step.decision) {
            is ExpiryDecision.Reload -> {
                val pendingHash = (result as? ResolveResult.Resolved)?.pendingHash
                load(choice, decision.stream, decision.resumeAtSeconds, pendingHash = pendingHash)
            }
            is ExpiryDecision.Surface -> fail(decision.failure)
            ExpiryDecision.Ignore, is ExpiryDecision.ReResolve -> {}
        }
    }

    private fun fail(failure: PlaybackFailure) {
        pickWatch?.failed()
        stall?.cancel()
        stall = null
        saveProgress(flush = true)
        if (observedVideo && file?.playing == true) {
            val at = observedPosition()
            recorder.notifyObservers { onPlayingChanged(false, at) }
        }
        closeFile()
        updateInfo { it.copy(resolving = false, failure = failure, alternativeStreams = alternatives.size, judging = false) }
        val request = request ?: return
        if (request.autoPlay != null && !opened && !askedForStream && current == request.stream) {
            // Auto-play's pick didn't start: the stream list opens over the player, as for an episode without one.
            askedForStream = true
            mutableEvents.tryEmit(SessionEvent.ChooseStream(request.meta, request.videoId, T.streams.autoPlay.failed))
        }
    }

    /**
     * [sent] came instead of the video: the engine pauses (an error video it opened stays where it is), the position is
     * the resume point again, and the error shows ([PlaybackFailure.ErrorVideo]) until a retry, another stream or
     * [watchErrorVideo]. Nothing about it was recorded ([records]).
     */
    private fun showErrorVideo(sent: SentErrorVideo) {
        val request = request ?: return
        Log.w(TAG) { "the stream sent an error video: ${sent.sign}" }
        sentErrorVideo = sent
        position = sent.resumeAt
        engine.setPaused(true)
        fail(PlaybackFailure.ErrorVideo(sent.sign, isEpisode = request.meta.isEpisode(request.videoId), isMovie = request.meta.type == "movie"))
        updateInfo { it.copy(errorVideo = sent.sign) }
    }

    // Progress

    /**
     * Queues a progress write at the current position, with whether it counts the video as watched; with [flush], the
     * sync engine pushes it right after. An episode that comes to count as watched moves the library's pointer on in
     * that write, and nothing more is recorded.
     */
    private fun saveProgress(flush: Boolean = false) {
        val recording = recording ?: return
        val file = file?.takeIf { records(it) && !live(it) } ?: return
        if (pendingPosition != null) return
        recording.saveProgress(millis(position), millis(duration), watchedThresholdPercent, file.creditsStartMillis, flush)
    }

    /**
     * Whether [file] records what it plays: the one rule for every write a file makes (progress, seeks, watched marks,
     * the stored duration, the pointer's moves, the stream history, observers and watch together). A probe or a trailer
     * ([probing]) records nothing, nor does a file before the engine showed it and its length was checked, nor an
     * error video ([EngineFile.accepted]). A live one ([live]) records its stream history and its observers' events only.
     */
    private fun records(file: EngineFile?): Boolean = !probing && file?.accepted == true

    /**
     * Whether [file] plays live ([SessionInfo.live]): the title is live ([com.edde746.tortuga.model.MetaItemPreview.isLive]),
     * or the engine's reports say the file is a live stream ([LiveFollower.detect]). Nothing about its position is
     * saved: no progress, resume point, watched mark or move on past the credits.
     */
    private fun live(file: EngineFile?): Boolean = request?.meta?.preview?.isLive == true || file?.live?.detected == true

    /** Saves the progress the moment the video comes to count as watched, by playing or by a seek. */
    private fun saveOnceWatched(file: EngineFile) {
        if (recording?.comesToCount(millis(position), millis(duration), watchedThresholdPercent, file.creditsStartMillis) == true) saveProgress()
    }

    /** Reports the position to observers once it moved a second or more, and not while a seek is settling. */
    private fun observePosition() {
        if (!observedVideo || pendingPosition != null) return
        val now = millis(position)
        if (abs(now - observedPositionMillis) < PlaybackRecorder.OBSERVED_POSITION_STEP_MILLIS) return
        observedPositionMillis = now
        val at = observedPosition()
        recorder.notifyObservers { onPosition(at) }
    }

    /** Tells observers the current video was left, once. */
    private fun stopObserved() {
        if (!observedVideo) return
        observedVideo = false
        val at = observedPosition()
        recorder.notifyObservers { onVideoStopped(at) }
    }

    private fun observedPosition() = PlaybackPosition(millis(position), millis(duration), file?.creditsStartMillis)

    private fun expectPosition(seconds: Double) {
        pendingPosition = seconds
        seekSettle?.cancel()
        seekSettle = scope.launch {
            delay(SEEK_SETTLE_TIMEOUT)
            pendingPosition = null
        }
    }

    /**
     * [video]'s stream, from its download or the binge pick ([BingePicker]) after the stream playing; a lookup that
     * starts now and runs on its own.
     */
    private fun pickEpisode(request: PlayRequest, video: Video): Deferred<NextPick> {
        val choice = current ?: request.stream
        return scope.async {
            val profile = settings.settings(profileId)
            val local = localVideos?.localVideo(request.meta.id, video.id)
            val picked = if (local != null) {
                localPlayRequest(request.meta, video.id, local)
            } else {
                binge.pick(profileId, request.meta, video, choice, readingAddons(TAG) { installedAddons() }.orEmpty(), profile)
            }
            NextPick(picked, autoPlay = profile.streamAutoPlay != AutoPlayMode.OFF)
        }
    }

    /** What plays an episode: [request], or none; [autoPlay] was on and found nothing either. */
    private class NextPick(val request: PlayRequest?, val autoPlay: Boolean)

    private inline fun updateInfo(change: (SessionInfo) -> SessionInfo) {
        mutableInfo.value = change(mutableInfo.value)
    }

    private fun nextEpisode(next: Video, settings: ProfileSettings): NextEpisode {
        val code = next.episodeCode()
        val title = next.title.trim().takeIf { it.isNotEmpty() }
        return NextEpisode(
            title = title ?: code.orEmpty(),
            subtitle = code.takeIf { title != null },
            autoplay = settings.bingeWatching,
            countdownSeconds = settings.upNextCountdownSeconds,
        )
    }

    /**
     * One file handed to the engine, and what the session did with it; [local] when it is a download's, [errorVideo]
     * when it is an error video opened for its message ([watchErrorVideo]). [startAt] is where it was asked to start.
     */
    private class EngineFile(
        val choice: StreamChoice,
        var resolved: ResolvedStream,
        val local: LocalVideo?,
        /** The decoding and cache options it loaded with, which a live reopen keeps. */
        val options: PlaybackOptions,
        val startAt: Double,
        val errorVideo: ErrorVideoSign?,
        /** Its live side: whether it is a live stream, and its rewind window. */
        val live: LiveFollower,
    ) {
        /** The engine's id of its load ([PlayerEngine.load]), again at each live reopen: the reports that are its own. */
        var loadId = 0L
        var loaded = false

        /** [judge] decided on it: accepted, let go as an error video, or one opened for its message. */
        var judged = false

        /** Waits for its length after its first frame while one can tell an error video ([judge]), then accepts it. */
        var lengthWait: Job? = null

        /** It plays the request's video: shown by the engine, its length checked, no error video ([records]). */
        var accepted = false

        /** The load whose error the session handled: once per load, however often the engine reports it. */
        var errorHandledIn = 0L
        var remembered = false
        var playing = false
        var ended = false
        var healthySeconds = 0.0
        var healthyAt = 0.0
        var userAudio = false
        var userSubtitle = false
        var audioId: Long? = null
        var subtitleId: Long? = null
        var userSecondSubtitle = false
        var secondSubtitleId: Long? = null

        /** The audio and subtitle tracks the last choice of each was made from ([chooseTracks]); null before one. */
        var audioChosenFrom: List<Track>? = null
        var subtitlesChosenFrom: List<Track>? = null

        /** The subtitle tracks and the first subtitle the second's last choice was made with; null before one. */
        var secondChosenFrom: Pair<List<Track>, Long?>? = null

        var subtitlesAdded = false

        /** The subtitle the user added from [SessionInfo.moreSubtitles], until the engine lists it. */
        var requestedSubtitle: ExternalSubtitle? = null

        /** The hash's tail request while it runs ([hash]): the file isn't reported open before it ends. */
        var hashing: Job? = null

        /** The chapters last seen, with the duration, and where the credits to the end start by them. */
        var chapters: List<Chapter> = emptyList()
        var chaptersDuration = 0.0
        var creditsStart: Double? = null
        val creditsStartMillis: Long? get() = creditsStart?.let { millis(it) }

        /** The last track list seen, and so the one the choices were made from. */
        var tracks: List<Track> = emptyList()
    }

    /** The external subtitles found for one stream. */
    private class SubtitleLookup(val choice: StreamChoice) {
        var found: FoundSubtitles? = null
    }

    /** What [choice] sent instead of the video, resolved as [stream], told by [sign]; [resumeAt] is the video's resume point. */
    private class SentErrorVideo(val choice: StreamChoice, val stream: ResolvedStream, val sign: ErrorVideoSign, val resumeAt: Double)

    internal companion object {
        private const val TAG = "session"

        /**
         * Past this fraction of the duration a movie counts as finished (stremio-core's `CREDITS_THRESHOLD_COEF`):
         * leaving takes it back to its start, and a position past it resumes from the start.
         */
        const val CREDITS_THRESHOLD = 0.9

        /** Positions under a second resume from the start (`advanceToVideo` leaves 1 ms). */
        const val MIN_RESUME_MILLIS = 1000L

        /**
         * How long a file waits after its first frame for its length while one can tell an error video ([judge]). The
         * length comes as soon as the engine reads mpv's property changes, which a busy engine thread holds back: with
         * mpv 0.41, a client thread busy 0.3 s or 1.5 s after `file-loaded` got `playback-restart` before the duration.
         * Several times that, while only a file mpv never measures waits it out.
         */
        private val LENGTH_WAIT = 10.seconds

        /** A reported position this close to the expected one means the engine got there. */
        private const val SEEK_SETTLED_SECONDS = 2.0
        private val SEEK_SETTLE_TIMEOUT = 5.seconds

        /** A file whose end comes this soon after where it started ended there ([cutShort]). */
        private const val ENDED_AT_START_SECONDS = 5.0

        /** Seeks closer together than this push once, after the last. */
        private val SEEK_FLUSH_DELAY = 2.seconds

        /** Position steps up to this long count as played; longer ones are seeks. */
        private const val HEALTHY_STEP_SECONDS = 5.0

        fun millis(seconds: Double): Long = if (seconds.isFinite() && seconds > 0) (seconds * 1000).roundToLong() else 0

        /** Where [videoId] starts: the library's position for it, unless it is past the credits or barely started. */
        fun resumePoint(item: LibraryItem?, videoId: String): Double {
            val state = item?.state ?: return 0.0
            if (state.videoId != videoId || state.timeOffset < MIN_RESUME_MILLIS) return 0.0
            if (state.duration > 0 && state.timeOffset > state.duration * CREDITS_THRESHOLD) return 0.0
            return state.timeOffset / 1000.0
        }
    }
}

/**
 * The user's track picks in a title ([PlaybackSession.trackCarries]) carry across episodes: each later
 * file of the title takes the track that matches a pick best ([TrackChooser.carried]), and subtitles turned off stay
 * off. They last while the app runs, never stored: the title played again from Detail or Continue Watching goes on
 * with them, as stremio-core keeps a binge's tracks in its stream state; a restart forgets them.
 *
 * @param audio the audio track picked; null when none was.
 * @param subtitles the subtitles picked; null when none were.
 * @param secondSubtitle the second subtitle picked; null when none was.
 */
internal data class TrackCarry(val audio: Track? = null, val subtitles: Subtitles? = null, val secondSubtitle: Subtitles? = null) {
    /** Subtitles the user picked: [track], or off when it is null. */
    data class Subtitles(val track: Track?)
}

/**
 * A file the session opened in the engine ([PlaybackSession.openedFile]): [videoId] of [meta] from [choice], resolved
 * as [resolved] (its size, hash and file name when the server gave them), or played from the download [local].
 * [serial] tells apart two openings of the same stream (a reload).
 */
class OpenedFile(
    val meta: MetaItem,
    val videoId: String,
    val choice: StreamChoice,
    val resolved: ResolvedStream,
    val serial: Int,
    val local: LocalVideo? = null,
)
