package com.edde746.tortuga.trackers

import kotlinx.serialization.Serializable

/**
 * One tracker account's tokens as the service issued them. A device's sign-in hands
 * them to the account server, which keeps them sealed and alone refreshes and revokes them; the
 * device keeps only the access token ([TrackerTokenStore]). Never in SQLite, sync records or logs, so [toString] leaves
 * the tokens out.
 *
 * @param expiresAt epoch seconds; `null` when the token doesn't expire.
 * @param createdAt epoch seconds.
 * @param username the account's name as the service shows it, for the settings row.
 */
@Serializable
data class TrackerSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    val username: String? = null,
    val createdAt: Long,
) {
    /** The access token expires within [REFRESH_WINDOW_SECONDS] of [nowEpochSeconds]: refresh before handing it out. */
    fun needsRefresh(nowEpochSeconds: Long): Boolean = expiresAt != null && nowEpochSeconds >= expiresAt - REFRESH_WINDOW_SECONDS

    override fun toString(): String =
        "TrackerSession(expiresAt=$expiresAt, username=$username, createdAt=$createdAt, refreshable=${refreshToken != null})"

    companion object {
        const val REFRESH_WINDOW_SECONDS: Long = 300
    }
}

/**
 * Exchanges a session's refresh token for a new session (the service's token endpoint): the account server's refresh
 * of a stored connection. Services whose tokens don't refresh (AniList) have none.
 */
fun interface TokenRefresher {
    /**
     * The refreshed session; it keeps [session]'s username. Refresh tokens may rotate (Trakt): the old one is spent
     * once this returns.
     *
     * @throws TrackerException.Auth with `permanent = true` when the service refused (Trakt: 400/401/403; a missing
     *   refresh token), `false` when the endpoint misbehaved (5xx); [TrackerException.Network] when unreachable.
     */
    suspend fun refresh(session: TrackerSession): TrackerSession
}

/**
 * The tokens of one connected account, for a tracker's requests. Implemented by the account store, which asks the
 * account server for access tokens and drops the connection when the server says it is gone.
 */
interface TrackerCredentials {
    val service: TrackerService

    /**
     * The access token for the next request: the one this device holds, or the account server's when that one expires
     * soon. When the server can't be asked, a held token that hasn't expired is returned, so the request's own 401
     * decides.
     *
     * @throws TrackerException.Auth (permanent) when the connection is gone; [TrackerException.AccountServer] when
     *   the device holds no usable token and the server can't be asked, or the secret store can't read the one held.
     */
    suspend fun accessToken(): String

    /**
     * A request sent with [rejected] got a 401: ask the account server once for every caller at a time (it refreshes
     * at the service), and return the new token (without asking when [rejected] was already replaced). Retry the
     * request once with it.
     *
     * @throws TrackerException.Auth permanent when the connection is gone (the service refused its refresh);
     *   [TrackerException.AccountServer] when the server can't be asked, the service's token endpoint failed, or the
     *   secret store can't keep the new token.
     */
    suspend fun refreshAfterUnauthorized(rejected: String): String

    /**
     * The service declared the session behind [rejected] dead (a 401 where tokens don't refresh, AniList): the account
     * server drops the connection, and so does this device.
     */
    suspend fun invalidate(rejected: String)
}
