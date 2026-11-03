@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.discover

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.presentation.board.CINEMETA
import com.edde746.tortuga.presentation.board.FakeCatalogSource
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.presentation.board.catalogAddon
import com.edde746.tortuga.presentation.board.catalogPath
import com.edde746.tortuga.presentation.board.catalogRequests
import com.edde746.tortuga.presentation.board.cinemeta
import com.edde746.tortuga.presentation.board.items
import com.edde746.tortuga.presentation.board.trackerDiscoverLink
import com.edde746.tortuga.presentation.board.trackerItem
import com.edde746.tortuga.presentation.menu.FilterChip
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverStoreTest {
    /**
     * A catalog that pages, one whose genre takes two values at once, and one Discover doesn't list (its required
     * `search` has no options).
     */
    private val pages = catalogAddon(
        "pages",
        """[
            {"type":"movie","id":"all","name":"All","extra":[{"name":"skip"}]},
            {"type":"movie","id":"multi","name":"Multi","extra":[{"name":"genre","options":["A","B","C"],"optionsLimit":2}]},
            {"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true},{"name":"genre","options":["A","B"]},{"name":"skip"}]}
        ]""",
    )

    private class Host : DiscoverHost {
        val opened = mutableListOf<String>()
        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) {
            opened += "$type/$id"
        }
        override fun openDownloads() {
            opened += "downloads"
        }
        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) {
            opened += "detail $type/$id/$videoId $action"
        }
    }

    private class Fixture(
        scope: TestScope,
        addons: List<Descriptor>,
        link: DeepLink.Discover?,
        trackers: List<TrackerCatalogSource> = emptyList(),
    ) {
        var thirdPageCalls = 0
        var listsCalls = 0
        val env = BrowseEnv(scope) { request ->
            when (request.url.host) {
                "v3-cinemeta.strem.io" -> when (request.catalogPath) {
                    "movie/top", "series/top", "series/top/genre=Adventure", "movie/top/genre=Drama" -> json(cinemeta("catalog-movie-top.json"))
                    "movie/top/skip=49&genre=Drama", "movie/year/genre=2026" -> json(cinemeta("catalog-movie-top-page2.json"))
                    "movie/top/skip=97&genre=Drama" ->
                        if (++thirdPageCalls == 1) respond("", HttpStatusCode.InternalServerError) else json("""{"metas":[]}""")
                    "series/last-videos/lastVideosIds=tt0903747%2Ctt7366338" -> json(cinemeta("catalog-movie-top.json"))
                    else -> respond("", HttpStatusCode.NotFound)
                }
                "other.example.com" -> json(cinemeta("catalog-movie-top.json"))
                // Three items sent: the first carries a malformed link and a malformed trailer, the last has no id.
                "pages.example.com" -> if (request.catalogPath == "movie/all") {
                    json(
                        """{"metas":[
                            {"id":"tt1","type":"movie","name":"One","links":[{"name":"x","category":"Genres","url":"not a url"}],"trailerStreams":[{}]},
                            {"id":"tt2","type":"movie","name":"Two"},
                            {"type":"movie","name":"No id"}
                        ]}""",
                    )
                } else {
                    json("""{"metas":[{"id":"tt9","type":"movie","name":"Nine"}]}""")
                }
                "lists.example.com" -> if (++listsCalls == 1) respond("", HttpStatusCode.BadGateway) else json("""{"metas":[]}""")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val host = Host()
        val trackers = MutableStateFlow(trackers)
        val network = MutableStateFlow(NetworkState.ONLINE)
        val store = DiscoverStore(
            flowOf(addons), env.aggregator, this.trackers, link, host, scope.backgroundScope, network, offersDownloads = true,
            env.data.library.observeMarks("p"), MediaActions("p", env.data.library, trackerMarks = null, scope.backgroundScope, notice = {}) { _, _ -> null },
        )
        val requests: List<String> get() = env.http.catalogRequests.map { it.substringAfter(' ') }
    }

    @Test
    fun theSelectionIsDerivedFromTheManifests() = runTest {
        val f = Fixture(this, listOf(CINEMETA), link = null)
        runCurrent()
        var state = f.store.state.value
        assertEquals("\u2068Popular\u2069 · Movies", state.title, "the catalog's name isolated, the type not")
        assertEquals(DiscoverPicker("Type", "Movies", true, false, listOf(DiscoverOption("Movies", null, true), DiscoverOption("Series", null, false))), state.types)
        assertEquals("Popular", state.catalogs?.selection)
        assertEquals(
            listOf(DiscoverOption("Popular", "Cinemeta", true), DiscoverOption("New", "Cinemeta", false), DiscoverOption("Featured", "Cinemeta", false)),
            state.catalogs?.options,
            "catalogs whose required extras have no options (last-videos, calendar-videos) can't be opened",
        )
        val genre = state.filters.single()
        assertEquals(listOf("Genre", "All"), listOf(genre.label, genre.selection))
        assertFalse(genre.isRequired)
        assertFalse(genre.multiSelect)
        assertEquals(20, genre.options.size)
        assertEquals(DiscoverOption("All", null, true), genre.options[0])
        assertEquals(DiscoverOption("Action", null, false), genre.options[1])
        assertEquals(listOf("movie/top"), f.requests)
        assertEquals(49, state.items.size)

        f.store.selectCatalog(1)
        runCurrent()
        state = f.store.state.value
        assertEquals("\u2068New\u2069 · Movies", state.title)
        assertEquals("movie/year/genre=2026", f.requests.last(), "a required extra starts at its first option")
        val year = state.filters.single()
        assertTrue(year.isRequired)
        assertEquals("2026", year.selection)
        assertEquals(DiscoverOption("2026", null, true), year.options[0], "no \"All\" for a required extra")

        f.store.selectType(1)
        runCurrent()
        state = f.store.state.value
        assertEquals("\u2068Popular\u2069 · Series", state.title)
        assertEquals(listOf("Popular", "New", "Featured"), state.catalogs?.options?.map { it.label })
        assertEquals("series/top", f.requests.last())

        f.store.selectFilter(0, 2)
        runCurrent()
        assertEquals("series/top/genre=Adventure", f.requests.last())
        assertEquals("Adventure", f.store.state.value.filters[0].selection)

        f.store.selectFilter(0, 0)
        runCurrent()
        assertEquals("series/top", f.requests.last())
        assertEquals("All", f.store.state.value.filters[0].selection)
    }

    /**
     * Each filter chip reads the same on every UI: the type, the catalog and an extra the addon requires show their
     * selection; a filter shows its name while All is picked, never "All" alone, and the selection once it narrows the
     * catalog. A screen reader hears the name with the selection.
     */
    @Test
    fun aFilterChipNamesItsFilterUntilItNarrowsTheCatalog() = runTest {
        val f = Fixture(this, listOf(CINEMETA), link = null)
        runCurrent()
        var state = f.store.state.value
        assertEquals(FilterChip("Type", "Movies", "\u2068Type\u2069: \u2068Movies\u2069", narrowed = false), state.types?.chip)
        assertEquals(FilterChip("Catalog", "Popular", "\u2068Catalog\u2069: \u2068Popular\u2069", narrowed = false), state.catalogs?.chip)
        assertEquals(FilterChip("Genre", "Genre", "\u2068Genre\u2069: \u2068All\u2069", narrowed = false), state.filters.single().chip)

        val genre = state.filters.single().options[2].label
        f.store.selectFilter(0, 2)
        runCurrent()
        assertEquals(FilterChip("Genre", genre, "\u2068Genre\u2069: \u2068$genre\u2069", narrowed = true), f.store.state.value.filters.single().chip)

        f.store.selectCatalog(1)
        runCurrent()
        state = f.store.state.value
        assertEquals(FilterChip("Genre", "2026", "\u2068Genre\u2069: \u20682026\u2069", narrowed = false), state.filters.single().chip, "a required extra")
    }

    @Test
    fun aCardFollowsItsTitlesLibraryStateWhichItsMenuChanges() = runTest {
        val f = Fixture(this, listOf(CINEMETA), link = null)
        runCurrent()
        val card = f.store.state.value.items[0]
        assertFalse(card.watched)
        assertEquals(listOf(MediaAction.MARK_WATCHED, MediaAction.CHOOSE_STREAM, MediaAction.ADD_TO_LIBRARY), card.menu.map { it.action })

        f.store.menuAction(card, MediaAction.MARK_WATCHED)
        runCurrent()
        var marked = f.store.state.value.items[0]
        assertTrue(marked.watched, "the poster shows it watched")
        assertEquals(listOf(MediaAction.MARK_UNWATCHED, MediaAction.CHOOSE_STREAM, MediaAction.ADD_TO_LIBRARY), marked.menu.map { it.action })
        assertFalse(f.store.state.value.items[1].watched, "the others stay as they were")

        f.store.menuAction(marked, MediaAction.ADD_TO_LIBRARY)
        runCurrent()
        marked = f.store.state.value.items[0]
        assertTrue(marked.inLibrary)
        assertEquals(listOf(MediaAction.MARK_UNWATCHED, MediaAction.CHOOSE_STREAM, MediaAction.REMOVE_FROM_LIBRARY), marked.menu.map { it.action })

        f.store.menuAction(marked, MediaAction.CHOOSE_STREAM)
        assertEquals(listOf("detail ${card.type}/${card.id}/null CHOOSE_STREAM"), f.host.opened, "Detail picks the video and lists its streams")
    }

    @Test
    fun skipCountsTheItemsTheAddonSentNotWhatWasDroppedInsideThem() = runTest {
        val f = Fixture(this, listOf(pages), link = null)
        runCurrent()
        assertEquals(listOf("movie/all"), f.requests)
        assertEquals(listOf("tt1", "tt2"), f.store.state.value.items.map { it.id })

        f.store.loadNextPage()
        runCurrent()
        assertEquals("movie/all/skip=3", f.requests.last(), "the addon sent three items; a malformed link or trailer inside one isn't an item")
    }

    @Test
    fun aPageOfTitlesListedAlreadyIsTheEnd() = runTest {
        val f = Fixture(this, listOf(pages), link = null)
        runCurrent()
        f.store.loadNextPage()
        runCurrent()
        assertEquals(listOf("tt1", "tt2", "tt9"), f.store.state.value.items.map { it.id })
        assertEquals(DiscoverMore.Available, f.store.state.value.more)

        f.store.loadNextPage()
        runCurrent()
        assertEquals("movie/all/skip=4", f.requests.last())
        assertEquals(listOf("tt1", "tt2", "tt9"), f.store.state.value.items.map { it.id })
        assertEquals(DiscoverMore.None, f.store.state.value.more, "a page of titles listed already is the end, as an empty page is")
        val asked = f.requests.size
        f.store.loadNextPage()
        runCurrent()
        assertEquals(asked, f.requests.size)
    }

    @Test
    fun aMultiValueFilterTogglesItsValuesUpToItsLimit() = runTest {
        val f = Fixture(this, listOf(pages), DeepLink.Discover(pages.transportUrl, "movie", "multi"))
        runCurrent()
        var genre = f.store.state.value.filters.single()
        assertTrue(genre.multiSelect)
        assertEquals(listOf("All", "A", "B", "C"), genre.options.map { it.label })

        f.store.selectFilter(0, 1)
        runCurrent()
        f.store.selectFilter(0, 2)
        runCurrent()
        assertEquals("movie/multi/genre=B&genre=A", f.requests.last(), "one name=value per value, the newest first")
        genre = f.store.state.value.filters.single()
        assertEquals("\u2068B\u2069, \u2068A\u2069", genre.selection, "several values isolated each")
        assertEquals(listOf(false, true, true, false), genre.options.map { it.selected })

        f.store.selectFilter(0, 3)
        runCurrent()
        assertEquals("movie/multi/genre=C&genre=B", f.requests.last(), "past the limit of two, the oldest value goes")

        f.store.selectFilter(0, 2)
        runCurrent()
        assertEquals("movie/multi/genre=C", f.requests.last(), "picking a picked value takes it off")

        f.store.selectFilter(0, 0)
        runCurrent()
        assertEquals("movie/multi", f.requests.last())
        assertEquals("All", f.store.state.value.filters.single().selection)
    }

    @Test
    fun pagesSkipWhatIsLoadedAndListEachItemOnce() = runTest {
        val link = DeepLink.Discover(DefaultAddons.CINEMETA_URL, "movie", "top", listOf(ExtraValue("genre", "Drama"), ExtraValue("skip", "20")))
        val f = Fixture(this, listOf(CINEMETA), link)
        runCurrent()
        var state = f.store.state.value
        assertEquals(listOf("movie/top/genre=Drama"), f.requests, "the link's skip is dropped")
        assertEquals("Drama", state.filters.single().selection)
        assertEquals(DiscoverOption("Drama", null, true), state.filters.single().options.first { it.label == "Drama" })
        assertEquals(49, state.items.size)
        assertEquals(DiscoverMore.Available, state.more)

        f.store.loadNextPage()
        f.store.loadNextPage()
        state = f.store.state.value
        assertEquals(DiscoverMore.Loading, state.more, "the next page loads under the grid, one page at a time")
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf("movie/top/genre=Drama", "movie/top/skip=49&genre=Drama"), f.requests)
        assertEquals(49 + 48 - 1, state.items.size, "the page overlap (tt0047086) is listed once")
        assertEquals(state.items.size, state.items.map { it.id }.toSet().size)
        assertEquals(DiscoverMore.Available, state.more)

        f.store.loadNextPage()
        runCurrent()
        state = f.store.state.value
        assertEquals("movie/top/skip=97&genre=Drama", f.requests.last(), "skip is every item loaded, 49 + 48")
        assertEquals(DiscoverMore.Failed("The addon returned an error (HTTP 500)."), state.more)
        assertEquals(96, state.items.size)

        f.store.retry()
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf("movie/top/skip=97&genre=Drama", "movie/top/skip=97&genre=Drama"), f.requests.takeLast(2))
        assertEquals(DiscoverMore.None, state.more, "an empty page is the end")
        assertEquals(96, state.items.size)

        f.store.openItem(state.items[60])
        assertEquals(listOf("movie/${state.items[60].id}"), f.host.opened)
    }

    @Test
    fun linksOpenAnyCatalogAsStremioCoreDoes() = runTest {
        val requiredLeftOut = Fixture(this, listOf(CINEMETA), DeepLink.Discover(DefaultAddons.CINEMETA_URL, "movie", "year"))
        val lastVideos = Fixture(
            this,
            listOf(CINEMETA),
            DeepLink.Discover(
                DefaultAddons.CINEMETA_URL,
                "series",
                "last-videos",
                listOf(ExtraValue("lastVideosIds", "tt0903747,tt7366338"), ExtraValue("skip", "5")),
            ),
        )
        val find = Fixture(this, listOf(pages), DeepLink.Discover(pages.transportUrl, "movie", "find", listOf(ExtraValue("search", "x"))))
        val elsewhere = Fixture(this, listOf(CINEMETA), DeepLink.Discover("https://other.example.com/manifest.json", "movie", "top", listOf(ExtraValue("genre", "Drama"))))
        runCurrent()
        assertEquals(listOf("movie/year/genre=2026"), requiredLeftOut.requests, "a required extra the link leaves out gets its first option")

        // A catalog Discover doesn't list (its required extra has no options) shows under its type, none of the type's
        // catalogs picked.
        assertEquals(listOf("series/last-videos/lastVideosIds=tt0903747%2Ctt7366338"), lastVideos.requests, "the link's extras, never skip")
        var state = lastVideos.store.state.value
        assertEquals("\u2068Last videos\u2069 · Series", state.title)
        assertEquals("Series", state.types?.selection)
        assertEquals(listOf(false, true), state.types?.options?.map { it.selected })
        assertEquals("Last videos", state.catalogs?.selection)
        assertEquals(listOf("Popular", "New", "Featured"), state.catalogs?.options?.map { it.label })
        assertEquals(listOf(false, false, false), state.catalogs?.options?.map { it.selected })
        assertEquals(emptyList(), state.filters, "its one extra has no options")
        assertEquals(49, state.items.size)
        assertEquals(DiscoverMore.None, state.more, "it doesn't declare skip")

        lastVideos.store.selectCatalog(0)
        runCurrent()
        assertEquals("series/top", lastVideos.requests.last())
        assertEquals(listOf(true, false, false), lastVideos.store.state.value.catalogs?.options?.map { it.selected })

        // An installed one has its manifest's filters and pages.
        assertEquals(listOf("movie/find/search=x"), find.requests)
        assertEquals(listOf("All", "A", "B"), find.store.state.value.filters.single().options.map { it.label })
        find.store.selectFilter(0, 1)
        runCurrent()
        assertEquals("movie/find/genre=A&search=x", find.requests.last())
        assertEquals(DiscoverMore.Available, find.store.state.value.more)
        find.store.loadNextPage()
        runCurrent()
        assertEquals("movie/find/skip=1&genre=A&search=x", find.requests.last())

        // One of an addon that isn't installed loads as linked, with neither filters nor pages.
        assertEquals(listOf("other.example.com movie/top/genre=Drama"), elsewhere.env.http.catalogRequests)
        state = elsewhere.store.state.value
        assertEquals("\u2068top\u2069 · Movies", state.title, "without the manifest, the catalog's id")
        assertEquals("top", state.catalogs?.selection)
        assertEquals(emptyList(), state.filters)
        assertEquals(49, state.items.size)
        assertEquals(DiscoverMore.None, state.more)
    }

    @Test
    fun aLinkFollowedWhileOpenMovesToItsCatalog() = runTest {
        val adventure = DeepLink.Discover(DefaultAddons.CINEMETA_URL, "series", "top", listOf(ExtraValue("genre", "Adventure")))
        val early = Fixture(this, listOf(CINEMETA), link = null)
        early.store.open(adventure)
        runCurrent()
        assertEquals(listOf("series/top/genre=Adventure"), early.requests, "before the addons are read, the link replaces the start")

        val f = Fixture(this, listOf(CINEMETA), link = null)
        runCurrent()
        f.store.open(adventure)
        runCurrent()
        val state = f.store.state.value
        assertEquals(listOf("movie/top", "series/top/genre=Adventure"), f.requests)
        assertEquals("\u2068Popular\u2069 · Series", state.title)
        assertEquals("Adventure", state.filters.single().selection)
        assertEquals(49, state.items.size)

        f.store.open(DeepLink.Discover("https://other.example.com/manifest.json", "movie", "top"))
        runCurrent()
        assertEquals("other.example.com movie/top", f.env.http.catalogRequests.last(), "a catalog of an addon that isn't installed opens too")
        assertEquals("\u2068top\u2069 · Movies", f.store.state.value.title)
    }

    @Test
    fun anEmptyCatalogAndAFailedPageSaySo() = runTest {
        val lists = catalogAddon("lists", """[{"type":"movie","id":"nothing","name":"Nothing","extra":[{"name":"skip"}]}]""")
        val f = Fixture(this, listOf(lists), link = null)
        runCurrent()
        var state = f.store.state.value
        assertEquals(DiscoverBody.Failed("The addon returned an error (HTTP 502)."), state.body)

        f.store.retry()
        assertEquals(DiscoverBody.Loading, f.store.state.value.body, "the spinner while the first page loads again")
        runCurrent()
        state = f.store.state.value
        assertEquals(DiscoverBody.Empty("This catalog has nothing to show."), state.body)

        val none = Fixture(this, listOf(catalogAddon("bare", "[]")), link = null)
        runCurrent()
        assertEquals(DiscoverBody.Empty("None of the installed addons has a catalog to browse."), none.store.state.value.body)
        assertNull(none.store.state.value.types)
        assertNull(none.store.state.value.title)
    }

    @Test
    fun typesAreOrderedAsStremioCoreOrdersThem() = runTest {
        val types = listOf("other", "anime", "series", "movie", "tv", "channel", "zzz", "all")
        val addon = catalogAddon("types", types.joinToString(",", "[", "]") { """{"type":"$it","id":"c"}""" })
        val f = Fixture(this, listOf(addon), link = null)
        runCurrent()
        assertEquals(
            listOf("All", "Movies", "Series", "Channels", "TV channels", "Anime", "Zzz", "Other"),
            f.store.state.value.types?.options?.map { it.label },
        )
        assertEquals(
            listOf("movie/top", "series/top", "movie/year", "series/year", "movie/imdbRating", "series/imdbRating"),
            DiscoverCatalogs.selectable(listOf(CINEMETA)).map { "${it.catalog.type}/${it.catalog.id}" },
        )
    }

    private val watchlist = TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null)

    /** Trakt's watchlist of both types over two pages; the first has no movie. */
    private fun trakt() = FakeCatalogSource(
        TrackerService.TRAKT,
        listOf(watchlist),
        mapOf(
            "watchlist" to mapOf(
                null to TrackerCatalogPage(listOf(trackerItem("tt0903747", "series", "Breaking Bad")), next = "2"),
                "2" to TrackerCatalogPage(
                    listOf(trackerItem("tt0111161", "movie", "The Shawshank Redemption"), trackerItem("tt7366338", "series", "Chernobyl")),
                    next = null,
                ),
            ),
        ),
    )

    @Test
    fun aTrackerListIsOfferedUnderEachOfItsTypesAfterTheAddonCatalogs() = runTest {
        val trakt = trakt()
        val f = Fixture(this, listOf(CINEMETA), link = null, trackers = listOf(trakt))
        runCurrent()
        var state = f.store.state.value
        assertEquals("\u2068Popular\u2069 · Movies", state.title, "Discover still opens on the first addon catalog")
        assertEquals(
            listOf("Popular" to "Cinemeta", "New" to "Cinemeta", "Featured" to "Cinemeta", "Watchlist" to "Trakt"),
            state.catalogs?.options?.map { it.label to it.detail },
        )

        f.store.selectCatalog(3)
        runCurrent()
        state = f.store.state.value
        assertEquals("\u2068Watchlist\u2069 · Movies", state.title)
        assertEquals(emptyList(), state.filters)
        assertEquals(listOf("tt0111161"), state.items.map { it.id }, "the movies only; a page without one loads the next")
        assertEquals(listOf("lists", "page watchlist", "page watchlist@2"), trakt.calls)
        assertEquals(DiscoverMore.None, state.more)

        f.store.selectType(1)
        runCurrent()
        assertEquals("\u2068Popular\u2069 · Series", f.store.state.value.title)
        f.store.selectCatalog(3)
        runCurrent()
        state = f.store.state.value
        assertEquals("\u2068Watchlist\u2069 · Series", state.title)
        assertEquals(listOf("tt0903747"), state.items.map { it.id })
        assertEquals(DiscoverMore.Available, state.more, "the service's cursor pages it")
        f.store.loadNextPage()
        runCurrent()
        assertEquals(listOf("tt0903747", "tt7366338"), f.store.state.value.items.map { it.id })

        f.store.openItem(f.store.state.value.items[1])
        assertEquals(listOf("series/tt7366338"), f.host.opened)
    }

    @Test
    fun aTrackerListWithNoneOfTheTypeOnAnyPageSaysItHasNothing() = runTest {
        // A watchlist of series alone over two pages, under Movies.
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
        val f = Fixture(this, listOf(CINEMETA), link = null, trackers = listOf(trakt))
        runCurrent()
        f.store.selectCatalog(3)
        runCurrent()
        val state = f.store.state.value
        assertEquals("\u2068Watchlist\u2069 · Movies", state.title)
        assertEquals(listOf("lists", "page watchlist", "page watchlist@2"), trakt.calls)
        assertEquals(DiscoverBody.Empty("This catalog has nothing to show."), state.body)
    }

    @Test
    fun aLinkToATrackerListWaitsForTheTrackersListsAndAFailedPageRetries() = runTest {
        val trakt = trakt()
        val listed = CompletableDeferred<Unit>()
        trakt.listDelay = listed
        trakt.failPages["watchlist"] = TrackerException.Network(TrackerService.TRAKT, IllegalStateException("offline"))
        val f = Fixture(this, listOf(CINEMETA), trackerDiscoverLink(TrackerService.TRAKT, watchlist, "series"), listOf(trakt))
        runCurrent()
        var state = f.store.state.value
        assertEquals(DiscoverBody.Loading, state.body)
        assertNull(state.title)
        assertEquals(emptyList(), f.requests, "no addon catalog opens in the meantime")

        listed.complete(Unit)
        runCurrent()
        state = f.store.state.value
        assertEquals("\u2068Watchlist\u2069 · Series", state.title)
        assertEquals(DiscoverBody.Failed("Couldn’t reach Trakt."), state.body)

        trakt.failPages.clear()
        f.store.retry()
        runCurrent()
        assertEquals(listOf("tt0903747"), f.store.state.value.items.map { it.id })
    }

    @Test
    fun offlineDiscoverPointsToDownloadsAndAFailedPageLoadsAgainOnceBack() = runTest {
        val lists = catalogAddon("lists", """[{"type":"movie","id":"nothing","name":"Nothing","extra":[{"name":"skip"}]}]""")
        val trakt = FakeCatalogSource(TrackerService.TRAKT, emptyList(), emptyMap())
        trakt.failLists = TrackerException.Network(TrackerService.TRAKT, IllegalStateException("offline"))
        val f = Fixture(this, listOf(lists), link = null, trackers = listOf(trakt))
        f.network.value = NetworkState.OFFLINE
        runCurrent()
        var state = f.store.state.value
        assertEquals(DiscoverBody.Offline(OfflineState(T.offline.title, T.offline.text, T.offline.openDownloads)), state.body, "offline before the failed page's error")
        f.store.openDownloads()
        assertEquals(listOf("downloads"), f.host.opened)

        trakt.failLists = null
        f.network.value = NetworkState.ONLINE
        runCurrent()
        state = f.store.state.value
        assertEquals(DiscoverBody.Empty("This catalog has nothing to show."), state.body)
        assertEquals(2, f.listsCalls, "the failed page is asked again")
        assertEquals(listOf("lists", "lists"), trakt.calls, "so are the tracker's lists")
    }
}

/** The grid's next page; null while something else shows. */
private val DiscoverUiState.more: DiscoverMore?
    get() = (body as? DiscoverBody.Grid)?.more
