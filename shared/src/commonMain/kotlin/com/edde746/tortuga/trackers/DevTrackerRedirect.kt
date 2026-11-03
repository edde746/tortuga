package com.edde746.tortuga.trackers

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** The hosts a fake tracker server stands in for (the services' APIs and the age database's), each to its path prefix there. */
private val DEV_TRACKER_HOSTS = mapOf(
    "api.trakt.tv" to "",
    "api.mdblist.com" to "/mdblist",
    "api.simkl.com" to "/simkl",
    "api.myanimelist.net" to "/mal",
    // MyAnimeList's authorization and token endpoints: /mal/v1/oauth2/..., apart from the API's /mal/v2.
    "myanimelist.net" to "/mal",
    "graphql.anilist.co" to "/anilist",
    // AniList's authorization and token endpoints: /anilist/api/v2/oauth/..., apart from the API's POST /anilist.
    "anilist.co" to "/anilist",
    // Kids profiles' age database: agerating's releases, /github/edde746/agerating/releases/....
    "github.com" to "/github",
)

/**
 * Sends the tracker services' API calls and the kids profiles' age database download to the fake tracker server at
 * [baseUrl] (`tools/media/test_trackers.py`) instead of their hosts, the same paths under each host's prefix there
 * ([DEV_TRACKER_HOSTS]); other requests pass. A development session's trackers and age database (the dev module's
 * `DevTrackers`) and a development server's refreshes and revokes (the server's `TORTUGA_DEV_TRACKERS_URL`) go
 * through it.
 */
fun devTrackerRedirect(baseUrl: String): ClientPlugin<Unit> {
    val server = Url(baseUrl)
    return createClientPlugin("DevTrackerServer") {
        onRequest { request, _ -> request.url.toDevTracker(server) }
    }
}

/**
 * The tracker service's [url] at the fake tracker server at [baseUrl], as [devTrackerRedirect] sends it there: a
 * development server's sign-in relay at the fake (the server's `TORTUGA_DEV_TRACKER_OAUTH_URL` and
 * `TORTUGA_DEV_TRACKER_OAUTH_BROWSER_URL`).
 *
 * @throws IllegalArgumentException when [url]'s host is no tracker service's.
 */
@Throws(IllegalArgumentException::class)
fun devTrackerUrl(baseUrl: String, url: String): String {
    val builder = URLBuilder(url)
    require(builder.toDevTracker(Url(baseUrl))) { "$url is no tracker service's" }
    return builder.buildString()
}

/**
 * Checks that the fake tracker server at [baseUrl] agrees with its client, from what it answers at `GET /_config`:
 * its account server routes under [accountPrefix], [user] as its user, [accessToken] taken as signed in (its token
 * prefix starts it) and every service under [devTrackerRedirect]'s prefix. The two sides are written apart; this is
 * where a change to one alone shows.
 *
 * @throws IllegalStateException naming each disagreement, or when the server doesn't answer the check.
 */
@Throws(IllegalStateException::class, CancellationException::class)
suspend fun checkDevTrackerServer(http: HttpClient, baseUrl: String, accountPrefix: String, user: String, accessToken: String) {
    val url = "${baseUrl.trimEnd('/')}/_config"
    val response = http.get(url)
    check(response.status.isSuccess()) { "the fake tracker server answered ${response.status} at $url: is it test_trackers.py, and current?" }
    val config = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    fun text(key: String): String =
        checkNotNull((config[key] as? JsonPrimitive)?.content) { "the fake tracker server's $url has no $key" }
    val prefixes = config["prefixes"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
    val wanted = DEV_TRACKER_HOSTS.values.toSet()
    val problems = buildList {
        if (text("account") != accountPrefix) add("its account routes are under '${text("account")}', the session's under '$accountPrefix'")
        if (text("user") != user) add("its user is '${text("user")}', the session's '$user'")
        if (!accessToken.startsWith(text("tokenPrefix"))) add("it takes tokens starting '${text("tokenPrefix")}', the session's is '$accessToken'")
        if (prefixes != wanted) add("its service prefixes are $prefixes, the redirect's $wanted")
    }
    check(problems.isEmpty()) { "the fake tracker server at $baseUrl disagrees with the development session: ${problems.joinToString("; ")}" }
}

/** Points this URL at the fake tracker [server], under its service's prefix there; false when its host is no service's. */
private fun URLBuilder.toDevTracker(server: Url): Boolean {
    val prefix = DEV_TRACKER_HOSTS[host] ?: return false
    protocol = server.protocol
    host = server.host
    port = server.port
    encodedPath = server.encodedPath.trimEnd('/') + prefix + encodedPath
    return true
}
