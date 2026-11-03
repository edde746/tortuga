package com.edde746.tortuga.collections

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.presentation.board.RowFeed
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.mdblist.mdblistPublicListId
import com.edde746.tortuga.trackers.trakt.TraktLists
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.TestTimeSource

/** Which addon, catalog or tracker a collection's source reads, and what it lacks when it can't. */
class CollectionSourcesTest {
    private fun addon(id: String, name: String, host: String, catalogs: String) = Descriptor(
        Manifest.decode("""{"id":"$id","version":"1.0.0","name":"$name","types":["movie","series"],"resources":["catalog"],"catalogs":$catalogs}"""),
        "https://$host/manifest.json".transportUrl(),
    )

    private val aiom = addon(
        "aio-metadata",
        "AIOMetadata",
        "aio.example/abc",
        """[{"type":"movie","id":"mdblist.86934","name":"Netflix Movies"},
            {"type":"movie","id":"tmdb.top","extra":[{"name":"genre","options":["Horror"]},{"name":"skip"}]},
            {"type":"movie","id":"search","extra":[{"name":"search","isRequired":true}]}]""",
    )

    /** Another configuration of the same addon (the same manifest id), with other catalogs. */
    private val aiomOther = addon("aio-metadata", "AIOMetadata", "aio.example/xyz", """[{"type":"series","id":"other"}]""")

    private class Account(override val service: TrackerService) : TrackerCatalogSource {
        override suspend fun catalogs(): List<TrackerCatalog> = emptyList()
        override suspend fun page(catalog: TrackerCatalog, cursor: String?) = TrackerCatalogPage(emptyList(), null)
    }

