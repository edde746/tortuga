package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.settings.TrackerSwitch
import com.edde746.tortuga.data.settings.TrackerSwitches
import com.edde746.tortuga.data.tracker.TrackerAccount
import com.edde746.tortuga.data.tracker.TrackerAccountRepository
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ServerSession
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.account.signin.OAuth
import com.edde746.tortuga.sync.account.signin.RedirectReceiver
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.trackers.catalog.ListedCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerCatalogProvider
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistMembership
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import com.edde746.tortuga.trackers.ratings.TrackerRatingsProvider
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import com.edde746.tortuga.trackers.relay.OAuthRelayApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the user acts on while an account connects: a code to enter (or scan), or a link to open (or scan). */
sealed interface SignInPrompt {
    /** Enter [grant]'s user code at its verification URL, or scan its complete URL. */
    data class DeviceCode(val grant: DeviceCodeGrant) : SignInPrompt

    /** Open (or scan) [start]'s URL and authorize there. */
    data class Link(val start: RelayStart) : SignInPrompt
}

/**
 * A profile's tracker accounts (connections belong to the Tortuga account, per profile, and serve every device of
 * it): sign-in, the connection list, each service's switches (sending at all, content filters: the profile's synced
 * settings, [TrackerSwitches]), and the tokens behind each connection.
 *
 * The account server ([server], through [api]'s tracker connection routes) holds each connection's tokens and alone
 * refreshes them. This device keeps the account's connection list ([TrackerAccountRepository]), which
 * [syncConnections] brings up to date, and each connection's current access token ([TrackerTokenStore]) until it
 * expires. A sign-in hands the service's tokens to the server; no refresh token stays here. Without an account
 * session nothing can be connected ([canConnect]).
 *
 * Each connection is a binding named by [TrackerAccount.connectedAt] (when the server stored the sign-in). Its
 * [TrackerCredentials] ask the server one request at a time, keep the token before handing it out, and drop the
 * binding when the server says the connection is gone. [relist] then lists the connections again, as it does after a
 * connect or a disconnect here.
 */
