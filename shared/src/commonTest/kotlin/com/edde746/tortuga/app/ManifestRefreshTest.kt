package com.edde746.tortuga.app

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.data.sync.PushAck
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.presentation.addons.addonsStore
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.boardStore
import com.edde746.tortuga.presentation.discover.discoverStore
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Installed addons' manifests fetched again: a while after the app comes to the foreground (each addon at most once a
 * day, never offline) and when the Addons screen opens. A changed manifest replaces the stored one in place; the
 * profile's screens and requests follow it.
 */
class ManifestRefreshTest {
    /** A device whose addons answer [manifests] by host (a 500 for a host without one) and any catalog with one film. */
    private class World(scope: TestScope) {
        var now: Instant = Instant.parse("2026-11-02T08:00:00Z")
        val manifests = mutableMapOf<String, String>()

        /** The hosts whose manifest was asked for, in order. */
        val asked = mutableListOf<String>()
        val data = LocalData(
            inMemoryDatabase(), { now.toEpochMilliseconds() }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        private val http = FakeHttp(scope) { request ->
            val host = request.url.host
            when {
                request.url.encodedPath == "/manifest.json" -> {
                    asked += host
                    manifests[host]?.let { json(it) } ?: respond("", HttpStatusCode.InternalServerError)
                }
                request.url.encodedPath.startsWith("/catalog/") -> json("""{"metas":[{"id":"tt0000002","type":"movie","name":"Film"}]}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val graph = scope.testGraph(data, http.client, object : Clock {
            override fun now() = this@World.now
        })

        /** Runs [profileId] as this device's app does. */
        suspend fun session(profileId: String) =
            AppSession(graph, profileId, FakeAppPlatform(), start = null, profileAccount = null, startProfile = data.profiles.profile(profileId))

        /** The app leaves the foreground and comes back, and stays there long enough for its background work to start. */
        fun TestScope.foreground() {
            graph.setForeground(false)
            runCurrent()
            graph.setForeground(true)
            advanceTimeBy(10.seconds)
            runCurrent()
        }

        /** Marks every change pushed, as a sync does. */
        suspend fun pushAll() {
            val records = data.sync.dirtyRecords(null, 1_000)
            data.sync.markPushed(records.mapIndexed { i, record -> PushAck(record.recordId, record.hlc, i + 1L) })
        }
    }

    private fun url(host: String) = "https://$host/manifest.json"

    private fun manifest(host: String, version: String = "1.0.0", catalogs: List<String> = emptyList(), resources: List<String> = emptyList(), id: String = "org.example.$host") =
        """{"id":"$id","version":"$version","name":"$host","types":["movie"],""" +
            """"resources":[${(listOfNotNull("catalog".takeIf { catalogs.isNotEmpty() }) + resources).joinToString(",") { "\"$it\"" }}],""" +
            """"catalogs":[${catalogs.joinToString(",") { """{"type":"movie","id":"$it","name":"${it.replaceFirstChar(Char::uppercase)}"}""" }}],""" +
            """"idPrefixes":["tt"]}"""

    @Test
    fun aChangedManifestReplacesTheStoredOneAndTheAddonKeepsItsPlaceSwitchAndAgreement() = runTest {
        val w = World(this)
        w.manifests["catalogs.example"] = manifest("catalogs.example", catalogs = listOf("top"))
        w.manifests["off.example"] = manifest("off.example", catalogs = listOf("top"))
        w.manifests["tracker.example"] = manifest("tracker.example", resources = listOf("player"))
        val p = w.data.profiles.create("Ann", null).id
        val registry = w.graph.addonRegistry(p)
        registry.install(url("off.example").transportUrl())
        registry.install(url("tracker.example").transportUrl(), watchActivity = true)
        registry.install(url("catalogs.example").transportUrl())
        registry.move(url("catalogs.example"), 0)
        registry.setEnabled(url("off.example"), false)
        val updated = mapOf(
            "catalogs.example" to manifest("catalogs.example", "1.1.0", catalogs = listOf("top", "new")),
            "off.example" to manifest("off.example", "1.1.0", catalogs = listOf("top", "new")),
            "tracker.example" to manifest("tracker.example", "2.0.0", resources = listOf("player", "stream")),
        )
        w.manifests += updated
        w.session(p)
        w.asked.clear()

        with(w) { foreground() }

        assertEquals(listOf("catalogs.example", "tracker.example"), w.asked.sorted(), "the enabled addons are asked, the one turned off isn't")
        val stored = w.data.addons.addons(p)
        assertEquals(listOf(url("catalogs.example"), url("off.example"), url("tracker.example")), stored.map { it.transportUrl }, "in the user's order")
        assertEquals(listOf(true, false, true), stored.map { it.enabled })
        assertEquals(listOf(false, false, true), stored.map { it.watchActivity }, "the agreement holds while the manifest still asks for it")
        assertEquals(listOf(updated["catalogs.example"], manifest("off.example", catalogs = listOf("top")), updated["tracker.example"]), stored.map { it.manifestJson })
        assertEquals(
            listOf("1.1.0" to listOf("top", "new"), "2.0.0" to emptyList()),
            registry.addons.value.map { addon -> addon.manifest.version to addon.manifest.catalogs.map { it.id } },
            "what every request plans with follows at once",
        )
        assertEquals(listOf("player", "stream"), registry.addons.value[1].manifest.resources.map { it.name })
    }

    @Test
    fun aManifestWithTheSameContentWritesNothing() = runTest {
        val w = World(this)
        val body = manifest("same.example", catalogs = listOf("top"))
        w.manifests["same.example"] = body
        val p = w.data.profiles.create("Ann", null).id
        w.graph.addonRegistry(p).install(url("same.example").transportUrl())
        // The same manifest in another body: its keys in another order, with spaces.
        w.manifests["same.example"] =
            """{ "name": "same.example", "version": "1.0.0", "id": "org.example.same.example", "types": ["movie"], "idPrefixes": ["tt"], """ +
            """"catalogs": [{"name": "Top", "id": "top", "type": "movie"}], "resources": ["catalog"] }"""
        w.session(p)
        w.pushAll()
        w.asked.clear()

        with(w) { foreground() }

        assertEquals(listOf("same.example"), w.asked)
        assertEquals(listOf(body), w.data.addons.addons(p).map { it.manifestJson })
        assertEquals(emptyList(), w.data.sync.dirtyRecords(null, 1_000).map { it.recordId }, "nothing to sync")
    }

    @Test
    fun aFailedFetchAnInvalidManifestAndAnotherAddonsManifestKeepTheStoredOne() = runTest {
        val w = World(this)
        val hosts = listOf("down.example", "invalid.example", "moved.example")
        hosts.forEach { w.manifests[it] = manifest(it, catalogs = listOf("top")) }
        val p = w.data.profiles.create("Ann", null).id
        val registry = w.graph.addonRegistry(p)
        hosts.forEach { registry.install(url(it).transportUrl()) }
        val before = w.data.addons.addons(p)
        w.manifests.remove("down.example")
        w.manifests["invalid.example"] = """{"id":"org.example.invalid.example","version":"2.0.0","name":"""
        w.manifests["moved.example"] = manifest("moved.example", "2.0.0", catalogs = listOf("top", "new"), id = "org.example.another")
        w.session(p)
        w.pushAll()
        w.asked.clear()

        with(w) { foreground() }

        assertEquals(hosts, w.asked.sorted())
        assertEquals(before, w.data.addons.addons(p))
        assertEquals(listOf("1.0.0", "1.0.0", "1.0.0"), registry.addons.value.map { it.manifest.version })
        assertEquals(emptyList(), w.data.sync.dirtyRecords(null, 1_000).map { it.recordId })
    }

    @Test
    fun theForegroundAsksEachAddonAtMostOnceADayAndNeverOfflineWhileTheAddonsScreenAsksWhenItOpens() = runTest {
        val w = World(this)
        w.manifests["daily.example"] = manifest("daily.example")
        val p = w.data.profiles.create("Ann", null).id
        val registry = w.graph.addonRegistry(p)
        registry.install(url("daily.example").transportUrl())
        w.session(p)
        w.asked.clear()
        fun version() = registry.addons.value.single().manifest.version

        with(w) { foreground() }
        assertEquals(listOf("daily.example"), w.asked, "asked at the first foreground")

        w.manifests["daily.example"] = manifest("daily.example", "2.0.0")
        w.now += 23.hours
        with(w) { foreground() }
        assertEquals(listOf("daily.example"), w.asked, "not again within a day")
        assertEquals("1.0.0", version())

        w.now += 1.hours
        w.graph.setNetwork(NetworkState.OFFLINE)
        with(w) { foreground() }
        assertEquals(listOf("daily.example"), w.asked, "nor offline")

        w.graph.setNetwork(NetworkState(online = true, metered = true))
        with(w) { foreground() }
        assertEquals(listOf("daily.example", "daily.example"), w.asked, "a day later, on a metered network too")
        assertEquals("2.0.0", version())

        w.manifests["daily.example"] = manifest("daily.example", "3.0.0")
        val store = w.graph.addonsStore(p, NoHosts)
        runCurrent()
        assertEquals(listOf("daily.example", "daily.example", "daily.example"), w.asked, "the Addons screen asks whenever it opens")
        assertEquals("3.0.0", version())
        store.release()
    }

    @Test
    fun homeAndDiscoverFollowTheCatalogsAndTheLayoutKeepsTheRowsThatStayAndForgetsTheOthers() = runTest {
        val w = World(this)
        w.manifests["rows.example"] = manifest("rows.example", catalogs = listOf("one", "two", "three"))
        val p = w.data.profiles.create("Ann", null).id
        w.graph.addonRegistry(p).install(url("rows.example").transportUrl())
        fun key(catalog: String) = "addon|${url("rows.example")}|movie|$catalog"
        w.data.settings.set(p, ProfileSetting.HomeRows, HomeLayout(order = listOf(key("three"), key("two"), key("one")), hidden = listOf(key("one"))))
        w.manifests["rows.example"] = manifest("rows.example", "2.0.0", catalogs = listOf("one", "two", "four"))
        w.session(p)
        val board = w.graph.boardStore(p, NoHosts, remote = false, watchingTogether = flowOf(0), kidsProfile = flowOf(false))
        val discover = w.graph.discoverStore(p, null, NoHosts)
        runCurrent()
        fun rows() = (board.state.value.body as BoardBody.Rows).rows.map { row -> listOf("One", "Two", "Three", "Four").single { it in row.title } }
        fun catalogs() = discover.state.value.catalogs?.options?.map { it.label }
        assertEquals(listOf("Three", "Two"), rows())
        assertEquals(listOf("One", "Two", "Three"), catalogs())

        with(w) { foreground() }

        assertEquals(listOf("Two", "Four"), rows(), "Two keeps its place, One stays hidden, Four comes in its default place")
        assertEquals(listOf("One", "Two", "Four"), catalogs())
        assertEquals(HomeLayout(order = listOf(key("two"), key("one")), hidden = listOf(key("one"))), w.data.settings.settings(p).homeRows)
        board.release()
        discover.release()
    }
}
