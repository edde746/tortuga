package com.edde746.tortuga.downloads

import app.cash.sqldelight.Query
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.headers
import com.edde746.tortuga.playback.session.urlStream
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Download season…" on a long series: the work per episode queued doesn't grow with the series. */
class LongSeriesDownloadsTest {
    @Test
    fun aSeasonQueuedSavesTheSeriesDetailsOnce() = downloadsTest(fake = true) { h ->
        h.serveStreams { videoId -> listOf(urlStream("https://cdn.example/$videoId.mkv")) }
        val manager = h.install()
        val show = MetaItem(MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece"), CountedVideos.episodes("tt0388629", 100))
        var writes = 0
        val listener = Query.Listener { writes++ }
        val saved = h.data.db.database.downloadQueries.selectMeta(show.id)
        saved.addListener(listener)

        val queued = manager.enqueueEpisodes(h.profileId, show, season = 1, SeasonPick.ALL, reference = null)
        saved.removeListener(listener)

        assertEquals(100, queued)
        assertEquals(1, writes, "the saved details are written once for the whole season")
        assertEquals(show.name, manager.savedMeta(show.type, show.id)?.name)
    }

    @Test
    fun theNextEpisodesOfALongSeriesArePickedWithoutComparingEveryEpisode() = downloadsTest(fake = true) { h ->
        h.serveStreams { videoId -> listOf(urlStream("https://cdn.example/$videoId.mkv")) }
        val videos = CountedVideos.episodes("tt0388629", 1_000)
        val show = MetaItem(MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece"), videos)
        h.data.library.upsertFromMeta(h.profileId, show.librarySnapshot())
        h.data.library.advanceToVideo(h.profileId, show, "tt0388629:1:500")
        val manager = h.install()
        videos.reads = 0

        val queued = manager.enqueueEpisodes(h.profileId, show, season = null, SeasonPick.NEXT_5, reference = null)
        h.settle()

        assertEquals(5, queued)
        assertEquals((500..504).map { "tt0388629:1:$it" }, manager.downloads(h.profileId).first().map { it.videoId }.sorted())
        assertTrue(videos.reads <= 20 * 1_000, "${videos.reads} reads of 1000 videos")
    }

    /**
     * The artwork a season's downloads still miss is saved when the network comes back with one read of the series'
     * saved details (a long series' are megabytes), not one per episode. Counted with details that can't be read,
     * whose every read is logged.
     */
    @Test
    fun theMissingArtworkOfASeasonIsSavedWithOneReadOfTheSeriesDetails() = downloadsTest { h ->
        h.downloadSeason()
        assertTrue(h.data.downloads.downloads().none { it.artworkSaved }, "no image answered")
        h.storeMetaJson("""{"id":""")
        val reads = mutableListOf<String>()
        val sink = Log.sink
        Log.sink = Log.Sink { _, _, message, _ -> if ("saved meta" in message) reads += message }
        try {
            h.network(online = false)
            h.network(online = true)
        } finally {
            Log.sink = sink
        }
        assertEquals(1, reads.size, "reads of the saved details for 20 episodes: $reads")

        // Readable again: every episode's artwork is saved with the next missing extras.
        h.storeMetaJson(AddonJson.encodeToString(MetaItem.serializer(), SEASON))
        h.serveImages()
        h.network(online = false)
        h.network(online = true)
        val rows = h.data.downloads.downloads()
        assertEquals(20, rows.size)
        assertTrue(rows.all { it.artworkSaved }, "every episode's artwork is saved")
        val meta = assertNotNull(h.manager.savedMeta(SEASON.type, SEASON.id))
        assertTrue(meta.videos.all { assertNotNull(it.thumbnail).startsWith("file:///") }, "every thumbnail is a file")
        assertTrue(assertNotNull(meta.preview.poster).startsWith("file:///"))
    }

    @Test
    fun theDownloadsOfASeriesShareOneReadingOfItsSavedArtwork() = downloadsTest { h ->
        h.serveImages()
        h.downloadSeason()

        val rows = h.data.downloads.downloads()
        assertEquals(21, rows.first().artwork.size, "the poster and 20 thumbnails")
        assertTrue(rows.all { it.artwork === rows.first().artwork }, "the series' saved artwork is decoded once for its 20 downloads")
    }

    /** Downloads [SEASON]'s 20 episodes. */
    private suspend fun DownloadsHarness.downloadSeason() {
        serveCdn(ByteArray(1_000) { it.toByte() })
        serveStreams { videoId -> listOf(urlStream("https://cdn.example/$videoId.mkv")) }
        val manager = install()
        assertEquals(20, manager.enqueueEpisodes(profileId, SEASON, season = 1, SeasonPick.ALL, reference = null))
        settle()
        assertTrue(manager.all.value.all { it.status == DownloadStatus.COMPLETED }, "${manager.all.value.map { it.status }}")
    }

    private fun DownloadsHarness.serveImages() {
        routes["img.example"] = { respond(ByteArray(10), HttpStatusCode.OK, headers(HttpHeaders.ContentType to "image/jpeg")) }
    }

    private suspend fun DownloadsHarness.storeMetaJson(json: String) = data.db.write {
        val stored = assertNotNull(q.downloadQueries.selectMeta(SEASON.id).executeAsOneOrNull())
        q.downloadQueries.upsertMeta(stored.copy(meta_json = json))
    }

    private companion object {
        /** A season of 20 episodes, each with its thumbnail. */
        val SEASON = MetaItem(
            MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece", poster = "https://img.example/op.jpg"),
            (1..20).map { n -> Video(id = "tt0388629:1:$n", seriesInfo = SeriesInfo(1, n), thumbnail = "https://img.example/1-$n.jpg") },
        )
    }
}
