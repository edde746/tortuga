package com.edde746.tortuga.trackers

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.kids.AgeRatingDataset
import com.edde746.tortuga.kids.AgeRatings
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.sync.account.ServerSession
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.trackers.ids.AnimeEntryTitles
import com.edde746.tortuga.trackers.ids.AnimeMappings
import com.edde746.tortuga.trackers.ids.StremioTrackerIdResolver
import com.edde746.tortuga.trackers.relay.OAuthRelayClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Sets up the trackers this build has registrations for ([config]; the bootstraps pass `BuildTrackerConfig`), with
 * the anime mappings kept in [cacheDirectory] (the platform's cache directory), and the connections kept by
 * [account]'s server; and kids profiles' age database ([AgeRatingDataset], kept there too). Both datasets' disk copies
 * are brought up to date each time the app comes to the foreground ([startDatasets]); each loads into memory early only where it will be used:
 * the age database while a profile on this device is a kids profile, the anime mappings while an anime tracker is
 * connected on it. See the other overload.
 */
fun AppGraph.installTrackers(config: TrackerConfig, cacheDirectory: String, account: SyncAccount): TrackerHub =
    installTrackers(config, cacheDirectory, http, account.api, account.sessions)

/**
 * [installTrackers] with the services' API calls made through [serviceHttp] (a development session's fake server: the
 * dev module's `DevTrackers`) and the connections kept by the account server [server] reaches (a fake, or none).
 */
fun AppGraph.installTrackers(config: TrackerConfig, cacheDirectory: String, serviceHttp: HttpClient, server: ServerSession): TrackerHub =
    installTrackers(config, cacheDirectory, serviceHttp, SyncApi(http), server)

/**
 * [installTrackers] with the services' API calls made through [serviceHttp] and the connections kept by the account
 * server [api] reaches with [server]; the relay and the anime datasets use the graph's client. Kids profiles' age
 * database downloads through [serviceHttp] too, so a development session's fake serves it.
 */
internal fun AppGraph.installTrackers(
    config: TrackerConfig,
    cacheDirectory: String,
    serviceHttp: HttpClient,
    api: SyncApi,
    server: ServerSession,
): TrackerHub {
    check(trackers == null) { "trackers are already installed" }
    val mappings = AnimeMappings(http, Path(cacheDirectory, DATASETS_DIRECTORY).toString(), scope)
    val hub = TrackerHub(
        accountRepository = data.trackerAccounts,
        queue = data.trackerWriteQueue,
        settings = data.settings,
        tokens = TrackerTokenStore(secrets),
        server = server,
        api = api,
        providers = trackerProviders(serviceHttp, config),
        clientIds = TrackerService.entries.associateWith { config.clientId(it) },
        resolver = StremioTrackerIdResolver(mappings),
        animeTitles = AnimeEntryTitles(mappings.source),
        relay = config.relayBaseUrl.takeIf { it.isNotBlank() }?.let { OAuthRelayClient(http, it, device.name) },
        loadMeta = { profileId, type, id -> firstMeta(profileId, type, id) },
        clock = Clock.System,
        timeSource = TimeSource.Monotonic,
        scope = scope,
    )
    trackers = hub
    val ages = AgeRatingDataset(serviceHttp, Path(cacheDirectory, AGE_RATINGS_DIRECTORY), scope)
    val ratings = AgeRatings(ages, mappings.source)
    ageRatings = ratings
    scope.startDatasets(
        listOf(
            DatasetAtStart(ages::updateDiskCopy, data.profiles.observeProfiles().map { it.any(Profile::kids) }) { ratings.prefetch() },
            DatasetAtStart(
                mappings::updateDiskCopy,
                data.trackerAccounts.observeConnections().map { connections -> connections.any { (_, service) -> service in ANIME_SERVICES } },
                mappings::load,
            ),
        ),
        foreground,
        network,
    )
    return hub
}

/** The title's meta from the profile's addons, as Detail picks it ([com.edde746.tortuga.addons.AddonAggregator.firstMeta]). */
internal suspend fun AppGraph.firstMeta(profileId: String, type: String, id: String): MetaItem? =
    withTimeoutOrNull(META_TIMEOUT) { aggregator.firstMeta(addonRegistry(profileId).load(), type, id) }

private val META_TIMEOUT = 30.seconds

/** The trackers that need the anime mappings: while one is connected on this device, they load at start. */
private val ANIME_SERVICES = setOf(TrackerService.MYANIMELIST, TrackerService.ANILIST)

/** The datasets' directory under the platform's cache directory. */
private const val DATASETS_DIRECTORY = "trackers"

/** Kids profiles' age database's directory under the platform's cache directory. */
private const val AGE_RATINGS_DIRECTORY = "kids"
