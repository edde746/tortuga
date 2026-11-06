package com.edde746.tortuga.downloads

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.resolve.headers
import com.edde746.tortuga.playback.resolve.respondRange
import com.edde746.tortuga.playback.session.STREAM_ADDON
import com.edde746.tortuga.playback.session.SUBTITLE_ADDON
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A scripted [DownloadTransport]: [start] records the request and runs [onStart] (by default nothing: the test ends the
 * transfer with [finish]); [stop] answers [TransferResult.Stopped] for a transfer it runs; [reattach] reports the ids in
 * [stillRunning] as still running.
 */
internal class FakeTransport : DownloadTransport {
    val started = mutableListOf<TransferRequest>()
    val stopped = mutableListOf<String>()
    val running = mutableSetOf<String>()
    var stillRunning: Set<String> = emptySet()
    var listener: TransferListener? = null
    var onStart: (TransferRequest, TransferListener) -> Unit = { _, _ -> }

    override fun start(request: TransferRequest, listener: TransferListener) {
        started += request
        running += request.id
        this.listener = listener
        onStart(request, listener)
    }

    override fun stop(id: String) {
        stopped += id
        if (running.remove(id)) listener?.onResult(id, TransferResult.Stopped)
    }

    override fun reattach(ids: List<String>, listener: TransferListener, done: (running: List<String>) -> Unit) {
        this.listener = listener
        val still = ids.filter { it in stillRunning }
        running += still
        done(still)
    }

    /** Ends the transfer [id] with [result] (writing [bytes] into its part file first, for a Completed one). */
    fun finish(id: String, result: TransferResult) {
        running -= id
        listener?.onResult(id, result)
    }

    fun progress(id: String, bytes: Long, total: Long?) {
        listener?.onProgress(id, bytes, total)
    }
}

/** A dispatcher that, while [held], keeps what is dispatched to it (in order) until released: a disk too busy to commit. */
internal class HeldDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()

    var held = false
        set(value) {
            field = value
            if (value) return
            val released = waiting.toList()
            waiting.clear()
            released.forEach { (context, block) -> inner.dispatch(context, block) }
        }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (held) waiting += context to block else inner.dispatch(context, block)
    }
}

/**
 * The OS side of the tests' downloads: one location in a temporary directory, its free space set by the test; the next
 * [locationFailures] reads of the locations throw, as a volume that can't be read does.
 */
internal class FakeDownloadPlatform(
    val root: Path,
    override val transport: DownloadTransport,
    override val wifiOnlyDefault: Boolean? = true,
) : DownloadPlatform {
    var free: Long? = 1L shl 40
    var locationList: List<DownloadLocation> = listOf(DownloadLocation(DownloadLocation.INTERNAL, root.toString(), "Internal storage", removable = false))
    var locationFailures = 0
    val queueActive = mutableListOf<Pair<Boolean, Boolean>>()
    val excluded = mutableListOf<String>()

    override fun locations(): List<DownloadLocation> {
        if (locationFailures > 0) {
            locationFailures--
            throw IOException("the volume can't be read")
        }
        return locationList
    }

    override fun freeBytes(path: String): Long? = free

    override fun onQueueActive(active: Boolean, wifiOnly: Boolean) {
        queueActive += active to wifiOnly
    }

    override fun excludeFromBackup(path: String) {
        excluded += path
    }
}

