@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync.engine

import com.edde746.tortuga.sync.FailingSecretStore
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.SignedOutReason
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The account's notice stream against the in-memory server, in virtual time: another device's change
 * pulls at once, notices coalesce, the stream lives only while the app is in front with a ready account, and its
 * reopenings keep to the server's Retry-After and their backoff.
 */
class SyncNoticesTest {
    private val server = FakeSyncServer()

    /** A device signed in to the one account ("alice"), its engine not started. */
    private suspend fun TestScope.signedIn(name: String, store: FailingSecretStore = FailingSecretStore()): TestDevice {
        val device = TestDevice(name, server, this, backgroundScope, store.memory, store = store)
        device.account.restore()
        device.account.signIn(server.serverUrl) { TestDevice.credential() }
        return device
    }

    /** A device signed in to the account with its engine running and the app in the foreground. */
    private suspend fun TestScope.running(name: String, store: FailingSecretStore = FailingSecretStore()): TestDevice {
        val device = signedIn(name, store)
        device.engine.start()
        device.engine.onForeground()
        runCurrent()
        return device
    }

    private suspend fun TestDevice.sync() = assertEquals(SyncStatus.Kind.Idle, engine.syncNow().kind)

    private suspend fun TestDevice.profileNames() = data.profiles.profiles().map { it.name }.sorted()

    private fun opens() = server.requests.count { it == EVENTS }

    private fun pulls() = server.requests.count { it == PULL }

    @Test
    fun anotherDevicesChangeArrivesWithoutWaitingForThePeriodicPull() = runTest {
        val tv = running("TV")
        val phone = signedIn("Phone")
        tv.use {
            phone.use {
                assertEquals(listOf(server.account().id), server.openStreams())
                phone.data.profiles.create("Wednesday", null)
                phone.sync()
                runCurrent()
                assertEquals(listOf("Wednesday"), tv.profileNames(), "the TV pulled on the phone's notice")
            }
        }
    }

    @Test
    fun noticesThatComeDuringAPullMakeOneMorePull() = runTest {
        val tv = running("TV")
        tv.use {
            server.requests.clear()
            server.beforeNextPull = {
                repeat(3) {
                    server.changed()
                    delay(100)
                }
            }
            server.changed()
            advanceTimeBy(10_000)
            assertEquals(2, pulls(), "the notice's pull, then one for the three that came during it")
        }
    }

    @Test
    fun theStreamIsOpenOnlyWhileTheAppIsInFrontWithAReadyAccount() = runTest {
        val tv = running("TV")
        val phone = signedIn("Phone")
        tv.use {
            phone.use {
                val alice = server.account().id
                assertEquals(listOf(alice), server.openStreams())
                tv.engine.onBackground()
                runCurrent()
                assertEquals(emptyList(), server.openStreams(), "the background closes it")

                // An hour in the background, with a change on the phone: no stream, no pull.
                server.requests.clear()
                phone.data.profiles.create("Wednesday", null)
                phone.sync()
                advanceTimeBy(60 * 60_000)
                assertEquals(0, opens())
                assertEquals(1, pulls(), "only the phone's")
                assertEquals(emptyList(), tv.profileNames())

                // Back within the minute that skips the foreground's own pull: the stream opens again, and its open
                // pulls what changed while it was closed.
                tv.engine.onForeground()
                runCurrent()
                assertEquals(listOf("Wednesday"), tv.profileNames())
                tv.engine.onBackground()
                runCurrent()
                phone.data.profiles.create("Kids", null)
                phone.sync()
                advanceTimeBy(30_000)
                tv.engine.onForeground()
                runCurrent()
                assertEquals(listOf(alice), server.openStreams(), "the foreground opens it again")
                assertEquals(listOf("Kids", "Wednesday"), tv.profileNames())

                // Signing out closes it; another account's sign-in opens that account's.
                tv.account.signOut()
                runCurrent()
                assertEquals(emptyList(), server.openStreams())
                tv.account.signIn(server.serverUrl) { TestDevice.credential("bob") }
                runCurrent()
                val bob = server.accounts.values.single { it.subject == "bob" }.id
                assertEquals(listOf(bob), server.openStreams())
                tv.account.signIn(server.serverUrl) { TestDevice.credential() }
                runCurrent()
                assertEquals(listOf(alice), server.openStreams(), "switching accounts moves it")
            }
        }
    }

