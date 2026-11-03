package com.edde746.tortuga.fixtures

import com.edde746.tortuga.addons.CatalogPaging
import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.log.Redactor
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.net.TortugaHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate

private const val DEFAULT_OUT = "shared/src/commonTest/fixtures/live"

private const val MOVIE = "tt0111161" // The Shawshank Redemption
private const val SERIES = "tt7366338" // Chernobyl (5 episodes)
private const val EPISODE = "$SERIES:1:1"

private class Recording(val file: String, val addon: String, val transportUrl: String, val path: ResourcePath?)

/**
 * Records the addon fixture corpus from the live public addons through the shared core: raw
 * response bodies, scrubbed of secrets before they touch the disk ([FixtureScrubber]), plus `index.json` describing
 * each request. Cinemeta (manifest, the movie Board catalog and its second page, movie and series search, one movie
 * and one series meta; the series Board catalog is left out: it embeds every episode list, over 500 KB),
 * and OpenSubtitles v3 (subtitles for the movie and one episode, without the release names of the files they were
 * made for: [FixtureScrubber.withoutReleaseNames]). Only these metadata and subtitle addons are recorded; the stream
 * lists in `fixtures/streams` are invented, not recorded.
 *
 * `./gradlew :dev:recordFixtures` runs it from the repository root and writes into [DEFAULT_OUT];
 * `--args=<dir>` writes into another folder.
 */
fun main(args: Array<String>) = runBlocking<Unit> {
    require(args.size <= 1) { "usage: ./gradlew :dev:recordFixtures [--args=<out dir>]" }
    // Sent as `User-Agent: Tortuga/<version>` (TortugaHttpClient) and written into index.json; the Gradle task sets it.
    val version = checkNotNull(System.getProperty("tortuga.version")) { "tortuga.version is not set: run ./gradlew :dev:recordFixtures" }
    val out = File(args.firstOrNull() ?: DEFAULT_OUT)
    out.mkdirs()
    val cinemeta = DefaultAddons.CINEMETA_URL
    val subtitles = DefaultAddons.OPENSUBTITLES_URL
    fun catalog(type: String, vararg extra: ExtraValue) = ResourcePath(Resources.CATALOG, type, "top", extra.toList())

    val recordings = mutableListOf(
        Recording("cinemeta/manifest.json", "Cinemeta", cinemeta, null),
        Recording("cinemeta/catalog-movie-top.json", "Cinemeta", cinemeta, catalog("movie")),
        Recording("cinemeta/search-movie-shawshank.json", "Cinemeta", cinemeta, catalog("movie", ExtraValue(Extras.SEARCH, "shawshank"))),
        Recording("cinemeta/search-series-chernobyl.json", "Cinemeta", cinemeta, catalog("series", ExtraValue(Extras.SEARCH, "chernobyl"))),
        Recording("cinemeta/meta-movie-$MOVIE.json", "Cinemeta", cinemeta, ResourcePath(Resources.META, "movie", MOVIE)),
        Recording("cinemeta/meta-series-$SERIES.json", "Cinemeta", cinemeta, ResourcePath(Resources.META, "series", SERIES)),
        Recording("opensubtitles/manifest.json", "OpenSubtitles v3", subtitles, null),
        Recording("opensubtitles/subtitles-movie-$MOVIE.json", "OpenSubtitles v3", subtitles, ResourcePath(Resources.SUBTITLES, "movie", MOVIE)),
        Recording(
            "opensubtitles/subtitles-series-${EPISODE.replace(':', '-')}.json", "OpenSubtitles v3", subtitles,
            ResourcePath(Resources.SUBTITLES, "series", EPISODE),
        ),
    )

    val index = mutableListOf<JsonElement>()
    TortugaHttpClient.create(version).use { http ->
        var i = 0
        while (i < recordings.size) {
            val r = recordings[i++]
            val url = r.path?.let { TransportUrl.resourceUrl(checkNotNull(TransportUrl.checked(r.transportUrl)), it) } ?: r.transportUrl
            val response = http.get(url)
            val raw = response.bodyAsText()
            check(response.status.isSuccess()) { "${r.file}: HTTP ${response.status.value}" }
            val scrubbed = FixtureScrubber.scrub(FixtureScrubber.withoutReleaseNames(raw), r.transportUrl)
            File(out, r.file).apply { parentFile.mkdirs() }.writeText(scrubbed.text)
            index += buildJsonObject {
                put("file", r.file)
                put("addon", r.addon)
                put("transportUrl", FixtureScrubber.scrubTransportUrl(r.transportUrl))
                if (r.path != null) {
                    put("resource", r.path.resource)
                    put("type", r.path.type)
                    put("id", r.path.id)
                    put("extra", JsonArray(r.path.extra.map { buildJsonObject { put("name", it.name); put("value", it.value) } }))
                }
            }
            println("recorded ${r.file} (${scrubbed.text.length} chars" +
                (if (scrubbed.replacements > 0) ", ${scrubbed.replacements} secrets scrubbed" else "") + ")")

            // The second page of the movie Board catalog: `skip` as CatalogPaging computes it from the first page.
            if (r.file == "cinemeta/catalog-movie-top.json") {
                val manifest = Manifest.decode(File(out, "cinemeta/manifest.json").readText())
                val top = manifest.catalogs.first { it.id == "top" && it.type == "movie" }
                val size = (AddonResponse.decode(raw).content as ResourceContent.Metas).items.size
                val next = CatalogPaging.nextPage(top, ResourceRequest(checkNotNull(TransportUrl.checked(cinemeta)), r.path!!), listOf(size))!!
                recordings.add(i, Recording("cinemeta/catalog-movie-top-page2.json", "Cinemeta", cinemeta, next.path))
            }
        }
    }
    val indexJson = buildJsonObject {
        put("recorded", LocalDate.now().toString())
        put("recordedBy", ":dev:recordFixtures $version")
        put("fixtures", JsonArray(index))
    }
    File(out, "index.json").writeText(Json(from = AddonJson) { prettyPrint = true }.encodeToString(JsonObject.serializer(), indexJson) + "\n")
    val leaks = out.walkTopDown().filter { it.isFile }.flatMap { file -> Redactor.findKeys(file.readText()).map { file to it } }.toList()
    check(leaks.isEmpty()) { leaks.joinToString("\n") { (file, key) -> "key-shaped text left in ${file.path}: ${Redactor.redact(key)}" } }
    println("wrote ${recordings.size} fixtures and index.json to ${out.path}")
}

