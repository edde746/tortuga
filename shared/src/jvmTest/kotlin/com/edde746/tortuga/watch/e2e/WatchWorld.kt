package com.edde746.tortuga.watch.e2e

import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.app.ActivePlayback
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.AppPlatform
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.BundledText
import com.edde746.tortuga.app.PlatformPlayback
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.RecordingObserver
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerStore
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.presentation.watch.WatchPromptUi
import com.edde746.tortuga.presentation.watch.WatchStore
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.watch.WatchState
import com.edde746.tortuga.watch.WatchTogether
import com.edde746.tortuga.watch.media.RoomFile
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import party.openwatch.Participant
import party.openwatch.PartyEvent
import party.openwatch.PartyState
import party.openwatch.Relays
import party.openwatch.Room
import party.openwatch.WatchParty
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A video file the fake addons offer, served at `/files/<name>`; the simulated players play it for [durationSeconds],
 * and seek in it only when it is [seekable] (a stream without an index isn't).
 */
internal class MediaFile(val name: String, val size: Int, val durationSeconds: Double, seed: Long, val seekable: Boolean = true) {
    val bytes: ByteArray = lcgBytes(size, seed)
}

/** A stream a fake addon offers for a video: [file], named [title], with [file]'s size and name as its hints. */
internal class Offer(val file: MediaFile, val title: String)

/**
 * A fake Stremio addon at `/<id>/manifest.json`: [metas] (JSON objects) and the [streams] of each video id; with
 * [streamsFail], every stream request fails (HTTP 500).
 */
internal class FakeAddon(
    val id: String,
    val name: String,
    val metas: Map<String, String>,
    val streams: Map<String, List<Offer>>,
    val streamsFail: Boolean = false,
)

/**
 * Watch together end to end in one JVM, on a real Open Watch Party relay at [relayUrl] (`-Ptortuga.e2e.watchRelay`:
 * already running for the test): fake addons and range-served files on a local HTTP server (the JDK's),
 * [TestApp]s, whole apps ([AppGraph] and [AppSession], each on a main thread of its own, the relay as their
 * self-hosted relay) whose players are [SimulatedEngine]s, and [OtherApp]s, another app's participant (the SDK alone).
 * Everything runs in real time.
 */
internal class WatchWorld private constructor(
    val relayUrl: String,
    private val files: Map<String, MediaFile>,
    private val addons: Map<String, FakeAddon>,
) : AutoCloseable {
    private val serverThreads = Executors.newCachedThreadPool { Thread(it, "fake-addons").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0).apply {
        executor = serverThreads
        createContext("/") { exchange -> serve(exchange) }
        start()
    }
    private val apps = CopyOnWriteArrayList<TestApp>()
    private val others = CopyOnWriteArrayList<OtherApp>()

    /** The fake addons' and files' server. */
    val baseUrl: String = "http://$LOOPBACK:${server.address.port}"

    fun addonUrl(id: String) = "$baseUrl/$id/manifest.json"

    fun fileUrl(file: MediaFile) = "$baseUrl/files/${file.name}"

    /** The file the simulated players play at [url]; null for a URL that isn't one. */
    fun fileOf(url: String): MediaFile? = files[url.substringAfterLast('/')]

    private fun serve(exchange: HttpExchange) {
        try {
            val parts = exchange.requestURI.path.trim('/').split('/')
            when {
                parts.size == 2 && parts[0] == "files" -> serveFile(exchange, files[parts[1]])
                parts.size == 2 && parts[1] == "manifest.json" -> {
                    val addon = addons[parts[0]] ?: return respond(exchange, 404)
                    respondJson(
                        exchange,
                        """{"id":"org.example.${addon.id}","version":"1.0.0","name":"${addon.name}","types":["movie","series"],"resources":["meta","stream"],"catalogs":[]}""",
                    )
                }
                parts.size == 4 -> {
                    val addon = addons[parts[0]] ?: return respond(exchange, 404)
                    val id = parts[3].removeSuffix(".json")
                    if (parts[1] == "stream" && addon.streamsFail) return respond(exchange, 500)
                    val body = when (parts[1]) {
                        "meta" -> addon.metas[id]?.let { """{"meta":$it}""" }
                        "stream" -> addon.streams[id]?.let { offers -> """{"streams":[${offers.joinToString(",") { streamJson(it) }}]}""" }
                        else -> null
                    }
                    respondJson(exchange, body ?: """{"streams":[]}""")
                }
                else -> respond(exchange, 404)
            }
        } finally {
            exchange.close()
        }
    }

    /** [file]'s bytes, the range asked for when there is one (the stream resolver's size and hash requests). */
    private fun serveFile(exchange: HttpExchange, file: MediaFile?) {
        if (file == null) return respond(exchange, 404)
        val size = file.bytes.size
        val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
            ?: return respond(exchange, 200, file.bytes, OCTET_STREAM)
        val first = range[0].toIntOrNull()
        if (first == null || first >= size) {
            exchange.responseHeaders.add("Content-Range", "bytes */$size")
            return respond(exchange, 416)
        }
        val last = range.getOrNull(1)?.toIntOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$size")
        respond(exchange, 206, file.bytes.copyOfRange(first, last + 1), OCTET_STREAM)
    }

    private fun respondJson(exchange: HttpExchange, json: String) = respond(exchange, 200, json.encodeToByteArray(), "application/json")

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0), contentType: String? = null) {
        contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        try {
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.write(body)
        } catch (e: IOException) {
            // The client stopped reading: the stream resolver reads the start of a file only.
        }
    }

    private fun streamJson(offer: Offer): String =
        """{"url":"${fileUrl(offer.file)}","name":"${offer.title}","behaviorHints":{"videoSize":${offer.file.size},"filename":"${offer.file.name}"}}"""

    /** Starts the app of [name] (its profile's name): an in-memory database, [addons] installed, the relay as its relay. */
    suspend fun app(name: String, addons: List<String>, openDelay: Duration = 300.milliseconds): TestApp {
        val app = TestApp(name, this, openDelay)
        apps += app
        app.start(addons.map(::addonUrl))
        return app
    }

    /** Another app's participant [name] on the relay, as the client [client] (`<app>/<version>`). */
    fun other(name: String, client: String): OtherApp = OtherApp(name, client, relayUrl).also { others += it }

    /** What every app sees, for a failure's message. */
    fun describe(): String = (apps.map { it.describe() } + others.map { it.describe() }).joinToString("\n")

    /**
     * Waits until [condition] holds (read on the test thread; apps' state is read through their flows and engines),
     * failing with [what] and every app's state after [timeout].
     */
    suspend fun until(what: String, timeout: Duration = 20.seconds, condition: suspend () -> Boolean) {
        val met = withTimeoutOrNull(timeout) {
            while (!condition()) delay(20)
            true
        }
        if (met == null) throw AssertionError("timed out after $timeout waiting for: $what\n${describe()}")
    }

    override fun close() {
        for (app in apps.asReversed()) app.close()
        for (other in others) other.close()
        server.stop(0)
        serverThreads.shutdownNow()
    }

    companion object {
        /** The Gradle property, and system property, of the relay's base URL. */
        const val RELAY_PROPERTY = "tortuga.e2e.watchRelay"

        private const val LOOPBACK = "127.0.0.1"
        private const val OCTET_STREAM = "application/octet-stream"

        /** The world, once the relay answers its `/health`. */
        suspend fun start(files: List<MediaFile>, addons: List<FakeAddon>): WatchWorld {
            val relay = System.getProperty(RELAY_PROPERTY)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: throw AssertionError("-P$RELAY_PROPERTY is not set: give it a running Open Watch Party relay's base URL")
            val healthy = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URI("$relay/health").toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    try {
                        connection.responseCode == 200
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(false)
            }
            if (!healthy) throw AssertionError("the relay at $relay doesn't answer /health")
            return WatchWorld(relay, files.associateBy { it.name }, addons.associateBy { it.id })
        }
    }
}

