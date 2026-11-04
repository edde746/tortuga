package com.edde746.tortuga.playback.session

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamProxyHeaders
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.MediaAccessibilityPreferences
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.PlayerEngineListener
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoLayout
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.resolve.StreamResolver
import com.edde746.tortuga.playback.resolve.serveFile
import com.edde746.tortuga.playback.subtitles.SubtitleResolver
import com.edde746.tortuga.playback.suggest.StreamPicks
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * An engine that behaves like mpv towards the session: it reports a new file opening, then open (its first frame up:
 * one playback restart, seekable), counts a restart per seek of an open file, and echoes track selections and added
 * subtitles in its track list. Tests move the position and raise errors with [report].
 */
internal class ScriptedEngine : PlayerEngine {
    private var listener: PlayerEngineListener? = null
    var state = EngineState()
        private set

    val loads = mutableListOf<LoadRequest>()
    val selections = mutableListOf<Pair<TrackKind, Long?>>()
    val secondSelections = mutableListOf<Long?>()
    val addedSubtitles = mutableListOf<Triple<String, String?, String?>>()
    val pauses = mutableListOf<Boolean>()
    val subtitleStyles = mutableListOf<SubtitleStyle>()
    var released = false

    /** The tracks the next [open] file has. */
    var fileTracks: List<Track> = emptyList()

    /**
     * How far a seek reaches in a file it can't seek in: the end of the last keyframe range its read-ahead holds whole
     * (`demuxer-cache-state`'s `seekable-ranges`, which the session isn't told). As mpv 0.41, a seek past it is refused
     * ("Cannot seek in this stream") and playback goes on; a seek before what it holds read
     * ([EngineState.cacheStartSeconds]) lands at its start. Null: every seek lands.
     */
    var seekableEnd: Double? = null

    fun report(change: (EngineState) -> EngineState) {
        state = change(state)
        listener?.onState(state)
    }

    /** The last loaded file opens: [duration] long, at the load's start position. */
    fun open(duration: Double) {
        val start = loads.last().startSeconds
        report {
            it.copy(
                loaded = true, durationSeconds = duration, positionSeconds = start, tracks = fileTracks, buffering = false,
                seekable = true, playbackRestarts = 1,
            )
        }
    }

    fun play(position: Double) = report { it.copy(positionSeconds = position) }

    fun fail(message: String, httpStatus: Int? = null, kind: EngineErrorKind = EngineErrorKind.OTHER) =
        report { it.copy(loaded = false, error = EngineError(message, httpStatus, kind), tracks = emptyList()) }

    override fun load(request: LoadRequest): Long {
        loads += request
        val id = loads.size.toLong()
        report {
            it.copy(
                loadId = id, loaded = false, eof = false, error = null, buffering = true, positionSeconds = 0.0, tracks = emptyList(),
                playbackRestarts = 0, cacheStartSeconds = null, cacheEndSeconds = null, httpErrorStatus = null,
            )
        }
        return id
    }

    override fun setPaused(paused: Boolean) {
        pauses += paused
        // As mpv with keep-open: at the end of a file it pauses again on its own, until a load replaces the file.
        report { it.copy(paused = paused || it.eof) }
    }

    override fun seek(seconds: Double, exact: Boolean) {
        if (!state.seekable && seekableEnd?.let { seconds > it } == true) return
        val lands = if (state.seekable) seconds else state.cacheStartSeconds?.let { maxOf(seconds, it) } ?: seconds
        report { it.copy(positionSeconds = lands, playbackRestarts = if (it.loaded) it.playbackRestarts + 1 else it.playbackRestarts) }
    }
    override fun setSpeed(speed: Double) = report { it.copy(speed = speed) }
    override fun setSubtitleDelay(seconds: Double) = report { it.copy(subtitleDelaySeconds = seconds) }
    override fun setAudioDelay(seconds: Double) = report { it.copy(audioDelaySeconds = seconds) }
    override fun setSubtitleLift(fraction: Double) = Unit
    override fun setSubtitleStyle(style: SubtitleStyle) {
        subtitleStyles += style
    }
    override fun setVolume(volume: Double) = report { it.copy(volume = volume) }
    override fun setMuted(muted: Boolean) = report { it.copy(muted = muted) }
    override fun setVideoLayout(layout: VideoLayout) = Unit
    override fun setAudioNormalization(enabled: Boolean) = Unit
    override fun setAudioChannels(channels: AudioChannels) = Unit
    override fun setShaders(paths: List<String>) = Unit
    override fun setAudioDevice(name: String) = Unit
    override fun setStatsWanted(wanted: Boolean) = Unit

    override fun selectTrack(kind: TrackKind, id: Long?) {
        selections += kind to id
        // As mpv: the second subtitle's track can't be the first too.
        if (kind == TrackKind.SUBTITLE && state.tracks.any { it.kind == kind && it.id == id && it.secondary }) return
        report { s -> s.copy(tracks = s.tracks.map { if (it.kind == kind) it.copy(selected = it.id == id) else it }) }
    }

