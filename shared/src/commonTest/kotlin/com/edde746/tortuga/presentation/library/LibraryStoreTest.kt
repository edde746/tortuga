package com.edde746.tortuga.presentation.library

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.hasMore
import com.edde746.tortuga.presentation.items
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.strings.T
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LibraryStoreTest {
    @AfterTest
    fun resetLocale() {
        T.locale = "en"
    }

    private class Host : LibraryHost {
        val opened = mutableListOf<Triple<String, String, String?>>()
        val details = mutableListOf<String>()

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) {
            opened += Triple(type, id, null)
        }

        override fun openVideo(type: String, id: String, videoId: String) {
            opened += Triple(type, id, videoId)
        }

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) {
            details += "$type/$id/$videoId $action"
        }

        override fun openCalendar() = error("not asked here")
    }

    private class Fixture(private val scope: TestScope) {
        val clock = TestClock()
        val data = LocalData(
            inMemoryDatabase(), { clock.now },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val host = Host()

        fun store(mode: LibraryMode) = LibraryStore(
            mode,
            when (mode) {
                LibraryMode.LIBRARY -> data.library.observeLibrary("p")
                LibraryMode.CONTINUE_WATCHING -> data.library.observeContinueWatching("p", Int.MAX_VALUE)
            },
            host, scope.backgroundScope,
            MediaActions("p", data.library, trackerMarks = null, scope.backgroundScope, notice = {}) { _, _ -> null }, flowOf(emptyMap()),
        )

        /** Adds [id] a minute after the previous change. */
        suspend fun add(id: String, type: String, name: String, shape: String = "poster") {
            clock.now += 60_000
            data.library.addToLibrary("p", MetaSnapshot(id, type, name, "https://images.example/$id.jpg", shape))
        }

        suspend fun watch(id: String, videoId: String, timeMs: Long, durationMs: Long) {
            clock.now += 60_000
            data.library.recordProgress("p", id, videoId, timeMs, durationMs)
        }
    }

    @Test
    fun typesFollowStremioOrderAndFilter() = runTest {
        val f = Fixture(this)
        f.add("yt1", "channel", "A channel")
        f.add("tt2", "series", "Breaking Bad")
        f.add("an1", "anime", "Frieren")
        f.add("tt1", "movie", "Alien", shape = "landscape")
        val store = f.store(LibraryMode.LIBRARY)
        assertEquals(LibraryBody.Loading, store.state.value.body)
        assertFalse(store.state.value.showsFilters, "no filters while the library is read")
        runCurrent()
        var state = store.state.value
        assertTrue(state.body is LibraryBody.Grid)
        assertEquals("Library", state.title)
        assertEquals(listOf("All", "Movies", "Series", "Channels", "Anime"), state.types.map { it.label })
        assertEquals(listOf(true, false, false, false, false), state.types.map { it.selected })
        assertTrue(state.showsTypes)
        assertEquals(PosterShape.LANDSCAPE, state.items.first { it.id == "tt1" }.posterShape)

        store.selectType("series")
        state = store.state.value
        assertEquals(listOf("tt2"), state.items.map { it.id })
        assertTrue(state.types.single { it.type == "series" }.selected)

        // The selected type stays when its last item leaves, as in stremio-core: its chip stays, marked, and the list
        // says there's nothing of it.
        store.menuAction("tt2", MediaAction.REMOVE_FROM_LIBRARY)
        runCurrent()
        state = store.state.value
        assertEquals(emptyList(), state.items)
        assertEquals(LibraryBody.Empty("Nothing of this type here."), state.body)
        assertEquals(listOf("All", "Movies", "Series", "Channels", "Anime"), state.types.map { it.label })
        assertEquals(listOf(false, false, true, false, false), state.types.map { it.selected })
        assertTrue(state.showsTypes, "the filters stay with nothing to list, to pick another type")

        // All takes it away.
        store.selectType(null)
        assertEquals(listOf("All", "Movies", "Channels", "Anime"), store.state.value.types.map { it.label })
    }

    /**
     * The type filter's chip says "Type" while every type shows, never "All" alone, and the type once one is picked,
     * narrowing the list; a screen reader hears both. The sort's chip says the order, heard as "Sort by: …".
     */
    @Test
    fun theFilterChipsNameTheirFilterUntilItNarrowsTheList() = runTest {
        val f = Fixture(this)
        f.add("tt2", "series", "Breaking Bad")
        f.add("tt1", "movie", "Alien")
        val store = f.store(LibraryMode.LIBRARY)
        runCurrent()
        assertEquals(FilterChip("Type", "Type", "\u2068Type\u2069: \u2068All\u2069", narrowed = false), store.state.value.typeChip)
        assertEquals(FilterChip("Sort by", "Recently watched", "Sort by: Recently watched", narrowed = false), store.state.value.sortChip)

        store.selectType("series")
        store.selectSort(LibrarySort.NAME)
        assertEquals(FilterChip("Type", "Series", "\u2068Type\u2069: \u2068Series\u2069", narrowed = true), store.state.value.typeChip)
        assertEquals(FilterChip("Sort by", "Title A–Z", "Sort by: Title A–Z", narrowed = false), store.state.value.sortChip)
    }

    @Test
    fun typesAreWordedAsDiscoverAndAddonsWordThem() = runTest {
        T.locale = "ar"
        val f = Fixture(this)
        f.add("tt2", "series", "Breaking Bad")
        f.add("tt1", "movie", "Alien")
        val store = f.store(LibraryMode.LIBRARY)
        runCurrent()
        // Arabic's "Movies" and "Series" as Discover's type picker and the addon filter say them (not "الأفلام").
        assertEquals(listOf("الكل", "أفلام", "مسلسلات"), store.state.value.types.map { it.label })
    }

    @Test
    fun menusOfferPlayFromTheBeginningWherePlayResumes() = runTest {
        val f = Fixture(this)
        f.add("tt1", "movie", "Halfway")
        f.add("tt2", "movie", "In the credits")
        f.watch("tt1", "tt1", 3_000_000, 6_000_000)
        f.watch("tt2", "tt2", 5_700_000, 6_000_000)
        for (mode in LibraryMode.entries) {
            val store = f.store(mode)
            runCurrent()
            assertEquals(
                listOf("tt2" to false, "tt1" to true),
                store.state.value.items.map { row -> row.id to (MediaAction.PLAY_FROM_START in row.menu.map { it.action }) },
                "$mode: past the credits Play starts at 0",
            )
        }
    }

    @Test
    fun sortsOrderTheItems() = runTest {
        val f = Fixture(this)
        f.add("a", "movie", "banana")
        f.add("b", "movie", "Apple")
        f.add("c", "movie", "cherry")
        // b watched long ago and once; a watched now, twice.
        f.watch("b", "b", 10_000, 100_000)
        f.data.library.markWatched("p", MetaSnapshot("b", "movie", "Apple", null, "poster"), true)
        f.watch("a", "a", 10_000, 100_000)
        f.data.library.markWatched("p", MetaSnapshot("a", "movie", "banana", null, "poster"), true)
        f.data.library.markWatched("p", MetaSnapshot("a", "movie", "banana", null, "poster"), true)
        val store = f.store(LibraryMode.LIBRARY)
        runCurrent()
        fun order(sort: LibrarySort): List<String> {
            store.selectSort(sort)
            assertEquals(sort, store.state.value.sorts.single { it.selected }.sort)
            return store.state.value.items.map { it.id }
        }
        assertEquals(listOf("a", "b", "c"), order(LibrarySort.LAST_WATCHED))
        assertEquals(listOf("c", "b", "a"), order(LibrarySort.ADDED))
        assertEquals(listOf("b", "a", "c"), order(LibrarySort.NAME))
        assertEquals(listOf("c", "a", "b"), order(LibrarySort.NAME_REVERSE))
        assertEquals(listOf("a", "b", "c"), order(LibrarySort.TIMES_WATCHED))
        assertEquals(listOf("a", "b", "c"), order(LibrarySort.WATCHED))
        assertEquals(listOf("c", "b", "a"), order(LibrarySort.NOT_WATCHED))
        assertEquals(
            listOf("Recently watched", "Recently added", "Title A–Z", "Title Z–A", "Most watched", "Watched first", "Unwatched first"),
            store.state.value.sorts.map { it.label },
        )
        val a = store.state.value.items.single { it.id == "a" }
        assertTrue(a.watched)

        store.menuAction("a", MediaAction.MARK_UNWATCHED)
        runCurrent()
        assertFalse(store.state.value.items.single { it.id == "a" }.watched)
    }

    @Test
    fun pagesHoldAHundredItems() = runTest {
        val f = Fixture(this)
        repeat(230) { f.add("tt$it", "movie", "Movie $it") }
        val store = f.store(LibraryMode.LIBRARY)
        runCurrent()
        assertEquals(100, store.state.value.items.size)
        assertTrue(store.state.value.hasMore)
        store.loadNextPage()
        store.loadNextPage()
        assertEquals(230, store.state.value.items.size)
        assertFalse(store.state.value.hasMore)
        store.loadNextPage()
        assertEquals(230, store.state.value.items.size)
        // A new filter or sort starts over at the first page.
        store.selectSort(LibrarySort.NAME)
        assertEquals(100, store.state.value.items.size)
    }

    @Test
    fun continueWatchingListsItemsToResumeAndDismissesThem() = runTest {
        val f = Fixture(this)
        f.add("tt1", "movie", "In library, started")
        f.add("tt2", "movie", "In library, not started")
        f.data.library.upsertFromMeta("p", MetaSnapshot("tt3", "series", "Only played", null, "poster"))
        f.watch("tt1", "tt1", 1_800_000, 7_200_000)
        f.watch("tt3", "tt3:1:2", 600_000, 2_400_000)
        val store = f.store(LibraryMode.CONTINUE_WATCHING)
        runCurrent()
        var state = store.state.value
        assertEquals("Continue Watching", state.title)
        assertEquals(listOf("tt3", "tt1"), state.items.map { it.id })
        assertEquals(0.25, state.items.first().progress)

        store.open("tt3")
        assertEquals(Triple("series", "tt3", "tt3:1:2"), f.host.opened.single())

        store.menuAction("tt3", MediaAction.REMOVE_FROM_CONTINUE_WATCHING)
        runCurrent()
        state = store.state.value
        assertEquals(listOf("tt1"), state.items.map { it.id })
        store.menuAction("tt1", MediaAction.REMOVE_FROM_CONTINUE_WATCHING)
        runCurrent()
        assertEquals(LibraryBody.Empty("Nothing to continue. Titles appear here once you start watching them."), store.state.value.body)
        assertFalse(store.state.value.showsFilters, "one type and nothing to sort")
        // Dismissed, not removed: still in the library.
        val library = f.store(LibraryMode.LIBRARY)
        runCurrent()
        assertEquals(setOf("tt1", "tt2"), library.state.value.items.map { it.id }.toSet())
        library.open("tt1")
        assertNull(f.host.opened.last().third)
    }

    @Test
    fun menuEntriesCarriedOutOnDetailOpenItThereOnTheVideoInProgress() = runTest {
        val f = Fixture(this)
        f.add("tt1", "movie", "Started")
        f.data.library.upsertFromMeta("p", MetaSnapshot("tt3", "series", "Only played", null, "poster"))
        f.watch("tt1", "tt1", 1_800_000, 7_200_000)
        f.watch("tt3", "tt3:1:2", 600_000, 2_400_000)
        val library = f.store(LibraryMode.LIBRARY)
        val continueWatching = f.store(LibraryMode.CONTINUE_WATCHING)
        runCurrent()
        assertEquals(
            listOf(MediaAction.PLAY_FROM_START, MediaAction.MARK_WATCHED, MediaAction.CHOOSE_STREAM, MediaAction.REMOVE_FROM_LIBRARY),
            library.state.value.items.single().menu.map { it.action },
        )

        // In the library, Play from the beginning is the video in progress's; the stream list is the one Detail's Play picks.
        library.menuAction("tt1", MediaAction.PLAY_FROM_START)
        library.menuAction("tt1", MediaAction.CHOOSE_STREAM)
        continueWatching.menuAction("tt3", MediaAction.PLAY_FROM_START)
        continueWatching.menuAction("tt3", MediaAction.CHOOSE_STREAM)
        assertEquals(
            listOf(
                "movie/tt1/tt1 PLAY_FROM_START",
                "movie/tt1/null CHOOSE_STREAM",
                "series/tt3/tt3:1:2 PLAY_FROM_START",
                "series/tt3/tt3:1:2 CHOOSE_STREAM",
            ),
            f.host.details,
        )
        assertEquals(emptyList(), f.host.opened)
    }
}
