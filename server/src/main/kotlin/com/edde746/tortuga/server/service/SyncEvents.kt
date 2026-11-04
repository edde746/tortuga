package com.edde746.tortuga.server.service

import com.edde746.tortuga.sync.api.SyncProtocol
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The account change notice streams open on this server ([SyncPaths.SYNC_EVENTS][com.edde746.tortuga.sync.api.SyncPaths.SYNC_EVENTS],
 * SyncEventRoutes), by account. Notices don't reach another server's streams: one server per database.
 *
 * - Every committed write that changes what a pull answers calls [changed] ([com.edde746.tortuga.server.db.Tx.afterCommit]):
 *   each open stream of the account but those of the session that wrote hears the account's sync version. A stream
 *   holds the highest version it has yet to send, never a queue: notices that come faster than it sends fold into one.
 * - A session that ends (revoked, signed out, evicted, expired: [endSession]) or a deleted account ([endAccount]) loses
 *   its stream at once. One stream per session: a new one ends the session's older one, a reconnect over a connection
 *   whose end the server hasn't seen.
 * - A stream lasts no longer than the access token it opened with ([Stream.endsAt]): the janitor's round ends it once
 *   that has expired ([endExpired]), and the app opens another with its current token.
 * - At most [maxStreams] at once, counting those still authenticating: a place is taken first ([tryAdmit]), and given
 *   back by [leave] if no stream opens, or by its stream's [close].
 * - One job ([start]) asks every stream for a keepalive each [keepalive].
 */
class SyncEvents(val maxStreams: Int, private val keepalive: Duration = SyncProtocol.EVENTS_KEEPALIVE_MILLIS.milliseconds) {
    /**
     * One open stream of [sessionId] on [accountId], read by its writer only ([awaitWork], [take]), until [endsAt], when
     * the access token it opened with expires. While its connection still holds the last write ([hold]), notices and
     * keepalives only mark what is due and wake no one: a client that stops reading costs a stream nothing per notice.
     * Its connection wakes it once it has sent what it held ([resume]).
     */
    class Stream(val accountId: UUID, val sessionId: UUID, val endsAt: Instant) {
        /** The highest version not yet sent, or [NONE]: an account that never pushed is at 0, a version like any other. */
        private val version = AtomicLong(NONE)

        @Volatile
        private var keepaliveDue = false

        @Volatile
        private var held = false

        /** Wakes the writer; closed when the stream ends. */
        private val work = Channel<Unit>(Channel.CONFLATED)

        /** [close] has run. */
        internal val closed = AtomicBoolean()

        internal fun changed(version: Long) {
            this.version.accumulateAndGet(version, ::maxOf)
            if (!held) work.trySend(Unit)
        }

        internal fun keepalive() {
            keepaliveDue = true
            if (!held) work.trySend(Unit)
        }

        /**
         * The writer can't write yet: until [resume], what comes waits unannounced. The writer checks its connection
         * again after this, since a [resume] just before it wakes no one.
         */
        fun hold() {
            held = true
        }

        /** The connection has sent what it held: a held writer looks again. */
        fun resume() {
            if (!held) return
            held = false
            work.trySend(Unit)
        }

        /** Ends the stream: its writer stops and the response ends. Any thread, any number of times. */
        fun end() {
            work.close()
        }

        /** Waits until there may be something to send; false once the stream has ended. */
        suspend fun awaitWork(): Boolean = work.receiveCatching().isSuccess

        /**
         * What to send now: the highest version not yet sent, [KEEPALIVE] when only a keepalive is due (a notice counts
         * as one), null for nothing.
         */
        fun take(): Long? {
            val next = version.getAndSet(NONE)
            val due = keepaliveDue
            keepaliveDue = false
            return next.takeIf { it != NONE } ?: KEEPALIVE.takeIf { due }
        }
    }

    private val byAccount = ConcurrentHashMap<UUID, Array<Stream>>()

    private val admitted = AtomicInteger()

    /** Streams open or authenticating. */
    val size: Int get() = admitted.get()

    /** Takes a place for a stream; false when all [maxStreams] are taken. */
    fun tryAdmit(): Boolean {
        while (true) {
            val current = admitted.get()
            if (current >= maxStreams) return false
            if (admitted.compareAndSet(current, current + 1)) return true
        }
    }

    /** Gives back a place [tryAdmit] took for a stream that never opened. */
    fun leave() {
        admitted.decrementAndGet()
    }

    /** Registers [stream], which holds a place from [tryAdmit]; the session's older stream, if any, ends. */
    fun open(stream: Stream) {
        var older: Stream? = null
        byAccount.compute(stream.accountId) { _, streams ->
            older = streams?.firstOrNull { it.sessionId == stream.sessionId }
            ((streams?.filter { it !== older } ?: emptyList()) + stream).toTypedArray()
        }
        older?.end()
    }

    /** Unregisters [stream] and gives back its place: once, whichever of its writer and its call gets here first. */
    fun close(stream: Stream) {
        if (!stream.closed.compareAndSet(false, true)) return
        byAccount.computeIfPresent(stream.accountId) { _, streams ->
            streams.filter { it !== stream }.toTypedArray().takeIf { it.isNotEmpty() }
        }
        admitted.decrementAndGet()
    }

    /** [accountId]'s sync version is [version] after a change that session [from] committed: its other streams hear. */
    fun changed(accountId: UUID, from: UUID, version: Long) {
        byAccount[accountId]?.forEach { if (it.sessionId != from) it.changed(version) }
    }

    fun endSession(accountId: UUID, sessionId: UUID) {
        byAccount[accountId]?.forEach { if (it.sessionId == sessionId) it.end() }
    }

    fun endAccount(accountId: UUID) {
        byAccount[accountId]?.forEach { it.end() }
    }

    /** Ends every stream whose access token has expired by [now]. */
    fun endExpired(now: Instant) {
        for (streams in byAccount.values) streams.forEach { if (!it.endsAt.isAfter(now)) it.end() }
    }

    /** The keepalive job, for as long as [scope] lives. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            delay(keepalive)
            for (streams in byAccount.values) streams.forEach { it.keepalive() }
        }
    }

    companion object {
        /** [Stream.take]'s answer when only a keepalive is due; never a version, which starts at 0. */
        const val KEEPALIVE = -1L

        /** A stream's pending version when no notice waits. */
        private const val NONE = -1L
    }
}
