package com.edde746.tortuga.playback.players

import com.edde746.tortuga.addons.readingAddons
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.ErrorVideoSign
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.displayText
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.resolve.ResolveResult
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.resolve.StreamResolver
import com.edde746.tortuga.playback.session.LanguagePreferences
import com.edde746.tortuga.playback.session.LocalVideos
import com.edde746.tortuga.playback.session.PlayPurpose
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.PlaybackObserver
import com.edde746.tortuga.playback.session.PlaybackPosition
import com.edde746.tortuga.playback.session.PlaybackRecorder
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.playback.session.SessionConfig
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.playback.session.TrackChooser
import com.edde746.tortuga.playback.session.episodeLine
import com.edde746.tortuga.playback.session.isEpisode
import com.edde746.tortuga.playback.session.nextVideo
import com.edde746.tortuga.playback.session.pastCredits
import com.edde746.tortuga.playback.session.playerHeaders
import com.edde746.tortuga.playback.session.playerTitle
import com.edde746.tortuga.playback.session.resolved
import com.edde746.tortuga.playback.session.startPosition
import com.edde746.tortuga.playback.session.startSeek
import com.edde746.tortuga.playback.session.subtitleRequest
import com.edde746.tortuga.playback.session.video
import com.edde746.tortuga.playback.subtitles.SubtitleResolver
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Plays videos of one profile in player apps: what `PlaybackController.play` does instead of opening
 * Tortuga's player when the player for the play is a player app ([play]: the one-off "Play with…" choice, else
 * `DeviceSetting.Player`).
 *
 * - A hand-off resolves the stream with the same [StreamResolver] as the player (the player app gets the final URL,
 *   never the addon's resolve link), with the addon's headers and Tortuga's `User-Agent` when it set none, the addons'
 *   subtitles where the app takes them (looked up as the player does; the one to show marked as the player would
 *   choose it), the title the player would show, and the start: the request's, else the resume point. A download
 *   hands off its file and its saved subtitles, nothing resolved.
 * - Meanwhile the banner says "Opening in VLC…" with Cancel ([dismiss]); a resolve failure, and an error video the
 *   resolve tells (`ErrorVideoSign`: nothing is handed off), are worded as the player words them; a player that isn't
 *   found or didn't start says so, with Play in Tortuga ([playInTortuga]): there is no silent fallback. Once the app
 *   started, the banner goes and the Streams sheet closes; a live player that then fails (a crash, a failing exit
 *   status) before it opened the file didn't start either, nor did an Android player whose result says it failed
 *   without saying where it stopped (mpv-android's and MX Player's cancel, Vimu's failure), and the banner says so.
 * - Recording is the player's, through the same calls ([PlaybackSession]'s rules): the library item at the hand-off;
 *   then by what the launch reports ([PlayerAppProgress]). A player that reports a length telling an error video
 *   ([ErrorVideoSign.ofDuration]) before it was recorded leaves nothing recorded, as Tortuga's player:
 *   - [PlayerAppProgress.LIVE]: once the player reported the duration, the stream history, the resume point's move to
 *     the start and the progress are recorded and the observers (the trackers) hear the video start where it is, as
 *     from Tortuga's player once the file is open; then the progress is saved every
 *     [SessionConfig.progressIntervalMillis] while it plays, on pause (pushed to sync) and the moment the video comes
 *     to count as watched (`countsAsWatched`; an episode then moves the library's pointer on, as in Tortuga's player);
 *     a jump of more than a few seconds is saved as a seek, positions reach the observers each second moved; Ended
 *     saves the last position, stops the video, takes a movie past its credits back to its start and pushes to sync.
 *     Detail shows "Playing in mpv · 12:34 of 1:45:00" meanwhile, "Paused in mpv · …" while the player is paused
 *     ([title]).
 *   - [PlayerAppProgress.ON_RETURN]: an Ended records the stream history; with a position (or completed with a
 *     duration: the player's, else the one the library has for the video) it is recorded once as a playback from the
 *     start position to it; without one, the "Finished watching?" prompt stays, kept from the hand-off on
 *     (`DeviceSetting.PlayerAppPrompts`), so it survives the app being closed meanwhile. One that says the player
 *     failed without a position records nothing and drops the prompt: the player didn't start.
 *   - [PlayerAppProgress.NONE]: the stream history at the hand-off and the prompt, nothing more: no progress is
 *     invented.
 *   A later playback of the title, Tortuga's too, replaces its prompt. Nothing plays next on its own.
 * - A live title (`MetaItemPreview.isLive`) records its stream history and the observers' events only, as in Tortuga's
 *   player: no progress, seek, watched mark or move on, and no prompt (nothing to finish).
 * - Each launched hand-off tells the observers about its video as a playback of its own, with observers of its own
 *   ([observe]); its recording in the library goes on whatever else plays.
 *
 * What a hand-off records goes through a [PlaybackRecorder], as in Tortuga's player: library and prompt writes run one
 * at a time in call order and finish after [close]. Call everything on the main thread; a launcher's reports may come
 * from any thread.
 */
class PlayerAppHandoff internal constructor(
    private val profileId: String,
    private val catalog: PlayerAppCatalog,
    private val resolver: StreamResolver,
    private val subtitles: SubtitleResolver,
    private val library: LibraryRepository,
    streamHistory: StreamHistoryRepository,
    private val settings: SettingsRepository,
    private val deviceSettings: DeviceSettingsRepository,
    private val installedAddons: suspend () -> List<Descriptor>,
    private val sync: SyncTrigger,
    private val config: SessionConfig,
    private val clock: Clock,
    private val host: PlayerAppHandoffHost,
    parentScope: CoroutineScope,
    /** This device's downloads for the profile; null without downloads. */
    private val localVideos: LocalVideos? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val recorder = PlaybackRecorder(profileId, library, streamHistory, sync, config.progressIntervalMillis, scope, TAG)
    private val reports = Channel<Pair<HandedOff, PlayerAppEvent>>(Channel.UNLIMITED)

    /** The play waiting for the device's settings to say which player it goes to. */
    private var deciding: Job? = null

    /** The hand-off under way: resolving, then starting the app. */
    private var underWay: Job? = null

    /** The video of the banner shown, for its Play in Tortuga. */
    private var bannerRequest: PlayRequest? = null

    /** Launched plays whose player reports back, until it ended. */
    private val following = mutableListOf<HandedOff>()

    /** What makes each hand-off's own observers ([observe]). */
    private val observerSources = mutableListOf<() -> PlaybackObserver>()

    /** The live players' playbacks, by hand-off, the latest started last. */
    private val live = MutableStateFlow<Map<HandedOff, PlayerAppTitle.Playing>>(emptyMap())
    private var playingCount = 0
    private var closed = false

    init {
        scope.launch { for ((play, event) in reports) play.onEvent(event) }
    }

    /**
     * A player app may play [request]: not a probe (watch together measuring a file) nor a trailer (its "Finished
     * watching?" would ask about the title), and a stream Tortuga's player would play, or a download where this
     * platform's player apps can open the files ([PlayerAppLauncher.handsOffFiles]).
     */
    internal fun takes(request: PlayRequest): Boolean = when {
        request.purpose != PlayPurpose.WATCH -> false
        request.local != null -> catalog.launcher.handsOffFiles
        else -> request.stream.playable
    }

    /**
     * Plays [request] in [playerId] (`PlayerApp.TORTUGA_ID` or a player app's id), or with null in the player Settings
     * → Player names: a player app's hand-off starts, or [inTortuga] plays it in Tortuga's player. Decided at once when
     * the setting is known, else as soon as it is read.
     */
    internal fun play(request: PlayRequest, playerId: String?, inTortuga: (PlayRequest) -> Unit) {
        if (closed) return
        deciding?.cancel()
        deciding = null
        if (playerId != null) return route(request, playerId, inTortuga)
        val stored = catalog.settings.value
        if (stored != null) return route(request, stored.player, inTortuga)
        // The app just started: the setting is read first.
        deciding = scope.launch {
            val chosen = catalog.settings.filterNotNull().first().player
            deciding = null
            route(request, chosen, inTortuga)
        }
    }

    /**
     * Tortuga's player plays [request] (null: a watch-together guest's player, its video not known yet): the hand-off
     * under way (or waiting for the setting) stops, the banner goes, and the title's "Finished watching?" prompt is
     * replaced by this playback.
     */
    internal fun playingInTortuga(request: PlayRequest?) {
        deciding?.cancel()
        deciding = null
        underWay?.cancel()
        underWay = null
        showBanner(null, null)
        if (request != null && request.purpose != PlayPurpose.PROBE) dropPrompt(request.meta.id)
    }

    /** The banner's Play in Tortuga: its video plays in Tortuga's player. */
    fun playInTortuga() {
        val request = bannerRequest ?: return
        host.playInTortuga(request)
    }

    /** The banner's Cancel (the hand-off under way stops) or Dismiss. */
    fun dismiss() {
        underWay?.cancel()
        underWay = null
        showBanner(null, null)
    }

    /**
     * Tells an observer [source] makes about each video a player app plays from now on, as the built-in player does
     * (the trackers): each hand-off gets its own, so an observer can tell one playback from another.
     */
    internal fun observe(source: () -> PlaybackObserver) {
        observerSources += source
    }

    /**
     * The title [metaId]'s playback in a player app as Detail shows it: a live player playing it, else its "Finished
     * watching?" prompt for this profile, else null.
     */
    internal fun title(metaId: String): Flow<PlayerAppTitle?> =
        combine(live, catalog.settings) { playing, stored ->
            playing.values.lastOrNull { it.metaId == metaId }
                ?: stored?.playerAppPrompts?.lastOrNull { it.profileId == profileId && it.metaId == metaId }?.let(PlayerAppTitle::Asking)
        }.distinctUntilChanged()

    /** The title [metaId]'s "Finished watching?" prompt was answered: it goes. */
    internal fun answered(metaId: String) = dropPrompt(metaId)

    /**
     * Ends the hand-offs: the one under way stops, a live player's playback is saved where it last was and stopped (its
     * reports no longer count), the banner goes. The last writes complete in the background.
     */
    internal fun close() {
        if (closed) return
        closed = true
        deciding?.cancel()
        underWay?.cancel()
        showBanner(null, null)
        for (play in following.toList()) play.abandon()
        reports.close()
        recorder.close()
    }

    // Handing off

    private fun route(request: PlayRequest, playerId: String?, inTortuga: (PlayRequest) -> Unit) {
        if (playerId == null || playerId == PlayerApp.TORTUGA_ID) inTortuga(request) else start(request, playerId)
    }

    private fun start(request: PlayRequest, playerId: String) {
        underWay?.cancel()
        val name = isolate(catalog.name(playerId))
        showBanner(request, PlayerAppBanner(T.playerApps.banner.opening(name), busy = true, playInTortugaText = null, dismissText = T.playerApps.banner.cancel))
        underWay = scope.launch {
            catalog.awaitKnown()
            val app = catalog.find(playerId) ?: return@launch failed(request, T.playerApps.banner.notInstalled(name))
            val prepared = prepare(request, app) ?: return@launch
            val play = HandedOff(request, app, prepared.media.startSeconds, prepared.startSeek, prepared.knownDurationMillis, prepared.watchedThresholdPercent)
            val launch = try {
                catalog.launcher.launch(app, prepared.media) { event -> reports.trySend(play to event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "starting ${app.id} failed" }
                PlayerAppLaunch.Failed(PlayerAppFailure.OTHER)
            }
            // Cancelled while the app started (Cancel, another play): what it answered isn't recorded.
            ensureActive()
            underWay = null
            when (launch) {
                is PlayerAppLaunch.Failed -> failed(
                    request,
                    when (launch.failure) {
                        PlayerAppFailure.NOT_INSTALLED -> T.playerApps.banner.notInstalled(isolate(app.name))
                        PlayerAppFailure.OTHER -> T.playerApps.banner.didNotStart(isolate(app.name))
                    },
                )
                is PlayerAppLaunch.Launched -> launched(play, launch.progress)
            }
        }
    }

    /** What [app] is handed for [request], and the start's seek; null after a failure the banner shows. */
    private suspend fun prepare(request: PlayRequest, app: PlayerApp): Prepared? {
        val local = request.local
        val stream = if (local != null) local.resolved() else resolve(request) ?: return null
        val stored = settings.settings(profileId)
        val profile = LanguagePreferences.of(stored)
        val item = library.item(profileId, request.meta.id)
        // A player that can't be told where to start starts at the beginning.
        val start = if (app.capabilities.startPosition) request.startPosition(item) else 0.0
        val handed = when {
            local != null -> local.subtitles.map { PlayerAppSubtitle(it.path, local = true, it.title, it.language) }
            // A player app takes its subtitles up front: what Tortuga's player adds as the file opens.
            app.capabilities.subtitles -> subtitles.resolve(request.subtitleRequest(request.stream, stream), readingAddons(TAG) { installedAddons() }.orEmpty(), profile.subtitleWanted)
                .added.map { PlayerAppSubtitle(it.url, local = false, it.title, it.language) }
            else -> emptyList()
        }
        val shown = TrackChooser.external(handed.map { it.language }, profile.subtitles)
        val media = PlayerAppMedia(
            url = stream.url,
            local = local != null,
            headers = if (local != null) emptyMap() else stream.playerHeaders(config.userAgent),
            title = request.playerTitle(),
            startSeconds = start,
            subtitles = handed.mapIndexed { index, subtitle -> if (index == shown) subtitle.copy(selected = true) else subtitle },
            filename = stream.filename,
        )
        val knownDuration = item?.state?.takeIf { it.videoId == request.videoId && it.duration > 0 }?.duration
        return Prepared(media, startSeek(item, request.videoId, start), knownDuration, stored.watchedThresholdPercent)
    }

    /** [request]'s stream resolved as the player resolves it; null after a failure, shown as the player words it. */
    private suspend fun resolve(request: PlayRequest): ResolvedStream? {
        val result = request.stream.request()?.let { resolver.resolve(it) } ?: ResolveResult.Failed(ResolveFailure.UnsupportedUrl)
        return when (result) {
            is ResolveResult.Resolved -> {
                val errorVideo = result.stream.errorVideo ?: return result.stream
                // Tortuga's player shows the same error, with the message to watch.
                val failure = PlaybackFailure.ErrorVideo(errorVideo, request.meta.isEpisode(request.videoId), request.meta.type == "movie")
                failed(request, failure.displayText)
                null
            }
            is ResolveResult.Failed -> {
                showBanner(
                    request,
                    PlayerAppBanner(PlaybackFailure.Resolve(result.failure).displayText, busy = false, playInTortugaText = null, dismissText = T.playerApps.banner.dismiss),
                )
                null
            }
        }
    }

    /** [request]'s player app isn't there or didn't start: the banner says so ([text]) and offers Tortuga's player. */
    private fun failed(request: PlayRequest, text: String) {
        showBanner(request, PlayerAppBanner(text, busy = false, playInTortugaText = T.playerApps.banner.playInTortuga, dismissText = T.playerApps.banner.dismiss))
    }

    private fun launched(play: HandedOff, progress: PlayerAppProgress) {
        showBanner(null, null)
        host.closeStreams()
        val request = play.request
        val meta = request.meta
        play.recording.saveItem()
        // A player that reports back records the stream once its report shows the file isn't an error video.
        if (progress == PlayerAppProgress.NONE) play.recording.recordStream(request.stream)
        play.progress = progress
        // Asked unless the player reports where it is as it plays, or the title is live (nothing to finish, as in
        // Tortuga's player); an ON_RETURN player that comes back with where it stopped drops it then.
        if (progress == PlayerAppProgress.LIVE || meta.preview.isLive) dropPrompt(meta.id) else ask(play)
        if (progress != PlayerAppProgress.NONE) play.follow()
    }

    private fun showBanner(request: PlayRequest?, banner: PlayerAppBanner?) {
        bannerRequest = request
        host.showBanner(banner)
    }

    // The prompts

    /** Keeps [play]'s "Finished watching?" prompt, in place of the title's earlier one. */
    private fun ask(play: HandedOff) {
        val request = play.request
        val video = request.video()
        val prompt = PlayerAppPrompt(
            profileId = profileId,
            metaId = request.meta.id,
            videoId = request.videoId,
            player = play.app.name,
            label = video?.takeIf { it.seriesInfo != null }?.episodeLine() ?: request.meta.name.ifBlank { video?.title.orEmpty() },
        )
        updatePrompts { prompts -> (prompts.filterNot { it.profileId == profileId && it.metaId == prompt.metaId } + prompt).takeLast(MAX_PROMPTS) }
    }

    private fun dropPrompt(metaId: String) {
        // Read as the settings stand when known: most plays have no prompt to drop.
        val stored = catalog.settings.value
        if (stored != null && stored.playerAppPrompts.none { it.profileId == profileId && it.metaId == metaId }) return
        updatePrompts { prompts -> prompts.filterNot { it.profileId == profileId && it.metaId == metaId } }
    }

    private fun updatePrompts(change: (List<PlayerAppPrompt>) -> List<PlayerAppPrompt>) {
        recorder.write {
            val prompts = deviceSettings.value(DeviceSetting.PlayerAppPrompts)
            val changed = change(prompts)
            if (changed != prompts) deviceSettings.set(DeviceSetting.PlayerAppPrompts, changed)
        }
    }

    // Recording

    /** Tells sync whether something plays, as the hand-offs playing come and go. */
    private fun reportPlaying(playing: Boolean) {
        val before = playingCount
        playingCount = (playingCount + if (playing) 1 else -1).coerceAtLeast(0)
        if ((before == 0) != (playingCount == 0)) sync.setPlaying(playingCount > 0)
    }

    /**
     * What a hand-off is handed, the seek its start records once the player reports it started, how long the video is
     * as the library has it from an earlier play (null when it has none), and the profile's watched threshold.
     */
    private class Prepared(val media: PlayerAppMedia, val startSeek: PlaybackPosition?, val knownDurationMillis: Long?, val watchedThresholdPercent: Int)

    /**
     * One launched hand-off of [request] to [app], started at [startSeconds]; [startSeek] is the resume point's move to
     * the start, recorded with the hand-off ([announce]); [knownDurationMillis] the video's duration in the library,
     * for a player that says it played to the end without saying how long the video is, and for telling an error video
     * by its length ([length]); [watchedThresholdPercent] the profile's, for whether the video counts as watched
     * (`countsAsWatched`).
     */
    private inner class HandedOff(
        val request: PlayRequest,
        val app: PlayerApp,
        val startSeconds: Double,
        private val startSeek: PlaybackPosition?,
        private val knownDurationMillis: Long?,
        private val watchedThresholdPercent: Int,
    ) {
        var progress = PlayerAppProgress.NONE
        private val metaId = request.meta.id
        private val next: Video? = request.meta.nextVideo(request.videoId, clock.now())
        private val download: Long? = request.local?.downloadId

        /** A live title: its position records nothing ([saveProgress], seeks, the move on), as in Tortuga's player. */
        private val liveTitle = request.meta.preview.isLive

        /** What it records ([PlaybackRecorder.video]). */
        val recording = recorder.video(request.meta, request.videoId)

        /** Its own observers ([observe]). */
        private val observers = observerSources.map { it() }

        private var started = false
        private var announced = false
        private var ended = false
        private var playing = false
        private var paused = false
        private var positionMillis = PlaybackSession.millis(startSeconds)
        private var durationMillis = 0L
        private var observedMillis = 0L
        private var lastReport: TimeMark? = null
        private var ticker: Job? = null

        /**
         * The player reported a length that tells an error video ([ErrorVideoSign.ofDuration], against the library's
         * length for the video): nothing is recorded, as in Tortuga's player.
         */
        private var errorVideo = false

        /** Its player reports back: its reports are heard, and the download it plays is left alone until it ended. */
        fun follow() {
            following += this
            download?.let { localVideos?.playbackStarted(it) }
            if (progress == PlayerAppProgress.LIVE) showLive()
        }

        fun onEvent(event: PlayerAppEvent) {
            if (ended || this !in following) return
            when (progress) {
                PlayerAppProgress.LIVE -> when (event) {
                    PlayerAppEvent.Started -> start()
                    is PlayerAppEvent.Position -> position(event)
                    is PlayerAppEvent.Ended -> if (event.failed && !started) {
                        // It died before it opened the file: nothing to record, and it didn't start.
                        Log.w(TAG) { "${app.name} failed before it opened the file" }
                        finish()
                        failed(request, T.playerApps.banner.didNotStart(isolate(app.name)))
                    } else {
                        end(event.positionMillis ?: event.durationMillis?.takeIf { event.completed }, event.durationMillis)
                    }
                }
                PlayerAppProgress.ON_RETURN -> if (event is PlayerAppEvent.Ended) returned(event)
                PlayerAppProgress.NONE -> {}
            }
        }

        /** The handoff closes while it plays: what is known so far is kept. */
        fun abandon() {
            if (progress == PlayerAppProgress.LIVE && started) end(null, null) else finish()
        }

        // Live

        private fun start() {
            if (started) return
            started = true
            lastReport = timeSource.markNow()
            observedMillis = positionMillis
            ticker = recorder.progressTicker { if (playing) saveProgress() }
        }

        private fun position(event: PlayerAppEvent.Position) {
            start()
            move(event.positionMillis, event.durationMillis)
            if (durationMillis > 0) announce()
            // The moment it comes to count as watched, by playing or by a seek, it is saved.
            if (recording.comesToCount(positionMillis, durationMillis, watchedThresholdPercent, creditsStartMillis = null)) saveProgress()
            val nowPlaying = !event.paused
            paused = event.paused
            if (nowPlaying != playing) {
                playing = nowPlaying
                reportPlaying(nowPlaying)
                if (!nowPlaying) saveProgress(flush = true)
                val at = at()
                if (announced) tell { onPlayingChanged(nowPlaying, at) }
            }
            if (abs(positionMillis - observedMillis) >= PlaybackRecorder.OBSERVED_POSITION_STEP_MILLIS) {
                observedMillis = positionMillis
                val at = at()
                if (announced) tell { onPosition(at) }
            }
            showLive()
        }

        /**
         * The hand-off is recorded from when the player said how long the video is (players report where they are before
         * they read the duration) and the length is no error video's, as Tortuga's player records a file once it opened
         * and its length was checked: the stream history, the start's seek, its progress, and the observers hear the
         * video start where it is (so the trackers' start carries its progress, a resumed play's too); at the end,
         * whatever is known. Until then nothing is recorded. A live title records its stream history and the observers'
         * events only.
         */
        private fun announce() {
            if (announced || errorVideo) return
            announced = true
            if (progress == PlayerAppProgress.LIVE) recording.recordStream(request.stream)
            if (!liveTitle) startSeek?.let { recording.recordSeek(it.positionMillis, it.durationMillis) }
            saveProgress()
            val at = at()
            tell { onVideoStarted(request.meta, request.videoId, at, playing) }
        }

        /**
         * The player is at [position] of [duration] (0: unknown). Further from where it would be by now than a few
         * seconds is a seek in the player: what played up to it is saved, and the jump isn't counted as watched.
         */
        private fun move(position: Long, duration: Long) {
            length(duration)
            val elapsed = lastReport?.takeIf { playing }?.elapsedNow()?.inWholeMilliseconds ?: 0
            lastReport = timeSource.markNow()
            val reached = position.coerceAtLeast(0)
            val jumped = abs(reached - (positionMillis + elapsed)) > SEEK_JUMP_MILLIS
            if (jumped) {
                saveProgress()
                if (announced && !liveTitle) recording.recordSeek(reached, durationMillis)
            }
            positionMillis = reached
            if (jumped && announced) {
                val at = at()
                tell { onSeeked(at) }
            }
        }

        /** The player closed at [position] (null: where it last was) of [duration]: the video is left as Tortuga's player leaves it. */
        private fun end(position: Long?, duration: Long?) {
            if (!started) {
                // It never opened the file: nothing to record.
                if (position == null) return finish()
                start()
            }
            if (position != null) move(position, duration ?: 0) else length(duration ?: 0)
            announce()
            // Closed while it played: sync hears it stop playing; the observers hear only the stop, as from Tortuga's player.
            if (playing) {
                playing = false
                reportPlaying(false)
            }
            leave()
            finish()
        }

        /** The player said the file runs [duration] (0: unknown); a length that tells an error video is noted. */
        private fun length(duration: Long) {
            if (duration <= 0) return
            durationMillis = duration
            if (announced || errorVideo || ErrorVideoSign.ofDuration(duration / 1000.0, knownDurationMillis?.let { it / 1000.0 }) == null) return
            Log.w(TAG) { "${app.name} plays an error video" }
            errorVideo = true
        }

        // On return

        private fun returned(event: PlayerAppEvent.Ended) {
            if (event.failed && event.positionMillis == null) {
                // Its result says it failed before it played (a cancel, a failure): nothing to record or ask.
                Log.w(TAG) { "${app.name} says it failed before it played" }
                dropPrompt(metaId)
                finish()
                return failed(request, T.playerApps.banner.didNotStart(isolate(app.name)))
            }
            // Played to the end: Android's players (mpv-android, MX Player, Vimu) say so without the duration.
            val duration = event.durationMillis ?: knownDurationMillis?.takeIf { event.completed }
            length(duration ?: 0)
            // An error video: nothing to record, nothing to ask.
            if (errorVideo) {
                dropPrompt(metaId)
                return finish()
            }
            recording.recordStream(request.stream)
            val position = event.positionMillis ?: duration?.takeIf { event.completed }
            // Without a position the prompt kept at the hand-off asks (a live title has none).
            if (position == null) return finish()
            dropPrompt(metaId)
            // From the start, so the time between counts as watched (a new video starts counting there).
            announce()
            positionMillis = position.coerceAtLeast(0)
            val at = at()
            if (announced) tell { onPosition(at) }
            leave()
            finish()
        }

        /**
         * Leaving the video: the observers hear it stop, its last progress (an episode that counts as watched moves the
         * pointer on), the pointer's move on past the credits as in Tortuga's player (not a live title's), and a push to
         * sync.
         */
        private fun leave() {
            if (!announced) return
            val at = at()
            tell { onVideoStopped(at) }
            saveProgress()
            recording.leave(pastCredits = !liveTitle && pastCredits(positionMillis / 1000.0, durationMillis / 1000.0), next)
        }

        private fun finish() {
            ended = true
            ticker?.cancel()
            following -= this
            download?.let { localVideos?.playbackEnded(it) }
            live.value -= this
        }

        /**
         * Saves progress as Tortuga's player does: with whether the video counts as watched; an episode that comes to
         * count moves the library's pointer on in that write, and nothing more is recorded. A live title's isn't saved.
         */
        private fun saveProgress(flush: Boolean = false) {
            if (!announced || liveTitle) return
            recording.saveProgress(positionMillis, durationMillis, watchedThresholdPercent, creditsStartMillis = null, flush)
        }

        private fun showLive() {
            // Kept in its place: the order is the order they started.
            live.value += this to PlayerAppTitle.Playing(metaId, request.videoId, app.name, positionMillis, durationMillis, paused)
        }

        private fun at() = PlaybackPosition(positionMillis, durationMillis)

        private inline fun tell(event: PlaybackObserver.() -> Unit) = recorder.notifyObservers(observers, event)
    }

    private companion object {
        const val TAG = "player-apps"

        /** Reports this much further from where the player would be by now are seeks. */
        const val SEEK_JUMP_MILLIS = 5_000L

        /** The prompts kept on this device, the oldest dropped first. */
        const val MAX_PROMPTS = 50
    }
}

/** A title's playback in a player app as Detail shows it ([PlayerAppHandoff.title]). */
internal sealed interface PlayerAppTitle {
    /**
     * [player] plays [videoId] of [metaId] now and reports it ([PlayerAppProgress.LIVE]), at [positionMillis] of
     * [durationMillis] (0 while unknown); [paused] while the player says it is paused.
     */
    data class Playing(
        val metaId: String,
        val videoId: String,
        val player: String,
        val positionMillis: Long,
        val durationMillis: Long,
        val paused: Boolean,
    ) : PlayerAppTitle

    /** The "Finished watching?" prompt. */
    data class Asking(val prompt: PlayerAppPrompt) : PlayerAppTitle
}

/** What a [PlayerAppHandoff] asks of the app around it; called on the main thread. */
internal interface PlayerAppHandoffHost {
    /** Plays [request] in Tortuga's player (the banner's Play in Tortuga). */
    fun playInTortuga(request: PlayRequest)

    /** A player app started: the Streams sheet, if it is on top, closes. */
    fun closeStreams()

    /** Shows [banner] over the screen on top (`AppSessionState.playerApp`), or none. */
    fun showBanner(banner: PlayerAppBanner?)
}
