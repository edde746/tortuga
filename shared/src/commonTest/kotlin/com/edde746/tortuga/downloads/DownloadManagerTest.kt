package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.downloads.DownloadStream
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.headers
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadManagerTest {
    private val bytes = lcgBytes(400_000, seed = 3)

    @Test
    fun aMovieResolvesDownloadsAndCompletesUnderItsName() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()

        val queued = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        assertEquals(DownloadStatus.QUEUED, queued.status)
        h.settle()

        val done = assertNotNull(h.download(queued.id))
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(bytes.size.toLong(), done.bytes)
        assertEquals(bytes.size.toLong(), done.totalBytes)
        val tag = DownloadNames.tag("movie", FILM.id, FILM.id)
        val path = "Movies/The Shawshank Redemption (1994)/The Shawshank Redemption (1994) [$tag].mkv"
        assertContentEquals(bytes, read(h.file(path)))
        assertFalse(h.exists("$path.part"))

        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        assertEquals(h.file(path).toString(), local.path)
        assertEquals(bytes.size.toLong(), local.size)
        assertEquals("film.mkv", local.filename)
        assertEquals(cdnChoice(), local.stream)
        assertNull(manager.localVideo("another profile", FILM.id, FILM.id))
    }

    @Test
    fun downloadsRunOneAtATimeOldestFirst() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val first = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv"))
        h.wallClock.now += 1000
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("2.mkv"))
        h.wallClock.now += 1000
        val third = manager.enqueue(h.profileId, SHOW, "tt0903747:1:3", cdnChoice("3.mkv"))
        h.settle()

        assertEquals(listOf(first.id.toString()), h.fakeTransport.started.map { it.id })
        assertEquals(DownloadStatus.DOWNLOADING, h.download(first.id)?.status)
        assertEquals(DownloadStatus.QUEUED, h.download(second.id)?.status)
        assertEquals(DownloadStatus.QUEUED, h.download(third.id)?.status)

        h.complete(h.fakeTransport.started.last())
        assertEquals(DownloadStatus.COMPLETED, h.download(first.id)?.status)
        assertEquals(listOf(first.id, second.id).map { it.toString() }, h.fakeTransport.started.map { it.id })

        h.data.deviceSettings.set(DeviceSetting.DownloadsAtOnce, 2)
        h.settle()
        assertEquals(listOf(first.id, second.id, third.id).map { it.toString() }, h.fakeTransport.started.map { it.id })
    }

    @Test
    fun anInterruptedTransferResumesWithRangeAndIfRange() = downloadsTest { h ->
        var cut = true
        h.routes["cdn.example"] = { request ->
            if (request.headers[HttpHeaders.Range] == null && cut) {
                // The first transfer's connection drops after 150 000 bytes.
                cut = false
                respond(bytes.copyOf(150_000), HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "${bytes.size}", HttpHeaders.ETag to "\"v1\""))
            } else {
                serveVideo(request, bytes)
            }
        }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()

        assertEquals(DownloadStatus.QUEUED, h.download(id)?.status)
        assertEquals(150_000L, h.download(id)?.bytes)
        h.advance(DownloadWorker.RETRY_DELAY)

        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
        val transfers = h.transferRequests()
        assertEquals(listOf(null, "bytes=150000-"), transfers.map { it.headers[HttpHeaders.Range] })
        assertEquals("\"v1\"", transfers.last().headers[HttpHeaders.IfRange])
        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        assertContentEquals(bytes, read(Path(local.path)))
    }

    @Test
    fun anExpiredLinkIsResolvedAgainAndTheSameFileResumes() = downloadsTest { h ->
        var transfers = 0
        h.routes["cdn.example"] = { request ->
            if (!isTransfer(request)) {
                serveVideo(request, bytes)
            } else {
                when (transfers++) {
                    0 -> respond(bytes.copyOf(150_000), HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "${bytes.size}", HttpHeaders.ETag to "\"v1\""))
                    1 -> respond("expired", HttpStatusCode.Forbidden)
                    else -> serveVideo(request, bytes)
                }
            }
        }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        h.advance(DownloadWorker.RETRY_DELAY)

        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
        assertEquals(listOf(null, "bytes=150000-", "bytes=150000-"), h.transferRequests().map { it.headers[HttpHeaders.Range] })
        assertEquals(2, h.http.requests.count { it.headers[HttpHeaders.Range] == "bytes=0-65535" }, "the retry reuses the resolve; the 403 resolves again")
        assertContentEquals(bytes, read(Path(assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).path)))
    }

    @Test
    fun aFileOfAnotherSizeAfterExpiryStartsOver() = downloadsTest { h ->
        val replaced = lcgBytes(350_000, seed = 9)
        var current = bytes
        var transfers = 0
        h.routes["cdn.example"] = { request ->
            if (!isTransfer(request)) {
                serveVideo(request, current)
            } else {
                when (transfers++) {
                    0 -> {
                        current = replaced
                        respond(bytes.copyOf(150_000), HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "${bytes.size}", HttpHeaders.ETag to "\"v1\""))
                    }
                    1 -> respond("expired", HttpStatusCode.Gone)
                    else -> serveVideo(request, current, etag = "\"v2\"")
                }
            }
        }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        h.advance(DownloadWorker.RETRY_DELAY)

        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
        assertEquals(listOf(null, "bytes=150000-", null), h.transferRequests().map { it.headers[HttpHeaders.Range] })
        assertContentEquals(replaced, read(Path(assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).path)))
    }

    @Test
    fun aStreamThatNoLongerResolvesIsFoundAgainInItsAddon() = downloadsTest { h ->
        h.serveCdn(bytes)
        var lazyHits = 0
        h.routes["lazy.example"] = { if (lazyHits++ == 0) respond("", HttpStatusCode.NotFound) else redirectTo("https://cdn.example/film.mkv") }
        h.serveStreams { listOf(urlStream("https://lazy.example/play/abc", name = "Renamed")) }
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, choice(urlStream("https://lazy.example/play/abc", name = "Original"))).id
        h.settle()

        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
        assertEquals("Renamed", (h.data.downloads.download(id)?.stream as? DownloadStream.Picked)?.choice?.stream?.name)
    }

    @Test
    fun aStreamItsAddonNoLongerOffersIsGone() = downloadsTest { h ->
        h.routes["lazy.example"] = { respond("", HttpStatusCode.NotFound) }
        h.serveStreams { listOf(urlStream("https://lazy.example/play/other")) }
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, choice(urlStream("https://lazy.example/play/abc"))).id
        h.settle()

        assertEquals(DownloadFailure(DownloadFailureKind.STREAM_GONE), h.download(id)?.failure)
        assertEquals(DownloadStatus.FAILED, h.download(id)?.status)
    }

    @Test
    fun aFileShorterThanAnnouncedFailsAndIsDeleted() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val request = h.fakeTransport.started.single()

        h.complete(request, size = bytes.size - 10L)

        assertEquals(DownloadFailure(DownloadFailureKind.SIZE_MISMATCH), h.download(id)?.failure)
        assertFalse(SystemFileSystem.exists(Path(request.partPath)))
    }

    @Test
    fun anErrorVideoIsNeverDownloaded() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice("videos/downloading_v2.mp4")).id
        h.settle()

        assertEquals(DownloadFailure(DownloadFailureKind.ERROR_VIDEO), h.download(id)?.failure)
        assertTrue(h.transferRequests().isEmpty())
    }

    @Test
    fun aStreamFoundAgainThatIsAnErrorVideoIsNeverDownloaded() = downloadsTest { h ->
        h.serveCdn(bytes)
        var lazyHits = 0
        h.routes["lazy.example"] = { if (lazyHits++ == 0) respond("", HttpStatusCode.NotFound) else redirectTo("https://cdn.example/videos/downloading_v2.mp4") }
        h.serveStreams { listOf(urlStream("https://lazy.example/play/abc")) }
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, choice(urlStream("https://lazy.example/play/abc"))).id
        h.settle()

        assertEquals(DownloadFailure(DownloadFailureKind.ERROR_VIDEO), h.download(id)?.failure)
        assertTrue(h.transferRequests().isEmpty())
    }

    @Test
    fun aPlaylistLinkIsRefusedBeforeTransferring() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice("live/index.m3u8")).id
        h.settle()

        assertEquals(DownloadFailure(DownloadFailureKind.UNSUPPORTED), h.download(id)?.failure)
        assertTrue(h.transferRequests().isEmpty())
    }

    @Test
    fun aPlaylistServedWithoutItsExtensionIsRefusedOnceItArrives() = downloadsTest { h ->
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nsegment0.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
        h.serveCdn(playlist)
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice("play/abc")).id
        h.settle()

        assertEquals(DownloadStatus.FAILED, h.download(id)?.status)
        assertEquals(DownloadFailure(DownloadFailureKind.UNSUPPORTED), h.download(id)?.failure)
        assertNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        assertTrue(SystemFileSystem.list(h.file("Movies/The Shawshank Redemption (1994)")).isEmpty(), "nothing kept")
    }

    @Test
    fun aDownloadWhoseFileCantBeDeletedStaysUntilItIs() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val video = Path(assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).path)
        // Something in the way of the deletion: the file's name is a folder with something in it.
        SystemFileSystem.delete(video)
        SystemFileSystem.createDirectories(Path(video, "held"))

        manager.delete(h.profileId, id)
        h.settle()
        assertNotNull(h.data.downloads.download(id), "kept, so its files can still be deleted")

        SystemFileSystem.delete(Path(video, "held"))
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv"))
        h.settle()
        assertNull(h.data.downloads.download(id), "deleted with its files by the next purge")
        assertFalse(SystemFileSystem.exists(video))
    }

    @Test
    fun aDownloadWithoutRoomForItsSizeFailsBeforeTransferring() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.platform.free = DownloadWorker.SPACE_MARGIN
        val manager = h.install()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()

        assertEquals(DownloadFailure(DownloadFailureKind.NOT_ENOUGH_SPACE), h.download(id)?.failure)
        assertTrue(h.fakeTransport.started.isEmpty())
    }

    @Test
    fun aMissingLocationFailsTheDownload() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        h.platform.locationList = emptyList()

        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()

        assertEquals(DownloadFailure(DownloadFailureKind.LOCATION_UNAVAILABLE), h.download(id)?.failure)
    }

    @Test
    fun aFailedWriteOnAVolumeWithoutRoomIsAFullDiskAndStopsTheQueue() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val first = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        h.wallClock.now += 1000
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("2.mkv")).id
        h.settle()

        // The OS's error, in the user's language, can't say the disk is full: the space left does.
        h.platform.free = DownloadWorker.SPACE_MARGIN - 1
        h.fakeTransport.finish(first.toString(), TransferResult.WriteFailed)
        h.settle()

        val diskFull = DownloadFailure(DownloadFailureKind.DISK_FULL)
        assertEquals(diskFull, h.download(first)?.failure)
        assertEquals(diskFull, h.download(second)?.failure)
        assertEquals(1, h.fakeTransport.started.size)
    }

    @Test
    fun aFailedWriteWithRoomLeftOrUnreadableSpaceIsALocationUnavailable() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val first = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        h.wallClock.now += 1000
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("2.mkv")).id
        h.settle()

        h.fakeTransport.finish(first.toString(), TransferResult.WriteFailed)
        h.settle()
        h.platform.free = null
        h.fakeTransport.finish(second.toString(), TransferResult.WriteFailed)
        h.settle()

        val unavailable = DownloadFailure(DownloadFailureKind.LOCATION_UNAVAILABLE)
        assertEquals(unavailable, h.download(first)?.failure, "room left")
        assertEquals(unavailable, h.download(second)?.failure, "free space unknown")
        assertEquals(listOf(first, second).map { it.toString() }, h.fakeTransport.started.map { it.id })
    }

    @Test
    fun theSavedMetaShowsSavedArtworkAsFiles() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.routes["img.example"] = { respond(ByteArray(10), HttpStatusCode.OK, headers(HttpHeaders.ContentType to "image/jpeg")) }
        val manager = h.install()

        val queued = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice())
        assertEquals("https://img.example/bb.jpg", manager.savedMeta("series", SHOW.id)?.preview?.poster, "saved when queued")
        h.settle()

        val meta = assertNotNull(manager.savedMeta("series", SHOW.id))
        val poster = assertNotNull(meta.preview.poster)
        assertTrue(poster.startsWith("file:///"), poster)
        assertTrue(assertNotNull(meta.videos.first { it.id == "tt0903747:1:2" }.thumbnail).startsWith("file:///"))
        assertEquals("https://img.example/1-1.jpg", meta.videos.first { it.id == "tt0903747:1:1" }.thumbnail)
        val download = assertNotNull(h.download(queued.id))
        assertEquals(poster, download.poster)
        assertEquals("Cat's in the Bag...", download.episodeTitle)
        assertEquals(1, download.season)
        assertEquals(2, download.episode)
    }

    @Test
    fun theLibraryKeepsTheAddonsPosterWhereTheSavedMetaHasTheFile() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.routes["img.example"] = { respond(ByteArray(10), HttpStatusCode.OK, headers(HttpHeaders.ContentType to "image/jpeg")) }
        val manager = h.install()
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice())
        h.settle()
        val saved = assertNotNull(manager.savedMeta("series", SHOW.id))
        assertTrue(assertNotNull(saved.preview.poster).startsWith("file:///"))
        val library = h.data.library

        // Offline, a card's or Detail's mark works from the saved meta: the item it creates takes the poster the
        // download was saved with, since it syncs to devices that don't have the file.
        library.markVideoWatched(h.profileId, saved, "tt0903747:1:1", true)
        assertEquals("https://img.example/bb.jpg", library.item(h.profileId, SHOW.id)?.poster)

        // Online, Detail gives it the addon's poster of now; Add to library and a play from Downloads over the saved meta
        // keep that one.
        library.addToLibrary(h.profileId, SHOW.copy(preview = SHOW.preview.copy(poster = "https://img.example/bb-2.jpg")).librarySnapshot())
        library.addToLibrary(h.profileId, saved.librarySnapshot())
        library.upsertFromMeta(h.profileId, saved.librarySnapshot())
        library.markAllWatched(h.profileId, saved, true)
        assertEquals("https://img.example/bb-2.jpg", library.item(h.profileId, SHOW.id)?.poster)
    }

    @Test
    fun subtitlesAreSavedNextToTheVideo() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.routes["subs.example.com"] = { request ->
            if (request.url.segments.firstOrNull() == "subtitles") {
                json("""{"subtitles":[{"id":"1","lang":"eng","url":"https://subs.example.com/files/1.srt"}]}""")
            } else {
                respond("1\n00:00:01,000 --> 00:00:02,000\nHello\n", HttpStatusCode.OK)
            }
        }
        h.data.settings.set(h.profileId, ProfileSetting.SubtitleLanguages, listOf("en"))
        val manager = h.install()

        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        h.settle()

        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        val subtitle = local.subtitles.single()
        assertEquals("en", subtitle.language)
        assertTrue(subtitle.path.endsWith("_subs${pathSeparator(subtitle.path)}1.en.srt"), subtitle.path)
        assertTrue(SystemFileSystem.exists(Path(subtitle.path)))
        val sentHash = h.http.requests.first { it.url.host == "subs.example.com" && it.url.segments.firstOrNull() == "subtitles" }
        assertTrue(sentHash.url.toString().contains("videoSize=${bytes.size}"), "looked up by the file's size")
    }

    @Test
    fun anEmptySubtitleAnswerIsNotKeptAndIsAskedForAgain() = downloadsTest { h ->
        h.serveCdn(bytes)
        var file = ""
        h.routes["subs.example.com"] = { request ->
            if (request.url.segments.firstOrNull() == "subtitles") {
                json("""{"subtitles":[{"id":"1","lang":"eng","url":"https://subs.example.com/files/1.srt"}]}""")
            } else {
                respond(file, HttpStatusCode.OK)
            }
        }
        h.data.settings.set(h.profileId, ProfileSetting.SubtitleLanguages, listOf("en"))
        val manager = h.install()
        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        h.settle()
        assertEquals(emptyList(), assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).subtitles)

        // The network coming back saves what a download misses.
        file = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
        h.network(online = false)
        h.network(online = true)
        val subtitle = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).subtitles.single()
        assertEquals(file, read(Path(subtitle.path)).decodeToString())
    }
    @Test
    fun subtitlesAnAddonCouldntLookUpAreAskedForAgain() = downloadsTest { h ->
        h.serveCdn(bytes)
        var lookups = 0
        h.routes["subs.example.com"] = { request ->
            if (request.url.segments.firstOrNull() == "subtitles") {
                if (lookups++ == 0) {
                    respond("", HttpStatusCode.ServiceUnavailable)
                } else {
                    json("""{"subtitles":[{"id":"1","lang":"eng","url":"https://subs.example.com/files/1.srt"}]}""")
                }
            } else {
                respond("1\n00:00:01,000 --> 00:00:02,000\nHello\n", HttpStatusCode.OK)
            }
        }
        h.data.settings.set(h.profileId, ProfileSetting.SubtitleLanguages, listOf("en"))
        val manager = h.install()
        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        h.settle()
        assertEquals(emptyList(), assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).subtitles)

        h.network(online = false)
        h.network(online = true)

        assertEquals("en", assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).subtitles.single().language)
    }

}

/** A transfer's request (whole, or from an offset on), not one of the resolve's ranged reads. */
private fun isTransfer(request: io.ktor.client.request.HttpRequestData) = request.headers[HttpHeaders.Range]?.endsWith("-") != false

private fun io.ktor.client.engine.mock.MockRequestHandleScope.redirectTo(location: String) =
    respond(ByteArray(0), HttpStatusCode.Found, headers(HttpHeaders.Location to location))

private fun pathSeparator(path: String) = if ('\\' in path) "\\" else "/"
