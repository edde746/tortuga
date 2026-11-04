@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync.account

import com.edde746.tortuga.data.movie
import com.edde746.tortuga.sync.FailingSecretStore
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.api.DeviceInfo
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Account lifecycle against the in-memory server: sign-in, device links, sessions, sign-out, local data. */
class SyncAccountTest {
    private val server = FakeSyncServer()

    private fun TestScope.device(
        name: String,
        secrets: com.edde746.tortuga.secrets.MemorySecretStore = com.edde746.tortuga.secrets.MemorySecretStore(),
        clockSkewMillis: Long = 0,
    ) = TestDevice(name, server, this, backgroundScope, secrets, clockSkewMillis = clockSkewMillis)

    private suspend fun TestDevice.signUp(subject: String = "alice") {
        account.restore()
        assertEquals(SignInResult.NEW_ACCOUNT, account.signIn(server.serverUrl) { TestDevice.credential(subject) })
    }

    private suspend fun TestDevice.signInExisting(subject: String = "alice"): SignInResult {
        account.restore()
        return account.signIn(server.serverUrl) { TestDevice.credential(subject) }
    }

    /** A profile with a library item, pushed to the server. */
    private suspend fun TestDevice.syncedProfile(): String {
        val profile = data.profiles.create("Wednesday", null)
        data.library.addToLibrary(profile.id, movie())
        engine.syncNow()
        assertTrue(data.sync.dirtyRecords(null, 10).isEmpty())
        return profile.id
    }

