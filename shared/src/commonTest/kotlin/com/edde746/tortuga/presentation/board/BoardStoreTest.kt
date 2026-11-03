@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.complete
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.downloads.serveVideo
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoardStoreTest {
    /** Two catalogs the Board leaves out (a required genre, a required search) among three it shows. */
    private val lists = catalogAddon(
        "lists",
        """[
            {"type":"movie","id":"trending","name":"Trending","extra":[{"name":"skip"}]},
            {"type":"series","id":"by-genre","name":"By genre","extra":[{"name":"genre","isRequired":true,"options":["Drama"]}]},
            {"type":"movie","id":"nothing","name":"Nothing"},
            {"type":"movie","id":"broken","name":"Broken"},
            {"type":"series","id":"find","extra":[{"name":"search","isRequired":true}]}
        ]""",
    )

    private class Host : BoardHost {
        val opened = mutableListOf<String>()
        val previews = mutableListOf<MetaItemPreview?>()
        val discover = mutableListOf<DeepLink.Discover>()
        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) {
            opened += "meta $type/$id"
            previews += preview
        }
        override fun openVideo(type: String, id: String, videoId: String) {
            opened += "video $type/$id/$videoId"
        }
        override fun openDiscover(link: DeepLink.Discover) {
            discover += link
        }
        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) {
            opened += "detail $type/$id/$videoId $action"
        }
        override fun openDownloads() {
            opened += "downloads"
        }
        override fun openWatchTogether() {
            opened += "watch together"
        }
        override fun openLibrary() {
            opened += "library"
        }
        override fun openGuide() {
            opened += "guide"
        }
        override fun openCalendar() {
            opened += "calendar"
        }
        override fun openCollectionFolder(collectionId: String, folderId: String) {
            opened += "folder $collectionId/$folderId"
        }
    }

    private class Fixture(
        scope: TestScope,
        addons: List<Descriptor>,
        trackers: List<TrackerCatalogSource> = emptyList(),
        layout: HomeLayout = HomeLayout(),
        offersDownloads: Boolean = true,
        remote: Boolean = false,
        hidden: Set<String> = emptySet(),
    ) {
        val env = BrowseEnv(scope) { request ->
            when (request.url.host) {
                "lists.example.com" -> when (request.catalogPath) {
                    "movie/trending" -> json(
                        """{"metas":[
                            {"id":"tt1","type":"movie","name":"One","releaseInfo":"2020","imdbRating":"7.1","poster":"https://img.example/1.jpg"},
                            {"id":"tt1","type":"movie","name":"One again"},
                            {"id":"tt2","type":"movie","name":" Two ","posterShape":"landscape","released":"2019-05-06T00:00:00.000Z","background":"https://img.example/2-bg.jpg"}
                        ]}""",
                    )
                    "movie/nothing" -> json("""{"metas":[]}""")
                    "movie/trending/skip=3" -> json("""{"metas":[{"id":"tt2","type":"movie","name":"Two"},{"id":"tt3","type":"movie","name":"Three"}]}""")
                    "movie/trending/skip=5" -> json("""{"metas":[]}""")
                    else -> respond("", HttpStatusCode.InternalServerError)
                }
                "v3-cinemeta.strem.io" -> json(cinemeta("catalog-movie-top.json"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val addons = MutableStateFlow(addons)
        val host = Host()
        val trackers = MutableStateFlow(trackers)
        val layout = MutableStateFlow(layout)
        val network = MutableStateFlow(NetworkState.ONLINE)
        val watching = MutableStateFlow(0)
        val store = BoardStore(
            "p", this.addons, env.aggregator, env.data.library, env.data.library.observeContinueWatching("p", BoardStore.CONTINUE_WATCHING_LIMIT),
            this.trackers, this.layout, host, scope.backgroundScope, network, offersDownloads,
            flowOf(emptyList()), watching, flowOf(false), StandardTestDispatcher(scope.testScheduler),
            MediaActions("p", env.data.library, trackerMarks = null, scope.backgroundScope, notice = {}) { _, _ -> null }, remote,
            hiddenTitles = MutableStateFlow(hidden),
        )
    }

    /** A movie row's title: its catalog's or list's name, isolated, and the type. */
    private fun movies(name: String) = "\u2068$name\u2069 · Movies"

    /** A series row's title: its catalog's or list's name, isolated, and the type. */
    private fun series(name: String) = "\u2068$name\u2069 · Series"

    @Test
    fun rowsFollowTheAddonOrderLeaveOutRequiredExtrasAndLoadAsTheyShow() = runTest {
        val f = Fixture(this, listOf(lists, CINEMETA))
        runCurrent()
        var state = f.store.state.value
        assertEquals(
            listOf(movies("Trending"), movies("Broken"), movies("Popular"), series("Popular"), movies("Featured"), series("Featured")),
            state.rows.map { it.title },
            "no required-extra catalogs; the empty catalog is left out",
        )
        assertEquals(listOf("lists", "lists", "Cinemeta", "Cinemeta", "Cinemeta", "Cinemeta"), state.rows.map { it.sourceName })
        assertEquals(
            listOf("org.example.lists/movie/trending", "org.example.lists/movie/broken", "com.linvo.cinemeta/movie/top"),
            state.rows.take(3).map { it.key },
        )
        assertEquals(
            listOf(READY, BROKEN, RowStatus.Idle, RowStatus.Idle, RowStatus.Idle, RowStatus.Idle),
            state.rows.map { it.status },
        )
        assertEquals(
            setOf("lists.example.com movie/trending", "lists.example.com movie/nothing", "lists.example.com movie/broken"),
            f.env.http.catalogRequests.toSet(),
            "only the first rows are requested",
        )
        assertEquals(3, f.env.http.requests.size)

        f.store.rowVisible(2)
        runCurrent()
        state = f.store.state.value
        assertEquals(
            listOf(READY, BROKEN, READY, READY, RowStatus.Idle, RowStatus.Idle),
            state.rows.map { it.status },
            "a visible row loads with the one after it",
        )
        assertEquals(setOf("v3-cinemeta.strem.io movie/top", "v3-cinemeta.strem.io series/top"), f.env.http.catalogRequests.drop(3).toSet())

        f.store.rowVisible(2)
        runCurrent()
        assertEquals(5, f.env.http.requests.size, "a row loads once")
        assertTrue(state.body is BoardBody.Rows)
    }

    @Test
    fun itemsArePreformattedAndEachIdShowsOncePerRow() = runTest {
        val f = Fixture(this, listOf(lists, CINEMETA))
        runCurrent()
        f.store.rowVisible(2)
        runCurrent()
        val state = f.store.state.value
        assertEquals(
            listOf(
                MetaCard("tt1", "movie", "One", "2020", "7.1", "https://img.example/1.jpg", PosterShape.POSTER, null),
                MetaCard("tt2", "movie", "Two", "2019", null, null, PosterShape.LANDSCAPE, "https://img.example/2-bg.jpg"),
            ),
            state.rows[0].items.map { it.copy(preview = null) },
        )
        val cinemeta = state.rows[2].items
        assertEquals(49, cinemeta.size)
        assertEquals(MetaCard("tt6933238", "movie", "Unabom", "2026", null, cinemeta[0].posterUrl, PosterShape.POSTER, cinemeta[0].backgroundUrl), cinemeta[0].copy(preview = null))
        assertEquals("tt6933238", cinemeta[0].preview?.id, "each card carries its catalog item")
        assertTrue(cinemeta[0].posterUrl!!.startsWith("https://"))
        assertEquals("6.8", cinemeta[2].rating)
    }

    @Test
    fun rowsFollowTheInstalledListAndReloadOnRequest() = runTest {
        val f = Fixture(this, listOf(CINEMETA))
        runCurrent()
        assertEquals(3, f.env.http.requests.size)

        f.addons.value = listOf(lists, CINEMETA)
        runCurrent()
        val state = f.store.state.value
        assertEquals(
            listOf(movies("Trending"), movies("Broken"), movies("Popular"), series("Popular"), movies("Featured"), series("Featured")),
            state.rows.map { it.title },
        )
        assertEquals(
            listOf(READY, BROKEN, READY, READY, READY, RowStatus.Idle),
            state.rows.map { it.status },
            "rows already loaded keep their pages",
        )
        assertEquals(3, f.env.http.catalogRequests.count { it.startsWith("v3-cinemeta") })
        assertEquals(6, f.env.http.requests.size)

        f.store.retryRow(1)
        runCurrent()
        assertEquals("lists.example.com movie/broken", f.env.http.catalogRequests.last())
        assertEquals(7, f.env.http.requests.size)

        f.addons.value = listOf(CINEMETA)
        runCurrent()
        assertEquals(listOf(movies("Popular"), series("Popular"), movies("Featured"), series("Featured")), f.store.state.value.rows.map { it.title })
    }

    @Test
    fun continueWatchingLeadsWithWhereEachItemStopped() = runTest {
        val f = Fixture(this, emptyList())
        val library = f.env.data.library
        suspend fun progress(meta: MetaSnapshot, videoId: String, minutes: Double, durationMinutes: Int) {
            library.upsertFromMeta("p", meta)
            library.recordProgress("p", meta.id, videoId, (minutes * 60_000).toLong(), durationMinutes * 60_000L)
            f.env.clock.now += 1_000
        }
        progress(MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", "https://img.example/s.jpg", "poster"), "tt0111161", 60.0, 142)
        progress(MetaSnapshot("tt7366338", "series", "Chernobyl", null, "poster"), "tt7366338:1:2", 48.0, 60)
        progress(MetaSnapshot("tt0903747", "series", "Breaking Bad", null, "landscape"), "tt0903747:1:2", 40.0, 47)
        val breakingBad = MetaItem(
            MetaItemPreview(id = "tt0903747", type = "series", name = "Breaking Bad"),
            listOf(Video(id = "tt0903747:1:2", seriesInfo = SeriesInfo(1, 2)), Video(id = "tt0903747:1:3", seriesInfo = SeriesInfo(1, 3))),
        )
        library.advanceToVideo("p", breakingBad, "tt0903747:1:3")
        f.env.clock.now += 1_000
        progress(MetaSnapshot("kitsu:1", "series", "Cowboy Bebop", null, "poster"), "kitsu:1:5", 22.5, 23)
        library.upsertFromMeta("p", MetaSnapshot("tt1", "movie", "Never played", null, "poster"))
        runCurrent()

        val cards = f.store.state.value.continueWatching
        assertEquals(listOf("Cowboy Bebop", "Breaking Bad", "Chernobyl", "The Shawshank Redemption"), cards.map { it.title }, "most recent first")
        assertEquals(listOf("Episode 5 · 1 min left", "S1E3 · Up next", "S1E2 · 12 min left", "1 h 22 min left"), cards.map { it.detail })
        assertEquals(listOf(978, 0, 800, 423), cards.map { (it.progress * 1000).roundToInt() })
        assertEquals(PosterShape.LANDSCAPE, cards[1].posterShape)
        assertEquals("https://img.example/s.jpg", cards[3].posterUrl)
        assertEquals(
            listOf(false, false, true, true),
            cards.map { card -> MediaAction.PLAY_FROM_START in card.menu.map { it.action } },
            "Play from the beginning where Play resumes: not past the credits (Cowboy Bebop starts at 0), nor up next",
        )
        assertTrue(f.store.state.value.body !is BoardBody.Empty, "no catalogs, but Continue Watching is something to show")

        f.store.openContinueWatching(cards[2])
        f.store.openContinueWatching(cards[3])
        assertEquals(listOf("video series/tt7366338/tt7366338:1:2", "video movie/tt0111161/tt0111161"), f.host.opened)
    }

    @Test
    fun aContinueWatchingCardsMenuOpensItsDetailsAddsItToTheLibraryAndRemovesIt() = runTest {
        val f = Fixture(this, emptyList())
        val library = f.env.data.library
        library.upsertFromMeta("p", MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", null, "poster"))
        library.recordProgress("p", "tt0111161", "tt0111161", 3_600_000, 8_520_000)
        runCurrent()
        val card = f.store.state.value.continueWatching.single()
        assertEquals(
            listOf(
                MediaAction.PLAY_FROM_START, MediaAction.MARK_WATCHED, MediaAction.REMOVE_FROM_CONTINUE_WATCHING, MediaAction.DETAILS,
                MediaAction.CHOOSE_STREAM, MediaAction.ADD_TO_LIBRARY,
            ),
            card.menu.map { it.action },
        )

        f.store.continueWatchingAction(card, MediaAction.DETAILS)
        assertEquals(listOf("meta movie/tt0111161"), f.host.opened, "the title's page, not the video's streams a press opens")
        f.store.continueWatchingAction(card, MediaAction.ADD_TO_LIBRARY)
        runCurrent()
        assertEquals(MediaAction.REMOVE_FROM_LIBRARY, f.store.state.value.continueWatching.single().menu.last().action)
        f.store.continueWatchingAction(card, MediaAction.REMOVE_FROM_CONTINUE_WATCHING)
        runCurrent()
        assertEquals(emptyList(), f.store.state.value.continueWatching)
        assertEquals(listOf("tt0111161"), library.observeLibrary("p").first().map { it.id }, "still in the library")
    }

    @Test
    fun menuEntriesCarriedOutOnDetailOpenItThereOnTheCardsVideo() = runTest {
        val f = Fixture(this, listOf(CINEMETA))
        f.env.data.library.upsertFromMeta("p", MetaSnapshot("tt7366338", "series", "Chernobyl", null, "poster"))
        f.env.data.library.recordProgress("p", "tt7366338", "tt7366338:1:2", 600_000, 3_600_000)
        runCurrent()
        val resumed = f.store.state.value.continueWatching.single()
        val catalog = f.store.state.value.rows[0].items[0]

        // A Continue Watching card names its episode; a catalog card leaves the video to Detail's Play.
        assertEquals(
            listOf(MediaAction.PLAY_FROM_START, MediaAction.CHOOSE_STREAM),
            resumed.menu.map { it.action }.filter { it.onDetail != null },
        )
        f.store.continueWatchingAction(resumed, MediaAction.PLAY_FROM_START)
        f.store.continueWatchingAction(resumed, MediaAction.CHOOSE_STREAM)
        f.store.menuAction(catalog, MediaAction.CHOOSE_STREAM)
        runCurrent()
        assertEquals(
            listOf(
                "detail series/tt7366338/tt7366338:1:2 PLAY_FROM_START",
                "detail series/tt7366338/tt7366338:1:2 CHOOSE_STREAM",
                "detail movie/tt6933238/null CHOOSE_STREAM",
            ),
            f.host.opened,
        )
        assertEquals("tt7366338:1:2", f.store.state.value.continueWatching.single().videoId, "nothing changed here")
    }

    @Test
    fun navigationGoesThroughTheHost() = runTest {
        val f = Fixture(this, listOf(CINEMETA))
        runCurrent()
        val state = f.store.state.value
        f.store.seeAll(1)
        f.store.openItem(state.rows[0].items[0])
        assertEquals(listOf(DeepLink.Discover(DefaultAddons.CINEMETA_URL, "series", "top")), f.host.discover)
        assertEquals(listOf("meta movie/tt6933238"), f.host.opened)
        assertSame(state.rows[0].items[0].preview, assertNotNull(f.host.previews.single()), "the catalog item goes along, for Detail's header")
    }

    @Test
    fun aBoardOfEmptyCatalogsSaysSo() = runTest {
        val f = Fixture(this, listOf(catalogAddon("lists", """[{"type":"movie","id":"nothing","name":"Nothing"}]""")))
        assertEquals(BoardBody.Loading, f.store.state.value.body, "the spinner until the addons are read")
        runCurrent()
        assertEquals(BoardBody.Empty("Nothing to show yet. Install addons with catalogs to fill this page."), f.store.state.value.body)
    }

    private fun trakt() = FakeCatalogSource(
        TrackerService.TRAKT,
        listOf(
            TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null),
            TrackerCatalog(TrackerService.TRAKT, "trending.movie", TrackerCatalog.Kind.TRENDING, "movie"),
            TrackerCatalog(TrackerService.TRAKT, "recommended.series", TrackerCatalog.Kind.RECOMMENDED, "series"),
        ),
        mapOf(
            "watchlist" to mapOf(
                null to TrackerCatalogPage(listOf(trackerItem("tt0903747", "series", "Breaking Bad"), trackerItem("tt0111161", "movie", "The Shawshank Redemption")), next = "2"),
            ),
            "recommended.series" to mapOf(null to TrackerCatalogPage(listOf(trackerItem("tt7366338", "series", "Chernobyl")), next = null)),
        ),
    )

    @Test
    fun connectedTrackersListsLeadTheBoardAndOpenThroughTheAddons() = runTest {
        val trakt = trakt()
        val f = Fixture(this, listOf(CINEMETA), listOf(trakt))
        runCurrent()
        var state = f.store.state.value
        assertEquals(
            listOf("Watchlist", series("Recommended"), movies("Popular"), series("Popular"), movies("Featured"), series("Featured")),
            state.rows.map { it.title },
            "the tracker's lists first; its empty trending list is left out",
        )
        assertEquals(listOf("Trakt", "Trakt", "Cinemeta"), state.rows.take(3).map { it.sourceName })
        assertEquals(listOf(true, true, false), state.rows.take(3).map { it.fromTracker })
        assertEquals(listOf("tt0903747" to "series", "tt0111161" to "movie"), state.rows[0].items.map { it.id to it.type })
        assertEquals("https://img.example/tt0903747.jpg", state.rows[0].items[0].posterUrl)
        assertEquals(listOf("lists", "page watchlist", "page trending.movie", "page recommended.series"), trakt.calls)

        f.store.openItem(state.rows[0].items[0])
        f.store.seeAll(0)
        f.store.seeAll(1)
        assertEquals(listOf("meta series/tt0903747"), f.host.opened, "an item opens by its id, through the addons")
        assertEquals(
            listOf(
                DeepLink.Discover("tortuga-tracker:trakt", "series", "watchlist"),
                DeepLink.Discover("tortuga-tracker:trakt", "series", "recommended.series"),
            ),
            f.host.discover,
            "a list of both types opens Discover on its first item's type",
        )

        f.trackers.value = emptyList()
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf(movies("Popular"), series("Popular"), movies("Featured"), series("Featured")), state.rows.map { it.title })
        assertEquals(listOf(READY, READY, READY, RowStatus.Idle), state.rows.map { it.status }, "the addon rows move up and load")
    }

    @Test
    fun aRowScrolledToItsEndLoadsItsNextPage() = runTest {
        val watchlist = TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null)
        val trakt = FakeCatalogSource(
            TrackerService.TRAKT,
            listOf(watchlist),
            mapOf(
                "watchlist" to mapOf(
                    null to TrackerCatalogPage(listOf(trackerItem("tt0903747", "series", "Breaking Bad")), next = "2"),
                    "2" to TrackerCatalogPage(listOf(trackerItem("tt7366338", "series", "Chernobyl")), next = null),
                ),
            ),
        )
        val f = Fixture(this, listOf(lists), listOf(trakt))
        runCurrent()
        assertEquals(listOf("Watchlist", movies("Trending"), movies("Broken")), f.store.state.value.rows.map { it.title })

        f.store.loadNextPage(1)
        assertEquals(RowStatus.Ready(loadingMore = true), f.store.state.value.rows[1].status)
        runCurrent()
        assertEquals("lists.example.com movie/trending/skip=3", f.env.http.catalogRequests.last(), "skip counts every item the first page sent")
        val row = f.store.state.value.rows[1]
        assertEquals(listOf("tt1", "tt2", "tt3"), row.items.map { it.id }, "an item on two pages shows once")
        assertEquals(READY, row.status)

        f.store.loadNextPage(1)
        runCurrent()
        assertEquals("lists.example.com movie/trending/skip=5", f.env.http.catalogRequests.last())
        val asked = f.env.http.requests.size
        f.store.loadNextPage(1)
        f.store.loadNextPage(2)
        runCurrent()
        assertEquals(asked, f.env.http.requests.size, "an empty page ends a row; a row not loaded has no next page")

        f.store.loadNextPage(0)
        f.store.loadNextPage(0)
        runCurrent()
        f.store.loadNextPage(0)
        runCurrent()
        assertEquals(listOf("lists", "page watchlist", "page watchlist@2"), trakt.calls, "one page at a time, and none after the last cursor")
        assertEquals(listOf("tt0903747", "tt7366338"), f.store.state.value.rows[0].items.map { it.id })
    }

    /**
     * The titles left out (Hide watched titles) leave the rows; a row whose first page is mostly left out loads one more
     * page by itself, and only one: the rest come as it is scrolled to its end.
     */
    @Test
    fun aRowMostlyLeftOutLoadsOneMorePageByItself() = runTest {
        fun pages(f: Fixture) = f.env.http.catalogRequests.filter { it.startsWith("lists.example.com movie/trending") }
        val half = Fixture(this, listOf(lists), hidden = setOf("tt1"))
        runCurrent()
        assertEquals(listOf("tt2"), half.store.state.value.rows.single { it.title == movies("Trending") }.items.map { it.id })
        assertEquals(listOf("lists.example.com movie/trending"), pages(half), "half of the page left out loads no other")

        val f = Fixture(this, listOf(lists), hidden = setOf("tt1", "tt2"))
        runCurrent()
        val row = f.store.state.value.rows.single { it.title == movies("Trending") }
        assertEquals(listOf("tt3"), row.items.map { it.id })
        assertEquals(READY, row.status)
        assertEquals(listOf("lists.example.com movie/trending", "lists.example.com movie/trending/skip=3"), pages(f), "one page more, no further")

        f.store.loadNextPage(f.store.state.value.rows.indexOf(row))
        runCurrent()
        assertEquals("lists.example.com movie/trending/skip=5", pages(f).last(), "scrolled to its end, the row goes on")
    }

    @Test
    fun onARemotesUiARowShowsTwentyItemsThenASeeAllCardOpeningItsCatalog() = runTest {
        val touch = Fixture(this, listOf(CINEMETA))
        runCurrent()
        var row = touch.store.state.value.rows[0]
        assertEquals(49 to SeeAll.HEADING, row.items.size to row.seeAll, "touch and pointer UIs: every item, See all in the heading")

        val f = Fixture(this, listOf(CINEMETA), remote = true)
        runCurrent()
        row = f.store.state.value.rows[0]
        assertEquals("\u2068Popular\u2069 · Movies", row.title, "the catalog's name isolated, the type not")
        assertEquals(touch.store.state.value.rows[0].items.take(BoardStore.REMOTE_ROW_ITEMS), row.items, "the first 20 of the 49")
        assertEquals(SeeAll.CARD, row.seeAll)
        val asked = f.env.http.catalogRequests.size
        f.store.loadNextPage(0)
        runCurrent()
        assertEquals(asked, f.env.http.catalogRequests.size, "a row with 20 items loads no more pages")
        f.store.seeAll(0)
        assertEquals(listOf(DeepLink.Discover(DefaultAddons.CINEMETA_URL, "movie", "top")), f.host.discover, "the card opens the catalog as the heading's See all does")
    }

    @Test
    fun onARemotesUiTheSeeAllCardShowsWhileTheCatalogHasPagesNotLoaded() = runTest {
        val chernobyl = trackerItem("tt7366338", "series", "Chernobyl")
        val trakt = FakeCatalogSource(
            TrackerService.TRAKT,
            listOf(
                TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null),
                TrackerCatalog(TrackerService.TRAKT, "recommended.series", TrackerCatalog.Kind.RECOMMENDED, "series"),
            ),
            mapOf(
                "watchlist" to mapOf(
                    null to TrackerCatalogPage(listOf(trackerItem("tt0903747", "series", "Breaking Bad")), next = "2"),
                    "2" to TrackerCatalogPage(listOf(chernobyl), next = null),
                ),
                "recommended.series" to mapOf(null to TrackerCatalogPage(listOf(chernobyl), next = null)),
            ),
        )
        val f = Fixture(this, listOf(lists), listOf(trakt), remote = true)
        runCurrent()
        fun rows() = f.store.state.value.rows.map { Triple(it.title, it.items.size, it.seeAll) }
        assertEquals(
            listOf(
                Triple("Watchlist", 1, SeeAll.CARD),
                Triple(series("Recommended"), 1, SeeAll.NONE),
                Triple(movies("Trending"), 2, SeeAll.CARD),
            ),
            rows().take(3),
            "a next cursor, or a catalog that declares skip and sent a page, has more; a last page doesn't",
        )
        val notLoaded = rows().drop(3)
        assertTrue(notLoaded.isNotEmpty() && notLoaded.all { it.second == 0 && it.third == SeeAll.NONE }, "a row not loaded has no card: $notLoaded")

        f.store.loadNextPage(2)
        runCurrent()
        assertEquals(Triple(movies("Trending"), 3, SeeAll.CARD), rows()[2], "under 20 items the row pages as elsewhere")
        f.store.loadNextPage(2)
        f.store.loadNextPage(0)
        runCurrent()
        assertEquals(listOf(Triple("Watchlist", 2, SeeAll.NONE), Triple(movies("Trending"), 3, SeeAll.NONE)), listOf(rows()[0], rows()[2]), "the last page loaded: the row shows its whole catalog")
    }

    /**
     * A row on screen keeps the row shown after it requested, whichever row that becomes as rows answer empty or arrive,
     * without another report: on TV the lists after the Watchlist answered empty and were left out, and the row that
     * came after it stayed a loading shelf until a scroll composed it.
     */
    @Test
    fun theRowShownAfterOneOnScreenLoadsAsRowsAnswerEmptyOrArrive() = runTest {
        val trakt = FakeCatalogSource(
            TrackerService.TRAKT,
            listOf(
                TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null),
                TrackerCatalog(TrackerService.TRAKT, "recommended.movie", TrackerCatalog.Kind.RECOMMENDED, "movie"),
                TrackerCatalog(TrackerService.TRAKT, "recommended.series", TrackerCatalog.Kind.RECOMMENDED, "series"),
                TrackerCatalog(TrackerService.TRAKT, "trending.movie", TrackerCatalog.Kind.TRENDING, "movie"),
                TrackerCatalog(TrackerService.TRAKT, "trending.series", TrackerCatalog.Kind.TRENDING, "series"),
            ),
            mapOf(
                "watchlist" to mapOf(null to TrackerCatalogPage(listOf(trackerItem("tt0903747", "series", "Breaking Bad")), next = null)),
                "trending.movie" to emptyMap(),
                "trending.series" to mapOf(null to TrackerCatalogPage(listOf(trackerItem("tt7366338", "series", "Chernobyl")), next = null)),
            ),
        )
        val f = Fixture(this, emptyList(), listOf(trakt))
        fun rows() = f.store.state.value.rows.map { it.title to it.status }
        runCurrent()
        assertEquals(
            listOf("Watchlist" to READY, movies("Trending") to RowStatus.Idle, series("Trending") to RowStatus.Idle),
            rows(),
            "the first three rows requested, the two empty ones left out",
        )

        f.store.rowVisible(0)
        assertEquals(RowStatus.Loading, rows()[1].second, "the row now after it is requested at once")
        runCurrent()
        assertEquals(
            listOf("Watchlist" to READY, series("Trending") to READY),
            rows(),
            "the row after it answered empty: the one after that loads",
        )

        f.store.rowVisible(1)
        f.addons.value = listOf(CINEMETA)
        runCurrent()
        assertEquals(
            listOf(series("Trending") to READY, movies("Popular") to READY, series("Popular") to RowStatus.Idle),
            rows().subList(1, 4),
            "rows arriving after it: the first loads",
        )
    }

    @Test
    fun aTrackerThatFailsShowsWhyAndReadsItsListsAgainOnRetry() = runTest {
        val trakt = trakt()
        trakt.failLists = TrackerException.Network(TrackerService.TRAKT, IllegalStateException("offline"))
        val f = Fixture(this, listOf(CINEMETA), listOf(trakt))
        runCurrent()
        var row = f.store.state.value.rows[0]
        assertEquals("Trakt" to RowStatus.Failed("Couldn’t reach Trakt."), row.title to row.status)
        assertTrue(row.fromTracker)
        f.store.seeAll(0)
        assertEquals(emptyList(), f.host.discover, "a failed tracker has no list to open")

        trakt.failLists = null
        trakt.failPages["watchlist"] = TrackerException.Auth(TrackerService.TRAKT, "refresh refused", permanent = true)
        f.store.retryRow(0)
        assertEquals(RowStatus.Loading, f.store.state.value.rows[0].status)
        runCurrent()
        row = f.store.state.value.rows[0]
        assertEquals(listOf("lists", "lists", "page watchlist", "page trending.movie", "page recommended.series"), trakt.calls)
        assertEquals("Watchlist" to RowStatus.Failed("Trakt signed you out. Connect it again in Settings."), row.title to row.status)

        trakt.failPages.clear()
        f.store.retryRow(0)
        runCurrent()
        assertEquals(listOf("Breaking Bad", "The Shawshank Redemption"), f.store.state.value.rows[0].items.map { it.title })
    }

    /** A catalog's key in Home's layout: its addon's link, its type and id. */
    private fun key(addon: Descriptor, type: String, id: String) = "addon|${addon.transportUrl}|$type|$id"

    @Test
    fun theProfilesLayoutArrangesTheRowsAndHiddenOnesAreNeverRequested() = runTest {
        val trakt = trakt()
        val layout = HomeLayout(
            order = listOf(
                key(CINEMETA, "series", "imdbRating"), key(lists, "movie", "trending"), key(lists, "movie", "nothing"),
                key(lists, "movie", "broken"), "tracker|trakt|watchlist", key(CINEMETA, "movie", "top"),
                key(CINEMETA, "series", "top"), key(CINEMETA, "movie", "imdbRating"),
            ),
            hidden = listOf(key(lists, "movie", "trending"), key(CINEMETA, "series", "top"), "tracker|trakt|trending.movie"),
        )
        val f = Fixture(this, listOf(lists, CINEMETA), listOf(trakt), layout)
        runCurrent()
        var state = f.store.state.value
        assertEquals(
            listOf(series("Featured"), movies("Broken"), "Watchlist", series("Recommended"), movies("Popular"), movies("Featured")),
            state.rows.map { it.title },
            "the stored order; a list it doesn't name follows the row before it by default; the empty catalog is left out",
        )
        assertEquals(
            setOf("v3-cinemeta.strem.io series/imdbRating", "lists.example.com movie/nothing", "lists.example.com movie/broken"),
            f.env.http.catalogRequests.toSet(),
            "the first shown rows are requested",
        )
        state.rows.indices.forEach(f.store::rowVisible)
        runCurrent()
        assertTrue(f.env.http.catalogRequests.none { it == "lists.example.com movie/trending" || it == "v3-cinemeta.strem.io series/top" })
        assertEquals(listOf("lists", "page watchlist", "page recommended.series"), trakt.calls, "a hidden list isn't read")

        f.layout.value = layout.withHidden(key(lists, "movie", "trending"), hidden = false)
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf(series("Featured"), movies("Trending"), movies("Broken")), state.rows.take(3).map { it.title })
        assertEquals(READY, state.rows[1].status, "a row shown again loads in its place")
    }

    @Test
    fun continueWatchingAndEveryRowCanBeHidden() = runTest {
        val f = Fixture(this, listOf(CINEMETA), layout = HomeLayout(hidden = listOf(HomeLayout.CONTINUE_WATCHING)))
        val library = f.env.data.library
        library.upsertFromMeta("p", MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", null, "poster"))
        library.recordProgress("p", "tt0111161", "tt0111161", 60 * 60_000L, 142 * 60_000L)
        runCurrent()
        assertEquals(emptyList(), f.store.state.value.continueWatching)
        assertEquals(4, f.store.state.value.rows.size)

        val catalogs = listOf("movie" to "top", "series" to "top", "movie" to "imdbRating", "series" to "imdbRating").map { (type, id) -> key(CINEMETA, type, id) }
        f.layout.value = HomeLayout(hidden = catalogs)
        runCurrent()
        var state = f.store.state.value
        assertEquals(listOf("The Shawshank Redemption"), state.continueWatching.map { it.title }, "shown again, it follows the library")
        assertEquals(BoardBody.Rows(emptyList()), state.body, "Continue Watching alone is a page")

        f.layout.value = HomeLayout(hidden = catalogs + HomeLayout.CONTINUE_WATCHING)
        runCurrent()
        state = f.store.state.value
        assertEquals(emptyList(), state.continueWatching)
        assertEquals(BoardBody.Empty(T.browse.board.allHidden), state.body)
        assertEquals(3, f.env.http.requests.size, "only the rows of the first layout were requested")
    }

    @Test
    fun offlineTheBoardPointsToDownloadsAndFailedRowsLoadAgainOnceBack() = runTest {
        val f = Fixture(this, listOf(lists))
        f.network.value = NetworkState.OFFLINE
        runCurrent()
        var state = f.store.state.value
        assertEquals(BoardBody.Offline(OfflineState(T.offline.title, T.offline.text, T.offline.openDownloads)), state.body, "offline in place of the rows that loaded")
        f.store.openDownloads()
        assertEquals(listOf("downloads"), f.host.opened)

        fun requests(path: String) = f.env.http.catalogRequests.count { it == "lists.example.com $path" }
        assertEquals(1, requests("movie/broken"))
        f.network.value = NetworkState(online = true, metered = true)
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf(READY, BROKEN), state.rows.map { it.status })
        assertEquals(2, requests("movie/broken"), "the failed row is asked again")
        assertEquals(1, requests("movie/trending"), "a row that loaded isn't")

        f.network.value = NetworkState.ONLINE
        runCurrent()
        assertEquals(2, requests("movie/broken"), "only coming back online reloads")
    }

    @Test
    fun withoutDownloadsTheOfflineStateIsItsTextAlone() = runTest {
        val f = Fixture(this, listOf(lists), offersDownloads = false)
        f.network.value = NetworkState.OFFLINE
        runCurrent()
        assertEquals(BoardBody.Offline(OfflineState(T.offline.title, T.offline.textNoDownloads, actionText = null)), f.store.state.value.body)
    }

    @Test
    fun homesDownloadsSumUpTheDownloadsOnTheirWayAndGoOnceNoneIs() = downloadsTest(fake = true) { h ->
        val film = lcgBytes(200_000, seed = 1)
        val episode = lcgBytes(100_000, seed = 2)
        h.routes["cdn.example"] = { request -> serveVideo(request, if (request.url.encodedPath.endsWith("film.mkv")) film else episode) }
        h.data.deviceSettings.set(DeviceSetting.DownloadsAtOnce, 2)
        val manager = h.install()
        val store = h.graph.boardStore(
            h.profileId, Host(), remote = false, watchingTogether = flowOf(0), kidsProfile = flowOf(false),
        )
        h.settle()
        fun shortcut() = store.state.value.shortcuts.single { it.key == "downloads" }.label
        fun request(id: Long) = h.fakeTransport.started.last { it.id == id.toString() }
        assertEquals("Downloads", shortcut(), "Home's shortcut is there with nothing on its way")

        // Waiting for Wi-Fi: queued, no size known yet.
        h.network(online = true, metered = true)
        val movie = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        val first = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv")).id
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("1-2.mkv")).id
        h.settle()
        assertEquals("Downloading 3", shortcut(), "waiting for Wi-Fi, no size known")

        // Two at once, their sizes known; the third queued behind them, its size not known yet.
        h.network(online = true)
        h.fakeTransport.progress(request(movie).id, 50_000, film.size.toLong())
        h.fakeTransport.progress(request(first).id, 50_000, episode.size.toLong())
        h.settle()
        assertEquals("Downloading 3, 33%", shortcut(), "100 000 bytes of the known 300 000, not the fractions' mean")

        manager.pause(second)
        h.settle()
        assertEquals("Downloading 2, 33%", shortcut(), "a paused download isn't on its way")
        manager.cancel(first)
        h.settle()
        assertEquals("Downloading 1, 25%", shortcut(), "nor is a cancelled one")
        h.complete(request(movie))
        assertEquals("Downloads", shortcut(), "the last one completed")

        manager.resume(second)
        h.settle()
        assertEquals("Downloading 1, 0%", shortcut(), "resumed")
        manager.pause(second)
        h.settle()
        assertEquals("Downloads", shortcut(), "the last one paused")
        manager.resume(second)
        h.settle()
        assertTrue(shortcut().startsWith("Downloading 1"), "resumed again, it reads ${shortcut()}")
        manager.cancel(second)
        h.settle()
        assertEquals("Downloads", shortcut(), "the last one cancelled")
    }

    @Test
    fun homesShortcutsAreWatchTogetherDownloadsLibraryCalendarThenEachTrackersLists() = runTest {
        val simkl = FakeCatalogSource(TrackerService.SIMKL, listOf(TrackerCatalog(TrackerService.SIMKL, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null)), emptyMap())
        val mdblist = FakeCatalogSource(TrackerService.MDBLIST, listOf(TrackerCatalog(TrackerService.MDBLIST, "1", TrackerCatalog.Kind.LIST, "movie", name = "Classics")), emptyMap())
        mdblist.failLists = TrackerException.Network(TrackerService.MDBLIST, IllegalStateException("offline"))
        val f = Fixture(this, listOf(CINEMETA), listOf(trakt(), mdblist, simkl), HomeLayout(hidden = listOf("tracker|trakt|recommended.series")))
        runCurrent()
        assertEquals(
            listOf(
                "watchTogether" to "Watch Together",
                "downloads" to "Downloads",
                "library" to "Library",
                "calendar" to "Calendar",
                "tracker.trakt.watchlist" to "\u2068Watchlist\u2069 · Trakt",
                "tracker.trakt.trending.movie" to movies("Trending"),
                "tracker.trakt.recommended.series" to series("Recommended"),
                "tracker.simkl.watchlist" to "\u2068Watchlist\u2069 · Simkl",
            ),
            f.store.state.value.shortcuts.map { it.key to it.label },
            "every list, also one Home hides or that is empty; none for a tracker whose lists failed; two alike name their service",
        )

        val bare = Fixture(this, emptyList(), offersDownloads = false)
        runCurrent()
        assertEquals(listOf("watchTogether", "library", "calendar"), bare.store.state.value.shortcuts.map { it.key }, "no Downloads where the device has none")
    }

    @Test
    fun aTrackersListsShortcutCarriesItsTrackerAndTheAppsScreensNone() = runTest {
        val simkl = FakeCatalogSource(TrackerService.SIMKL, listOf(TrackerCatalog(TrackerService.SIMKL, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null)), emptyMap())
        val f = Fixture(this, listOf(CINEMETA), listOf(trakt(), simkl))
        runCurrent()
        assertEquals(
            listOf(
                "watchTogether" to null,
                "downloads" to null,
                "library" to null,
                "calendar" to null,
                "tracker.trakt.watchlist" to TrackerService.TRAKT,
                "tracker.trakt.trending.movie" to TrackerService.TRAKT,
                "tracker.trakt.recommended.series" to TrackerService.TRAKT,
                "tracker.simkl.watchlist" to TrackerService.SIMKL,
            ),
            f.store.state.value.shortcuts.map { it.key to it.tracker },
            "an addon's catalog is a row, never a shortcut; the app's screens come from no tracker",
        )
    }

    @Test
    fun theWatchTogetherShortcutSaysHowManyAreInThisDevicesRoom() = runTest {
        val f = Fixture(this, emptyList())
        runCurrent()
        fun label() = f.store.state.value.shortcuts.single { it.key == "watchTogether" }.label
        assertEquals("Watch Together", label())
        f.watching.value = 3
        runCurrent()
        assertEquals("3 watching together", label())
        f.watching.value = 0
        runCurrent()
        assertEquals("Watch Together", label(), "out of the room")
    }

    @Test
    fun aShortcutOpensItsScreenAndATrackersListItsCatalogInDiscover() = runTest {
        val f = Fixture(this, listOf(CINEMETA), listOf(trakt()), HomeLayout(hidden = listOf("tracker|trakt|watchlist")))
        runCurrent()
        f.store.state.value.shortcuts.forEach { f.store.openShortcut(it.key) }
        assertEquals(listOf("watch together", "downloads", "library", "calendar"), f.host.opened)
        assertEquals(
            listOf(
                DeepLink.Discover("tortuga-tracker:trakt", "movie", "watchlist"),
                DeepLink.Discover("tortuga-tracker:trakt", "movie", "trending.movie"),
                DeepLink.Discover("tortuga-tracker:trakt", "series", "recommended.series"),
            ),
            f.host.discover,
            "a list of both types not loaded yet opens on movies",
        )

        f.layout.value = HomeLayout()
        runCurrent()
        f.store.openShortcut("tracker.trakt.watchlist")
        f.store.seeAll(0)
        assertEquals(
            List(2) { DeepLink.Discover("tortuga-tracker:trakt", "series", "watchlist") },
            f.host.discover.drop(3),
            "once loaded, on its first item's type, as its row's See all",
        )
    }

    @Test
    fun theTvGuideShortcutShowsOnceAnAddonHasAGuideCatalog() = runTest {
        fun addon(name: String, epgProvider: Boolean, extra: String) = Descriptor(
            com.edde746.tortuga.model.Manifest.decode(
                """{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["tv"],"resources":["catalog"],
                   "catalogs":[{"type":"tv","id":"channels","name":"Channels","extra":$extra}],
                   "behaviorHints":{"epgProvider":$epgProvider}}""",
            ),
            "https://$name.example.com/manifest.json".transportUrl(),
        )
        val guide = addon("guide", epgProvider = true, extra = """[{"name":"skip"},{"name":"date"}]""")
        val noDate = addon("nodate", epgProvider = true, extra = """[{"name":"skip"}]""")
        val notProvider = addon("plain", epgProvider = false, extra = """[{"name":"date"}]""")

        val f = Fixture(this, listOf(noDate, notProvider))
        runCurrent()
        fun keys(fixture: Fixture) = fixture.store.state.value.shortcuts.map { it.key }
        assertEquals(listOf("watchTogether", "downloads", "library", "calendar"), keys(f), "a date catalog only counts on an epgProvider addon")
        f.addons.value = listOf(noDate, notProvider, guide)
        runCurrent()
        assertEquals(listOf("watchTogether", "downloads", "library", "guide", "calendar"), keys(f), "installed: the shortcut comes")
        assertEquals("TV Guide", f.store.state.value.shortcuts.single { it.key == "guide" }.label)
        f.store.openShortcut("guide")
        assertEquals(listOf("guide"), f.host.opened)
        f.addons.value = listOf(noDate)
        runCurrent()
        assertEquals(listOf("watchTogether", "downloads", "library", "calendar"), keys(f), "uninstalled: it goes")
    }
}

/** A loaded row, no page on its way. */
private val READY = RowStatus.Ready(loadingMore = false)

/** How the lists addon's broken catalog fails. */
private val BROKEN = RowStatus.Failed("The addon returned an error (HTTP 500).")
