package com.edde746.tortuga.addons

import com.edde746.tortuga.app.ProfileAddonStore
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.model.DescriptorFlags
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.model.transportUrl
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AddonRegistryTest {
    private class MemoryStore(var stored: List<StoredAddon> = emptyList()) : AddonStore {
        override suspend fun load() = stored
        override suspend fun save(addons: List<StoredAddon>) {
            stored = addons
        }
    }

    /** Manifests by URL path; version and hints can change between requests. */
    private class Addons {
        var version = "1.0.0"
        var hints = """{"configurable":true}"""
        var resources = """["stream"]"""
        fun manifest(name: String) =
            """{"id":"org.example.$name","version":"$version","name":"$name","types":["movie"],"resources":$resources,"behaviorHints":$hints}"""
    }

    private fun TestScope.registry(store: AddonStore, addons: Addons = Addons()) = AddonRegistry(
        store,
        AddonTransport(
            mockClient { request -> json(addons.manifest(request.url.encodedPath.split('/')[1])) },
            parseDispatcher = StandardTestDispatcher(testScheduler),
        ),
    )

    private val cinemeta = DefaultAddons.CINEMETA_URL
    private val openSubtitles = DefaultAddons.OPENSUBTITLES_URL

    @Test
    fun theSwitchAndTheAgreementSurviveAReload() = runTest {
        val data = testData()
        val addons = Addons().apply { resources = """["stream","player"]""" }
        val tracker = "https://a.example/tracker/manifest.json"
        registry(ProfileAddonStore(data.addons, "p"), addons).run {
            seedDefaults()
            install(tracker.transportUrl(), watchActivity = true)
            setEnabled(tracker, false)
        }

        val reloaded = registry(ProfileAddonStore(data.addons, "p"), addons)
        assertEquals(listOf(cinemeta, openSubtitles), reloaded.load().map { it.transportUrl }, "off, it is asked for nothing")
        assertEquals(
            listOf(cinemeta to (true to false), openSubtitles to (true to false), tracker to (false to true)),
            reloaded.installed.value.map { it.descriptor.transportUrl to (it.enabled to it.watchActivity) },
        )
        assertEquals(emptyList(), reloaded.watchActivityAddons(), "nor sent any watch activity")
        reloaded.setEnabled(tracker, true)
        assertEquals(listOf(tracker), registry(ProfileAddonStore(data.addons, "p"), addons).watchActivityAddons().map { it.transportUrl })
        data.close()
    }

    @Test
    fun theBuiltInAddonIsOnEvenWhenStoredOffAndCantBeTurnedOff() = runTest {
        val store = MemoryStore(listOf(StoredAddon(cinemeta, DefaultAddons.CINEMETA_MANIFEST_JSON, enabled = false)))
        val registry = registry(store)
        assertEquals(listOf(cinemeta), registry.load().map { it.transportUrl })
        assertEquals(listOf(true), registry.installed.value.map { it.enabled })
        assertEquals(AddonError.Protected, assertFailsWith<AddonException> { registry.setEnabled(cinemeta, false) }.error)
    }

    @Test
    fun defaultsAreSeededOnlyIntoAnEmptyProfileAndOnlyOnRequest() = runTest {
        val store = MemoryStore()
        val registry = registry(store)
        // An empty list may just not be synced yet: loading never seeds.
        assertEquals(emptyList(), registry.load())
        assertEquals(emptyList(), store.stored)
        val addons = registry.seedDefaults()
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), addons.map { it.manifest.name })
        assertEquals(listOf(cinemeta, openSubtitles), store.stored.map { it.transportUrl })
        assertEquals(DescriptorFlags(official = true, protected = true), addons[0].flags)

        val existing = MemoryStore(listOf(StoredAddon(openSubtitles, DefaultAddons.OPENSUBTITLES_MANIFEST_JSON)))
        assertEquals(listOf("OpenSubtitles v3"), registry(existing).seedDefaults().map { it.manifest.name })
    }

    @Test
    fun aListReplacedBySyncIsNotOverwritten() = runTest {
        val store = MemoryStore()
        val registry = registry(store)
        registry.seedDefaults()
        // Sync replaces the profile's list behind the registry's back.
        store.stored = listOf(StoredAddon(openSubtitles, DefaultAddons.OPENSUBTITLES_MANIFEST_JSON))
        registry.install("https://a.example.com/a/manifest.json".transportUrl())
        assertEquals(listOf(openSubtitles, "https://a.example.com/a/manifest.json"), store.stored.map { it.transportUrl })
        assertEquals(listOf("OpenSubtitles v3", "a"), registry.addons.value.map { it.manifest.name })
    }

    @Test
    fun installFromStremioLinkAppendsAndPersistsTheManifestBody() = runTest {
        val store = MemoryStore()
        val backend = Addons()
        val registry = registry(store, backend)
        registry.seedDefaults()
        val installed = registry.install("stremio://Streams.Example.com/streams/cfg=a|b/manifest.json".transportUrl())
        assertEquals("https://streams.example.com/streams/cfg=a|b/manifest.json", installed.transportUrl)
        assertEquals(DescriptorFlags(), installed.flags)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "streams"), registry.addons.value.map { it.manifest.name })
        assertEquals(backend.manifest("streams"), store.stored.last().manifestJson)
        assertEquals("https://streams.example.com/streams/cfg=a|b/configure", AddonRegistry.configureUrl(installed))
    }

    @Test
    fun reinstallingUpdatesInPlaceAndIdenticalIsRejected() = runTest {
        val backend = Addons()
        val registry = registry(MemoryStore(), backend)
        registry.install("https://a.example.com/a/manifest.json".transportUrl())
        registry.install("https://b.example.com/b/manifest.json".transportUrl())
        assertEquals(
            AddonError.AlreadyInstalled,
            assertFailsWith<AddonException> { registry.install("https://a.example.com/a/manifest.json".transportUrl()) }.error,
        )
        backend.version = "1.1.0"
        registry.install("https://a.example.com/a/manifest.json".transportUrl())
        assertEquals(listOf("a" to "1.1.0", "b" to "1.0.0"), registry.addons.value.map { it.manifest.name to it.manifest.version })
    }

    @Test
    fun configurationRequiredPointsToTheConfigurePage() = runTest {
        val backend = Addons().apply { hints = """{"configurable":true,"configurationRequired":true}""" }
        val error = assertFailsWith<AddonException> { registry(MemoryStore(), backend).install("https://c.example.com/c/manifest.json?x=1".transportUrl()) }.error
        assertEquals(AddonError.ConfigurationRequired("https://c.example.com/c/configure?x=1"), error)
    }

    @Test
    fun onlyAddonLinksInstall() = runTest {
        for (link in listOf("https://example.com/addon.json", "https://example.com/stremio/v1", "not a link", "ftp://example.com/manifest.json")) {
            assertNull(TransportUrl.parse(link), link)
        }
    }

    @Test
    fun removeAndReorder() = runTest {
        val store = MemoryStore()
        val registry = registry(store)
        registry.seedDefaults()
        registry.install("https://a.example.com/a/manifest.json".transportUrl())
        assertEquals(AddonError.Protected, assertFailsWith<AddonException> { registry.remove(cinemeta) }.error)
        assertEquals(AddonError.NotInstalled, assertFailsWith<AddonException> { registry.remove("https://x.example.com/manifest.json") }.error)
        registry.move("https://a.example.com/a/manifest.json", 0)
        assertEquals(listOf("a", "Cinemeta", "OpenSubtitles v3"), registry.addons.value.map { it.manifest.name })
        registry.remove(openSubtitles)
        registry.move(cinemeta, 99)
        assertEquals(listOf("a", "Cinemeta"), registry.addons.value.map { it.manifest.name })
        // A fresh registry over the same store sees the same list.
        assertEquals(listOf("a", "Cinemeta"), registry(store).load().map { it.manifest.name })
    }

    @Test
    fun undecodableStoredAddonsAreKeptButNotListed() = runTest {
        val broken = StoredAddon("https://old.example.com/manifest.json", """{"id":"old"}""")
        val store = MemoryStore(listOf(StoredAddon(cinemeta, DefaultAddons.CINEMETA_MANIFEST_JSON), broken))
        val registry = registry(store)
        assertEquals(listOf("Cinemeta"), registry.load().map { it.manifest.name })
        registry.install("https://a.example.com/a/manifest.json".transportUrl())
        assertEquals(listOf(cinemeta, broken.transportUrl, "https://a.example.com/a/manifest.json"), store.stored.map { it.transportUrl })
        assertEquals(broken, store.stored[1])
        assertNull(AddonRegistry.configureUrl(registry.addons.value[0]))
    }
}
