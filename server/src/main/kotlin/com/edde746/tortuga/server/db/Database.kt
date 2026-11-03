package com.edde746.tortuga.server.db

import com.edde746.tortuga.server.http.ServerBusy
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

/**
 * Postgres access: a Hikari pool, Flyway migrations (`src/main/resources/db/migration`) and blocking JDBC transactions
 * run on threads of their own, one per pooled connection.
 *
 * Why plain JDBC and Flyway: the schema is a handful of tables whose queries rely on Postgres features (`FOR UPDATE`
 * row locks that order sync versions, `ON CONFLICT`, `RETURNING`, `= ANY(array)`, `bytea`), which read most clearly
 * as SQL. Flyway's versioned SQL files are the migration format self-hosters know, it validates checksums at startup,
 * and it needs no code generation step.
 */
class Database private constructor(private val dataSource: HikariDataSource) {
    /**
     * Where transactions run: as many threads as the pool has connections, so blocking JDBC never takes a thread of
     * `Dispatchers.IO`, which the HTTP engine serves every request on (`/healthz` too).
     */
    private val dispatcher = AtomicInteger().let { threads ->
        Executors.newFixedThreadPool(dataSource.maximumPoolSize) { task ->
            Thread(task, "tortuga-db-${threads.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * Admission: one permit per connection, and at most [WAITING_PER_CONNECTION] transactions per connection waiting for
     * one. A transaction that finds the queue full, or waits longer than [ADMISSION_WAIT], fails with [ServerBusy],
     * so an overloaded server sheds requests at once instead of holding them (and their memory) without bound.
     */
    private val permits = Semaphore(dataSource.maximumPoolSize)
    private val waiting = AtomicInteger()
    private val maxWaiting = dataSource.maximumPoolSize * WAITING_PER_CONNECTION

    /** False from a transaction that found the database unreachable until one commits again: each change is logged once. */
    private val reachable = AtomicBoolean(true)

    /**
     * Runs [block] in one transaction at the pool's isolation (read committed): committed if it returns, rolled back if
     * it throws; once committed, what [block] left for afterwards runs ([Tx.afterCommit]). [block] can't suspend, so
     * nothing slower than the database (a tracker's token endpoint) runs while it holds a connection. A statement that
     * needs one snapshot of several tables reads them in one query.
     *
     * [wait] bounds the wait for a connection; [Duration.INFINITE] for a write that must not be shed, such as tokens a
     * service has just issued, which also waits behind a full queue.
     *
     * While the database can't be reached, a transaction fails with [ServerBusy] too, after the pool's 5 s wait for a
     * connection or when the one it has breaks ([isConnectionFailure]): its request gets 503 with Retry-After, not 500
     * and a stack trace. The first such failure is logged as a warning, and the first commit after it.
     */
    suspend fun <T> transaction(wait: Duration = ADMISSION_WAIT, block: Tx.() -> T): T {
        // Set with no suspension point after the permit is taken, and every exit after that releases it: a timeout or
        // the caller's cancellation can land after acquire() returned but before withTimeoutOrNull did, which then
        // answers null or throws with the permit taken.
        var acquired = permits.tryAcquire()
        try {
            if (!acquired) {
                val queued = waiting.incrementAndGet()
                try {
                    if (queued > maxWaiting && wait.isFinite()) throw ServerBusy(BUSY)
                    withTimeoutOrNull(wait) {
                        permits.acquire()
                        acquired = true
                    }
                } finally {
                    waiting.decrementAndGet()
                }
                if (!acquired) throw ServerBusy(BUSY)
            }
            // A cancelled caller still waits here for the block: cancellation doesn't stop blocking JDBC.
            return withContext(dispatcher) {
                try {
                    dataSource.connection.use { connection ->
                        val tx = Tx(connection)
                        val result = try {
                            tx.block().also { connection.commit() }
                        } catch (t: Throwable) {
                            try {
                                connection.rollback()
                            } catch (r: SQLException) {
                                // A broken connection can't roll back either; Postgres ends the transaction with it.
                                t.addSuppressed(r)
                            }
                            throw t
                        }
                        if (!reachable.get() && reachable.compareAndSet(false, true)) log.info("The database answers again")
                        tx.committed.forEach { it() }
                        result
                    }
                } catch (e: SQLException) {
                    if (!e.isConnectionFailure()) throw e
                    if (reachable.compareAndSet(true, false)) {
                        log.warn("The database can't be reached; requests get 503 until it answers: {}", e.toString())
                    }
                    throw ServerBusy(UNREACHABLE)
                }
            }
        } finally {
            if (acquired) permits.release()
        }
    }

    companion object {
        /** How long a transaction waits for a free connection before the request is answered 503. */
        val ADMISSION_WAIT = 2.seconds

        /** How many transactions may wait for each connection; past that, requests are answered 503 at once. */
        const val WAITING_PER_CONNECTION = 50

        private const val BUSY = "Every database connection is busy"

        private const val UNREACHABLE = "The database can't be reached"

        private val log = LoggerFactory.getLogger(Database::class.java)

        /** Postgres shutting down (admin, crash) or still starting: its connections end or aren't accepted yet. */
        private val SHUTDOWN_STATES = setOf("57P01", "57P02", "57P03")

        /**
         * True when the database couldn't be reached: no connection within the pool's wait (Hikari's
         * [SQLTransientConnectionException]), a connection that broke (SQLSTATE class 08) or one Postgres ended as it
         * shut down.
         */
        private fun SQLException.isConnectionFailure(): Boolean =
            this is SQLTransientConnectionException || sqlState?.startsWith("08") == true || sqlState in SHUTDOWN_STATES

        /** Opens a pool of [poolSize] connections and brings the schema up to date. */
        fun connect(url: String, user: String, password: String, poolSize: Int): Database {
            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                poolName = "tortuga"
                // Every connection stays out of autocommit and at Postgres's default isolation, so a transaction sends
                // its statements and its COMMIT and nothing else (no SET per transaction).
                isAutoCommit = false
                // Postgres may still be starting (compose): wait for it instead of failing at once.
                initializationFailTimeout = 60_000
                // A permit means a free connection; waiting longer than this means Postgres is unreachable.
                connectionTimeout = 5_000
                // Postgres cancels a statement after 10 s, and a socket silent for 30 s fails, so a stuck query can't
                // keep a connection and its thread.
                addDataSourceProperty("options", "-c statement_timeout=10s")
                addDataSourceProperty("socketTimeout", "30")
                // Keep row values (identities, record ids) out of exception messages, and so out of the logs.
                addDataSourceProperty("logServerErrorDetail", "false")
            }
            val dataSource = HikariDataSource(config)
            migrate(url, user, password)
            return Database(dataSource)
        }

        /** Migrates over a connection of its own, without the pool's statement timeout. */
        private fun migrate(url: String, user: String, password: String) {
            Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}

/** One open transaction. Parameters bind by type: `String`, `Int`, `Long`, `Boolean`, `ByteArray`, [UUID], [Instant]. */
class Tx(val connection: Connection) {
    internal val committed = ArrayList<() -> Unit>(0)

    /** Runs [action] once this transaction has committed, never if it rolls back: telling others of what it wrote. */
    fun afterCommit(action: () -> Unit) {
        committed += action
    }

    fun update(sql: String, vararg params: Any?): Int = prepare(sql, params).use { it.executeUpdate() }

    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        prepare(sql, params).use { statement ->
            statement.executeQuery().use { rows ->
                val out = ArrayList<T>()
                while (rows.next()) out += map(rows)
                out
            }
        }

    fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? = query(sql, *params, map = map).firstOrNull()

    /** Runs [sql] once per element of [rows] as one JDBC batch. */
    fun batch(sql: String, rows: List<Array<Any?>>) {
        if (rows.isEmpty()) return
        connection.prepareStatement(sql).use { statement ->
            for (row in rows) {
                bind(statement, row)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** A `bytea[]` parameter, for `= ANY(?)`. */
    fun byteaArray(values: Collection<ByteArray>): java.sql.Array = connection.createArrayOf("bytea", values.toTypedArray())

    private fun prepare(sql: String, params: Array<out Any?>): PreparedStatement =
        connection.prepareStatement(sql).also { bind(it, params) }

    private fun bind(statement: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                is Instant -> statement.setObject(position, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                else -> statement.setObject(position, value)
            }
        }
    }
}

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

/** True for a unique-constraint violation (SQLSTATE 23505), e.g. two sign-ins racing to create one identity. */
fun SQLException.isUniqueViolation(): Boolean = sqlState == "23505"
