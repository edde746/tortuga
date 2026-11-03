package com.edde746.tortuga.playback

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How an error video is told ([ErrorVideoSign]): each sign, and the real videos none of them trips on. */
class ErrorVideoSignTest {
    private val mp4 = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".encodeToByteArray()
    private val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val mib = 1024L * 1024
    private val gb = 1_000_000_000L

    private fun resolved(url: String, served: Long?, hinted: Long?, head: ByteArray = mp4) =
        ErrorVideoSign.ofResolve(Url(url), served, hinted, head)

    @Test
    fun addonErrorVideosAreKnownByTheirPathShape() {
        val errorVideos = listOf(
            "https://addon.example/videos/failed_access_v2.mp4",
            "https://addon-a.example/videos/downloading_v2.mp4",
            "https://addon-b.example/videos/not_ready.mp4",
            "https://addon-c.example/v0/store/_/static/download_failed.mp4",
            "https://addon-d.example/static/exceptions/transfer_error.mp4",
            "https://addon.example/static/payment_required.mp4",
        )
        for (url in errorVideos) assertEquals(ErrorVideoSign.Url, resolved(url, served = null, hinted = null), url)
        assertEquals(
            ErrorVideoSign.Url,
            resolved("https://addon.example/videos/not_ready.mp4", served = 2_048, hinted = null, head = "#EXTM3U\n".encodeToByteArray()),
            "the link tells it before the body does",
        )
    }

    @Test
    fun ordinaryLinksAreNoErrorVideos() {
        val videos = listOf(
            "https://cdn.example.net/dl/abc/Show.S01E11.1080p.mkv",
            "https://27.download.host.example/d/ABCDEF1234567/Show.S01E11.1080p.WEB-DL.mkv",
            "https://rr4---sn-4g5e6nzz.googlevideo.com/videoplayback?expire=1760000000&itag=22",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://media.example.org/videos/Show.S01E11.1080p.mp4",
            "https://media.example.org/videos/holiday_v2.mp4",
            "https://cdn.example.net/static/trailer.mp4",
        )
        for (url in videos) assertNull(resolved(url, served = 1_400_000_000, hinted = 1_400_000_000), url)
    }

    @Test
    fun aSmallFileWhereTheAddonSaidAFiftyTimesLargerOneIsAnErrorVideo() {
        assertEquals(ErrorVideoSign.Size(2_000_000, 1_200_000_000), resolved(URL, served = 2_000_000, hinted = 1_200_000_000))
        assertEquals(ErrorVideoSign.Size(2_000_000, 100_000_000), resolved(URL, served = 2_000_000, hinted = 100_000_000), "50 times")
        assertNull(resolved(URL, served = 2_000_000, hinted = 99_999_999), "less than 50 times")
        assertNull(resolved(URL, served = 2_000_000, hinted = null), "the addon said no size")
        assertNull(resolved(URL, served = null, hinted = 1_200_000_000), "the server said no size")
        assertNull(resolved(URL, served = 300_000_000, hinted = 1_200_000_000), "another encode of the episode")
        assertEquals(ErrorVideoSign.Size(8 * mib, 4 * gb), resolved(URL, served = 8 * mib, hinted = 4 * gb))
        assertNull(resolved(URL, served = 8 * mib + 1, hinted = 4 * gb), "larger than any error video")
        assertNull(resolved(URL, served = 700_000_000, hinted = 40 * gb), "an episode under a season pack's size")
    }

    @Test
    fun aPlaylistOrAPageSaysNothingByItsSize() {
        for (body in listOf("#EXTM3U\n#EXT-X-VERSION:3\n", "<!DOCTYPE html>", """{"error":"not ready"}""")) {
            assertNull(resolved(URL, served = 2_048, hinted = 1_200_000_000, head = body.encodeToByteArray()), body)
        }
    }

    @Test
    fun aShortFileIsAnErrorVideoWhereTheVideoRanTenMinutesOrMore() {
        assertEquals(ErrorVideoSign.Duration(120.0, 1440.0), ErrorVideoSign.ofDuration(120.0, 1440.0))
        assertEquals(ErrorVideoSign.Duration(150.0, 600.0), ErrorVideoSign.ofDuration(150.0, 600.0), "both bounds count")
        assertNull(ErrorVideoSign.ofDuration(151.0, 1440.0))
        assertNull(ErrorVideoSign.ofDuration(120.0, 599.0))
        assertNull(ErrorVideoSign.ofDuration(120.0, null), "a video never played: nothing to compare with")
        assertNull(ErrorVideoSign.ofDuration(120.0, 300.0), "a short that ran as short before")
        assertNull(ErrorVideoSign.ofDuration(1300.0, 1440.0), "another cut of the episode")
        assertNull(ErrorVideoSign.ofDuration(0.0, 1440.0), "no length yet")
        assertNull(ErrorVideoSign.ofDuration(Double.NaN, 1440.0))
    }

    @Test
    fun theHarnessMediaAreNoErrorVideos() {
        // tools/media's 10-60 s clips play as Cinemeta titles: no length known at first, then their own.
        for (seconds in listOf(10.0, 30.0, 60.0)) {
            assertNull(ErrorVideoSign.ofDuration(seconds, null), "$seconds s, first play")
            assertNull(ErrorVideoSign.ofDuration(seconds, seconds), "$seconds s, played again")
        }
        assertNull(resolved("http://192.168.1.15:8765/remux.mkv", served = 15_296_153, hinted = 15_296_153, head = mkv))
    }

    private companion object {
        const val URL = "https://cdn.example.net/dl/abc/Show.S01E11.1080p.mkv"
    }
}
