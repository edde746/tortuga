package com.edde746.tortuga.app

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [startDevSession]'s profile options, as [DevLaunch.parse] reads them, and the app [devAppSession] starts on them, on
 * a real graph ([AppBootstrap.dev]) against a local addon.
 */
class DevStartTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val addon = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/manifest.json" -> MANIFEST
                "/activity/manifest.json" -> ACTIVITY_MANIFEST
                "/meta/movie/demo-a.json" -> """{"meta":{"id":"demo-a","type":"movie","name":"Amber Shore"}}"""
                "/meta/movie/demo-b.json" -> """{"meta":{"id":"demo-b","type":"movie","name":"Brass Lanterns"}}"""
                else -> null
            }?.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            body?.let { exchange.responseBody.write(it) }
            exchange.close()
        }
        start()
    }
    private val addonUrl = "http://127.0.0.1:${addon.address.port}/manifest.json"
    private val activityUrl = "http://127.0.0.1:${addon.address.port}/activity/manifest.json"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
        // The system's settings, as a platform reports them before it builds a core.
        SystemInputs.report(listOf("en"), region = null, captions = false, audioDescriptions = false)
    }

    @AfterTest
    fun tearDown() {
        addon.stop(0)
        Dispatchers.resetMain()
        main.close()
    }

    private fun session(block: suspend (AppGraph) -> Unit) = runBlocking {
        val cache = Files.createTempDirectory("dev-start")
        val boot = AppBootstrap.dev(
            DatabaseFactory.inMemory(), BuildFlavor.SIDELOADED, "test", DeviceDescriptor("Test", DevicePlatform.WINDOWS), cache.toString(), trackers = null,
        )
        try {
            block(boot.graph)
        } finally {
            boot.close()
            cache.toFile().deleteRecursively()
        }
    }

    /** The session [args] ask for beside `--dev` and the local addon, started without a sign-in platform. */
    private suspend fun AppGraph.start(vararg args: String): DevStart =
        startDevSession(DevLaunch.parse(listOf("--dev", "--dev-addon", addonUrl) + args), signIn = null)

    private suspend fun AppGraph.installed(start: DevStart): List<String> = addonRegistry(start.profile.id).load().map { it.transportUrl }

    /** The installed addons that the user agreed may receive their watch activity. */
    private suspend fun AppGraph.watchActivity(start: DevStart): List<String> =
        addonRegistry(start.profile.id).watchActivityAddons().map { it.transportUrl }

    /** What covers the screens of [start]'s app as it is built on the main thread, as the apps build it. */
    private suspend fun AppGraph.overlayAtStart(start: DevStart): AppOverlay? = withContext(Dispatchers.Main) {
        val app = devAppSession(start, StartPlatform)
        app.state.value.overlay.also { app.close() }
    }

    @Test
    fun theWatchActivityAgreementIsGivenOnlyWhenAskedAndOnlyToAddonsThatDeclareIt() {
        session { graph -> assertEquals(emptyList(), graph.watchActivity(graph.start("--dev-addon", activityUrl))) }
        session { graph ->
            val start = graph.start("--dev-addon", activityUrl, "--dev-watch-activity")
            assertEquals(listOf(activityUrl), graph.watchActivity(start))
        }
    }

    @Test
    fun addonsOnlyLeavesTheProfileWithTheGivenAddonsAlone() = session { graph ->
        val start = graph.start("--dev-addons-only")
        assertEquals(listOf(addonUrl), graph.installed(start))
    }

    @Test
    fun withoutAddonsOnlyTheDefaultAddonsStay() = session { graph ->
        val start = graph.start()
        assertEquals(listOf(DefaultAddons.CINEMETA_URL, DefaultAddons.OPENSUBTITLES_URL, addonUrl), graph.installed(start))
    }

    @Test
    fun theProfileIsNamedAndItsLibraryHoldsTheGivenTitlesInOrder() = session { graph ->
        val start = graph.start("--dev-profile", "Mara:sea", "--dev-library", "movie:demo-a", "--dev-library", "movie:demo-b")
        val profile = graph.data.profiles.profiles().single()
        assertEquals(listOf("Mara", "sea"), listOf(profile.name, profile.avatar))
        assertEquals(listOf("demo-a", "demo-b"), graph.data.library.observeLibrary(start.profile.id).first().map { it.id })
    }

    @Test
    fun theProfileStartsInTheGivenInterfaceLanguage() = session { graph ->
        val start = graph.start("--dev-language", "ar")
        assertEquals("ar", graph.data.settings.settings(start.profile.id).interfaceLanguage)
    }

    /** `--dev-picks` seeds the profile's picks and turns its stream suggestions on; `--dev-suggestions false` keeps them off. */
    @Test
    fun mockedPicksTurnSuggestionsOnUnlessTheyAreSwitchedOff() {
        session { graph ->
            val start = graph.start("--dev-picks", "phone")
            assertTrue(graph.data.streamPicks.picks(start.profile.id).isNotEmpty())
            assertTrue(graph.data.settings.settings(start.profile.id).streamSuggestions)
        }
        session { graph ->
            val start = graph.start("--dev-picks", "phone", "--dev-suggestions", "false")
            assertTrue(graph.data.streamPicks.picks(start.profile.id).isNotEmpty())
            assertEquals(false, graph.data.settings.settings(start.profile.id).streamSuggestions)
        }
        session { graph -> assertEquals(false, graph.data.settings.settings(graph.start().profile.id).streamSuggestions) }
        assertFailsWith<IllegalArgumentException> { DevLaunch.parse(listOf("--dev", "--dev-suggestions", "on")) }
    }

    @Test
    fun kidsMakesTheProfileAKidsProfileBesideAParentLockedWithThePin() = session { graph ->
        val start = graph.start("--dev-profile", "Mia", "--dev-kids", "2468")
        val profiles = graph.data.profiles.profiles()
        val kid = profiles.single { it.id == start.profile.id }
        val parent = profiles.single { it.id != start.profile.id }
        assertEquals("Mia" to true, kid.name to kid.kids)
        assertEquals("Parent" to false, parent.name to parent.kids)
        assertTrue(graph.data.profiles.verifyPin(parent.id, "2468"))
        // A kept session starts again on the same two profiles.
        val again = graph.start("--dev-profile", "Mia", "--dev-kids", "2468")
        assertEquals(start.profile.id, again.profile.id)
        assertEquals(2, graph.data.profiles.profiles().size)
        for (bad in listOf("", "246", "24a8", "24680")) assertFailsWith<IllegalArgumentException>(bad) { DevKids.parse(bad) }
    }

    /**
     * The session's first screens open as its profile allows from the start: `--dev-open profile` opens a new
     * profile's editor at once on a profile that isn't a kids profile, and none on a kids profile.
     */
    @Test
    fun theNewProfilesEditorOpensAtOnceUnlessTheProfileIsAKidsProfile() {
        session { graph ->
            val start = graph.start("--dev-open", "profile")
            assertEquals(AppOverlay.EditProfile(null), graph.overlayAtStart(start))
        }
        session { graph ->
            val start = graph.start("--dev-open", "profile", "--dev-kids", "2468")
            assertNull(graph.overlayAtStart(start))
        }
    }

    @Test
    fun aProfileWithoutANameOrWithAnUnknownAvatarOrABadPinIsRejected() {
        assertEquals(DevProfile("Mara", null, null), DevProfile.parse("Mara"))
        assertEquals(DevProfile("Mara", null, "2468"), DevProfile.parse("Mara::2468"))
        for (bad in listOf("", ":sea", "Mara:teal", "Mara:sea:246", "Mara:sea:24a8", "Mara:sea:2468:1")) {
            assertFailsWith<IllegalArgumentException>(bad) { DevProfile.parse(bad) }
        }
        for (bad in listOf("movie", "movie:", ":demo-a")) assertFailsWith<IllegalArgumentException>(bad) { DevLibraryItem.parse(bad) }
    }

    /** The OS side a session's start reaches: nothing plays, opens or copies there. */
    private object StartPlatform : AppPlatform {
        override val formFactor = FormFactor.DESKTOP
        override val playerConfig = PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis = 500)

        override fun createPlayback(): PlatformPlayback = error("a session's start plays nothing")

        override fun setFullscreen(fullscreen: Boolean) = Unit

        override fun openUrl(url: String) = false

        override fun setClipboardText(text: String, sensitive: Boolean) = Unit

        override fun deviceFeatures() = DeviceFeatures(hardwareDecoding = false, hdr = false, matchFrameRate = false, audioPassthrough = false)

        override fun bundledTexts(folder: String): List<BundledText> = emptyList()

        override fun exit() = Unit
    }

    private companion object {
        val MANIFEST = """
            {"id":"app.tortuga.devstart","version":"1.0.0","name":"Dev start","resources":["meta"],"types":["movie"],
             "idPrefixes":["demo-"],"catalogs":[]}
        """.trimIndent()
        val ACTIVITY_MANIFEST = """
            {"id":"app.tortuga.devstart.activity","version":"1.0.0","name":"Dev start activity","resources":["player","library"],
             "types":["movie"],"idPrefixes":["demo-"],"catalogs":[]}
        """.trimIndent()
    }
}
