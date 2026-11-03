@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.app

import com.edde746.tortuga.data.movie
import com.edde746.tortuga.data.series
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.account.await
import com.edde746.tortuga.presentation.homescreen.HomeScreenLink
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.navigation.NavigationStacks
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.SavedNavigation
import com.edde746.tortuga.presentation.navigation.SavedRoute
import com.edde746.tortuga.presentation.navigation.SavedStacks
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.settings.AccountSettingsStore
import com.edde746.tortuga.presentation.settings.LinkApprovalStep
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.SignedOutReason
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.SyncPaths
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The account shell: which app runs for the gate's destination, when an app ends, where an OS link lands. */
class RootSessionTest {
    private val server = FakeSyncServer()

    @Test
    fun aProfilesAppOutlastsThePickerUntilAnotherProfileRunsOrTheAccountSignsOut() = runTest {
        TestDevice("Tablet", server, this, backgroundScope).use { device ->
            val platform = FakeAppPlatform()
            val root = RootSession(testGraph(device.data), device.account, device.engine, TestSignInPlatform(), platform, Startup(backgroundScope, Duration.ZERO))
            root.screen.await { it == RootScreen.SignIn }
            device.account.signIn(server.serverUrl) { TestDevice.credential() }
            root.screen.await { it == RootScreen.Profiles }
            val kids = device.data.profiles.create("Kids", null)
            val parents = device.data.profiles.create("Parents", null)

            root.gate.enterProfile(kids.id)
            runCurrent()
            val first = assertIs<RootScreen.Main>(root.screen.value).app
            assertEquals(kids.id, first.profileId)
            var released = 0
            // A Detail on Home's stack, then Library: each destination keeps its stack and its stores.
            val detail = Route.Detail("movie", "tt1")
            first.navigator.navigate(detail)
            first.navigator.retain(detail, "test", create = { Any() }, release = { released++ })
            first.navigator.pick(TopLevelDestination.LIBRARY)
            first.navigator.retain(Route.Library, "test", create = { Any() }, release = { released++ })
            first.playback.play(MOVIE_REQUEST)
            runCurrent()
            // With an account, a sign-in method can be added.
            first.linkIdentity()
            assertEquals(AppOverlay.LinkIdentity, first.state.value.overlay)

            // The picker covers the app: its playback ends and the overlay closes, the rest stays.
            first.switchProfile()
            runCurrent()
            assertEquals(RootScreen.Profiles, root.screen.value)
            assertSame(first, root.app)
            assertNull(first.state.value.playback)
            assertNull(first.state.value.overlay)
            assertEquals(listOf<Route>(Route.Library), first.navigator.backStack.value)
            assertTrue(platform.playbacks.single().scripted.released)
            assertEquals(0, released)

            // Leaving the picker without choosing returns to the same app.
            root.gate.cancelSwitch()
            runCurrent()
            assertSame(first, assertIs<RootScreen.Main>(root.screen.value).app)

            // Another profile gets an app of its own; the first one is closed.
            first.switchProfile()
            runCurrent()
            root.gate.enterProfile(parents.id)
            runCurrent()
            val second = assertIs<RootScreen.Main>(root.screen.value).app
            assertNotSame(first, second)
            assertEquals(parents.id, second.profileId)
            // Every stack of the first profile's app is let go, what it kept released; the next one starts on Home, with
            // no stack of the first one's.
            assertEquals(2, released)
            assertEquals(listOf<Route>(Route.Board), second.navigator.backStack.value)
            assertNull(second.navigator.retain(detail, "test", create = { Any() }, release = {}))
            assertNull(second.navigator.retain(Route.Library, "test", create = { Any() }, release = {}))
            first.playback.play(MOVIE_REQUEST)
            assertEquals(1, platform.playbacks.size, "the closed app starts no playback")

            // Signing out closes the running app, playback and all.
            second.playback.play(MOVIE_REQUEST)
            runCurrent()
            device.account.signOut(discardUnsynced = true)
            root.screen.await { it == RootScreen.SignIn }
            assertNull(root.app)
            assertNull(second.state.value.playback)
            assertTrue(platform.playbacks.last().scripted.released)

            root.close()
            root.close()
        }
    }