    override fun selectSecondSubtitle(id: Long?) {
        secondSelections += id
        // As mpv: the first subtitle's track can't be the second too.
        if (state.tracks.any { it.kind == TrackKind.SUBTITLE && it.id == id && it.selected }) return
        report { s -> s.copy(tracks = s.tracks.map { if (it.kind == TrackKind.SUBTITLE) it.copy(secondary = it.id == id) else it }) }
    }

    override fun addSubtitle(url: String, title: String?, lang: String?) {
        addedSubtitles += Triple(url, title, lang)
        report { s ->
            val id = (s.tracks.filter { it.kind == TrackKind.SUBTITLE }.maxOfOrNull { it.id } ?: 0) + 1
            s.copy(tracks = s.tracks + Track(id, TrackKind.SUBTITLE, title, lang, "subrip", selected = false, external = true))
        }
    }

    override fun setListener(listener: PlayerEngineListener?) {
        this.listener = listener
    }

    override fun release() {
        released = true
    }
}

internal class RecordingSync : SyncTrigger {
    val playing = mutableListOf<Boolean>()
    var flushes = 0

    override fun setPlaying(playing: Boolean) {
        this.playing += playing
    }

    override fun flushNow() {
        flushes++
    }
}

/** A tracker's view of a session ([PlaybackSession.observe]): each [PlaybackObserver] event as a line. */
internal class RecordingObserver : PlaybackObserver {
    val events = mutableListOf<String>()

    override fun onVideoStarted(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean) {
        events += "started ${at.positionMillis}/${at.durationMillis}"
    }

    override fun onPlayingChanged(playing: Boolean, at: PlaybackPosition) {
        events += "${if (playing) "playing" else "paused"} ${at.positionMillis}/${at.durationMillis}"
    }

    override fun onPosition(at: PlaybackPosition) {
        events += "position ${at.positionMillis}/${at.durationMillis}"
    }

    override fun onVideoStopped(at: PlaybackPosition) {
        events += "stopped ${at.positionMillis}/${at.durationMillis}"
    }
}

internal fun track(
    id: Long,
    kind: TrackKind,
    language: String?,
    title: String? = null,
    selected: Boolean = false,
    forced: Boolean = false,
    default: Boolean = false,
    external: Boolean = false,
    hearingImpaired: Boolean = false,
    visualImpaired: Boolean = false,
    codec: String? = null,
    channels: Int? = null,
) = Track(
    id = id, kind = kind, title = title, language = language, codec = codec, selected = selected, external = external, isDefault = default,
    forced = forced, hearingImpaired = hearingImpaired, visualImpaired = visualImpaired, channels = channels,
)

/** An episode's tracks as a REMUX has them (`tools/media`'s remux.mkv); English audio playing. */
internal val EPISODE_1_TRACKS = listOf(
    track(1, TrackKind.AUDIO, "en", "English - TrueHD 5.1", selected = true, default = true, codec = "truehd", channels = 6),
    track(2, TrackKind.AUDIO, "de", "Deutsch - AC-3 5.1", codec = "ac3", channels = 6),
    track(3, TrackKind.AUDIO, "en", "Commentary - AAC 2.0", codec = "aac", channels = 2),
    track(1, TrackKind.SUBTITLE, "en", "English (forced)", forced = true, default = true, codec = "hdmv_pgs_subtitle"),
    track(2, TrackKind.SUBTITLE, "en", "English", codec = "hdmv_pgs_subtitle"),
    track(3, TrackKind.SUBTITLE, "en", "English (SDH)", hearingImpaired = true, codec = "hdmv_pgs_subtitle"),
    track(4, TrackKind.SUBTITLE, "es", "Español", codec = "ass"),
    track(5, TrackKind.SUBTITLE, "fr", "Français", codec = "subrip"),
)

/** The next episode's (`tools/media`'s remux-next.mkv): the same kinds of tracks in another order, so other ids. */
internal val EPISODE_2_TRACKS = listOf(
    track(1, TrackKind.AUDIO, "en", "English - AAC 2.0", selected = true, default = true, codec = "aac", channels = 2),
    track(2, TrackKind.AUDIO, "en", "Commentary - AAC 2.0", codec = "aac", channels = 2),
    track(3, TrackKind.AUDIO, "de", "Deutsch - AAC 2.0", codec = "aac", channels = 2),
    track(1, TrackKind.SUBTITLE, "en", "English (SDH)", hearingImpaired = true, codec = "subrip"),
    track(2, TrackKind.SUBTITLE, "fr", "Français", codec = "subrip"),
    track(3, TrackKind.SUBTITLE, "en", "English", codec = "subrip"),
    track(4, TrackKind.SUBTITLE, "en", "English (forced)", forced = true, default = true, codec = "subrip"),
    track(5, TrackKind.SUBTITLE, "es", "Español", codec = "ass"),
)

/** The track of [kind] with [id]. */
internal fun List<Track>.withId(kind: TrackKind, id: Long): Track = single { it.kind == kind && it.id == id }

