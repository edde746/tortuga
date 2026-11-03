package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.trackers.MAX_APP_ANSWER_BYTES
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.trakt.TraktRelated
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Detail's "More like this" ([DetailUiState.related]) over fake addons and a fake Trakt: the addon's `similar` catalog
 * first, then Trakt's related titles, each once; a title without an IMDb id has the addon alone; a failing source
 * leaves the other's titles; Trakt's answers are kept and its 429 respected.
 */
class DetailStoreRelatedTest {
    private class Host : DetailHost {
        val opened = mutableListOf<String>()

        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) = Unit

        override fun openDownloadStreams(meta: MetaItem, videoId: String) = Unit

        override fun play(request: PlayRequest) = Unit

        override fun openUrl(url: String) = Unit

        override fun openDeepLink(link: DeepLink) = Unit

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) = Unit

        override fun openKeepSynced() = Unit

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) {
            opened += "$type/$id"
        }

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
    }

    /** The meta addon (Cinemeta's ids and a Kitsu id), and an addon whose `similar` catalogs answer [similar]. */
    private val metas = addon(
        """{"id":"org.example.meta","version":"1.0.0","name":"meta","types":["movie","series"],"resources":["meta"],"idPrefixes":["tt","kitsu:"]}""",
        "meta",
    )
    private val similar = addon(
        """{"id":"org.example.similar","version":"1.0.0","name":"similar","types":["movie","series"],"resources":["catalog"],
           "catalogs":[{"type":"movie","id":"similar","name":"More Like This","extra":[{"name":"similar","isRequired":true}]},
                       {"type":"series","id":"similar","name":"More Like This","extra":[{"name":"similar","isRequired":true}]}]}""",
        "similar",
    )

    private fun addon(manifest: String, host: String) = Descriptor(Manifest.decode(manifest), "https://$host.example.com/manifest.json".transportUrl())

    /**
     * One fake network: the meta addon has [TITLE] and [ANIME]; the similar addon answers [similarAnswer] for each id;
     * Trakt answers [traktAnswer] for each `/{kind}/{imdb}/related`, counting its calls.
     */
    private class Fixture(private val scope: TestScope, private val addons: List<Descriptor>) {
        val similarAnswer = mutableMapOf<String, suspend MockRequestHandleScope.() -> HttpResponseData>()
        val traktAnswer = mutableMapOf<String, suspend MockRequestHandleScope.() -> HttpResponseData>()
        val traktCalls = mutableListOf<String>()
        val data = LocalData(inMemoryDatabase(), { NOW }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val host = Host()
        val http = FakeHttp(scope) { request -> answer(request) }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))
        val trakt = TraktRelated(
            TrackerHttp(http.client, TrackerService.TRAKT, maxBodyBytes = MAX_APP_ANSWER_BYTES),
            mapOf("trakt-api-version" to "2", "trakt-api-key" to "client"),
            scope.testScheduler.timeSource,
        )

        private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath.decodeURLPart()
            return when (request.url.host) {
                "meta.example.com" -> when (path) {
                    "/meta/movie/$TITLE.json" -> json("""{"meta":{"id":"$TITLE","type":"movie","name":"The Shawshank Redemption"}}""")
                    "/meta/series/$ANIME.json" -> json("""{"meta":{"id":"$ANIME","type":"series","name":"Cowboy Bebop"}}""")
                    else -> respond("", HttpStatusCode.NotFound)
                }
                "similar.example.com" -> {
                    val id = path.substringAfter("similar=").removeSuffix(".json")
                    similarAnswer[id]?.invoke(this) ?: json("""{"metas":[]}""")
                }
                "api.trakt.tv" -> {
                    traktCalls += "$path?${request.url.encodedQuery}"
                    assertEquals("client", request.headers["trakt-api-key"], "the app's client id alone")
                    assertNull(request.headers[HttpHeaders.Authorization], "no account")
                    val id = path.removeSuffix("/related").substringAfterLast('/')
                    traktAnswer[id]?.invoke(this) ?: json("[]")
                }
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }

        fun store(type: String = "movie", id: String = TITLE) = DetailStore(
            profileId = PROFILE,
            type = type,
            id = id,
            videoId = null,
            shownVideoId = null,
            aggregator = aggregator,
            installedAddons = { addons },
            library = data.library,
            streamHistory = data.streamHistory,
            settings = data.settings.observe(PROFILE),
            autoPicker = StreamAutoPicker(aggregator, data.streamHistory),
            host = host,
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(NOW)
            },
            parentScope = scope.backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
            relatedTitles = RelatedTitles(aggregator, trakt),
            titleMarks = data.library.observeMarks(PROFILE),
        )
    }

    private fun DetailStore.ids() = assertNotNull(state.value.related, "the row shows").cards.map { it.id }

    @Test
    fun theAddonsTitlesComeFirstThenTraktsEachOnceAndNeverTheTitleItself() = runTest {
        val f = Fixture(this, listOf(metas, similar))
        f.similarAnswer[TITLE] = {
            metas(
                meta("tt0068646", "The Godfather"),
                // An addon of TMDB ids that names the IMDb title too: Trakt's Goodfellas is the same title.
                meta("tmdb:769", "GoodFellas", imdb = "tt0099685"),
                meta(TITLE, "The Shawshank Redemption"),
            )
        }
        f.traktAnswer[TITLE] = {
            trakt(
                related("tt0099685", "Goodfellas", 1990),
                related("tt0061512", "Cool Hand Luke", 1967, poster = "media.trakt.tv/images/movies/000/000/746/posters/medium/311e1de72a.jpg.webp"),
                related("tt0068646", "The Godfather", 1972),
                related(null, "No IMDb id", 2001),
            )
        }
        val store = f.store()
        settle()

        val row = assertNotNull(store.state.value.related)
        assertEquals("More like this", row.title)
        assertEquals(false, row.loading)
        assertNull(row.emptyText)
        assertEquals(listOf("tt0068646", "tmdb:769", "tt0061512"), row.cards.map { it.id })
        val luke = row.cards.last()
        assertEquals("Cool Hand Luke", luke.title)
        assertEquals("1967", luke.year)
        assertEquals("https://media.trakt.tv/images/movies/000/000/746/posters/medium/311e1de72a.jpg.webp", luke.posterUrl)
        assertEquals(listOf("/movies/$TITLE/related?extended=images&limit=20"), f.traktCalls)

        store.openRelated(luke)
        assertEquals(listOf("movie/tt0061512"), f.host.opened)
    }

    @Test
    fun theRowHoldsAtMostTwentyTitles() = runTest {
        val f = Fixture(this, listOf(metas, similar))
        f.similarAnswer[TITLE] = { metas(*(1..15).map { meta("tt10000$it", "Addon $it") }.toTypedArray()) }
        f.traktAnswer[TITLE] = { trakt(*(1..15).map { related("tt20000$it", "Trakt $it", 2000) }.toTypedArray()) }
        val store = f.store()
        settle()
        val ids = store.ids()
        assertEquals(RelatedTitles.LIMIT, ids.size)
        assertEquals((1..15).map { "tt10000$it" } + (1..5).map { "tt20000$it" }, ids)
    }

    @Test
    fun aTitleWithoutAnImdbIdHasTheAddonAlone() = runTest {
        val f = Fixture(this, listOf(metas, similar))
        f.similarAnswer[ANIME] = { metas(meta("kitsu:6", "Trigun", type = "series")) }
        val store = f.store(type = "series", id = ANIME)
        settle()
        assertEquals(listOf("kitsu:6"), store.ids())
        assertEquals(emptyList(), f.traktCalls, "Trakt is never asked")
    }

    @Test
    fun theRowWaitsForBothSourcesAndShowsLoadingMeanwhile() = runTest {
        val f = Fixture(this, listOf(metas, similar))
        f.similarAnswer[TITLE] = {
            delay(5.seconds)
            metas(meta("tt0068646", "The Godfather"))
        }
        f.traktAnswer[TITLE] = { trakt(related("tt0061512", "Cool Hand Luke", 1967)) }
        val store = f.store()
        runCurrent()
        advanceTimeBy(4.seconds)
        val loading = assertNotNull(store.state.value.related)
        assertTrue(loading.loading, "Trakt has answered, the addon not yet")
        assertEquals(emptyList(), loading.cards)
        settle()
        assertEquals(listOf("tt0068646", "tt0061512"), store.ids())
    }

    @Test
    fun aSourceThatFailsLeavesTheOthersTitles() = runTest {
        val traktDown = Fixture(this, listOf(metas, similar))
        traktDown.similarAnswer[TITLE] = { metas(meta("tt0068646", "The Godfather")) }
        traktDown.traktAnswer[TITLE] = { respond("", HttpStatusCode.ServiceUnavailable) }
        val withoutTrakt = traktDown.store()
        settle()
        assertEquals(listOf("tt0068646"), withoutTrakt.ids())

        val addonDown = Fixture(this, listOf(metas, similar))
        addonDown.similarAnswer[TITLE] = { respond("", HttpStatusCode.InternalServerError) }
        addonDown.traktAnswer[TITLE] = { trakt(related("tt0061512", "Cool Hand Luke", 1967)) }
        val withoutAddon = addonDown.store()
        settle()
        assertEquals(listOf("tt0061512"), withoutAddon.ids())
    }

    @Test
    fun noRowWhenNoSourceAnswersAndAnEmptyOneWhenTheyAnswerWithNone() = runTest {
        val down = Fixture(this, listOf(metas, similar))
        down.similarAnswer[TITLE] = { respond("", HttpStatusCode.InternalServerError) }
        down.traktAnswer[TITLE] = { respond("", HttpStatusCode.InternalServerError) }
        val failed = down.store()
        settle()
        assertEquals("The Shawshank Redemption", (failed.state.value.body as? DetailBody.Page)?.header?.title)
        assertNull(failed.state.value.related, "every source failed")

        // No similar catalog installed and no IMDb id: nothing is asked, nothing shows.
        val none = Fixture(this, listOf(metas)).store(type = "series", id = ANIME)
        settle()
        assertEquals("Cowboy Bebop", (none.state.value.body as? DetailBody.Page)?.header?.title)
        assertNull(none.state.value.related)

        // Trakt doesn't know the title (an empty list), and no addon has one.
        val unknown = Fixture(this, listOf(metas))
        val empty = unknown.store()
        settle()
        val row = assertNotNull(empty.state.value.related)
        assertEquals(emptyList(), row.cards)
        assertEquals("No similar titles found.", row.emptyText)
        assertEquals(1, unknown.traktCalls.size)
    }

    @Test
    fun theCardsCarryTheLibrarysMarks() = runTest {
        val f = Fixture(this, listOf(metas))
        f.traktAnswer[TITLE] = { trakt(related("tt0061512", "Cool Hand Luke", 1967), related("tt0068646", "The Godfather", 1972)) }
        val store = f.store()
        settle()
        f.data.library.addToLibrary(PROFILE, MetaSnapshot("tt0068646", "movie", "The Godfather", null, "poster"))
        settle()
        val cards = assertNotNull(store.state.value.related).cards
        assertEquals(listOf(false, true), cards.map { it.inLibrary })
    }

    @Test
    fun traktsAnswerIsKeptForThreeHoursAndItsRateLimitRespected() = runTest {
        val f = Fixture(this, listOf(metas))
        f.traktAnswer[TITLE] = { trakt(related("tt0061512", "Cool Hand Luke", 1967)) }
        val first = f.store()
        settle()
        first.release()
        val second = f.store()
        settle()
        assertEquals(listOf("tt0061512"), second.ids())
        assertEquals(1, f.traktCalls.size, "the second visit is answered from the cache")
        second.release()

        advanceTimeBy(3.hours)
        f.traktAnswer[TITLE] = { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120")) }
        val limited = f.store()
        settle()
        assertNull(limited.state.value.related, "rate limited, and nothing else answers")
        assertEquals(2, f.traktCalls.size)
        limited.release()

        f.traktAnswer[TITLE] = { trakt(related("tt0061512", "Cool Hand Luke", 1967)) }
        val held = f.store()
        settle()
        assertNull(held.state.value.related)
        assertEquals(2, f.traktCalls.size, "no call within Retry-After")
        held.release()

        advanceTimeBy(2.minutes)
        val after = f.store()
        settle()
        assertEquals(listOf("tt0061512"), after.ids())
        assertEquals(3, f.traktCalls.size)
    }

    /**
     * Runs the stores, which live in the test's background scope (which `advanceUntilIdle` leaves alone), and a minute
     * of their waits.
     */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1.minutes)
        runCurrent()
    }

    private companion object {
        const val PROFILE = "p"
        const val TITLE = "tt0111161"
        const val ANIME = "kitsu:1"
        const val NOW = 1_700_000_000_000L

        fun meta(id: String, name: String, type: String = "movie", imdb: String? = null) =
            """{"id":"$id","type":"$type","name":"$name"${imdb?.let { ",\"imdb_id\":\"$it\"" }.orEmpty()}}"""

        fun MockRequestHandleScope.metas(vararg metas: String) = json("""{"metas":[${metas.joinToString(",")}]}""")

        fun related(imdb: String?, title: String, year: Int, poster: String? = null) = buildString {
            append("""{"title":"$title","year":$year,"ids":{"trakt":1,"slug":"x"""")
            if (imdb != null) append(""","imdb":"$imdb"""")
            append("}")
            if (poster != null) append(""","images":{"poster":["$poster"]}""")
            append("}")
        }

        fun MockRequestHandleScope.trakt(vararg titles: String) = json("[${titles.joinToString(",")}]")
    }
}
