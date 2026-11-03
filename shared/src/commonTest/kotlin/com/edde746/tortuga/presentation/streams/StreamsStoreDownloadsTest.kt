package com.edde746.tortuga.presentation.streams

import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Streams sheet over a real DownloadManager: download mode, and each row's Download action. */
class StreamsStoreDownloadsTest {
    private val bytes = lcgBytes(100_000, seed = 9)

    private class Host : StreamsHost {
        val played = mutableListOf<PlayRequest>()
        var closed = 0

        override fun play(request: PlayRequest) {
            played += request
        }

        override fun playWith(request: PlayRequest, playerId: String) {
            played += request
        }

        override fun openUrl(url: String) = Unit

        override fun openDeepLink(link: DeepLink) = Unit

        override fun closeStreams() {
            closed++
        }
    }

    /** 1080p and 720p files at `cdn.example`, with a trailer between them that opens outside the app. */
    private val streams = listOf(
        urlStream("https://cdn.example/1080.mkv", name = "Streams\n1080p"),
        Stream(StreamSource.YouTube("dQw4w9WgXcQ"), name = "Trailer"),
        urlStream("https://cdn.example/720.mkv", name = "Streams\n720p"),
    )

    @Test
    fun inDownloadModeAPickQueuesItsDownloadAndClosesTheSheet() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { streams }
        val manager = h.install()
        val host = Host()
        val store = h.graph.streamsStore(h.profileId, FILM, FILM.id, host, notice = null, download = true)
        h.settle()
        val state = store.state.value
        assertTrue(state.downloadMode)
        assertEquals(T.streams.download.notice, state.notice)
        val rows = state.groups.single().streams
        assertEquals(listOf("Streams 1080p", "Streams 720p"), rows.map { it.name }, "what opens outside the app can't be downloaded")
        assertEquals(listOf(null, null), rows.map { it.downloadActionText })

        store.pick(rows[1].id)
        store.pick(rows[0].id)
        h.settle()
        assertEquals(1, host.closed)
        assertEquals(emptyList(), host.played)
        val queued = manager.downloads(h.profileId).first().single()
        assertEquals(FILM.id, queued.videoId)
        assertEquals("https://cdn.example/720.mkv", h.fakeTransport.started.single().url, "the first pick; the second came while it was queued")
        assertNull(store.state.value.downloadMessage)
    }

    @Test
    fun aRowsDownloadActionQueuesItAndSaysSoWhileAPickStillPlays() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { streams }
        val manager = h.install()
        val host = Host()
        val store = h.graph.streamsStore(h.profileId, SHOW, "tt0903747:1:2", host, notice = null)
        h.settle()
        var state = store.state.value
        assertFalse(state.downloadMode)
        assertNull(state.notice)
        val rows = state.groups.single().streams
        assertEquals(listOf("Streams 1080p", "Trailer", "Streams 720p"), rows.map { it.name })
        assertEquals(listOf(T.streams.download.action, null, T.streams.download.action), rows.map { it.downloadActionText })

        store.download(rows[1].id)
        h.settle()
        assertNull(store.state.value.downloadMessage, "the trailer can't be downloaded")
        store.download(rows[0].id)
        h.settle()
        state = store.state.value
        assertEquals(T.streams.download.queued, state.downloadMessage)
        assertEquals(0, host.closed)
        assertEquals(listOf("tt0903747:1:2"), manager.downloads(h.profileId).first().map { it.videoId })
        assertEquals("https://cdn.example/1080.mkv", h.fakeTransport.started.single().url)
        store.dismissDownloadMessage()
        assertNull(store.state.value.downloadMessage)

        store.pick(rows[2].id)
        val played = host.played.single()
        assertEquals(StreamSource.Url("https://cdn.example/720.mkv"), played.stream.stream.source)
        assertNull(played.local, "a pick streams")
    }
}