/**
 * Downloads on the test's virtual clock: an [AppGraph] over an in-memory database and a mock HTTP world (every request
 * goes to [routes] by host; a host without a route answers 404), with [STREAM_ADDON] and [SUBTITLE_ADDON] installed for
 * [profileId], and a [FakeDownloadPlatform] whose location is a fresh temporary directory ([cleanUp] deletes it). The
 * transport is the real [KtorTransport] over the mock world, or [fakeTransport] with `fake = true`.
 *
 * Call [install] (after setting up what the launch should find), then drive it with [settle] and [advance].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DownloadsHarness(val scope: TestScope, fake: Boolean = false) {
    val root: Path = Path(SystemTemporaryDirectory, "tortuga-downloads-" + Random.nextLong().toULong())
    val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val http = FakeHttp(scope) { request -> routes[request.url.host]?.invoke(this, request) ?: respond("", HttpStatusCode.NotFound) }
    val wallClock = TestClock()

    /** Where file operations run: [HeldDispatcher.held] holds every one. */
    val io = HeldDispatcher(StandardTestDispatcher(scope.testScheduler))

    /** The database's dispatcher: [HeldDispatcher.held] holds every read and write. */
    val database = HeldDispatcher(StandardTestDispatcher(scope.testScheduler))
    val data = LocalData(inMemoryDatabase(), { wallClock.now }, database, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
    val graph = AppGraph(
        data = data,
        secrets = MemorySecretStore(),
        sync = SyncTrigger.None,
        flavor = BuildFlavor.SIDELOADED,
        device = DeviceDescriptor("Test device", DevicePlatform.LINUX),
        appVersion = "test",
        http = http.client,
        scope = CoroutineScope(scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job])),
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(wallClock.now)
        },
        parseDispatcher = StandardTestDispatcher(scope.testScheduler),
    )
    val fakeTransport = FakeTransport()
    val transport: DownloadTransport = if (fake) fakeTransport else KtorTransport(http.client, io, SystemFileSystem, scope.testTimeSource)
    val platform = FakeDownloadPlatform(root, transport)
    lateinit var profileId: String
        private set
    lateinit var manager: DownloadManager
        private set

    /** Creates the profile with the test addons; call before [install]. */
    suspend fun createProfile(name: String = "Alex", addons: List<Descriptor> = listOf(STREAM_ADDON, SUBTITLE_ADDON)): String {
        val profile = data.profiles.create(name, null)
        data.addons.replaceAll(profile.id, addons.map { AddonDescriptor(it.transportUrl, ADDON_MANIFESTS.getValue(it.transportUrl)) })
        if (!::profileId.isInitialized) profileId = profile.id
        return profile.id
    }

    /** [AppGraph.installDownloads] on this harness, then the launch's work settles. */
    fun install(): DownloadManager {
        manager = graph.installDownloads(platform, io, SystemFileSystem, scope.testTimeSource)
        settle()
        return manager
    }

    fun settle() = scope.runCurrent()

    fun advance(duration: Duration) {
        scope.advanceTimeBy(duration)
        scope.runCurrent()
    }

    fun network(online: Boolean, metered: Boolean = false) {
        graph.setNetwork(NetworkState(online, metered))
        settle()
    }

    /** Serves [bytes] at every path of `cdn.example` like a static file server with [etag] ([serveVideo]). */
    fun serveCdn(bytes: ByteArray, etag: String = "\"v1\"") {
        routes["cdn.example"] = { request -> serveVideo(request, bytes, etag) }
    }

    /** The stream addon answers [streams] for every video. */
    fun serveStreams(streams: (videoId: String) -> List<Stream>) {
        routes["streams.example.com"] = { request ->
            val videoId = request.url.segments.last().removeSuffix(".json")
            json("""{"streams":${AddonJson.encodeToString(ListSerializer(Stream.serializer()), streams(videoId))}}""")
        }
    }

    /** The transfers' requests to `cdn.example` (not the resolves' ranged reads): whole, or from an offset on. */
    fun transferRequests(): List<HttpRequestData> =
        http.requests.filter { it.url.host == "cdn.example" && it.headers[HttpHeaders.Range]?.endsWith("-") != false }

    /** The download [id] as the manager shows it now. */
    fun download(id: Long): Download? = manager.all.value.firstOrNull { it.id == id }

    fun file(relativePath: String): Path = child(root.toString(), relativePath)

    fun exists(relativePath: String): Boolean = SystemFileSystem.exists(file(relativePath))

    fun cleanUp() {
        DownloadFiles(SystemFileSystem).deleteRecursively(root)
    }
}

