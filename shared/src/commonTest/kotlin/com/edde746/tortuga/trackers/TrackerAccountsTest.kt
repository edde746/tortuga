package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.settings.TrackerSwitches
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.trackers.TrackerService.ANILIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Connections held by the account server: sign-in hands the tokens over, each device keeps the
 * access token until it expires and asks the server for the next, and the server's list and its "disconnected"
 * answer are what this device's connections follow.
 */
class TrackerAccountsTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    private fun TestScope.fixture(server: FakeTrackerAccountServer? = null) = TrackerFixture(this, server = server).also { fixtures += it }

    private suspend fun TrackerFixture.stored(service: TrackerService) = tokens.load(profileId, service)

    private suspend fun TrackerFixture.queueWrite(service: TrackerService) {
        val binding = data.trackerAccounts.account(profileId, service)!!.connectedAt
        data.trackerWriteQueue.enqueue(profileId, service, binding, "${service.id}|movie|imdb=tt1", movieMark(), null, 1)
    }

    /** Every secret this device holds, decoded: no refresh token may be among them. */
    private fun TrackerFixture.secretText() = secrets.entries.values.joinToString("\n") { it.decodeToString() }

    @Test
    fun theHeldTokenServesUntilItNearlyExpiresThenTheServerIsAskedOnce() = runTest {
        val f = fixture()
        val expiresAt = f.clock.now().epochSeconds + 3600
        f.connect(TRAKT, TrackerSession("a0", "r0", expiresAt = expiresAt, username = "alex", createdAt = 1))
        val credentials = f.tracker(TRAKT).credentials
        assertEquals("a0", credentials.accessToken())
        f.advance(3000.seconds)
        assertEquals("a0", credentials.accessToken(), "ten minutes left: still the held token")
        assertEquals(listOf("connect trakt", "list"), f.server.calls)

        f.advance(400.seconds)
        val refreshed = credentials.accessToken()
        assertEquals("trakt-access-1", refreshed, "within the window: the server refreshed and handed the new token")
        assertEquals(refreshed, credentials.accessToken())
        assertEquals(listOf("connect trakt", "list", "token trakt"), f.server.calls)
        assertEquals(1, f.server.refreshes)
        assertEquals(refreshed, f.stored(TRAKT)?.accessToken, "kept before it was handed out")

        // The next launch takes the kept token without the server.
        f.server.down = true
        val next = f.newHub()
        val relaunched = next.accounts.credentials(f.profileId, TRAKT)!!
        assertEquals(refreshed, relaunched.accessToken())
    }

    @Test
    fun concurrentRejectedRequestsAskTheServerOnce() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.server.refreshDelay = 2.seconds
        val credentials = f.tracker(TRAKT).credentials
        val first = async { credentials.refreshAfterUnauthorized("a0") }
        val second = async { credentials.refreshAfterUnauthorized("a0") }
        f.advance(3.seconds)
        assertEquals("trakt-access-1", first.await())
        assertEquals("trakt-access-1", second.await(), "the second caller waited for the first answer instead of asking again")
        assertEquals(listOf("connect trakt", "list", "token trakt rejected"), f.server.calls)
        assertEquals(1, f.server.refreshes)

        // A request that was rejected with the old token later gets the new one without asking.
        assertEquals("trakt-access-1", credentials.refreshAfterUnauthorized("a0"))
        assertEquals("trakt-access-1", credentials.accessToken())
        assertEquals(1, f.server.calls.count { it.startsWith("token") })
    }

    @Test
    fun theServersDisconnectedAnswerDropsTheConnectionHereAndListsAgain() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.queueWrite(TRAKT)
        f.queueWrite(SIMKL)
        f.server.refuseRefresh = true
        val credentials = f.tracker(TRAKT).credentials
        val refused = assertFailsWith<TrackerException.Auth> { credentials.refreshAfterUnauthorized("a0") }
        assertTrue(refused.permanent)
        f.settle()
        assertEquals(listOf(SIMKL), f.hub.accounts.accounts(f.profileId).map { it.service })
        assertNull(f.stored(TRAKT))
        assertEquals(listOf("simkl simkl|movie|imdb=tt1 watched=true attempts=0"), f.queued())
        assertTrue(assertFailsWith<TrackerException.Auth> { credentials.accessToken() }.permanent)
        assertEquals("list", f.server.calls.last(), "the account's connections listed again")
    }

    @Test
    fun aConnectionGoneFromTheAccountIsDroppedWhenItsTokenIsNeeded() = runTest {
        val f = fixture()
        f.connect(SIMKL)
        // Disconnected from another device; this one finds out when it next needs a token.
        f.server.drop(f.profileId, SIMKL)
        f.secrets.entries.clear()
        assertTrue(assertFailsWith<TrackerException.Auth> { f.tracker(SIMKL).credentials.accessToken() }.permanent)
        f.settle()
        assertEquals(emptyList(), f.hub.accounts.accounts(f.profileId))
    }

    @Test
    fun aDeadSessionOfAnOldSignInDoesNotDropTheNewOne() = runTest {
        val f = fixture()
        f.connect(ANILIST)
        val old = f.tracker(ANILIST).credentials
        f.connect(ANILIST, TrackerSession("b0", null, null, "sam", 2))
        old.invalidate("a0")
        f.settle()
        assertEquals("sam", f.hub.accounts.accounts(f.profileId).single().username)
        assertEquals("b0", f.stored(ANILIST)?.accessToken)
        assertEquals("b0", f.tracker(ANILIST).credentials.accessToken())

        // AniList has no refresh: a 401 on the current sign-in ends it, on the account and here.
        assertFailsWith<TrackerException.Auth> { f.tracker(ANILIST).credentials.invalidate("b0") }
        f.settle()
        assertEquals(emptyList(), f.hub.accounts.accounts(f.profileId))
        assertEquals(emptyMap(), f.server.connections)
        assertNull(f.stored(ANILIST))
    }

    @Test
    fun aSignInHandsTheTokensToTheServerAndKeepsNoRefreshTokenHere() = runTest {
        val f = fixture()
        val grant = DeviceCodeGrant("dc", "ABCD-1234", "https://trakt.tv/activate", "https://trakt.tv/activate/ABCD-1234", expiresInSeconds = 600, intervalSeconds = 5)
        val signIn = FakeDeviceCode(this, listOf(DevicePoll.Pending, DevicePoll.SlowDown, DevicePoll.Pending, DevicePoll.Success(kotlinx.serialization.json.JsonObject(emptyMap()))), grant)
        f.providers.getValue(TRAKT).deviceCode = signIn
        val prompts = mutableListOf<SignInPrompt>()
        val account = async { f.hub.accounts.signIn(f.profileId, TRAKT) { prompts += it } }
        f.advance(60.seconds)
        assertEquals("alex", assertNotNull(account.await()).username)
        assertEquals(listOf<SignInPrompt>(SignInPrompt.DeviceCode(grant)), prompts)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), signIn.polls, "slow_down adds 5 s to the interval")

        val held = f.server.connections.getValue(f.profileId to TRAKT.id)
        assertEquals(listOf("granted", "r0", "client-trakt", "alex"), held.request.let { listOf(it.accessToken, it.refreshToken, it.clientId, it.username) })
        assertEquals(held.connectedAtMillis, f.hub.accounts.accounts(f.profileId).single().connectedAt)
        assertEquals("granted", f.stored(TRAKT)?.accessToken)
        assertFalse("r0" in f.secretText(), "the refresh token stays on the server")
    }

    @Test
    fun withoutAnAccountNothingConnects() = runTest {
        val f = fixture()
        f.server.signedIn = false
        f.providers.getValue(TRAKT).deviceCode = FakeDeviceCode(this, emptyList(), DeviceCodeGrant("dc", "ABCD", "https://trakt.tv/activate", expiresInSeconds = 600, intervalSeconds = 5))
        assertFalse(f.hub.accounts.canConnect)
        val error = assertFailsWith<TrackerException.AccountServer> { f.hub.accounts.signIn(f.profileId, TRAKT) {} }
        assertEquals(AccountError.NotSignedIn, error.error)
        assertEquals(emptyList(), f.server.calls, "no code was asked for")
    }

    @Test
    fun disconnectingGoesThroughTheServerThenDropsEverythingHere() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.queueWrite(TRAKT)
        f.hub.accounts.setEnabled(f.profileId, TRAKT, false)
        f.hub.accounts.setContentFilter(f.profileId, TRAKT, TrackerContentType.ANIME, on = false)
        val off = TrackerSwitches.DEFAULT.copy(sending = false, anime = false)

        f.server.down = true
        assertFailsWith<TrackerException.AccountServer> { f.hub.accounts.disconnect(f.profileId, TRAKT) }
        assertEquals(listOf(TRAKT), f.hub.accounts.accounts(f.profileId).map { it.service }, "nothing changed while unreachable")
        assertEquals(off, f.hub.accounts.observeSwitches(f.profileId).first().getValue(TRAKT))

        f.server.down = false
        f.hub.accounts.disconnect(f.profileId, TRAKT)
        assertEquals(listOf("a0"), f.server.revoked)
        assertEquals(emptyList(), f.hub.accounts.accounts(f.profileId))
        assertEquals(emptyList(), f.queued())
        assertNull(f.stored(TRAKT))
        assertTrue(f.secrets.entries.isEmpty(), "no token left in the secret store")
        assertEquals(TrackerSwitches.DEFAULT, f.hub.accounts.observeSwitches(f.profileId).first().getValue(TRAKT), "a later connection starts with every switch on")
    }

    @Test
    fun anotherDeviceOfTheAccountSeesTheConnectionUsesItAndItsDisconnectReachesTheFirst() = runTest {
        val phone = fixture()
        val tv = fixture(server = phone.server)
        phone.connect(TRAKT)

        tv.hub.accounts.syncConnections()
        assertEquals(listOf("alex"), tv.hub.accounts.accounts(tv.profileId).map { it.username })
        assertEquals("a0", tv.hub.accounts.credentials(tv.profileId, TRAKT)!!.accessToken(), "the token from the server, no sign-in")
        assertEquals("a0", tv.stored(TRAKT)?.accessToken)

        tv.hub.accounts.disconnect(tv.profileId, TRAKT)
        // The phone's next pull reports the account's connections counter, which moved.
        phone.hub.onTrackersVersion(phone.server.version)
        phone.settle()
        assertEquals(emptyList(), phone.hub.accounts.accounts(phone.profileId))
        assertNull(phone.stored(TRAKT), "the gone connection's token cleared")
    }

    @Test
    fun theConnectionsAreListedOnlyWhenTheyCanHaveChanged() = runTest {
        val phone = fixture()
        val tv = fixture(server = phone.server)
        fun listings() = phone.server.calls.count { it == "list" }

        phone.connect(TRAKT)
        assertEquals(1, listings(), "a connect here lists")

        phone.hub.onTrackersVersion(phone.server.version)
        phone.hub.onForeground()
        phone.settle()
        assertEquals(1, listings(), "a pull with the counter the list reflects, and a foreground soon after: none")

        tv.connect(SIMKL)
        val afterTv = listings()
        phone.hub.onTrackersVersion(phone.server.version)
        phone.settle()
        assertEquals(afterTv + 1, listings(), "a pull whose counter moved lists")
        assertEquals(setOf(TRAKT, SIMKL), phone.hub.accounts.accounts(phone.profileId).map { it.service }.toSet())

        phone.advance(29.minutes)
        phone.hub.onForeground()
        phone.settle()
        assertEquals(afterTv + 1, listings())
        phone.advance(2.minutes)
        phone.hub.onForeground()
        phone.settle()
        assertEquals(afterTv + 2, listings(), "a foreground over 30 minutes after the last listing lists")

        phone.hub.accounts.disconnect(phone.profileId, SIMKL)
        phone.settle()
        assertEquals(afterTv + 3, listings(), "a disconnect here lists")
    }

    @Test
    fun aListingLeavesTheSwitchesAndANewSignInElsewhereStartsANewBinding() = runTest {
        val phone = fixture()
        val tv = fixture(server = phone.server)
        phone.connect(TRAKT)
        tv.hub.accounts.syncConnections()
        tv.hub.accounts.setEnabled(tv.profileId, TRAKT, false)
        tv.queueWrite(TRAKT)
        val before = tv.hub.accounts.accounts(tv.profileId).single()

        tv.hub.accounts.syncConnections()
        assertEquals(before, tv.hub.accounts.accounts(tv.profileId).single(), "the same sign-in: nothing changes")
        assertEquals(1, tv.queued().size)

        phone.connect(TRAKT, TrackerSession("b0", "r1", null, "sam", 2))
        tv.hub.accounts.syncConnections()
        val after = tv.hub.accounts.accounts(tv.profileId).single()
        assertEquals("sam", after.username)
        assertTrue(after.connectedAt > before.connectedAt)
        assertFalse(tv.hub.accounts.observeSwitches(tv.profileId).first().getValue(TRAKT).sending, "the profile's switch stays")
        assertEquals(emptyList(), tv.queued(), "writes queued for the previous sign-in dropped")
        assertEquals("b0", tv.hub.accounts.credentials(tv.profileId, TRAKT)!!.accessToken())
    }

    @Test
    fun writesMadeWhileTheServerIsUnreachableQueue() = runTest {
        val f = fixture()
        f.connect(TRAKT, TrackerSession("a0", "r0", expiresAt = f.clock.now().epochSeconds + 60, username = "alex", createdAt = 1))
        f.advance(120.seconds)
        f.server.down = true
        f.coordinator.marks.onMarked(WatchedMark.Video(seriesMeta("tt0903747", 3), "tt0903747:1:1", true))
        f.drain()
        assertEquals(listOf("trakt trakt|s1e1|imdb=tt0903747 watched=true attempts=0"), f.queued(), "an expired token and no server: queued")
        assertEquals(emptyList(), f.calls(TRAKT))

        f.server.down = false
        f.hub.onNetworkRestored()
        f.advance(30.seconds)
        assertEquals(listOf("watched tt0903747:1:1"), f.undated(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aDeletedProfileTakesItsTokensWithIt() = runTest {
        val f = fixture()
        val kept = f.data.profiles.create("Kept", null)
        val gone = f.data.profiles.create("Gone", null)
        for (profile in listOf(kept, gone)) f.hub.accounts.connect(profile.id, TRAKT, TrackerSession("a0", "r0", null, "alex", 1))
        f.settle()
        // Deleted here or by sync: not through the tracker accounts.
        f.data.profiles.delete(gone.id)
        f.settle()
        assertNull(f.tokens.load(gone.id, TRAKT))
        assertEquals("a0", f.tokens.load(kept.id, TRAKT)?.accessToken)
        assertEquals(setOf(kept.id to TRAKT), f.tokens.stored())
    }

    @Test
    fun aDeviceCodeSignInEndsWithoutAConnectionWhenDeclinedOrExpired() = runTest {
        val f = fixture()
        val grant = DeviceCodeGrant("dc", "ABCD", "https://simkl.com/pin", expiresInSeconds = 20, intervalSeconds = 5)
        val declined = FakeDeviceCode(this, listOf(DevicePoll.Pending, DevicePoll.Denied), grant)
        f.providers.getValue(SIMKL).deviceCode = declined
        val first = async { f.hub.accounts.signIn(f.profileId, SIMKL) {} }
        f.advance(60.seconds)
        assertNull(first.await())
        assertEquals(2, declined.polls.size)

        val unanswered = FakeDeviceCode(this, emptyList(), grant)
        f.providers.getValue(SIMKL).deviceCode = unanswered
        val second = async { f.hub.accounts.signIn(f.profileId, SIMKL) {} }
        f.advance(60.seconds)
        assertNull(second.await())
        assertEquals(4, unanswered.polls.size, "polled until the code's 20 s were up")
        assertEquals(emptyList(), f.hub.accounts.accounts(f.profileId))
        assertEquals(emptyList(), f.server.calls)
        assertTrue(f.secrets.entries.isEmpty())
    }

    @Test
    fun aServiceWithoutAProviderIsNotOffered() = runTest {
        val f = TrackerFixture(this, services = listOf(TRAKT, SIMKL)).also { fixtures += it }
        assertEquals(listOf(TRAKT, SIMKL), f.hub.accounts.services)
        assertFailsWith<TrackerException.SignIn> { f.hub.accounts.signIn(f.profileId, ANILIST) {} }
    }
}
