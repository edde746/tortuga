package com.edde746.tortuga.collections

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nuvio's collection files as packs and AIOMetadata's exporter write them, and the files Tortuga refuses. */
class NuvioCollectionsTest {
    private fun fixture(name: String) = NuvioCollections.decode(FixtureCorpus.text("collections/$name"))

    private val CollectionFile.folders get() = collections.flatMap { it.folders }
    private val CollectionFile.sources get() = folders.flatMap { it.sources }

    @Test
    fun legacyCatalogSourcesAndShapesInAnyCase() {
        val file = fixture("pack-legacy-streaming.json")
        assertEquals(3, file.collections.size)
        assertEquals(13, file.folders.size)
        assertEquals(listOf(PosterShape.LANDSCAPE, PosterShape.POSTER, PosterShape.LANDSCAPE), file.collections.map { it.shape }, "Landscape too")
        assertEquals(setOf(CollectionViewMode.TABS), file.collections.map { it.viewMode }.toSet())
        assertTrue(file.collections.all { it.showAllTab })
        assertEquals(13, file.sources.size)
        assertTrue(file.sources.all { it is CollectionSource.AddonCatalog && it.genre == null })
        assertEquals(6, file.folders.count { it.coverUrl != null }, "an empty cover URL is none")
        assertEquals(ImportDrops(), file.dropped)
    }

    @Test
    fun followHomeIsFollowTheAppAndUnknownKeysAreIgnored() {
        val file = fixture("pack-follow-home.json")
        assertEquals(5, file.collections.size)
        assertEquals(51, file.folders.size)
        assertEquals(77, file.sources.filterIsInstance<CollectionSource.AddonCatalog>().size)
        assertEquals(77, file.sources.size)
        assertEquals(setOf(CollectionViewMode.FOLLOW_APP), file.collections.map { it.viewMode }.toSet())
        assertTrue(file.collections.none { it.showAllTab })
        assertTrue(file.folders.all { it.hideTitle })
        assertEquals(
            listOf(PosterShape.SQUARE, PosterShape.LANDSCAPE, PosterShape.POSTER, PosterShape.LANDSCAPE, PosterShape.LANDSCAPE),
            file.collections.map { it.shape },
        )
        assertEquals(ImportDrops(), file.dropped)
    }

    @Test
    fun tmdbIsLeftOutByKindAndTraktListsArePublicLists() {
        val file = fixture("pack-large.json")
        val kinds = T.collections.kind
        assertEquals(
            mapOf(kinds.tmdbDiscover to 388, kinds.tmdbCollection to 326, kinds.tmdbPerson to 348, kinds.tmdbCompany to 102),
            file.dropped.unsupported,
        )
        assertEquals(1_164, file.dropped.unsupported.values.sum())
        assertEquals(listOf("Channels", "Genres", "Film Sets", "People", "Studios", "By Decade"), file.dropped.collections)
        assertEquals(
            listOf("New Movies", "New Series", "Trending Movies", "Trending Series").map { DroppedFolder(it, "Highlights") },
            file.dropped.folders,
        )
        assertEquals(5, file.collections.size)
        assertEquals(43, file.folders.size)
        val addons = file.sources.filterIsInstance<CollectionSource.AddonCatalog>()
        assertEquals(91, addons.size)
        assertTrue(addons.all { it.genre == null }, "genre None and blank are none")
        assertEquals(1, addons.count { it.type == "all" })
        assertEquals(setOf(CollectionViewMode.FOLLOW_APP), file.collections.map { it.viewMode }.toSet())
        assertEquals(
            listOf(PosterShape.LANDSCAPE, PosterShape.LANDSCAPE, PosterShape.POSTER, PosterShape.SQUARE, PosterShape.LANDSCAPE),
            file.collections.map { it.shape },
            "a dropped folder's shape counts",
        )
        // `sources` win: the folder's legacy `catalogSources` don't hold its Trakt lists.
        val awards = file.collections.single { it.title == "Awards" }.folders.single()
        assertEquals(
            listOf(
                CollectionSource.TrackerList("trakt", "800001", TrackerCatalog.Kind.PUBLIC_LIST, "movie", "Award List 1"),
                CollectionSource.TrackerList("trakt", "800002", TrackerCatalog.Kind.PUBLIC_LIST, "movie", "Award List 2"),
                CollectionSource.TrackerList("trakt", "800003", TrackerCatalog.Kind.PUBLIC_LIST, "movie", "Award List 3"),
            ),
            awards.sources,
        )
    }

