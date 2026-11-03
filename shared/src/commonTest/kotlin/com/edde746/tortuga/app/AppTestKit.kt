package com.edde746.tortuga.app

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.profile.encodeHomeCatalogs
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.output.DisplayInfo
import com.edde746.tortuga.playback.output.OutputPlan
import com.edde746.tortuga.playback.output.PlaybackOutputHost
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.SERIES
import com.edde746.tortuga.playback.session.ScriptedEngine
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.homescreen.HomeScreen
import com.edde746.tortuga.presentation.homescreen.HomeScreenItem
import com.edde746.tortuga.presentation.player.MediaCommandHandler
import com.edde746.tortuga.presentation.player.MediaControlsHost
import com.edde746.tortuga.presentation.player.NowPlaying
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerStore
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import com.edde746.tortuga.data.db.Profile as ProfileRow

/**
 * The OS side of the tests' app: every playback, fullscreen change and release lands in [log] in the order it happened,
 * a playback's entries prefixed with its number (`1: engine released`). With [playerApps], the device has player apps.
 * [opensUrls] false is a device where nothing opens a link (an Apple TV): [opened] still records what was asked. With
 * [homeScreen], the device is a TV with a home screen. [formFactor]: a desktop unless a test says otherwise.
 */
internal class FakeAppPlatform(
    override val installLinksOverScreen: Boolean = false,
    override val playerApps: PlayerAppLauncher? = null,
    var opensUrls: Boolean = true,
    override val homeScreen: FakeHomeScreen? = null,
    override val formFactor: FormFactor = FormFactor.DESKTOP,
) : AppPlatform {
    val log = mutableListOf<String>()
    val playbacks = mutableListOf<FakePlatformPlayback>()
    val opened = mutableListOf<String>()
    val clipboard = mutableListOf<String>()
    var exits = 0

    override val playerConfig = PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis = 500)

    override fun createPlayback(): PlatformPlayback = FakePlatformPlayback(playbacks.size + 1, log).also { playbacks += it }

    override fun setFullscreen(fullscreen: Boolean) {
        log += "fullscreen $fullscreen"
    }

    override fun openUrl(url: String): Boolean {
        opened += url
        return opensUrls
    }

    override fun setClipboardText(text: String, sensitive: Boolean) {
        clipboard += text
    }

    override fun deviceFeatures() =
        DeviceFeatures(hardwareDecoding = true, hdr = false, matchFrameRate = false, audioPassthrough = false)

    /** The tests' app bundles no resources. */
    override fun bundledTexts(folder: String): List<BundledText> = emptyList()

    override fun exit() {
        exits++
    }
}

/** A TV's home screen: [published] is every list the app published, in order. */
internal class FakeHomeScreen : HomeScreen {
    val published = mutableListOf<List<HomeScreenItem>>()

    override fun publish(items: List<HomeScreenItem>) {
        published += items
    }
}

/**
 * One playback's engine (a [ScriptedEngine]), media controls and display output, logging what happens to them; [awake]
 * is every [keepDisplayAwake] it got, in order.
 */
internal class FakePlatformPlayback(private val number: Int, private val log: MutableList<String>) : PlatformPlayback {
    val scripted = ScriptedEngine()
    var attached: PlayerStore? = null
        private set
    val awake = mutableListOf<Boolean>()

    override val engine: PlayerEngine = object : PlayerEngine by scripted {
        override fun release() {
            scripted.release()
            log += "$number: engine released"
        }
    }

    override val hardwareDecoder = HardwareDecoder.WINDOWS

    override val mediaControls = object : MediaControlsHost {
        override fun show(nowPlaying: NowPlaying, commands: MediaCommandHandler) = Unit

        override fun hide() {
            log += "$number: controls hidden"
        }
    }

    override val output = object : PlaybackOutputHost {
        override suspend fun display() = DisplayInfo(modes = emptyList(), current = null, hdrSupported = false, hdrEnabled = false)

        override fun apply(plan: OutputPlan) = Unit

        override fun restore() {
            log += "$number: display restored"
        }
    }

    override fun attach(store: PlayerStore) {
        attached = store
        log += "$number: attached"
    }

    override fun keepDisplayAwake(awake: Boolean) {
        this.awake += awake
    }

    override fun release() {
        log += "$number: platform released"
    }
}

/**
 * An [AppGraph] on the test's scheduler: [data] (by default an in-memory database of its own), [http] (by default a
 * client every request of which gets a 404: streams don't resolve, and most tests need nothing else), [clock] (by
 * default the system's), and a scope that ends with the test.
 */
internal fun TestScope.testGraph(
    data: LocalData = testData(),
    http: HttpClient = FakeHttp(this) { respond("", HttpStatusCode.NotFound) }.client,
    clock: Clock = Clock.System,
): AppGraph = AppGraph(
    data = data,
    secrets = MemorySecretStore(),
    sync = SyncTrigger.None,
    flavor = BuildFlavor.SIDELOADED,
    device = DeviceDescriptor("Test device", DevicePlatform.LINUX),
    appVersion = "test",
    http = http,
    scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])),
    clock = clock,
    parseDispatcher = StandardTestDispatcher(testScheduler),
    timeSource = testScheduler.timeSource,
)

/**
 * Stores the profile [id], named [name] and not a kids profile, as a sync from another device would, and returns it:
 * for the tests that run a profile by an id of their own. The kids rules count a profile that isn't stored as a kids
 * profile, and a session started without its profile ([AppSession]'s `startProfile`) too until it has read it.
 */
internal suspend fun LocalData.addProfile(id: String, name: String = "Alice"): Profile {
    db.write {
        q.profileQueries.upsert(
            ProfileRow(
                profile_id = id,
                name = name,
                avatar = null,
                pin_hash = null,
                created_at = now(),
                kids = false,
                home_catalogs = encodeHomeCatalogs(emptyList()),
                record_id = RecordKey.Profile(id).recordId(),
                hlc = tick().toString(),
                server_version = null,
                dirty = true,
                deleted = false,
            ),
        )
    }
    return checkNotNull(profiles.profile(id))
}

/** The first episode of the tests' series, from a stream that doesn't resolve. */
internal val EPISODE_1 = PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://cdn.example/s01e01.mkv")))

/** A movie of its own, from a stream that doesn't resolve. */
internal val MOVIE_REQUEST = PlayRequest(MOVIE, "tt2", choice(urlStream("https://cdn.example/movie.mkv")))

/** Local data in an in-memory database on the test's scheduler. */
internal fun TestScope.testData(): LocalData {
    val clock = TestClock()
    return LocalData(
        inMemoryDatabase(), { clock.now }, StandardTestDispatcher(testScheduler),
        HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )
}
