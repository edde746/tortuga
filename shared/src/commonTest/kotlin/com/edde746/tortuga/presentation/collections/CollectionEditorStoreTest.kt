@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.CollectionViewMode
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.presentation.board.FakeCatalogSource
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The collection editor: a new or existing collection, its folders and their sources, checked and saved. */
class CollectionEditorStoreTest {
    private class Fixture(val data: LocalData, val store: CollectionEditorStore) {
        var closed = 0
        val state get() = store.state.value
    }

    /** An editor of [collectionId] (null: a new one), [saved] stored before it opens. */
    private suspend fun TestScope.fixture(
        collectionId: String?,
        saved: Collection? = null,
        trackers: List<TrackerCatalogSource> = emptyList(),
    ): Fixture {
        val data = BrowseEnv(this) { respond("", HttpStatusCode.NotFound) }.data
        saved?.let { data.collections.save("p", it) }
        lateinit var fixture: Fixture
        val store = CollectionEditorStore(
            "p", collectionId, data.collections, MutableStateFlow(listOf(STUDIO)), MutableStateFlow(trackers), traktLists = null,
            close = { fixture.closed++ }, parentScope = backgroundScope,
        )
        fixture = Fixture(data, store)
        runCurrent()
        return fixture
    }

    @Test
    fun aNewCollectionIsCheckedThenSaved() = runTest {
        val f = fixture(collectionId = null)
        val text = T.collections.editor
        assertEquals(text.newHeading, f.state.heading)
        f.store.save()
        assertEquals(text.noTitle, f.state.error)
        f.store.setTitle("  Studios ")
        assertNull(f.state.error, "an edit clears the refusal")
        f.store.save()
        assertEquals(text.noFolder, f.state.error)

        f.store.addFolder()
        assertEquals(text.newFolder, f.state.folder?.heading, "a new folder's page opens")
        f.store.back()
        assertNull(f.state.folder)
        f.store.save()
        assertEquals(text.folderNoTitle("1"), f.state.error)

        f.store.openFolder(0)
        f.store.setFolderTitle("Ember")
        f.store.choose("shape", PosterShape.LANDSCAPE.name)
        f.store.setCoverUrl(" https://img.example/ember.jpg ")
        f.store.save()
        assertEquals(text.folderNoSource("\u2068Ember\u2069"), f.state.error)

        f.store.openPicker()
        val addons = assertNotNull(f.state.picker).groups.first()
        assertEquals(text.picker.addons, addons.title)
        assertEquals(
            listOf(
                "\u2068Ember Movies\u2069 · Movies",
                "\u2068Ember Series\u2069 · Series",
                "\u2068\u2068Genres\u2069 · Movies\u2069 · Drama",
                "\u2068\u2068Genres\u2069 · Movies\u2069 · Comedy",
            ),
            addons.choices.map { it.title },
            "no search catalog; a catalog that requires a genre once per genre",
        )
        f.store.pick(addons.choices[1].key)
        assertNull(f.state.picker, "a pick closes the picker")
        f.store.openPicker()
        assertEquals(listOf(false, true, false, false), f.state.picker?.groups?.first()?.choices?.map { it.added })
        f.store.pick(addons.choices[0].key)
        assertEquals(listOf("Ember Series", "Ember Movies"), f.state.folder?.sources?.map { it.title })
        f.store.moveSourceUp(1)
        f.store.choose("viewMode", CollectionViewMode.ROWS.name)
        f.store.toggle("showAllTab", false)
        f.store.save()
        runCurrent()

        assertEquals(1, f.closed)
        val saved = f.data.collections.collections("p").single()
        assertEquals("Studios", saved.title)
        assertEquals(CollectionViewMode.ROWS, saved.viewMode)
        assertEquals(false, saved.showAllTab)
        val folder = saved.folders.single()
        assertEquals("Ember", folder.title)
        assertEquals(PosterShape.LANDSCAPE, saved.shape)
        assertEquals("https://img.example/ember.jpg", folder.coverUrl)
        assertEquals(listOf("movie", "series"), folder.sources.map { (it as CollectionSource.AddonCatalog).type })
    }

