package com.edde746.tortuga.app

import com.edde746.tortuga.downloads.KeepSynced
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.downloads.KeepSyncedBody
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Keep synced in the running app: wherever the device has downloads, Downloads leads to its screen and what the rules
 * queue on their own is told once.
 */
class KeepSyncedAppTest {
    private val bytes = lcgBytes(100_000, seed = 31)

    @Test
    fun downloadsOpensTheScreenAndARunOnItsOwnIsToldUntilShown() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(), start = null, profileAccount = null)
        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        h.settle()

        val downloads = assertNotNull(app.downloadsStore())
        h.settle()
        assertNotNull(downloads.state.value.keepSynced, "Downloads leads to the screen")
        downloads.openKeepSynced()
        assertEquals(Route.KeepSynced, app.navigator.current)
        val rules = assertNotNull(app.keepSyncedStore())
        h.settle()
        assertEquals(1, assertIs<KeepSyncedBody.Rules>(rules.state.value.body).rules.size)
        rules.release()
        assertNull(app.state.value.keepSyncedNotice, "the user's own rule isn't announced")

        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        h.settle()
        assertEquals(T.downloads.keepSynced.notice(1, isolate("Breaking Bad")), app.state.value.keepSyncedNotice)
        app.dismissKeepSyncedNotice()
        h.settle()
        assertNull(app.state.value.keepSyncedNotice)
        downloads.release()
        app.close()
    }

    private fun stream(videoId: String): List<Stream> =
        listOf(urlStream("https://cdn.example/${videoId.replace(':', '-')}.mkv", name = "720p", bingeGroup = "rel-720"))
}
