package com.edde746.tortuga.kids

import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.board.rows
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * A kids profile's Home rows look up the suggested ages of only the cards they walk to: a row stops once it shows, or
 * waits on, as many cards as it wants.
 */
class KidsRowLookupsTest {
    /** The title of the movie catalog [name]'s row: the name, isolated, and the type. */
    private fun title(name: String) = "\u2068$name\u2069 · Movies"

    private fun BoardStore.ids(name: String) = state.value.rows.singleOrNull { it.title == title(name) }?.items?.map { it.id }

    /** Film [n] of these tests' catalogs, suggested for [age]. */
    private fun film(n: Int, age: Int) = RatedTitle("tt8${n.toString().padStart(6, '0')}", "movie", "Film $n", age)

    /**
     * The catalog `movie/[catalog]`: eight pages of 50 films, one in [every] of them suggested for 6 and the rest for 16;
     * [number] keeps each catalog's films apart.
     */
    private fun KidsWorld.mostlyBlocked(catalog: String, number: Int, every: Int = 10) {
        for (page in 0 until 8) {
            val films = (0 until 50).map { film(number * 1000 + page * 50 + it, if (it % every == 0) 6 else 16) }
            catalog(if (page == 0) "movie/$catalog" else "movie/$catalog/skip=${page * 50}", *films.toTypedArray())
        }
    }

    @Test
    fun fourRowsOnAColdHomeLookUpOnlyTheCardsTheyWalkTo() = runTest {
        val w = kidsWorld()
        val catalogs = listOf("family", "classics", "grown", "cartoons")
        catalogs.forEachIndexed { n, catalog -> w.mostlyBlocked(catalog, n) }
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        // All four on screen, none scrolled.
        board.state.value.rows.indices.forEach(board::rowVisible)
        runCurrent()

        for (name in listOf("Family", "Classics", "Grown-ups", "Cartoons")) {
            assertEquals(KidsGate.ROW_CARDS, board.ids(name)?.size, "$name: the five allowed films of its first page and five of its second")
        }
        // Each row's tenth allowed film is its 91st: it looks up no card after it.
        assertEquals(4 * 91, w.ages.reads)
        for (catalog in catalogs) {
            assertEquals(listOf("/catalog/movie/$catalog.json", "/catalog/movie/$catalog/skip=50.json"), w.addonPaths.filter { "/$catalog" in it })
        }
        app.close()
    }

    @Test
    fun aRowScrolledToLaterStillShowsTheTitlesItAllows() = runTest {
        val w = kidsWorld()
        // Rows above it that fill five more pages each.
        listOf("family", "classics", "grown").forEachIndexed { n, catalog -> w.mostlyBlocked(catalog, n, every = 50) }
        val allowed = (0 until 10).map { film(9000 + it, if (it % 2 == 0) 0 else 6) }
        w.catalog("movie/cartoons", *allowed.toTypedArray())
        val app = w.open()
        val board = app.boardStore()
        runCurrent()

        // Scrolled down to it once the rows above have shown what they found.
        board.rowVisible(board.state.value.rows.indexOfFirst { it.title == title("Cartoons") })
        runCurrent()
        assertEquals(allowed.map { it.id }, board.ids("Cartoons"))
        app.close()
    }

    @Test
    fun aRowScrolledToItsEndLooksFurther() = runTest {
        val w = kidsWorld()
        val films = (0 until 50).map { film(it, 0) }
        w.catalog("movie/family", *films.toTypedArray())
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        assertEquals(films.take(10).map { it.id }, board.ids("Family"))
        assertEquals(10, w.ages.reads, "the cards it shows, no more")

        board.loadNextPage(board.state.value.rows.indexOfFirst { it.title == title("Family") })
        runCurrent()
        assertEquals(films.take(20).map { it.id }, board.ids("Family"))
        assertEquals(20, w.ages.reads)
        assertEquals(listOf("/catalog/movie/family.json"), w.addonPaths.filter { "family" in it }, "its next page waits for the cards loaded")
        app.close()
    }

    @Test
    fun aShortRowWhoseEndStaysInViewGoesFurtherOnlyByFilling() = runTest {
        val w = kidsWorld()
        // One film in fifty allowed: its first page and the five it fills with find six.
        w.mostlyBlocked("family", 0, every = 50)
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        val row = board.state.value.rows.indexOfFirst { it.title == title("Family") }
        assertEquals(6, board.ids("Family")?.size)
        assertEquals(300, w.ages.reads, "every card of its six pages")

        // A row this short has its end in view: the UI asks for more each time it changes.
        repeat(10) {
            board.loadNextPage(row)
            runCurrent()
        }
        assertEquals(6, board.ids("Family")?.size)
        assertEquals(300, w.ages.reads, "a short row goes further only by filling")
        assertEquals(
            listOf("/catalog/movie/family.json") + (1..KidsGate.FILL_PAGES).map { "/catalog/movie/family/skip=${it * 50}.json" },
            w.addonPaths.filter { "family" in it },
        )
        app.close()
    }

    @Test
    fun aSuggestedAgeThatCouldntBeLookedUpStopsItsRowFilling() = runTest {
        val w = kidsWorld()
        // Ten films that would all be blocked: a row showing none of them would load its next page.
        val films = (0 until 10).map { film(it, 16) }
        w.catalog("movie/family", *films.toTypedArray())
        w.catalog("movie/family/skip=10", film(10, 0))
        w.ages.offline = true
        val app = w.open()
        val board = app.boardStore()
        runCurrent()

        assertNull(board.ids("Family"))
        assertEquals(listOf("/catalog/movie/family.json"), w.addonPaths.filter { "family" in it }, "no page while a suggested age couldn't be looked up")
        app.close()
    }

    @Test
    fun aRowLooksItsTitlesUpAgainByItselfOnceTheirRetryIsDue() = runTest {
        val w = kidsWorld()
        val films = (0 until 10).map { film(it, 0) }
        w.catalog("movie/family", *films.toTypedArray())
        // The age database can't be had: every lookup fails.
        w.ages.offline = true
        val app = w.open()
        val board = app.boardStore()
        runCurrent()
        assertNull(board.ids("Family"), "no suggested age, nothing to show")
        w.ages.offline = false

        advanceTimeBy(KidsGate.RETRY_FAILED - 1.milliseconds)
        runCurrent()
        assertEquals(0, w.ages.reads, "not asked again before")
        assertNull(board.ids("Family"))

        // Nothing else happens: no scroll, no settings change, no other suggested age.
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(films.map { it.id }, board.ids("Family"))
        assertEquals(films.size, w.ages.reads)
        app.close()
    }
}
