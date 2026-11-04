@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.sync.account.signin.RedirectReceiver
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.DevicePoll
import com.edde746.tortuga.trackers.FakeDeviceCode
import com.edde746.tortuga.trackers.FakeRelay
import com.edde746.tortuga.trackers.FakeRelaySignIn
import com.edde746.tortuga.trackers.FakeTrackerAccountServer
import com.edde746.tortuga.trackers.RelayResult
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerFixture
import com.edde746.tortuga.trackers.TrackerService.MYANIMELIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import com.edde746.tortuga.trackers.WatchedMark
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.movieMark
import com.edde746.tortuga.trackers.seriesMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The Trackers section over real accounts with fake services. */
class TrackersSettingsStoreTest {
    private var fixture: TrackerFixture? = null

    @AfterTest
    fun close() {
        fixture?.close()
    }

    private class Host : TrackersSettingsHost {
        val opened = mutableListOf<String>()

        override fun openUrl(url: String) {
            opened += url
        }
    }

    /**
     * A phone's sign-in browser: it comes back to the receiver with the state and a proof as soon as it opens, unless
     * the user never finishes there ([comesBack] false).
     */
    private class PhoneBrowser(private val comesBack: Boolean = true) : SignInPlatform by TestSignInPlatform(canOpenBrowser = true, canReceiveRedirect = true) {
        val opened = mutableListOf<String>()
        var closed = 0
        private val back = CompletableDeferred<Map<String, String>>()

        override fun openBrowser(url: String) {
            opened += url
            if (comesBack) back.complete(mapOf(SyncPaths.RETURN_STATE to "own", SyncPaths.RETURN_PROOF to "ttp_proof"))
        }

        override fun closeBrowser() {
            closed++
        }

        override fun openRedirectReceiver(): RedirectReceiver = object : RedirectReceiver {
            override val redirectUri = "http://127.0.0.1:53682/callback"
            override suspend fun awaitRedirect(state: String): Map<String, String> = back.await().takeIf { it[SyncPaths.RETURN_STATE] == state } ?: awaitCancellation()
            override fun close() {}
        }
    }

    private class Setup(val f: TrackerFixture, val store: TrackersSettingsStore, val host: Host) {
        val state: TrackersSettingsUiState get() = store.state.value

        fun row(index: Int = 0): TrackerServiceRow = state.services[index]
    }

    private fun TestScope.setup(f: TrackerFixture, signIn: SignInPlatform? = null, tv: Boolean = false): Setup {
        fixture = f
        val host = Host()
        val store = TrackersSettingsStore(f.profileId, f.hub.accounts, f.data.trackerWriteQueue, host, signIn, tv, { f.clock.now().toEpochMilliseconds() }, backgroundScope)
        f.settle()
        return Setup(f, store, host)
    }

    private fun grant(expiresInSeconds: Long = 600) = DeviceCodeGrant(
        deviceCode = "dc",
        userCode = "ABCD1234",
        verificationUrl = "https://trakt.tv/activate",
        verificationUrlComplete = "https://trakt.tv/activate/ABCD1234",
        expiresInSeconds = expiresInSeconds,
        intervalSeconds = 5,
    )

    @Test
    fun aDeviceCodeShowsWithItsCountdownUntilTheUserApprovesThenTheAccountIsConnected() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        val provider = s.f.providers.getValue(TRAKT)
        provider.deviceCode = FakeDeviceCode(this, listOf(DevicePoll.Pending, DevicePoll.Success(JsonObject(emptyMap()))), grant())
        assertEquals(listOf(TRAKT), s.state.services.map { it.service }, "services without a registration are left out")
        assertEquals(listOf("Trakt", "Not connected"), listOf(s.row().name, s.row().status))
        assertTrue(s.row().canConnect)
        assertNull(s.row().account)

        s.store.connect(TRAKT)
        assertIs<TrackerConnectStep.Starting>(s.state.connect?.step)
        s.f.settle()
        val connect = assertNotNull(s.state.connect)
        assertEquals("Connect Trakt", connect.title)
        val step = assertIs<TrackerConnectStep.Code>(connect.step)
        val code = step.code
        assertEquals(listOf("ABCD1234", "https://trakt.tv/activate/ABCD1234"), listOf(code.userCode, code.qrPayload))
        assertEquals("On a phone or computer, go to \u2068trakt.tv/activate\u2069 and enter this code:", code.instructions)
        assertEquals("Or scan the QR code with your phone.", code.scanHint)
        assertEquals("The code expires in 10:00.", code.expiresText)
        assertEquals("Waiting for you to allow Tortuga on Trakt…", step.message)
        assertFalse(s.row().canConnect, "one connect flow at a time")

