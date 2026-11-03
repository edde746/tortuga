package com.edde746.tortuga.watch.relay

import com.edde746.tortuga.log.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchRelayEndpointTest {
    private val originalSink = Log.sink
    private val lines = mutableListOf<String>()

    @AfterTest
    fun restoreLog() {
        Log.sink = originalSink
    }

    @Test
    fun aStoredRelayThatNoLongerParsesFallsBackToThePublicRelaysLogged() {
        Log.sink = Log.Sink { level, _, message, _ -> lines += "$level $message" }

        assertEquals(WatchRelayEndpoint.HOSTED, WatchRelayEndpoint.resolve(null))
        assertEquals(WatchRelayEndpoint.HOSTED, WatchRelayEndpoint.resolve(""))
        assertEquals("https://relay.example", WatchRelayEndpoint.resolve("https://relay.example/").baseUrl)
        assertEquals(emptyList(), lines, "none stored, or a relay: nothing to say")

        assertEquals(WatchRelayEndpoint.HOSTED, WatchRelayEndpoint.resolve("ftp://relay.example"))
        assertTrue(lines.single().startsWith("WARN "), "$lines")
    }
}