internal fun urlStream(url: String, name: String? = null, bingeGroup: String? = null, headers: Map<String, String> = emptyMap()) = Stream(
    source = StreamSource.Url(url),
    name = name,
    behaviorHints = StreamBehaviorHints(
        bingeGroup = bingeGroup,
        proxyHeaders = headers.takeIf { it.isNotEmpty() }?.let { StreamProxyHeaders(request = it) },
    ),
)

internal fun episode(season: Int, episode: Int, title: String = "", released: Instant? = null) =
    Video(id = "tt1:$season:$episode", title = title, released = released, seriesInfo = SeriesInfo(season, episode))

/** A series with two regular episodes and a special (specials sort last, as `MetaItem.videos` keeps them). */
internal val SERIES = MetaItem(
    MetaItemPreview(id = "tt1", type = "series", name = "Chernobyl"),
    videos = listOf(episode(1, 1, "1:23:45"), episode(1, 2, "Please Remain Calm"), episode(0, 1, "Behind the scenes")),
)

internal val MOVIE = MetaItem(MetaItemPreview(id = "tt2", type = "movie", name = "The Shawshank Redemption"))

internal const val STREAMS_URL = "https://streams.example.com/manifest.json"

internal val STREAM_ADDON = Descriptor(
    Manifest.decode("""{"id":"org.example.streams","version":"1.0.0","name":"Streams","types":["movie","series"],"resources":["stream"]}"""),
    STREAMS_URL.transportUrl(),
)

internal val SUBTITLE_ADDON = Descriptor(
    Manifest.decode("""{"id":"org.example.subs","version":"1.0.0","name":"Subs","types":["movie","series"],"resources":["subtitles"]}"""),
    "https://subs.example.com/manifest.json".transportUrl(),
)

internal fun choice(stream: Stream, addon: Descriptor = STREAM_ADDON) = StreamChoice(stream, addon.transportUrl, addon.manifest.name)

internal fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

/**
 * A session on the test's virtual clock over an in-memory database, a scripted engine and a mock HTTP world: every
 * request goes to [routes] by host, and a host without a route answers 404.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionHarness(val scope: TestScope, addons: List<Descriptor> = listOf(STREAM_ADDON), localVideos: LocalVideos? = null) {
    val profileId = "profile-1"
    val wallClock = TestClock()
    val data = LocalData(
        inMemoryDatabase(),
        { wallClock.now },
        StandardTestDispatcher(scope.testScheduler),
        HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )
    val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val http = FakeHttp(scope) { request ->
        routes[request.url.host]?.invoke(this, request) ?: respond("", HttpStatusCode.NotFound)
    }
    val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))
    val installed = addons.toMutableList()
    val engine = ScriptedEngine()
    val sync = RecordingSync()
    var now: Instant = Instant.parse("2026-09-29T12:00:00Z")

    /** The system's caption and audio-description settings, as the platform would report them. */
    val mediaAccessibility = MutableStateFlow(MediaAccessibilityPreferences())

    /** The profile's hand picks (while its suggestions are on), made on a phone on an unmetered network with an SDR screen. */
    val picks = StreamPicks(
        profileId,
        data.streamPicks,
        data.settings,
        { PickContext(DeviceClass.HANDHELD, metered = false, hdrDisplay = false, dolbyVisionDisplay = false, hardwareCodecs = emptySet()) },
        scope.backgroundScope,
    )

    val session = PlaybackSession(
        engine = engine,
        profileId = profileId,
        resolver = StreamResolver(http.client),
        subtitles = SubtitleResolver(aggregator),
        binge = BingePicker(aggregator, StreamAutoPicker(aggregator, data.streamHistory)),
        library = data.library,
        streamHistory = data.streamHistory,
        settings = data.settings,
        playbackOptions = { PlaybackOptions.of(data.deviceSettings.settings(), HardwareDecoder.APPLE) },
        subtitleStyle = data.deviceSettings.observe().map { SubtitleStyle.of(it.subtitles) },
        mediaAccessibility = mediaAccessibility,
        installedAddons = { installed.toList() },
        sync = sync,
        config = SessionConfig(userAgent = "Tortuga/test"),
        clock = object : Clock {
            override fun now(): Instant = now
        },
        parentScope = scope.backgroundScope,
        localVideos = localVideos,
        timeSource = scope.testScheduler.timeSource,
        picks = picks,
    )

    val events = mutableListOf<SessionEvent>()

    init {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { session.events.collect { events += it } }
    }

    /** Serves [bytes] at every path of `cdn.example`, with ranges, like a static file server. */
    fun serveCdn(bytes: ByteArray) {
        routes["cdn.example"] = { request -> serveFile(request, bytes) }
    }

    fun start(request: PlayRequest) {
        session.start(request)
        settle()
    }

    fun settle() = scope.runCurrent()

    fun advance(duration: Duration) {
        scope.advanceTimeBy(duration)
        scope.runCurrent()
    }

    /** Plays on to [to], reporting the position every [step] seconds of virtual time. */
    fun playTo(to: Double, step: Double = 1.0) {
        var position = engine.state.positionSeconds
        while (position < to) {
            position = minOf(position + step, to)
            engine.play(position)
            advance(step.seconds)
        }
    }

    suspend fun item(metaId: String) = data.library.item(profileId, metaId)
}
