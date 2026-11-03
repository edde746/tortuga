@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.app

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.kids.KidsTitle
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.presentation.account.await
import com.edde746.tortuga.presentation.account.remainingText
import com.edde746.tortuga.presentation.navigation.NavigationStacks
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.SavedNavigation
import com.edde746.tortuga.presentation.navigation.SavedRoute
import com.edde746.tortuga.presentation.navigation.SavedStacks
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.profiles.ParentPinStore
import com.edde746.tortuga.presentation.profiles.PinThrottle
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.DeviceLinkQr
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/** A kids profile's app: what waits for a parent's PIN, and the links, players and screens it doesn't reach. */
class KidsShellTest {
    private val server = FakeSyncServer()

    /**
     * A signed-in shell running Mia, a kids profile (with the PIN [miaPin], on [platform], with the stacks [saved] its
     * last session on this device left); the account's Parent profile has the PIN 1234.
     */
    private suspend fun TestScope.kidsShell(
        device: TestDevice,
        miaPin: String?,
        platform: FakeAppPlatform = FakeAppPlatform(),
        saved: SavedStacks? = null,
    ): RootSession {
        val graph = testGraph(device.data)
        val root = RootSession(graph, device.account, device.engine, TestSignInPlatform(), platform, Startup(backgroundScope, Duration.ZERO))
        root.screen.await { it == RootScreen.SignIn }
        device.account.signIn(server.serverUrl) { TestDevice.credential() }
        root.screen.await { it == RootScreen.Profiles }
        val parent = device.data.profiles.create("Parent", null)
        device.data.profiles.setPin(parent.id, "1234")
        val mia = device.data.profiles.create("Mia", null, kids = true)
        miaPin?.let { device.data.profiles.setPin(mia.id, it) }
        saved?.let { device.data.deviceSettings.set(SavedNavigation(mia.id), it) }
        runCurrent()
        root.gate.enterProfile(mia.id)
        runCurrent()
        return root
    }

    private val RootSession.running: AppSession get() = assertIs<RootScreen.Main>(screen.value).app

    private fun ParentPinStore.type(pin: String) = pin.forEach { digit(it.digitToInt()) }

    /** A kids profile's app without an account (as a development session runs it). */
    private suspend fun TestScope.kidsApp(platform: FakeAppPlatform = FakeAppPlatform()): AppSession {
        val graph = testGraph()
        val mia = graph.data.profiles.create("Mia", null, kids = true)
        return AppSession(graph, mia.id, platform, start = null, profileAccount = null).also { runCurrent() }
    }

    @Test
    fun leavingAKidsProfileTakesAParentsPinNotItsOwn() = runTest {
        TestDevice("Tablet", server, this, backgroundScope).use { device ->
            val root = kidsShell(device, miaPin = "5555")
            val app = root.running

            app.switchProfile()
            runCurrent()
            assertEquals(AppOverlay.ParentPin, app.state.value.overlay)
            assertSame(app, root.running, "the picker waits for the PIN")
            val closed = app.parentPinStore()
            assertEquals(T.kids.pin.title, closed.state.value.title)
            assertEquals(T.kids.pin.leave, closed.state.value.subtitle)
            closed.cancel()
            assertNull(app.state.value.overlay)
            closed.release()

            app.switchProfile()
            val pad = app.parentPinStore()
            pad.type("5555")
            runCurrent()
            assertEquals(T.profiles.pin.wrong, pad.state.value.error, "the kids profile's own PIN doesn't let it out")
            assertEquals(AppOverlay.ParentPin, app.state.value.overlay)
            assertSame(app, root.running)
            pad.type("1234")
            runCurrent()
            assertNull(app.state.value.overlay)
            assertEquals(RootScreen.Profiles, root.screen.value)
            pad.release()
            root.close()
        }
    }

