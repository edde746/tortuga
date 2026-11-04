package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.downloads.NewDownload
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.playback.resolve.headers
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The queue's state machine over a scripted transport: pause, cancel, retries, stops, the network, the launch. */
class DownloadQueueTest {
    private val bytes = lcgBytes(200_000, seed = 5)

    @Test
    fun pauseKeepsWhatArrivedAndResumeContinuesFromIt() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val first = h.fakeTransport.started.single()
        writePart(first, 50_000)
        h.fakeTransport.listener?.onResponse(first.id, "\"e1\"")
        h.fakeTransport.progress(first.id, 50_000, bytes.size.toLong())
        h.settle()
        assertEquals(DownloadStatus.DOWNLOADING, h.download(id)?.status)
        assertEquals(50_000L, h.download(id)?.bytes)

        manager.pause(id)
        h.settle()

        assertEquals(listOf(first.id), h.fakeTransport.stopped)
        assertEquals(DownloadStatus.PAUSED, h.download(id)?.status)
        assertEquals(50_000L, h.download(id)?.bytes)
        assertTrue(SystemFileSystem.exists(Path(first.partPath)))

        manager.resume(id)
        h.settle()

        val second = h.fakeTransport.started.last()
        assertEquals(2, h.fakeTransport.started.size)
        assertEquals(50_000L, second.offset)
        assertEquals("\"e1\"", second.validator)
        assertEquals(bytes.size.toLong(), second.expectedSize)
        h.complete(second)
        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
    }

    @Test
    fun aResumeWhileThePauseIsBeingWrittenQueuesItAgain() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        writePart(h.fakeTransport.started.single(), 50_000)

        // A disk too busy to commit: the paused run ends and its writes wait, and the resume arrives meanwhile.
        h.database.held = true
        manager.pause(id)
        h.settle()
        manager.resume(id)
        h.settle()
        h.database.held = false
        h.settle()

        assertEquals(2, h.fakeTransport.started.size, "the resume started it again")
        assertNotEquals(DownloadStatus.PAUSED, h.download(id)?.status)
    }

    @Test
    fun aRunThatThrowsIsTriedAgainAndThenFailsAsStorage() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        // Every attempt throws, as a database too busy to answer does.
        h.fakeTransport.onStart = { _, _ -> throw IllegalStateException("[SQLITE_BUSY] The database file is locked") }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        assertEquals(1, h.fakeTransport.started.size)
        assertNotEquals(DownloadStatus.FAILED, h.download(id)?.status)

        repeat(DownloadWorker.MAX_RETRIES) { h.advance(DownloadWorker.RETRY_DELAY) }

        assertEquals(DownloadWorker.MAX_RETRIES + 1, h.fakeTransport.started.size)
        assertEquals(DownloadStatus.FAILED, h.download(id)?.status)
        assertEquals(DownloadFailure(DownloadFailureKind.STORAGE), h.download(id)?.failure)
    }

    @Test
    fun aResumeReusesTheLastResolveWhileFreshAndAnExpiredLinkResolvesAgain() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        var minted = 0
        // A resolving addon: every resolve mints a new link to the same file.
        h.routes["resolve.example"] = {
            respond(ByteArray(0), HttpStatusCode.Found, headers(HttpHeaders.Location to "https://cdn.example/film.mkv?token=${++minted}"))
        }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, choice(urlStream("https://resolve.example/resolve/abc"))).id
        h.settle()
        val first = h.fakeTransport.started.single()

        manager.pause(id)
        h.settle()
        manager.resume(id)
        h.settle()

        val second = h.fakeTransport.started.last()
        assertEquals(2, h.fakeTransport.started.size)
        assertEquals(first.url, second.url, "the paused transfer resumes the same link")
        assertEquals(1, minted)

        h.fakeTransport.finish(second.id, TransferResult.Http(403))
        h.settle()

        val third = h.fakeTransport.started.last()
        assertEquals(3, h.fakeTransport.started.size)
        assertNotEquals(second.url, third.url, "an expired link is resolved again")
        assertEquals(2, minted)

        manager.pause(id)
        h.settle()
        h.advance(DownloadWorker.REUSE_WINDOW)
        manager.resume(id)
        h.settle()
        assertEquals(3, minted, "a resolve older than the window isn't reused")
    }

    @Test
    fun anExpiredLinkIsRecoveredAgainWhileThePartGrowsAndNotOnceItStops() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        var minted = 0
        h.routes["resolve.example"] = {
            respond(ByteArray(0), HttpStatusCode.Found, headers(HttpHeaders.Location to "https://cdn.example/film.mkv?token=${++minted}"))
        }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, choice(urlStream("https://resolve.example/resolve/abc"))).id
        h.settle()

        // Links that die partway (a platform keeping only part of what each delivered): the first expiry, then each
        // one after the part grew, resolves a fresh link, which continues from the part.
        var offset = 0L
        for (kept in listOf(0L, 60_000L, 120_000L)) {
            val request = h.fakeTransport.started.last()
            assertEquals(offset, request.offset)
            writePart(request, kept)
            h.fakeTransport.finish(request.id, TransferResult.Http(403))
            h.settle()
            offset = kept
        }
        val urls = h.fakeTransport.started.map { it.url }
        assertEquals(4, urls.toSet().size, "each expiry after the part grew resolves a fresh link")
        assertEquals(4, minted)
        assertEquals(120_000L, h.fakeTransport.started.last().offset)
        assertEquals(DownloadStatus.DOWNLOADING, h.download(id)?.status)

        // The fresh link expires without the part growing: no endless resolving, the download fails.
        h.fakeTransport.finish(h.fakeTransport.started.last().id, TransferResult.Http(403))
        h.settle()
        assertEquals(4, h.fakeTransport.started.size)
        assertEquals(DownloadStatus.FAILED, h.download(id)?.status)
        assertEquals(DownloadFailure(DownloadFailureKind.HTTP, 403), h.download(id)?.failure)
    }

    @Test
    fun cancelStopsTheTransferAndDeletesThePart() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val request = h.fakeTransport.started.single()
        writePart(request, 1000)

        manager.cancel(id)
        h.settle()

        assertEquals(DownloadStatus.CANCELLED, h.download(id)?.status)
        assertFalse(SystemFileSystem.exists(Path(request.partPath)))

        manager.retry(id)
        h.settle()
        assertEquals(0L, h.fakeTransport.started.last().offset)
        assertEquals(DownloadStatus.DOWNLOADING, h.download(id)?.status)
    }

    /** A part a failed deletion left behind (a cancel's, a restart's) is another file's: a fresh start never resumes it. */
    @Test
    fun aPartLeftWhereAFileStartsAfreshIsNotResumed() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val request = h.fakeTransport.started.single()
        manager.cancel(id)
        h.settle()
        writePart(request, 1000)

        manager.retry(id)
        h.settle()

        assertEquals(0L, h.fakeTransport.started.last().offset)
        assertFalse(SystemFileSystem.exists(Path(request.partPath)))
    }

    @Test
    fun networkFailuresAreRetriedThreeTimesThirtySecondsApart() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.fakeTransport.onStart = { request, listener -> listener.onResult(request.id, TransferResult.Network("IOException")) }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        assertEquals(1, h.fakeTransport.started.size)
        assertEquals(DownloadStatus.QUEUED, h.download(id)?.status)

        repeat(DownloadWorker.MAX_RETRIES) { retry ->
            h.advance(DownloadWorker.RETRY_DELAY - kotlin.time.Duration.parse("1ms"))
            assertEquals(retry + 1, h.fakeTransport.started.size, "not before 30 s")
            h.advance(kotlin.time.Duration.parse("1ms"))
            assertEquals(retry + 2, h.fakeTransport.started.size)
        }

        assertEquals(DownloadStatus.FAILED, h.download(id)?.status)
        assertEquals(DownloadFailure(DownloadFailureKind.NETWORK), h.download(id)?.failure)
    }

    /** Resume is the user acting: a paused download's failed attempts start over (`download.attempts`). */
    @Test
    fun aResumedDownloadHasItsRetriesAgain() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.fakeTransport.onStart = { request, listener -> listener.onResult(request.id, TransferResult.Network("IOException")) }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        repeat(DownloadWorker.MAX_RETRIES - 1) { h.advance(DownloadWorker.RETRY_DELAY) }
        assertEquals(DownloadWorker.MAX_RETRIES, h.fakeTransport.started.size)
        manager.pause(id)
        h.settle()
        assertEquals(DownloadStatus.PAUSED, h.download(id)?.status)

        manager.resume(id)
        h.settle()

        assertEquals(DownloadWorker.MAX_RETRIES + 1, h.fakeTransport.started.size)
        assertEquals(DownloadStatus.QUEUED, h.download(id)?.status, "its next failure is retried, not the last")
    }

    /** Retries count failures in a row: an attempt that left more of the file than it started with re-arms them. */
    @Test
    fun aTransferThatKeepsGrowingBetweenDropsIsRetriedUntilItCompletes() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        // Each attempt delivers 20 000 bytes more, then the connection drops.
        h.fakeTransport.onStart = { request, listener ->
            writePart(request, request.offset + 20_000)
            listener.onResult(request.id, TransferResult.Network("IOException"))
        }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()

        repeat(2 * DownloadWorker.MAX_RETRIES) { h.advance(DownloadWorker.RETRY_DELAY) }

        assertEquals(2 * DownloadWorker.MAX_RETRIES + 1, h.fakeTransport.started.size)
        assertEquals(DownloadStatus.QUEUED, h.download(id)?.status, "still retried")
        h.fakeTransport.onStart = { _, _ -> }
        h.advance(DownloadWorker.RETRY_DELAY)
        h.complete(h.fakeTransport.started.last())
        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
    }

    /** A pause isn't undone by a weaker stop that comes while its transfer still stops (the network dropping). */
    @Test
    fun aPauseStaysAPauseWhenTheNetworkDropsWhileItStops() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        // The transport takes its time to report the stop.
        h.fakeTransport.running.clear()

        manager.pause(id)
        h.settle()
        h.network(online = false)
        h.advance(Transfers.STOP_WAIT)
        h.network(online = true)

        assertEquals(DownloadStatus.PAUSED, h.download(id)?.status)
        assertEquals(1, h.fakeTransport.started.size, "not started again on its own")
    }

    @Test
    fun threeFailuresInARowStopTheQueueUntilTheUserActs() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.fakeTransport.onStart = { request, listener -> listener.onResult(request.id, TransferResult.Http(400)) }
        val manager = h.install()
        val ids = listOf("tt0903747:1:1", "tt0903747:1:2", "tt0903747:1:3", "tt0903747:2:1").map { videoId ->
            h.wallClock.now += 1000
            manager.enqueue(h.profileId, SHOW, videoId, cdnChoice("$videoId.mkv")).id
        }
        h.settle()

        assertEquals(3, h.fakeTransport.started.size)
        assertEquals(DownloadFailure(DownloadFailureKind.HTTP, 400), h.download(ids[0])?.failure)
        assertEquals(DownloadStatus.QUEUED, h.download(ids[3])?.status)
        assertFalse(manager.active.value)

        h.fakeTransport.onStart = { _, _ -> }
        manager.resume(ids[3])
        h.settle()
        assertEquals(ids[3].toString(), h.fakeTransport.started.last().id)
    }

    @Test
    fun aFullDiskFailsTheQueueAndStopsIt() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val first = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        h.wallClock.now += 1000
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("2.mkv")).id
        h.settle()

        h.fakeTransport.finish(first.toString(), TransferResult.DiskFull)
        h.settle()

        val diskFull = DownloadFailure(DownloadFailureKind.DISK_FULL)
        assertEquals(diskFull, h.download(first)?.failure)
        assertEquals(diskFull, h.download(second)?.failure)
        assertEquals(1, h.fakeTransport.started.size)

        // Queueing another is the user acting: the queue runs again.
        val third = manager.enqueue(h.profileId, SHOW, "tt0903747:1:3", cdnChoice("3.mkv")).id
        h.settle()
        assertEquals(listOf(first, third).map { it.toString() }, h.fakeTransport.started.map { it.id })
        manager.resumeAll(h.profileId)
        h.settle()
        assertEquals(DownloadStatus.QUEUED, h.download(first)?.status)
    }

    @Test
    fun wifiOnlyHoldsTheQueueOnAMeteredNetworkAndOfflineHoldsItToo() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.network(online = true, metered = true)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()

        assertTrue(h.fakeTransport.started.isEmpty())
        assertEquals(true, h.download(id)?.waitingForWifi)
        assertFalse(manager.active.value)

        h.network(online = true, metered = false)
        assertEquals(1, h.fakeTransport.started.size)
        assertEquals(DownloadStatus.DOWNLOADING, h.download(id)?.status)
        assertTrue(manager.active.value)
        assertEquals(true to true, h.platform.queueActive.last())

        h.network(online = true, metered = true)
        assertEquals(listOf(id.toString()), h.fakeTransport.stopped)
        assertEquals(DownloadStatus.QUEUED, h.download(id)?.status)
        assertEquals(false to true, h.platform.queueActive.last())

        h.network(online = false)
        assertEquals(false, h.download(id)?.waitingForWifi)
        assertEquals(1, h.fakeTransport.started.size)

        h.data.deviceSettings.set(DeviceSetting.DownloadWifiOnly, false)
        h.network(online = true, metered = true)
        assertEquals(2, h.fakeTransport.started.size)
        assertEquals(false, h.fakeTransport.started.last().wifiOnly)
    }

    @Test
    fun theNetworkIsStillFollowedAfterSavingMissingExtrasThrew() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        h.settle()
        // Complete without its artwork (img.example answers 404): the network coming back saves it.
        h.complete(h.fakeTransport.started.single())
        h.network(online = false)

        h.platform.locationFailures = 1
        h.network(online = true)
        assertEquals(0, h.platform.locationFailures, "saving the missing artwork read the locations")

        val id = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        h.settle()
        h.network(online = false)

        assertEquals(listOf(id.toString()), h.fakeTransport.stopped)
        assertEquals(DownloadStatus.QUEUED, h.download(id)?.status)
    }

    @Test
    fun theLaunchReattachesRequeuesAndSweepsOrphanParts() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val repo = h.data.downloads
        fun new(videoId: String) = NewDownload(
            type = "series", metaId = SHOW.id, videoId = videoId, title = SHOW.name, year = 2008, episodeTitle = null,
            season = 1, episode = videoId.last().digitToInt(), poster = null, thumbnail = null,
            stream = cdnChoice("$videoId.mkv"),
            locationId = DownloadLocation.INTERNAL, keepSynced = false,
        )
        val running = repo.enqueue(h.profileId, new("tt0903747:1:1"), SHOW).id
        h.wallClock.now += 1000
        val stale = repo.enqueue(h.profileId, new("tt0903747:1:2"), SHOW).id
        h.wallClock.now += 1000
        val resolving = repo.enqueue(h.profileId, new("tt0903747:1:3"), SHOW).id
        for (id in listOf(running, stale)) {
            repo.setPath(id, "TV Shows/Breaking Bad (2008)/Season 01/E$id.mkv")
            repo.setStatus(id, DownloadStatus.DOWNLOADING)
            repo.setTransferring(id, true)
        }
        repo.setStatus(resolving, DownloadStatus.RESOLVING)
        touch(h.file("TV Shows/Breaking Bad (2008)/Season 01/E$stale.mkv.part"))
        touch(h.file("TV Shows/Breaking Bad (2008)/Season 01/Gone [0000].mkv.part"))
        touch(h.file("TV Shows/Breaking Bad (2008)/Season 01/Gone [0000].mkv.part.resume"))
        h.fakeTransport.stillRunning = setOf(running.toString())

        h.install()

        assertEquals(DownloadStatus.DOWNLOADING, h.download(running)?.status)
        assertEquals(DownloadStatus.QUEUED, h.download(stale)?.status)
        assertEquals(DownloadStatus.QUEUED, h.download(resolving)?.status)
        assertTrue(h.fakeTransport.started.isEmpty(), "the reattached transfer holds the only slot")
        assertTrue(h.exists("TV Shows/Breaking Bad (2008)/Season 01/E$stale.mkv.part"))
        assertFalse(h.exists("TV Shows/Breaking Bad (2008)/Season 01/Gone [0000].mkv.part"))
        assertFalse(h.exists("TV Shows/Breaking Bad (2008)/Season 01/Gone [0000].mkv.part.resume"))

        touch(h.file("TV Shows/Breaking Bad (2008)/Season 01/E$running.mkv.part"))
        h.fakeTransport.finish(running.toString(), TransferResult.Completed(0, null))
        h.settle()

        assertEquals(DownloadStatus.COMPLETED, h.download(running)?.status)
        assertTrue(h.exists("TV Shows/Breaking Bad (2008)/Season 01/E$running.mkv"))
        assertEquals(stale.toString(), h.fakeTransport.started.single().id)
        assertNull(h.fakeTransport.started.single().validator)
    }

    @Test
    fun aLaunchWhoseLocationsCantBeReadStillRunsTheQueueAndItsActions() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.platform.locationFailures = 1
        val manager = h.install()
        assertEquals(0, h.platform.locationFailures, "the launch read the locations")

        // An action waits for the launch's reconcile: the launch has to end.
        val id = withTimeout(1.minutes) { manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id }
        h.settle()

        assertEquals(listOf(id.toString()), h.fakeTransport.started.map { it.id })
        assertEquals(listOf(DownloadLocation.INTERNAL), manager.locations.value.locations.map { it.id }, "the settings read the locations again")
    }

    private fun touch(path: Path) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.write(ByteArray(0)) }
    }
}