    @Test
    fun aBusyServersRetryAfterIsHonoured() = runTest {
        server.refuseEvents = HttpStatusCode.ServiceUnavailable
        server.eventsRetryAfterSeconds = 120
        val tv = running("TV")
        tv.use {
            assertEquals(1, opens())
            server.refuseEvents = null
            advanceTimeBy(119_000)
            assertEquals(1, opens(), "nothing before Retry-After")
            advanceTimeBy(122_000)
            assertEquals(2, opens(), "by twice Retry-After")
            assertEquals(listOf(server.account().id), server.openStreams())
        }
    }

    @Test
    fun aServerWithoutTheStreamIsPolledAsBefore() = runTest {
        server.refuseEvents = HttpStatusCode.NotFound
        val tv = running("TV")
        tv.use {
            advanceTimeBy(60 * 60_000)
            tv.engine.onBackground()
            runCurrent()
            tv.engine.onForeground()
            runCurrent()
            advanceTimeBy(60 * 60_000)
            assertEquals(1, opens(), "a 404 ends the stream for the session")
            assertTrue(pulls() >= 22, "the periodic pulls go on, got ${pulls()}")
        }
    }

    @Test
    fun aStreamThatEndsAtOnceReopensNoFasterThanTheBackoff() = runTest {
        server.endEventsAtOnce = true
        val tv = running("TV")
        tv.use {
            advanceTimeBy(30 * 60_000)
            // A stream that ends under a minute counts as a failure: waits of half to all of 10, 20, 40, 80 and 160 s,
            // then of the 5-minute cap.
            assertTrue(opens() in 10..16, "${opens()} opens in 30 minutes")
        }
    }

    @Test
    fun anExpiredTokenRefreshesAndARevokedSessionSignsOut() = runTest {
        val tv = running("TV")
        tv.use {
            server.expireAccessTokens()
            server.endStreams()
            advanceTimeBy(11_000)
            assertEquals(1, server.refreshes)
            assertEquals(3, opens(), "the first, the one refused as expired, the one with the new token")
            assertEquals(listOf(server.account().id), server.openStreams())

            // The server revokes the session and ends its stream: the reopen's 401 signs the TV out, and that is all.
            server.sessions.clear()
            server.endStreams()
            advanceTimeBy(60 * 60_000)
            assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_ENDED), tv.account.state.value)
            assertEquals(4, opens())
            assertEquals(emptyList(), server.openStreams())
        }
    }

    @Test
    fun aSecretStoreThatCantKeepRefreshedTokensFailsSyncVisiblyUntilItCan() = runTest {
        val store = FailingSecretStore()
        val tv = running("TV", store)
        tv.use {
            // The access token's hour runs out while the store can't write: the periodic pull's refresh fails.
            store.failWrites = true
            advanceTimeBy(FakeSyncServer.ACCESS_TTL_MILLIS + 60_000)
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.UNKNOWN), tv.engine.status.value.kind)
            // The stream, reopened, refreshes too and fails the same way: it opens again after its backoff.
            server.endStreams()
            advanceTimeBy(5 * 60_000)
            assertIs<AccountState.SignedIn>(tv.account.state.value)
            assertIs<SyncStatus.Kind.Error>(tv.engine.status.value.kind)
            assertEquals(emptyList(), server.openStreams())

            // The store writes again: the next refresh keeps its tokens, the stream opens and sync is up to date.
            store.failWrites = false
            advanceTimeBy(10 * 60_000)
            assertEquals(listOf(server.account().id), server.openStreams())
            assertEquals(SyncStatus.Kind.Idle, tv.engine.status.value.kind)
        }
    }

    private companion object {
        const val EVENTS = "GET ${SyncPaths.SYNC_EVENTS}"
        const val PULL = "GET ${SyncPaths.SYNC_PULL}"
    }
}
