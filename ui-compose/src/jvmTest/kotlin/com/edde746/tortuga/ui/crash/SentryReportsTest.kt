package com.edde746.tortuga.ui.crash

import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.Startup
import com.edde746.tortuga.app.SystemInputs
import com.edde746.tortuga.crash.CrashReports
import com.edde746.tortuga.crash.crashReports
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.ISerializer
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The key below is made up; it only has the shape of an addon's account key. */
@OptIn(ExperimentalCoroutinesApi::class)
class SentryReportsTest {
    private val key = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST"
    private val addon = "https://addon.example/account=$key/stream/movie/tt0111161.json"
    private val home = System.getProperty("user.home")

    /** Each envelope Sentry would have sent, as its wire text. */
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class RecordingTransport(private val serializer: ISerializer, private val sent: MutableList<String>) : ITransport {
        override fun send(envelope: SentryEnvelope, hint: Hint) {
            sent += ByteArrayOutputStream().also { serializer.serialize(envelope, it) }.toString(Charsets.UTF_8)
        }

        override fun flush(timeoutMillis: Long) = Unit
        override fun getRateLimiter(): RateLimiter? = null
        override fun close(isRestarting: Boolean) = Unit
        override fun close() = Unit
    }

    /** The product's secrets, in memory. */
    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, ByteArray>()

        override suspend fun get(key: String): ByteArray? = values[key]

        override suspend fun put(key: String, value: ByteArray) {
            values[key] = value
        }

        override suspend fun delete(key: String) {
            values.remove(key)
        }
    }

    /** The app graph's scope (its settings' flows) runs on Main. */
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
        // The system's settings, as a platform reports them before it builds a core.
        SystemInputs.report(listOf("en"), region = null, captions = false, audioDescriptions = false)
    }

    @AfterTest
    fun closeSentry() {
        Sentry.close()
        Dispatchers.resetMain()
        main.close()
    }

    /** Starts Sentry on [reports] with every envelope recorded in [sent]; the follow ends with the returned job. */
    private suspend fun CoroutineScope.follow(reports: CrashReports): Job {
        val started = CompletableDeferred<Unit>()
        val follow = SentryReports.follow(reports, this) { configure ->
            Sentry.init { options ->
                configure(options)
                options.flushTimeoutMillis = 200
                options.setTransportFactory { o, _ -> RecordingTransport(o.serializer, sent) }
            }
            started.complete(Unit)
        }
        withTimeout(10_000) { started.await() }
        return follow
    }

    @Test
    fun aCrashReportHoldsNoAddonPathUserOrHomeDirectory() = runBlocking {
        AppBootstrap.local(DatabaseFactory.inMemory(), BuildFlavor.SIDELOADED, "1.2.3", DeviceDescriptor("Test", DevicePlatform.WINDOWS)) {}.use { boot ->
            val follow = follow(boot.crashReports("http://public@127.0.0.1:9/1", CrashReports.DEVELOPMENT))

            // The addon URL everywhere an app could put it: what was open, a request, the user, a tag, an extra, the
            // crashing thread's name and its exception, beside a file in the user's home directory.
            Sentry.addBreadcrumb("opened $addon")
            Sentry.addBreadcrumb(Breadcrumb.http(addon, "GET", 200))
            Sentry.addBreadcrumb(Breadcrumb("player").apply { setData("stream", addon) })
            Sentry.setUser(User().apply { id = "profile-42"; ipAddress = "203.0.113.7" })
            Sentry.setTag("addon", addon)
            Sentry.setExtra("stream", addon)
            val crashing = Thread({ throw IllegalStateException("stream failed: $addon, cache $home/tortuga/cache") }, "loader $addon")
            crashing.start()
            crashing.join()
            follow.cancel()

            val events = sent.filter { "\"type\":\"event\"" in it }
            assertEquals(1, events.size, "one report of the one crash: ${sent.size} envelopes")
            val report = events.single()
            assertTrue("stream failed: https://addon.example/…" in report, report)
            for (secret in listOf(key, "account=", "tt0111161", "profile-42", "203.0.113.7", home)) {
                assertFalse(secret in report, "'$secret' reached the report: $report")
            }
        }
    }

    @Test
    fun aCoroutineTheAppSurvivedIsSentAsAnErrorScrubbedAsACrash(): Unit = runBlocking {
        val cache = Files.createTempDirectory("tortuga-test-cache")
        val device = DeviceDescriptor("Test", DevicePlatform.WINDOWS)
        try {
            AppBootstrap.product(DatabaseFactory.inMemory(), MemorySecrets(), BuildFlavor.SIDELOADED, "1.2.3", device, cache.toString(), Startup()).use { boot ->
                val follow = follow(boot.crashReports("http://public@127.0.0.1:9/1", CrashReports.PRODUCTION))

                // A store's coroutine that throws: the app runs on, and the exception goes out as an error, not as a crash.
                boot.graph.scope.launch { throw IllegalStateException("stream failed: $addon, cache $home/tortuga/cache") }.join()
                yield()
                follow.cancel()

                val events = sent.filter { "\"type\":\"event\"" in it }
                assertEquals(1, events.size, "one report of the one failure: ${sent.size} envelopes")
                val report = events.single()
                assertFalse("\"level\":\"fatal\"" in report, report)
                assertTrue("stream failed: https://addon.example/…" in report, report)
                for (secret in listOf(key, "account=", "tt0111161", home)) {
                    assertFalse(secret in report, "'$secret' reached the report: $report")
                }
            }
        } finally {
            cache.toFile().deleteRecursively()
        }
    }
}