    @Test
    fun aDeviceLinksQrCodeWaitsForTheSignInThenOpensTheApprovalInAccount() = runTest {
        TestDevice("Living room TV", server, this, backgroundScope).use { tv ->
            tv.account.restore()
            val pending = tv.account.startDeviceLink(server.serverUrl)
            TestDevice("Phone", server, this, backgroundScope).use { phone ->
                val root = RootSession(testGraph(phone.data), phone.account, phone.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
                root.screen.await { it == RootScreen.SignIn }

                // The camera opens the app on the TV's code while it is signed out: nothing is claimed.
                root.openUrl(pending.qrPayload)
                runCurrent()
                assertEquals(RootScreen.SignIn, root.screen.value)
                assertTrue(server.requests.none { it.endsWith(SyncPaths.LINK_CLAIM) }, "requests: ${server.requests}")

                // Once a profile runs, Settings → Account checks the code and asks to confirm the TV.
                phone.account.signIn(server.serverUrl) { TestDevice.credential() }
                root.screen.await { it == RootScreen.Profiles }
                root.gate.enterProfile(phone.data.profiles.create("Me", null).id)
                runCurrent()
                val settings = checkNotNull(assertIs<RootScreen.Main>(root.screen.value).app.settingsSession())
                assertEquals(SettingsSection.ACCOUNT, settings.main.state.value.section)
                val link = assertIs<LinkApprovalStep.Confirm>(checkNotNull(checkNotNull(settings.account).state.value.linkApproval).step)
                assertEquals(pending.code, link.code)
                assertEquals("Living room TV", link.deviceName)
                root.close()
            }
        }
    }

    @Test
    fun theTvHomeScreenShowsTheRunningProfilesItemsAndEachOpensOnlyInItsOwnProfile() = runTest {
        TestDevice("Living room TV", server, this, backgroundScope).use { tv ->
            val home = FakeHomeScreen()
            val root = RootSession(testGraph(tv.data), tv.account, tv.engine, TestSignInPlatform(), FakeAppPlatform(homeScreen = home), Startup(backgroundScope, Duration.ZERO))
            root.screen.await { it == RootScreen.SignIn }
            tv.account.signIn(server.serverUrl) { TestDevice.credential() }
            root.screen.await { it == RootScreen.Profiles }
            val kids = tv.data.profiles.create("Kids", null).id
            val parents = tv.data.profiles.create("Parents", null).id
            tv.data.library.upsertFromMeta(kids, movie())
            tv.data.library.recordProgress(kids, "tt0111161", "tt0111161", 60_000, 8_520_000)
            tv.data.library.upsertFromMeta(parents, series())
            tv.data.library.recordProgress(parents, "tt0903747", "tt0903747:1:2", 60_000, 2_900_000)
            fun shown() = home.published.last().map { it.profileId to it.id }

            root.gate.enterProfile(kids)
            runCurrent()
            assertEquals(listOf(kids to "tt0111161"), shown())
            val kidsApp = assertIs<RootScreen.Main>(root.screen.value).app

            // The running profile's item opens in it: the player left open at HOME closes, its playback ending, and the
            // item's video resumes.
            kidsApp.playback.play(MOVIE_REQUEST)
            runCurrent()
            assertEquals(listOf(Route.Board, Route.Player), kidsApp.navigator.backStack.value)
            root.openUrl(home.published.last().single().link)
            runCurrent()
            assertNull(kidsApp.state.value.playback)
            assertEquals(
                listOf(Route.Board, Route.Detail("movie", "tt0111161", "tt0111161", videoAction = DetailVideoAction.PLAY)),
                kidsApp.navigator.backStack.value,
            )
            val kidsStack = kidsApp.navigator.backStack.value

            // Another profile's item opens the picker; leaving it for the running profile drops the item.
            val parentsLink = HomeScreenLink.Play(parents, "series", "tt0903747", "tt0903747:1:2").toUrl()
            root.openUrl(parentsLink)
            runCurrent()
            assertEquals(RootScreen.Profiles, root.screen.value)
            root.gate.cancelSwitch()
            runCurrent()
            assertSame(kidsApp, assertIs<RootScreen.Main>(root.screen.value).app)
            assertEquals(kidsStack, kidsApp.navigator.backStack.value)
            assertFalse(kidsApp.openIncomingLink(parentsLink), "an app opens no other profile's item")

            // Entering the item's profile opens it there, and the home screen shows that profile's items instead.
            root.openUrl(parentsLink)
            runCurrent()
            root.gate.enterProfile(parents)
            runCurrent()
            val parentsApp = assertIs<RootScreen.Main>(root.screen.value).app
            assertEquals(parents, parentsApp.profileId)
            assertEquals(Route.Detail("series", "tt0903747", "tt0903747:1:2", videoAction = DetailVideoAction.PLAY), parentsApp.navigator.backStack.value.last())
            assertEquals(listOf(parents to "tt0903747"), shown())
            // Saved progress publishes again.
            tv.data.library.recordProgress(parents, "tt0903747", "tt0903747:1:2", 120_000, 2_900_000)
            runCurrent()
            assertEquals(120_000, home.published.last().single().positionMillis)

            // A PIN takes the profile's items off the home screen; signing out leaves nothing there.
            tv.data.profiles.setPin(parents, "1234")
            runCurrent()
            assertEquals(emptyList(), shown())
            tv.data.profiles.setPin(parents, null)
            runCurrent()
            assertEquals(listOf(parents to "tt0903747"), shown())
            tv.account.signOut(discardUnsynced = true)
            root.screen.await { it == RootScreen.SignIn }
            assertEquals(emptyList(), shown())
            root.close()
        }
    }

    @Test
    fun aTvHomeScreenItemThatStartsTheAppOnAnotherProfileWaitsForThePicker() = runTest {
        TestDevice("Living room TV", server, this, backgroundScope).use { tv ->
            fun launch() = RootSession(testGraph(tv.data), tv.account, tv.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
            val first = launch()
            first.screen.await { it == RootScreen.SignIn }
            tv.account.signIn(server.serverUrl) { TestDevice.credential() }
            first.screen.await { it == RootScreen.Profiles }
            val kids = tv.data.profiles.create("Kids", null).id
            val parents = tv.data.profiles.create("Parents", null).id
            first.gate.enterProfile(kids)
            runCurrent()
            first.close()

            // The next launch goes straight to the device's last profile, which isn't the item's: the picker opens.
            val root = launch()
            root.openUrl(HomeScreenLink.Detail(parents, "series", "tt0903747").toUrl())
            root.screen.await { it == RootScreen.Profiles }
            assertEquals(kids, root.app?.profileId)
            root.gate.enterProfile(parents)
            runCurrent()
            assertEquals(Route.Detail("series", "tt0903747"), assertIs<RootScreen.Main>(root.screen.value).app.navigator.backStack.value.last())
            root.close()
        }
    }

    @Test
    fun aRelaunchReopensEachProfileWhereItWasLeftWithoutThePlayerASheetOrWatchTogether() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            fun launch() = RootSession(testGraph(phone.data), phone.account, phone.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
            val first = launch()
            first.screen.await { it == RootScreen.SignIn }
            phone.account.signIn(server.serverUrl) { TestDevice.credential() }
            first.screen.await { it == RootScreen.Profiles }
            val alex = phone.data.profiles.create("Alex", null).id
            val sam = phone.data.profiles.create("Sam", null).id

            // Sam: Watch together over Home, then a Detail on Search.
            first.gate.enterProfile(sam)
            runCurrent()
            val samApp = assertIs<RootScreen.Main>(first.screen.value).app
            samApp.openWatchTogether()
            samApp.navigator.pick(TopLevelDestination.SEARCH)
            samApp.navigator.navigate(Route.Detail("movie", "tt3"))
            runCurrent()
            // Alex: a Detail on Home; on Library a Detail opened to play an episode, the player over it and a Streams
            // sheet over the player as the app ends.
            samApp.switchProfile()
            runCurrent()
            first.gate.enterProfile(alex)
            runCurrent()
            val alexApp = assertIs<RootScreen.Main>(first.screen.value).app
            assertEquals(listOf<Route>(Route.Board), alexApp.navigator.backStack.value, "a profile's first session starts on Home")
            alexApp.navigator.navigate(Route.Detail("movie", "tt1"))
            alexApp.navigator.pick(TopLevelDestination.LIBRARY)
            alexApp.navigator.navigate(Route.Detail("series", "tt2", "tt2:1:3", videoAction = DetailVideoAction.PLAY))
            alexApp.playback.play(EPISODE_1)
            runCurrent()
            alexApp.navigator.openStreams(EPISODE_1.meta, "tt1:1:2")
            runCurrent()
            assertEquals(Route.Player, alexApp.navigator.backStack.value.dropLast(1).last())
            first.close()

            // The relaunch goes to the device's last profile, Alex, on Alex's stacks: Library's Detail on the episode's
            // season with nothing to play, no player, no sheet.
            val second = launch()
            second.screen.await { it is RootScreen.Main }
            val alexAgain = assertIs<RootScreen.Main>(second.screen.value).app
            assertEquals(alex, alexAgain.profileId)
            assertEquals(
                NavigationStacks(
                    TopLevelDestination.LIBRARY,
                    mapOf(
                        TopLevelDestination.BOARD to listOf(Route.Board, Route.Detail("movie", "tt1")),
                        TopLevelDestination.LIBRARY to listOf(Route.Library, Route.Detail("series", "tt2", shownVideoId = "tt2:1:3")),
                    ),
                ),
                alexAgain.navigator.stacks.value,
            )
            assertNull(alexAgain.state.value.playback)

            // Sam, entered next, finds Sam's: Home without Watch together.
            alexAgain.switchProfile()
            runCurrent()
            second.gate.enterProfile(sam)
            runCurrent()
            val samAgain = assertIs<RootScreen.Main>(second.screen.value).app
            assertEquals(sam, samAgain.profileId)
            assertEquals(
                NavigationStacks(
                    TopLevelDestination.SEARCH,
                    mapOf(
                        TopLevelDestination.BOARD to listOf(Route.Board),
                        TopLevelDestination.SEARCH to listOf(Route.Search, Route.Detail("movie", "tt3")),
                    ),
                ),
                samAgain.navigator.stacks.value,
            )

            // Signing out takes every profile's stacks off the device.
            phone.account.signOut(discardUnsynced = true)
            second.screen.await { it == RootScreen.SignIn }
            assertNull(phone.data.deviceSettings.value(SavedNavigation(alex)))
            assertNull(phone.data.deviceSettings.value(SavedNavigation(sam)))
            second.close()
        }
    }

    @Test
    fun savedStacksDropWhatThisAppDoesntHave() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            val root = RootSession(testGraph(phone.data), phone.account, phone.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
            root.screen.await { it == RootScreen.SignIn }
            phone.account.signIn(server.serverUrl) { TestDevice.credential() }
            root.screen.await { it == RootScreen.Profiles }
            val alex = phone.data.profiles.create("Alex", null).id
            val sam = phone.data.profiles.create("Sam", null).id
            // As a later build wrote them: a route and a destination this one doesn't know beside the TV guide, which it
            // has.
            phone.data.deviceSettings.set(
                SavedNavigation(alex),
                SavedStacks(
                    SavedStacks.VERSION,
                    "LIBRARY",
                    mapOf(
                        "BOARD" to listOf(SavedRoute("board"), SavedRoute("guide")),
                        "LIBRARY" to listOf(SavedRoute("library"), SavedRoute("detail", "movie", "tt4"), SavedRoute("party"), SavedRoute("detail", "movie", "tt5")),
                        "PARTIES" to listOf(SavedRoute("parties")),
                    ),
                ),
            )
            // A format this build doesn't read.
            phone.data.deviceSettings.set(
                SavedNavigation(sam),
                SavedStacks(SavedStacks.VERSION + 1, "LIBRARY", mapOf("LIBRARY" to listOf(SavedRoute("library")))),
            )

            root.gate.enterProfile(alex)
            runCurrent()
            val alexApp = assertIs<RootScreen.Main>(root.screen.value).app
            assertEquals(
                NavigationStacks(
                    TopLevelDestination.LIBRARY,
                    mapOf(
                        TopLevelDestination.BOARD to listOf(Route.Board, Route.Guide),
                        TopLevelDestination.LIBRARY to listOf(Route.Library, Route.Detail("movie", "tt4")),
                    ),
                ),
                alexApp.navigator.stacks.value,
            )

            alexApp.switchProfile()
            runCurrent()
            root.gate.enterProfile(sam)
            runCurrent()
            assertEquals(
                NavigationStacks.of(Route.Board),
                assertIs<RootScreen.Main>(root.screen.value).app.navigator.stacks.value,
            )
            root.close()
        }
    }