/** The tests' movie, a file at `cdn.example`. */
internal val FILM = MetaItem(MetaItemPreview(id = "tt0111161", type = "movie", name = "The Shawshank Redemption", releaseInfo = "1994", poster = "https://img.example/poster.jpg"))

/** The tests' series: three episodes of season 1, two of season 2, a special. */
internal val SHOW = MetaItem(
    MetaItemPreview(id = "tt0903747", type = "series", name = "Breaking Bad", releaseInfo = "2008-2013", poster = "https://img.example/bb.jpg"),
    videos = listOf(
        showEpisode(1, 1, "Pilot"),
        showEpisode(1, 2, "Cat's in the Bag..."),
        showEpisode(1, 3, "...And the Bag's in the River"),
        showEpisode(2, 1, "Seven Thirty-Seven"),
        showEpisode(2, 2, "Grilled"),
        showEpisode(0, 1, "Making of"),
    ),
)

internal fun showEpisode(season: Int, episode: Int, title: String) =
    Video(id = "tt0903747:$season:$episode", title = title, seriesInfo = SeriesInfo(season, episode), thumbnail = "https://img.example/$season-$episode.jpg")

/** A stream of `cdn.example` from the test stream addon. */
internal fun cdnChoice(path: String = "film.mkv", name: String? = null, bingeGroup: String? = null): StreamChoice =
    choice(urlStream("https://cdn.example/$path", name = name, bingeGroup = bingeGroup))

private val ADDON_MANIFESTS = mapOf(
    STREAM_ADDON.transportUrl to """{"id":"org.example.streams","version":"1.0.0","name":"Streams","types":["movie","series"],"resources":["stream"]}""",
    SUBTITLE_ADDON.transportUrl to """{"id":"org.example.subs","version":"1.0.0","name":"Subs","types":["movie","series"],"resources":["subtitles"]}""",
)

/**
 * Answers like a static file server with [etag]: the whole file without `Range` (or when `If-Range` names another
 * validator), else a 206 for `bytes=<first>-[<last>]`, or 416 past the end.
 */
internal fun MockRequestHandleScope.serveVideo(request: HttpRequestData, file: ByteArray, etag: String = "\"v1\""): HttpResponseData {
    val size = file.size.toLong()
    val range = request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.split('-')
    val ifRange = request.headers[HttpHeaders.IfRange]
    if (range == null || ifRange != null && ifRange != etag) {
        return respond(file, HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "$size", HttpHeaders.ETag to etag))
    }
    val first = range[0].toLong()
    if (first >= size) return respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable, headers(HttpHeaders.ContentRange to "bytes */$size"))
    val last = range.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(size - 1) ?: (size - 1)
    return respondRange(file, first, last, size, HttpHeaders.ETag to etag)
}

/** A test over a fresh [DownloadsHarness] (its profile created; its directory deleted after). */
internal fun downloadsTest(fake: Boolean = false, block: suspend TestScope.(DownloadsHarness) -> Unit) = runTest(timeout = 60.seconds) {
    val h = DownloadsHarness(this, fake)
    try {
        h.createProfile()
        block(h)
    } finally {
        h.cleanUp()
    }
}

/** Writes [request]'s whole file (of [size] bytes) into its part file and completes the fake transfer. */
internal fun DownloadsHarness.complete(request: TransferRequest, size: Long = request.expectedSize ?: 1000L) {
    writePart(request, size)
    fakeTransport.finish(request.id, TransferResult.Completed(size, "\"etag\""))
    settle()
}

internal fun writePart(request: TransferRequest, size: Long) {
    val part = Path(request.partPath)
    part.parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(part).buffered().use { it.write(ByteArray(size.toInt())) }
}

internal fun read(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }
