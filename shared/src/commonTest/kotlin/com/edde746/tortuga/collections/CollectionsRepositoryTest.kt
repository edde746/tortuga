package com.edde746.tortuga.collections

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.PushAck
import com.edde746.tortuga.data.sync.RejectedRecord
import com.edde746.tortuga.data.sync.RemoteRecord
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.collectionHomeKey
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A profile's collections as synced settings: their records, Home's layout, and an import's merge. */
class CollectionsRepositoryTest {
    private val clockA = TestClock()
    private val a = testData(clockA)
    private val b = testData(TestClock())
    private val p = "p"

    @AfterTest
    fun close() {
        a.close()
        b.close()
    }

    private fun collection(id: String, title: String = "Collection $id", sources: Int = 1) = Collection(
        id = id,
        title = title,
        viewMode = CollectionViewMode.ROWS,
        showAllTab = false,
        shape = PosterShape.LANDSCAPE,
        backdropUrl = "https://images.example/$id.jpg",
        folders = listOf(
            CollectionFolder(
                id = "$id-f",
                title = "Folder",
                coverUrl = "https://images.example/$id-f.png",
                sources = List(sources) { CollectionSource.AddonCatalog("org.example.addon", "movie", "catalog-$it", addonName = "Example") } +
                    CollectionSource.TrackerList("trakt", "77", TrackerCatalog.Kind.PUBLIC_LIST, "movie", "Best"),
            ),
        ),
    )

    /** Everything [from] has dirty, as the server would hand it to another device, marked pushed on [from]. */
    private suspend fun push(from: LocalData): List<RemoteRecord> {
        val dirty = from.sync.dirtyRecords(null, 1_000)
        from.sync.markPushed(dirty.mapIndexed { i, record -> PushAck(record.recordId, record.hlc, i + 1L) })
        return dirty.mapIndexed { i, record -> RemoteRecord(record.recordId, i + 1L, Json.parseToJsonElement(record.payload).jsonObject) }
    }

    @Test
    fun aCollectionSyncsAsItsOwnSettingAndItsDeletionToo() = runTest {
        val movies = collection("movies")
        val shows = collection("shows")
        a.collections.save(p, movies)
        a.collections.save(p, shows)
        val records = push(a)
        // Each its own record, beside Home's layout (which took them first).
        assertEquals(setOf("setting/p/collection.movies", "setting/p/collection.shows", "setting/p/home.rows"), records.map { it.recordId }.toSet())

        assertEquals(emptyList(), b.sync.applyRemote(records))
        assertEquals(listOf(movies, shows), b.collections.observe(p).first())

        clockA.now += 1_000
        a.collections.delete(p, "movies")
        val dirty = a.sync.dirtyRecords(null, 10)
        // The collection's tombstone, and Home's layout without it.
        assertEquals(setOf("setting/p/collection.movies", "setting/p/home.rows"), dirty.map { it.recordId }.toSet())
        assertTrue(dirty.single { it.recordId == "setting/p/collection.movies" }.deleted)
        assertEquals(emptyList(), b.sync.applyRemote(push(a)))
        assertEquals(listOf(shows), b.collections.collections(p))
    }

    @Test
    fun aCollectionValueThatDoesntReadIsRefused() = runTest {
        a.collections.save(p, collection("movies"))
        val record = push(a).single { it.recordId == "setting/p/collection.movies" }
        val body = record.payload.getValue("body").jsonObject
        fun with(value: String) = JsonObject(record.payload + ("body" to JsonObject(body + ("value" to JsonPrimitive(value)))))
        val stored = Json.parseToJsonElement(a.settings.values(p, "collection.").values.single()).jsonObject

        val rejected = b.sync.applyRemote(
            listOf(
                RemoteRecord(record.recordId, 1, with("""{"id":"movies","title":"No folders"}""")),
                RemoteRecord(record.recordId, 2, with(JsonObject(stored + ("id" to JsonPrimitive("other"))).toString())),
            ),
        )
        assertEquals(listOf(RejectedRecord.Reason.UNREADABLE, RejectedRecord.Reason.UNREADABLE), rejected.map { it.reason })
        assertEquals(emptyList(), b.collections.collections(p))
    }

