package com.edde746.tortuga.trackers.connections

import com.edde746.tortuga.trackers.TrackerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable

/**
 * Tracker connections held by the account server: a profile's connection to a tracker
 * service (Trakt, Simkl, MDBList, MyAnimeList, AniList) belongs to the Tortuga account and serves every device of that
 * account. The server keeps the service's tokens sealed at rest and is the only one that refreshes them, so two devices
 * never spend the same single-use refresh token (Trakt) or cut each other off (Simkl). These are the types both sides
 * use, and their KDoc is the specification.
 *
 * **Flow.**
 * 1. A device signs in to the service as before (device code, or the OAuth relay) and hands the result to
 *    [TrackerConnectionPaths.CONNECTIONS] as a [TrackerConnectRequest]: the tokens, the client ID the grant was issued
 *    to and the account's username there. It keeps no refresh token.
 * 2. Every device lists the account's connections ([TrackerConnectionList]: no tokens) and asks
 *    [TrackerConnectionPaths.TOKEN] for an access token when it has none that lasts ([TrackerTokenRequest]). The server
 *    refreshes a token that expires within [REFRESH_WINDOW_SECONDS], one refresh per connection at a time.
 * 3. Any device disconnects ([TrackerConnectionPaths.connection]): the server revokes at the service (best effort) and
 *    forgets the connection.
 *
 * **Who reaches what.** Every route needs a session of the account (`Authorization: Bearer`, as the sync routes) and
 * reaches only that account's connections, keyed by profile ID and service. Deleting the account, or a profile (its
 * synced record's tombstone), revokes and deletes its connections.
 *
 * **Refresh and revoke** use the client ID stored with the connection: Tortuga's tracker clients are public, with no
 * client secret, and AniList's tokens don't refresh, so a self-hosted server needs no tracker configuration.
 *
 * **Errors.** Non-2xx answers carry the server's `ApiError` (`sync/api`). [DISCONNECTED] is the token route's own.
 */
object TrackerConnectionsApi {
    /**
     * 410 `ApiError.code` from [TrackerConnectionPaths.TOKEN]: the connection is gone. It was disconnected, a newer
     * sign-in replaced it ([TrackerTokenRequest.connectedAtMillis] no longer matches), or the service refused it (a
     * refused refresh, or a [TrackerTokenRequest.rejected] token where tokens don't refresh) and the server deleted
     * it. The device drops the connection and lists the account's connections again.
     */
    const val DISCONNECTED: String = "tracker_disconnected"

    /** The server refreshes an access token that expires within this many seconds before handing it out. */
    const val REFRESH_WINDOW_SECONDS: Long = TrackerSession.REFRESH_WINDOW_SECONDS

    /** Limits of [TrackerConnectRequest]; anything longer is a 400. */
    const val MAX_PROFILE_ID_LENGTH: Int = 128
    const val MAX_TOKEN_LENGTH: Int = 4096
    const val MAX_CLIENT_ID_LENGTH: Int = 256
    const val MAX_USERNAME_LENGTH: Int = 256

    /**
     * Most connections an account holds: each of the five services on twenty profiles. Storing a new one past it is a
     * 413 `quota_exceeded`; replacing a profile's connection to a service always fits.
     */
    const val MAX_CONNECTIONS: Int = 100
}

/** Every tracker connection endpoint, relative to the server's base URL. All need a session of the account. */
object TrackerConnectionPaths {
    /**
     * `GET`: the account's connections, every profile's, oldest first: [TrackerConnectionList].
     *
     * `POST`: stores a sign-in, [TrackerConnectRequest] → [TrackerConnection]. It replaces the profile's connection
     * to that service, if any (a new [TrackerConnection.connectedAtMillis]; the replaced tokens aren't revoked). 400
     * for an unknown service, a field over its limit ([TrackerConnectionsApi]), a blank access token, or no refresh
     * token where the service's tokens refresh; 413 `quota_exceeded` for a new connection past
     * [TrackerConnectionsApi.MAX_CONNECTIONS].
     */
    const val CONNECTIONS: String = "/v2/trackers/connections"

    /**
     * `POST`: [TrackerTokenRequest] → [TrackerAccessToken], refreshed at the service first when it expires within
     * [TrackerConnectionsApi.REFRESH_WINDOW_SECONDS] or the device says the service [rejected][TrackerTokenRequest.rejected]
     * it. Concurrent requests for one connection share one refresh. 410 [TrackerConnectionsApi.DISCONNECTED] when the
     * connection is gone (a refused refresh deletes it); 502 `upstream_unavailable` when the service's token endpoint
     * failed or couldn't be reached (the stored tokens stay; ask again later).
     */
    const val TOKEN: String = "/v2/trackers/token"

    /**
     * `DELETE`: disconnects [service] (a `TrackerService` id) from [profileId] on every device: 204, also when there
     * was no such connection. The server deletes the connection, then revokes its token at the service (best effort,
     * as the services allow).
     */
    fun connection(profileId: String, service: String): String = "$CONNECTIONS/${profileId.encodeURLPathPart()}/${service.encodeURLPathPart()}"
}

/**
 * A finished sign-in, from the device that made it. [service] is a `TrackerService` id; [clientId] the client ID the
 * grant was issued to (empty for AniList, whose tokens neither refresh nor revoke); [expiresInSeconds] how long the
 * access token has left as the device sends it, `null` when it doesn't expire; [username] the account's name at the
 * service, for the settings row.
 */
@Serializable
class TrackerConnectRequest(
    val profileId: String,
    val service: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
    val username: String? = null,
) {
    override fun toString(): String = "TrackerConnectRequest(service=$service, expiresInSeconds=$expiresInSeconds)"
}

/**
 * One connection, without tokens. [connectedAtMillis] is when the server stored this sign-in (epoch milliseconds, the
 * server's clock); it names the sign-in, so a reconnect moves it.
 */
@Serializable
data class TrackerConnection(
    val profileId: String,
    val service: String,
    val username: String? = null,
    val connectedAtMillis: Long,
)

/**
 * The account's connections. [version]: the account's tracker connections counter as of this listing, the one a pull
 * reports as `PullResponse.trackersVersion`; `null` from a server older than the field.
 */
@Serializable
data class TrackerConnectionList(val connections: List<TrackerConnection>, val version: Long? = null)

/**
 * Asks for the access token of the sign-in named by [connectedAtMillis]. [rejected]: the token the service just
 * answered 401 to, which the server refreshes (once, for every device asking with it) unless it already replaced it;
 * where tokens don't refresh (AniList), the connection is then dead and deleted.
 */
@Serializable
class TrackerTokenRequest(
    val profileId: String,
    val service: String,
    val connectedAtMillis: Long,
    val rejected: String? = null,
) {
    override fun toString(): String = "TrackerTokenRequest(service=$service, connectedAtMillis=$connectedAtMillis, rejected=${rejected != null})"
}

/**
 * An access token and how long it has left by the server's clock ([expiresInSeconds]; `null` when it doesn't expire),
 * which the device counts from when the answer arrived.
 */
@Serializable
class TrackerAccessToken(val accessToken: String, val expiresInSeconds: Long? = null) {
    override fun toString(): String = "TrackerAccessToken(expiresInSeconds=$expiresInSeconds)"
}
