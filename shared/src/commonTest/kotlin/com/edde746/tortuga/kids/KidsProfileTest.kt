@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.kids

import com.edde746.tortuga.app.AppOverlay
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.FakeAppPlatform
import com.edde746.tortuga.app.FakeHomeScreen
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.board.groups
import com.edde746.tortuga.presentation.board.items
import com.edde746.tortuga.presentation.board.rows
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.downloads.downloaded
import com.edde746.tortuga.presentation.downloads.installMetaAddon
import com.edde746.tortuga.presentation.downloads.serveMeta
import com.edde746.tortuga.presentation.items
import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.ids.FixtureMappings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A kids profile (a Kids cap, unrated titles blocked) lists, opens and plays only what it allows. */
class KidsProfileTest {
    /** The row of the catalog [name]: its title is the name, isolated, and [type]. */
    private fun BoardStore.row(name: String, type: String = "Movies") = state.value.rows.singleOrNull { it.title == "\u2068$name\u2069 · $type" }

    private fun BoardStore.ids(name: String, type: String = "Movies") = row(name, type)?.items?.map { it.id }

    private fun ids(vararg titles: RatedTitle) = titles.map { it.id }

    /** Ten films of [page], the first [rated] of them suggested for 0, the rest for 16. */
    private fun page(page: Int, rated: Int = 4) = (0 until 10).map { i ->
        RatedTitle("tt7${(page * 100 + i).toString().padStart(6, '0')}", "movie", "Film $page.$i", if (i < rated) 0 else 16)
    }

    @Test
    fun homesRowsListOnlyWhatTheProfileAllowsAndFollowItsSettings() = runTest {
        val w = kidsWorld()
        w.catalog("movie/family", TOY_STORY, DEADPOOL, GHOSTBUSTERS, TWELVE_ANGRY_MEN, NO_CERTIFICATION)
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        assertEquals(ids(TOY_STORY, GHOSTBUSTERS), board.ids("Family"), "in the catalog's order")
        assertEquals(5, w.ages.reads, "one lookup per title")

        val settings = w.graph.data.settings
        settings.set(w.kids.id, ProfileSetting.KidsCap, AgeStep.LITTLE)
        runCurrent()
        assertEquals(ids(TOY_STORY), board.ids("Family"))
        settings.set(w.kids.id, ProfileSetting.KidsAllowUnrated, true)
        runCurrent()
        assertEquals(ids(TOY_STORY, TWELVE_ANGRY_MEN, NO_CERTIFICATION), board.ids("Family"))
        settings.set(w.kids.id, ProfileSetting.KidsAllowed, listOf(KidsTitle(DEADPOOL.id, "movie", DEADPOOL.name)))
        settings.set(w.kids.id, ProfileSetting.KidsBlocked, listOf(KidsTitle(TOY_STORY.id, "movie", TOY_STORY.name)))
        runCurrent()
        assertEquals(ids(DEADPOOL, TWELVE_ANGRY_MEN, NO_CERTIFICATION), board.ids("Family"))
        assertEquals(5, w.ages.reads, "the settings changed the verdicts, not the suggested ages")
        app.close()
    }

    @Test
    fun aRowShowingTooFewTitlesLoadsItsNextPagesUntilItHasEnoughOrItsCatalogEnds() = runTest {
        val w = kidsWorld()
        for (n in 0..3) w.catalog(if (n == 0) "movie/family" else "movie/family/skip=${n * 10}", *page(n).toTypedArray())
        val classics = page(10)
        w.catalog("movie/classics", classics[0], classics[5], classics[6], classics[7])
        w.catalog("movie/classics/skip=4", classics[1])
        val grown = page(20, rated = 0)
        w.catalog("movie/grown", *grown.toTypedArray())
        val app = w.open()
        val board = app.boardStore()
        runCurrent()

        val allowed = (0..2).flatMap { page(it).take(4) }.take(KidsGate.ROW_CARDS)
        assertEquals(allowed.map { it.id }, board.ids("Family"), "the ten it allows of three pages")
        assertTrue("/catalog/movie/family/skip=20.json" in w.addonPaths)
        assertTrue("/catalog/movie/family/skip=30.json" !in w.addonPaths, "no page after it has enough")
        assertEquals(listOf(classics[0], classics[1]).map { it.id }, board.ids("Classics"))
        assertEquals(
            listOf("/catalog/movie/classics.json", "/catalog/movie/classics/skip=4.json", "/catalog/movie/classics/skip=5.json"),
            w.addonPaths.filter { "classics" in it },
            "its pages until an empty one",
        )
        assertNull(board.row("Grown-ups"), "a row it allows nothing of is left out")
        assertEquals(listOf("/catalog/movie/grown.json", "/catalog/movie/grown/skip=10.json"), w.addonPaths.filter { "grown" in it })
        app.close()
    }

