package com.edde746.tortuga.app

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ServerSession
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.trackers.BuildTrackerConfig
import com.edde746.tortuga.trackers.TrackerConfig
import com.edde746.tortuga.trackers.TrackerHub
import com.edde746.tortuga.trackers.TrackerSession
import com.edde746.tortuga.trackers.checkDevTrackerServer
import com.edde746.tortuga.trackers.devTrackerRedirect
import com.edde746.tortuga.trackers.installTrackers
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * A development session's fake tracker server (`--dev-trackers <url>`, [DevLaunch]; `tools/media/test_trackers.py`).
 * Each service's API calls, and a kids profile's age database download (agerating's releases on GitHub), go to
 * [baseUrl] instead of the host, the same paths under a prefix ([DEV_TRACKER_HOSTS]: Trakt's at the root, `/mdblist`,
 * `/simkl`, `/mal`, `/anilist`, `/github`).
 * It is also the session's account server for tracker connections: the product's client calls its routes under
 * `/tortuga` (`<baseUrl>/tortuga/v2/trackers/...`), where the fake keeps the connections while it runs.
 *
 * - By default [AppBootstrap.dev] offers Trakt alone, with a test registration; everything else (the device-code flow,
 *   handing the connection to the account server, scrobbling, the catalog rows) runs as in the product.
 * - [connected] (`--dev-trackers-connected`): all five services are offered, and [startDevSession] connects each to
 *   the session's profile with a fake session, without signing in; Detail then shows every score the fake has for a
 *   title.
 * - [relay] (`--dev-relay <url>`, [withRelay]): MyAnimeList and AniList are offered beside Trakt, not connected,
 *   and sign in through that Tortuga server's OAuth relay as in the product (a development stack's, whose relay signs
 *   in at a fake too: server/README.md, Development); [startDevSession] gives the session this device's sign-in
 *   platform for the sign-in ([AppSession.signIn]), which comes back to the app with a proof, where the device has one.
 */
class DevTrackers private constructor(
    val baseUrl: String,
    val connected: Boolean,
    val relay: String? = null,
) {
    override fun toString(): String = "DevTrackers($baseUrl, connected=$connected, relay=$relay)"

    /**
     * These trackers with MyAnimeList and AniList signing in through the relay at [url].
     *
     * @throws IllegalArgumentException unless [url] is an `http` or `https` URL, or when these are [connected], with
     *   the message to show.
     */
    @Throws(IllegalArgumentException::class)
    fun withRelay(url: String): DevTrackers {
        require(httpUrl(url)) { "needs the relay's http(s) URL, got '$url'" }
        require(!connected) { "a relay signs in, and connected trackers are connected without signing in: pass one" }
        return DevTrackers(baseUrl, connected = false, url.trimEnd('/'))
    }

    companion object {
        /** @throws IllegalArgumentException unless [url] is an `http` or `https` URL, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(url: String, connected: Boolean): DevTrackers {
            require(httpUrl(url)) { "needs the fake tracker server's http(s) URL, got '$url'" }
            return DevTrackers(url.trimEnd('/'), connected)
        }

        private fun httpUrl(url: String): Boolean {
            val parsed = runCatching { Url(url) }.getOrNull()
            return parsed != null && parsed.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS) && parsed.host.isNotEmpty()
        }
    }
}

/** A development session's fake registrations: nothing real, the fake server takes any. */
private const val DEV_CLIENT = "tortuga-dev"

/**
 * A development session's shared core: [AppBootstrap.local] (an in-memory database or [database], secrets in memory,
 * no account and no sync), its trackers against [trackers]' fake server, or without one this build's registrations
 * with no account, so nothing connects. [startDevSession] adds its local profile.
 *
 * @param trackers a fake tracker server to connect the trackers to (`--dev-trackers`, [DevLaunch.trackers]) instead of
 *   this build's registrations; null for the build's.
 */
fun AppBootstrap.Companion.dev(
    database: DatabaseFactory,
    flavor: BuildFlavor,
    appVersion: String,
    device: DeviceDescriptor,
    cacheDirectory: String,
    trackers: DevTrackers?,
): AppBootstrap = AppBootstrap.local(database, flavor, appVersion, device) { graph ->
    if (trackers != null) graph.installDevTrackers(trackers, cacheDirectory) else graph.installTrackers(BuildTrackerConfig.config, cacheDirectory, graph.http, NoAccount)
}

/** A development session without a fake tracker server: no account, so its trackers can't connect. */
private object NoAccount : ServerSession {
    override val signedIn: Boolean get() = false

    override suspend fun <T> authorized(call: suspend (serverUrl: String, accessToken: String) -> T): T =
        throw AccountException(AccountError.NotSignedIn)
}

/**
 * Installs the trackers against [trackers]' fake server ([DevTrackers]): Trakt, with MyAnimeList and AniList on a
 * relay, or every service when connected, the services' calls sent there by [devTrackerRedirect].
 */
private fun AppGraph.installDevTrackers(trackers: DevTrackers, cacheDirectory: String): TrackerHub {
    val config = when {
        // The relay is never reached: the services connect without signing in.
        trackers.connected -> TrackerConfig(DEV_CLIENT, DEV_CLIENT, DEV_CLIENT, DEV_CLIENT, relayBaseUrl = "${trackers.baseUrl}/relay")
        trackers.relay != null -> TrackerConfig(traktClientId = DEV_CLIENT, malClientId = DEV_CLIENT, relayBaseUrl = trackers.relay)
        else -> TrackerConfig(traktClientId = DEV_CLIENT)
    }
    val serviceHttp = http.config { install(devTrackerRedirect(trackers.baseUrl)) }
    return installTrackers(config, cacheDirectory, serviceHttp, DevServerSession("${trackers.baseUrl}$DEV_ACCOUNT_PREFIX"))
}

/** Where the fake tracker server answers the account server's tracker connection routes. */
internal const val DEV_ACCOUNT_PREFIX = "/tortuga"

/** The fake tracker server as a development session's account server: signed in, with a token the fake takes. */
private class DevServerSession(private val serverUrl: String) : ServerSession {
    override val signedIn: Boolean get() = true

    override suspend fun <T> authorized(call: suspend (serverUrl: String, accessToken: String) -> T): T = call(serverUrl, "dev")
}

/**
 * Connects every service this graph's trackers offer to [profileId] with a fake session ([DevTrackers.connected]), at
 * every start: the fake keeps connections only while it runs, so a database kept from an earlier session (`--dev-data`,
 * or the desktop's `--data-dir`) gets each one again.
 */
internal suspend fun AppGraph.connectDevTrackers(profileId: String) {
    val accounts = trackers?.accounts ?: return
    for (service in accounts.services) {
        // The fake server takes any token starting so ([checkDevTrackerServer]); it never expires, so nothing refreshes it.
        accounts.connect(profileId, service, TrackerSession(accessToken = DEV_TRACKER_TOKEN, username = DEV_TRACKER_USER, createdAt = 0))
    }
}

/** The fake server's user (`test_trackers.py --user`'s default), checked against its config ([checkDevTrackerServer]). */
internal const val DEV_TRACKER_USER = "tortuga-tester"

/** The connected trackers' access token: one the fake server takes as signed in. */
internal const val DEV_TRACKER_TOKEN = "fake-access-dev"
