@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.crypto.cryptoProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The download, disk copy and daily check of a dataset, against a fake of a GitHub repository's releases (the release's
 * tag, checksum and file as such: [CachedDatasetReleaseTest]), and the copy a start brings up to date
 * ([CachedDataset.updateDiskCopy]) without loading it.
 */
class CachedDatasetTest {
    private val directory = Path(SystemTemporaryDirectory, "tortuga-datasets-" + Random.nextLong().toULong())
    private val github = Releases()
    private val clock = MutableClock()

    /** The copy's text each time the dataset opened one. */
    private val opened = mutableListOf<String>()

    @AfterTest
    fun deleteDirectory() {
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun downloadsOnceThenReadsTheDiskCopy() = runTest {
        assertEquals("rows v1", dataset().get())
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), github.requests)
        assertEquals("rows v1", read(FILE))

        // A relaunch the same day reads the copy and asks nothing.
        clock.advance(CachedDataset.REFRESH_INTERVAL - 1.milliseconds)
        val relaunched = dataset()
        assertEquals("rows v1", relaunched.get())
        relaunched.refreshIfDue()
        assertEquals(3, github.requests.size)
    }

    @Test
    fun aFailedDownloadIsNotKeptSoTheNextLookupTriesAgain() = runTest {
        github.failing = true
        val data = dataset()
        assertNull(data.get())
        assertNull(data.get())
        assertEquals(listOf(LATEST, LATEST), github.requests)
        assertFalse(exists(FILE))

        github.failing = false
        assertEquals("rows v1", data.get())
    }

    @Test
    fun concurrentLookupsShareOneDownload() = runTest {
        val data = dataset()
        val results = (1..5).map { async { data.get() } }.awaitAll()
        assertEquals(List(5) { "rows v1" }, results)
        assertEquals(3, github.requests.size)
    }

    @Test
    fun anUnreadableCopyIsDeletedAndDownloadedAgain() = runTest {
        write(FILE, "corrupt")
        val data = dataset()
        assertNull(data.get())
        assertEquals(0, github.requests.size)
        assertEquals("rows v1", data.get())
        assertEquals(3, github.requests.size)
    }

    @Test
    fun anEmptyCopyOnDiskIsDeletedAndDownloadedAgain() = runTest {
        // As a build that moved a download into place before opening it left one: empty, its check today.
        write(FILE, "")
        write("$FILE.state", """{"version":"v0","lastCheckMillis":${clock.now().toEpochMilliseconds()}}""")
        val data = dataset()
        assertNull(data.get())
        assertEquals(0, github.requests.size)
        assertFalse(exists(FILE))
        assertEquals("rows v1", data.get())
        assertEquals(3, github.requests.size)
    }

    @Test
    fun anEmptyFirstDownloadIsDroppedAndTheFixedFileComesADayLater() = runTest {
        unusableFirstDownloadWaitsADay("")
    }

    @Test
    fun anUnreadableFirstDownloadIsDroppedAndTheFixedFileComesADayLater() = runTest {
        unusableFirstDownloadWaitsADay("corrupt")
    }

    @Test
    fun aCopyGoneFromTheCacheIsDownloadedAgainAtOnce() = runTest {
        dataset().get()
        // A cache directory can lose its files one by one: the state of today's check stays.
        SystemFileSystem.delete(Path(directory, FILE))
        assertEquals("rows v1", dataset().get())
        assertEquals(6, github.requests.size)
    }

    @Test
    fun aLoadNeverWaitsForTheScopesOwnDispatcher() = runTest {
        // The scope's dispatcher stands for the app's main thread, busy drawing: it runs nothing it is given.
        val busy = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        }
        val data = dataset(CoroutineScope(busy))
        assertEquals("rows v1", withTimeout(1.seconds) { data.get() })
    }

    @Test
    fun aStartDownloadsTheCopyWithoutKeepingItsIndex() = runTest {
        val data = dataset()
        data.updateDiskCopy()
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), github.requests)
        assertEquals("rows v1", read(FILE))
        assertEquals(listOf("rows v1"), opened, "opened once, to check it")

        // The first lookup opens the copy on disk again, and asks nothing.
        assertEquals("rows v1", data.get())
        advanceUntilIdle()
        assertEquals(listOf("rows v1", "rows v1"), opened)
        assertEquals(3, github.requests.size)
    }

    @Test
    fun aStartChecksTheCopyDaily() = runTest {
        dataset().updateDiskCopy()
        // Within the day: nothing asked.
        clock.advance(CachedDataset.REFRESH_INTERVAL - 1.milliseconds)
        dataset().updateDiskCopy()
        assertEquals(3, github.requests.size)

        // A day on, an unchanged tag downloads nothing and counts as today's check.
        clock.advance(1.milliseconds)
        dataset().updateDiskCopy()
        dataset().updateDiskCopy()
        assertEquals(listOf(LATEST), github.requests.drop(3))
        assertEquals(1, opened.size)

        // A new release is downloaded and checked.
        github.publish("v2", "rows v2")
        clock.advance(CachedDataset.REFRESH_INTERVAL)
        val data = dataset()
        data.updateDiskCopy()
        assertEquals(listOf(LATEST, "v2/SHA256SUMS", "v2/$FILE"), github.requests.drop(4))
        assertEquals("rows v2", read(FILE))
        assertEquals("rows v2", data.get())
        assertEquals(7, github.requests.size)
    }

    @Test
    fun aLookupDuringTheStartsDownloadWaitsForItAndDownloadsNothing() = runTest {
        val data = dataset()
        val file = CompletableDeferred<Unit>()
        github.holdFile = file
        launch { data.updateDiskCopy() }
        runCurrent()
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), github.requests, "the file is downloading")

        val lookup = async { data.get() }
        runCurrent()
        assertFalse(lookup.isCompleted)
        file.complete(Unit)
        assertEquals("rows v1", lookup.await())
        advanceUntilIdle()
        assertEquals(3, github.requests.size)
        assertEquals(listOf("rows v1", "rows v1"), opened, "the download's check, then the lookup's load")
        assertTrue(exists(FILE))
    }

    private suspend fun TestScope.unusableFirstDownloadWaitsADay(body: String) {
        github.publish("v0", body)
        val data = dataset()
        assertNull(data.get())
        assertFalse(exists(FILE))
        assertFalse(exists("$FILE.part"))

        // The answer counts as today's check: no lookup downloads it again before then, a relaunch's neither.
        github.publish("v1", "rows v1")
        clock.advance(CachedDataset.REFRESH_INTERVAL - 1.milliseconds)
        assertNull(data.get())
        assertNull(dataset().get())
        assertEquals(3, github.requests.size)

        clock.advance(1.milliseconds)
        assertEquals("rows v1", data.get())
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), github.requests.drop(3))
        assertEquals("rows v1", read(FILE))
    }

    /**
     * A dataset whose loads, checks and requests all run on the test's scheduler, so `advanceUntilIdle` waits for
     * them too (a mock engine on its own IO dispatcher could still be answering when the scheduler goes idle).
     */
    private fun TestScope.dataset(scope: CoroutineScope = this): CachedDataset<String> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CachedDataset(
            label = "Test",
            fileName = FILE,
            repository = Releases.REPOSITORY,
            asset = FILE,
            accept = "text/plain",
            http = HttpClient(github.engine(dispatcher)) { install(HttpTimeout) },
            directory = directory,
            fileSystem = SystemFileSystem,
            clock = clock,
            scope = scope,
            ioDispatcher = dispatcher,
            parseDispatcher = dispatcher,
            open = { path -> read(path.name).also { require(it != "corrupt") { "unreadable" }; opened += it } },
            isEmpty = String::isEmpty,
            summary = { it },
        )
    }

    private fun read(name: String) = SystemFileSystem.source(Path(directory, name)).buffered().use { it.readString() }

    private fun write(name: String, text: String) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.sink(Path(directory, name)).buffered().use { it.writeString(text) }
    }

    private fun exists(name: String) = SystemFileSystem.exists(Path(directory, name))

    private companion object {
        const val LATEST = "releases/latest"
        const val FILE = "data.txt"
    }

    /**
     * A repository's releases as GitHub serves them: `releases/latest` redirects (302) to the latest release's tag, and
     * `releases/download/<tag>/` has each release's file and its `SHA256SUMS`. While [failing] every request is
     * answered 503; while [holdFile] is incomplete the file's answer waits for it. [requests] is each path asked for
     * under the repository, a download's without `releases/download/` (`v1/SHA256SUMS`), in order.
     */
    private class Releases {
        val requests = mutableListOf<String>()
        var failing = false
        var holdFile: CompletableDeferred<Unit>? = null
        private val releases = mutableMapOf<String, String>()
        private var latest = ""

        init {
            publish("v1", "rows v1")
        }

        /** [tag] becomes the latest release, its file [text]. */
        fun publish(tag: String, text: String) {
            releases[tag] = text
            latest = tag
        }

        fun engine(dispatcher: CoroutineDispatcher) = MockEngine.create {
            this.dispatcher = dispatcher
            addHandler { request -> handle(request) }
        }

        private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            val path = request.url.toString().removePrefix("$REPOSITORY/")
            requests += path.removePrefix("releases/download/")
            if (failing) return respondError(HttpStatusCode.ServiceUnavailable)
            if (path == LATEST) return respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "$REPOSITORY/releases/tag/$latest"))
            val (tag, file) = path.removePrefix("releases/download/").split('/', limit = 2)
            val text = releases[tag] ?: return respondError(HttpStatusCode.NotFound)
            return when (file) {
                "SHA256SUMS" -> respond("${sha256(text)}  $FILE\n", HttpStatusCode.OK)
                FILE -> {
                    holdFile?.await()
                    respond(text, HttpStatusCode.OK)
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        private fun sha256(text: String) = cryptoProvider.get(SHA256).hasher().hashBlocking(text.encodeToByteArray()).toHexString()

        companion object {
            const val REPOSITORY = "https://github.test/owner/repo"
        }
    }
}

private class MutableClock : Clock {
    private var now = Instant.fromEpochMilliseconds(1_790_000_000_000)

    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}
