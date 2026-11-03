package com.edde746.tortuga.trackers

/**
 * One tracker service's implementation, as the account store and the coordinator use it. Each service
 * (`trackers/<service>/`) provides one; [TrackerConfig] decides which are available. The account server uses the same
 * providers' [refresher] and [revoke] for the connections it holds; the apps never call them.
 */
interface TrackerProvider {
    val service: TrackerService

    val signIn: TrackerSignIn

    /** `null` when the service's tokens don't refresh (AniList's last a year, then the user signs in again). */
    val refresher: TokenRefresher?

    /** The write side of the account behind [credentials]. A new instance per connection (see [Tracker]). */
    fun tracker(credentials: TrackerCredentials): Tracker

    /** Revokes [session] at the service on disconnect. Best effort: failures are logged, never thrown. */
    suspend fun revoke(session: TrackerSession)
}

/**
 * This build's client registrations. A blank value makes the services that need it unavailable, so a
 * build without registrations offers no tracker at all. Client ids ship in the binary, as for every native client of
 * these services; MyAnimeList's and AniList's secrets stay on the relay.
 *
 * @param relayBaseUrl the OAuth relay's base URL (MyAnimeList, AniList).
 */
data class TrackerConfig(
    val traktClientId: String = "",
    val simklClientId: String = "",
    val mdblistClientId: String = "",
    val malClientId: String = "",
    val relayBaseUrl: String = "",
) {
    override fun toString(): String = "TrackerConfig(relayBaseUrl=$relayBaseUrl)"
}