    @Test
    fun aRowStillShortAfterFiveMorePagesAsksForNoMore() = runTest {
        val w = kidsWorld()
        // Eight pages of ten films, the first of each page suggested for 0.
        for (n in 0..7) w.catalog(if (n == 0) "movie/family" else "movie/family/skip=${n * 10}", *page(n, rated = 1).toTypedArray())
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        assertEquals((0..5).map { page(it, rated = 1).first().id }, board.ids("Family"), "fewer than a row wants, still shown")
        assertEquals(
            listOf("/catalog/movie/family.json") + (1..5).map { "/catalog/movie/family/skip=${it * 10}.json" },
            w.addonPaths.filter { "family" in it },
            "its first page and five more",
        )
        app.close()
    }

    @Test
    fun presetHomeRowsReplaceTheProfilesCatalogsAndStillPassTheSuggestedAge() = runTest {
        // Cinemeta's Family rows aren't installed here: only the addon's own preset row shows.
        val w = kidsWorld(homeCatalogs = HomeCatalog.family + HomeCatalog(KIDS_ADDON_URL, "series", "preset"))
        w.catalog("series/preset", BLUEY, BREAKING_BAD, SWITCHED_AT_BIRTH)
        w.catalog("movie/family", TOY_STORY)
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        assertEquals(listOf("\u2068Preset\u2069 · Series"), board.state.value.rows.map { it.title })
        assertEquals(ids(BLUEY), board.ids("Preset", "Series"))
        assertEquals(listOf("/catalog/series/preset.json"), w.addonPaths)

        // Without a preset, the profile's catalogs, filtered.
        w.graph.data.profiles.setKids(w.kids.id, kids = true, homeCatalogs = emptyList())
        runCurrent()
        board.state.value.rows.indices.forEach(board::rowVisible)
        runCurrent()
        assertEquals(ids(TOY_STORY), board.ids("Family"))
        assertEquals(ids(BLUEY), board.ids("Preset", "Series"))
        app.close()
    }

    @Test
    fun discoverListsOnlyAllowedTitlesAndLoadsPagesUntilItsCatalogEnds() = runTest {
        val w = kidsWorld()
        w.catalog("movie/family", TOY_STORY, DEADPOOL, TWELVE_ANGRY_MEN)
        w.catalog("movie/family/skip=3", GHOSTBUSTERS, NO_CERTIFICATION)
        val app = w.open()
        app.navigator.openDiscover(DeepLink.Discover(KIDS_ADDON_URL, "movie", "family"))
        val discover = app.discoverStore()
        runCurrent()
        val state = discover.state.value
        assertEquals(ids(TOY_STORY, GHOSTBUSTERS), state.items.map { it.id })
        assertEquals(
            listOf("/catalog/movie/family.json", "/catalog/movie/family/skip=3.json", "/catalog/movie/family/skip=5.json"),
            w.addonPaths,
        )
        assertTrue(state.body is DiscoverBody.Grid)
        app.close()
    }

    @Test
    fun searchShowsOnlyTheHitsTheProfileAllowsAndFindsNothingWhenItAllowsNone() = runTest {
        val w = kidsWorld()
        w.catalog("movie/search/search=story", TOY_STORY, DEADPOOL, GHOSTBUSTERS)
        w.catalog("movie/search/search=deadpool", DEADPOOL)
        val app = w.open()
        val search = app.searchStore()
        search.search("story")
        runCurrent()
        assertEquals(listOf(ids(TOY_STORY, GHOSTBUSTERS)), search.state.value.groups.map { group -> group.items.map { it.id } })

        search.search("deadpool")
        runCurrent()
        val state = search.state.value
        assertEquals(SearchBody.Empty(T.browse.search.noResults(isolate("deadpool"))), state.body)
        assertEquals("deadpool", state.query, "the screen stays, its query too")
        app.close()
    }

