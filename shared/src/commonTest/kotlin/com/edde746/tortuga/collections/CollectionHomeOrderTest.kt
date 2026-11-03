package com.edde746.tortuga.collections

import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.presentation.board.collectionHomeKey
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where collections go on Home: new ones (imported, or made in the editor) first, in the file's order, also on a
 * profile that never arranged Home; replaced ones keep their place; every other row keeps its own.
 */
class CollectionHomeOrderTest {
    private val data = testData(TestClock())
    private val p = "p"

    /** Rows Home has besides the collections, in their default order. */
    private val others = listOf("addon|cinemeta|movie|top", "addon|cinemeta|series|top")

    @AfterTest
    fun close() = data.close()

    private fun collection(id: String, title: String = "Collection $id") = Collection(
        id = id,
        title = title,
        folders = listOf(CollectionFolder("$id-f", "Folder", sources = listOf(CollectionSource.AddonCatalog("org.example", "movie", "top")))),
    )

    /** Home's rows as it shows them: by default the collections (as stored) and then the others, arranged by the layout. */
    private suspend fun home(): List<String> {
        val rows = data.collections.collections(p).map { collectionHomeKey(it.id) } + others
        return data.settings.settings(p).homeRows.arrange(rows)
    }

    @Test
    fun anImportOnTheDefaultLayoutGoesFirstInTheFilesOrder() = runTest {
        data.collections.save(p, collection("b-old"))
        data.collections.import(p, listOf(collection("z-studios", "Studios"), collection("m-mixed", "Mixed")))
        assertEquals(
            listOf("z-studios", "m-mixed", "b-old").map(::collectionHomeKey) + others,
            home(),
            "the file's order, ahead of the collection there before, the other rows in their default place",
        )
    }

    @Test
    fun aNewCollectionGoesFirstOnTheDefaultLayout() = runTest {
        data.collections.save(p, collection("a-first"))
        data.collections.save(p, collection("z-new"))
        assertEquals(listOf("z-new", "a-first").map(::collectionHomeKey) + others, home())
    }

    @Test
    fun aReplacedCollectionKeepsItsPlace() = runTest {
        data.collections.save(p, collection("b-old"))
        data.collections.save(p, collection("a-kept"))
        data.settings.set(p, ProfileSetting.HomeRows, HomeLayout(order = listOf(others[1], collectionHomeKey("b-old"), others[0], collectionHomeKey("a-kept"))))
        data.collections.import(p, listOf(collection("a-kept", "Renamed"), collection("y-new")))
        assertEquals(listOf(collectionHomeKey("y-new"), others[1], collectionHomeKey("b-old"), others[0], collectionHomeKey("a-kept")), home())
    }
}