/**
 * Removes secrets from a recorded body before it is written:
 * - the configuration part of the addon's transport URL (between host and `/manifest.json`, and the query) wherever
 *   it appears;
 * - any key shape [Redactor.findKeys] knows.
 * A body that needs no change is kept byte for byte.
 */
object FixtureScrubber {
    class Result(val text: String, val replacements: Int)

    private const val PLACEHOLDER = "REDACTED"

    fun scrubTransportUrl(transportUrl: String): String =
        secretParts(transportUrl).fold(transportUrl) { url, (part, replacement) -> url.replace(part, replacement) }

    fun scrub(body: String, transportUrl: String): Result {
        var count = 0
        var text = body
        for ((part, replacement) in secretParts(transportUrl)) {
            count += text.split(part).size - 1
            text = text.replace(part, replacement)
        }
        for (key in Redactor.findKeys(text).distinct()) {
            count += text.split(key).size - 1
            text = text.replace(key, PLACEHOLDER)
        }
        return Result(text, count)
    }

    /** A subtitle's fields that name the release it was made for: file and release names, group, format. */
    private val RELEASE_KEYS = setOf("subtitleFileName", "movieReleaseName", "releaseGroup", "releaseFormat", "releaseOther")

    /** [body] without its subtitles' [RELEASE_KEYS]; a body without subtitles is kept byte for byte. */
    fun withoutReleaseNames(body: String): String {
        val element = runCatching { AddonJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return body
        val subtitles = element["subtitles"] as? JsonArray ?: return body
        val kept = JsonArray(subtitles.map { s -> (s as? JsonObject)?.let { o -> JsonObject(o - RELEASE_KEYS) } ?: s })
        return AddonJson.encodeToString(JsonObject.serializer(), JsonObject(element + ("subtitles" to kept)))
    }

    /**
     * The configuration parts of a transport URL with their replacements: the path before `/manifest.json`
     * (`/apikey=…|sort=size` in `https://host/apikey=…|sort=size/manifest.json`) and the query.
     */
    private fun secretParts(transportUrl: String): List<Pair<String, String>> {
        val afterScheme = transportUrl.substringAfter("://")
        val start = afterScheme.indexOfAny(charArrayOf('/', '?'))
        if (start < 0) return emptyList()
        val path = afterScheme.substring(start).substringBefore('?')
        val query = afterScheme.substring(start).substringAfter('?', "")
        val config = path.removeSuffix(TransportUrl.MANIFEST_PATH)
        return listOfNotNull(
            config.takeIf { it.isNotEmpty() }?.let { it to "/$PLACEHOLDER" },
            query.takeIf { it.isNotEmpty() }?.let { "?$it" to "?$PLACEHOLDER" },
        )
    }
}