    @Test
    fun aiometadataExportKeepsNamesGenresAndViewModes() {
        val file = fixture("aiometadata-export.json")
        assertEquals(2, file.collections.size)
        assertEquals(4, file.folders.size)
        val (streaming, genres) = file.collections
        assertEquals(CollectionViewMode.ROWS, streaming.viewMode)
        assertEquals(false, streaming.showAllTab)
        assertEquals(CollectionViewMode.TABS, genres.viewMode)
        assertEquals(listOf(PosterShape.LANDSCAPE, PosterShape.SQUARE), file.collections.map { it.shape })
        assertEquals(listOf(false, true, false, false), file.folders.map { it.hideTitle })
        assertEquals(
            CollectionSource.AddonCatalog("aio-metadata", "movie", "example.movies", null, "AIOMetadata", "Example Movies", null),
            file.sources.first(),
        )
        assertEquals(listOf(null, null, "Action", null, "Horror"), file.sources.map { (it as CollectionSource.AddonCatalog).genre })
    }

    @Test
    fun anExportReadsBackAsItWasWritten() {
        for (name in listOf("pack-legacy-streaming.json", "pack-follow-home.json", "pack-large.json", "aiometadata-export.json")) {
            val collections = fixture(name).collections
            assertEquals(CollectionFile(collections, ImportDrops()), NuvioCollections.decode(NuvioCollections.encode(collections)), name)
        }
        val own = listOf(
            Collection(
                id = "c",
                title = "Mine",
                viewMode = CollectionViewMode.FOLLOW_APP,
                shape = PosterShape.SQUARE,
                backdropUrl = "https://images.example/back.jpg",
                folders = listOf(
                    CollectionFolder(
                        id = "f",
                        title = "Lists",
                        coverUrl = "https://images.example/cover.gif",
                        hideTitle = true,
                        sources = listOf(
                            CollectionSource.AddonCatalog("org.example", "series", "top", "Drama", "Example", "Top", "https://addon.example/abc"),
                            CollectionSource.TrackerList("mdblist", "user/slug", TrackerCatalog.Kind.PUBLIC_LIST, null, "A list"),
                            CollectionSource.TrackerList("simkl", "plantowatch", TrackerCatalog.Kind.PLAN_TO_WATCH),
                            CollectionSource.TrackerList("trakt", "77", TrackerCatalog.Kind.PUBLIC_LIST, "series", "Shows"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(CollectionFile(own, ImportDrops()), NuvioCollections.decode(NuvioCollections.encode(own)))
        // A Trakt list of both types goes to Nuvio as one source per type.
        val both = own.single().copy(
            folders = listOf(own.single().folders.single().copy(sources = listOf(CollectionSource.TrackerList("trakt", "9", TrackerCatalog.Kind.PUBLIC_LIST)))),
        )
        assertEquals(
            listOf("movie", "series"),
            NuvioCollections.decode(NuvioCollections.encode(listOf(both))).sources.map { (it as CollectionSource.TrackerList).type },
        )
    }

    @Test
    fun sourcesWinOverCatalogSourcesUnlessEmpty() {
        fun folder(sources: String) = """[{"id":"c","title":"C","folders":[{"id":"f","title":"F",$sources,
            "catalogSources":[{"addonId":"legacy","type":"movie","catalogId":"top"}]}]}]"""
        val current = NuvioCollections.decode(folder(""""sources":[{"provider":"addon","addonId":"new","type":"movie","catalogId":"top"}]"""))
        assertEquals("new", (current.sources.single() as CollectionSource.AddonCatalog).addonId)
        val empty = NuvioCollections.decode(folder(""""sources":[]"""))
        assertEquals("legacy", (empty.sources.single() as CollectionSource.AddonCatalog).addonId)
        val missing = NuvioCollections.decode("""[{"id":"c","title":"C","folders":[{"id":"f","title":"F","tileShape":"wide",
            "sources":[{"provider":"addon","addonId":"a","type":"movie","catalogId":"top"}]}]}]""")
        assertEquals(PosterShape.LANDSCAPE, missing.collections.single().shape)
        assertNull(missing.collections.single().backdropUrl)
    }

    @Test
    fun unsupportedProvidersAreCountedAndEmptyFoldersAndCollectionsListed() {
        val file = NuvioCollections.decode(
            """[
              {"id":"a","title":"Kept","folders":[
                {"id":"f1","title":"Good","sources":[{"provider":"addon","addonId":"x","type":"movie","catalogId":"top"},
                  {"provider":"letterboxd","listId":"y"}]},
                {"id":"f2","title":"Gone","sources":[{"provider":"Trakt-ish"},{"provider":"tmdb","tmdbSourceType":"network","tmdbId":1}]},
                {"id":"f3","title":"Empty","sources":[]}]},
              {"id":"b","title":"Dropped","folders":[{"id":"f","title":"Only TMDB","sources":[{"provider":"tmdb","tmdbSourceType":"LIST"}]}]},
              {"id":"c","title":"No folders","folders":[]}
            ]""",
        )
        val kinds = T.collections.kind
        assertEquals(
            mapOf(kinds.letterboxd to 1, kinds.unknown(name = "Trakt-ish") to 1, kinds.tmdbNetwork to 1, kinds.tmdbList to 1),
            file.dropped.unsupported,
        )
        assertEquals(listOf(DroppedFolder("Gone", "Kept"), DroppedFolder("Empty", "Kept")), file.dropped.folders)
        assertEquals(listOf("Dropped", "No folders"), file.dropped.collections)
        assertEquals(listOf("f1"), file.folders.map { it.id })
    }

    @Test
    fun filesNuvioRefusesAreRefusedNamingWhatIsWrong() {
        val text = T.collections.refused
        fun refused(json: String) = assertFailsWith<CollectionFileException> { NuvioCollections.decode(json) }.message
        fun one(folder: String) = """[{"id":"c","title":"Movies","folders":[$folder]}]"""
        val source = """{"provider":"addon","addonId":"a","type":"movie","catalogId":"top"}"""

        assertEquals(text.notJson, refused("not json"))
        assertEquals(text.notList, refused("""{"collections":[]}"""))
        assertEquals(text.empty, refused("[]"))
        assertEquals(text.collectionInvalid(position = "2"), refused("""[{"id":"c","title":"C","folders":[]}, 3]"""))
        assertEquals(text.collectionNoId(position = "1"), refused("""[{"id":"","title":"C","folders":[]}]"""))
        assertEquals(text.collectionNoTitle(id = "c"), refused("""[{"id":"c","folders":[]}]"""))
        assertEquals(text.duplicateCollection(id = "c"), refused("""[{"id":"c","title":"A","folders":[]},{"id":"c","title":"B","folders":[]}]"""))
        assertEquals(text.noFolders(collection = "Movies"), refused("""[{"id":"c","title":"Movies"}]"""))
        assertEquals(text.folderInvalid(position = "1", collection = "Movies"), refused(one("\"f\"")))
        assertEquals(text.folderNoId(position = "1", collection = "Movies"), refused(one("""{"title":"F","sources":[$source]}""")))
        assertEquals(text.folderNoTitle(id = "f", collection = "Movies"), refused(one("""{"id":"f","sources":[$source]}""")))
        assertEquals(
            text.duplicateFolder(collection = "Movies", id = "f"),
            refused(one("""{"id":"f","title":"A","sources":[$source]},{"id":"f","title":"B","sources":[$source]}""")),
        )
        assertEquals(text.noSources(folder = "F", collection = "Movies"), refused(one("""{"id":"f","title":"F"}""")))
        assertEquals(
            text.unknownShape(folder = "F", collection = "Movies", shape = "HEXAGON"),
            refused(one("""{"id":"f","title":"F","tileShape":"HEXAGON","sources":[$source]}""")),
        )
        for (incomplete in listOf(
            """{"provider":"addon","addonId":"a","type":"movie"}""",
            """{"provider":"trakt","traktListId":"12"}""",
            """{"provider":"trakt","traktListId":0}""",
            """{"provider":"tmdb","tmdbId":12}""",
            """{"provider":"tracker","service":"trakt","listId":"w","kind":"SOMETHING"}""",
            "7",
        )) {
            assertEquals(
                text.sourceInvalid(position = "2", folder = "F", collection = "Movies"),
                refused(one("""{"id":"f","title":"F","sources":[$source,$incomplete]}""")),
                incomplete,
            )
        }
    }

    @Test
    fun aTraktOrMdblistListIsTheNumberTheServiceGaveIt() {
        fun one(source: String) = """[{"id":"c","title":"Movies","folders":[{"id":"f","title":"F","sources":[$source]}]}]"""
        fun tracker(service: String, listId: String) = """{"provider":"tracker","service":"$service","listId":"$listId","kind":"LIST"}"""
        for (service in listOf("trakt", "mdblist")) {
            assertEquals("list.7", (NuvioCollections.decode(one(tracker(service, "list.7"))).sources.single() as CollectionSource.TrackerList).listId)
            for (listId in listOf("list.../../users/settings", "list.0", "list.-3", "watchlist")) {
                assertEquals(
                    T.collections.refused.sourceInvalid(position = "1", folder = "F", collection = "Movies"),
                    assertFailsWith<CollectionFileException> { NuvioCollections.decode(one(tracker(service, listId))) }.message,
                    "$service $listId",
                )
            }
        }
    }
}
