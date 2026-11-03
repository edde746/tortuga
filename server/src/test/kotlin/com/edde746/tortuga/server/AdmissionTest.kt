package com.edde746.tortuga.server

import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.http.ServerBusy
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.captureLogs
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A server whose database connections are all busy, or whose database can't be reached, sheds requests. */
class AdmissionTest {
    @Test
    fun aFullPoolAnswersBusyWithRetryAfterAndRecovers() = serverTest {
        val token = signIn("alice").session.accessToken
        val taken = CountDownLatch(TestPostgres.POOL_SIZE)
        val release = CountDownLatch(1)
        coroutineScope {
            // Every connection held by a transaction that doesn't end until released.
            val holders = List(TestPostgres.POOL_SIZE) {
                async(Dispatchers.IO) {
                    TestPostgres.database.transaction {
                        taken.countDown()
                        release.await()
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) { taken.await() }
                val busy = get(SyncPaths.ACCOUNT, token)
                busy.assertError(HttpStatusCode.ServiceUnavailable, ApiErrorCode.SERVER_BUSY)
                assertEquals("5", busy.headers[HttpHeaders.RetryAfter])
            } finally {
                release.countDown()
            }
            holders.awaitAll()
        }
        get(SyncPaths.ACCOUNT, token).ok<AccountInfo>()
    }

    @Test
    fun healthChecksAskTheDatabaseAtMostOnceInFiveSeconds() = serverTest {
        assertEquals(HttpStatusCode.OK, get("/healthz").status)
        val taken = CountDownLatch(TestPostgres.POOL_SIZE)
        val release = CountDownLatch(1)
        coroutineScope {
            // Every connection taken: a check that asked the database now would get 503.
            val holders = List(TestPostgres.POOL_SIZE) {
                async(Dispatchers.IO) {
                    TestPostgres.database.transaction {
                        taken.countDown()
                        release.await()
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) { taken.await() }
                assertEquals(HttpStatusCode.OK, get("/healthz").status)
            } finally {
                release.countDown()
            }
            holders.awaitAll()
        }
    }

    @Test
    fun aFullQueueRefusesAtOnce(): Unit = runBlocking {
        val db = TestPostgres.database
        val taken = CountDownLatch(TestPostgres.POOL_SIZE)
        val release = CountDownLatch(1)
        coroutineScope {
            val holders = List(TestPostgres.POOL_SIZE) {
                async(Dispatchers.IO) {
                    db.transaction {
                        taken.countDown()
                        release.await()
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) { taken.await() }
                // Undispatched: each is in the queue when async returns.
                val queued = List(TestPostgres.POOL_SIZE * Database.WAITING_PER_CONNECTION) {
                    async(start = CoroutineStart.UNDISPATCHED) { runCatching { db.transaction { } } }
                }
                val started = TimeSource.Monotonic.markNow()
                assertFailsWith<ServerBusy> { db.transaction { } }
                assertTrue(started.elapsedNow() < Database.ADMISSION_WAIT / 2, "refused after ${started.elapsedNow()}")
                // The queue still drains once connections come free.
                release.countDown()
                assertTrue(queued.awaitAll().all { it.isSuccess })
            } finally {
                release.countDown()
            }
            holders.awaitAll()
        }
    }

    @Test
    fun anUnreachableDatabaseAnswersBusyAndIsLoggedOnce() {
        TestPostgres.relay().use { relay ->
            val db = TestPostgres.connect(1, relay)
            runBlocking { db.transaction { queryOne("SELECT 1") { it.getInt(1) } } }
            val logs = captureLogs {
                runBlocking {
                    // The database goes away under a transaction: its connection breaks.
                    assertFailsWith<ServerBusy> { db.transaction { relay.cut(); queryOne("SELECT 1") { it.getInt(1) } } }
                    // And no connection comes within the pool's wait.
                    assertFailsWith<ServerBusy> { db.transaction { queryOne("SELECT 1") { it.getInt(1) } } }
                }
            }
            val warnings = logs.filter { "database can't be reached" in it }
            assertEquals(1, warnings.size, "$logs")
            assertTrue(warnings.none { '\n' in it }, "one line, no stack trace: $warnings")
        }
    }

    // A wait's timeout and the caller's cancellation are asynchronous: either can land after the connection came free
    // and before the transaction saw it. Far more callers than connections, each giving up after a millisecond, make
    // most waits end as a connection comes free; afterwards every connection is still there.

    @Test
    fun aWaitThatTimesOutAsAConnectionComesFreeGivesItBack(): Unit = runBlocking {
        val db = TestPostgres.connect(SMALL_POOL)
        val served = AtomicInteger()
        val shed = AtomicInteger()
        load {
            try {
                db.transaction(wait = 1.milliseconds) { queryOne("SELECT 1") { it.getInt(1) } }
                served.incrementAndGet()
            } catch (_: ServerBusy) {
                shed.incrementAndGet()
            }
        }
        assertTrue(served.get() > 0 && shed.get() > 0, "served $served, shed $shed")
        assertEveryConnectionFree(db)
    }

    @Test
    fun aCallerCancelledAsAConnectionComesFreeGivesItBack(): Unit = runBlocking {
        val db = TestPostgres.connect(SMALL_POOL)
        val served = AtomicInteger()
        val cancelled = AtomicInteger()
        load {
            val done = withTimeoutOrNull(1.milliseconds) { db.transaction { queryOne("SELECT 1") { it.getInt(1) } } }
            (if (done == null) cancelled else served).incrementAndGet()
        }
        assertTrue(served.get() > 0 && cancelled.get() > 0, "served $served, cancelled $cancelled")
        assertEveryConnectionFree(db)
    }

    /** [CALLERS] callers each running [attempt] over and over for [LOAD_TIME]. */
    private suspend fun load(attempt: suspend () -> Unit) = withContext(Dispatchers.Default) {
        val end = TimeSource.Monotonic.markNow() + LOAD_TIME
        repeat(CALLERS) { launch { while (end.hasNotPassedNow()) attempt() } }
    }

    /** As many transactions as [db] has connections hold one each at once. */
    private suspend fun assertEveryConnectionFree(db: Database) = coroutineScope {
        val inside = CountDownLatch(SMALL_POOL)
        repeat(SMALL_POOL) {
            launch(Dispatchers.IO) {
                db.transaction(wait = 5.seconds) {
                    inside.countDown()
                    assertTrue(inside.await(5, TimeUnit.SECONDS), "a connection never came back")
                }
            }
        }
    }

    private companion object {
        const val SMALL_POOL = 2
        const val CALLERS = 32
        val LOAD_TIME = 3.seconds
    }
}