    /** A signed-in phone running a profile's app with Settings open; [block] gets the account settings and the app. */
    private suspend fun TestScope.inAccountSettings(block: suspend (TestDevice, RootSession, AccountSettingsStore) -> Unit) {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            val root = RootSession(testGraph(phone.data), phone.account, phone.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
            root.screen.await { it == RootScreen.SignIn }
            phone.account.signIn(server.serverUrl) { TestDevice.credential() }
            root.screen.await { it == RootScreen.Profiles }
            root.gate.enterProfile(phone.data.profiles.create("Me", null).id)
            runCurrent()
            val app = assertIs<RootScreen.Main>(root.screen.value).app
            app.navigator.navigate(Route.Settings)
            val store = checkNotNull(app.settingsSession()?.account)
            runCurrent()
            block(phone, root, store)
            root.close()
        }
    }

    @Test
    fun aDeletionWhoseGitHubGrantStayedSaysWhereToRemoveTortugaOnTheSignInScreen() = runTest {
        server.githubRevocation = GitHubRevocation.FAILED
        server.serverInfo = server.serverInfo.copy(
            providers = server.serverInfo.providers + ProviderInfo(IdentityProvider.GITHUB, "https://github.example", listOf("gh-client")),
        )
        inAccountSettings { _, root, store ->
            store.deleteAccount()
            store.confirmDialog()
            runCurrent()
            // Signing out closed the app (and Settings) while the deletion still asked the server for GitHub's issuer.
            assertEquals(RootScreen.SignIn, root.screen.value)
            assertEquals(T.account.deletion.done + "\n" + T.account.deletion.githubManual(isolate("github.example/settings/applications")), root.gate.state.value.notice)
        }
    }

