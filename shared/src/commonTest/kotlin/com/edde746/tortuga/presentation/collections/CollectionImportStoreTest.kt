@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.strings.T
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Importing collections: a file by its link or opened, summed up, then imported. */
class CollectionImportStoreTest {
    /** A pack in Nuvio's format: Studios (one folder of the studio's movies), Mixed (a missing addon and TMDB). */
    private val pack = """[
        {"id":"studios","title":"Studios","viewMode":"TABBED_GRID","showAllTab":true,"folders":[
            {"id":"ember","title":"Ember","tileShape":"POSTER","sources":[
                {"provider":"addon","addonId":"org.example.studio","type":"movie","catalogId":"ember","catalogName":"Ember Movies"}]}]},
        {"id":"mixed","title":"Mixed","viewMode":"ROWS","folders":[
            {"id":"tops","title":"Tops","tileShape":"SQUARE","sources":[
                {"provider":"addon","addonId":"org.example.missing","addonName":"Missing Addon","type":"movie","catalogId":"top"},
                {"provider":"tmdb","tmdbSourceType":"DISCOVER","title":"New"}]}]}
    ]"""

    private class Fixture(scope: TestScope, private val pack: String) {
        val env = BrowseEnv(scope) { respond("", HttpStatusCode.NotFound) }
        val http = FakeHttp(scope) { request ->
            when (request.url.encodedPath) {
                "/pack.json" -> json(pack)
                "/huge.json" -> json("[" + " ".repeat(CollectionImportStore.MAX_BYTES.toInt()) + "]")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val store = CollectionImportStore(
            "p", http.client, env.data.collections, addons = { listOf(STUDIO) }, trackers = { emptyList() }, traktLists = null,
            offersFiles = true, work = StandardTestDispatcher(scope.testScheduler), parentScope = scope.backgroundScope,
        )
        val step get() = store.state.value.step
    }

    @Test
    fun aFileIsSummedUpThenImported() = runTest {
        val f = Fixture(this, pack)
        f.store.open(pack)
        assertEquals(CollectionImportStep.Loading, f.step)
        runCurrent()
        val summary = assertIs<CollectionImportStep.Summary>(f.step)
        assertEquals(
            listOf(
                CollectionImportRow("Studios", T.collections.importing.rowNew(1), "\u2068Ember\u2069", replaces = false),
                CollectionImportRow("Mixed", T.collections.importing.rowNew(1), "\u2068Tops\u2069", replaces = false),
            ),
            summary.collections,
        )
        val text = T.collections.summary
        assertEquals(
            listOf(text.adds(2), text.needsAddon(1, name = "Missing Addon"), text.unsupported(1, kind = T.collections.kind.tmdbDiscover), text.unsupportedHint),
            summary.lines,
        )
        assertEquals(true, summary.canImport)

        f.store.confirm()
        runCurrent()
        assertEquals(CollectionImportStep.Done(T.collections.importing.imported(2), T.collections.importing.done), f.step)
        assertEquals(listOf("mixed", "studios"), f.env.data.collections.collections("p").map(Collection::id))

        // Again: both replace the profile's.
        f.store.open(pack)
        runCurrent()
        val again = assertIs<CollectionImportStep.Summary>(f.step)
        assertEquals(listOf(true, true), again.collections.map { it.replaces })
        assertEquals(T.collections.importing.rowReplaces(1), again.collections.first().detail)
    }

    @Test
    fun aLinkIsReadOverHttpWithinItsCap() = runTest {
        val f = Fixture(this, pack)
        f.store.fetch("ftp://example.com/pack.json")
        assertEquals(CollectionImportStep.Failed(T.collections.importing.notWeb), f.step)
        assertEquals(0, f.http.requests.size)

        f.store.fetch(" https://example.com/pack.json ")
        runCurrent()
        assertIs<CollectionImportStep.Summary>(f.step)

        f.store.fetch("https://example.com/huge.json")
        runCurrent()
        assertEquals(CollectionImportStep.Failed(T.collections.importing.tooLarge), f.step)

        f.store.fetch("https://example.com/missing.json")
        runCurrent()
        assertEquals(CollectionImportStep.Failed(T.collections.importing.httpError("404")), f.step)
    }

    @Test
    fun aFileThatIsntACollectionsFileIsRefusedWithItsReason() = runTest {
        val f = Fixture(this, pack)
        f.store.open("not json")
        runCurrent()
        assertEquals(CollectionImportStep.Failed(T.collections.refused.notJson), f.step)
        f.store.openFailed()
        assertEquals(CollectionImportStep.Failed(T.collections.importing.unreadable), f.step)
        f.store.confirm()
        assertIs<CollectionImportStep.Failed>(f.step, "nothing to import")
    }
}
