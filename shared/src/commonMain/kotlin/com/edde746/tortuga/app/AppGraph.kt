package com.edde746.tortuga.app

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonEvents
import com.edde746.tortuga.addons.AddonRegistry
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.kids.AgeRatings
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.net.AddonResponseCache
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.CustomShader
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.ShaderFiles
import com.edde746.tortuga.playback.ShaderPreset
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.SystemMediaAccessibility
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.output.PlaybackOutput
import com.edde746.tortuga.playback.output.PlaybackOutputHost
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppCatalog
import com.edde746.tortuga.playback.players.PlayerAppHandoff
import com.edde746.tortuga.playback.players.PlayerAppHandoffHost
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.playback.resolve.StreamResolver
import com.edde746.tortuga.playback.session.BingePicker
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.playback.session.SessionConfig
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.playback.session.localVideos
import com.edde746.tortuga.playback.subtitles.SubtitleResolver
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.presentation.calendar.EpisodeFeed
import com.edde746.tortuga.presentation.menu.WatchlistRequest
import com.edde746.tortuga.presentation.player.DiscordPresence
import com.edde746.tortuga.presentation.player.DiscordPresenceHost
import com.edde746.tortuga.presentation.player.MediaControls
import com.edde746.tortuga.presentation.player.MediaControlsHost
import com.edde746.tortuga.presentation.player.PlayerChoices
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerHost
import com.edde746.tortuga.presentation.player.PlayerPreferences
import com.edde746.tortuga.presentation.player.PlayerStore
import com.edde746.tortuga.presentation.streams.StreamListPolicy
import com.edde746.tortuga.presentation.streams.StreamsHost
import com.edde746.tortuga.presentation.streams.StreamsStore
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.trackers.TrackerHub
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Where this build is distributed, which decides what it may show. The Apple apps are
 * [APPLE]; Android and desktop builds take theirs from `-Ptortuga.flavor` (settings.gradle.kts).
 *
 * @param streamListPolicy how the stream list treats unplayable sources and instant badges.
 * @param addonCatalogs Settings' Addons section browses the addons that installed addons list (Cinemeta's Official and
 *   Community catalogs). Not in the App Store build, which has no in-app community addon list.
 */
enum class BuildFlavor(val streamListPolicy: StreamListPolicy, val addonCatalogs: Boolean) {
    /** App Store, TestFlight and every other Apple channel: no unplayable note, no instant badge, no addon browsing. */
    APPLE(StreamListPolicy(unplayableNote = false, cacheBadge = false), addonCatalogs = false),

    /** Google Play, Amazon and the Microsoft Store: unplayable sources are hidden without a note. */
    STORE(StreamListPolicy(unplayableNote = false, cacheBadge = true), addonCatalogs = true),

    /** Builds installed by hand (the GitHub release's APKs, Flatpaks): may say how many streams they can't play. */
    SIDELOADED(StreamListPolicy(unplayableNote = true, cacheBadge = true), addonCatalogs = true),
}

/**
 * The shared core for one app process (a manual composition root): the local data, the HTTP client and
 * addon response cache, the addon transport, aggregator and per-profile registries, the stream and subtitle
 * resolvers, and factories for playback sessions and the player and Streams stores.
 *
 * The secret store and sync come from outside (the sync engine implements [SyncTrigger]).
 *
 * Construct it off the main thread (it opens the database); use the rest from the main thread. [scope] is the app's
 * main-thread scope: stores and sessions run in it, and a closed session's last writes finish in it. [parseDispatcher]
 * is where addon answers are decoded and turned into the stores' values before they reach the main thread.
 * [timeSource] counts the kids ratings' waits: a failed lookup's retry ([KidsGate]).
 *
 * @param device this device as the server lists it; its platform also decides what the About page credits.
 * @param appVersion as About shows it ("1.0.0"); sent as `User-Agent: Tortuga/<version>` by the HTTP client and the
 *   player.
 */