    @Test
    fun aParentsPinLockoutOutlivesARelaunch() = runTest {
        TestDevice("Tablet", server, this, backgroundScope).use { device ->
            val root = kidsShell(device, miaPin = null)
            val app = root.running
            app.switchProfile()
            val pad = app.parentPinStore()
            repeat(PinThrottle.FREE_ATTEMPTS + 1) {
                pad.type("0000")
                runCurrent()
            }
            val until = assertNotNull(device.data.deviceSettings.value(DeviceSetting.PinLockouts)[PinThrottle.PARENT]).lockedUntil
            assertEquals(T.profiles.pin.lockedOut(remainingText(until, device.clock())), pad.state.value.error)
            pad.release()
            root.close()

            // The app starts again over the same device: Mia runs, and the parent's PIN pad is still locked out.
            val graph = testGraph(device.data)
            val relaunched = RootSession(graph, device.account, device.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
            relaunched.screen.await { it is RootScreen.Main }
            val again = relaunched.running
            again.switchProfile()
            val locked = again.parentPinStore()
            runCurrent()
            assertEquals(T.profiles.pin.lockedOut(remainingText(until, device.clock())), locked.state.value.error)
            locked.type("1234")
            runCurrent()
            assertEquals(AppOverlay.ParentPin, again.state.value.overlay, "a parent's PIN isn't taken while locked out")
            assertSame(again, relaunched.running)
            locked.release()
            relaunched.close()
        }
    }

    @Test
    fun aKidsProfilesSettingsHaveNoAccountAddonsOrEditingAndManagingTakesAParentsPin() = runTest {
        TestDevice("Tablet", server, this, backgroundScope).use { device ->
            val root = kidsShell(device, miaPin = null)
            val app = root.running
            app.navigator.navigate(Route.Settings)
            val settings = assertNotNull(app.settingsSession())
            runCurrent()
            assertEquals(
                listOf(SettingsSection.PLAYBACK, SettingsSection.APPEARANCE, SettingsSection.DEVICE, SettingsSection.SUBTITLES, SettingsSection.ABOUT),
                settings.main.state.value.sections.map { it.section },
            )
            assertNull(settings.account)
            assertNull(settings.addons)
            assertNull(settings.home)
            assertNull(settings.trackers)
            assertNull(settings.downloads)
            assertNull(settings.data)

            settings.main.editProfile()
            app.editProfile(app.profileId)
            assertNull(app.state.value.overlay, "the kids profile isn't edited from itself")

            settings.main.manageProfiles()
            assertEquals(AppOverlay.ParentPin, app.state.value.overlay)
            val pad = app.parentPinStore()
            assertEquals(T.kids.pin.settings, pad.state.value.subtitle)
            pad.type("1234")
            runCurrent()
            assertEquals(RootScreen.Profiles, root.screen.value)
            pad.release()
            root.close()
        }
    }

    @Test
    fun aKidsProfileFollowsNoInstallDeviceOrInviteLinkAndOpensNoWebPage() = runTest {
        val platform = FakeAppPlatform()
        val app = kidsApp(platform)
        val home = listOf(Route.Board)

        app.openLink(DeepLink.InstallAddon("https://addon.example/manifest.json".transportUrl()))
        assertTrue(app.openIncomingLink("stremio://addon.example/manifest.json"))
        app.openAddons()
        assertTrue(app.openIncomingLink(WatchInvite("AB2CD3", WatchRelayEndpoint.HOSTED).toUrl()))
        assertTrue(app.openIncomingLink(DeviceLinkQr(ServerUrl.parse("https://sync.example"), "ABCDEFGH").encode()))
        app.openWatchTogether()
        runCurrent()
        assertEquals(home, app.navigator.backStack.value)
        assertNull(app.state.value.installPreview)
        assertNull(app.settingsSession())

        app.openUrl("https://www.imdb.com/title/tt1/")
        assertEquals(emptyList(), platform.opened)
        assertNull(app.state.value.overlay)

        // A title's link opens its page, where the title's own rules apply.
        app.openLink(DeepLink.Detail("series", "tt1", "tt1:1:2"))
        assertEquals(Route.Detail("series", "tt1", "tt1:1:2"), app.navigator.current)
        app.close()
    }

    @Test
    fun aKidsProfilesHomeHasNoWatchTogetherOrTvGuideShortcut() = runTest {
        val app = kidsApp(FakeAppPlatform())
        app.graph.data.addons.install(
            app.profileId,
            "https://livetv.example.com/manifest.json",
            """{"id":"org.example.livetv","version":"1.0.0","name":"Live TV","types":["tv"],"resources":["catalog"],
               "catalogs":[{"type":"tv","id":"channels","name":"Channels","extra":[{"name":"date","isRequired":true}]}],
               "behaviorHints":{"epgProvider":true}}""",
        )
        val board = app.boardStore()
        runCurrent()
        val keys = board.state.value.shortcuts.map { it.key }
        assertTrue("watchTogether" !in keys && "guide" !in keys, "shortcuts: $keys")
        board.release()

        app.graph.data.profiles.setKids(app.profileId, false, emptyList())
        val adult = app.boardStore()
        runCurrent()
        assertTrue(adult.state.value.shortcuts.map { it.key }.containsAll(listOf("watchTogether", "guide")))
        adult.release()
        app.close()
    }

    @Test
    fun aKidsProfilePlaysInTortugasPlayerOnly() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.data.deviceSettings.set(DeviceSetting.Player, VLC.id)
        val mia = w.data.profiles.create("Mia", null, kids = true)
        // The film is allowed on the profile, so its rating doesn't matter here.
        w.data.settings.set(mia.id, ProfileSetting.KidsAllowed, listOf(KidsTitle(MOVIE.id, "movie", MOVIE.name)))
        val app = AppSession(w.graph, mia.id, w.platform, start = null, profileAccount = null)
        w.settle()

        app.playback.play(w.movie())
        w.settle()
        app.navigator.back()
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals(emptyList(), w.launcher.launches, "no player app was handed the stream")
        assertEquals(2, w.platform.playbacks.size)
        app.close()
    }

