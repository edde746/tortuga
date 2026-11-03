package com.edde746.tortuga.app

import com.edde746.tortuga.kids.KidsVerdict
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.output.PlaybackOutput
import com.edde746.tortuga.playback.players.PlayerAppHandoff
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.playback.session.SessionEvent
import com.edde746.tortuga.playback.session.TrackCarry
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.TortugaNavigator
import com.edde746.tortuga.presentation.player.DeviceLevels
import com.edde746.tortuga.presentation.player.DiscordPresence
import com.edde746.tortuga.presentation.player.MediaControls
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerHost
import com.edde746.tortuga.presentation.player.PlayerStore
import com.edde746.tortuga.watch.RoomPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One playback: the OS side ([platform], with its [engine]), the shared [session] over the engine, the player screen's
 * [store] over the session, and where the platform has them the OS media [controls], Discord's Rich Presence
 * ([discord]) and the display output following it. [config] is the store's keymap profile and timings; [watch] is the
 * playback as a watch-together room follows it.
 */
class ActivePlayback internal constructor(
    val platform: PlatformPlayback,
    val session: PlaybackSession,
    val store: PlayerStore,
    val controls: MediaControls?,
    internal val discord: DiscordPresence?,
    internal val output: PlaybackOutput?,
    val config: PlayerConfig,
) {
    val engine: PlayerEngine get() = platform.engine

    internal val watch = RoomPlayback(session, store.watchPlayer, store.inPictureInPicture)
}

/**
 * Starts and ends the playbacks of one [AppSession]. [play] opens the player over a new [PlatformPlayback], or hands
 * the video to a player app instead ([handoff]) when that is the player for the play; leaving the player (its route
 * popped by Back or Close, or replaced by another screen) ends the playback, which saves the progress and releases the
 * engine. Main thread only.
 *
 * With the Streams sheet ([Route.Streams]) on top, the player replaces it: Back from the player returns to the screen
 * the sheet was over. When the session asks for a stream to be picked (the next or previous episode without a binge
 * match, or an auto-played stream that didn't start), that video's Streams sheet opens over the player, with the
 * session's notice; a stream picked there plays in the same player, and closing the sheet without one closes the
 * player too, which has nothing left to play. Every player takes on the user's track picks in the title's earlier
 * players while the app runs ([PlaybackSession.trackCarries]): the binge goes on with the same subtitles and audio
 * through a stream picked over the player, Detail or Continue Watching.
 *
 * @param publish receives [active] whenever it changes (the app session's state).
 */
