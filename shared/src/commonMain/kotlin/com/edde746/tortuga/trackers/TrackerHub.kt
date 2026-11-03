package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.tracker.TrackerAccountRepository
import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.sync.account.ServerSession
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.trackers.ids.AnimeEntryTitles
import com.edde746.tortuga.trackers.ratings.TrackerRatings
import com.edde746.tortuga.trackers.trakt.TraktLists
import com.edde746.tortuga.trackers.trakt.TraktProvider
import com.edde746.tortuga.trackers.trakt.TraktRelated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The trackers of one app process: the [accounts] of every profile on this device and one
 * [TrackerCoordinator] per profile, which lives as long as the hub. Installed on the app graph by [installTrackers];
 * playback sessions and the Detail and Library stores reach their profile's coordinator through it, and Detail its
 * [ratings].
 *
 * The queued-write replays it triggers: a profile's coordinator starting, an account connecting, and the platform's
 * [onForeground] and [onNetworkRestored]. The account's connection list is listed only when it can have changed:
 * when the bootstrap asks ([refreshConnections]: the account restored or signed in in the foreground), on
 * [onForeground] when the last listing is older than [LISTING_MAX_AGE], after a connect or a disconnect here, when the
 * account server says a connection is gone, and when a pull reports another connections counter ([onTrackersVersion]).
 * Main-thread confined.
 */
class TrackerHub internal constructor(
    accountRepository: TrackerAccountRepository,
    private val queue: TrackerWriteQueue,
    private val settings: SettingsRepository,
    tokens: TrackerTokenStore,
    server: ServerSession,
    api: SyncApi,
    providers: List<TrackerProvider>,
    clientIds: Map<TrackerService, String>,
    internal val resolver: TrackerIdResolver,
    /** The titles the anime list entries of the MyAnimeList and AniList rows open as, for Detail. */
    internal val animeTitles: AnimeEntryTitles,
    relay: OAuthRelay?,
    private val loadMeta: suspend (profileId: String, type: String, id: String) -> MetaItem?,
    private val clock: Clock,
    private val timeSource: TimeSource.WithComparableMarks,
    private val scope: CoroutineScope,
) {
    private val coordinators = mutableMapOf<String, TrackerCoordinator>()

    /** Sign-in, connections and their toggles; connecting replays the profile's queued writes. */
    val accounts: TrackerAccounts = TrackerAccounts(
        repository = accountRepository,
        tokens = tokens,
        server = server,
        api = api,
        providers = providers,
        clientIds = clientIds,
        relay = relay,
        clock = clock,
        timeSource = timeSource,
        onConnected = { profileId -> coordinator(profileId).replayQueuedWrites() },
        relist = { refreshConnections() },
    )

    /** The connected trackers' scores for Detail's ratings row, kept per title for every profile of the process. */
    internal val ratings: TrackerRatings = TrackerRatings({ accounts.ratingsSources(it) }, resolver, timeSource)

    /** Trakt's related titles for Detail's "More like this" where this build has Trakt: no account needed. */
    internal val related: TraktRelated? = providers.firstNotNullOfOrNull { (it as? TraktProvider)?.related(timeSource) }

    /** Trakt's public lists for collections where this build has Trakt: no account needed. */
    internal val traktLists: TraktLists? = providers.firstNotNullOfOrNull { (it as? TraktProvider)?.lists(timeSource) }

    init {
        // Tokens never outlive their connection, whichever way it went (a deleted profile, sync dropping the data).
        scope.launch {
            accountRepository.observeConnections().collect {
                try {
                    accounts.clearUnusedTokens()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "clearing unused tracker sessions failed" }
                }
            }
        }
    }

    /** The profile's coordinator, created on first use (which replays what an earlier run left queued). */
    fun coordinator(profileId: String): TrackerCoordinator = coordinators.getOrPut(profileId) {
        TrackerCoordinator(
            profileId = profileId,
            accounts = accounts,
            resolver = resolver,
            queue = queue,
            settings = settings,
            loadMeta = { type, id -> loadMeta(profileId, type, id) },
            clock = clock,
            timeSource = timeSource,
            parentScope = scope,
        )
    }

    /**
     * The app came back to the foreground: list the account's connections unless that was done less than
     * [LISTING_MAX_AGE] ago, and replay queued writes.
     */
    fun onForeground() {
        scope.launch { accounts.syncConnectionsIfOlderThan(LISTING_MAX_AGE) }
        coordinators.values.forEach { it.replayQueuedWrites() }
    }

    /** The network came back: replay queued writes. */
    fun onNetworkRestored() = coordinators.values.forEach { it.replayQueuedWrites() }

    /** Lists the account's connections and makes this device's the same ([TrackerAccounts.syncConnections]). */
    fun refreshConnections() {
        scope.launch { accounts.syncConnections() }
    }

    /** A pull reported the account's tracker connections counter: list them when it moved ([TrackerAccounts.onTrackersVersion]). */
    fun onTrackersVersion(version: Long) {
        scope.launch { accounts.onTrackersVersion(version) }
    }

    internal fun marks(profileId: String): WatchedMarkListener = coordinator(profileId).marks

    private companion object {
        const val TAG = "trackers"

        /**
         * A safety net beside the counter pulls report: a return to the foreground lists the connections at most this
         * often (and a server without the counter shows another device's change by then).
         */
        val LISTING_MAX_AGE = 30.minutes
    }
}