    @Test
    fun aSessionTheServerEndsDuringTheDeletionStillLeavesNothingOfTheAccount() = runTest {
        inAccountSettings { phone, root, store ->
            // The deletion ends the account's sessions as it commits: the sync engine's next request finds this one gone,
            // which signs the device out and closes the app (and Settings) before the deletion is answered.
            server.afterNextDeletion = {
                assertFailsWith<AccountException> { phone.account.devices() }
                root.screen.await { it == RootScreen.SignIn }
            }
            store.deleteAccount()
            store.confirmDialog()
            runCurrent()
            assertEquals(RootScreen.SignIn, root.screen.value)
            assertEquals(T.account.deletion.done, root.gate.state.value.notice)
            assertEquals(AccountState.SignedOut(SignedOutReason.ACCOUNT_DELETED), phone.account.state.value)
            assertTrue(phone.data.profiles.profiles().isEmpty(), "the deleted account's profiles are gone")
        }
    }

    @Test
    fun aLinkNoAppOpensIsLoggedAndOpensNothing() = runTest {
        val originalSink = Log.sink
        val lines = mutableListOf<String>()
        Log.sink = Log.Sink { level, tag, message, _ -> lines += "$level $tag $message" }
        try {
            TestDevice("Tablet", server, this, backgroundScope).use { device ->
                val root = RootSession(testGraph(device.data), device.account, device.engine, TestSignInPlatform(), FakeAppPlatform(), Startup(backgroundScope, Duration.ZERO))
                device.account.signIn(server.serverUrl) { TestDevice.credential() }
                root.screen.await { it == RootScreen.Profiles }
                root.gate.enterProfile(device.data.profiles.create("Kids", null).id)
                runCurrent()
                val app = assertIs<RootScreen.Main>(root.screen.value).app
                val stack = app.navigator.backStack.value

                root.openUrl("tortuga://nothing-here")
                runCurrent()
                assertEquals(stack, app.navigator.backStack.value)
                assertTrue(lines.any { it.startsWith("WARN ") && "No link this app opens" in it }, "$lines")
                root.close()
            }
        } finally {
            Log.sink = originalSink
        }
    }
}
