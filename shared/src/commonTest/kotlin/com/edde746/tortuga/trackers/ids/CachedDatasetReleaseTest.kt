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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A dataset published as a GitHub repository's releases ([CachedDataset]): `releases/latest` redirects to
 * the latest release's tag, and each release has its file and its `SHA256SUMS` under `releases/download/<tag>/`. The
 * copy is kept as downloaded and opens only in the dataset's format (here: text after a `packed:` prefix), and is
 * checked daily by the latest tag.
 */
class CachedDatasetReleaseTest {
    private val directory = Path(SystemTemporaryDirectory, "tortuga-releases-" + Random.nextLong().toULong())
    private val github = FakeGitHub()
    private val clock = TestClock()
    private val opened = mutableListOf<String>()

    @AfterTest
    fun deleteDirectory() {
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun theFirstLookupTakesTheLatestTagsChecksumAndFileAndOpensIt() = runTest {
        val data = dataset()
        assertEquals("ages v1", data.get()?.text)
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), github.requests)
        assertEquals(PACKED + "ages v1", read(FILE), "the copy is the release's file as it is")
        assertFalse(exists("$FILE.part"))
    }

    @Test
    fun aReleasePublishedBetweenTheFetchesDoesntMixItsFilesWithTheLatest() = runTest {
        // v2 becomes the latest right after the redirect named v1: the checksum and the file still both come from v1.
        github.afterLatest = { github.publish("v2", "ages v2") }
        val data = dataset()
        assertEquals("ages v1", data.get()?.text)
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), github.requests)
        advanceUntilIdle()

        // The copy is v1's, so the next day's check sees the tag moved and takes v2's files.
        github.afterLatest = null
        clock.advance(CachedDataset.REFRESH_INTERVAL + 1.days)
        data.refreshIfDue()
        assertEquals("ages v2", data.get()?.text)
        assertEquals(listOf(LATEST, "v2/SHA256SUMS", "v2/$FILE"), github.requests.drop(3))
    }

    @Test
    fun aCopyOnDiskIsUsedOfflineAcrossLaunches() = runTest {
        dataset().get()
        advanceUntilIdle()
        github.offline = true

        // A relaunch the same day, offline: the copy, nothing asked.
        clock.advance(CachedDataset.REFRESH_INTERVAL / 2)
        assertEquals("ages v1", dataset().get()?.text)
        // A day later, still offline: the check fails without an answer, the copy stays, and the next launch asks again.
        clock.advance(CachedDataset.REFRESH_INTERVAL)
        val late = dataset()
        assertEquals("ages v1", late.get()?.text)
        advanceUntilIdle()
        assertEquals(3, github.requests.size)
        assertEquals("ages v1", late.get()?.text)

        github.offline = false
        dataset().get()
        advanceUntilIdle()
        assertEquals(LATEST, github.requests.last(), "the overdue check runs at the next launch")
    }

    @Test
    fun aDayLaterAnUnchangedTagDownloadsNothing() = runTest {
        dataset().get()
        advanceUntilIdle()
        clock.advance(CachedDataset.REFRESH_INTERVAL + 1.milliseconds)
        val relaunched = dataset()
        assertEquals("ages v1", relaunched.get()?.text)
        advanceUntilIdle()

        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE", LATEST), github.requests)
        // The unchanged tag counts as today's check.
        relaunched.refreshIfDue()
        assertEquals(4, github.requests.size)
    }

    @Test
    fun aNewReleaseReplacesTheCopy() = runTest {
        val data = dataset()
        data.get()
        advanceUntilIdle()
        github.publish("v2", "ages v2")
        clock.advance(CachedDataset.REFRESH_INTERVAL + 1.days)
        data.refreshIfDue()

        assertEquals("ages v2", data.get()?.text)
        assertEquals(PACKED + "ages v2", read(FILE))
        assertEquals(listOf("ages v1", "ages v2"), opened, "each download opened once, to check it")
        clock.advance(CachedDataset.REFRESH_INTERVAL + 1.days)
        data.refreshIfDue()
        assertEquals(LATEST, github.requests.last(), "v2 is the copy's tag")
    }

    @Test
    fun aCorruptDownloadIsDeletedAndTheCopyStaysADay() = runTest {
        val data = dataset()
        data.get()
        advanceUntilIdle()
        github.publish("v2", "ages v2", listed = "ages v3")
        clock.advance(CachedDataset.REFRESH_INTERVAL + 1.days)
        data.refreshIfDue()

        assertEquals("ages v1", data.get()?.text)
        assertEquals(PACKED + "ages v1", read(FILE))
        assertFalse(exists("$FILE.part"))
        assertEquals(listOf("ages v1"), opened, "the corrupt download was never opened")
        // The corrupt download counts as the check: nothing is asked again before the next day.
        val asked = github.requests.size
        clock.advance(CachedDataset.REFRESH_INTERVAL - 1.milliseconds)
        data.refreshIfDue()
        assertEquals(asked, github.requests.size)
    }

    @Test
    fun aFirstCorruptDownloadLeavesNoCopyAndWaitsADay() = runTest {
        github.publish("v1", "ages v1", listed = "something else")
        val data = dataset()
        assertNull(data.get())
        assertFalse(exists(FILE))
        assertFalse(exists("$FILE.part"))

        github.publish("v2", "ages v2")
        clock.advance(CachedDataset.REFRESH_INTERVAL - 1.milliseconds)
        assertNull(data.get())
        assertEquals(3, github.requests.size, "no lookup downloads again before a day has passed")
        clock.advance(1.milliseconds)
        assertEquals("ages v2", data.get()?.text)
    }

    @Test
    fun aReleaseWithoutTheFilesChecksumOrOneThatDoesntOpenIsUnusable() = runTest {
        github.unlisted = true
        assertNull(dataset().get())
        assertEquals(listOf(LATEST, "v1/SHA256SUMS"), github.requests, "no checksum, no download")

        github.unlisted = false
        github.publishRaw("v2", "not packed")
        clock.advance(CachedDataset.REFRESH_INTERVAL)
        assertNull(dataset().get())
        assertFalse(exists(FILE))
        assertFalse(exists("$FILE.part"))
    }

    @Test
    fun aLatestThatDoesntRedirectToATagDownloadsNothing() = runTest {
        github.redirects = false
        val data = dataset()
        assertNull(data.get())
        assertEquals(listOf(LATEST), github.requests)

        github.redirects = true
        assertEquals("ages v1", data.get()?.text, "an answer without a tag isn't an unusable file: the next lookup asks again")
    }

    /** A copy as the dataset opens it: the text after [PACKED]. */
    private class Opened(val text: String)

    /** A dataset whose loads, checks and requests all run on the test's scheduler. */
    private fun TestScope.dataset(): CachedDataset<Opened> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CachedDataset(
            label = "Test",
            fileName = FILE,
            repository = FakeGitHub.REPOSITORY,
            asset = FILE,
            accept = "application/gzip",
            http = HttpClient(github.engine(dispatcher)) { install(HttpTimeout) },
            directory = directory,
            fileSystem = SystemFileSystem,
            clock = clock,
            scope = this,
            ioDispatcher = dispatcher,
            parseDispatcher = dispatcher,
            open = { path ->
                val text = read(path.name)
                require(text.startsWith(PACKED)) { "not packed" }
                Opened(text.removePrefix(PACKED)).also { opened += it.text }
            },
            isEmpty = { it.text.isEmpty() },
            summary = { it.text },
            maxBytes = 1024,
        )
    }

    private fun read(name: String) = SystemFileSystem.source(Path(directory, name)).buffered().use { it.readString() }

    private fun exists(name: String) = SystemFileSystem.exists(Path(directory, name))

    private companion object {
        const val PACKED = "packed:"
        const val LATEST = "releases/latest"
        const val FILE = "data.bin.gz"
    }

    /**
     * A repository's releases as GitHub serves them: `releases/latest` redirects (302) to the latest release's tag
     * page (a 200 without a tag while not [redirects]), and `releases/download/<tag>/` has each release's packed file
     * and its `SHA256SUMS`, which lists the file's checksum unless [unlisted]. [afterLatest] runs once the redirect is
     * answered (a release published in between); while [offline] nothing answers. [requests] is each path asked for
     * under the repository, a download's without `releases/download/` (`v1/SHA256SUMS`), in order.
     */
    private class FakeGitHub {
        val requests = mutableListOf<String>()
        var offline = false
        var unlisted = false
        var redirects = true
        var afterLatest: (() -> Unit)? = null

        /** Each release by tag: its packed file, and the content its checksum list was made from. */
        private val releases = mutableMapOf<String, Pair<String, String>>()
        private var latest = ""

        init {
            publish("v1", "ages v1")
        }

        /** [tag] becomes the latest release, with [text] packed and the checksum of [listed] packed listed for it. */
        fun publish(tag: String, text: String, listed: String = text) = publishRaw(tag, PACKED + text, PACKED + listed)

        fun publishRaw(tag: String, file: String, listed: String = file) {
            releases[tag] = file to listed
            latest = tag
        }

        fun engine(dispatcher: CoroutineDispatcher) = MockEngine.create {
            this.dispatcher = dispatcher
            addHandler { request -> handle(request) }
        }

        private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            if (offline) error("offline")
            val path = request.url.toString().removePrefix("$REPOSITORY/")
            if (path == LATEST) {
                requests += path
                if (!redirects) return respond("<html>releases</html>", HttpStatusCode.OK)
                val location = "$REPOSITORY/releases/tag/$latest"
                afterLatest?.invoke()
                return respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
            }
            val download = path.removePrefix("releases/download/")
            requests += download
            val (tag, file) = download.split('/', limit = 2)
            val (packed, listed) = releases[tag] ?: return respondError(HttpStatusCode.NotFound)
            return when (file) {
                "SHA256SUMS" -> {
                    val sums = listOfNotNull(
                        "${sha256("other")}  other.db.zst",
                        "${sha256(listed)}  $FILE".takeUnless { unlisted },
                    )
                    respond(sums.joinToString("\n", postfix = "\n"), HttpStatusCode.OK)
                }
                FILE -> respond(packed, HttpStatusCode.OK)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        private fun sha256(text: String) = cryptoProvider.get(SHA256).hasher().hashBlocking(text.encodeToByteArray()).toHexString()

        companion object {
            const val REPOSITORY = "https://github.test/owner/repo"
        }
    }

    private class TestClock : Clock {
        private var now = Instant.fromEpochMilliseconds(1_790_000_000_000)

        override fun now(): Instant = now

        fun advance(by: Duration) {
            now += by
        }
    }
}