/**
 * One app, as a device runs it: its graph and profile session on a main thread of its own ([on]), the app's HTTP client
 * (OkHttp), and simulated players, each opening a file [openDelay] after its load. [observer] hears every playback
 * session of the app as the trackers would; [events] are the room's events.
 */
internal class TestApp(val name: String, private val world: WatchWorld, openDelay: Duration) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "app-$name").apply { isDaemon = true } }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val http = TortugaHttpClient.create("test")
    private val observer = RecordingObserver()

    /** How long this app's players take to open a file; a change holds from the next playback on. */
    @Volatile
    var openDelay: Duration = openDelay

    val events = CopyOnWriteArrayList<PartyEvent>()

    lateinit var graph: AppGraph
        private set
    lateinit var session: AppSession
        private set
    lateinit var profileId: String
        private set

    /** The lobby, once [lobby] opened it. */
    private var lobbyStore: WatchStore? = null

    val watch: WatchTogether get() = session.watchTogether
    val state: WatchState get() = watch.state.value
    val party: PartyState<RoomFile> get() = state.party

    /** The room this app is in (connected, or resuming), or null. */
    val room: Room? get() = state.takeIf { it.inRoom }?.party?.room
    val active: ActivePlayback? get() = session.state.value.playback
    val store: PlayerStore get() = checkNotNull(active) { "$name has no playback" }.store

    /** The engine of the playback open now. */
    val engine: SimulatedEngine get() = checkNotNull(active) { "$name has no playback" }.engine as SimulatedEngine

    private val engineOrNull: SimulatedEngine? get() = active?.engine as? SimulatedEngine

    /** Runs [block] on this app's main thread. */
    suspend fun <T> on(block: suspend () -> T): T = withContext(dispatcher) { block() }

    internal suspend fun start(addonUrls: List<String>) {
        boot(addonUrls)
        // The relay setting reaches the room's state through the settings' flow: no room before it did, so nothing here
        // ever goes to the public relays.
        val relay = checkNotNull(WatchRelayEndpoint.parse(world.relayUrl)) { "${world.relayUrl} isn't a relay base URL" }
        checkNotNull(withTimeoutOrNull(10.seconds) { watch.state.first { it.relay == relay } }) { "$name's relay setting didn't take" }
    }

    private suspend fun boot(addonUrls: List<String>) = on {
        val data = LocalData(inMemoryDatabase(), { System.currentTimeMillis() }, dispatcher, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        graph = AppGraph(
            data = data,
            secrets = MemorySecretStore(),
            sync = SyncTrigger.None,
            flavor = BuildFlavor.SIDELOADED,
            device = DeviceDescriptor("$name's computer", DevicePlatform.LINUX),
            appVersion = "test",
            http = http,
            scope = scope,
            clock = Clock.System,
        )
        profileId = data.profiles.create(name, avatar = null).id
        val registry = graph.addonRegistry(profileId)
        for (url in addonUrls) registry.install(url.transportUrl())
        data.deviceSettings.set(DeviceSetting.WatchRelay, world.relayUrl)
        session = AppSession(graph, profileId, Platform(), start = null, profileAccount = null)
        // As the trackers do: each playback session of the app is observed from its start.
        scope.launch { session.state.map { it.playback }.distinctUntilChanged().collect { it?.session?.observe(observer) } }
        scope.launch { watch.events.collect { events += it } }
    }

    /** The lobby (the Watch together screen), opened once. */
    suspend fun lobby(): WatchStore = on { lobbyStore ?: session.watchStore().also { lobbyStore = it } }

    /** What this app's trackers heard of its playbacks, read on its main thread. */
    suspend fun trackerEvents(): List<String> = on { observer.events.toList() }

    /** [videoId] of [type]/[metaId] as this app's addons offer it: the meta, and the streams in their order. */
    suspend fun title(type: String, metaId: String, videoId: String): Pair<MetaItem, List<StreamChoice>> = on {
        val addons = graph.addonRegistry(profileId).load()
        val meta = graph.aggregator.meta(addons, type, metaId).last().firstNotNullOf { (it.state as? Loadable.Ready)?.content }
        val streams = graph.aggregator.streams(addons, type, videoId).last().flatMap { state ->
            (state.state as? Loadable.Ready)?.content.orEmpty().map { StreamChoice(it, state.addon.transportUrl, state.addon.manifest.name) }
        }
        meta to streams
    }

    /** Plays [videoId] from its first stream, as picking it in the Streams sheet does. */
    suspend fun play(type: String, metaId: String, videoId: String, streamIndex: Int = 0) {
        val (meta, streams) = title(type, metaId, videoId)
        on { session.playback.play(PlayRequest(meta, videoId, streams[streamIndex])) }
    }

    /** The participant [name] as this app's room lists it. */
    fun participant(name: String): Participant? = room?.participants?.firstOrNull { it.name == name }

    /** The player is attached to the room with a file: it follows the room, and its controls go to the room. */
    fun attached(): Boolean = watch.playback.attached.value != null

    /** The player has [file] loaded last. */
    fun plays(file: MediaFile): Boolean = engineOrNull?.url == world.fileUrl(file)

    fun playing(): Boolean {
        val s = engineOrNull?.state ?: return false
        return s.loaded && !s.paused && !s.eof
    }

    fun paused(): Boolean {
        val s = engineOrNull?.state ?: return false
        return s.loaded && s.paused
    }

    /** The player's position now. */
    fun position(): Double = checkNotNull(positionAt(System.nanoTime())) { "$name has no playback" }

    /** The player's position at [nanos] ([System.nanoTime]), or null without a playback. */
    fun positionAt(nanos: Long): Double? = engineOrNull?.positionAt(nanos)

    /** The player's question to a guest (a file of another length, none found), or null. */
    fun prompt(): WatchPromptUi? = active?.store?.state?.value?.watch?.prompt

    /** Whether the room told this app that [by] did [kind] (`play`, `pause`, `seek`, `rate`, ...). */
    fun heard(kind: String, by: String): Boolean = events.any { it is PartyEvent.Action && it.kind == kind && it.by?.name == by }

    fun describe(): String {
        if (!::session.isInitialized) return "$name: not started"
        val state = state
        val party = state.party
        val room = party.room
        val engine = engineOrNull
        return buildString {
            append("$name: connection=${party.connection} room=${room?.code} host=${party.isHost} phase=${room?.phase} epoch=${room?.epoch}")
            append(" position=${room?.positionMs} rate=${room?.rate} control=${room?.control} locked=${room?.locked} waitingOn=${room?.waitingOn}")
            append("\n  participants=${room?.participants?.map { "${it.name}(host=${it.host},connected=${it.connected},player=${it.player},match=${it.match},offset=${it.offsetMs})" }}")
            append("\n  media=${state.media?.videoId}/${party.media?.durationMs} match=${party.match} choice=${party.choice?.ref} mismatch=${party.mismatch?.let { "${it.candidate.ref} ${it.deltaMs}" }}")
            append(" offset=${party.offsetMs} player=${party.player} attached=${watch.playback.attached.value} activity=${state.activity} failure=${state.failure} end=${party.endReason}")
            if (engine != null) {
                val s = engine.state
                append("\n  engine: url=${engine.url?.substringAfterLast('/')} loaded=${s.loaded} paused=${s.paused} eof=${s.eof} position=${"%.2f".format(engine.positionAt())} speed=${s.speed} loads=${engine.loads.map { it.substringAfterLast('/') }}")
            }
        }
    }

    internal fun close() {
        runCatching {
            runBlocking {
                withTimeoutOrNull(5.seconds) {
                    on {
                        lobbyStore?.release()
                        if (::session.isInitialized) session.close()
                    }
                    // The room's leave runs on in the background: give it a moment.
                    delay(200)
                }
            }
        }
        scope.cancel()
        http.close()
        executor.shutdownNow()
    }

    private inner class Platform : AppPlatform {
        override val formFactor = FormFactor.DESKTOP
        override val playerConfig = PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis = 500)

        override fun createPlayback(): PlatformPlayback {
            val engine = SimulatedEngine(world::fileOf, scope, openDelay)
            return object : PlatformPlayback {
                override val engine: PlayerEngine = engine
                override val hardwareDecoder = HardwareDecoder.WINDOWS
                override val mediaControls = null
                override val output = null

                override fun keepDisplayAwake(awake: Boolean) = Unit
            }
        }

        override fun setFullscreen(fullscreen: Boolean) = Unit
        override fun openUrl(url: String) = true
        override fun setClipboardText(text: String, sensitive: Boolean) = Unit
        override fun deviceFeatures() = DeviceFeatures(hardwareDecoding = true, hdr = false, matchFrameRate = false, audioPassthrough = false)
        override fun bundledTexts(folder: String): List<BundledText> = emptyList()
        override fun exit() = Unit
    }
}

