package com.edde746.tortuga.server.http

import com.edde746.tortuga.server.service.SyncRecords
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicLong

/**
 * The heap that HTTP calls may hold at once: [total] bytes, shared by every call. A call reserves before it holds:
 * [CALL_BYTES] for itself and [BODY_COST] times its body's length before a byte of the body is read, and what it builds
 * once it knows the size (a pull's page, a push's stale records: [reserveBytes]). Everything a call reserved returns
 * when it ends, its answer written. A call that finds no room is answered 503 with Retry-After ([ServerBusy]), as when
 * every database connection is busy; one whose body is longer than its path takes, or that alone needs more than
 * [total], is answered 413. On Netty a request also holds what came in with it until its call is admitted
 * ([BodyGate]), so the bytes of requests not yet admitted count too.
 */
class ByteBudget(val total: Long) {
    private val reserved = AtomicLong()

    /** What the calls in progress have reserved. */
    val held: Long get() = reserved.get()

    fun tryReserve(bytes: Long): Boolean {
        while (true) {
            val current = reserved.get()
            if (current + bytes > total) return false
            if (reserved.compareAndSet(current, current + bytes)) return true
        }
    }

    fun release(bytes: Long) {
        reserved.addAndGet(-bytes)
    }

    companion object {
        /**
         * What a body costs per byte of its length while it is read and parsed and what it turned into is kept: a push
         * holds its body, then its records' compact JSON, then their sealed copies (each no larger than the body).
         */
        const val BODY_COST = 3

        /** What a call holds besides its body and what it builds: the engine's request, headers and coroutine. */
        const val CALL_BYTES = 8L * 1024

        /**
         * What the engine buffers of a body before the call reads it (Ktor's request channel holds up to 1 MiB): a body
         * without a Content-Length counts as at least this long.
         */
        const val ENGINE_BODY_BYTES = 1L shl 20

        /**
         * The hosted default: a quarter of [heap] (server/README.md, Memory and load). A heap whose quarter can't hold the
         * largest push ([PUSH_MOST_BYTES]) fails, naming the heap the server needs: every such push would get 413.
         */
        fun ofHeap(heap: Long = Runtime.getRuntime().maxMemory()): Long {
            val needed = 4 * PUSH_MOST_BYTES
            require(heap / 4 >= PUSH_MOST_BYTES) {
                "The heap is ${heap shr 20} MiB, and a push of the largest body (${SyncProtocol.MAX_REQUEST_BYTES shr 20} MiB) " +
                    "needs a heap of at least ${(needed + (1 shl 20) - 1) shr 20} MiB, a quarter of it for requests: raise -Xmx or " +
                    "-XX:MaxRAMPercentage"
            }
            return heap / 4
        }
    }
}

/**
 * What the parser holds per byte of the longest string it copies, besides the body: the string's characters twice, two
 * bytes each (as read, and joined), its compact copy while that doubles, and the copy's final array.
 */
internal const val PARSE_COST = 7L

/**
 * The most a push reserves of the byte budget: the call and its largest body, its longest string's parse, and twice its
 * stale records, which stop once those before the last reach [SyncRecords.PAGE_BYTES] (the last is at most a record and
 * its id, each sealed, and the answer's 96 bytes; 1 KiB covers those). The server doesn't start on a heap whose budget
 * is smaller ([ByteBudget.ofHeap]).
 */
internal const val PUSH_MOST_BYTES: Long = ByteBudget.CALL_BYTES + ByteBudget.BODY_COST * SyncProtocol.MAX_REQUEST_BYTES +
    PARSE_COST * SyncProtocol.MAX_RECORD_BYTES +
    2 * (SyncRecords.PAGE_BYTES + SyncProtocol.MAX_RECORD_BYTES + 4L * SyncProtocol.MAX_RECORD_ID_LENGTH + 1024)

/** What one call has reserved of a [ByteBudget]; all of it returns when the call ends. */
private class CallBytes(private val budget: ByteBudget) {
    private val reserved = AtomicLong()

    fun reserve(bytes: Long) {
        if (reserved.get() + bytes > budget.total) throw tooLarge("Request needs more memory than the server has for requests")
        if (!budget.tryReserve(bytes)) throw ServerBusy("No memory free for the request")
        reserved.addAndGet(bytes)
    }

    fun releaseAll() = budget.release(reserved.getAndSet(0))
}

private val CallBytesKey = AttributeKey<CallBytes>("tortuga.callBytes")

/**
 * Reserves [bytes] more of the budget for this call until it ends: 503 [ServerBusy] when there is no room, 413 when the
 * budget could never hold them.
 */
fun ApplicationCall.reserveBytes(bytes: Long) = attributes[CallBytesKey].reserve(bytes)

/**
 * Returns what this call has reserved, before it ends: a call that stays open once its work is done (a notice stream,
 * which [SyncEvents][com.edde746.tortuga.server.service.SyncEvents] budgets) would hold it for hours.
 */
fun ApplicationCall.releaseBytes() = attributes.getOrNull(CallBytesKey)?.releaseAll()

/**
 * Admits every call against [budget] before anything reads its body, and returns what the call reserved once it ended.
 * [bodyLimit] is the largest body the call's path takes: a Content-Length past it is refused (413) at once, and a body
 * without one (chunked) counts as the limit, or [ByteBudget.ENGINE_BODY_BYTES] when that is more. On Netty, the
 * connection's [BodyGate] learns the outcome: an admitted call's body is read on, a refused call's connection closes
 * once it is answered.
 */
fun Application.installByteBudget(budget: ByteBudget, bodyLimit: (ApplicationCall) -> Long) {
    intercept(ApplicationCallPipeline.Plugins) {
        // On Netty, the connection's reads wait for this call's admission ([BodyGate]); another engine has none.
        val gate = (call as? NettyApplicationCall)?.context?.pipeline()?.get(BodyGate::class.java)
        val limit = bodyLimit(call)
        val bytes = CallBytes(budget)
        call.attributes.put(CallBytesKey, bytes)
        try {
            try {
                val declared = call.request.contentLength()
                if (declared != null && declared > limit) throw tooLarge("Request body too large")
                // A request without Content-Length and without Transfer-Encoding has no body (HTTP/1.1).
                val length = declared ?: when {
                    call.request.headers.contains(HttpHeaders.TransferEncoding) -> maxOf(limit, ByteBudget.ENGINE_BODY_BYTES)
                    else -> 0
                }
                bytes.reserve(ByteBudget.CALL_BYTES + ByteBudget.BODY_COST * length)
            } catch (e: Throwable) {
                gate?.refused()
                throw e
            }
            gate?.admitted()
            proceed()
        } finally {
            bytes.releaseAll()
        }
    }
}
