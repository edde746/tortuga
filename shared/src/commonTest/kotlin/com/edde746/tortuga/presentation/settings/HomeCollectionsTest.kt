@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionFolder
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.NuvioCollections
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.catalogAddon
import com.edde746.tortuga.presentation.collections.CollectionEditorStore
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Settings → Home's collections: rows among the others, deleted after a question, edited, exported. */
class HomeCollectionsTest {
    private val source = CollectionSource.AddonCatalog("org.example.lists", "movie", "trending", catalogName = "Trending")
    private val studios = Collection(
        "studios", "Studios",
        folders = listOf("a", "b").map { CollectionFolder(it, "Folder $it", sources = listOf(source)) },
    )
    private val mixed = Collection("mixed", "Mixed", shape = PosterShape.SQUARE, folders = listOf(CollectionFolder("c", "Folder c", sources = listOf(source))))

    private class Fixture(scope: TestScope) {
        val data = LocalData(inMemoryDatabase(), { 1_790_000_000_000L }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val editors = mutableListOf<String?>()
        val store = HomeSettingsStore(
            "p", MutableStateFlow(listOf(catalogAddon("lists", """[{"type":"movie","id":"trending","name":"Trending"}]"""))), flowOf(emptyList()),
            flowOf(emptySet()), data.settings, data.collections, scope.backgroundScope,
            newImport = { error("no import here") },
            newEditor = { id, close ->
                editors += id
                CollectionEditorStore("p", id, data.collections, flowOf(emptyList()), flowOf(emptyList()), traktLists = null, close, scope.backgroundScope)
            },
            // 2 Nov 2026, 23:30 UTC: the 3rd an hour east of it.
            now = { 1_793_662_200_000L }, utcOffset = { 3600 },
        )
        val state get() = store.state.value
        val rows get() = (state.body as HomeSettingsBody.Rows).rows
    }

    @Test
    fun collectionsAreRowsFirstWithTheirFolderCountAndAMenu() = runTest {
        val f = Fixture(this)
        f.data.collections.save("p", studios)
        f.data.collections.save("p", mixed)
        runCurrent()
        assertEquals(listOf("Mixed", "Studios", "\u2068Trending\u2069 · Movies"), f.rows.map { it.title }, "first, the newest first")
        assertEquals(listOf(T.collections.rowSource(1), T.collections.rowSource(2), "lists"), f.rows.map { it.sourceName })
        assertEquals(listOf("mixed", "studios", null), f.rows.map { it.collectionId })
        assertEquals(listOf(T.settings.home.edit, T.settings.home.edit, null), f.rows.map { it.editLabel })
        assertEquals(true, f.state.collections.canExport)

        f.store.moveDown(f.rows.first().id)
        runCurrent()
        assertEquals(listOf("Studios", "Mixed"), f.rows.take(2).map { it.title })
    }

    @Test
    fun deletingAsksFirst() = runTest {
        val f = Fixture(this)
        f.data.collections.save("p", studios)
        runCurrent()
        f.store.askDelete("studios")
        assertEquals("studios", f.state.deleteDialog?.collectionId)
        f.store.dismissDelete()
        runCurrent()
        assertNull(f.state.deleteDialog)
        assertEquals(listOf(studios), f.data.collections.collections("p"))

        f.store.askDelete("studios")
        f.store.confirmDelete()
        runCurrent()
        assertNull(f.state.deleteDialog)
        assertEquals(emptyList(), f.data.collections.collections("p"))
        assertEquals(listOf("\u2068Trending\u2069 · Movies"), f.rows.map { it.title })
        assertEquals(false, f.state.collections.canExport)
    }

    @Test
    fun theExportIsNuviosFormatInHomesOrderNamedForToday() = runTest {
        val f = Fixture(this)
        assertNull(f.store.export(), "nothing to export")
        f.data.collections.save("p", studios)
        f.data.collections.save("p", mixed)
        runCurrent()
        val export = assertNotNull(f.store.export())
        assertEquals("tortuga-collections-2026-11-03.json", export.fileName)
        assertEquals(listOf(mixed, studios), NuvioCollections.decode(export.text).collections)

        f.store.exportFailed()
        assertEquals(T.settings.data.saveFailed, f.state.notice)
        f.store.dismissNotice()
        assertNull(f.state.notice)
    }

    @Test
    fun theEditorOpensOverThePageAndClosesItself() = runTest {
        val f = Fixture(this)
        f.data.collections.save("p", studios)
        runCurrent()
        f.store.editCollection("studios")
        runCurrent()
        assertEquals(HomeSettingsPage.EDITOR, f.state.page)
        val editor = assertNotNull(f.store.collectionEditor)
        assertEquals("Studios", editor.state.value.title)
        editor.back()
        assertNull(f.state.page, "Back from the editor closes it")
        assertNull(f.store.collectionEditor)

        f.store.newCollection()
        assertEquals(listOf("studios", null), f.editors)
        val created = f.store.collectionEditor
        f.store.editCollection("nope")
        assertSame(created, f.store.collectionEditor, "no editor for a collection the profile hasn't")
        f.store.closePage()
        assertNull(f.state.page)
    }
}
