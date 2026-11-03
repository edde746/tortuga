package com.edde746.tortuga.server.http

import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.util.AttributeKey
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import org.slf4j.LoggerFactory

/**
 * The connections the server holds and their kernel buffers, sized so that all of them fit a fixed share of the
 * memory the JVM sizes its heap from (a container's limit: the cgroup charges the kernel's socket buffers to it, past
 * it when packets arrive, and then kills the server). The byte budget ([ByteBudget]) doesn't count that memory.
 *
 * - A connection's buffers: [RECEIVE_BUFFER_BYTES] for what its client sent that the server hasn't read yet, set on
 *   the listening socket so that a connection has it from its handshake on, waiting to be accepted too, and
 *   [SEND_BUFFER_BYTES] for what the server sent that the client hasn't acknowledged. Linux doubles each and never
 *   grows a buffer set this way, so a connection holds about [KERNEL_BYTES] at most, both full. Left to itself, Linux
 *   gives a socket 128 KiB to receive into and grows it to 6 MiB: on 15 Oct (`UPLOADS=1`), 1,000 clients that each
 *   sent the first MiB of a push at once had the kernel charge 155 MiB of the container's 384 to their sockets within a
 *   second, and it killed the server, 7 times. A larger buffer on the listening socket would not do: the window a
 *   connection gets at its handshake holds after its buffer is lowered (on loopback, 38 KiB got ahead of the server's
 *   reads with 32 KiB there, against 9 KiB with [RECEIVE_BUFFER_BYTES]).
 * - While the server reads an admitted body, the connection's receive buffer is [BODY_RECEIVE_BUFFER_BYTES]
 *   ([BodyGate]): through the small one each read takes at most 8 KiB, and under load a push then held its share of
 *   the byte budget long enough to starve the others (server/README.md, Risks). What those raised buffers hold beyond
 *   the small ones, [BODY_RAISE_BYTES] each, comes out of [raisable] bytes for all connections ([tryRaise]); with none
 *   left, a body comes through the small buffer. The raise takes effect after a 4 KiB handshake: on loopback, with the
 *   server's reads stalled, an admitted body got all its 256 KiB ahead at 128 KiB (ConnectionsTest). Past 128 KiB,
 *   pushes grew no more (`BYTES=1`, 15 Oct: 64, 128 and 208 KiB gave 661, 725 and 716, two runs each). Lowering a
 *   buffer drops nothing the kernel holds: what came in past a raised body's end waits for the server's next read,
 *   which the engine makes at once unless 32 of the connection's requests are in progress.
 * - One connection for each [MEMORY_PER_CONNECTION] of the memory ([ofMemory]), a sixteenth of them, [backlog], waiting
 *   in the kernel to be accepted and the rest, [max], held; [raisable] is a thirty-second of it. Hosted (384 MiB):
 *   2,048 connections (1,920 held, 128 waiting) at 24 KiB, 48 MiB, and 12 MiB of raised buffers (49 at once), 60 MiB
 *   together, beside the heap's 45 % and the JVM's own memory (server/README.md, Memory and load).
 * - Past [max] open, a connection is reset as soon as the kernel hands it over (`SO_LINGER` 0: its buffers go at once),
 *   before the server reads a byte of it. Past [backlog] waiting, the kernel completes no more handshakes until the
 *   server accepts, and clients retry theirs: nothing waits in memory.
 * - A connection that turns into a notice stream leaves this count ([release]): the stream cap counts it instead
 *   (`TORTUGA_MAX_EVENT_STREAMS`, SyncEventRoutes), and its buffers drop to Linux's smallest, [STREAM_BUFFER_BYTES]
 *   asked for each: about 8 KB held at most, both full (server/README.md, Memory and load, Notice streams).
 *
 * Installed on the listening channel ([tortugaEmbeddedServer][com.edde746.tortuga.server.tortugaEmbeddedServer]),
 * ahead of the engine: a connection counts from its acceptance until it closes or [release]s.
 */
@ChannelHandler.Sharable
class Connections(val max: Int, val backlog: Int, val raisable: Long) : ChannelInboundHandlerAdapter() {
    private val open = AtomicInteger()