    /** A kids profile's player has no Watch together: no room to start from its options. Another profile's has. */
    @Test
    fun aKidsProfilesPlayerOffersNoWatchTogether() = runTest {
        val w = PlayerAppsWorld(this)
        val mia = w.data.profiles.create("Mia", null, kids = true)
        // The film is allowed on the profile, so its rating doesn't matter here.
        w.data.settings.set(mia.id, ProfileSetting.KidsAllowed, listOf(KidsTitle(MOVIE.id, "movie", MOVIE.name)))
        val kids = AppSession(w.graph, mia.id, w.platform, start = null, profileAccount = null)
        w.settle()
        kids.playback.play(w.movie())
        w.settle()
        assertNull(assertNotNull(kids.playback.active, "the film plays").store.state.value.watch, "the kids profile's player offers Watch together")
        kids.close()

        val alex = w.data.profiles.create("Alex", null)
        val adult = AppSession(w.graph, alex.id, w.platform, start = null, profileAccount = null)
        w.settle()
        adult.playback.play(w.movie())
        w.settle()
        val watch = assertNotNull(assertNotNull(adult.playback.active).store.state.value.watch, "another profile's player has Watch together")
        assertTrue(watch.startChoices.isNotEmpty(), "another profile's player offers no room to start")
        adult.close()
    }

    /**
     * The stacks a kids profile's last session left reopen without the TV guide, where the app has one: a kids profile
     * has no guide. Another profile's, started as the account shell starts it (the gate has read its profile), reopen
     * with it.
     */
    @Test
    fun aKidsProfilesSavedStacksReopenWithoutTheTvGuide() = runTest {
        TestDevice("TV", server, this, backgroundScope).use { device ->
            val saved = SavedStacks(
                SavedStacks.VERSION,
                "BOARD",
                mapOf(
                    "BOARD" to listOf(SavedRoute("board"), SavedRoute("guide")),
                    "LIBRARY" to listOf(SavedRoute("library"), SavedRoute("detail", "movie", "tt1")),
                ),
            )
            val platform = FakeAppPlatform()
            val root = kidsShell(device, miaPin = null, platform, saved)
            val app = root.running
            val library = TopLevelDestination.LIBRARY to listOf(Route.Library, Route.Detail("movie", "tt1"))
            assertEquals(
                NavigationStacks(TopLevelDestination.BOARD, mapOf(TopLevelDestination.BOARD to listOf(Route.Board), library)),
                app.navigator.stacks.value,
            )

            val parent = device.data.profiles.profiles().single { it.name == "Parent" }
            val account = ProfileAccount(device.account, device.engine, TestSignInPlatform(), root.gate)
            val adult = AppSession(app.graph, parent.id, platform, start = null, account, saved = saved, startProfile = parent)
            assertEquals(
                NavigationStacks(TopLevelDestination.BOARD, mapOf(TopLevelDestination.BOARD to listOf(Route.Board, Route.Guide), library)),
                adult.navigator.stacks.value,
            )
            adult.close()
            root.close()
        }
    }

    /**
     * A profile not read yet counts as a kids profile: a session started without its profile read reopens its stacks
     * without the TV guide.
     */
    @Test
    fun aProfileNotReadYetReopensWithoutTheTvGuide() = runTest {
        val graph = testGraph()
        val alex = graph.data.profiles.create("Alex", null)
        val saved = SavedStacks(SavedStacks.VERSION, "BOARD", mapOf("BOARD" to listOf(SavedRoute("board"), SavedRoute("guide"))))
        val app = AppSession(graph, alex.id, FakeAppPlatform(), start = null, profileAccount = null, saved = saved)
        assertEquals(NavigationStacks(TopLevelDestination.BOARD, mapOf(TopLevelDestination.BOARD to listOf(Route.Board))), app.navigator.stacks.value)
        app.close()
    }
}
