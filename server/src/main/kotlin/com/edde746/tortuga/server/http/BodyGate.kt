package com.edde746.tortuga.server.http

import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncProtocol
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.timeout.WriteTimeoutException
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One HTTP/1 connection on Netty, between the HTTP codec and Ktor's handler: no request body comes off the socket faster
 * than the server takes its call on, and none may take long to arrive.
 *
 * - Before its call is admitted ([admitted], from [installByteBudget]), a request holds [ByteBudget.CALL_BYTES] of
 *   [budget] and, with a body, what of it came in with its headers: at most [READ_BYTES], one read from the socket
 *   (Ktor reads without auto-read, one read per request for more). No room: 503 `server_busy` with Retry-After, written
 *   here before Ktor sees the request, and the connection closes. Its reads wait meanwhile, so the rest of the body
 *   waits in the kernel's receive buffer, held to [Connections.RECEIVE_BUFFER_BYTES]. Ktor's own buffer (up to a MiB a
 *   connection) only fills once the call has reserved its body ([ByteBudget.BODY_COST] times its length).
 * - An admitted body still coming in, longer than the small buffer holds, gets the connection's receive buffer raised to
 *   [Connections.BODY_RECEIVE_BUFFER_BYTES] until its last byte is read, while [connections] has room for it
 *   ([Connections.tryRaise]).
 * - A call refused at admission ([refused]: 503, 413) is answered, then the connection closes: its body is never read
 *   into the heap. For [LINGER_MILLIS] after the answer, what the client still sends is read and dropped, so that a
 *   client still sending gets the answer rather than a reset.
 * - A notice stream's connection ([streaming]) left [Connections]' count, so it serves nothing else: what its client
 *   sends after the stream's request is read and dropped, so that its close is seen at once, and the connection closes
 *   the same way once the stream ends.
 * - A body must be in by [grace] plus its length (at most [SyncProtocol.MAX_REQUEST_BYTES]; that, for one without a
 *   Content-Length) at [bytesPerSecond]; past that the connection closes without an answer. That holds for the body of
 *   a call answered without reading it too, which Ktor drains to keep the connection.
 * - A connection whose client stopped reading its answer, which the engine closes after its write timeout, is reset
 *   (`SO_LINGER` 0): the kernel drops what it still held for the client at once, instead of keeping it while it retries
 *   for up to two minutes after the server let the connection go, uncounted by [Connections].
 *
 * Netty calls it on the connection's event loop; [admitted] and [refused] may come from any thread.
 */
