package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.crypto.cryptoProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A dataset's snapshot, written on IO threads while a check may put a new release in place. */
class CachedDatasetSnapshotTest {
    private val directory = Path(SystemTemporaryDirectory, "tortuga-snapshot-" + Random.nextLong().toULong())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val now = Instant.fromEpochMilliseconds(1_790_000_000_000)

    /** The old copy's snapshot write has begun, and may go on. */
    private val writingOld = CountDownLatch(1)
    private val goOn = CountDownLatch(1)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun aLoadsSnapshotNeverReplacesTheOneOfAReleaseACheckPutInPlaceMeanwhile() = runBlocking {
        // Yesterday's copy, of release v1, and no snapshot yet; v2 is out.
        write(FILE, "rows v1")
        write("$FILE.state", """{"version":"v1","lastCheckMillis":${(now - 2.days).toEpochMilliseconds()}}""")
        val data = dataset()

        assertEquals("rows v1", data.get())
        assertTrue(writingOld.await(5, TimeUnit.SECONDS), "the load writes its copy's snapshot")
        // A start's check meanwhile, on another thread: it downloads v2 and puts it in place.
        val check = scope.async { data.updateDiskCopy() }
        withTimeoutOrNull(1.seconds) { check.await() }
        goOn.countDown()
        check.await()
        scope.coroutineContext.job.children.toList().joinAll()

        assertEquals("rows v2", read(FILE))
        assertEquals("rows v2", read("$FILE.packed"), "the snapshot is the copy's")
        assertEquals("rows v2", dataset().get(), "a relaunch reads the new release")
    }

    private fun dataset() = CachedDataset(
        label = "Test",
        fileName = FILE,
        repository = REPOSITORY,
        asset = FILE,
        accept = "text/plain",
        http = HttpClient(github()) { install(HttpTimeout) },
        directory = directory,
        fileSystem = SystemFileSystem,
        clock = object : Clock {
            override fun now() = this@CachedDatasetSnapshotTest.now
        },
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        parseDispatcher = Dispatchers.IO,
        open = { path -> read(path.name) },
        isEmpty = String::isEmpty,
        summary = { it },
        maxBytes = 1024,
        snapshot = PackedForm(
            write = { text, sink ->
                if (text == "rows v1") {
                    writingOld.countDown()
                    goOn.await(5, TimeUnit.SECONDS)
                }
                sink.writeString(text)
            },
            read = { it.readString() },
        ),
    )

    /** GitHub's releases, v2 the latest. */
    private fun github() = MockEngine { request ->
        when (request.url.toString().removePrefix("$REPOSITORY/")) {
            "releases/latest" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "$REPOSITORY/releases/tag/v2"))
            "releases/download/v2/SHA256SUMS" -> respond("${sha256("rows v2")}  $FILE\n", HttpStatusCode.OK)
            "releases/download/v2/$FILE" -> respond("rows v2", HttpStatusCode.OK)
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun sha256(text: String) = cryptoProvider.get(SHA256).hasher().hashBlocking(text.encodeToByteArray()).toHexString()

    private fun read(name: String) = SystemFileSystem.source(Path(directory, name)).buffered().use { it.readString() }

    private fun write(name: String, text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(Path(directory, name)).buffered().use { it.writeString(text) }
    }

    private companion object {
        const val REPOSITORY = "https://github.test/owner/repo"
        const val FILE = "data.txt"
    }
}