    @Test
    fun anImportReplacesByIdKeepingItsPlaceAndAddsFirst() = runTest {
        val old = collection("old", "Old")
        a.collections.save(p, old)
        val addon = "addon|https://addon.example/manifest.json|movie|top"
        val layout = HomeLayout(order = listOf(addon, collectionHomeKey("old")), hidden = listOf(collectionHomeKey("old")))
        a.settings.set(p, ProfileSetting.HomeRows, layout)

        val renamed = old.copy(title = "Old, renamed")
        val result = a.collections.import(p, listOf(collection("new1"), renamed, collection("new2")))

        assertEquals(ImportResult(added = listOf("new1", "new2"), replaced = listOf("old"), tooLarge = emptyList()), result)
        assertEquals(listOf(collection("new1"), collection("new2"), renamed), a.collections.collections(p))
        assertEquals(
            HomeLayout(order = listOf(collectionHomeKey("new1"), collectionHomeKey("new2"), addon, collectionHomeKey("old")), hidden = layout.hidden),
            a.settings.settings(p).homeRows,
        )
        // A profile that never arranged Home: the new one first, the rest of Home in its default order.
        assertEquals(ImportResult(listOf("x"), emptyList(), emptyList()), b.collections.import(p, listOf(collection("x"))))
        assertEquals(HomeLayout(order = listOf(collectionHomeKey("x"))), b.settings.settings(p).homeRows)
    }

    @Test
    fun aCollectionTooLargeToSyncIsNeverStored() = runTest {
        val large = collection("large", sources = 3_000)
        assertFalse(a.collections.fitsSync(p, large))
        assertTrue(a.collections.fitsSync(p, collection("small", sources = 1_000)))
        val error = assertFailsWith<CollectionTooLargeException> { a.collections.save(p, large) }
        assertEquals(T.collections.tooLarge(title = large.title), error.message)

        val result = a.collections.import(p, listOf(large, collection("small")))
        assertEquals(ImportResult(listOf("small"), emptyList(), listOf(large)), result)
        assertEquals(listOf("small"), a.collections.collections(p).map { it.id })
        assertTrue(a.sync.dirtyRecords(null, 10).all { it.payload.length <= SyncProtocol.MAX_RECORD_BYTES })
    }

    @Test
    fun deletingDropsTheCollectionFromHomesLayout() = runTest {
        a.collections.save(p, collection("a"))
        a.collections.save(p, collection("b"))
        a.settings.set(p, ProfileSetting.HomeRows, HomeLayout(order = listOf(collectionHomeKey("b"), collectionHomeKey("a")), hidden = listOf(collectionHomeKey("a"))))
        a.collections.delete(p, "a")
        assertEquals(HomeLayout(order = listOf(collectionHomeKey("b"))), a.settings.settings(p).homeRows)
        assertEquals(listOf("b"), a.collections.collections(p).map { it.id })
    }

    @Test
    fun theImportSummaryListsWhatItAddsReplacesNeedsAndLeavesOut() = runTest {
        val file = NuvioCollections.decode(FixtureCorpus.text("collections/pack-large.json"))
        val current = listOf(file.collections.first().copy(title = "Mine"))
        val tooLarge = file.collections.last()
        val summary = CollectionImport.summary(file, current, addons = emptyList(), trackers = emptyList(), traktLists = null) { it != tooLarge }

        val text = T.collections.summary
        val kinds = T.collections.kind
        assertEquals(file.collections - tooLarge, summary.collections)
        assertEquals(listOf(file.collections.first().id), summary.replaced)
        assertEquals(listOf(tooLarge), summary.tooLarge)
        assertEquals(
            listOf(
                text.adds(3),
                text.replaces(1),
                text.needsAddon(64, name = "org.example.metadata"),
                text.needsAddon(27, name = "org.example.streaming"),
                text.unsupported(388, kind = kinds.tmdbDiscover),
                text.unsupported(326, kind = kinds.tmdbCollection),
                text.unsupported(348, kind = kinds.tmdbPerson),
                text.unsupported(102, kind = kinds.tmdbCompany),
                text.unsupportedHint,
            ) +
                listOf("New Movies", "New Series", "Trending Movies", "Trending Series").map { text.folderDropped(folder = it, collection = "Highlights") } +
                listOf("Channels", "Genres", "Film Sets", "People", "Studios", "By Decade").map { text.collectionDropped(title = it) } +
                text.tooLarge(title = "Awards"),
            summary.lines,
        )
        // The Trakt lists of a build without Trakt say so, once.
        val withTrakt = CollectionImport.summary(file, emptyList(), emptyList(), emptyList(), null) { true }
        assertEquals(1, withTrakt.lines.count { it == T.collections.traktUnavailable })
    }
}
