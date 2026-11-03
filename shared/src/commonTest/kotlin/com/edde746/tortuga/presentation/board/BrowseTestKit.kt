package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.discover.DiscoverUiState
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.presentation.search.SearchGroup
import com.edde746.tortuga.presentation.search.SearchUiState
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

// Shared by the Board, Discover and Search store tests.

/** The rows of [BoardBody.Rows]; none while something else shows. */
internal val BoardUiState.rows: List<BoardRow>
    get() = (body as? BoardBody.Rows)?.rows.orEmpty()

/** The cards of [DiscoverBody.Grid]; none while something else shows. */
internal val DiscoverUiState.items: List<MetaCard>
    get() = (body as? DiscoverBody.Grid)?.items.orEmpty()

/** The groups of [SearchBody.Results]; none while something else shows. */
internal val SearchUiState.groups: List<SearchGroup>
    get() = (body as? SearchBody.Results)?.groups.orEmpty()

/** Cinemeta as recorded in the fixture corpus. */
internal val CINEMETA: Descriptor =
    Descriptor(Manifest.decode(FixtureCorpus.text("live/cinemeta/manifest.json")), DefaultAddons.CINEMETA_URL.transportUrl())

/** A catalog-only addon at `https://<name>.example.com/manifest.json` with [catalogs] (the manifest's JSON array). */
internal fun catalogAddon(name: String, catalogs: String): Descriptor = Descriptor(
    Manifest.decode(
        """{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["movie","series"],"resources":["catalog"],"catalogs":$catalogs}""",
    ),
    "https://$name.example.com/manifest.json".transportUrl(),
)

/** `movie/top/skip=49&genre=Drama` for a request of `…/catalog/movie/top/skip=49&genre=Drama.json`. */
internal val HttpRequestData.catalogPath: String
    get() = url.encodedPath.substringAfter("/catalog/").removeSuffix(".json")

/** `v3-cinemeta.strem.io movie/top` per catalog request, in the order they were made. */
internal val FakeHttp.catalogRequests: List<String>
    get() = requests.map { "${it.url.host} ${it.catalogPath}" }

internal fun cinemeta(file: String): String = FixtureCorpus.text("live/cinemeta/$file")

/** Local data on the test scheduler with a clock the test moves; an HTTP client answering with [handler]. */
internal class BrowseEnv(
    scope: TestScope,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val clock = TestClock()
    val data = LocalData(
        inMemoryDatabase(), { clock.now },
        StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )
    val http = FakeHttp(scope, handler = handler)
    val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))
}

/**
 * A connected tracker's read side: [catalogs] as its lists and [pages] as each list's pages (by catalog id, then
 * cursor: null for the first). [failLists] makes the next `catalogs()` calls throw it; [failPages] a list's pages.
 * [listDelay] holds `catalogs()` until the test lets it through.
 */
internal class FakeCatalogSource(
    override val service: TrackerService,
    var catalogs: List<TrackerCatalog>,
    val pages: Map<String, Map<String?, TrackerCatalogPage>>,
) : TrackerCatalogSource {
    val calls = mutableListOf<String>()
    var failLists: Throwable? = null
    val failPages = mutableMapOf<String, Throwable>()
    var listDelay: CompletableDeferred<Unit>? = null

    override suspend fun catalogs(): List<TrackerCatalog> {
        calls += "lists"
        listDelay?.await()
        failLists?.let { throw it }
        return catalogs
    }

    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        calls += "page ${catalog.id}${cursor?.let { "@$it" }.orEmpty()}"
        failPages[catalog.id]?.let { throw it }
        return pages[catalog.id]?.get(cursor) ?: TrackerCatalogPage(emptyList(), next = null)
    }
}

/** A tracker list item as the services give them: an addon-resolvable id, a name and a poster. */
internal fun trackerItem(id: String, type: String, name: String) =
    MetaItemPreview(id = id, type = type, name = name, poster = "https://img.example/$id.jpg")
