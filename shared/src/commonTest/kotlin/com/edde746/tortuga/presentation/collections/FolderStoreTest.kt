@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.CollectionViewMode
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.presentation.board.FakeCatalogSource
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.catalogRequests
import com.edde746.tortuga.presentation.board.trackerItem
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A collection's folder: its sources as tabs (with All) or rows, their pages, their notes, and what changes. */
class FolderStoreTest {
    private class Host : FolderHost {
        val opened = mutableListOf<String>()
        override fun openTrackers() {
            opened += "trackers"
        }
        override fun openAddons() {
            opened += "addons"
        }
        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) {
            opened += "meta $type/$id"
        }
        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit
    }

    private class Fixture(scope: TestScope, collection: Collection, addons: List<Descriptor> = listOf(STUDIO), trackers: List<TrackerCatalogSource> = emptyList()) {
        val env = scope.studioEnv()
        val collections = MutableStateFlow(listOf(collection))
        val addons = MutableStateFlow(addons)
        val trackers = MutableStateFlow(trackers)
        val host = Host()
        val store = FolderStore(
            "p", "c", "f", collections, this.addons, this.trackers, traktLists = null, env.aggregator, env.data.library,
            MediaActions("p", env.data.library, trackerMarks = null, scope.backgroundScope, notice = {}) { _, _ -> null },
            host, scope.backgroundScope, noteActions = true,
        )
        val state get() = store.state.value
        val grid get() = checkNotNull(state.grid)
    }

    private val ready = RowStatus.Ready(loadingMore = false)

    @Test
    fun allMergesTheSourcesInTurnEachTitleOnceAndEachSourceHasItsTab() = runTest {
        val f = Fixture(this, collection(EMBER_MOVIES, EMBER_SERIES, MISSING))
        runCurrent()
        assertEquals("Ember", f.state.title)
        assertEquals(FolderLayout.TABS, f.state.layout)
        assertEquals(listOf(T.collections.folder.all, "Ember Movies", "Ember Series", "Missing Top"), f.state.tabs.map { it.title })
        assertEquals(0, f.state.selectedTab)
        assertEquals(listOf("m1", "s1", "m2", "both", "s2"), f.grid.items.map { it.id }, "one from each in turn, each title once")
        assertEquals(ready, f.grid.status)

        f.store.selectTab(2)
        runCurrent()
        assertEquals(listOf("s1", "both", "s2"), f.grid.items.map { it.id })

        f.store.selectTab(3)
        runCurrent()
        assertEquals(FolderNote(T.collections.needsAddon(name = "Missing Addon"), FolderNoteAction.OPEN_ADDONS, T.collections.folder.goToAddons), f.grid.note)
        assertEquals(emptyList(), f.grid.items)
        f.store.noteAction(3)
        assertEquals(listOf("addons"), f.host.opened)
    }

    @Test
    fun aGridLoadsTheNextPagesOfItsSourcesNearItsEnd() = runTest {
        val f = Fixture(this, collection(EMBER_MOVIES, EMBER_SERIES))
        runCurrent()
        f.store.gridNearEnd()
        runCurrent()
        assertTrue("studio.example.com movie/ember/skip=3" in f.env.http.catalogRequests)
        assertEquals(listOf("m1", "s1", "m2", "both", "s2", "m4"), f.grid.items.map { it.id })
        f.store.gridNearEnd()
        runCurrent()
        f.store.gridNearEnd()
        runCurrent()
        assertEquals(1, f.env.http.catalogRequests.count { it == "studio.example.com movie/ember/skip=4" }, "an empty page ends the source")
    }

    @Test
    fun withoutTheAllTabOrWithOneSourceTheTabsAreTheSources() = runTest {
        val none = Fixture(this, collection(EMBER_MOVIES, EMBER_SERIES, allTab = false))
        runCurrent()
        assertEquals(listOf("Ember Movies", "Ember Series"), none.state.tabs.map { it.title })
        assertEquals(listOf("m1", "m2", "both"), none.grid.items.map { it.id })
        assertEquals(listOf("studio.example.com movie/ember"), none.env.http.catalogRequests, "a tab not shown isn't asked")

        val one = Fixture(this, collection(EMBER_SERIES))
        runCurrent()
        assertEquals(listOf("Ember Series"), one.state.tabs.map { it.title })
    }

    @Test
    fun rowsShowEachSourceWithWhatItLacks() = runTest {
        val watchlist = CollectionSource.TrackerList("simkl", "plantowatch", TrackerCatalog.Kind.PLAN_TO_WATCH, "movie")
        for (mode in listOf(CollectionViewMode.ROWS, CollectionViewMode.FOLLOW_APP)) {
            val f = Fixture(this, collection(EMBER_MOVIES, watchlist, MISSING, EMBER_SERIES, mode = mode))
            runCurrent()
            assertEquals(FolderLayout.ROWS, f.state.layout)
            assertNull(f.state.grid)
            assertEquals(emptyList(), f.state.tabs)
            val rows = f.state.rows
            assertEquals(listOf("Ember Movies", T.browse.tracker.planToWatch, "Missing Top", "Ember Series"), rows.map { it.title })
            assertEquals(listOf("studio", "Simkl", "Missing Addon", "studio"), rows.map { it.sourceName })
            assertEquals(listOf("m1", "m2", "both"), rows[0].items.map { it.id })
            assertEquals(FolderNote(T.settings.trackers.connectNamed(name = "Simkl"), FolderNoteAction.CONNECT_TRACKER, T.collections.folder.goToTrackers), rows[1].note)
            assertEquals(FolderNoteAction.OPEN_ADDONS, rows[2].note?.action)
            assertEquals(RowStatus.Idle, rows[3].status, "a row past the first ones waits to be on screen")

            f.store.rowVisible(2)
            runCurrent()
            assertEquals(listOf("s1", "both", "s2"), f.state.rows[3].items.map { it.id })
            f.store.noteAction(1)
            f.store.noteAction(0)
            assertEquals(listOf("trackers"), f.host.opened, "a row with titles has no note")
        }
    }

    @Test
    fun aSourceThatAnswersWithNothingSaysSo() = runTest {
        val empty = CollectionSource.AddonCatalog("org.example.studio", "movie", "nothing")
        val nothing = Fixture(this, collection(EMBER_MOVIES, empty, mode = CollectionViewMode.ROWS), addons = listOf(STUDIO))
        runCurrent()
        // The studio has no catalog "nothing": it says which.
        assertEquals(T.collections.needsCatalog(addon = "studio", catalog = "nothing"), nothing.state.rows[1].note?.text)
    }

    @Test
    fun itFollowsAddonsTrackersAndTheCollection() = runTest {
        val list = TrackerCatalog(TrackerService.SIMKL, "plantowatch", TrackerCatalog.Kind.PLAN_TO_WATCH, "movie")
        val watchlist = CollectionSource.TrackerList("simkl", list.id, list.kind, list.type)
        val f = Fixture(this, collection(EMBER_MOVIES, watchlist, mode = CollectionViewMode.ROWS), addons = emptyList())
        runCurrent()
        assertEquals(FolderNoteAction.OPEN_ADDONS, f.state.rows[0].note?.action)

        f.addons.value = listOf(STUDIO)
        runCurrent()
        assertNull(f.state.rows[0].note, "the addon installed: its titles show")
        assertEquals(listOf("m1", "m2", "both"), f.state.rows[0].items.map { it.id })

        val simkl = FakeCatalogSource(TrackerService.SIMKL, listOf(list), mapOf(list.id to mapOf(null to TrackerCatalogPage(listOf(trackerItem("tt9", "movie", "Nine")), next = null))))
        f.trackers.value = listOf(simkl)
        runCurrent()
        assertEquals(listOf("tt9"), f.state.rows[1].items.map { it.id }, "the tracker connected")
        f.trackers.value = emptyList()
        runCurrent()
        assertEquals(FolderNoteAction.CONNECT_TRACKER, f.state.rows[1].note?.action, "and disconnected")

        val asked = f.env.http.requests.size
        f.collections.value = listOf(collection(EMBER_MOVIES, watchlist, mode = CollectionViewMode.ROWS).let { it.copy(folders = it.folders.map { folder -> folder.copy(title = "Ember Pictures") }) })
        runCurrent()
        assertEquals("Ember Pictures", f.state.title)
        assertEquals(listOf("m1", "m2", "both"), f.state.rows[0].items.map { it.id }, "an unchanged source keeps its titles")
        assertEquals(asked, f.env.http.requests.size)
        assertFalse(f.state.gone)

        f.collections.value = emptyList()
        runCurrent()
        assertTrue(f.state.gone)
    }

    @Test
    fun aCardOpensItsTitle() = runTest {
        val f = Fixture(this, collection(EMBER_MOVIES))
        runCurrent()
        f.store.openItem(f.grid.items.first())
        assertEquals(listOf("meta movie/m1"), f.host.opened)
    }
}