class AppGraph internal constructor(
    val data: LocalData,
    val secrets: SecretStore,
    internal val sync: SyncTrigger,
    val flavor: BuildFlavor,
    val device: DeviceDescriptor,
    val appVersion: String,
    val http: HttpClient,
    val scope: CoroutineScope,
    internal val clock: Clock,
    internal val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    internal val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : AutoCloseable {
    constructor(
        database: DatabaseFactory,
        secrets: SecretStore,
        sync: SyncTrigger,
        flavor: BuildFlavor,
        device: DeviceDescriptor,
        appVersion: String,
        http: HttpClient = TortugaHttpClient.create(appVersion),
        scope: CoroutineScope,
        clock: Clock = Clock.System,
    ) : this(LocalData(database), secrets, sync, flavor, device, appVersion, http, scope, clock)

    val responseCache: AddonResponseCache = AddonResponseCache()
    val transport: AddonTransport = AddonTransport(http, responseCache, scope, parseDispatcher)
    val aggregator: AddonAggregator = AddonAggregator(transport)
    val streamResolver: StreamResolver = StreamResolver(http)
    val subtitleResolver: SubtitleResolver = SubtitleResolver(aggregator)

    private val sessionConfig = SessionConfig(userAgent = TortugaHttpClient.userAgent(appVersion))
    internal val autoPicker = StreamAutoPicker(aggregator, data.streamHistory)
    private val binge = BingePicker(aggregator, autoPicker)
    internal val registries = mutableMapOf<String, AddonRegistry>()
    internal val addonEventSenders = mutableMapOf<String, AddonEvents>()

    /** Each profile's [EpisodeFeed]s by extra, kept for the app's life. */
    internal val episodeFeeds = mutableMapOf<Pair<String, String>, EpisodeFeed>()

    /**
     * What came of each profile's last card-menu entry that couldn't be carried out ("Couldn't mark Breaking Bad: …"),
     * by profile id, until its session has shown it ([AppSessionState.menuNotice]).
     */
    internal val menuNotices = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The title each profile's Watchlist sheet is open on, by profile id, until its session closes it
     * ([AppSessionState.watchlist], [AppSession.dismissWatchlist]).
     */
    internal val watchlistSheets = MutableStateFlow<Map<String, WatchlistRequest>>(emptyMap())

    /**
     * Tracker accounts and scrobbling, once [com.edde746.tortuga.trackers.installTrackers] set them
     * up; `null` in a build without them. Playback sessions and the Detail and Library stores report to it.
     */
    var trackers: TrackerHub? = null
        internal set

    /**
     * Offline downloads, once [com.edde746.tortuga.downloads.installDownloads] set them up; `null` where
     * the platform has none (Apple TV) and in a development session without them.
     */
    var downloads: DownloadManager? = null
        internal set

    private val mutableNetwork = MutableStateFlow(NetworkState.ONLINE)

    /**
     * The device's network as the platform last reported it ([AppBootstrap.onNetworkChanged]); [NetworkState.ONLINE]
     * until it does. A development session's `offline` switch holds it offline.
     */
    val network: StateFlow<NetworkState> = mutableNetwork.asStateFlow()

    // The platform's last report, kept while a development session holds the network offline.
    private var reportedNetwork = NetworkState.ONLINE
    private var offlineHeld = false

    internal fun setNetwork(state: NetworkState) {
        reportedNetwork = state
        if (!offlineHeld) mutableNetwork.value = state
    }

    /**
     * A development session's `offline` switch (the dev module's `DevSetting.Offline`): while [held], [network] stays
     * offline whatever the platform reports (desktop and simulators prove offline mode with it); released, it is the
     * platform's last report (and the trackers replay their queued writes, as when the network comes back).
     */
    fun holdOffline(held: Boolean) {
        val wasOnline = network.value.online
        offlineHeld = held
        mutableNetwork.value = if (held) NetworkState.OFFLINE else reportedNetwork
        if (network.value.online && !wasOnline) trackers?.onNetworkRestored()
    }

    private val mutableForeground = MutableStateFlow(false)

    /**
     * Whether the app is in the foreground as the platform last reported it ([AppBootstrap.onForeground],
     * [AppBootstrap.onBackground]); `false` until the first report, so a process started for background work (Android's
     * download worker) counts as in the background.
     */
    val foreground: StateFlow<Boolean> = mutableForeground.asStateFlow()

    internal fun setForeground(foreground: Boolean) {
        mutableForeground.value = foreground
    }

    /** The Mac's window as it last reported it ([AppBootstrap.onWindowVisibleChanged]); null where nothing reports it. */
    private val mutableWindowVisible = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the app can be seen. On the Mac its window's report alone decides ([AppBootstrap.onWindowVisibleChanged]):
     * a window in sight behind another app's counts, and one minimized, hidden or covered whole doesn't, even while the
     * app is the active one. Elsewhere it is [foreground]. Watch together's player follows the room while it is.
     */
    val visible: Flow<Boolean> = combine(foreground, mutableWindowVisible) { foreground, window -> window ?: foreground }.distinctUntilChanged()

    internal fun setWindowVisible(visible: Boolean) {
        mutableWindowVisible.value = visible
    }

    /**
     * The Streams screen for [videoId] of [meta] (a movie's video id is its meta id), with [notice] on top when it
     * opened instead of a stream auto-play would have played. With [download], a pick queues the stream's download
     * instead of playing it; with [fromStart], it plays from 0. With [playerApps] (this device's, as they're found), its
     * rows offer "Play with…". A pick goes to [picks] ([StreamPicks]).
     */
    fun streamsStore(
        profileId: String,
        meta: MetaItem,
        videoId: String,
        host: StreamsHost,
        notice: String?,
        download: Boolean = false,
        playerApps: Flow<List<PlayerApp>>? = null,
        fromStart: Boolean = false,
        picks: StreamPicks? = null,
    ): StreamsStore = StreamsStore(
        meta = meta,
        videoId = videoId,
        profileId = profileId,
        aggregator = aggregator,
        installedAddons = installedAddons(profileId),
        streamHistory = data.streamHistory,
        settings = data.settings,
        policy = flavor.streamListPolicy,
        host = host,
        parentScope = scope,
        notice = notice,
        downloads = downloads,
        downloadMode = download,
        playerApps = playerApps,
        fromStart = fromStart,
        picks = picks,
    )

    /**
     * [profileId]'s hand picks of streams on this device ([StreamPicks]), each made where [platform] says: its kind of
     * device, screen and decoders, and the network as it is at the pick.
     */
    internal fun streamPicks(profileId: String, platform: AppPlatform): StreamPicks {
        val capabilities by lazy { platform.videoCapabilities() }
        val context = {
            PickContext(DeviceClass.of(platform.formFactor), network.value.metered, capabilities.hdr, capabilities.dolbyVision, capabilities.hardwareCodecs)
        }
        return StreamPicks(profileId, data.streamPicks, data.settings, context, scope, learnDispatcher = parseDispatcher)
    }

    /**
     * A playback session over the platform's [engine], whose files load with [PlaybackOptions] from this device's
     * settings for the platform's [decoder], whose subtitles follow this device's subtitle style ([SubtitleStyle]), and
     * whose tracks follow the system's caption and audio-description settings ([SystemMediaAccessibility]); start it
     * with `PlaybackSession.start` and hand it to [playerStore]. [PlaybackSession.close] saves the progress and releases
     * the engine. What comes of a hand-picked stream, and the player's next stream as a pick, go to [picks].
     */
    fun playbackSession(profileId: String, engine: PlayerEngine, decoder: HardwareDecoder, picks: StreamPicks? = null): PlaybackSession = PlaybackSession(
        engine = engine,
        profileId = profileId,
        resolver = streamResolver,
        subtitles = subtitleResolver,
        binge = binge,
        library = data.library,
        streamHistory = data.streamHistory,
        settings = data.settings,
        playbackOptions = { PlaybackOptions.of(data.deviceSettings.settings(), decoder) },
        subtitleStyle = data.deviceSettings.observe().map { SubtitleStyle.of(it.subtitles) },
        mediaAccessibility = SystemMediaAccessibility.preferences,
        installedAddons = installedAddons(profileId),
        sync = sync,
        config = sessionConfig,
        clock = clock,
        parentScope = scope,
        localVideos = downloads?.localVideos(profileId),
        picks = picks,
    ).also { session ->
        trackers?.coordinator(profileId)?.follow(session)
        session.observe(addonEvents(profileId).player())
    }

    /** This device's player apps through the platform's [launcher], for one running app in [appScope]; detected now. */
    internal fun playerAppCatalog(launcher: PlayerAppLauncher, appScope: CoroutineScope): PlayerAppCatalog =
        PlayerAppCatalog(launcher, data.deviceSettings, appScope)

    /**
     * Hands [profileId]'s videos to the player apps of [catalog] as a play asks ([PlayerAppHandoff]), recording them as
     * a [PlaybackSession] records its own (the trackers follow them too); [host] shows its banner and plays in Tortuga.
     */
    internal fun playerAppHandoff(profileId: String, catalog: PlayerAppCatalog, host: PlayerAppHandoffHost): PlayerAppHandoff = PlayerAppHandoff(
        profileId = profileId,
        catalog = catalog,
        resolver = streamResolver,
        subtitles = subtitleResolver,
        library = data.library,
        streamHistory = data.streamHistory,
        settings = data.settings,
        deviceSettings = data.deviceSettings,
        installedAddons = installedAddons(profileId),
        sync = sync,
        config = sessionConfig,
        clock = clock,
        host = host,
        parentScope = scope,
        localVideos = downloads?.localVideos(profileId),
    ).also { handoff ->
        trackers?.coordinator(profileId)?.let { handoff.observe(it::observer) }
        handoff.observe(addonEvents(profileId)::player)
    }

    /**
     * Titles' suggested ages for kids profiles, once [com.edde746.tortuga.trackers.installTrackers] set up the age
     * database; null before (every lookup then fails, and a kids profile shows only what a parent allowed).
     */
    internal var ageRatings: AgeRatings? = null

    private val kidsGates = mutableMapOf<String, KidsGate>()

    /** [profileId]'s kids rules, one per profile. */
    internal fun kidsGate(profileId: String): KidsGate = kidsGates.getOrPut(profileId) {
        KidsGate(profileId, data.profiles.observeProfile(profileId), data.settings.observe(profileId), data.settings, { ageRatings }, scope, timeSource)
    }

    /**
     * A development session's `player.fastTimers` switch (the dev module's `DevSetting.FastTimers`): while on, the
     * players opened next get [playerConfig]'s fast sleep timer.
     */
    var fastPlayerTimers = false

    /**
     * The config the next player opens with, from the platform's [config]: as it is, or with a development session's
     * fast timers ([fastPlayerTimers]) its sleep timer's minutes [FAST_SLEEP_TIMER_MINUTE_SECONDS] seconds each.
     */
    internal fun playerConfig(config: PlayerConfig): PlayerConfig =
        if (fastPlayerTimers) config.copy(sleepTimerMinuteSeconds = FAST_SLEEP_TIMER_MINUTE_SECONDS) else config

    /**
     * The player screen's store over [session]; its fit, shader and audio options are this device's
     * ([playerPreferences]), its shaders' files in [shaderFiles] (null where the player offers no shaders), imported
     * through the system's open dialog where [importsShaders].
     */
    fun playerStore(session: PlaybackSession, host: PlayerHost, config: PlayerConfig, shaderFiles: ShaderFiles?, importsShaders: Boolean): PlayerStore =
        PlayerStore(session.playerEngine, session, host, playerPreferences, config, scope, shaderFiles = shaderFiles, importsShaders = importsShaders)

    /** The player's options this device keeps for the next video, as its device settings. */
    private val playerPreferences = object : PlayerPreferences {
        override val choices = data.deviceSettings.observe()
            .map { PlayerChoices(it.videoFit, it.shaderPreset, it.customShaders, it.audioNormalization, it.audioChannels, it.swipeGestures) }
            .distinctUntilChanged()

        override fun setVideoFit(fit: VideoFit) = save(DeviceSetting.VideoFit, fit)

        override fun setShaderPreset(id: String) = save(DeviceSetting.ShaderPreset, id)

        override fun addCustomShader(shader: CustomShader) {
            scope.launch {
                data.deviceSettings.update(DeviceSetting.CustomShaders) { it + shader }
                data.deviceSettings.set(DeviceSetting.ShaderPreset, shader.id)
            }
        }

        override fun removeCustomShader(shader: CustomShader) {
            scope.launch {
                data.deviceSettings.update(DeviceSetting.ShaderPreset) { if (it == shader.id) ShaderPreset.Off.id else it }
                data.deviceSettings.update(DeviceSetting.CustomShaders) { shaders -> shaders.filter { it.id != shader.id } }
            }
        }

        override fun setAudioNormalization(enabled: Boolean) = save(DeviceSetting.AudioNormalization, enabled)

        override fun setAudioChannels(channels: AudioChannels) = save(DeviceSetting.AudioChannels, channels)

        private fun <T> save(setting: DeviceSetting<T>, value: T) {
            scope.launch { data.deviceSettings.set(setting, value) }
        }
    }

    /** The OS media controls of the playback in [store] and [session], shown through the platform's [host]. */
    fun mediaControls(store: PlayerStore, session: PlaybackSession, host: MediaControlsHost): MediaControls =
        MediaControls(store, session, host, scope)

    /**
     * Discord's Rich Presence for the playback in [store] and [session], through the platform's [host], while this
     * device's setting is on.
     */
    fun discordPresence(store: PlayerStore, session: PlaybackSession, host: DiscordPresenceHost): DiscordPresence = DiscordPresence(
        store,
        session,
        data.deviceSettings.observe().map { it.discordPresence }.distinctUntilChanged(),
        host,
        scope,
        clock,
    )

    /**
     * The display mode, HDR and passthrough of the playback in [session] on a [formFactor] device, switched by the
     * platform's [host]; a desktop switches the mode only while [store]'s player is fullscreen.
     */
    fun playbackOutput(store: PlayerStore, session: PlaybackSession, formFactor: FormFactor, host: PlaybackOutputHost): PlaybackOutput =
        PlaybackOutput(session.engineState, session.info.map { it.trailer }, store.state.map { it.fullscreen }, formFactor, data.deviceSettings::settings, host, scope)

    /**
     * Stops everything this graph runs and closes the HTTP client and the database, the latter once the reads and writes
     * already running on the I/O context are done ([LocalData.close]).
     */
    override fun close() {
        scope.cancel()
        http.close()
        data.close()
    }

    private fun installedAddons(profileId: String): suspend () -> List<Descriptor> {
        val registry = addonRegistry(profileId)
        return { registry.current() }
    }
}

/** A sleep-timer minute's length in seconds under [AppGraph.fastPlayerTimers]: a tenth of a minute. */
private const val FAST_SLEEP_TIMER_MINUTE_SECONDS = 6
