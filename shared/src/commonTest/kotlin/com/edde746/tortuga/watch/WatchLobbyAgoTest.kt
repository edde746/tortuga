@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.watch

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.RecentRoom
import com.edde746.tortuga.data.settings.RecentRooms
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.presentation.watch.WatchStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The lobby's recent rooms say how long ago each was used, and that moves on while the screen is open. */
class WatchLobbyAgoTest {
    @Test
    fun aRecentRoomsLastUseAgesWhileTheLobbyIsOpen() = runTest {
        val start = Instant.parse("2026-10-26T12:00:00Z")
        val clock = object : Clock {
            override fun now() = start + testScheduler.currentTime.milliseconds
        }
        val data = LocalData(
            inMemoryDatabase(), { clock.now().toEpochMilliseconds() }, StandardTestDispatcher(testScheduler),
            HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        data.deviceSettings.set(
            RecentRooms("profile-1"),
            listOf(RecentRoom("K7MQ2X", WatchRelayEndpoint.HOSTED.baseUrl, start.toEpochMilliseconds())),
        )
        val http = HttpClient(
            MockEngine.create {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { awaitCancellation() }
            },
        ) { install(WebSockets) }
        val watch = testWatchTogether(
            http = http,
            data = data,
            profileId = "profile-1",
            displayName = "Anna",
            client = "Tortuga/test",
            wallClock = clock,
            parentScope = backgroundScope,
        )
        val lobby = WatchStore(watch, watch.playback, data.deviceSettings, clock, backgroundScope)
        runCurrent()
        fun used() = lobby.state.value.recentRooms.single().detail
        assertEquals(T.watch.recentUsed(T.settings.time.justNow), used())

        advanceTimeBy(1.minutes + 1.seconds)
        runCurrent()
        assertEquals(T.watch.recentUsed(T.settings.time.minutesAgo(1)), used())

        advanceTimeBy(2.days)
        runCurrent()
        assertEquals(T.watch.recentUsed(T.settings.time.daysAgo(2)), used())
        lobby.release()
    }
}