class BodyGate(
    private val budget: ByteBudget,
    private val connections: Connections,
    private val grace: Duration = GRACE,
    private val bytesPerSecond: Long = BYTES_PER_SECOND,
) : ChannelDuplexHandler() {
    private lateinit var ctx: ChannelHandlerContext

    /** Requests handed to Ktor, and those answered (their response's last part written); answers go in request order. */
    private var decoded = 0L
    private var answered = 0L

    /** Requests handed to Ktor whose calls haven't been admitted, and what they hold of [budget]. */
    private var waiting = 0
    private var held = 0L

    /** Ktor asked for a read while a request waited for admission. */
    private var readHeld = false

    /** The connection closes once this many requests are answered: a call was refused. */
    private var closeAfter = Long.MAX_VALUE

    /** Past an answer that closes the connection: what comes in is dropped until it closes. */
    private var closing = false

    /** The connection carries a notice stream: it reads on, dropping what comes in, until it closes. */
    private var streaming = false
    private var deadline: ScheduledFuture<*>? = null
    private var linger: ScheduledFuture<*>? = null

    /** The body being read: its Content-Length, [Long.MAX_VALUE] without one (chunked); 0 when none is. */
    private var bodyLength = 0L

    /** What this connection's raised receive buffer holds of [Connections.raisable] (0: not raised). */
    private var bodyRaise = 0L

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        this.ctx = ctx
    }

    /** The call of a request waiting on this connection was admitted: it reserved its own bytes. */
    fun admitted() = ctx.executor().execute { pass() }

    /** The call of a request waiting on this connection was refused: the connection closes once it is answered. */
    fun refused() = ctx.executor().execute {
        closeAfter = min(closeAfter, decoded)
        pass()
    }

    /** The call answering the latest request on this connection is a notice stream: the connection closes after it. */
    fun streaming() = ctx.executor().execute {
        streaming = true
        closing = true
        closeAfter = min(closeAfter, decoded)
        ctx.read()
    }

    private fun pass() {
        if (waiting > 0) waiting--
        if (waiting == 0) {
            // Every request so far is admitted, so the body being read, the last one's, is too.
            if (closeAfter == Long.MAX_VALUE && bodyLength > 2L * Connections.RECEIVE_BUFFER_BYTES && bodyRaise == 0L &&
                connections.tryRaise(Connections.BODY_RAISE_BYTES)
            ) {
                bodyRaise = Connections.BODY_RAISE_BYTES
                ctx.channel().config().setOption(ChannelOption.SO_RCVBUF, Connections.BODY_RECEIVE_BUFFER_BYTES)
            }
            releaseWaiting()
        }
    }

    private fun releaseWaiting() {
        budget.release(held)
        held = 0
        if (readHeld && closeAfter == Long.MAX_VALUE) {
            readHeld = false
            ctx.read()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when {
            closing -> ReferenceCountUtil.release(msg)
            msg is HttpRequest -> request(ctx, msg)
            else -> {
                if (msg is LastHttpContent) {
                    deadline?.cancel(false)
                    deadline = null
                    bodyLength = 0
                    if (bodyRaise > 0) {
                        ctx.channel().config().setOption(ChannelOption.SO_RCVBUF, Connections.RECEIVE_BUFFER_BYTES)
                        connections.lower(bodyRaise)
                        bodyRaise = 0
                    }
                }
                ctx.fireChannelRead(msg)
            }
        }
    }

    private fun request(ctx: ChannelHandlerContext, request: HttpRequest) {
        val length = request.headers().get(HttpHeaderNames.CONTENT_LENGTH)?.toLongOrNull()
        val chunked = HttpUtil.isTransferEncodingChunked(request)
        val body = if (chunked) READ_BYTES else min(length ?: 0, READ_BYTES)
        val bytes = ByteBudget.CALL_BYTES + body
        if (!budget.tryReserve(bytes)) {
            ReferenceCountUtil.release(request)
            closing = true
            // Earlier requests still unanswered: their answers come first, then the connection closes.
            if (answered < decoded) closeAfter = min(closeAfter, decoded) else ctx.writeAndFlush(busy()).addListener { linger() }
            return
        }
        held += bytes
        waiting++
        decoded++
        bodyLength = if (chunked) Long.MAX_VALUE else length ?: 0
        if (chunked || (length ?: 0) > 0) {
            val most = min(length ?: Long.MAX_VALUE, SyncProtocol.MAX_REQUEST_BYTES.toLong())
            deadline?.cancel(false)
            deadline = ctx.executor().schedule({ ctx.close() }, grace.inWholeMilliseconds + most * 1000 / bytesPerSecond, TimeUnit.MILLISECONDS)
        }
        ctx.fireChannelRead(request)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        if (closing && (linger != null || streaming)) ctx.read()
        ctx.fireChannelReadComplete()
    }

    override fun read(ctx: ChannelHandlerContext) {
        if (!closing && (waiting > 0 || closeAfter != Long.MAX_VALUE)) readHeld = true else ctx.read()
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        when {
            msg is LastHttpContent && !(msg is HttpResponse && msg.status().codeClass() == HttpStatusClass.INFORMATIONAL) ->
                ctx.write(msg, promise.unvoid().addListener { answer() })
            else -> ctx.write(msg, promise)
        }
    }

    private fun answer() {
        answered++
        // Answers go in request order: every request up to this one is past its admission, admitted or not.
        if (waiting > decoded - answered) {
            waiting = (decoded - answered).toInt()
            if (waiting == 0) releaseWaiting()
        }
        if (answered >= closeAfter) linger()
    }

    private fun linger() {
        if (linger != null) return
        closing = true
        linger = ctx.executor().schedule({ ctx.close() }, LINGER_MILLIS, TimeUnit.MILLISECONDS)
        ctx.read()
    }

    /** The connection closed: what its waiting requests held goes back, and what its raised buffer held. */
    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        deadline?.cancel(false)
        linger?.cancel(false)
        budget.release(held)
        held = 0
        connections.lower(bodyRaise)
        bodyRaise = 0
    }

    /** The engine's write timeout fired: the close that follows it resets the connection. */
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is WriteTimeoutException) ctx.channel().config().setOption(ChannelOption.SO_LINGER, 0)
        ctx.fireExceptionCaught(cause)
    }

    private fun busy(): DefaultFullHttpResponse {
        val body = SyncProtocol.json.encodeToString(ApiError.serializer(), ApiError(ApiErrorCode.SERVER_BUSY, "Server busy"))
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE, Unpooled.copiedBuffer(body, Charsets.UTF_8))
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
            .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
            .set(HttpHeaderNames.RETRY_AFTER, BUSY_RETRY_AFTER_SECONDS)
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response
    }

    companion object {
        /** Netty's largest read from a socket (its adaptive buffer's most). */
        const val READ_BYTES = 64L * 1024

        /** A body's time before its length counts. */
        val GRACE: Duration = 10.seconds

        /** The slowest a body may arrive on average (512 kbit/s): an 8 MiB push within 138 s. */
        const val BYTES_PER_SECOND = 64L * 1024

        /** How long a refused request's connection still takes in what its client sends before it closes. */
        const val LINGER_MILLIS = 2_000L
    }
}
