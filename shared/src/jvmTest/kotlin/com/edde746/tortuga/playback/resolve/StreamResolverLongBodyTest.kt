package com.edde746.tortuga.playback.resolve

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * The resolver's reads stop early in a body far longer than what the client buffers. The client hands a reader a copy
 * of the body, filled by a coroutine of its own at most 1 MiB ahead of the read. Here that coroutine (on the engine's
 * dispatcher) and the server run Unconfined, inline whenever the read makes room, so whenever the resolver stops
 * reading, the server's channel and the copy are both full again and the copy waits for a reader. The tests' clock is
 * virtual: a resolve that waits for the copy ends at its timeout.
 */
class StreamResolverLongBodyTest {
    @Test
    fun aResolveReturnsOnceItHasReadTheStartOfTheBody() = runTest {
        val body = LongBody(this)

        val result = StreamResolver(body.http.client).resolveHead(StreamRequest("https://cdn.example/f.mkv"))

        assertEquals(LongBody.SIZE, assertIs<ResolveResult.Resolved>(result, "$result").stream.size)
        assertEquals(0, currentTime, "virtual milliseconds the resolve waited")
        body.server.join()
        assertTrue(body.cancelled, "the body went on after ${body.written} of ${LongBody.SIZE} bytes")
    }

    @Test
    fun aPlaylistOverTheCapLeavesNothingOfTheClientRunning() = runTest {
        val body = LongBody(this)

        assertNull(StreamResolver(body.http.client).livePlaylist("https://cdn.example/live.m3u8", emptyMap()))

        advanceUntilIdle()
        // The resolver reads through its own copy of the client (one that follows no redirects) on the same engine.
        val clients = body.http.client.engine.coroutineContext.job.children
        assertEquals(0, clients.sumOf { client -> client.children.count { it.isActive } }, "coroutines of the clients still running")
    }

    /** A server that ignores Range and sends [SIZE] bytes in 8 KiB writes, until the client cancels the body. */
    private class LongBody(scope: TestScope) {
        var written = 0L
        var cancelled = false
        lateinit var server: Job
        val http = FakeHttp(scope, engineDispatcher = Dispatchers.Unconfined) {
            val body = ByteChannel()
            server = scope.backgroundScope.launch(Dispatchers.Unconfined) {
                val block = ByteArray(8192)
                try {
                    while (written < SIZE) {
                        body.writeFully(block)
                        body.flush()
                        written += block.size
                    }
                    body.flushAndClose()
                } catch (e: ClosedByteChannelException) {
                    cancelled = true
                }
            }
            respond(body, HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "$SIZE"))
        }

        companion object {
            const val SIZE = 64L * 1024 * 1024
        }
    }
}