    @Test
    fun anExistingCollectionIsEditedAndBackDropsWhatWasntSaved() = runTest {
        val studios = collection(EMBER_MOVIES, MISSING, more = listOf(folder("g", "Second", EMBER_SERIES)))
        val f = fixture(collectionId = "c", saved = studios)
        assertEquals(T.collections.editor.editHeading, f.state.heading)
        assertEquals(listOf("Ember", "Second"), f.state.folders.map { it.title })
        f.store.moveFolderDown(0)
        assertEquals(listOf("Second", "Ember"), f.state.folders.map { it.title })
        f.store.openFolder(1)
        assertEquals(listOf(null, T.collections.needsAddon(name = "Missing Addon")), f.state.folder?.sources?.map { it.note })
        f.store.removeSource(1)
        f.store.back()
        f.store.deleteFolder(0)
        f.store.setTitle("Changed")
        f.store.back()
        f.store.discard()
        runCurrent()
        assertEquals(1, f.closed)
        assertEquals(listOf(studios), f.data.collections.collections("p"), "nothing saved")

        val gone = fixture(collectionId = "nope")
        assertEquals(1, gone.closed, "a collection the profile hasn't closes the editor")
    }

    @Test
    fun trackersOfferTheirListsAndAListByLink() = runTest {
        val list = TrackerCatalog(TrackerService.MDBLIST, "list.7", TrackerCatalog.Kind.LIST, null, "Best")
        val mdblist = FakeCatalogSource(TrackerService.MDBLIST, listOf(list), emptyMap())
        val f = fixture(collectionId = null, trackers = listOf(mdblist))
        f.store.addFolder()
        f.store.openPicker()
        runCurrent()
        val trackers = f.state.picker!!.groups[1]
        val text = T.collections.editor.picker
        assertEquals(text.trackers, trackers.title)
        assertEquals(listOf("Best", text.byLink("MDBList")), trackers.choices.map { it.title })

        f.store.pick(trackers.choices[1].key)
        assertEquals(text.byLink("MDBList"), f.state.picker?.urlForm?.title)
        f.store.submitListUrl("https://example.com/not-a-list")
        runCurrent()
        assertEquals(text.notAList("MDBList"), f.state.picker?.urlForm?.error)
        f.store.back()
        assertNull(f.state.picker?.urlForm)

        f.store.pick(trackers.choices[0].key)
        f.store.setFolderTitle("Lists")
        f.store.setTitle("Mine")
        f.store.save()
        runCurrent()
        assertEquals(
            listOf(CollectionSource.TrackerList("mdblist", "list.7", TrackerCatalog.Kind.LIST, null, "Best")),
            f.data.collections.collections("p").single().folders.single().sources,
        )
    }

    @Test
    fun aLinkLookupLeftByBackAddsItsListNowhere() = runTest {
        val answer = CompletableDeferred<TrackerCatalog?>()
        val mdblist = object : TrackerCatalogSource {
            override val service = TrackerService.MDBLIST
            override suspend fun catalogs(): List<TrackerCatalog> = emptyList()
            override suspend fun page(catalog: TrackerCatalog, cursor: String?) = TrackerCatalogPage(emptyList(), next = null)
            override suspend fun publicList(input: String): TrackerCatalog? = answer.await()
        }
        val f = fixture(collectionId = "c", saved = collection(EMBER_MOVIES, more = listOf(folder("g", "Second", EMBER_SERIES))), trackers = listOf(mdblist))
        f.store.openFolder(0)
        f.store.openPicker()
        runCurrent()
        f.store.pick(f.state.picker!!.groups[1].choices.last().key)
        f.store.submitListUrl("https://mdblist.com/lists/someone/best")
        runCurrent()
        // The lookup is slow: the user backs out of the form, the picker and the folder, and opens the other folder.
        repeat(3) { f.store.back() }
        f.store.openFolder(1)
        answer.complete(TrackerCatalog(TrackerService.MDBLIST, "list.7", TrackerCatalog.Kind.PUBLIC_LIST, null, "Best"))
        runCurrent()

        assertEquals(listOf("Ember Series"), f.state.folder?.sources?.map { it.title }, "the open folder isn't the one it was asked for")
        f.store.back()
        f.store.openFolder(0)
        assertEquals(listOf("Ember Movies"), f.state.folder?.sources?.map { it.title }, "nor is the list added to the folder it was asked for")
    }
}