    @Test
    fun moreLikeThisShowsOnlyTheTitlesTheProfileAllowsAndFollowsItsSettings() = runTest {
        val w = kidsWorld()
        w.catalog("movie/similar/similar=${TOY_STORY.id}", DEADPOOL, GHOSTBUSTERS, TWELVE_ANGRY_MEN, NO_CERTIFICATION)
        val app = w.open()
        val detail = app.detailStore(Route.Detail("movie", TOY_STORY.id))
        runCurrent()
        val row = detail.state.value.related
        assertEquals(ids(GHOSTBUSTERS), row?.cards?.map { it.id }, "in the addon's order")
        assertNull(row?.emptyText)

        w.graph.data.settings.set(w.kids.id, ProfileSetting.KidsAllowed, listOf(KidsTitle(DEADPOOL.id, "movie", DEADPOOL.name)))
        runCurrent()
        assertEquals(ids(DEADPOOL, GHOSTBUSTERS), detail.state.value.related?.cards?.map { it.id })
        app.close()

        val parent = w.open(w.parent)
        val unfiltered = parent.detailStore(Route.Detail("movie", TOY_STORY.id))
        runCurrent()
        assertEquals(ids(DEADPOOL, GHOSTBUSTERS, TWELVE_ANGRY_MEN, NO_CERTIFICATION), unfiltered.state.value.related?.cards?.map { it.id })
        parent.close()
    }

    @Test
    fun aBlockedTitlesPageSaysOnlyWhyAndAsksTheAddonsNothing() = runTest {
        val w = kidsWorld()
        val app = w.open()
        val deadpool = app.detailStore(Route.Detail("movie", DEADPOOL.id))
        val angryMen = app.detailStore(Route.Detail("movie", TWELVE_ANGRY_MEN.id))
        runCurrent()
        val blocked = T.kids.blocked
        assertEquals(DetailBody.Blocked(blocked.title, blocked.suggested(isolate("16")), blocked.ask), (deadpool.state.value.body as? DetailBody.Blocked))
        assertNull((deadpool.state.value.body as? DetailBody.Page)?.header)
        assertNull(deadpool.state.value.kidsBlockAction)
        assertEquals(blocked.notRated, (angryMen.state.value.body as? DetailBody.Blocked)?.reason)
        deadpool.reload()
        runCurrent()
        assertEquals(emptyList(), w.addonPaths, "no title, stream or anything else")

        w.ages.offline = true
        val bluey = app.detailStore(Route.Detail("series", BLUEY.id))
        runCurrent()
        assertEquals(blocked.unchecked, (bluey.state.value.body as? DetailBody.Blocked)?.reason, "its suggested age couldn't be checked")
        w.ages.offline = false

        // An allowed title loads, unless its addon's own rating is above the cap.
        w.sent[GHOSTBUSTERS.id] = "TV-MA"
        val toyStory = app.detailStore(Route.Detail("movie", TOY_STORY.id))
        val ghostbusters = app.detailStore(Route.Detail("movie", GHOSTBUSTERS.id))
        runCurrent()
        assertEquals(TOY_STORY.name, (toyStory.state.value.body as? DetailBody.Page)?.header?.title)
        assertNull((toyStory.state.value.body as? DetailBody.Blocked))
        assertEquals(T.kids.block, toyStory.state.value.kidsBlockAction)
        assertEquals(blocked.byAddon, (ghostbusters.state.value.body as? DetailBody.Blocked)?.reason)
        assertNull((ghostbusters.state.value.body as? DetailBody.Page)?.header)
        assertEquals(listOf("/stream/movie/${TOY_STORY.id}.json"), w.addonPaths.filter { it.startsWith("/stream/") })
        app.close()
    }

    @Test
    fun aBlockedTitleSavedWithItsDownloadsStaysBlockedAndUnaskedBackOnline() = downloadsTest { h ->
        val deadpool = MetaItem(MetaItemPreview(id = DEADPOOL.id, type = "movie", name = DEADPOOL.name))
        h.serveCdn(lcgBytes(10_000))
        h.serveMeta(deadpool)
        h.installMetaAddon()
        h.install()
        // Downloaded before the profile became a kids profile.
        h.downloaded(deadpool, DEADPOOL.id, cdnChoice())
        h.graph.ageRatings = AgeRatings(FakeAges(), FixtureMappings())
        h.data.profiles.setKids(h.profileId, kids = true, homeCatalogs = emptyList())
        h.network(online = false)

        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(), start = null, profileAccount = null)
        val detail = app.detailStore(Route.Detail("movie", DEADPOOL.id))
        h.settle()
        val blocked = DetailBody.Blocked(T.kids.blocked.title, T.kids.blocked.suggested(isolate("16")), T.kids.blocked.ask)
        assertEquals(blocked, (detail.state.value.body as? DetailBody.Blocked), "offline, from its saved title")

