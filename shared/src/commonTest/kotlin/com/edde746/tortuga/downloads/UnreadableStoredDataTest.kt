package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.resolve.headers
import com.edde746.tortuga.playback.resolve.lcgBytes
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A download whose stored stream or saved meta no longer decodes (written by another version): what each use does. */
class UnreadableStoredDataTest {
    private val bytes = lcgBytes(200_000, seed = 5)

    private suspend fun DownloadsHarness.storeStreamJson(id: Long, json: String) =
        data.db.write { q.downloadQueries.setStream(json, cdnChoice().addonUrl, "Streams", id) }

    private suspend fun DownloadsHarness.storeMetaJson(json: String) = data.db.write {
        val stored = assertNotNull(q.downloadQueries.selectMeta(SHOW.id).executeAsOneOrNull())
        q.downloadQueries.upsertMeta(stored.copy(meta_json = json))
    }

    @Test
    fun aCompletedDownloadWhoseStreamCantBeReadStillPlaysItsFile() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)

        h.storeStreamJson(id, """{"url":""")

        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id), "the file is still there")
        assertContentEquals(bytes, read(Path(local.path)))
        assertEquals(bytes.size.toLong(), local.size)
        assertFalse(local.stream.playable, "it plays as its file: nothing the stream history would keep")
    }

    @Test
    fun aSavedMetaThatCantBeReadLeavesTheArtworkMissingUntilItIsSavedAgain() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.routes["img.example"] = { respond(ByteArray(10), HttpStatusCode.OK, headers(HttpHeaders.ContentType to "image/jpeg")) }
        val manager = h.install()
        val id = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice()).id
        h.storeMetaJson("""{"id":""")
        h.settle()

        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
        assertEquals(false, h.data.downloads.download(id)?.artworkSaved, "nothing says what to fetch: it stays missing")

        // Saved again (the title's next download, a Keep synced run), it is fetched with the next missing extras.
        h.storeMetaJson(AddonJson.encodeToString(MetaItem.serializer(), SHOW))
        h.network(online = false)
        h.network(online = true)
        val meta = assertNotNull(manager.savedMeta("series", SHOW.id))
        assertTrue(assertNotNull(meta.videos.first { it.id == "tt0903747:1:2" }.thumbnail).startsWith("file:///"))
        assertTrue(assertNotNull(meta.preview.poster).startsWith("file:///"))
    }

    @Test
    fun autoRemoveKeepsTheEpisodesOfASeriesWhoseSavedMetaCantBeRead() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.data.deviceSettings.set(DeviceSetting.DownloadAutoRemoveWatched, true)
        val manager = h.install()
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv"))
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("2.mkv")).id
        h.settle()
        h.storeMetaJson("""{"id":""")

        // The first episode watched to its end: the series counts a watch; the second is still unwatched.
        h.data.library.upsertFromMeta(h.profileId, SHOW.librarySnapshot())
        h.data.library.recordProgress(h.profileId, SHOW.id, "tt0903747:1:1", 2_900_000, 3_000_000, SHOW, watched = true)
        h.settle()

        assertEquals(DownloadStatus.COMPLETED, h.download(second)?.status, "an unwatched episode stays")
    }
}