        s.f.advance(1.seconds)
        assertEquals("The code expires in 9:59.", assertIs<TrackerConnectStep.Code>(s.state.connect?.step).code.expiresText)
        s.store.openInBrowser()
        assertEquals(listOf("https://trakt.tv/activate/ABCD1234"), s.host.opened, "the page with the code filled in")

        s.f.advance(10.seconds)
        assertNull(s.state.connect)
        assertEquals(listOf("Connected as \u2068alex\u2069", "alex"), listOf(s.row().status, s.row().username))
        assertTrue(s.row().connected)
        assertEquals("Connected to Trakt as \u2068alex\u2069.", s.state.notice)
        val account = assertNotNull(s.row().account)
        assertEquals(listOf(true, true, true, true), listOf(account.scrobble, account.movies, account.series, account.anime).map { it.checked })
        assertEquals(2, provider.deviceCode?.polls?.size)
    }

    @Test
    fun anExpiredCodeOffersANewOneAndCancellingStopsPolling() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        val provider = s.f.providers.getValue(TRAKT)
        provider.deviceCode = FakeDeviceCode(this, emptyList(), grant(expiresInSeconds = 10))
        s.store.connect(TRAKT)
        s.f.advance(11.seconds)
        val connect = assertNotNull(s.state.connect)
        assertEquals(TrackerConnectStep.Expired("The code expired before it was used."), connect.step)
        assertEquals("Close", connect.cancelLabel)

        s.store.retryConnect()
        s.f.settle()
        assertIs<TrackerConnectStep.Code>(s.state.connect?.step)
        val polls = provider.deviceCode!!.polls.size
        s.store.cancelConnect()
        assertNull(s.state.connect)
        s.f.advance(30.seconds)
        assertEquals(polls, provider.deviceCode!!.polls.size, "no poll after cancelling")
        assertTrue(s.row().canConnect)
    }

    @Test
    fun aDeclineAndAFailureSayWhatHappened() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        val provider = s.f.providers.getValue(TRAKT)
        provider.deviceCode = FakeDeviceCode(this, listOf(DevicePoll.Denied), grant())
        s.store.connect(TRAKT)
        s.f.advance(5.seconds)
        assertEquals(TrackerConnectStep.Declined("Trakt didn’t allow the connection."), s.state.connect?.step)

        provider.deviceCode = FakeDeviceCode(this, emptyList(), grant(), failRequest = TrackerException.Network(TRAKT, IllegalStateException("offline")))
        s.store.retryConnect()
        s.f.settle()
        assertEquals(TrackerConnectStep.Failed("Couldn’t reach Trakt. Check the connection and try again."), s.state.connect?.step)
        assertFalse(s.row().connected)
    }

    @Test
    fun aRelayServiceConnectsThroughItsSignInLink() = runTest {
        val relay = FakeRelay()
        // A TV: the link is for another device, through the QR code; nothing opens here.
        val s = setup(TrackerFixture(this, listOf(MYANIMELIST), relay), TestSignInPlatform(isTv = true, canOpenBrowser = true), tv = true)
        s.f.providers.getValue(MYANIMELIST).relaySignIn = FakeRelaySignIn
        s.store.connect(MYANIMELIST)
        s.f.settle()
        assertEquals(listOf<String?>(null), relay.returns)
        val step = assertIs<TrackerConnectStep.Link>(assertNotNull(s.state.connect).step)
        val link = step.link
        assertEquals("https://relay.example/v1/trackers/oauth/mal/s1", link.url)
        assertEquals(link.url, link.qrPayload)
        assertEquals("Sign in to MyAnimeList in your browser and allow Tortuga.", link.instructions)
        assertFalse(step.canOpenInBrowser)

        relay.result.complete(RelayResult(accessToken = "t", refreshToken = "r", expiresInSeconds = 3600))
        s.f.settle()
        assertNull(s.state.connect)
        assertEquals("Connected as \u2068kai\u2069", s.row().status)
    }

    @Test
    fun withoutASignInBrowserOfItsOwnOpenInBrowserOpensTheLinkThatAsksToConfirm() = runTest {
        val relay = FakeRelay()
        // A development session: no sign-in platform, the system's browser.
        val s = setup(TrackerFixture(this, listOf(MYANIMELIST), relay))
        s.f.providers.getValue(MYANIMELIST).relaySignIn = FakeRelaySignIn
        s.store.connect(MYANIMELIST)
        s.f.settle()
        assertEquals(listOf<String?>(null), relay.returns)
        val step = assertIs<TrackerConnectStep.Link>(assertNotNull(s.state.connect).step)
        assertTrue(step.canOpenInBrowser)
        s.store.openInBrowser()
        assertEquals(listOf(step.link.url), s.host.opened)
    }

    @Test
    fun aRelayLinkOpenedOnThisPhoneComesBackToItWithNothingToConfirm() = runTest {
        val relay = FakeRelay()
        val phone = PhoneBrowser()
        val s = setup(TrackerFixture(this, listOf(MYANIMELIST), relay), phone)
        s.f.providers.getValue(MYANIMELIST).relaySignIn = FakeRelaySignIn
        s.store.connect(MYANIMELIST)
        s.f.settle()
        assertEquals(listOf<String?>("http://127.0.0.1:53682/callback"), relay.returns)
        // The QR code stays the link for another device.
        assertEquals("https://relay.example/v1/trackers/oauth/mal/s1", assertIs<TrackerConnectStep.Link>(s.state.connect?.step).link.qrPayload)

        s.store.openInBrowser()
        assertEquals(listOf("https://relay.example/v1/trackers/oauth/mal/own"), phone.opened)
        assertTrue(s.host.opened.isEmpty())
        s.f.settle()
        assertEquals(listOf("ttp_proof"), relay.collected)
        assertNull(s.state.connect)
        assertEquals("Connected as \u2068kai\u2069", s.row().status)
        assertEquals(1, phone.closed)
    }

    @Test
    fun releasingTheStoreMidSignInClosesItsSignInBrowser() = runTest {
        val relay = FakeRelay()
        val phone = PhoneBrowser(comesBack = false)
        val s = setup(TrackerFixture(this, listOf(MYANIMELIST), relay), phone)
        s.f.providers.getValue(MYANIMELIST).relaySignIn = FakeRelaySignIn
        s.store.connect(MYANIMELIST)
        s.f.settle()
        s.store.openInBrowser()
        s.f.settle()
        assertEquals(listOf("https://relay.example/v1/trackers/oauth/mal/own"), phone.opened)
        assertEquals(0, phone.closed, "the user is signing in there")

        // The app's session closes meanwhile.
        s.store.release()
        assertEquals(1, phone.closed)
    }

    @Test
    fun disconnectingAsksFirstThenDropsTheConnectionOnTheAccountAndHere() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        s.f.connect(TRAKT)
        assertEquals("Connected as \u2068alex\u2069", s.row().status)

        s.store.disconnect(TRAKT)
        val dialog = assertNotNull(s.state.dialog)
        assertEquals(listOf("Disconnect Trakt?", "Disconnect"), listOf(dialog.title, dialog.confirmLabel))
        assertTrue(dialog.destructive)
        s.store.dismissDialog()
        s.f.settle()
        assertNull(s.state.dialog)
        assertTrue(s.row().connected)

        s.store.disconnect(TRAKT)
        s.store.confirmDialog()
        s.f.settle()
        assertFalse(s.row().connected)
        assertEquals("Not connected", s.row().status)
        assertEquals("Disconnected from Trakt.", s.state.notice)
        assertEquals(listOf("a0"), s.f.server.revoked)
        assertTrue(s.f.server.connections.isEmpty())
        assertNull(s.f.tokens.load(s.f.profileId, TRAKT))
    }

    @Test
    fun withoutAnAccountOrItsServerConnectAndDisconnectSayWhyAndChangeNothing() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        s.f.connect(TRAKT)
        s.f.server.down = true
        s.store.disconnect(TRAKT)
        s.store.confirmDialog()
        s.f.settle()
        assertEquals("Couldn’t reach your Tortuga server. Check the connection and try again.", s.state.notice)
        assertTrue(s.row().connected)

        s.f.server.down = false
        s.f.hub.accounts.disconnect(s.f.profileId, TRAKT)
        s.f.settle()
        s.f.server.signedIn = false
        s.store.connect(TRAKT)
        s.f.settle()
        assertNull(s.state.connect, "no sign-in starts")
        assertEquals("Connecting Trakt needs a Tortuga account. Sign in to your account first.", s.state.notice)
    }

    @Test
    fun anAccountsSwitchesAndQueuedWritesFollowWhatIsStored() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        s.f.connect(TRAKT)
        // Switches flipped faster than the rows follow the stored connection (D-pad, keyboard) each keep theirs.
        s.store.setMovies(TRAKT, false)
        s.store.setAnime(TRAKT, false)
        s.store.setScrobbling(TRAKT, false)
        s.store.setAnime(TRAKT, true)
        s.f.settle()
        val stored = assertNotNull(s.f.data.trackerAccounts.account(s.f.profileId, TRAKT))
        assertEquals(listOf(false, false, true, true), listOf(stored.enabled, stored.syncMovies, stored.syncSeries, stored.syncAnime))
        var account = assertNotNull(s.row().account)
        assertEquals(listOf(false, false, true, true), listOf(account.scrobble, account.movies, account.series, account.anime).map { it.checked })
        assertNull(account.queued)

        for (key in listOf("trakt|movie|imdb=tt1", "trakt|movie|imdb=tt2")) {
            s.f.data.trackerWriteQueue.enqueue(s.f.profileId, TRAKT, stored.connectedAt, key, movieMark(), null, 1)
        }
        s.f.settle()
        account = assertNotNull(s.row().account)
        assertEquals(2, account.queuedCount)
        assertEquals("2 changes waiting to be sent", account.queued)
    }

    @Test
    fun writesGivenUpOnShowWithWhyTheLatestWas() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        s.f.connect(TRAKT)
        fun replay() {
            s.f.coordinator.replayQueuedWrites()
            s.f.advance(30.seconds)
        }
        // Trakt refuses a mark (422) when it's made and at each of its retries.
        s.f.tracker(TRAKT).failNext("watched", *Array(1 + TrackerWriteQueue.MAX_ATTEMPTS) { TrackerException.Api(TRAKT, 422) })
        s.f.coordinator.marks.onMarked(WatchedMark.Video(seriesMeta("tt0903747", 3), "tt0903747:1:1", watched = true))
        s.f.drain()
        var account = assertNotNull(s.row().account)
        assertEquals("1 change waiting to be sent", account.queued)
        assertNull(account.failed)
        repeat(TrackerWriteQueue.MAX_ATTEMPTS) { replay() }
        account = assertNotNull(s.row().account)
        assertNull(account.queued)
        assertEquals(1, account.failedCount)
        assertEquals("1 change couldn’t be sent (last: Trakt answered error 422)", account.failed)

        // A queued write that no longer reads (what writes are built from changed shape): given up on at the next replay.
        s.f.data.db.database.trackerQueries.insertQueued(
            s.f.profileId, TRAKT.id, "trakt|movie|imdb=tt0111161", true, """{"metaId":"tt0111161","videoId":"tt0111161","kind":"FILM","contentType":"MOVIE"}""", null, 1, null, false,
        )
        replay()
        account = assertNotNull(s.row().account)
        assertNull(account.queued)
        assertEquals(2, account.failedCount)
        assertEquals("2 changes couldn’t be sent (last: it couldn’t be read)", account.failed)
    }

    @Test
    fun aConnectionThisBuildCanNoLongerMakeCanStillBeDisconnected() = runTest {
        val s = setup(TrackerFixture(this, listOf(TRAKT)))
        // Another device of the account, whose build has Simkl, connected it.
        s.f.server.api.connectTracker(
            FakeTrackerAccountServer.URL,
            "session",
            TrackerConnectRequest(s.f.profileId, SIMKL.id, "client-simkl", "s0", "r0", username = "sam"),
        )
        s.f.hub.refreshConnections()
        s.f.settle()
        assertEquals(listOf(TRAKT, SIMKL), s.state.services.map { it.service })
        val simkl = s.row(1)
        assertEquals("Not available in this version of the app", simkl.status)
        assertFalse(simkl.available)
        assertFalse(simkl.canConnect)
        assertTrue(simkl.connected)
        s.store.connect(SIMKL)
        assertNull(s.state.connect, "it can't connect again")

        s.store.disconnect(SIMKL)
        s.store.confirmDialog()
        s.f.settle()
        assertEquals(listOf(TRAKT), s.state.services.map { it.service }, "the Simkl row is gone")
        assertEquals("Disconnected from Simkl.", s.state.notice)
        assertEquals(listOf("s0"), s.f.server.revoked)
        assertTrue(s.f.server.connections.isEmpty())
    }
}