        h.network(online = true)
        assertEquals(blocked, (detail.state.value.body as? DetailBody.Blocked))
        assertNull((detail.state.value.body as? DetailBody.Page)?.header)
        assertEquals(emptyList(), h.http.requests.filter { it.url.host == "meta.example.com" }.map { it.url.encodedPath }, "its title isn't asked for")
        app.close()
    }

    @Test
    fun aParentsPinAllowsABlockedTitleOnTheProfileAndItsPageLoads() = runTest {
        val w = kidsWorld()
        val app = w.open()
        // Opened from its card: the catalog item names it.
        val route = Route.Detail("movie", DEADPOOL.id)
        app.navigator.openDetail(route, MetaItemPreview(id = DEADPOOL.id, type = "movie", name = DEADPOOL.name))
        val detail = app.detailStore(route)
        runCurrent()
        detail.askParent()
        runCurrent()
        assertEquals(AppOverlay.ParentPin, app.state.value.overlay)
        val pad = app.parentPinStore()
        assertEquals(T.kids.pin.allow(isolate(DEADPOOL.name)), pad.state.value.subtitle)
        pad.type("0000")
        runCurrent()
        assertEquals(T.profiles.pin.wrong, pad.state.value.error)
        assertEquals(T.kids.blocked.suggested(isolate("16")), (detail.state.value.body as? DetailBody.Blocked)?.reason, "still blocked")
        pad.type("1234")
        runCurrent()
        pad.release()

        assertNull(app.state.value.overlay)
        assertNull((detail.state.value.body as? DetailBody.Blocked))
        assertEquals(DEADPOOL.name, (detail.state.value.body as? DetailBody.Page)?.header?.title)
        assertTrue("/stream/movie/${DEADPOOL.id}.json" in w.addonPaths, "its streams are asked for")
        assertEquals(listOf(KidsTitle(DEADPOOL.id, "movie", DEADPOOL.name)), w.graph.data.settings.settings(w.kids.id).kidsAllowed)
        app.close()
    }

    @Test
    fun aTitleAllowedFromALinkIsListedByItsNameOnceItsPageLoads() = runTest {
        val w = kidsWorld()
        val app = w.open()
        // A link gives the title's id and nothing else.
        app.openLink(DeepLink.Detail("movie", DEADPOOL.id))
        val detail = app.detailStore(app.navigator.current as Route.Detail)
        runCurrent()
        detail.askParent()
        app.parentPinStore().type("1234")
        runCurrent()

        assertEquals(DEADPOOL.name, (detail.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(listOf(KidsTitle(DEADPOOL.id, "movie", DEADPOOL.name)), w.graph.data.settings.settings(w.kids.id).kidsAllowed)
        app.close()
    }

    @Test
    fun aBlockedTitleOpenedFromALinkAsksAParentWithoutItsId() = runTest {
        val w = kidsWorld()
        val app = w.open()
        // A link gives the title's id and nothing else, and its page loads nothing of a blocked title.
        app.openLink(DeepLink.Detail("movie", DEADPOOL.id))
        val detail = app.detailStore(app.navigator.current as Route.Detail)
        runCurrent()
        detail.askParent()
        runCurrent()
        assertEquals(AppOverlay.ParentPin, app.state.value.overlay)
        assertEquals(T.kids.pin.allowThis, app.parentPinStore().state.value.subtitle)
        app.close()
    }

    @Test
    fun blockingATitleOnTheProfileTakesAParentsPinAndShowsTheBlock() = runTest {
        val w = kidsWorld()
        w.catalog("movie/family", TOY_STORY, GHOSTBUSTERS)
        val app = w.open()
        val board = app.boardStore()
        val detail = app.detailStore(Route.Detail("movie", TOY_STORY.id))
        runCurrent()
        assertEquals(ids(TOY_STORY, GHOSTBUSTERS), board.ids("Family"))
        detail.blockOnProfile()
        runCurrent()
        val pad = app.parentPinStore()
        assertEquals(T.kids.pin.block(isolate(TOY_STORY.name)), pad.state.value.subtitle)
        pad.cancel()
        runCurrent()
        assertNull((detail.state.value.body as? DetailBody.Blocked), "cancelled: nothing changes")

        detail.blockOnProfile()
        app.parentPinStore().type("1234")
        runCurrent()
        assertEquals(DetailBody.Blocked(T.kids.blocked.title, T.kids.blocked.byParent, T.kids.blocked.ask), (detail.state.value.body as? DetailBody.Blocked))
        assertNull((detail.state.value.body as? DetailBody.Page)?.header)
        assertEquals(listOf(KidsTitle(TOY_STORY.id, "movie", TOY_STORY.name)), w.graph.data.settings.settings(w.kids.id).kidsBlocked)
        assertEquals(ids(GHOSTBUSTERS), board.ids("Family"))
        app.close()
    }

    @Test
    fun aTitleTheProfileDoesntAllowDoesntPlay() = runTest {
        val w = kidsWorld()
        val app = w.open()
        // Its verdict on its way, then known.
        repeat(2) {
            app.playback.play(DEADPOOL.playRequest(), playerId = null)
            runCurrent()
            assertEquals(emptyList(), w.platform.playbacks)
            assertNotEquals<Route>(Route.Player, app.navigator.current)
        }
        app.playback.play(TOY_STORY.playRequest(), playerId = null)
        runCurrent()
        assertEquals(1, w.platform.playbacks.size)
        assertEquals(Route.Player, app.navigator.current)
        app.close()
    }

    @Test
    fun libraryContinueWatchingAndTheTvHomeScreenListOnlyAllowedTitles() = runTest {
        val home = FakeHomeScreen()
        val w = kidsWorld(platform = FakeAppPlatform(homeScreen = home))
        val library = w.graph.data.library
        val mia = w.kids.id
        library.addToLibrary(mia, TOY_STORY.snapshot())
        library.addToLibrary(mia, DEADPOOL.snapshot())
        for (series in listOf(BLUEY, BREAKING_BAD)) {
            library.upsertFromMeta(mia, series.snapshot())
            library.recordProgress(mia, series.id, "${series.id}:1:1", 600_000, 1_800_000)
        }
        val app = w.open()
        val libraryStore = app.libraryStore()
        val continueWatching = app.continueWatchingStore()
        val board = app.boardStore()
        runCurrent()
        assertEquals(ids(TOY_STORY), libraryStore.state.value.items.map { it.id })
        assertEquals(ids(BLUEY), continueWatching.state.value.items.map { it.id })
        assertEquals(ids(BLUEY), board.state.value.continueWatching.map { it.id })
        assertEquals(ids(BLUEY), home.published.last().map { it.id })
        assertTrue(home.published.none { items -> items.any { it.id == BREAKING_BAD.id } }, "never on the TV's home screen: ${home.published}")
        app.close()
    }

    @Test
    fun aLibraryTypeWithItsOnlyTitleStillBeingRatedShowsTheSpinner() = runTest {
        val w = kidsWorld()
        w.graph.data.library.addToLibrary(w.kids.id, TOY_STORY.snapshot())
        w.graph.data.library.addToLibrary(w.kids.id, BREAKING_BAD.snapshot())
        // The age database answers the film at once and holds the series until [held] completes.
        val held = CompletableDeferred<Unit>()
        val series = AgeIdTable.IMDB to BREAKING_BAD.id.removePrefix("tt").toLong()
        val database = object : AgeDatabase {
            override suspend fun <R : Any> read(block: (AgeLookup) -> R): R? {
                var holds = false
                val result = w.ages.read { ages -> block { table, id -> ages.age(table, id).also { if (table to id == series) holds = true } } }
                if (holds) held.await()
                return result
            }

            override fun prefetch() = w.ages.prefetch()
        }
        w.graph.ageRatings = AgeRatings(database, FixtureMappings())
        val app = w.open()
        val library = app.libraryStore()
        runCurrent()
        assertEquals(ids(TOY_STORY), library.state.value.items.map { it.id })

        // The series' verdict is still on its way: it may yet be allowed, so the spinner shows, not a blank grid.
        library.selectType("series")
        runCurrent()
        assertEquals(LibraryBody.Loading, library.state.value.body)

        held.complete(Unit)
        runCurrent()
        assertEquals(LibraryBody.Empty(T.library.emptyType), library.state.value.body)
        app.close()
    }

    @Test
    fun theCalendarListsOnlyTheEpisodesOfAllowedSeries() = runTest {
        val w = kidsWorld()
        // A day apart around now: some fall in this month here whatever the time zone.
        val now = w.graph.clock.now()
        val released = listOf(now - 1.days, now, now + 1.days).map { it.toString() }
        for (series in listOf(BLUEY, BREAKING_BAD)) {
            w.episodes[series.id] = released
            w.graph.data.library.addToLibrary(w.kids.id, series.snapshot())
        }
        w.graph.setForeground(true)
        val app = w.open()
        val calendar = app.calendarStore()
        runCurrent()
        assertEquals(setOf(BLUEY.id), calendar.state.value.agenda.flatMap { day -> day.episodes.map { it.metaId } }.toSet())
        app.close()
    }
}
