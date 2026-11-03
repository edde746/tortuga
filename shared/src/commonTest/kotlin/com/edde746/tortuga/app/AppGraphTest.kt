package com.edde746.tortuga.app

import com.edde746.tortuga.net.NetworkState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppGraphTest {
    @Test
    fun aHeldOfflineNetworkStaysOfflineUntilReleased() = runTest {
        val graph = testGraph()

        graph.holdOffline(true)
        assertEquals(NetworkState.OFFLINE, graph.network.value)
        graph.setNetwork(NetworkState(online = true, metered = true))
        assertEquals(NetworkState.OFFLINE, graph.network.value, "the platform's reports wait while held")

        graph.holdOffline(false)
        assertEquals(NetworkState(online = true, metered = true), graph.network.value)
    }

    @Test
    fun fastTimersMakeTheSleepTimersMinutesSixSecondsInThePlayersOpenedFromThenOn() = runTest {
        val graph = testGraph()
        val platform = FakeAppPlatform()
        val app = AppSession(graph, "profile-1", platform, start = null, profileAccount = null, startProfile = graph.data.addProfile("profile-1"))

        graph.fastPlayerTimers = true
        app.playback.play(MOVIE_REQUEST)
        runCurrent()
        assertEquals(platform.playerConfig.copy(sleepTimerMinuteSeconds = 6), assertNotNull(app.state.value.playback).config)

        graph.fastPlayerTimers = false
        app.playback.play(MOVIE_REQUEST)
        runCurrent()
        assertEquals(platform.playerConfig, assertNotNull(app.state.value.playback).config)
        app.close()
    }
}
