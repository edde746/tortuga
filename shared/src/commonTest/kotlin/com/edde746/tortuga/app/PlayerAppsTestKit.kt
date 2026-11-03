@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.app

import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppCapabilities
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.PlayerAppEvents
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppsFound
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.resolve.redirect
import com.edde746.tortuga.playback.resolve.serveFile
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.RecordingObserver
import com.edde746.tortuga.playback.session.RecordingSync
import com.edde746.tortuga.playback.session.SERIES
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Clock
import kotlin.time.Duration

/** VLC as the desktop finds it: it takes everything and reports while it plays. */
internal val VLC = PlayerApp(
    "vlc", "VLC", PlayerAppKind.DETECTED,
    PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.LIVE),
)

/** MX Player as Android finds it: it takes everything and answers with where it stopped. */
internal val MX_PLAYER = PlayerApp(
    "com.mxtech.videoplayer.ad", "MX Player", PlayerAppKind.DETECTED,
    PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.ON_RETURN),
)

/** Infuse through its URL scheme: the URL only, nothing back. */
internal val INFUSE = PlayerApp("infuse", "Infuse", PlayerAppKind.DETECTED, PlayerAppCapabilities.URL_ONLY)

/**
 * A platform's player apps: [players] are found, every launch is recorded in [launches] and answered by [answer]
 * (launched, reporting as the app's capabilities say, by default); [report] sends a launch's player reports, the last
 * one's unless another is named.
 */
internal class FakePlayerLauncher(
    var players: List<PlayerApp> = listOf(VLC, MX_PLAYER, INFUSE),
    override val customPlayers: CustomPlayerKind? = CustomPlayerKind.COMMAND,
    override val handsOffFiles: Boolean = true,
) : PlayerAppLauncher {
    val launches = mutableListOf<Pair<PlayerApp, PlayerAppMedia>>()
    var answer: (PlayerApp) -> PlayerAppLaunch = { PlayerAppLaunch.Launched(it.capabilities.progress) }
    var detections = 0
    private val reporters = mutableListOf<PlayerAppEvents>()

    /** What the last launch was handed. */
    val media: PlayerAppMedia get() = launches.last().second

    override fun detect(done: PlayerAppsFound) {
        detections++
        done.onFound(players)
    }

    override suspend fun launch(app: PlayerApp, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch {
        launches += app to media
        reporters += events
        return answer(app)
    }

    /** Sends [event] as the player of [launch] (its index in [launches]) reports it. */
    fun report(event: PlayerAppEvent, launch: Int = reporters.lastIndex) = reporters[launch].onEvent(event)
}

/**
 * A running app ([open]) whose platform has [launcher]'s player apps, over a graph on the test's scheduler with a mock
 * HTTP world ([routes] by host, 404 otherwise), recording what sync is told ([sync]), what the trackers hear
 * ([observer], all hand-offs; [handoffObservers], one each) and the hand-offs' threshold events ([events]).
 */
internal class PlayerAppsWorld(val scope: TestScope, val launcher: FakePlayerLauncher = FakePlayerLauncher()) {
    val profileId = "profile-1"
    val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val http = FakeHttp(scope) { request -> routes[request.url.host]?.invoke(this, request) ?: respond("", HttpStatusCode.NotFound) }
    val sync = RecordingSync()
    val data = scope.testData()
    val graph = AppGraph(
        data = data,
        secrets = MemorySecretStore(),
        sync = sync,
        flavor = BuildFlavor.SIDELOADED,
        device = DeviceDescriptor("Test device", DevicePlatform.LINUX),
        appVersion = "test",
        http = http.client,
        scope = CoroutineScope(scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job])),
        clock = Clock.System,
        parseDispatcher = StandardTestDispatcher(scope.testScheduler),
    )
    val platform = FakeAppPlatform(playerApps = launcher)
    val observer = RecordingObserver()

    /** Each hand-off's own observer, in the order the hand-offs began. */
    val handoffObservers = mutableListOf<RecordingObserver>()

    /** A new running app for the profile (stored first), its settings read and its players found. */
    suspend fun open(): AppSession {
        val app = AppSession(graph, profileId, platform, start = null, profileAccount = null, startProfile = data.addProfile(profileId))
        val handoff = checkNotNull(app.playerAppHandoff)
        handoff.observe { observer }
        handoff.observe { RecordingObserver().also { handoffObservers += it } }
        settle()
        return app
    }

    /** Hosted links: `addon.example/<name>` redirects to `cdn.example/<name>.mkv`, a file served with ranges. */
    fun serveHostedLinks() {
        routes["addon.example"] = { request -> redirect("https://cdn.example/${request.url.segments.last()}.mkv") }
        routes["cdn.example"] = { request -> serveFile(request, FILE) }
    }

    /** A meta addon answering for [MOVIE] and [SERIES], installed for the profile (Detail shows them). */
    suspend fun installMetaAddon() {
        routes["meta.example"] = { request ->
            when (request.url.encodedPath) {
                "/meta/movie/${MOVIE.id}.json" -> json("""{"meta":{"id":"${MOVIE.id}","type":"movie","name":"${MOVIE.name}"}}""")
                "/meta/series/${SERIES.id}.json" -> json(
                    """{"meta":{"id":"${SERIES.id}","type":"series","name":"${SERIES.name}","videos":[
                        {"id":"tt1:1:1","title":"1:23:45","season":1,"episode":1},
                        {"id":"tt1:1:2","title":"Please Remain Calm","season":1,"episode":2},
                        {"id":"tt1:0:1","title":"Behind the scenes","season":0,"episode":1}
                    ]}}""",
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        data.addons.install(
            profileId,
            "https://meta.example/manifest.json",
            """{"id":"org.example.meta","version":"1.0.0","name":"Meta","types":["movie","series"],"resources":["meta"],"idPrefixes":["tt"]}""",
        )
    }

    /** The movie from its hosted link. */
    fun movie(): PlayRequest = PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie")))

    /** The first episode from its hosted link. */
    fun episode(): PlayRequest = PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://addon.example/s01e01")))

    suspend fun item(metaId: String) = data.library.item(profileId, metaId)

    fun settle() = scope.runCurrent()

    fun advance(duration: Duration) {
        scope.advanceTimeBy(duration)
        scope.runCurrent()
    }

    private companion object {
        val FILE = ByteArray(300_000) { (it * 31).toByte() }
    }
}