/**
 * Another app's participant: Open Watch Party's SDK alone ([WatchParty]) as [name] with the client `<app>/<version>`
 * [client], on a thread of its own ([on]), with no player and no resolver: its media is the descriptor it sets.
 */
internal class OtherApp(val name: String, client: String, relayUrl: String) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "other-$name").apply { isDaemon = true } }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val http = TortugaHttpClient.create("test")
    private val party = WatchParty<String>(relays = Relays.at(http, relayUrl), client = client, name = name, scope = scope)

    val state: PartyState<String> get() = party.state.value

    /** Runs [block] on the party's thread. */
    suspend fun <T> on(block: suspend WatchParty<String>.() -> T): T = withContext(dispatcher) { party.block() }

    /** The participant [name] as the room lists it. */
    fun participant(name: String): Participant? = state.room?.participants?.firstOrNull { it.name == name }

    /** The room's position now, in seconds. */
    suspend fun roomPosition(): Double = checkNotNull(on { roomPositionMs() }) { "$name's room has no position" } / 1000.0

    fun describe(): String {
        val room = state.room
        return "$name (other app): connection=${state.connection} room=${room?.code} phase=${room?.phase} epoch=${room?.epoch} " +
            "position=${room?.positionMs} participants=${room?.participants?.map { "${it.name}(host=${it.host},player=${it.player},match=${it.match})" }}"
    }

    internal fun close() {
        runCatching {
            runBlocking {
                withTimeoutOrNull(5.seconds) {
                    withContext(dispatcher) {
                        party.leave()
                        party.close()
                    }.join()
                }
            }
        }
        scope.cancel()
        http.close()
        executor.shutdownNow()
    }
}

/** The largest difference between the apps' player positions at one instant; infinite when one has no playback. */
internal fun spread(apps: List<TestApp>): Double {
    val now = System.nanoTime()
    val positions = apps.map { it.positionAt(now) ?: return Double.POSITIVE_INFINITY }
    return positions.max() - positions.min()
}
