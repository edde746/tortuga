@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.search

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.settings.RecentSearches
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.presentation.board.CINEMETA
import com.edde746.tortuga.presentation.board.catalogAddon
import com.edde746.tortuga.presentation.board.catalogPath
import com.edde746.tortuga.presentation.board.catalogRequests
import com.edde746.tortuga.presentation.board.cinemeta
import com.edde746.tortuga.presentation.board.groups
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.strings.T
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SearchStoreTest {
    /** Two movie search catalogs, and two catalogs Search leaves out (another required extra; no `search`). */
    private val lists = catalogAddon(
        "lists",
        """[
            {"type":"movie","id":"people","name":"People","extra":[{"name":"search","isRequired":true}]},
            {"type":"movie","id":"titles","name":"Titles","extra":[{"name":"search","isRequired":true}]},
            {"type":"series","id":"by-genre","extra":[{"name":"search","isRequired":true},{"name":"genre","isRequired":true,"options":["Drama"]}]},
            {"type":"movie","id":"trending","name":"Trending"}
        ]""",
    )

    private class Host : SearchHost {
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

    private class Fixture(scope: TestScope) {
        var pageTwoCalls = 0
        val env = BrowseEnv(scope) { request ->
            when ("${request.url.host} ${request.catalogPath}") {
                "v3-cinemeta.strem.io movie/top/search=shawshank" -> json(cinemeta("search-movie-shawshank.json"))
                // Fails once; then a repeat of the first page's first item and one new.
                "v3-cinemeta.strem.io movie/top/skip=4&search=shawshank" ->
                    if (++pageTwoCalls == 1) {
                        respond("", HttpStatusCode.InternalServerError)
                    } else {
                        json("""{"metas":[{"id":"tt0111161","type":"movie","name":"Again"},{"id":"tt9000001","type":"movie","name":"Next"}]}""")
                    }
                "v3-cinemeta.strem.io series/top/search=chernobyl" -> json(cinemeta("search-series-chernobyl.json"))
                // Cinemeta's search answers a skip with its first page again.
                "v3-cinemeta.strem.io series/top/skip=10&search=chernobyl" -> json(cinemeta("search-series-chernobyl.json"))
                "lists.example.com movie/people/search=shawshank" -> respond("", HttpStatusCode.InternalServerError)
                "lists.example.com movie/titles/search=shawshank" -> {
                    delay(5.seconds)
                    json("""{"metas":[{"id":"tt0111161","type":"movie","name":"Shawshank"},{"id":"tt0111161","type":"movie","name":"Again"}]}""")
                }
                else -> json("""{"metas":[]}""")
            }
        }
        val host = Host()
        val network = MutableStateFlow(NetworkState.ONLINE)
        private val scope = scope

        /** [profileId]'s recent searches on this device, newest first. */
        suspend fun recent(profileId: String = "p"): List<String> = env.data.deviceSettings.value(RecentSearches(profileId))

        fun store(addons: List<Descriptor>, profileId: String = "p", query: String? = null) = SearchStore(
            flowOf(addons), env.aggregator, env.data.deviceSettings, profileId, host, query, scope.backgroundScope, network, offersDownloads = true,
            env.data.library.observeMarks(profileId), MediaActions(profileId, env.data.library, trackerMarks = null, scope.backgroundScope, notice = {}) { _, _ -> null },
        )
    }

    @Test
    fun typingIsDebouncedAndResultsGroupByCatalogInAddonOrder() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(CINEMETA, lists))
        runCurrent()
        var state = store.state.value
        assertFalse(state.searching)
        assertEquals(SearchBody.Hint, state.body, "a blank query with no recent searches shows the hint")

        store.setQuery("shaw")
        advanceTimeBy(200.milliseconds)
        store.setQuery("shawshank")
        runCurrent()
        assertTrue(store.state.value.searching, "waiting for typing to pause")
        assertEquals(SearchBody.Loading, store.state.value.body)
        advanceTimeBy(399.milliseconds)
        runCurrent()
        assertEquals(emptyList(), f.env.http.requests, "nothing is searched while typing")

        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(
            setOf(
                "v3-cinemeta.strem.io movie/top/search=shawshank",
                "v3-cinemeta.strem.io series/top/search=shawshank",
                "lists.example.com movie/people/search=shawshank",
                "lists.example.com movie/titles/search=shawshank",
            ),
            f.env.http.catalogRequests.toSet(),
            "only the typed query, in every catalog that searches and needs nothing else",
        )
        state = store.state.value
        assertEquals(listOf("Movies", "\u2068People\u2069 · Movies", "\u2068Titles\u2069 · Movies"), state.groups.map { it.title }, "Cinemeta's empty series group is left out")
        assertEquals(listOf("Cinemeta", "lists", "lists"), state.groups.map { it.addonName })
        assertEquals(
            listOf(SearchGroupStatus.Ready(loadingMore = false), SearchGroupStatus.Failed("The addon returned an error (HTTP 500)."), SearchGroupStatus.Loading),
            state.groups.map { it.status },
        )
        assertEquals(listOf("tt0111161", "tt0293927", "tt3882670", "tt12975816"), state.groups[0].items.map { it.id })
        assertEquals("1994", state.groups[0].items[0].year)
        assertTrue(state.searching, "the first results show while other catalogs answer")

        advanceTimeBy(5.seconds)
        runCurrent()
        state = store.state.value
        assertEquals(SearchGroupStatus.Ready(loadingMore = false), state.groups[2].status)
        assertEquals(listOf("Shawshank"), state.groups[2].items.map { it.title }, "each id once")
        assertFalse(state.searching)
        assertEquals(emptyList(), f.recent(), "typing isn't remembered")

        store.setQuery("  shawshank ")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(4, f.env.http.requests.size, "the same query isn't searched again")

        store.setQuery("")
        state = store.state.value
        assertEquals(SearchBody.Hint, state.body)
        assertFalse(state.searching)
    }

    @Test
    fun committedQueriesBecomeRecentSearchesPerProfile() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(CINEMETA))
        runCurrent()
        store.setQuery("  chernobyl ")
        store.submit()
        runCurrent()
        var state = store.state.value
        assertEquals(listOf("Series"), state.groups.map { it.title }, "submitting searches at once")
        assertEquals(10, state.groups[0].items.size)
        store.setQuery("")
        assertEquals(listOf("chernobyl"), store.recentShown(), "a blank query lists the recent searches")

        for (i in 1..10) store.search("q$i")
        store.search("Q3")
        runCurrent()
        assertEquals(listOf("Q3", "q10", "q9", "q8", "q7", "q6", "q5", "q4", "q2", "q1"), f.recent(), "newest first, bounded, one per spelling")

        store.removeRecent(1)
        store.pickRecent(1)
        runCurrent()
        state = store.state.value
        assertEquals("q9", state.query)
        assertEquals(listOf("q9", "Q3", "q8", "q7", "q6", "q5", "q4", "q2", "q1"), f.recent())

        val again = f.store(listOf(CINEMETA))
        val otherProfile = f.store(listOf(CINEMETA), profileId = "other")
        runCurrent()
        assertEquals(f.recent(), again.recentShown(), "kept on the device")
        assertEquals(SearchBody.Hint, otherProfile.state.value.body)

        store.clearRecent()
        runCurrent()
        assertEquals(emptyList(), f.recent())
        val cleared = f.store(listOf(CINEMETA))
        runCurrent()
        assertEquals(SearchBody.Hint, cleared.state.value.body)
    }

    @Test
    fun aSearchLinkSearchesAtOnceAndOpeningAResultRemembersTheQuery() = runTest {
        val f = Fixture(this)
        val link = DeepLink.parse("stremio:///search?search=shawshank") as DeepLink.Search
        val store = f.store(listOf(CINEMETA), query = link.query)
        runCurrent()
        var state = store.state.value
        assertEquals("shawshank", state.query)
        assertEquals(listOf("Movies"), state.groups.map { it.title })
        assertEquals(listOf("shawshank"), f.recent())

        val typed = f.store(listOf(CINEMETA), profileId = "typed")
        typed.setQuery("chernobyl")
        advanceTimeBy(1.seconds)
        runCurrent()
        state = typed.state.value
        assertEquals(emptyList(), f.recent("typed"))
        typed.openItem(state.groups[0].items[0])
        runCurrent()
        assertEquals(listOf("series/tt7366338"), f.host.opened)
        assertEquals(listOf("chernobyl"), f.recent("typed"))
    }

    @Test
    fun nothingFoundSaysWhy() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(CINEMETA, lists))
        store.search("nothing")
        runCurrent()
        assertEquals(SearchBody.Empty("No results for “\u2068nothing\u2069”"), store.state.value.body, "the query isolated")

        val cannotSearch = f.store(listOf(catalogAddon("plain", """[{"type":"movie","id":"trending"}]""")))
        cannotSearch.search("x")
        runCurrent()
        assertEquals(SearchBody.Empty("None of the installed addons can search."), cannotSearch.state.value.body)
    }

    @Test
    fun releaseDropsATypedQueryStillWaitingForItsDebounce() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(CINEMETA))
        runCurrent()
        store.setQuery("shawshank")
        store.release()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(emptyList(), f.env.http.requests)
    }

    @Test
    fun recentSearchesLeaveWithTheirProfileAndWithTheAccount() = runTest {
        val data = Fixture(this).env.data
        val kept = data.profiles.create("Kept", null)
        val deleted = data.profiles.create("Deleted", null)
        data.deviceSettings.set(RecentSearches(kept.id), listOf("kept"))
        data.deviceSettings.set(RecentSearches(deleted.id), listOf("gone"))

        data.profiles.delete(deleted.id)
        assertEquals(emptyList(), data.deviceSettings.value(RecentSearches(deleted.id)))
        assertEquals(listOf("kept"), data.deviceSettings.value(RecentSearches(kept.id)))

        data.sync.dropSyncedData()
        assertEquals(emptyList(), data.deviceSettings.value(RecentSearches(kept.id)))
    }

    @Test
    fun offlineSearchPointsToDownloadsAndFailedCatalogsAreAskedAgainOnceBack() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(lists), query = "shawshank")
        f.network.value = NetworkState.OFFLINE
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(
            SearchBody.Offline(com.edde746.tortuga.presentation.board.OfflineState(T.offline.title, T.offline.text, T.offline.openDownloads)),
            store.state.value.body,
            "offline before the query's results",
        )
        store.openDownloads()
        assertEquals(listOf("downloads"), f.host.opened)
        fun asked() = f.env.http.catalogRequests.count { it == "lists.example.com movie/people/search=shawshank" }
        assertEquals(1, asked())

        f.network.value = NetworkState.ONLINE
        runCurrent()
        assertTrue(store.state.value.body !is SearchBody.Offline)
        assertEquals(2, asked(), "a failed catalog is asked again")
    }

    @Test
    fun aGroupScrolledToItsEndLoadsItsNextPage() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(CINEMETA))
        store.search("shawshank")
        runCurrent()
        assertEquals(4, store.state.value.groups.single().items.size)

        store.loadNextPage(0)
        assertEquals(SearchGroupStatus.Ready(loadingMore = true), store.state.value.groups.single().status)
        runCurrent()
        var group = store.state.value.groups.single()
        assertEquals("v3-cinemeta.strem.io movie/top/skip=4&search=shawshank", f.env.http.catalogRequests.last(), "skip counts what the addon sent")
        assertEquals(SearchGroupStatus.Ready(loadingMore = false), group.status)
        assertEquals(4, group.items.size, "a page that fails leaves the group as it was")

        store.loadNextPage(0)
        runCurrent()
        group = store.state.value.groups.single()
        assertEquals(listOf("tt0111161", "tt0293927", "tt3882670", "tt12975816", "tt9000001"), group.items.map { it.id }, "asked again, it loads; an item on two pages shows once")

        store.loadNextPage(0)
        runCurrent()
        assertEquals("v3-cinemeta.strem.io movie/top/skip=6&search=shawshank", f.env.http.catalogRequests.last())
        val asked = f.env.http.requests.size
        store.loadNextPage(0)
        runCurrent()
        assertEquals(asked, f.env.http.requests.size, "an empty page ends the group")

        store.search("chernobyl")
        runCurrent()
        store.search("shawshank")
        runCurrent()
        assertEquals(4, store.state.value.groups.single().items.size, "a new search starts each group from its first page")
    }

    @Test
    fun aPageThatAddsNoNewTitleEndsTheGroup() = runTest {
        val f = Fixture(this)
        val store = f.store(listOf(CINEMETA))
        store.search("chernobyl")
        runCurrent()
        val first = store.state.value.groups.single().items.map { it.id }

        store.loadNextPage(0)
        runCurrent()
        assertEquals("v3-cinemeta.strem.io series/top/skip=10&search=chernobyl", f.env.http.catalogRequests.last())
        val group = store.state.value.groups.single()
        assertEquals(first, group.items.map { it.id })
        assertEquals(SearchGroupStatus.Ready(loadingMore = false), group.status)

        val asked = f.env.http.requests.size
        store.loadNextPage(0)
        runCurrent()
        assertEquals(asked, f.env.http.requests.size, "a page of titles the group has already ends it, as an empty page does")
    }
}

/** The queries of the recent searches shown ([SearchBody.Recent]); none while something else shows. */
private fun SearchStore.recentShown(): List<String> = (state.value.body as? SearchBody.Recent)?.recent.orEmpty().map { it.query }