    @Test
    fun signUpSignsInAndKeepsOnlyTheSessionInTheSecretStore() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val signedIn = assertIs<AccountState.SignedIn>(phone.account.state.value)
            assertEquals(server.account().id, signedIn.accountId)
            assertEquals(server.serverUrl, signedIn.serverUrl)
            assertEquals(server.sessions.single().deviceId, signedIn.deviceId)
            assertEquals(setOf("account.session"), phone.secrets.entries.keys)
        }
    }

    @Test
    fun aRestartRestoresTheSignedInAccountFromTheSecretStore() = runTest {
        val secrets = com.edde746.tortuga.secrets.MemorySecretStore()
        device("Phone", secrets).use { it.signUp() }
        device("Phone again", secrets).use { phone ->
            assertIs<AccountState.SignedOut>(phone.account.state.value)
            phone.account.restore()
            assertEquals(server.account().id, assertIs<AccountState.SignedIn>(phone.account.state.value).accountId)
        }
    }

    @Test
    fun anExistingAccountOnANewDeviceSignsInAndGetsTheAccountsData() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val profileId = phone.syncedProfile()
            device("Tablet").use { tablet ->
                assertEquals(SignInResult.EXISTING_ACCOUNT, tablet.signInExisting())
                assertEquals(server.account().id, assertIs<AccountState.SignedIn>(tablet.account.state.value).accountId)
                tablet.engine.syncNow()
                assertEquals(listOf("Wednesday"), tablet.data.profiles.profiles().map { it.name })
                assertNotNull(tablet.data.library.item(profileId, movie().id))
            }
        }
    }

    @Test
    fun aDeviceLinkGivesASignedOutTvItsOwnSessionAndTheAccountsData() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val profileId = phone.syncedProfile()
            device("Living room TV").use { tv ->
                tv.account.restore()
                val pending = tv.account.startDeviceLink(server.serverUrl)
                assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(pending.code))
                assertEquals(DeviceLinkQr(server.serverUrl, pending.code.replace("-", "")), DeviceLinkQr.parse(pending.qrPayload))
                val result = async { pending.awaitResult() }

                val incoming = phone.account.claimDeviceLink(pending.code.lowercase().replace('-', ' '))
                assertEquals("Living room TV", incoming.deviceName)
                incoming.approve()

                assertEquals(DeviceLinkOutcome.LINKED, result.await())
                val signedIn = assertIs<AccountState.SignedIn>(tv.account.state.value)
                assertEquals(server.account().id, signedIn.accountId)
                assertEquals(2, server.sessions.size)
                assertNotEquals(phone.account.devices().single { it.current }.deviceId, signedIn.deviceId)
                tv.engine.syncNow()
                assertEquals(listOf("Wednesday"), tv.data.profiles.profiles().map { it.name })
                assertNotNull(tv.data.library.item(profileId, movie().id))
            }
        }
    }

    @Test
    fun aSignedInDeviceCannotStartADeviceLink() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            assertFailsWith<IllegalStateException> { phone.account.startDeviceLink(server.serverUrl) }
            assertTrue(server.links.isEmpty())
        }
    }

    @Test
    fun aDeclinedOrUnansweredLinkLeavesTheDeviceSignedOut() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            device("TV").use { tv ->
                val declined = tv.account.startDeviceLink(server.serverUrl)
                val declinedResult = async { declined.awaitResult() }
                phone.account.claimDeviceLink(declined.code).reject()
                assertEquals(DeviceLinkOutcome.REJECTED, declinedResult.await())

                val unanswered = tv.account.startDeviceLink(server.serverUrl)
                val unansweredResult = async { unanswered.awaitResult() }
                advanceTimeBy(11 * 60_000L)
                assertEquals(DeviceLinkOutcome.EXPIRED, unansweredResult.await())
                val gone = assertFailsWith<AccountException> { phone.account.claimDeviceLink(unanswered.code) }
                assertEquals(AccountError.LinkNotFound, gone.error)
                assertEquals(AccountState.SignedOut(null), tv.account.state.value)
                assertEquals(1, server.sessions.size)
            }
        }
    }

    @Test
    fun aLinkPollWaitsLongerWhileTheServerIsUnreachableAndStillLinks() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            device("TV").use { tv ->
                tv.account.restore()
                val pending = tv.account.startDeviceLink(server.serverUrl)
                server.down = true
                server.requests.clear()
                val result = async { pending.awaitResult() }
                advanceTimeBy(5 * 60_000L)
                // Every 2 s that would be 150 polls; failures in a row wait longer, up to 30 s.
                val polls = server.requests.size
                assertTrue(polls in 10..75, "$polls polls in 5 minutes")

                server.down = false
                phone.account.claimDeviceLink(pending.code).approve()
                assertEquals(DeviceLinkOutcome.LINKED, result.await())
                assertIs<AccountState.SignedIn>(tv.account.state.value)
            }
        }
    }

    @Test
    fun signOutDropsTheSessionAndTheSyncedData() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            phone.syncedProfile()
            phone.account.signOut()
            assertEquals(AccountState.SignedOut(null), phone.account.state.value)
            assertTrue(phone.secrets.entries.isEmpty())
            assertTrue(server.sessions.isEmpty())
            assertTrue(phone.data.profiles.profiles().isEmpty())
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
            assertNull(phone.data.syncState.dataAccount())
        }
    }

    @Test
    fun accountDeletionSignsOutThisDeviceAndEndsTheOthersSessions() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            device("Tablet").use { tablet ->
                tablet.signInExisting()
                val deletion = phone.account.deleteAccount()
                assertEquals(false, deletion.appleRevocationNeeded)
                assertEquals(AccountState.SignedOut(SignedOutReason.ACCOUNT_DELETED), phone.account.state.value)
                assertTrue(server.accounts.isEmpty())

                val ended = assertFailsWith<AccountException> { tablet.account.accountInfo() }
                assertEquals(AccountError.SessionEnded, ended.error)
                assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_ENDED), tablet.account.state.value)
            }
        }
    }

    @Test
    fun expiredAccessTokensAreRefreshedOnceForConcurrentCalls() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val before = phone.secrets.entries.getValue("account.session").decodeToString()
            server.expireAccessTokens()
            val results = List(4) { async { phone.account.devices() } }.awaitAll()
            assertTrue(results.all { it.size == 1 })
            assertEquals(1, server.refreshes)
            // The rotated tokens are what the secret store keeps.
            assertNotEquals(before, phone.secrets.entries.getValue("account.session").decodeToString())
        }
    }

    @Test
    fun aCallRefusedForTheTokenARefreshJustReplacedRunsAgainWithTheNewOne() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            server.expireAccessTokens()
            var overlapping: Deferred<List<DeviceInfo>>? = null
            server.afterNextRefresh = {
                // A call that left with the old token reaches the server after it rotated, before the refresh's answer.
                overlapping = async { phone.account.devices() }
                delay(1_000)
            }
            assertEquals(1, phone.account.devices().size)
            assertEquals(1, assertNotNull(overlapping).await().size)
            assertIs<AccountState.SignedIn>(phone.account.state.value)
        }
    }

    @Test
    fun aSignOutDuringARefreshLeavesNoSessionBehind() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            server.expireAccessTokens()
            var signOut: Deferred<Unit>? = null
            server.afterNextRefresh = {
                signOut = async { phone.account.signOut() }
                delay(1_000)
            }
            runCatching { phone.account.devices() }
            assertNotNull(signOut).await()
            assertEquals(AccountState.SignedOut(null), phone.account.state.value)
            assertTrue(phone.secrets.entries.isEmpty())
            assertTrue(server.sessions.isEmpty(), "the server's session ended with the refreshed token")
        }
    }

    @Test
    fun theAccessTokenLastsTheServersHourHoweverFarTheClockIsOff() = runTest {
        for ((subject, skew) in listOf("alice" to 2 * 3_600_000L, "bob" to -3 * 3_600_000L)) {
            val refreshes = server.refreshes
            device("Phone", clockSkewMillis = skew).use { phone ->
                phone.signUp(subject)
                // Not refreshed before every call (a clock ahead), nor left to expire (a clock behind): refreshed half
                // a minute before the server's hour is up.
                advanceTimeBy(FakeSyncServer.ACCESS_TTL_MILLIS - 31_000)
                phone.account.devices()
                assertEquals(refreshes, server.refreshes)
                advanceTimeBy(1_000)
                phone.account.devices()
                assertEquals(refreshes + 1, server.refreshes)
            }
        }
    }

    @Test
    fun aSessionThatEndedKeepsLocalDataForTheSameAccountsNextSignIn() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val profile = phone.data.profiles.create("Wednesday", null)
            server.sessions.clear()
            assertEquals(AccountError.SessionEnded, assertFailsWith<AccountException> { phone.account.devices() }.error)
            assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_ENDED), phone.account.state.value)
            assertTrue("account.session" !in phone.secrets.entries)
            assertEquals(listOf(profile), phone.data.profiles.profiles())

            assertEquals(SignInResult.EXISTING_ACCOUNT, phone.signInExisting())
            assertEquals(listOf(profile), phone.data.profiles.profiles())
            // The change made before the session ended is still to be pushed.
            assertEquals(listOf("profile/${profile.id}"), phone.data.sync.dirtyRecords(null, 10).map { it.recordId })
        }
    }

    @Test
    fun aSessionTheServerEndedSignsOutAlsoWhenTheSecretStoreCantDeleteIt() = runTest {
        val store = FailingSecretStore()
        TestDevice("Phone", server, this, backgroundScope, store.memory, store = store).use { phone ->
            phone.signUp()
            store.failDeletes = true
            server.sessions.clear()
            assertEquals(AccountError.SessionEnded, assertFailsWith<AccountException> { phone.account.devices() }.error)
            assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_ENDED), phone.account.state.value)
        }
        // The entry the store kept: the next start loads it, and its first request signs out again.
        TestDevice("Phone", server, this, backgroundScope, store.memory, store = store).use { phone ->
            phone.account.restore()
            assertIs<AccountState.SignedIn>(phone.account.state.value)
            assertFailsWith<AccountException> { phone.account.devices() }
            assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_ENDED), phone.account.state.value)
        }
    }

    @Test
    fun aSecretStoreThatCantKeepTheSessionFailsTheSignInAsAnAccountError() = runTest {
        val store = FailingSecretStore()
        TestDevice("Phone", server, this, backgroundScope, store.memory, store = store).use { phone ->
            phone.account.restore()
            store.failWrites = true
            val failure = assertFailsWith<AccountException> { phone.account.signIn(server.serverUrl) { TestDevice.credential() } }
            assertEquals(AccountError.Unexpected(0, SessionVault.SECRET_STORE_FAILED), failure.error)
            assertIs<AccountState.SignedOut>(phone.account.state.value)
        }
    }

    @Test
    fun aSecretStoreThatFailsToReadAtStartKeepsTheSessionAndSaysSo() = runTest {
        val store = FailingSecretStore()
        TestDevice("Phone", server, this, backgroundScope, store.memory, store = store).use { it.signUp() }
        val deviceId = server.sessions.single().deviceId

        store.failReads = true
        TestDevice("Phone again", server, this, backgroundScope, store.memory, store = store).use { phone ->
            phone.account.restore()
            assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_UNREADABLE), phone.account.state.value)
        }
        assertEquals(setOf("account.session"), store.memory.entries.keys)

        // The next start reads it: the same device, still signed in.
        store.failReads = false
        TestDevice("Phone, third start", server, this, backgroundScope, store.memory, store = store).use { phone ->
            phone.account.restore()
            assertEquals(deviceId, assertIs<AccountState.SignedIn>(phone.account.state.value).deviceId)
            assertEquals(1, phone.account.devices().size)
        }
    }

    @Test
    fun signingInToAnotherAccountAsksBeforeDroppingChangesThatNeverSynced() = runTest {
        device("Phone").use { phone ->
            phone.signUp("alice")
            phone.data.profiles.create("Alice", null)
            server.sessions.clear()
            assertFailsWith<AccountException> { phone.account.devices() }

            // Declined: nothing changes here, and Bob's new session ends.
            var asked: Long? = null
            val declined = assertFailsWith<AccountException> {
                phone.account.signIn(server.serverUrl, discardUnsynced = { asked = it; false }) { TestDevice.credential("bob") }
            }
            assertEquals(AccountError.UnsyncedChanges(1), declined.error)
            assertEquals(1L, asked)
            assertEquals(listOf("Alice"), phone.data.profiles.profiles().map { it.name })
            assertEquals(server.accounts.values.single { it.subject == "alice" }.id, phone.data.syncState.dataAccount())
            assertIs<AccountState.SignedOut>(phone.account.state.value)
            assertTrue(server.sessions.isEmpty())

            // Confirmed: the previous account's data goes.
            assertEquals(SignInResult.EXISTING_ACCOUNT, phone.account.signIn(server.serverUrl, discardUnsynced = { true }) { TestDevice.credential("bob") })
            assertTrue(phone.data.profiles.profiles().isEmpty())
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
            assertEquals(server.accounts.values.single { it.subject == "bob" }.id, phone.data.syncState.dataAccount())
        }
    }

    @Test
    fun signingOutRefusesWhileChangesHaventSyncedAndKeepsEverything() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val profile = phone.syncedProfile()
            server.down = true
            phone.data.library.addToLibrary(profile, movie("tt0000002"))

            val refused = assertFailsWith<AccountException> { phone.account.signOut() }
            assertEquals(AccountError.UnsyncedChanges(1), refused.error)
            assertIs<AccountState.SignedIn>(phone.account.state.value)
            assertEquals(1, phone.data.sync.dirtyRecords(null, 10).size)
            assertEquals(setOf("account.session"), phone.secrets.entries.keys)

            // Once the change is pushed, signing out asks nothing.
            server.down = false
            phone.engine.syncNow()
            phone.account.signOut()
            assertIs<AccountState.SignedOut>(phone.account.state.value)
            assertTrue(phone.data.profiles.profiles().isEmpty())
        }
    }

    @Test
    fun rateLimitsAreWaitedOutWhenShortAndReportedWhenLong() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            server.rateLimitNext = 1
            server.rateLimitSeconds = 3
            val started = testScheduler.currentTime
            assertEquals(1, phone.account.devices().size)
            val waited = testScheduler.currentTime - started
            assertTrue(waited in 3_000..6_000, "waited $waited ms: at least Retry-After, at most twice it")

            server.rateLimitNext = 1
            server.rateLimitSeconds = 120
            val error = assertFailsWith<AccountException> { phone.account.devices() }
            assertEquals(AccountError.RateLimited(120_000), error.error)
        }
    }

    @Test
    fun anUnreachableServerIsANetworkError() = runTest {
        device("Phone").use { phone ->
            phone.account.restore()
            server.down = true
            val error = assertFailsWith<AccountException> { phone.account.beginSignIn(server.serverUrl) }
            assertEquals(AccountError.Network, error.error)
        }
    }
}
