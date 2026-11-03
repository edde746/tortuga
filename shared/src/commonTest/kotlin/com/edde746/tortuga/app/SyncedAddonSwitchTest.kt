package com.edde746.tortuga.app

import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.presentation.board.items
import com.edde746.tortuga.presentation.discover.discoverStore
import com.edde746.tortuga.presentation.rows
import com.edde746.tortuga.presentation.settings.homeSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The switch and the agreement the user set on another of their devices, which the synced addon list brings. */
class SyncedAddonSwitchTest {
    /** A catalog addon at `https://<name>.example/manifest.json` with [catalogs] (type to id), each named as its id. */
    private fun catalogAddon(name: String, vararg catalogs: Pair<String, String>) = SyncedAddon(
        "https://$name.example/manifest.json",
        """{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["movie","series"],"resources":["catalog"],"catalogs":[""" +
            catalogs.joinToString(",") { (type, id) -> """{"type":"$type","id":"$id","name":"$id"}""" } + "]}",
    )

    private fun SyncedAddon.turned(on: Boolean) = SyncedAddon(transportUrl, manifest, enabled = on, watchActivity = watchActivity)

    @Test
    fun anAddonTurnedOffOnAnotherDeviceIsAskedForNothingHereUntilItIsTurnedBackOn() = runTest {
        val w = everythingWorld()
        val p = w.data.profiles.create("Ann", null).id
        w.pullAddons(p, SyncedAddon(EVERYTHING_URL, EVERYTHING_MANIFEST, enabled = false, watchActivity = true))
        runCurrent()
        assertEquals(ASKED_OF_EVERYTHING.keys.associateWith { emptySet<String>() }, askedOfEverything(w, p), "turned off, nothing asks it anything")

        w.clock.now += 1_000
        w.pullAddons(p, SyncedAddon(EVERYTHING_URL, EVERYTHING_MANIFEST, enabled = true, watchActivity = true))
        runCurrent()
        assertEquals(ASKED_OF_EVERYTHING, askedOfEverything(w, p))
    }

    @Test
    fun aDiscoverLinkToATurnedOffAddonsCatalogOpensDiscoverAsNoLinkDoesUntilTheAddonIsBackOn() = runTest {
        val w = everythingWorld()
        val p = w.data.profiles.create("Ann", null).id
        val other = catalogAddon("other", "movie" to "popular")
        w.answers["other.example /catalog/movie/popular.json"] = """{"metas":[{"id":"tt0000009","type":"movie","name":"Other film"}]}"""
        val everything = SyncedAddon(EVERYTHING_URL, EVERYTHING_MANIFEST)
        w.pullAddons(p, other, everything.turned(on = false))
        val link = DeepLink.Discover(EVERYTHING_URL, "movie", "top")

        val plain = w.graph.discoverStore(p, null, NoHosts)
        runCurrent()
        val unlinked = plain.state.value
        plain.release()
        assertEquals(listOf("Other film"), unlinked.items.map { it.title })

        val off = w.graph.discoverStore(p, link, NoHosts)
        runCurrent()
        assertEquals(unlinked, off.state.value, "the link to the turned-off addon's catalog opens Discover as no link does")
        off.release()
        assertEquals(emptyList(), w.requests.filter { it.startsWith("everything.example ") }, "and asks it nothing")

        w.clock.now += 1_000
        w.pullAddons(p, other, everything.turned(on = true))
        runCurrent()
        val on = w.graph.discoverStore(p, link, NoHosts)
        runCurrent()
        assertEquals("Top", on.state.value.catalogs?.selection)
        assertEquals(listOf("Film"), on.state.value.items.map { it.title })
        assertEquals(listOf("everything.example /catalog/movie/top.json"), w.requests.filter { it.startsWith("everything.example ") })
        on.release()
    }

    @Test
    fun aTurnedOffAddonsHomeRowsLeaveTheEditorButKeepTheirPlaceAndVisibilityUntilItIsBackOn() = runTest {
        val w = AddonWorld(this)
        val p = w.data.profiles.create("Ann", null).id
        val lists = catalogAddon("lists", "movie" to "new", "movie" to "old")
        val off = catalogAddon("off", "movie" to "top", "series" to "top")
        fun key(addon: SyncedAddon, type: String, id: String) = "addon|${addon.transportUrl}|$type|$id"
        val (offMovies, offSeries) = key(off, "movie", "top") to key(off, "series", "top")
        val (listsNew, listsOld) = key(lists, "movie", "new") to key(lists, "movie", "old")
        w.data.settings.set(p, ProfileSetting.HomeRows, HomeLayout(order = listOf(offMovies, listsNew, offSeries, listsOld), hidden = listOf(offSeries)))
        w.pullAddons(p, lists, off.turned(on = false))
        val store = w.graph.homeSettingsStore(p, offersFiles = false)
        runCurrent()
        fun rows() = store.state.value.rows.map { it.id to it.shown }
        assertEquals(listOf("org.example.lists/movie/new" to true, "org.example.lists/movie/old" to true), rows(), "the turned-off addon's rows aren't here")

        // Edits of the other rows: a move and a hide.
        store.moveDown("org.example.lists/movie/new")
        runCurrent()
        store.setShown("org.example.lists/movie/new", false)
        runCurrent()
        assertEquals(
            HomeLayout(order = listOf(offMovies, listsOld, listsNew, offSeries), hidden = listOf(offSeries, listsNew)),
            w.data.settings.settings(p).homeRows,
            "the turned-off addon's rows keep their place and visibility",
        )

        w.clock.now += 1_000
        w.pullAddons(p, lists, off.turned(on = true))
        runCurrent()
        assertEquals(
            listOf(
                "org.example.off/movie/top" to true, "org.example.lists/movie/old" to true,
                "org.example.lists/movie/new" to false, "org.example.off/series/top" to false,
            ),
            rows(),
            "back on, its rows are where they were, the hidden one still hidden",
        )
        store.release()
    }
}
