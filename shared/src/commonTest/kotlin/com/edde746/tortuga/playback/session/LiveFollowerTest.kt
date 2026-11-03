@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.playback.session

import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.resolve.StreamResolver
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** A live file's window ([LiveFollower]) as the session leaves the file ([LiveFollower.close]). */
class LiveFollowerTest {
    @Test
    fun closingStopsThePlaylistReadsAndAReadUnderWayShowsNothing() = runTest {
        var reads = 0
        val http = FakeHttp(this) {
            reads++
            // The second read takes a while: the file is left meanwhile.
            if (reads > 1) delay(3.seconds)
            val text = buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:500\n")
                repeat(45) { append("#EXTINF:2.000,\nevent/${500 + it}.ts\n") }
            }
            respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"))
        }
        val shown = mutableListOf<LiveWindow?>()
        val stream = ResolvedStream("https://live.example/event.m3u8", emptyMap(), size = null, videoHash = null, filename = null)
        val live = LiveFollower(StreamResolver(http.client), stream, "Tortuga/test", backgroundScope, testScheduler.timeSource) { shown += it }

        live.followWindow(EngineState(loaded = true, playbackRestarts = 1))
        runCurrent()
        assertEquals(1, reads)
        assertEquals(90.0, shown.last()?.windowSeconds)
        val before = shown.size

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, reads, "read again a target duration later")
        live.close()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, reads, "not read again once closed")
        assertEquals(before, shown.size, "the read under way shows nothing")
    }
}
