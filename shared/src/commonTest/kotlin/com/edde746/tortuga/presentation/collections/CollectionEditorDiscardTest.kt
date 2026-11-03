@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.strings.T
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Back from the collection editor: it closes at once when nothing changed, else asks "Discard changes?" first. */
class CollectionEditorDiscardTest {
    private class Fixture(val data: LocalData, val store: CollectionEditorStore) {
        var closed = 0
        val state get() = store.state.value
    }

    private suspend fun TestScope.fixture(collectionId: String?, saved: Collection? = null): Fixture {
        val data = BrowseEnv(this) { respond("", HttpStatusCode.NotFound) }.data
        saved?.let { data.collections.save("p", it) }
        lateinit var fixture: Fixture
        val store = CollectionEditorStore(
            "p", collectionId, data.collections, MutableStateFlow(listOf(STUDIO)), MutableStateFlow(emptyList()), traktLists = null,
            close = { fixture.closed++ }, parentScope = backgroundScope,
        )
        fixture = Fixture(data, store)
        runCurrent()
        return fixture
    }

    @Test
    fun backFromAnUnchangedEditorClosesWithoutAsking() = runTest {
        val f = fixture(collectionId = "c", saved = collection(EMBER_MOVIES))
        f.store.openFolder(0)
        f.store.back()
        f.store.setTitle("Other")
        f.store.setTitle("Studios ")
        f.store.back()
        assertNull(f.state.discard, "the same collection as loaded once trimmed")
        assertEquals(1, f.closed)

        val new = fixture(collectionId = null)
        new.store.addFolder()
        new.store.deleteFolder(0)
        new.store.back()
        assertNull(new.state.discard)
        assertEquals(1, new.closed)
    }

    @Test
    fun backFromAChangedEditorAsksAndKeepEditingKeepsTheDraft() = runTest {
        val f = fixture(collectionId = "c", saved = collection(EMBER_MOVIES))
        f.store.choose(f.state.shape.id, PosterShape.SQUARE.name)
        f.store.back()
        val question = assertNotNull(f.state.discard)
        val text = T.collections.editor
        assertEquals(DiscardQuestion(text.discardTitle, text.discard, text.keepEditing), question)
        assertEquals("Discard changes?", question.title)
        assertEquals(0, f.closed)

        f.store.keepEditing()
        assertNull(f.state.discard)
        assertEquals(PosterShape.SQUARE.name, f.state.shape.options.single { it.selected }.id, "the draft stays")
        f.store.back()
        f.store.back()
        assertNull(f.state.discard, "Back on the question keeps editing")
        assertEquals(0, f.closed)

        f.store.back()
        f.store.discard()
        runCurrent()
        assertEquals(1, f.closed)
        assertEquals(PosterShape.POSTER, f.data.collections.collections("p").single().shape, "nothing saved")
    }

    @Test
    fun aNewCollectionWithATitleAsksToo() = runTest {
        val f = fixture(collectionId = null)
        f.store.setTitle("Mine")
        f.store.back()
        assertNotNull(f.state.discard)
        f.store.discard()
        assertEquals(1, f.closed)
    }
}