class PlaybackController internal constructor(
    private val graph: AppGraph,
    private val profileId: String,
    private val platform: AppPlatform,
    /** The profile's hand picks of streams: each player's session records what came of them. */
    private val picks: StreamPicks,
    private val navigator: TortugaNavigator,
    private val scope: CoroutineScope,
    private val publish: (ActivePlayback?) -> Unit,
) {
    /** The playback the player shows, or null. */
    var active: ActivePlayback? = null
        private set

    private var events: Job? = null
    /** Passes the store's [com.edde746.tortuga.presentation.player.PlayerUiState.keepAwake] on to the platform. */
    private var awake: Job? = null
    private var closed = false

    /** The user's track picks by title while the app runs, shared by every player ([PlaybackSession.trackCarries]). */
    private val trackCarries = mutableMapOf<String, TrackCarry>()

    /**
     * The profile's kids rules, taken as the session starts so the profile is read before the first play: on a
     * profile that isn't a kids profile, a play then starts at once.
     */
    private val kids = graph.kidsGate(profileId)

    /** The Streams sheet the session asked for over the player ([SessionEvent.ChooseStream]), while it is open. */
    private var choosing: Route.Streams? = null

    init {
        navigator.addRemovalListener { route ->
            if (route != choosing) return@addRemovalListener
            choosing = null
            // Closed without a stream picked ([play] clears it first): the player under it has nothing to play.
            if (navigator.current == Route.Player) navigator.pop(Route.Player)
        }
    }

    /** What the player store asks of the app: closing the player, fullscreen, the brightness and the system's volume. */
    private val host = object : PlayerHost {
        override fun close() {
            when {
                navigator.pop(Route.Player) -> Unit
                // The player is the only screen (a dev session's direct play): leaving it leaves the app.
                navigator.current == Route.Player -> platform.exit()
                // Already gone from the stack: a late Back while it animates out.
                else -> Unit
            }
        }

        override fun setFullscreen(fullscreen: Boolean) = platform.setFullscreen(fullscreen)

        override val levels: DeviceLevels? get() = platform.deviceLevels
    }

    /** Watch together's hold on playbacks, when the session has it ([PlaybackRoom]). */
    internal var room: PlaybackRoom? = null

    /** Player apps' hand-offs, where the platform has player apps ([AppPlatform.playerApps]). */
    internal var handoff: PlayerAppHandoff? = null

    /**
     * Plays [request] in the player Settings → Player names: Tortuga's, or a player app's ([play] with a player).
     */
    fun play(request: PlayRequest) = play(request, playerId = null)

    /**
     * Plays [request] in the player [playerId] for this play only (a "Play with…" choice: `PlayerApp.TORTUGA_ID` or a
     * player app's id); null plays in the one Settings → Player names. A player app gets it through [handoff], once
     * that setting is read where it isn't yet. Tortuga's player plays it anyway in a watch-together room, as a probe,
     * and when no player app can (a stream that isn't `Playable`, a download where other apps can't open the files).
     *
     * In Tortuga's player, the current playback ends first; the player replaces a Streams sheet on top, or is the
     * screen under it already. In a watch-together room the room adjusts the request first, or plays it in the
     * current playback itself (a guest's stream picked for the room's media). Nothing happens once the app is closed.
     *
     * On a kids profile ([AppGraph.kidsGate]) the title plays only once its gate allows it: at once when its verdict is
     * known, after the lookup otherwise; a title it blocks doesn't play.
     */
    fun play(request: PlayRequest, playerId: String?) {
        if (closed) return
        val meta = request.meta.preview
        when (kids.verdictNow(meta.type, meta.id, meta)) {
            KidsVerdict.Allowed -> start(request, playerId)
            is KidsVerdict.Blocked -> Log.i(TAG) { "Not playing a title this kids profile doesn't allow" }
            null -> scope.launch {
                if (kids.verdict(meta.type, meta.id, meta) == KidsVerdict.Allowed) {
                    start(request, playerId)
                } else {
                    Log.i(TAG) { "Not playing a title this kids profile doesn't allow" }
                }
            }
        }
    }

    private fun start(request: PlayRequest, playerId: String?) {
        if (closed) return
        val handoff = handoff
        if (handoff == null || room?.inRoom == true || !handoff.takes(request)) return playInTortuga(request)
        handoff.play(request, playerId, ::playInTortuga)
    }

    private fun playInTortuga(request: PlayRequest) {
        if (closed) return
        handoff?.playingInTortuga(request)
        val room = room
        val toPlay = if (room == null) {
            request
        } else {
            room.beforePlay(request) ?: run {
                choosing = null
                navigator.navigate(Route.Player)
                return
            }
        }
        open { it.start(toPlay) }
    }

    /**
     * Opens the player over a new playback whose session has nothing to play yet: a watch-together guest's, while it
     * looks for a file matching the host's. Tortuga's player takes over from the hand-offs as for any play
     * ([PlayerAppHandoff.playingInTortuga]). Null once the app is closed.
     */
    internal fun openForRoom(): ActivePlayback? {
        if (closed) return null
        handoff?.playingInTortuga(null)
        return open {}
    }

    private fun open(start: (PlaybackSession) -> Unit): ActivePlayback {
        stopPlayback(replaced = true)
        choosing = null
        val playback = platform.createPlayback()
        val session = graph.playbackSession(profileId, playback.engine, playback.hardwareDecoder, picks)
        session.trackCarries = trackCarries
        val config = graph.playerConfig(platform.playerConfig)
        val store = graph.playerStore(session, host, config, platform.shaderFiles, platform.hasFileDialogs)
        val controls = playback.mediaControls?.let { graph.mediaControls(store, session, it) }
        val discord = platform.discordPresence?.let { graph.discordPresence(store, session, it) }
        val output = playback.output?.let { graph.playbackOutput(store, session, platform.formFactor, it) }
        playback.attach(store)
        // Subscribed before the session starts, so nothing it emits is missed.
        events = scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect(::onSessionEvent) }
        awake = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.map { it.keepAwake }.distinctUntilChanged().collect(playback::keepDisplayAwake)
        }
        start(session)
        val active = ActivePlayback(playback, session, store, controls, discord, output, config)
        this.active = active
        publish(active)
        navigator.navigate(Route.Player)
        room?.onStarted(active)
        return active
    }

    /**
     * Ends the current playback, if any: tells the room, leaves fullscreen, takes down the media controls and Discord's
     * activity, gives the display back its mode and lets it sleep, releases the platform's side, stops the store, then
     * closes the session (which saves the progress and releases the engine). The player closing with it gives the
     * screen its own brightness back; a playback replaced at once keeps the one the viewer swiped to.
     */
    fun stop() = stopPlayback(replaced = false)

    /** [stop]; [replaced] when another playback takes the player's place at once. */
    private fun stopPlayback(replaced: Boolean) {
        val playback = active ?: return
        active = null
        publish(null)
        events?.cancel()
        events = null
        awake?.cancel()
        awake = null
        room?.onStopped(playback, replaced)
        if (playback.store.state.value.fullscreen) platform.setFullscreen(false)
        if (!replaced) platform.deviceLevels?.restoreBrightness()
        playback.controls?.release()
        playback.discord?.release()
        playback.output?.release()
        playback.platform.keepDisplayAwake(false)
        playback.platform.release()
        playback.store.release()
        playback.session.close()
    }

    /** Ends the playback for good: the app is closing. */
    internal fun close() {
        closed = true
        stop()
    }

    /** What the session asks beyond the player: another episode needs a stream picked, in its Streams sheet. */
    private fun onSessionEvent(event: SessionEvent) {
        if (event !is SessionEvent.ChooseStream) return
        navigator.openStreams(event.meta, event.videoId, event.notice)
        val underSheet = navigator.layout.screens.last()
        choosing = (navigator.layout.sheet as? Route.Streams)?.takeIf { underSheet == Route.Player }
    }
}

/** Watch together's part in the app's playbacks ([PlaybackController.room]). */
internal interface PlaybackRoom {
    /** This device is in a room: everything plays in Tortuga's player, which the room follows. */
    val inRoom: Boolean

    /** [request] as it plays in a room, or null when the room plays it in the current playback itself. */
    fun beforePlay(request: PlayRequest): PlayRequest?

    /** [active] opened in the player. */
    fun onStarted(active: ActivePlayback)

    /** [active] ended; [replaced] when another playback takes its place at once. */
    fun onStopped(active: ActivePlayback, replaced: Boolean)
}

private const val TAG = "playback"
