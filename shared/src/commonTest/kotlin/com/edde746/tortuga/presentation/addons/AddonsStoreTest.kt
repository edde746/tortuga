@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.addons

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonRegistry
import com.edde746.tortuga.addons.AddonStore
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.addons.StoredAddon
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.addons.mockClient
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonsStoreTest {
    /** A configured addon's link carries an account key, as many addons' links do. */
    private val secret = "key=SECRETKEY123"
    private val streamsUrl = "https://addon.example/$secret/manifest.json"

    private class MemoryStore : AddonStore {
        var stored: List<StoredAddon> = emptyList()
        override suspend fun load() = stored
        override suspend fun save(addons: List<StoredAddon>) {
            stored = addons
        }
    }

    private class Host : AddonsHost {
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        override fun openUrl(url: String) {
            opened += url
        }
        override fun copyToClipboard(text: String) {
            copied += text
        }
    }

    private class Fixture(scope: TestScope, browse: Boolean) {
        var streamsVersion = "1.0.0"

        /** Scrobbler's resources: from version 2 on it asks for the watch activity too. */
        var scrobblerVersion = "2.0.0"
        val requests = mutableListOf<String>()
        val store = MemoryStore()
        val http = scope.mockClient { request ->
            requests += request.url.toString()
            when (request.url.host) {
                "addon.example" -> json(
                    """{"id":"example.streams","version":"$streamsVersion","name":"Example Streams","types":["movie","series"],""" +
                        """"resources":["stream"],"behaviorHints":{"configurable":true}}""",
                )
                "channels.example" -> json(
                    """{"id":"org.example.channels","version":"2.0.0","name":"Channels","types":["channel"],"resources":["catalog","meta"],""" +
                        """"catalogs":[{"id":"top","type":"channel","name":"Top"}]}""",
                )
                "scrobbler.example" -> json(
                    """{"id":"org.example.scrobbler","version":"$scrobblerVersion","name":"Scrobbler","types":["movie","series"],""" +
                        """"resources":${if (scrobblerVersion.startsWith("1.")) """["catalog"]""" else """["catalog","player","library"]"""},""" +
                        """"catalogs":[{"id":"top","type":"movie","name":"Top"}]}""",
                )
                "needsconfig.example" -> json(
                    """{"id":"org.example.needsconfig","version":"1.0.0","name":"Needs config","types":["movie"],"resources":["stream"],""" +
                        """"behaviorHints":{"configurable":true,"configurationRequired":true}}""",
                )
                // Cinemeta's addon catalogs: Official lists two addons, Community fails.
                "v3-cinemeta.strem.io" -> if ("/official.json" in request.url.encodedPath) {
                    json(
                        """{"addons":[""" +
                            """{"transportName":"http","transportUrl":"https://addon.example/manifest.json","manifest":""" +
                            """{"id":"example.streams","version":"1.0.0","name":"Example Streams","types":["movie","series"],"resources":["stream"]}},""" +
                            """{"transportName":"http","transportUrl":"https://channels.example/manifest.json","manifest":""" +
                            """{"id":"org.example.channels","version":"2.0.0","name":"Channels","description":"Channels to watch","types":["channel"],"resources":["catalog"]}}""" +
                            """]}""",
                    )
                } else {
                    respond("", HttpStatusCode.InternalServerError)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val transport = AddonTransport(http, parseDispatcher = StandardTestDispatcher(scope.testScheduler))
        val registry = AddonRegistry(store, transport)
        val host = Host()
        val addons = AddonsStore(
            registry,
            transport,
            { store.stored.map { it.transportUrl } },
            host,
            scope.backgroundScope,
            refreshManifests = {},
            catalogs = if (browse) AddonAggregator(transport) else null,
        )

        val state get() = addons.state.value

        fun id(name: String) = state.rows.first { it.name == name }.id

        fun names() = state.rows.map { it.name }
    }

    private suspend fun TestScope.fixture(vararg installed: String, browse: Boolean = false): Fixture {
        val fixture = Fixture(this, browse)
        fixture.registry.seedDefaults()
        installed.forEach { fixture.registry.install(it.transportUrl()) }
        runCurrent()
        return fixture
    }

    @Test
    fun anInstallLinkIsPreviewedThenInstalledWithoutTheLinkInTheState() = runTest {
        val f = fixture()
        f.addons.startInstall()
        f.addons.updateInstallLink("stremio://addon.example/$secret/manifest.json")
        assertTrue(f.state.install.enterLink.canSubmit)
        f.addons.submitInstallLink()
        assertIs<AddonInstallStep.Loading>(f.state.install.step)
        runCurrent()

        val preview = assertNotNull(f.state.install.preview)
        assertIs<AddonInstallStep.Preview>(f.state.install.step)
        assertEquals("Example Streams", preview.name)
        assertEquals(T.addons.version(isolate("1.0.0")), preview.version)
        assertEquals(listOf(T.browse.type.movie, T.browse.type.series).joinToString(T.addons.listSeparator), preview.types)
        assertEquals(T.addons.resource.stream, preview.resources)
        assertEquals(AddonInstallAction.Install(T.addons.install.confirm), preview.action)
        // Previewing installs nothing.
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), f.names())
        assertFalse(secret in f.state.toString())

        f.addons.confirmInstall()
        assertIs<AddonInstallStep.Installing>(f.state.install.step)
        runCurrent()
        assertIs<AddonInstallStep.Installed>(f.state.install.step)
        assertEquals(T.addons.install.installed(isolate("Example Streams")), f.state.install.result)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams"), f.names())
        // The stremio:// link is stored as the addon's https transport URL.
        assertEquals(streamsUrl, f.store.stored.last().transportUrl)
        assertFalse(secret in f.state.toString())
    }

    @Test
    fun thePreviewComparesTheManifestWithTheInstalledCopy() = runTest {
        val f = fixture(streamsUrl)
        val requestsBefore = f.requests.size
        f.addons.previewInstall(streamsUrl.transportUrl())
        runCurrent()
        val same = assertNotNull(f.state.install.preview)
        assertEquals(AddonInstallAction.None, same.action)
        assertEquals(T.addons.install.alreadyInstalled, same.status)
        f.addons.confirmInstall()
        runCurrent()
        assertEquals(requestsBefore + 1, f.requests.size, "nothing to install: only the preview's request")

        f.streamsVersion = "1.1.0"
        f.addons.previewInstall(streamsUrl.transportUrl())
        runCurrent()
        val newer = assertNotNull(f.state.install.preview)
        assertEquals(AddonInstallAction.Update(T.addons.install.update), newer.action)
        assertEquals(T.addons.install.updateVersion(isolate("1.1.0")), newer.status)
        f.addons.confirmInstall()
        runCurrent()
        assertEquals(T.addons.install.updated(isolate("Example Streams")), f.state.install.result)
        // Updated in place, not added again.
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams"), f.names())
        assertTrue(f.state.rows.last().facts.startsWith(T.addons.version(isolate("1.1.0"))), f.state.rows.last().facts)
    }

    /** A row's version, types and resources are one line whose separators stay with the fact before them. */
    @Test
    fun aRowsFactsAreOneLineThatBreaksAfterASeparatorNeverBefore() = runTest {
        val f = fixture(streamsUrl)
        assertEquals("Version \u20681.0.0\u2069\u00a0· Movies, Series\u00a0· Streams", f.state.rows.last().facts)
    }

    @Test
    fun anAddonThatMustBeConfiguredFirstOffersItsConfigurationPage() = runTest {
        val f = fixture()
        f.addons.previewInstall("stremio://needsconfig.example/manifest.json".transportUrl())
        runCurrent()
        val preview = assertNotNull(f.state.install.preview)
        assertEquals(AddonInstallAction.Configure(T.addons.install.configure), preview.action)
        assertEquals(T.addons.install.configureFirst, preview.status)
        f.addons.confirmInstall()
        assertEquals(listOf("https://needsconfig.example/configure"), f.host.opened)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), f.names())
    }

    @Test
    fun aConfigurableAddonOffersConfiguringBeforeInstalling() = runTest {
        val f = fixture()
        f.addons.previewInstall("stremio://addon.example/manifest.json".transportUrl())
        runCurrent()
        val preview = assertNotNull(f.state.install.preview)
        assertEquals(AddonInstallAction.Install(T.addons.install.confirm), preview.action)
        assertEquals(T.addons.install.configure, preview.configureLabel)
        f.addons.configurePreview()
        assertEquals(listOf("https://addon.example/configure"), f.host.opened)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), f.names(), "configuring installs nothing")

        // An addon that has to be configured first has Configure as its main button, once.
        f.addons.previewInstall("stremio://needsconfig.example/manifest.json".transportUrl())
        runCurrent()
        assertEquals(AddonInstallAction.Configure(T.addons.install.configure), f.state.install.preview?.action)
        assertNull(f.state.install.preview?.configureLabel)
        // An addon without settings offers none.
        f.addons.previewInstall("https://channels.example/manifest.json".transportUrl())
        runCurrent()
        assertEquals("Channels", f.state.install.preview?.name)
        assertNull(f.state.install.preview?.configureLabel)
    }

    @Test
    fun browseListsTheInstalledAddonsCatalogsAndPreviewsAPick() = runTest {
        val f = fixture("https://channels.example/manifest.json", browse = true)
        val browse = assertNotNull(f.state.browse)
        assertEquals(AddonsSection.INSTALLED, f.state.section)
        assertEquals(listOf("Official", "Community"), browse.catalogs.map { it.label })
        assertTrue(f.requests.none { "addon_catalog" in it }, "nothing is fetched before Browse shows")

        f.addons.showSection(AddonsSection.BROWSE)
        assertEquals(AddonBrowseBody.Loading, f.state.browse?.body)
        runCurrent()
        val official = assertNotNull(f.state.browse)
        assertEquals(AddonsSection.BROWSE, f.state.section)
        assertEquals("https://v3-cinemeta.strem.io/addon_catalog/all/official.json", f.requests.last())
        assertEquals(
            listOf(T.addons.allTypes, T.browse.type.movie, T.browse.type.series, T.browse.type.channel),
            official.types.map { it.label },
        )
        assertEquals(listOf("Example Streams", "Channels"), official.results.map { it.name })
        assertEquals(listOf(null, "Channels to watch"), official.results.map { it.description })
        // Channels is installed.
        assertEquals(listOf(null, T.addons.browse.installedNote), official.results.map { it.note })

        f.addons.selectBrowseType("movie")
        runCurrent()
        assertEquals("https://v3-cinemeta.strem.io/addon_catalog/movie/official.json", f.requests.last())
        // Another catalog keeps the type where it has it.
        f.addons.selectBrowseCatalog(official.catalogs[1].id)
        runCurrent()
        assertEquals("https://v3-cinemeta.strem.io/addon_catalog/movie/community.json", f.requests.last())
        assertEquals(AddonBrowseBody.Failed(T.addons.error.http("500")), f.state.browse?.body)

        f.addons.selectBrowseCatalog(official.catalogs[0].id)
        runCurrent()
        f.addons.previewBrowseItem(assertNotNull(f.state.browse).results.first().id)
        runCurrent()
        assertEquals("Example Streams", f.state.install.preview?.name)
        assertEquals(AddonInstallAction.Install(T.addons.install.confirm), f.state.install.preview?.action)
    }

    @Test
    fun noBrowseWhereTheBuildHasNone() = runTest {
        val f = fixture()
        assertNull(f.state.browse)
        f.addons.showSection(AddonsSection.BROWSE)
        assertEquals(AddonsSection.INSTALLED, f.state.section)
    }

    @Test
    fun aBadLinkIsRefusedAndAFailedFetchKeepsTheLinkForCorrection() = runTest {
        val f = fixture()
        f.addons.startInstall()
        f.addons.updateInstallLink("https://example.com/addon")
        f.addons.submitInstallLink()
        assertIs<AddonInstallStep.EnterLink>(f.state.install.step)
        assertEquals(T.addons.error.invalidTransportUrl, f.state.install.error)
        assertEquals(emptyList(), f.requests, "refused before any request")

        f.addons.updateInstallLink("https://gone.example/manifest.json")
        assertNull(f.state.install.error, "typing clears the error")
        f.addons.submitInstallLink()
        runCurrent()
        assertIs<AddonInstallStep.EnterLink>(f.state.install.step)
        assertEquals(T.addons.error.http("404"), f.state.install.error)
        assertEquals("https://gone.example/manifest.json", f.state.install.enterLink.link)
    }

    @Test
    fun reorderingFollowsTheShownListAndIdsSurviveIt() = runTest {
        val f = fixture(streamsUrl, "https://channels.example/manifest.json")
        val streams = f.id("Example Streams")
        val channels = f.id("Channels")
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams", "Channels"), f.names())
        assertEquals(listOf(false, true, true, true), f.state.rows.map { it.canMoveUp })
        assertEquals(listOf(true, true, true, false), f.state.rows.map { it.canMoveDown })

        f.addons.moveUp(streams)
        runCurrent()
        assertEquals(listOf("Cinemeta", "Example Streams", "OpenSubtitles v3", "Channels"), f.names())
        assertEquals(streams, f.id("Example Streams"))

        f.addons.moveTo(channels, 0)
        runCurrent()
        assertEquals(listOf("Channels", "Cinemeta", "Example Streams", "OpenSubtitles v3"), f.names())

        // stremio-core's type order: movie, series, channel.
        assertEquals(
            listOf(T.addons.allTypes, T.browse.type.movie, T.browse.type.series, T.browse.type.channel),
            f.state.typeFilters.map { it.label },
        )
        // The chip names the filter until it narrows the list, never "All" alone; a screen reader hears both parts.
        val type = isolate(T.browse.discover.type)
        assertEquals(FilterChip(T.browse.discover.type, T.browse.discover.type, "$type: ${isolate(T.addons.allTypes)}", narrowed = false), f.state.typeChip)
        f.addons.filterType("movie")
        assertEquals(FilterChip(T.browse.discover.type, T.browse.type.movie, "$type: ${isolate(T.browse.type.movie)}", narrowed = true), f.state.typeChip)
        assertEquals(listOf("Cinemeta", "Example Streams", "OpenSubtitles v3"), f.names())
        // Among the shown addons Cinemeta moves below Example Streams; the hidden Channels stays first.
        f.addons.moveDown(f.id("Cinemeta"))
        runCurrent()
        assertEquals(listOf("Example Streams", "Cinemeta", "OpenSubtitles v3"), f.names())
        f.addons.filterType(AddonsStore.ALL_TYPES)
        assertEquals(listOf("Channels", "Example Streams", "Cinemeta", "OpenSubtitles v3"), f.names())
        assertEquals(listOf("https://channels.example/manifest.json", streamsUrl, DefaultAddons.CINEMETA_URL, DefaultAddons.OPENSUBTITLES_URL), f.store.stored.map { it.transportUrl })
    }

    @Test
    fun removingAsksFirstAndTheBuiltInAddonCannotBeRemoved() = runTest {
        val f = fixture(streamsUrl)
        val cinemeta = f.state.rows.first { it.name == "Cinemeta" }
        assertFalse(cinemeta.removable)
        assertEquals(T.addons.builtIn, cinemeta.note)
        f.addons.remove(cinemeta.id)
        assertNull(f.state.removeDialog)
        assertEquals(T.addons.error.protectedAddon, f.state.notice)
        assertEquals(3, f.state.rows.size)

        val streams = f.id("Example Streams")
        f.addons.remove(streams)
        assertEquals(T.addons.remove.title(isolate("Example Streams")), f.state.removeDialog?.title)
        f.addons.dismissRemove()
        runCurrent()
        assertEquals(3, f.store.stored.size, "dismissed: still installed")

        f.addons.remove(streams)
        f.addons.confirmRemove()
        runCurrent()
        assertNull(f.state.removeDialog)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), f.names())
        assertEquals(listOf(DefaultAddons.CINEMETA_URL, DefaultAddons.OPENSUBTITLES_URL), f.store.stored.map { it.transportUrl })
    }

    @Test
    fun aRemovalConfirmedWhileAMoveIsStoredIsStoredAfterIt() = runTest {
        val f = fixture(streamsUrl, "https://channels.example/manifest.json")
        val streams = f.id("Example Streams")
        f.addons.moveUp(f.id("Channels"))
        f.addons.remove(streams)
        f.addons.confirmRemove()
        assertNull(f.state.removeDialog, "confirmed, the removal waits for the move")
        runCurrent()
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Channels"), f.names())
    }

    @Test
    fun aRemovalAskedForAnAddonRemovedMeanwhileDoesntComeBackWithIt() = runTest {
        val f = fixture(streamsUrl)
        f.addons.remove(f.id("Example Streams"))
        assertNotNull(f.state.removeDialog)
        // Removed elsewhere (another device's sync), then installed again.
        f.registry.remove(streamsUrl)
        runCurrent()
        assertNull(f.state.removeDialog)
        f.registry.install(streamsUrl.transportUrl())
        runCurrent()
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams"), f.names())
        assertNull(f.state.removeDialog)
    }

    @Test
    fun anInstallLinkOpenedDuringAnInstallIsPreviewedOnceItEnds() = runTest {
        val f = fixture()
        f.addons.previewInstall(streamsUrl.transportUrl())
        runCurrent()
        f.addons.confirmInstall()
        assertIs<AddonInstallStep.Installing>(f.state.install.step)
        f.addons.previewInstall("https://channels.example/manifest.json".transportUrl())
        assertIs<AddonInstallStep.Installing>(f.state.install.step)
        runCurrent()
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams"), f.names(), "the confirmed install ran")
        assertIs<AddonInstallStep.Preview>(f.state.install.step)
        assertEquals("Channels", f.state.install.preview?.name)
    }

    @Test
    fun theAddonLinkIsRevealedAndCopiedOnlyOnRequest() = runTest {
        val f = fixture(streamsUrl)
        val streams = f.id("Example Streams")
        f.addons.openDetails(streams)
        val details = assertNotNull(f.state.details)
        assertEquals("Example Streams", details.name)
        assertTrue(details.configurable)
        assertFalse(details.share.revealed)
        assertNull(details.share.link)
        assertFalse(secret in f.state.toString())

        // Copying hands the link over without showing it.
        f.addons.copyLink()
        assertEquals(listOf(streamsUrl), f.host.copied)
        assertFalse(secret in f.state.toString())

        f.addons.revealLink()
        assertEquals(streamsUrl, f.state.details?.share?.link)
        f.addons.hideLink()
        assertNull(f.state.details?.share?.link)

        // A reveal ends with the details.
        f.addons.revealLink()
        f.addons.closeDetails()
        f.addons.openDetails(streams)
        assertNull(f.state.details?.share?.link)
        assertFalse(secret in f.state.toString())

        f.addons.configure(streams)
        assertEquals(listOf("https://addon.example/$secret/configure"), f.host.opened)
    }

    private val scrobblerUrl = "https://scrobbler.example/manifest.json"

    @Test
    fun theRowSwitchTurnsAnAddonOffAndOnAndTheBuiltInAddonStaysOn() = runTest {
        val f = fixture(streamsUrl)
        val streams = f.id("Example Streams")
        fun row(name: String) = f.state.rows.first { it.name == name }
        assertTrue(row("Example Streams").enabled)
        assertTrue(row("Example Streams").canDisable)

        f.addons.setEnabled(streams, false)
        runCurrent()
        assertFalse(row("Example Streams").enabled)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams"), f.names(), "off, it keeps its place")
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), f.registry.addons.value.map { it.manifest.name }, "and is asked for nothing")
        f.addons.openDetails(streams)
        assertEquals(false, f.state.details?.enabled)
        assertEquals(T.addons.details.enabledOff, f.state.details?.enabledNote)

        f.addons.setEnabled(streams, true)
        runCurrent()
        assertTrue(row("Example Streams").enabled)
        assertEquals(true, f.state.details?.enabled)
        assertNull(f.state.details?.enabledNote)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Example Streams"), f.registry.addons.value.map { it.manifest.name })

        assertFalse(row("Cinemeta").canDisable)
        f.addons.setEnabled(f.id("Cinemeta"), false)
        runCurrent()
        assertTrue(row("Cinemeta").enabled)
        assertTrue(f.store.stored.first { it.transportUrl == DefaultAddons.CINEMETA_URL }.enabled)
        assertEquals("Cinemeta", f.registry.addons.value.first().manifest.name)
    }

    @Test
    fun aChangeMadeWhileAnotherIsStoredIsStoredAfterIt() = runTest {
        val f = fixture(streamsUrl, "https://channels.example/manifest.json")
        fun enabled() = f.state.rows.associate { it.name to it.enabled }
        // Two switches in quick succession, then a reorder: none is dropped.
        f.addons.setEnabled(f.id("Example Streams"), false)
        assertTrue(f.state.working)
        f.addons.setEnabled(f.id("Channels"), false)
        f.addons.moveUp(f.id("Channels"))
        runCurrent()
        assertEquals(mapOf("Cinemeta" to true, "OpenSubtitles v3" to true, "Channels" to false, "Example Streams" to false), enabled())
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Channels", "Example Streams"), f.names())
        assertFalse(f.state.working)
    }

    @Test
    fun aNewInstallAskingForTheWatchActivityWaitsForTheTickAndKeepsTheAgreement() = runTest {
        val f = fixture()
        f.addons.previewInstall(scrobblerUrl.transportUrl())
        runCurrent()
        val asking = assertNotNull(f.state.install.preview)
        assertEquals(AddonInstallAction.Install(T.addons.install.confirm), asking.action)
        assertEquals(false, asking.watchActivity?.checked)
        assertFalse(asking.canConfirm)

        f.addons.confirmInstall()
        runCurrent()
        assertIs<AddonInstallStep.Preview>(f.state.install.step, "no install without the tick")
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3"), f.names())

        f.addons.setInstallConsent(true)
        val ticked = assertNotNull(f.state.install.preview)
        assertEquals(true, ticked.watchActivity?.checked)
        assertTrue(ticked.canConfirm)
        f.addons.confirmInstall()
        runCurrent()
        assertIs<AddonInstallStep.Installed>(f.state.install.step)
        assertEquals(listOf("Cinemeta", "OpenSubtitles v3", "Scrobbler"), f.names())
        assertTrue(f.store.stored.single { it.transportUrl == scrobblerUrl }.watchActivity)
        assertEquals(listOf(scrobblerUrl), f.registry.watchActivityAddons().map { it.transportUrl })
        assertNull(f.state.rows.single { it.name == "Scrobbler" }.note)
        f.addons.openDetails(f.id("Scrobbler"))
        assertEquals(true, f.state.details?.watchActivity?.checked)

        // An addon that doesn't ask has no tick to wait for.
        f.addons.previewInstall(streamsUrl.transportUrl())
        runCurrent()
        val plain = assertNotNull(f.state.install.preview)
        assertNull(plain.watchActivity)
        assertTrue(plain.canConfirm)
    }

    @Test
    fun anUpdateNewlyAskingForTheWatchActivityInstallsWithoutTheAgreementUntilTheUserGivesIt() = runTest {
        val f = fixture()
        f.scrobblerVersion = "1.0.0"
        f.registry.install(scrobblerUrl.transportUrl())
        runCurrent()
        assertNull(f.state.rows.single { it.name == "Scrobbler" }.note)

        f.scrobblerVersion = "2.0.0"
        f.addons.previewInstall(scrobblerUrl.transportUrl())
        runCurrent()
        val update = assertNotNull(f.state.install.preview)
        assertEquals(AddonInstallAction.Update(T.addons.install.update), update.action)
        assertEquals(false, update.watchActivity?.checked)
        assertTrue(update.canConfirm, "an update needn't wait for the tick")
        f.addons.confirmInstall()
        runCurrent()
        assertIs<AddonInstallStep.Installed>(f.state.install.step)
        val updated = f.store.stored.single { it.transportUrl == scrobblerUrl }
        assertTrue("\"2.0.0\"" in updated.manifestJson)
        assertFalse(updated.watchActivity)
        assertEquals(emptyList(), f.registry.watchActivityAddons())
        val scrobbler = f.id("Scrobbler")
        assertEquals(T.addons.watchActivity.notAllowed, f.state.rows.single { it.id == scrobbler }.note)
        f.addons.openDetails(scrobbler)
        assertEquals(false, f.state.details?.watchActivity?.checked)

        f.addons.setWatchActivity(scrobbler, true)
        runCurrent()
        assertEquals(true, f.state.details?.watchActivity?.checked)
        assertNull(f.state.rows.single { it.id == scrobbler }.note)
        assertTrue(f.store.stored.single { it.transportUrl == scrobblerUrl }.watchActivity)
        assertEquals(listOf(scrobblerUrl), f.registry.watchActivityAddons().map { it.transportUrl })
    }
}

private val AddonsUiState.rows: List<AddonRow> get() = assertIs<AddonsBody.Rows>(body).addons

private val AddonBrowseUiState.results: List<AddonBrowseItem> get() = assertIs<AddonBrowseBody.Results>(body).items

/** The manifest the step shows, if it shows one. */
private val AddonInstallUiState.preview: AddonPreviewUiState?
    get() = when (val step = step) {
        is AddonInstallStep.Preview -> step.preview
        is AddonInstallStep.Installing -> step.preview
        else -> null
    }

private val AddonInstallUiState.error: String?
    get() = when (val step = step) {
        is AddonInstallStep.EnterLink -> step.error
        is AddonInstallStep.Preview -> step.error
        else -> null
    }

private val AddonInstallUiState.result: String get() = assertIs<AddonInstallStep.Installed>(step).result

private val AddonInstallUiState.enterLink: AddonInstallStep.EnterLink get() = assertIs(step)