    private val traktLists = TraktLists(
        TrackerHttp(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), TrackerService.TRAKT, maxBodyBytes = 1024),
        emptyMap(),
        TestTimeSource(),
    )

    private fun resolve(
        source: CollectionSource,
        addons: List<Descriptor> = listOf(aiomOther, aiom),
        trackers: List<TrackerCatalogSource> = emptyList(),
        trakt: TraktLists? = traktLists,
    ) = CollectionSources.resolve(source, addons, trackers, trakt)

    private fun SourceState.request() = ((this as SourceState.Ready).feed as RowFeed.Addon).planned

    @Test
    fun anInstalledAddonByUrlElseById() {
        val byId = resolve(CollectionSource.AddonCatalog("aio-metadata", "series", "other"))
        assertEquals(aiomOther, byId.request().addon, "the first addon with that manifest id")
        assertEquals(ResourcePath("catalog", "series", "other"), byId.request().request.path)

        val byUrl = resolve(CollectionSource.AddonCatalog("aio-metadata", "movie", "mdblist.86934", addonUrl = "https://aio.example/abc"))
        assertEquals(aiom, byUrl.request().addon)

        val genre = resolve(CollectionSource.AddonCatalog("aio-metadata", "movie", "tmdb.top", genre = "Horror", addonUrl = "https://aio.example/abc"))
        assertEquals(ResourcePath("catalog", "movie", "tmdb.top", listOf(ExtraValue("genre", "Horror"))), genre.request().request.path)
    }

    @Test
    fun aMissingOrTurnedOffAddonIsNamed() {
        assertEquals(SourceState.NeedsAddon("AIOMetadata"), resolve(CollectionSource.AddonCatalog("aio-metadata", "movie", "top", addonName = "AIOMetadata"), addons = emptyList()))
        // Turned off: the profile's enabled addons don't hold it.
        assertEquals(SourceState.NeedsAddon("pw.ers.netflix-catalog"), resolve(CollectionSource.AddonCatalog("pw.ers.netflix-catalog", "movie", "nfx")))
        assertEquals(T.collections.needsAddon(name = "pw.ers.netflix-catalog"), SourceState.NeedsAddon("pw.ers.netflix-catalog").note)
    }

    @Test
    fun aMissingCatalogOrAFilterTortugaCantSetCantShow() {
        val missing = resolve(CollectionSource.AddonCatalog("aio-metadata", "movie", "gone", catalogName = "Gone Movies", addonUrl = "https://aio.example/abc"))
        assertEquals(SourceState.NeedsCatalog("AIOMetadata", "Gone Movies"), missing)
        assertEquals(T.collections.needsCatalog(addon = "AIOMetadata", catalog = "Gone Movies"), missing.note)
        assertEquals(SourceState.NeedsCatalog("AIOMetadata", "nope"), resolve(CollectionSource.AddonCatalog("aio-metadata", "series", "nope")))
        val search = resolve(CollectionSource.AddonCatalog("aio-metadata", "movie", "search", addonUrl = "https://aio.example/abc"))
        assertEquals(SourceState.Unavailable(T.collections.needsFilter), search)
        val noGenre = resolve(CollectionSource.AddonCatalog("aio-metadata", "movie", "mdblist.86934", genre = "Drama", addonUrl = "https://aio.example/abc"))
        assertEquals(SourceState.Unavailable(T.collections.needsFilter), noGenre)
    }

    @Test
    fun aTrackerListNeedsItsTrackerConnected() {
        val watchlist = CollectionSource.TrackerList("simkl", "plantowatch", TrackerCatalog.Kind.PLAN_TO_WATCH, "movie")
        assertEquals(SourceState.NeedsTracker(TrackerService.SIMKL), resolve(watchlist, trackers = listOf(Account(TrackerService.TRAKT))))
        assertEquals(T.settings.trackers.connectNamed(name = "Simkl"), SourceState.NeedsTracker(TrackerService.SIMKL).note)
        val simkl = Account(TrackerService.SIMKL)
        assertEquals(
            SourceState.Ready(RowFeed.Tracker(simkl, TrackerCatalog(TrackerService.SIMKL, "plantowatch", TrackerCatalog.Kind.PLAN_TO_WATCH, "movie"))),
            resolve(watchlist, trackers = listOf(simkl)),
        )
        // MDBList's public lists are read with the connected account's key.
        val mdblist = CollectionSource.TrackerList("mdblist", "user/slug", TrackerCatalog.Kind.PUBLIC_LIST)
        assertEquals(SourceState.NeedsTracker(TrackerService.MDBLIST), resolve(mdblist))
    }

    @Test
    fun aTraktPublicListNeedsNoConnectionButABuildWithTrakt() {
        val list = CollectionSource.TrackerList("trakt", "4325279", TrackerCatalog.Kind.PUBLIC_LIST, "movie", "Best Picture")
        assertEquals(
            SourceState.Ready(RowFeed.Tracker(traktLists, TrackerCatalog(TrackerService.TRAKT, "4325279", TrackerCatalog.Kind.PUBLIC_LIST, "movie", "Best Picture"))),
            resolve(list, trackers = emptyList()),
        )
        assertEquals(SourceState.Unavailable(T.collections.traktUnavailable), resolve(list, trakt = null))
    }

    @Test
    fun publicListUrlsReadAsNuvioReadsThem() {
        assertEquals("123", TraktLists.listPath(" 123 "))
        assertEquals("best-picture", TraktLists.listPath("https://trakt.tv/users/someone/lists/best-picture?sort=rank"))
        assertEquals("4325279", TraktLists.listPath("https://trakt.tv/lists/4325279"))
        assertEquals("777", TraktLists.listPath("https://app.trakt.tv/lists?id=777"))
        assertEquals("my-list", TraktLists.listPath("my-list"))
        assertNull(TraktLists.listPath("https://example.com/a b"))
        assertEquals("user/slug", mdblistPublicListId("https://mdblist.com/lists/user/slug/"))
        assertEquals("4242", mdblistPublicListId("4242"))
        assertNull(mdblistPublicListId("https://mdblist.com/lists/user/../x"))
    }
}
