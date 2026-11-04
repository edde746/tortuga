@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.trackers

import com.edde746.tortuga.crypto.cryptoProvider
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.trackers.ids.CachedDataset
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The datasets at the app's start: every disk copy brought up to date in turn once the app has been in the foreground
 * for [START_DELAY], on a network that is up and not metered then, and again each time it comes back to the
 * foreground; each dataset loaded as soon as this device needs it, whatever the network.
 */
class DatasetsAtStartTest {
    private val foreground = MutableStateFlow(false)
    private val network = MutableStateFlow(NetworkState.ONLINE)

    /** What the datasets did, each with the virtual time in ms it started at. */
    private val events = mutableListOf<String>()

    @Test
    fun everyDiskCopyIsUpdatedInTurnOnlyOnceTheAppHasBeenInTheForegroundAWhile() = runTest {
        start(dataset("ages"), dataset("anime"))
        advanceTimeBy(1.hours)
        assertEquals(emptyList(), events, "never in the foreground")

        foreground.value = true
        advanceTimeBy(START_DELAY - 1.milliseconds)
        runCurrent()
        assertEquals(emptyList(), events)
        advanceTimeBy(1.hours)
        // Each update takes a second: the second starts once the first is done.
        assertEquals(listOf("ages updated at 3610000", "anime updated at 3611000"), events)
    }

    @Test
    fun aForegroundAfterTheIntervalChecksAgain() = runTest {
        start(released("v1"))
        foreground.value = true
        advanceTimeBy(START_DELAY + 1.seconds)
        assertEquals(listOf(LATEST, "v1/SHA256SUMS", "v1/$FILE"), requests)

        // A TV left on: the app goes to the background and comes back the next day, the process still alive.
        foreground.value = false
        advanceTimeBy(CachedDataset.REFRESH_INTERVAL)
        tag = "v2"
        foreground.value = true
        advanceTimeBy(START_DELAY + 1.seconds)
        assertEquals(listOf(LATEST, "v2/SHA256SUMS", "v2/$FILE"), requests.drop(3))
    }

    @Test
    fun foregroundsWithinTheIntervalAskNothing() = runTest {
        start(released("v1"))
        foreground.value = true
        advanceTimeBy(START_DELAY + 1.seconds)
        assertEquals(3, requests.size)

        tag = "v2"
        repeat(5) {
            foreground.value = false
            advanceTimeBy(1.hours)
            foreground.value = true
            advanceTimeBy(START_DELAY + 1.seconds)
        }
        assertEquals(3, requests.size, "the last check is less than a day old")
    }

    @Test
    fun quickSwitchesDuringTheDelayMakeOneUpdate() = runTest {
        start(dataset("ages"), dataset("anime"))
        repeat(4) {
            foreground.value = true
            advanceTimeBy(START_DELAY / 2)
            foreground.value = false
            advanceTimeBy(1.seconds)
        }
        foreground.value = true
        advanceTimeBy(START_DELAY - 1.milliseconds)
        runCurrent()
        assertEquals(emptyList(), events, "each foreground starts the wait over")
        advanceTimeBy(1.hours)
        assertEquals(listOf("ages updated at 34000", "anime updated at 35000"), events)
    }

    @Test
    fun noDiskCopyIsUpdatedOnAMeteredNetwork() = runTest {
        network.value = NetworkState(online = true, metered = true)
        foreground.value = true
        start(dataset("ages"), dataset("anime"))
        advanceTimeBy(START_DELAY + 1.milliseconds)
        assertEquals(emptyList(), events)
        // Skipped at this start, not put off: a network unmetered later doesn't bring it back.
        network.value = NetworkState.ONLINE
        advanceTimeBy(1.hours)
        assertEquals(emptyList(), events)
    }

    @Test
    fun noDiskCopyIsUpdatedOffline() = runTest {
        network.value = NetworkState(online = false, metered = false)
        foreground.value = true
        start(dataset("ages"), dataset("anime"))
        advanceTimeBy(1.hours)
        assertEquals(emptyList(), events)
    }

    @Test
    fun aDatasetLoadsAsSoonAsItIsNeeded() = runTest {
        // Offline and in the background: a load doesn't wait for either.
        network.value = NetworkState(online = false, metered = false)
        val animeNeeded = MutableStateFlow(false)
        start(dataset("ages", needed = flowOf(true)), dataset("anime", needed = animeNeeded))
        runCurrent()
        assertEquals(listOf("ages loaded at 0"), events, "a kids profile: the ages load at once")

        advanceTimeBy(5.seconds)
        assertEquals(listOf("ages loaded at 0"), events, "no anime tracker yet: the mappings stay unloaded")
        animeNeeded.value = true
        runCurrent()
        assertEquals(listOf("ages loaded at 0", "anime loaded at 5000"), events)

        // Loaded once: needed again later loads nothing more.
        animeNeeded.value = false
        runCurrent()
        animeNeeded.value = true
        runCurrent()
        assertEquals(2, events.size)
    }

    @AfterTest
    fun deleteDirectory() {
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    private val directory = Path(SystemTemporaryDirectory, "tortuga-datasets-at-start-" + Random.nextLong().toULong())

    /** The latest release's tag, its file the tag's name. */
    private var tag = "v1"

    /** Each path asked for under the repository, a download's without `releases/download/`. */
    private val requests = mutableListOf<String>()

    /** A real dataset, its clock the test's virtual time, against a repository whose latest release is [tag]. */
    private fun TestScope.released(first: String): DatasetAtStart {
        tag = first
        val dispatcher = StandardTestDispatcher(testScheduler)
        val github = MockEngine.create {
            this.dispatcher = dispatcher
            addHandler { request ->
                val path = request.url.toString().removePrefix("$REPOSITORY/")
                requests += path.removePrefix("releases/download/")
                when {
                    path == LATEST -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "$REPOSITORY/releases/tag/$tag"))
                    path.endsWith("/SHA256SUMS") -> respond("${sha256(path.split('/')[2])}  $FILE\n", HttpStatusCode.OK)
                    else -> respond(path.split('/')[2], HttpStatusCode.OK)
                }
            }
        }
        val data = CachedDataset(
            label = "Test",
            fileName = FILE,
            repository = REPOSITORY,
            asset = FILE,
            accept = "text/plain",
            http = HttpClient(github),
            directory = directory,
            fileSystem = SystemFileSystem,
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            },
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            parseDispatcher = dispatcher,
            open = { it.name },
            isEmpty = String::isEmpty,
            summary = { it },
            maxBytes = 1024,
        )
        return DatasetAtStart(updateDiskCopy = { data.updateDiskCopy() }, needed = MutableStateFlow(false), load = { data.get() })
    }

    private fun sha256(text: String) = cryptoProvider.get(SHA256).hasher().hashBlocking(text.encodeToByteArray()).toHexString()

    private fun TestScope.start(vararg datasets: DatasetAtStart) =
        backgroundScope.startDatasets(datasets.toList(), foreground, network)

    private fun TestScope.dataset(name: String, needed: Flow<Boolean> = MutableStateFlow(false)) = DatasetAtStart(
        updateDiskCopy = {
            events += "$name updated at ${testScheduler.currentTime}"
            delay(1.seconds)
        },
        needed = needed,
        load = { events += "$name loaded at ${testScheduler.currentTime}" },
    )

    private companion object {
        const val REPOSITORY = "https://github.test/owner/repo"
        const val LATEST = "releases/latest"
        const val FILE = "data.txt"
    }
}
