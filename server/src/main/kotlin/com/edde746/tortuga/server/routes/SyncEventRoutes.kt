package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.BodyGate
import com.edde746.tortuga.server.http.Connections
import com.edde746.tortuga.server.http.ServerBusy
import com.edde746.tortuga.server.http.bearer
import com.edde746.tortuga.server.http.releaseBytes
import com.edde746.tortuga.server.service.SyncEvents
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import io.ktor.server.routing.get
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ClosedByteChannelException
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.WriteBufferWaterMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * `GET` [SyncPaths.SYNC_EVENTS]: the account change notice stream, on Ktor's SSE plugin; [SyncEvents] holds the open
 * streams and decides what each sends. Before the response starts, the call takes a place under
 * [SyncEvents.maxStreams] (503 with Retry-After [STREAMS_TAKEN_RETRY_AFTER_SECONDS] past it), authenticates (401, or
 * 503 when the database is busy), so every refusal is an ordinary error answer, registers its stream, so no change
 * committed after its 200 goes unheard, and authenticates again, so a session that ended in between (revoked, signed
 * out, evicted, its account deleted: [SyncEvents.endSession] found no stream to end) gets 401 instead of a stream. The
 * stream's writer gives the place back when it ends (on Netty the call's `respond` returns once the writer has, on
 * Ktor's test engine at once). Once streaming, the call gives back its share of the byte budget ([releaseBytes]), and
 * on Netty:
 * - its connection leaves the HTTP connections' count for the stream cap, with the smallest kernel buffers
 *   ([Connections.release], [Connections.STREAM_BUFFER_BYTES]), and closes once the stream ends
 *   ([BodyGate.streaming]);
 * - it writes only while Netty holds none of its bytes (write water marks of one byte, [Writable]): a client that stops
 *   reading holds the kernel's small buffers and one notice at most, never a queue, and once a write has waited the
 *   engine's write timeout (Ktor's default, 10 s) the connection is reset (BodyGate); the app opens another stream and
 *   pulls.
 */
fun Route.syncEventRoutes(services: Services) {
    val events = services.events
    get(SyncPaths.SYNC_EVENTS) {
        if (!events.tryAdmit()) throw ServerBusy("Every notice stream is taken", STREAMS_TAKEN_RETRY_AFTER_SECONDS)
        val stream = try {
            val session = services.sessions.authenticate(call.bearer())
            SyncEvents.Stream(session.accountId, session.sessionId, session.accessExpiresAt)
        } catch (e: Throwable) {
            events.leave()
            throw e
        }
        call.releaseBytes()
        call.response.header(HttpHeaders.CacheControl, "no-store, no-transform")
        call.response.header("X-Accel-Buffering", "no")
        events.open(stream)
        try {
            // A session that ends from here on ends the registered stream; one that ended since the first look is seen here.
            services.sessions.authenticate(call.bearer())
            call.respond(
                SSEServerContent(call) {
                    try {
                        serve(stream)
                    } finally {
                        events.close(stream)
                    }
                },
            )
        } catch (e: Throwable) {
            // The writer never ran, or failed: its stream is over either way.
            events.close(stream)
            throw e
        }
    }
}

/** What a 503 for a stream past [SyncEvents.maxStreams] asks the app to wait, in seconds: it polls meanwhile. */
private const val STREAMS_TAKEN_RETRY_AFTER_SECONDS = 60

private val KEEPALIVE_EVENT = ServerSentEvent(comments = "")

/**
 * Writes [stream] until it ends, on [Dispatchers.Default]: Ktor runs the handler on `Dispatchers.IO`, where 1,000
 * writers woken every 5 ms made the scheduler start 113 threads (15 Oct, test/notify-stream). It starts with a
 * keepalive: Netty holds a GET's response head until the body's first flush, and the app counts the stream open, and
 * pulls, once it has the head.
 */
private suspend fun ServerSSESession.serve(stream: SyncEvents.Stream) {
    val netty = call.nettyCall()?.context
    val channel = netty?.channel()
    if (channel != null) {
        Connections.release(channel)
        netty.pipeline().get(BodyGate::class.java)?.streaming()
        channel.config().setOption(ChannelOption.SO_SNDBUF, Connections.STREAM_BUFFER_BYTES)
        channel.config().setOption(ChannelOption.SO_RCVBUF, Connections.STREAM_BUFFER_BYTES)
        channel.config().writeBufferWaterMark = WriteBufferWaterMark(1, 1)
        channel.pipeline().addFirst(Writable(stream))
        channel.closeFuture().addListener { stream.end() }
    }
    try {
        send(KEEPALIVE_EVENT)
        withContext(Dispatchers.Default) {
            while (stream.awaitWork()) {
                if (channel != null && !channel.isWritable) {
                    // Netty still holds the last write: the client is behind, and what comes meanwhile waits folded.
                    stream.hold()
                    if (!channel.isWritable) continue
                    stream.resume()
                }
                when (val next = stream.take()) {
                    null -> continue
                    SyncEvents.KEEPALIVE -> send(KEEPALIVE_EVENT)
                    else -> send(ServerSentEvent(data = next.toString(), event = SyncPaths.EVENT_CHANGED))
                }
            }
        }
    } catch (e: ClosedByteChannelException) {
        // The connection closed under a write: the stream is over, as when its close is seen first.
        log.debug("Notice stream closed while writing", e)
    }
}

/** Wakes a held notice stream's writer once Netty has handed all it held for it to the kernel. */
private class Writable(private val stream: SyncEvents.Stream) : ChannelInboundHandlerAdapter() {
    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable) stream.resume()
        ctx.fireChannelWritabilityChanged()
    }
}

/** The Netty call under [this], or null on another engine (Ktor's test engine). */
private fun ApplicationCall.nettyCall(): NettyApplicationCall? = when (this) {
    is RoutingCall -> pipelineCall.engineCall
    is RoutingPipelineCall -> engineCall
    else -> this
} as? NettyApplicationCall

private val log = LoggerFactory.getLogger("com.edde746.tortuga.server.routes.SyncEventRoutes")