    /** What the raised buffers of all connections hold beyond their small ones, at most. */
    private val raised = AtomicLong()

    /** Connections reset since the last log line, and when that was (nanoTime; 0: none yet). */
    private val refused = AtomicInteger()
    private val loggedAt = AtomicLong()

    /** One accepted connection, on its way to the engine (Netty's acceptor, after this handler). */
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val connection = msg as Channel
        if (open.incrementAndGet() > max) {
            open.decrementAndGet()
            connection.config().setOption(ChannelOption.SO_LINGER, 0)
            connection.unsafe().closeForcibly()
            refusedOne()
            return
        }
        connection.attr(COUNTED_BY).set(this)
        connection.closeFuture().addListener { release(connection) }
        ctx.fireChannelRead(connection)
    }

    /** Takes [bytes] of [raisable] for a connection's raised buffer; false when the raised buffers hold it all. */
    fun tryRaise(bytes: Long): Boolean {
        while (true) {
            val current = raised.get()
            if (current + bytes > raisable) return false
            if (raised.compareAndSet(current, current + bytes)) return true
        }
    }

    /** A connection's raised buffer is small again, or the connection closed: its [bytes] come back. */
    fun lower(bytes: Long) {
        raised.addAndGet(-bytes)
    }

    private fun refusedOne() {
        refused.incrementAndGet()
        val now = System.nanoTime()
        val last = loggedAt.get()
        if (last != 0L && now - last < LOG_INTERVAL.inWholeNanoseconds || !loggedAt.compareAndSet(last, now)) return
        log.warn("Connections: {} open, the most the server holds; reset {} new ones since the last line", max, refused.getAndSet(0))
    }

    companion object {
        /**
         * [connection] no longer counts among those held: it closed, or it is a notice stream now. Once per connection;
         * a second call does nothing.
         */
        fun release(connection: Channel) {
            connection.attr(COUNTED_BY).getAndSet(null)?.open?.decrementAndGet()
        }

        /** What a notice stream's connection asks for each of its kernel buffers: Linux rounds 1 up to its smallest. */
        const val STREAM_BUFFER_BYTES = 1

        /** What a connection's client may send ahead of the server's reads: a request's head and some of its body. */
        const val RECEIVE_BUFFER_BYTES = 4 * 1024

        /** What the server may have sent a connection's client that it hasn't acknowledged: some of an answer. */
        const val SEND_BUFFER_BYTES = 8 * 1024

        /** The receive buffer while the server reads an admitted body. */
        const val BODY_RECEIVE_BUFFER_BYTES = 128 * 1024

        /** A held connection's kernel buffers at most, about: both small ones full, each doubled. */
        const val KERNEL_BYTES = 2L * (RECEIVE_BUFFER_BYTES + SEND_BUFFER_BYTES)

        /** What a raised receive buffer holds beyond the small one, at most, about. */
        const val BODY_RAISE_BYTES = 2L * (BODY_RECEIVE_BUFFER_BYTES - RECEIVE_BUFFER_BYTES)

        /** The memory per connection: its [KERNEL_BYTES] are an eighth of it. */
        const val MEMORY_PER_CONNECTION = 8 * KERNEL_BYTES

        /**
         * The cap for [memory] bytes: [MEMORY_PER_CONNECTION] each, a sixteenth of them waiting to be accepted, and a
         * thirty-second of it for raised buffers.
         */
        fun ofMemory(memory: Long = physicalMemory()): Connections {
            val total = (memory / MEMORY_PER_CONNECTION).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return Connections(max = total - total / 16, backlog = total / 16, raisable = memory / 32)
        }

        /** The memory the JVM sizes its heap from: its container's limit in one, the machine's otherwise. */
        private fun physicalMemory(): Long =
            (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize

        private val LOG_INTERVAL = 1.minutes

        private val log = LoggerFactory.getLogger(Connections::class.java)

        /** The [Connections] a connection counts against, until [release]. */
        private val COUNTED_BY = AttributeKey.valueOf<Connections>("tortuga.countedBy")
    }
}
