package com.edde746.tortuga.trackers

import com.edde746.tortuga.trackers.anilist.AnilistProvider
import com.edde746.tortuga.trackers.mal.MalProvider
import com.edde746.tortuga.trackers.mdblist.MdblistProvider
import com.edde746.tortuga.trackers.simkl.SimklProvider
import com.edde746.tortuga.trackers.trakt.TraktProvider
import io.ktor.client.HttpClient

/**
 * The services this build can connect, in [TrackerService] order: each one whose registration [config] carries.
 * Trakt, Simkl and MDBList need their client ids, MyAnimeList its client id (for refreshes) and the relay, AniList the
 * relay. A service left out is reported as unavailable, never half-offered.
 *
 * Providers whose service has a read side also implement [com.edde746.tortuga.trackers.catalog.TrackerCatalogProvider]
 * (all five do).
 */
fun trackerProviders(http: HttpClient, config: TrackerConfig): List<TrackerProvider> = TrackerService.entries.mapNotNull { service ->
    val available = when (service) {
        TrackerService.TRAKT -> config.traktClientId.isNotBlank()
        TrackerService.SIMKL -> config.simklClientId.isNotBlank()
        TrackerService.MDBLIST -> config.mdblistClientId.isNotBlank()
        TrackerService.MYANIMELIST -> config.malClientId.isNotBlank() && config.relayBaseUrl.isNotBlank()
        TrackerService.ANILIST -> config.relayBaseUrl.isNotBlank()
    }
    if (available) trackerProvider(http, service, config.clientId(service), MAX_APP_ANSWER_BYTES) else null
}

/**
 * [service]'s provider as the client [clientId] (ignored for AniList, which has none): what the account server
 * refreshes and revokes a stored connection's tokens with. An answer whose body is longer than
 * [maxBodyBytes] fails as [TrackerException.Network].
 */
fun trackerProvider(http: HttpClient, service: TrackerService, clientId: String, maxBodyBytes: Long): TrackerProvider = when (service) {
    TrackerService.TRAKT -> TraktProvider(http, clientId, maxBodyBytes)
    TrackerService.SIMKL -> SimklProvider(http, clientId, maxBodyBytes)
    TrackerService.MDBLIST -> MdblistProvider(http, clientId, maxBodyBytes)
    TrackerService.MYANIMELIST -> MalProvider(http, clientId, maxBodyBytes)
    TrackerService.ANILIST -> AnilistProvider(http, maxBodyBytes)
}

/** The client ID [service]'s grants are issued to in this build: what a sign-in hands the account server. */
fun TrackerConfig.clientId(service: TrackerService): String = when (service) {
    TrackerService.TRAKT -> traktClientId
    TrackerService.SIMKL -> simklClientId
    TrackerService.MDBLIST -> mdblistClientId
    TrackerService.MYANIMELIST -> malClientId
    TrackerService.ANILIST -> ""
}

/** The apps' cap on one answer: 32 MiB, far above the longest list a service sends whole (Simkl's plan-to-watch). */
const val MAX_APP_ANSWER_BYTES: Long = 32L * 1024 * 1024
