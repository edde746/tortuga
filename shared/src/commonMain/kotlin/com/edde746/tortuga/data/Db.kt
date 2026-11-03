package com.edde746.tortuga.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.edde746.tortuga.data.db.TortugaDatabase
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.Backoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What every repository shares: the database, the I/O context, the write lock and this device's clock.
 *
 * Writes run one at a time ([write]): each is a read-modify-write transaction, and SQLite can't upgrade two
 * concurrent read transactions to writes without failing one of them. Reads and query flows run concurrently. Every
 * read ([read]) and query flow ([observeList], [observeOneOrNull], [observeOne]) is read again when it finds the
 * database busy ([readAgain]); any other failure goes to its caller.
 */
internal class Db(
    val database: TortugaDatabase,
    val io: CoroutineContext,
    val wallClock: () -> Long,
    private val maxDriftMillis: Long,
) {
    private val writeLock = Mutex()

    // Loaded by the first write and only touched under writeLock.
    private var clock: HybridClock? = null

    /**
     * Runs [block] in one transaction, after every earlier write. [at] is the write's wall time ([Tx.now], epoch
     * milliseconds) when the caller gives one; the HLC stays this device's ([Tx.tick]).
     */
    suspend fun <R> write(at: Long? = null, block: Tx.() -> R): R = writeLock.withLock {
        withContext(io) {
            database.transactionWithResult {
                val clock = clock ?: loadClock().also { clock = it }
                val before = clock.last
                val result = Tx(this@Db, clock, at).block()
                if (clock.last != before) database.syncStateQueries.saveClock(clock.node, clock.last.toString())
                result
            }
        }
    }

    /** [block] on [io]; one that finds the database busy runs again ([readAgain]). */
    suspend fun <R> read(block: TortugaDatabase.() -> R): R {
        var failures = 0
        while (true) {
            try {
                return withContext(io) { database.block() }
            } catch (e: Exception) {
                if (!readAgain(e, ++failures)) throw e
            }
        }
    }

    /** [query]'s rows, now and after every change to its tables; a read that finds the database busy runs again. */
    fun <T : Any> observeList(query: Query<T>): Flow<List<T>> = query.asFlow().mapToList(io).busyRetried()

    /** [query]'s row or null, now and after every change to its tables; a read that finds the database busy runs again. */
    fun <T : Any> observeOneOrNull(query: Query<T>): Flow<T?> = query.asFlow().mapToOneOrNull(io).busyRetried()

    /** [query]'s one row, now and after every change to its tables; a read that finds the database busy runs again. */
    fun <T : Any> observeOne(query: Query<T>): Flow<T> = query.asFlow().mapToOne(io).busyRetried()

    /**
     * A query flow that subscribes again when a read finds the database busy ([readAgain], the busy reads counted since
     * its last value), so its readers (a screen, the downloads engine) see the next change instead of keeping the last
     * rows for good. Any other failure ends it with that error.
     */
    private fun <T> Flow<T>.busyRetried(): Flow<T> = flow {
        var failures = 0
        emitAll(onEach { failures = 0 }.retryWhen { cause, _ -> readAgain(cause, ++failures) })
    }

    /**
     * Whether a read that failed with [cause], [failures] times in a row, runs again: only when it found the database
     * busy ([isDatabaseBusy]: another connection's lock held past the busy timeout while the disk is saturated), at most
     * [BUSY_RETRIES] times, after [Backoff.equalJitter] of [BUSY_RETRY_MIN_MILLIS] (logged). Any other failure, a busy
     * one past those, and a cancellation are the caller's.
     */
    private suspend fun readAgain(cause: Throwable, failures: Int): Boolean {
        if (cause is CancellationException || !cause.isDatabaseBusy() || failures > BUSY_RETRIES) return false
        val wait = Backoff.equalJitter(failures, BUSY_RETRY_MIN_MILLIS, BUSY_RETRY_MAX_MILLIS)
        Log.w(TAG, cause) { "a database read found the database busy; reading again in $wait ms ($failures of $BUSY_RETRIES)" }
        delay(wait)
        return true
    }

    private fun loadClock(): HybridClock {
        val saved = database.syncStateQueries.selectClock().executeAsOneOrNull()
        if (saved != null) return HybridClock(saved.node_id, Hlc.parse(saved.last_hlc), wallClock, maxDriftMillis)
        val clock = HybridClock(Hlc.newNodeId(), null, wallClock, maxDriftMillis)
        database.syncStateQueries.saveClock(clock.node, clock.last.toString())
        return clock
    }

    private companion object {
        const val TAG = "db"
        const val BUSY_RETRIES = 5
        const val BUSY_RETRY_MIN_MILLIS = 1_000L
        const val BUSY_RETRY_MAX_MILLIS = 30_000L
    }
}

/** A write transaction: the queries plus this device's clock, and the write's wall time when its caller set one. */
internal class Tx(private val db: Db, private val clock: HybridClock, private val at: Long?) {
    val q: TortugaDatabase get() = db.database

    /** A fresh timestamp for a local write. */
    fun tick(): Hlc = clock.send()

    /** Folds in a remote timestamp; see [HybridClock.receive]. */
    fun receive(remote: Hlc): Hlc = clock.receive(remote)

    fun now(): Long = at ?: db.wallClock()
}
