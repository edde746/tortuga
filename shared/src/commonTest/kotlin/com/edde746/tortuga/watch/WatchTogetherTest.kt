package com.edde746.tortuga.watch

import com.edde746.tortuga.app.testData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.presentation.watch.WatchStore
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import party.openwatch.Relays
import party.openwatch.WatchParty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Watch together's room operations against relays that never answer (every request and WebSocket waits for good), so
 * what a create, a join or an invite starts stays under way until something ends it.
 */
class WatchTogetherTest {
    @Test
    fun aJoinWhoseScreenWentLeavesTheLobbyReady() = runTest {
        val room = room()
        val lobby = room.lobby()
        lobby.setJoinText("K7M-Q2X")
        lobby.join()
        runCurrent()
        assertEquals(WatchActivity.JOINING, room.watch.state.value.activity)

        // Back: the screen releases its store, and the join with it.
        lobby.release()
        runCurrent()
        assertNull(room.watch.state.value.activity)
        val again = room.lobby()
        again.setJoinText("K7M-Q2X")
        assertTrue(again.state.value.canJoin)
        assertNull(again.state.value.busy)
        again.release()
    }

    @Test
    fun aStartWhosePlayerWentLeavesNothingUnderWay() = runTest {
        val room = room()
        val start = launch { room.watch.create(WatchControlMode.HOST_ONLY) }
        runCurrent()
        assertEquals(WatchActivity.CREATING, room.watch.state.value.activity)
        start.cancel()
        runCurrent()
        assertNull(room.watch.state.value.activity)
    }

    @Test
    fun anyProfileNameStartsARoom() = runTest {
        val http = HttpClient(MockEngine { awaitCancellation() })
        val names = listOf("Anna\u200F", "\u202EannA", "Anna\nBerg\tB", "\u200E\u200F", "   ", "A\uD800nna", "😀".repeat(40), "a".repeat(31) + "😀")
        for (name in names) {
            val party = WatchTogether.partyName(name)
            // The SDK refuses a name it can't send by throwing.
            WatchParty<String>(relays = Relays.public(http), client = CLIENT, name = party, scope = backgroundScope)
            assertTrue(party.codePointLength() in 1..32, "$name: $party")
        }
        assertEquals("Anna", WatchTogether.partyName("Anna\u200F"))
        assertEquals("Anna Berg B", WatchTogether.partyName("Anna\nBerg\tB"))
        assertEquals("Tortuga", WatchTogether.partyName("\u200E\u200F"))
        assertEquals("😀".repeat(32), WatchTogether.partyName("😀".repeat(40)))

        // Through the room: the profile's name starts one (which waits on the relay) instead of failing.
        val room = room(name = "Anna\u200F")
        val start = launch { room.watch.create(WatchControlMode.HOST_ONLY) }
        runCurrent()
        assertEquals(WatchActivity.CREATING, room.watch.state.value.activity)
        assertNull(room.watch.state.value.failure)
        start.cancel()
    }

    @Test
    fun anInviteOnAnotherRelayAsksFirstAndNamesIt() = runTest {
        val room = room()
        val watch = room.watch
        val lobby = room.lobby()
        runCurrent()
        val stranger = checkNotNull(WatchRelayEndpoint.parse("http://10.0.0.9:8085"))
        val invite = WatchInvite("K7MQ2X", stranger)
        watch.openInvite(invite)
        runCurrent()
        assertEquals(invite, watch.state.value.invite)
        assertNull(watch.state.value.activity)
        assertTrue(room.requests.isEmpty(), "nothing reaches a relay before the yes: ${room.requests.map { it.url }}")
        val question = assertNotNull(lobby.state.value.inviteQuestion)
        assertTrue("K7M-Q2X" in question.title && "10.0.0.9" in question.title, question.title)

        // Cancel: nothing joined.
        lobby.dismissInvite()
        runCurrent()
        assertNull(lobby.state.value.inviteQuestion)
        assertNull(watch.state.value.activity)
        assertTrue(room.requests.isEmpty())

        // Asked again and confirmed: the join goes to the invite's relay.
        watch.openInvite(invite)
        lobby.joinInvite()
        runCurrent()
        assertEquals(WatchActivity.JOINING, watch.state.value.activity)
        assertEquals(stranger, watch.state.value.roomRelay)
        assertTrue(room.requests.any { it.url.host == "10.0.0.9" }, "${room.requests.map { it.url }}")
        assertNull(lobby.state.value.inviteQuestion)
        lobby.release()
    }

    @Test
    fun anInviteOnThisDevicesRelayJoinsAtOnce() = runTest {
        val room = room()
        val join = launch { room.watch.openInvite(WatchInvite("K7MQ2X", WatchRelayEndpoint.HOSTED)) }
        runCurrent()
        assertNull(room.watch.state.value.invite)
        assertEquals(WatchActivity.JOINING, room.watch.state.value.activity)
        join.cancel()
    }

    /** Watch together for one profile, on the public relays (the device's setting), with its HTTP requests so far. */
    private class Room(val test: TestScope, val watch: WatchTogether, val settings: DeviceSettingsRepository, val requests: List<HttpRequestData>) {
        fun lobby() = WatchStore(watch, watch.playback, settings, FixedClock, test.backgroundScope)
    }

    /** A profile named [name] over relays that never answer. */
    private fun TestScope.room(name: String = "Anna"): Room {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(
            MockEngine.create {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    requests += request
                    awaitCancellation()
                }
            },
        ) { install(WebSockets) }
        val data = testData()
        val watch = testWatchTogether(
            http = http,
            data = data,
            profileId = "profile-1",
            displayName = name,
            client = CLIENT,
            wallClock = FixedClock,
            parentScope = backgroundScope,
        )
        runCurrent()
        return Room(this, watch, data.deviceSettings, requests)
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(TestClock().now)
    }

    private fun String.codePointLength(): Int {
        var count = 0
        var i = 0
        while (i < length) {
            i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    private companion object {
        const val CLIENT = "Tortuga/test"
    }
}