class TrackerAccounts internal constructor(
    private val repository: TrackerAccountRepository,
    private val settings: SettingsRepository,
    private val tokens: TrackerTokenStore,
    private val server: ServerSession,
    private val api: SyncApi,
    providers: List<TrackerProvider>,
    private val clientIds: Map<TrackerService, String>,
    private val relay: OAuthRelay?,
    private val clock: Clock,
    private val timeSource: TimeSource,
    private val onConnected: (profileId: String) -> Unit,
    /** Lists the account's connections again, without waiting for it ([syncConnections] in the hub's scope). */
    private val relist: () -> Unit,
) {
    private val providers: Map<TrackerService, TrackerProvider> = providers.associateBy { it.service }
    private val mutations = Mutex()
    private val listing = Mutex()
    private val bindings = mutableMapOf<Pair<String, TrackerService>, Binding>()

    /** Connections made or dropped here, so a listing that was under way meanwhile doesn't undo them; under [mutations]. */
    private var changes = 0L

    /**
     * When the list was last brought up to date, and the account's connections counter it reflects
     * ([TrackerConnectionList.version], or the last pull's on a server whose listing lacks it); `null` before.
     */
    private var listedAt: TimeMark? = null
    private var listedVersion: Long? = null
    private var pulledVersion: Long? = null

    /** The services this build can connect (those with a client registration), in [TrackerService] order. */
    val services: List<TrackerService> = TrackerService.entries.filter { it in this.providers }

    /** This device has an account session: connections belong to the account, so without one none can be made. */
    val canConnect: Boolean get() = server.signedIn

    /** The profile's connections, oldest sign-in first. */
    fun observe(profileId: String): Flow<List<TrackerAccount>> = repository.observeAccounts(profileId)

    suspend fun accounts(profileId: String): List<TrackerAccount> = repository.accounts(profileId)

    /**
     * Connects [service] for [profileId]: shows [prompt] (a device code, or the relay's link), waits until the user
     * authorized, and hands the connection to the account server. Returns `null` when the user declined or the code
     * expired; cancel the caller to give up.
     *
     * A relay sign-in can finish in this device's own browser when [openReceiver] gives a receiver for its return
     * ([RelayStart.browserUrl]; the browser comes back with a proof that only this device gets), or on another device
     * that scanned [RelayStart.url] and confirmed there; whichever answers first ends it. Without [openReceiver] only
     * the other device can.
     *
     * @throws TrackerException the service or the relay failed (a [TrackerException.SignIn] when it refused), or
     *   the account server couldn't take the connection ([TrackerException.AccountServer], also without an account
     *   session, before anything starts).
     */
    @Throws(TrackerException::class, CancellationException::class)
    suspend fun signIn(
        profileId: String,
        service: TrackerService,
        openReceiver: (() -> RedirectReceiver)? = null,
        prompt: (SignInPrompt) -> Unit,
    ): TrackerAccount? {
        val provider = providers[service] ?: throw TrackerException.SignIn(service, "not available in this build")
        if (!server.signedIn) throw TrackerException.AccountServer(service, AccountException(AccountError.NotSignedIn))
        val session = when (val signIn = provider.signIn) {
            is TrackerSignIn.DeviceCode -> {
                val grant = signIn.requestCode()
                prompt(SignInPrompt.DeviceCode(grant))
                when (val answer = DeviceCodePoller.poll(grant, timeSource) { signIn.probe(grant) }) {
                    is DevicePoll.Success -> signIn.session(answer.token)
                    else -> {
                        Log.i(TAG) { "${service.id} sign-in ended: $answer" }
                        return null
                    }
                }
            }
            is TrackerSignIn.Relay -> {
                val relay = relay ?: throw TrackerException.SignIn(service, "no OAuth relay configured")
                val result = relaySignIn(relay, service, openReceiver, prompt) ?: run {
                    Log.i(TAG) { "${service.id} sign-in declined" }
                    return null
                }
                signIn.session(result)
            }
        }
        return connect(profileId, service, session)
    }

    /** The relay's tokens for [service], from this device's own browser or another device's confirmation; null: declined. */
    private suspend fun relaySignIn(
        relay: OAuthRelay,
        service: TrackerService,
        openReceiver: (() -> RedirectReceiver)?,
        prompt: (SignInPrompt) -> Unit,
    ): RelayResult? {
        if (openReceiver == null) {
            val start = relay.start(service, redirectUri = null)
            prompt(SignInPrompt.Link(start))
            return relay.poll(service, start.session)
        }
        val receiver = openReceiver()
        return receiver.use {
            val start = relay.start(service, receiver.redirectUri)
            prompt(SignInPrompt.Link(start))
            val state = start.browserState ?: return@use relay.poll(service, start.session)
            // Each side's failure ends only its own wait until the select takes it: a supervisor scope.
            supervisorScope {
                val elsewhere = async { relay.poll(service, start.session) }
                val here = async { browserProof(service, receiver, openReceiver, state)?.let { relay.collect(service, start.session, it) } }
                try {
                    select { elsewhere.onAwait { it }; here.onAwait { it } }
                } finally {
                    elsewhere.cancel()
                    here.cancel()
                }
            }
        }
    }

    /**
     * The proof this device's browser brings back to [opened] with `state` = [state]; null when the person declined
     * there. A browser closed before its return (Apple's sheet or tab) waits on a new receiver from [openReceiver] for
     * the next one the person opens; a return that never comes leaves the end to the relay's poll.
     */
    private suspend fun browserProof(service: TrackerService, opened: RedirectReceiver, openReceiver: () -> RedirectReceiver, state: String): String? {
        var receiver = opened
        while (true) {
            // The wait closes its receiver when it ends.
            val redirect = try {
                OAuth.awaitRedirect(receiver, state, OAuthRelayApi.SESSION_TTL_MILLIS)
            } catch (e: AccountException) {
                when (e.error) {
                    AccountError.SignInCancelled -> {
                        receiver = openReceiver()
                        continue
                    }
                    AccountError.SignInExpired -> awaitCancellation()
                    else -> throw TrackerException.SignIn(service, "the browser's return failed (${e.error})")
                }
            }
            redirect[SyncPaths.RETURN_ERROR]?.let { error ->
                if (error == "access_denied") return null
                throw TrackerException.SignIn(service, "upstream authorization failed")
            }
            return redirect[SyncPaths.RETURN_PROOF] ?: throw TrackerException.SignIn(service, "the browser came back without a proof")
        }
    }

    /**
     * Hands a signed-in [session] to the account server as the profile's [service] connection, replacing any previous
     * one (whose queued writes are dropped), keeps its access token, then replays the profile's queued writes.
     *
     * @throws TrackerException.AccountServer when the server couldn't take it; nothing is connected then. Also when
     *   this device's secret store couldn't keep the token: the server has the connection then, and the next listing
     *   brings it here.
     */
    suspend fun connect(profileId: String, service: TrackerService, session: TrackerSession): TrackerAccount {
        val request = TrackerConnectRequest(
            profileId = profileId,
            service = service.id,
            clientId = clientIds[service].orEmpty(),
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresInSeconds = session.expiresAt?.let { it - nowSeconds() },
            username = session.username,
        )
        val connection = onServer(service) { url, token -> api.connectTracker(url, token, request) }
        val account = mutations.withLock {
            changes++
            // The token first: a connection row never exists without it.
            tokens.save(profileId, service, TrackerToken(connection.connectedAtMillis, session.accessToken, session.expiresAt))
            repository.connect(profileId, service, connection.username, emptyList(), connection.connectedAtMillis)
        }
        Log.i(TAG) { "${service.id} connected" }
        onConnected(profileId)
        relist()
        return account
    }

    /**
     * Disconnects [service] from the profile on every device: the account server forgets the connection and revokes
     * its token at the service (best effort), then this device drops the connection, its queued writes and its token,
     * and the profile's switches for the service go back to on, so a later connection starts with every one on.
     *
     * @throws TrackerException.AccountServer when the server couldn't be reached; nothing changed then. Also when this
     *   device's secret store couldn't delete the token: the connection is gone then, and the cleanup of unused tokens
     *   tries the token again.
     */
    suspend fun disconnect(profileId: String, service: TrackerService) {
        onServer(service) { url, token -> api.disconnectTracker(url, token, profileId, service.id) }
        mutations.withLock {
            changes++
            repository.disconnect(profileId, service)
            tokens.clear(profileId, service)
            bindings.remove(profileId to service)
        }
        Log.i(TAG) { "${service.id} disconnected" }
        settings.edit(profileId) { for (switch in TrackerSwitch.entries) remove(ProfileSetting.Tracker(service, switch).key) }
        relist()
    }

    /**
     * Makes this device's connections the account's (the account server's list). Does nothing without an account
     * session or while another listing runs. A refusal from the server is logged; the list stays as it was until the
     * next time, so the pulls and foregrounds that call this keep calling it. Any other failure (the database) is thrown.
     */
    suspend fun syncConnections() {
        if (!server.signedIn || !listing.tryLock()) return
        try {
            val before = mutations.withLock { changes }
            val versionBefore = pulledVersion
            val list = try {
                server.authorized { url, token -> api.trackerConnections(url, token) }
            } catch (e: AccountException) {
                Log.i(TAG) { "listing the account's tracker connections failed: ${e.error}" }
                return
            }
            mutations.withLock {
                // A connection made or dropped here meanwhile is newer than this list; the next listing has it.
                if (changes != before) return
                repository.replaceAll(list.connections)
            }
            listedAt = timeSource.markNow()
            listedVersion = list.version ?: versionBefore
        } finally {
            listing.unlock()
        }
    }

    /** [syncConnections] unless the list was brought up to date less than [maxAge] ago (the app's foreground). */
    internal suspend fun syncConnectionsIfOlderThan(maxAge: Duration) {
        if (listedAt?.let { it.elapsedNow() < maxAge } == true) return
        syncConnections()
    }

    /**
     * A pull reported the account's connections counter [version] (`PullResponse.trackersVersion`): [syncConnections]
     * when the list reflects another, so a connection made or dropped on another device arrives with the next pull.
     */
    internal suspend fun onTrackersVersion(version: Long) {
        pulledVersion = version
        if (version != listedVersion) syncConnections()
    }

    /** Every service's switches for the profile, again whenever one changes (here, or on another device by sync). */
    fun observeSwitches(profileId: String): Flow<Map<TrackerService, TrackerSwitches>> =
        settings.observe(profileId).map { it.trackers }.distinctUntilChanged()

    /** Whether the profile's writes go to [service] at all, on every device of the profile. */
    suspend fun setEnabled(profileId: String, service: TrackerService, enabled: Boolean) =
        settings.set(profileId, ProfileSetting.Tracker(service, TrackerSwitch.SENDING), enabled)

    /** Whether the profile's writes about [type] go to [service], on every device of the profile. */
    suspend fun setContentFilter(profileId: String, service: TrackerService, type: TrackerContentType, on: Boolean) =
        settings.set(profileId, ProfileSetting.Tracker(service, TrackerSwitch.of(type)), on)

    /**
     * The profile's connections as trackers, each bound to its sign-in, with the service's switches: the same
     * [Tracker] instance as long as the binding lasts, a new one after a reconnect. Connections to services this build
     * has no provider for are left out.
     */
    internal fun bound(profileId: String): Flow<List<BoundTracker>> = combine(observe(profileId), observeSwitches(profileId)) { accounts, switches ->
        accounts.mapNotNull { account ->
            binding(profileId, account)?.let { BoundTracker(account, switches.getValue(account.service), it.tracker, it.watchlist) }
        }
    }

    /**
     * The read side of the profile's connections, in [TrackerService] order: one source per
     * connected service with a catalog, the same instance as long as its sign-in lasts. Emits again only when the
     * sources change (a connect, a disconnect), not when a connection's switches do. The pages read of a watchlist
     * through them are what the app knows of it ([WatchlistMembership]).
     */
    internal fun catalogSources(profileId: String): Flow<List<TrackerCatalogSource>> = observe(profileId).map { accounts ->
        accounts.sortedBy { it.service.ordinal }.mapNotNull { account -> binding(profileId, account)?.listed }
    }.distinctUntilChanged { old, new -> old.size == new.size && old.indices.all { old[it] === new[it] } }

    /**
     * The scores of the profile's connections for Detail's ratings row, in [TrackerService] order: one source per
     * connected service, enabled or not (the switches are about writes), the same instance as long as its sign-in
     * lasts.
     */
    internal fun ratingsSources(profileId: String): Flow<List<TrackerRatingsSource>> = observe(profileId).map { accounts ->
        accounts.sortedBy { it.service.ordinal }.mapNotNull { account -> binding(profileId, account)?.ratings }
    }

    /**
     * Clears the tokens no connection uses any more: a connection gone from the account, a profile deleted here or by
     * sync, or the account's data dropped take their tokens with them.
     */
    internal suspend fun clearUnusedTokens() {
        mutations.withLock {
            val connected = repository.connections()
            for ((profileId, service) in tokens.stored() - connected) {
                tokens.clear(profileId, service)
                bindings.remove(profileId to service)
                Log.i(TAG) { "${service.id} token of a removed connection cleared" }
            }
        }
    }

    /**
     * The credentials of [service]'s current sign-in for [profileId], or `null` when it isn't connected: what the read
     * side (tracker catalogs) makes its requests with.
     */
    internal suspend fun credentials(profileId: String, service: TrackerService): TrackerCredentials? =
        repository.account(profileId, service)?.let { binding(profileId, it)?.credentials }

    /** [account]'s binding: the one made for its sign-in, or a new one after a reconnect. */
    private fun binding(profileId: String, account: TrackerAccount): Binding? {
        val provider = providers[account.service] ?: return null
        val key = profileId to account.service
        bindings[key]?.takeIf { it.connectedAt == account.connectedAt }?.let { return it }
        val credentials = AccountCredentials(profileId, account.service, account.connectedAt)
        return Binding(account.connectedAt, provider, credentials).also { bindings[key] = it }
    }

    /** Runs [call] with the account session; its failures become [TrackerException.AccountServer]s. */
    private suspend fun <T> onServer(service: TrackerService, call: suspend (serverUrl: String, accessToken: String) -> T): T = try {
        server.authorized(call)
    } catch (e: AccountException) {
        throw TrackerException.AccountServer(service, e)
    }

    private fun nowSeconds(): Long = clock.now().epochSeconds

    /** One sign-in's tracker, its read side, watchlist and scores (where the service has them) and credentials. */
    private class Binding(val connectedAt: Long, provider: TrackerProvider, val credentials: AccountCredentials) {
        val tracker: Tracker = provider.tracker(credentials)
        private val catalog: TrackerCatalogSource? by lazy { (provider as? TrackerCatalogProvider)?.catalog(credentials) }
        val watchlist: BoundWatchlist? by lazy {
            (catalog as? TrackerWatchlist)?.let { BoundWatchlist(it, WatchlistMembership()) }
        }

        /** [catalog], its watchlist's pages noted in [watchlist]'s membership. */
        val listed: TrackerCatalogSource? by lazy {
            val source = catalog ?: return@lazy null
            watchlist?.let { ListedCatalogSource(source, it.membership) } ?: source
        }
        val ratings: TrackerRatingsSource? by lazy { (provider as? TrackerRatingsProvider)?.ratings(credentials) }
    }

    private inner class AccountCredentials(
        private val profileId: String,
        override val service: TrackerService,
        private val connectedAt: Long,
    ) : TrackerCredentials {
        /** One request to the account server at a time for this connection; also guards [token] and [gone]. */
        private val asking = Mutex()
        private var token: TrackerToken? = null
        private var gone = false

        override suspend fun accessToken(): String = asking.withLock {
            val held = held()
            if (held != null && !held.needsRefresh(nowSeconds())) return@withLock held.accessToken
            try {
                ask(rejected = null)
            } catch (e: TrackerException.AccountServer) {
                // A token that hasn't expired still works; the request's own 401 decides.
                if (held == null || held.expired(nowSeconds())) throw e
                Log.d(TAG) { "${service.id} token not renewed (${e.error}), using the one held" }
                held.accessToken
            }
        }

        override suspend fun refreshAfterUnauthorized(rejected: String): String = asking.withLock {
            val held = held()
            // Another caller got a new token while this one waited: it is the answer, and asking with the old one
            // again would refresh twice.
            if (held != null && held.accessToken != rejected) return@withLock held.accessToken
            ask(rejected)
        }

        override suspend fun invalidate(rejected: String) {
            asking.withLock {
                if (held()?.accessToken == rejected) ask(rejected)
            }
        }

        /** The account server's token for this sign-in, kept before it is returned. Call with [asking] held. */
        private suspend fun ask(rejected: String?): String {
            val request = TrackerTokenRequest(profileId, service.id, connectedAt, rejected)
            val answer = onServer(service) { url, token -> api.trackerToken(url, token, request) } ?: run {
                drop()
                throw TrackerException.Auth(service, "disconnected on the account", permanent = true)
            }
            val next = TrackerToken(connectedAt, answer.accessToken, answer.expiresInSeconds?.let { nowSeconds() + it })
            mutations.withLock {
                // Not for a sign-in that ended while the server answered.
                if (repository.account(profileId, service)?.connectedAt == connectedAt) tokens.save(profileId, service, next)
            }
            token = next
            Log.d(TAG) { "${service.id} token from the account server" }
            return next.accessToken
        }

        /** The token held for this sign-in, or `null`. Call with [asking] held; throws once the connection is gone. */
        private suspend fun held(): TrackerToken? {
            if (gone) throw TrackerException.Auth(service, "disconnected on the account", permanent = true)
            token?.let { return it }
            return tokens.load(profileId, service)?.takeIf { it.connectedAt == connectedAt }?.also { token = it }
        }

        /**
         * The server no longer has this sign-in: drop it here (unless a newer sign-in replaced it), then list the
         * account's connections again. Call with [asking] held.
         */
        private suspend fun drop() {
            gone = true
            token = null
            mutations.withLock {
                changes++
                if (repository.disconnect(profileId, service, connectedAt)) {
                    tokens.clear(profileId, service)
                    Log.w(TAG) { "${service.id} disconnected on the account" }
                }
                val key = profileId to service
                if (bindings[key]?.credentials === this) bindings.remove(key)
            }
            relist()
        }
    }

    private companion object {
        const val TAG = "trackers"
    }
}

/**
 * A connection, the profile's switches for its service, and the tracker bound to its sign-in, with its watchlist where
 * the service has one (the five services this build knows all do).
 */
internal class BoundTracker(
    val account: TrackerAccount,
    val switches: TrackerSwitches,
    val tracker: Tracker,
    val watchlist: BoundWatchlist? = null,
) {
    val service: TrackerService get() = account.service

    /** Writes about [type] go to this account: sending is on and its content filter lets them through. */
    fun takes(type: TrackerContentType): Boolean = switches.takes(type)
}

/** One sign-in's [TrackerWatchlist] and what is known of it. */
internal class BoundWatchlist(val list: TrackerWatchlist, val membership: WatchlistMembership)
