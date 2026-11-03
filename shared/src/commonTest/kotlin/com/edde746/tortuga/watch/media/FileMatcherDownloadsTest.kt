package com.edde746.tortuga.watch.media

import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.STREAM_ADDON
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.downloads.downloaded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A guest's files over a real DownloadManager: its download of the room's video comes first. */
class FileMatcherDownloadsTest {
    private val bytes = lcgBytes(100_000, seed = 23)

    /** The Shawshank Redemption as a host opened it through another addon's TMDB ids. */
    private val room = RoomMedia(
        type = "movie",
        metaId = "tmdb:278",
        videoId = "tmdb:278",
        imdbId = FILM.id,
        tmdbId = "278",
        title = FILM.name,
        fingerprint = FileFingerprint(HOUR),
    )

    @Test
    fun theGuestsDownloadComesFirstAndItsOwnStreamIsntListedAgain() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.serveStreams { listOf(urlStream("https://cdn.example/film.mkv"), urlStream("https://cdn.example/other.mkv")) }
        val other = h.createProfile("Sam", listOf(STREAM_ADDON))
        val manager = h.install()
        h.downloaded(FILM, FILM.id, cdnChoice())
        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        val title = RoomTitle(FILM, FILM.id)

        // Under the room's mapped IMDb id, the download is first, then the addons' other streams.
        val files = h.graph.fileMatcher(h.profileId).files(room, title).files
        assertEquals(listOf(local to "film.mkv", null to "other.mkv"), files.map { it.local to it.choice.path })
        assertEquals(cdnChoice(), files.first().choice)

        // Another profile's download isn't its own: only streams.
        val theirs = h.graph.fileMatcher(other).files(room, title).files
        assertEquals(listOf(null to "film.mkv", null to "other.mkv"), theirs.map { it.local to it.choice.path })
    }
}
