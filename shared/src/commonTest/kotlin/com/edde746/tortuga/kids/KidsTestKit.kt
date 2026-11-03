package com.edde746.tortuga.kids

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.FakeAppPlatform
import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.calendar.episodesAnswer
import com.edde746.tortuga.presentation.profiles.ParentPinStore
import com.edde746.tortuga.trackers.ids.FixtureMappings
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** A title of the kids tests, with its suggested age in the age database (null when the database hasn't it). */
internal data class RatedTitle(val id: String, val type: String, val name: String, val age: Int?) {
    fun snapshot() = MetaSnapshot(id, type, name, "https://img.example/$id.jpg", "poster")

    /** Playing it from a stream that doesn't resolve. */
    fun playRequest() = PlayRequest(MetaItem(MetaItemPreview(id = id, type = type, name = name)), id, choice(urlStream("https://cdn.example/$id.mkv")))
}

internal val TOY_STORY = RatedTitle("tt0114709", "movie", "Toy Story", 0)
internal val BLUEY = RatedTitle("tt7678620", "series", "Bluey", 0)
internal val GHOSTBUSTERS = RatedTitle("tt0087332", "movie", "Ghostbusters", 6)
internal val DEADPOOL = RatedTitle("tt1431045", "movie", "Deadpool", 16)

/** A series of Cinemeta's Family genre suggested for 12. */
internal val SWITCHED_AT_BIRTH = RatedTitle("tt1758772", "series", "Switched at Birth", 12)
internal val BREAKING_BAD = RatedTitle("tt0903747", "series", "Breaking Bad", 16)

/** Films the age database hasn't. */
internal val TWELVE_ANGRY_MEN = RatedTitle("tt0050083", "movie", "12 Angry Men", null)
internal val NO_CERTIFICATION = RatedTitle("tt9000001", "movie", "Unrated Film", null)

/** The titles the tests' addon serves and the age database knows. */
internal val KIDS_TITLES = listOf(TOY_STORY, BLUEY, GHOSTBUSTERS, DEADPOOL, SWITCHED_AT_BIRTH, BREAKING_BAD, TWELVE_ANGRY_MEN, NO_CERTIFICATION)

/**
 * The age database as the kids tests have it: [ages] by table and id, each title [add]ed by its IMDb id; while
 * [offline] it can't be had (not downloaded yet, no network) and every lookup fails. [reads] counts the reads that
 * reached it, one per title looked up; [prefetches] the downloads asked for ahead of a lookup.
 */
internal class FakeAges(titles: Iterable<RatedTitle> = KIDS_TITLES) : AgeDatabase {
    val ages = mutableMapOf<Pair<AgeIdTable, Long>, Int>()
    var offline = false
    var reads = 0
        private set
    var prefetches = 0
        private set

    init {
        add(titles)
    }

    fun add(titles: Iterable<RatedTitle>) = titles.forEach { title ->
        title.age?.let { ages[AgeIdTable.IMDB to title.id.removePrefix("tt").toLong()] = it }
    }

    override suspend fun <R : Any> read(block: (AgeLookup) -> R): R? {
        if (offline) return null
        reads++
        return block { table, id -> ages[table to id] }
    }

    override fun prefetch() {
        prefetches++
    }
}

internal const val KIDS_ADDON_URL = "https://kids.example.com/manifest.json"

private const val KIDS_MANIFEST = """{"id":"org.example.kids","version":"1.0.0","name":"Kids","types":["movie","series"],
    "idPrefixes":["tt"],"resources":["catalog","meta","stream"],"catalogs":[
    {"type":"movie","id":"family","name":"Family","extra":[{"name":"skip"}]},
    {"type":"movie","id":"classics","name":"Classics","extra":[{"name":"skip"}]},
    {"type":"movie","id":"grown","name":"Grown-ups","extra":[{"name":"skip"}]},
    {"type":"movie","id":"cartoons","name":"Cartoons","extra":[{"name":"skip"}]},
    {"type":"series","id":"preset","name":"Preset"},
    {"type":"series","id":"calendar-videos","extra":[{"name":"calendarVideosIds","isRequired":true,"optionsLimit":100}]},
    {"type":"movie","id":"similar","name":"More Like This","extra":[{"name":"similar","isRequired":true}]},
    {"type":"movie","id":"search","name":"Search","extra":[{"name":"search","isRequired":true},{"name":"skip"}]}]}"""

/**
 * An app with Mia ([kids]), a kids profile with a Kids cap and no PIN, and [parent], an adult
 * profile with the PIN 1234. Both have one addon, [KIDS_ADDON_URL], whose catalogs answer [catalog]'s pages (its
 * calendar [episodes]) and whose metas and streams are those of [titles] (with the rating it [sent] for each); their
 * suggested ages come from [ages].
 */
internal class KidsWorld(private val scope: TestScope, val platform: FakeAppPlatform) {
    val titles: MutableMap<String, RatedTitle> = KIDS_TITLES.associateByTo(mutableMapOf()) { it.id }
    val ages = FakeAges()
    private val pages = mutableMapOf<String, List<RatedTitle>>()

    /** The age rating the addon sends with a title, by id. */
    val sent = mutableMapOf<String, String>()

    /** The release dates of each series' episodes, by id, as the addon's calendar catalog dates them. */
    val episodes = mutableMapOf<String, List<String>>()

    val addon = FakeHttp(scope) { request -> serve(request) }

    /** The addon's requests' paths, in order (`/catalog/movie/family/skip=10.json`). */
    val addonPaths: List<String> get() = addon.requests.map { it.url.encodedPath }

    val graph: AppGraph = scope.testGraph(http = addon.client)
    lateinit var kids: Profile
    lateinit var parent: Profile

    /** The page of the catalog at [path] (`movie/family`, `movie/family/skip=10`); a page not set is empty. */
    fun catalog(path: String, vararg titles: RatedTitle) {
        pages[path] = titles.toList()
        titles.forEach { this.titles[it.id] = it }
        ages.add(titles.asList())
    }

    suspend fun start(homeCatalogs: List<HomeCatalog>) {
        graph.ageRatings = AgeRatings(ages, FixtureMappings())
        parent = graph.data.profiles.create("Parent", null)
        graph.data.profiles.setPin(parent.id, "1234")
        kids = graph.data.profiles.create("Mia", null, kids = true, homeCatalogs = homeCatalogs)
        graph.data.settings.set(kids.id, ProfileSetting.KidsCap, AgeStep.KIDS)
        for (profile in listOf(parent, kids)) graph.data.addons.install(profile.id, KIDS_ADDON_URL, KIDS_MANIFEST)
    }

    /** [profile]'s app session, started. */
    fun open(profile: Profile = kids): AppSession =
        AppSession(graph, profile.id, platform, start = null, profileAccount = null).also { scope.runCurrent() }

    private fun MockRequestHandleScope.serve(request: HttpRequestData): HttpResponseData {
        if (request.url.host != "kids.example.com") return respond("", HttpStatusCode.NotFound)
        val path = request.url.encodedPath.removePrefix("/").removeSuffix(".json")
        val (resource, rest) = path.split('/', limit = 2)
        val id = rest.substringAfter('/')
        return when (resource) {
            "catalog" ->
                if (rest.startsWith("series/calendar-videos/")) episodesAnswer(request, episodes)
                else json("""{"metas":[${pages[rest].orEmpty().joinToString(",") { it.item() }}]}""")
            "meta" -> titles[id]?.let { json("""{"meta":${it.item()}}""") } ?: respond("", HttpStatusCode.NotFound)
            "stream" -> json("""{"streams":[{"url":"https://cdn.example/$id.mkv"}]}""")
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun RatedTitle.item(): String {
        val certification = sent[id]?.let { ""","certification":"$it"""" }.orEmpty()
        return """{"id":"$id","type":"$type","name":"$name","poster":"https://img.example/$id.jpg"$certification}"""
    }
}

/** A [KidsWorld] with the kids profile's Home rows preset ([homeCatalogs]). */
internal suspend fun TestScope.kidsWorld(
    homeCatalogs: List<HomeCatalog> = emptyList(),
    platform: FakeAppPlatform = FakeAppPlatform(),
): KidsWorld = KidsWorld(this, platform).apply { start(homeCatalogs) }

internal fun ParentPinStore.type(pin: String) = pin.forEach { digit(it.digitToInt()) }
