package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.FakeAppPlatform
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.presentation.board.toCard
import com.edde746.tortuga.presentation.downloads.downloaded
import com.edde746.tortuga.presentation.downloads.installMetaAddon
import com.edde746.tortuga.presentation.downloads.serveMeta
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A card menu's marks over the real app (Home, Discover, Search, Library, Detail's More like this): a mark that needs
 * the title's details takes those saved with its downloads when no addon answers, says so over the app's screens where
 * there are none, and lands even when the session closes while the details load.
 */
class CardMarksTest {
    @Test
    fun aContinueWatchingMarkTakesTheDetailsSavedWithTheDownloadWhenNoAddonAnswers() = downloadsTest { h ->
        h.serveCdn(lcgBytes(10_000, seed = 23))
        h.routes["img.example"] = { respond(ByteArray(10), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg")) }
        h.serveMeta(SHOW, reachable = { false })
        h.installMetaAddon()
        h.install()
        val episode = "tt0903747:1:2"
        h.downloaded(SHOW, episode, cdnChoice("1-2.mkv"))
        assertTrue(h.manager.savedMeta("series", SHOW.id)?.preview?.poster.orEmpty().startsWith("file:///"), "the download saved its poster")
        h.data.library.upsertFromMeta(h.profileId, SHOW.librarySnapshot())
        h.data.library.recordProgress(h.profileId, SHOW.id, episode, 600_000, 2_400_000)
        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(), start = null, profileAccount = null)
        val board = app.boardStore()
        suspend fun watched(videoId: String) =
            WatchedBitField.of(h.data.library.item(h.profileId, SHOW.id)?.state?.watchedVideos, SHOW).isWatched(videoId)

        h.network(online = false)
        board.continueWatchingAction(board.state.value.continueWatching.single(), MediaAction.MARK_WATCHED)
        h.settle()
        assertTrue(watched(episode), "offline, the episode is marked from the saved details")
        assertEquals(SHOW.preview.poster, h.data.library.item(h.profileId, SHOW.id)?.poster, "the library item keeps the addon's poster, not this device's file")
        val next = board.state.value.continueWatching.single()
        assertEquals("tt0903747:1:3", next.videoId, "the card moves on to the next episode")

        // Online, with the addon not answering: the saved details stand in again.
        h.network(online = true)
        board.continueWatchingAction(next, MediaAction.MARK_WATCHED)
        h.settle()
        assertTrue(watched("tt0903747:1:3"), "online with no addon answering, the episode is marked from the saved details")
        app.close()
    }

    @Test
    fun aMarkWithoutTheTitlesDetailsSaysSoOverTheAppFromEveryScreen() = downloadsTest { h ->
        h.serveMeta(reachable = { false })
        h.installMetaAddon()
        h.install()
        h.data.library.addToLibrary(h.profileId, SHOW.librarySnapshot())
        h.network(online = false)
        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(), start = null, profileAccount = null)
        val board = app.boardStore()
        val discover = app.discoverStore()
        val search = app.searchStore()
        val library = app.libraryStore()
        val detail = app.detailStore(Route.Detail(SHOW.type, SHOW.id))
        h.settle()

        val card = SHOW.preview.toCard()
        val text = T.mediaMenu.markFailed(isolate(SHOW.name))
        val screens = listOf<Pair<String, () -> Unit>>(
            "Home" to { board.menuAction(card, MediaAction.MARK_WATCHED) },
            "Discover" to { discover.menuAction(card, MediaAction.MARK_WATCHED) },
            "Search" to { search.menuAction(card, MediaAction.MARK_WATCHED) },
            "Library" to { library.menuAction(SHOW.id, MediaAction.MARK_WATCHED) },
            "More like this" to { detail.relatedMenuAction(card, MediaAction.MARK_WATCHED) },
        )
        for ((screen, mark) in screens) {
            mark()
            h.settle()
            assertEquals(text, app.state.value.menuNotice, screen)
            app.dismissMenuNotice()
            h.settle()
            assertNull(app.state.value.menuNotice, "$screen: once shown, it goes")
        }
        assertFalse(h.data.library.observeMarks(h.profileId).first().getValue(SHOW.id).watched, "nothing is marked")

        board.menuAction(card, MediaAction.MARK_WATCHED)
        board.release()
        h.settle()
        assertEquals(text, app.state.value.menuNotice, "the screen it was picked on gone, the notice still shows")
        detail.release()
        app.close()
    }

    @Test
    fun aMarkLandsWhenTheSessionClosesWhileTheDetailsLoad() = downloadsTest { h ->
        val shows = (1..4).map { n -> MetaItem(SHOW.preview.copy(id = "tt100000$n", name = "Show $n"), SHOW.videos) }
        h.routes["meta.example.com"] = { request ->
            delay(2.seconds)
            val id = request.url.segments.last().removeSuffix(".json")
            json("""{"meta":${AddonJson.encodeToString(MetaItem.serializer(), shows.single { it.id == id })}}""")
        }
        h.installMetaAddon()
        h.install()
        shows.forEach { h.data.library.addToLibrary(h.profileId, it.librarySnapshot()) }
        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(), start = null, profileAccount = null)
        val board = app.boardStore()
        val discover = app.discoverStore()
        val search = app.searchStore()
        val library = app.libraryStore()
        h.settle()

        board.menuAction(shows[0].preview.toCard(), MediaAction.MARK_WATCHED)
        discover.menuAction(shows[1].preview.toCard(), MediaAction.MARK_WATCHED)
        search.menuAction(shows[2].preview.toCard(), MediaAction.MARK_WATCHED)
        library.menuAction(shows[3].id, MediaAction.MARK_WATCHED)
        h.settle()
        // A profile switch or a sign-out while the details load: the session releases its screens' stores.
        board.release()
        discover.release()
        search.release()
        library.release()
        app.close()
        h.advance(5.seconds)

        val marks = h.data.library.observeMarks(h.profileId).first()
        assertEquals(listOf(true, true, true, true), shows.map { marks.getValue(it.id).watched })
    }
}
