@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionFolder
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.kids.KIDS_ADDON_URL
import com.edde746.tortuga.kids.TOY_STORY
import com.edde746.tortuga.kids.kidsWorld
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.strings.T
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The profile's collections on Home: rows of folder tiles, arranged with the others, opening their folders. */
class BoardCollectionsTest {
    private val lists = catalogAddon("lists", """[{"type":"movie","id":"trending","name":"Trending"}]""")

    private class Host : BoardHost {
        val opened = mutableListOf<String>()
        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit
        override fun openVideo(type: String, id: String, videoId: String) = Unit
        override fun openDiscover(link: DeepLink.Discover) = Unit
        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit
        override fun openDownloads() = Unit
        override fun openWatchTogether() = Unit
        override fun openLibrary() = Unit
        override fun openGuide() = Unit
        override fun openCalendar() = Unit
        override fun openCollectionFolder(collectionId: String, folderId: String) {
            opened += "$collectionId/$folderId"
        }
    }

    private inner class Fixture(scope: TestScope, collections: List<Collection>, layout: HomeLayout = HomeLayout()) {
        val env = BrowseEnv(scope) { request ->
            if (request.url.host == "lists.example.com") json("""{"metas":[{"id":"tt1","type":"movie","name":"One"}]}""") else respond("", HttpStatusCode.NotFound)
        }
        val collections = MutableStateFlow(collections)
        val layout = MutableStateFlow(layout)
        val host = Host()
        val store = BoardStore(
            "p", flowOf(listOf(lists)), env.aggregator, env.data.library, flowOf(emptyList()),
            flowOf(emptyList()), this.layout, host, scope.backgroundScope, MutableStateFlow(NetworkState.ONLINE), offersDownloads = false,
            flowOf(emptyList()), flowOf(0), flowOf(false), StandardTestDispatcher(scope.testScheduler),
            MediaActions("p", env.data.library, trackerMarks = null, scope.backgroundScope, notice = {}) { _, _ -> null }, remote = false,
            collections = this.collections,
        )
        val rows get() = store.state.value.rows
    }

    private fun folder(id: String, vararg sources: CollectionSource, hideTitle: Boolean = false) =
        CollectionFolder(id, "Folder $id", "https://img.example/$id.jpg", hideTitle, sources.toList())

    private val trending = CollectionSource.AddonCatalog("org.example.lists", "movie", "trending")
    private val missing = CollectionSource.AddonCatalog("org.example.gone", "movie", "top", addonName = "Gone")
    private val studios = Collection(
        "studios", "Studios", shape = PosterShape.LANDSCAPE,
        folders = listOf(folder("a", trending), folder("b", missing, trending), folder("c", missing, hideTitle = true)),
    )
    private val mixed = Collection("mixed", "Mixed", folders = listOf(folder("d", trending)))
    private val empty = Collection("empty", "Empty", folders = emptyList())

    private val trendingTitle = "\u2068Trending\u2069 · Movies"

    @Test
    fun collectionsComeFirstAsRowsOfFolderTilesUnlessTheLayoutSaysOtherwise() = runTest {
        val f = Fixture(this, listOf(studios, empty, mixed))
        runCurrent()
        assertEquals(listOf("Studios", "Mixed", trendingTitle), f.rows.map { it.title }, "first, the empty one left out")
        val row = f.rows.first()
        assertEquals("collection/studios", row.key)
        assertEquals(T.collections.row, row.sourceName)
        assertEquals(SeeAll.NONE, row.seeAll)
        assertEquals(emptyList(), row.items)
        assertEquals(PosterShape.LANDSCAPE, row.folderShape, "the collection's shape, every tile's")
        assertEquals(
            listOf(
                FolderTile("a", "Folder a", "https://img.example/a.jpg", showTitle = true, note = null),
                FolderTile("b", "Folder b", "https://img.example/b.jpg", showTitle = true, note = null),
                FolderTile("c", "Folder c", "https://img.example/c.jpg", showTitle = false, note = T.collections.needsAddon(name = "Gone")),
            ),
            row.folders,
            "a folder none of whose sources can show carries its first source's note",
        )

        f.layout.value = HomeLayout(order = listOf("addon|https://lists.example.com/manifest.json|movie|trending", "collection|mixed"), hidden = listOf("collection|studios"))
        runCurrent()
        assertEquals(listOf(trendingTitle, "Mixed"), f.rows.map { it.title }, "arranged and hidden by the layout")
    }

    @Test
    fun aTileOpensItsFolder() = runTest {
        val f = Fixture(this, listOf(studios, mixed))
        runCurrent()
        f.store.openFolder(1, "d")
        f.store.openFolder(0, "nope")
        f.store.openFolder(2, "a")
        assertEquals(listOf("mixed/d"), f.host.opened)
    }

    @Test
    fun aKidsProfileWithPresetRowsShowsNoCollection() = runTest {
        val preset = HomeCatalog(KIDS_ADDON_URL, "movie", "family")
        val w = kidsWorld(homeCatalogs = listOf(preset))
        w.catalog("movie/family", TOY_STORY)
        val source = CollectionSource.AddonCatalog("org.example.kids", "movie", "family")
        w.graph.data.collections.save(w.kids.id, Collection("kids", "Kids", folders = listOf(folder("k", source))))
        w.graph.data.collections.save(w.parent.id, Collection("kids", "Kids", folders = listOf(folder("k", source))))
        val kids = w.open()
        val board = kids.boardStore()
        runCurrent()
        assertEquals(listOf("\u2068Family\u2069 · Movies"), board.state.value.rows.map { it.title })
        kids.close()

        val parent = w.open(w.parent)
        val parentBoard = parent.boardStore()
        runCurrent()
        assertTrue("Kids" in parentBoard.state.value.rows.map { it.title }, "another profile shows its collection")
        parent.close()
    }
}
