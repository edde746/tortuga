package com.edde746.tortuga.app

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.AddonEntry
import com.edde746.tortuga.data.sync.AddonListBody
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.data.sync.PushAck
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.RemoteRecord
import com.edde746.tortuga.data.sync.SyncRecord
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.serveFile
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.presentation.addons.AddonsHost
import com.edde746.tortuga.presentation.board.BoardHost
import com.edde746.tortuga.presentation.detail.DetailHost
import com.edde746.tortuga.presentation.discover.DiscoverHost
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.presentation.search.SearchHost
import com.edde746.tortuga.presentation.streams.StreamsHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.time.Instant

/** A series with 102 episodes out in season 1 and a 103rd still to come. */
internal const val SHOW_ID = "tt0000001"
internal val SHOW = MetaItem(
    MetaItemPreview(id = SHOW_ID, type = "series", name = "Show"),
    videos = (1..103).map { episode ->
        Video(
            id = "$SHOW_ID:1:$episode",
            title = "Episode $episode",
            released = if (episode == 103) Instant.parse("2030-01-01T00:00:00Z") else Instant.parse("2020-01-01T00:00:00Z"),
            seriesInfo = SeriesInfo(1, episode),
        )
    },
)

internal const val FILM_ID = "tt0000002"
internal val FILM = MetaItem(MetaItemPreview(id = FILM_ID, type = "movie", name = "Film"))

/** An addon at `https://<host>/manifest.json` that receives watch activity: `player` and `library` for movies and series with `tt` ids. */
internal fun receiverManifest(host: String) =
    """{"id":"org.example.$host","version":"1.0.0","name":"$host","types":["movie","series"],"resources":["player","library"],"idPrefixes":["tt"]}"""

internal const val TRACKER_URL = "https://tracker.example/manifest.json"
internal const val META_URL = "https://meta.example/manifest.json"

/** The addon [SHOW] and [FILM] come from: their metas only. */
internal const val META_MANIFEST =
    """{"id":"org.example.meta","version":"1.0.0","name":"Meta","types":["movie","series"],"resources":["meta"],"idPrefixes":["tt"]}"""

/** One addon of the profile's list as another of the user's devices stored it: its switch, and their agreement that it receives their watch activity. */
internal class SyncedAddon(val transportUrl: String, val manifest: String, val enabled: Boolean = true, val watchActivity: Boolean = false)

/**
 * An [AppGraph] (its real stores) whose HTTP world is addons that record what they are asked: each host answers its
 * manifest from [manifests], `meta.example` the metas of [SHOW] and [FILM], `cdn.example` serves the files played, and
 * any other request gets its body from [answers] (by `host path`), else `{}`. [requests] is every request but the
 * manifests' and the files', as `host path`, in order.
 */
internal class AddonWorld(scope: TestScope) {
    val clock = TestClock()
    val data = newDevice(scope)
    val requests = mutableListOf<String>()
    val manifests = mutableMapOf("tracker.example" to receiverManifest("tracker.example"), "meta.example" to META_MANIFEST)
    val answers = mutableMapOf(
        "meta.example /meta/series/$SHOW_ID.json" to """{"meta":${AddonJson.encodeToString(MetaItem.serializer(), SHOW)}}""",
        "meta.example /meta/movie/$FILM_ID.json" to """{"meta":${AddonJson.encodeToString(MetaItem.serializer(), FILM)}}""",
    )
    private val file = lcgBytes(300_000)
    val http = FakeHttp(scope) { request ->
        val host = request.url.host
        val path = request.url.encodedPath
        when {
            host == "cdn.example" -> serveFile(request, file)
            path.endsWith("/manifest.json") -> manifests[host]?.let { json(it) } ?: respond("", HttpStatusCode.NotFound)
            else -> {
                requests += "$host $path"
                json(answers["$host $path"] ?: "{}")
            }
        }
    }
    val graph = scope.testGraph(data, http.client)
    private var serverVersion = 0L

    /** Another database on the test's scheduler and [clock]: this device's, or another of the user's. */
    fun newDevice(scope: TestScope) = LocalData(
        inMemoryDatabase(), { clock.now }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )

    /** The `player` and `library` requests [host] got, their paths in order. */
    fun events(host: String = "tracker.example") =
        requests.filter { it.startsWith("$host /player/") || it.startsWith("$host /library/") }.map { it.substringAfter(' ') }

    /** [addons] as the profile's addon list, the way a sync pull brings the list another of the user's devices stored. */
    suspend fun pullAddons(profileId: String, vararg addons: SyncedAddon) = data.pullAddons(profileId, clock.now, ++serverVersion, *addons)

    /** [from]'s changes not synced yet, pushed and then pulled by [to], as sync brings them. */
    suspend fun sync(from: LocalData, to: LocalData) {
        val records = from.sync.dirtyRecords(null, 1_000)
        val pulled = records.map { RemoteRecord(it.recordId, ++serverVersion, Json.parseToJsonElement(it.payload).jsonObject) }
        from.sync.markPushed(records.zip(pulled) { record, remote -> PushAck(record.recordId, record.hlc, remote.serverVersion) })
        assertEquals(emptyList(), to.sync.applyRemote(pulled))
    }
}

/**
 * [addons] as [profileId]'s addon list, the way a sync pull brings the list another of the user's devices stored at
 * [wallMillis], the server's version [serverVersion]: each entry with its switch and the user's agreement.
 */
internal suspend fun LocalData.pullAddons(profileId: String, wallMillis: Long, serverVersion: Long, vararg addons: SyncedAddon) {
    val record = SyncRecord(
        RecordKey.AddonList(profileId), Hlc(wallMillis, 0, "f".repeat(Hlc.NODE_LENGTH)),
        AddonListBody(addons.map { AddonEntry(it.transportUrl, it.manifest, it.enabled, it.watchActivity) }),
    )
    val pulled = Json.parseToJsonElement(record.encode()).jsonObject
    assertEquals(emptyList(), sync.applyRemote(listOf(RemoteRecord(record.key.recordId(), serverVersion, pulled))))
}

/** The screens' hosts, for stores that are driven through their intents alone. */
internal object NoHosts : BoardHost, DiscoverHost, SearchHost, DetailHost, StreamsHost, AddonsHost {
    override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit
    override fun openVideo(type: String, id: String, videoId: String) = Unit
    override fun openDiscover(link: DeepLink.Discover) = Unit
    override fun openDownloads() = Unit
    override fun openWatchTogether() = Unit
    override fun openLibrary() = Unit
    override fun openGuide() = Unit
    override fun openCalendar() = Unit
    override fun openCollectionFolder(collectionId: String, folderId: String) = Unit
    override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit
    override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) = Unit
    override fun openDownloadStreams(meta: MetaItem, videoId: String) = Unit
    override fun openKeepSynced() = Unit
    override fun play(request: PlayRequest) = Unit
    override fun playWith(request: PlayRequest, playerId: String) = Unit
    override fun openUrl(url: String) = Unit
    override fun openDeepLink(link: DeepLink) = Unit
    override fun closeStreams() = Unit
    override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) = Unit
    override fun copyToClipboard(text: String) = Unit
    override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
}
