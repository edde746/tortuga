package com.edde746.tortuga.sync.account

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.secrets.SecretStore
import com.edde746.tortuga.sync.account.signin.GitHubOAuth
import com.edde746.tortuga.sync.account.signin.OAuth
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DeviceInfo
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.LinkIdentityRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.sync.api.normalizeLinkCode
import io.ktor.client.HttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Tortuga account on this device: sign-in, device links, devices, identities, sign-out and
 * deletion. Screens observe [state]; the sync engine ([com.edde746.tortuga.sync.engine.SyncEngine]) runs while it is
 * [AccountState.SignedIn].
 *
 * The session (server URL, account id and tokens) lives in the platform [SecretStore] as `account.session`; nothing
 * secret reaches SQLite or logs.
 *
 * Local synced data belongs to one account, the one with a pull cursor ([com.edde746.tortuga.data.sync.SyncStateRepository]).
 * It is dropped (with the cursor) on sign-out, account deletion, and a sign-in or link to another account. A session
 * the server ended keeps it, so signing in to the same account again picks up where it was. Changes made here that the
 * account doesn't have yet are never dropped unasked: a sign-out refuses while there are any
 * ([AccountError.UnsyncedChanges]) unless told to drop them, and a sign-in or link to another account asks first.
 *
 * Create one per process after [LocalData] and call [restore] before the first synced write. Every function is
 * main-safe; failures are [AccountException]s, the platform secret store's included ([SessionVault]).
 */
class SyncAccount internal constructor(
    secrets: SecretStore,
    private val data: LocalData,
    private val device: DeviceDescriptor,
    internal val api: SyncApi,
    internal val clock: () -> Long,
    private val linkPollIntervalMillis: Long,
) {
    /**
     * @param http the app's client ([com.edde746.tortuga.net.TortugaHttpClient]).
     * @param device this device as the device list shows it; its name at most 64 characters.
     */
    constructor(secrets: SecretStore, data: LocalData, http: HttpClient, device: DeviceDescriptor) :
        this(secrets, data, device, SyncApi(http), { Clock.System.now().toEpochMilliseconds() }, LINK_POLL_INTERVAL_MILLIS)

    private val current = MutableStateFlow<AccountState>(AccountState.SignedOut(null))
    internal val sessions = SessionManager(api, SessionVault(secrets), clock, ::sessionEnded)

    /** Serializes account transitions (sign-in, link, sign-out). */
    private val transitions = Mutex()

    /**
     * Held while local synced data is written on behalf of the account (the sync engine applying a page) or dropped,
     * so a sign-out or account change never interleaves with a sync write. [generation] changes with every drop; the
     * engine checks it inside the lock ([withSyncData]).
     */
    private val dataLock = Mutex()
    private var generation = 0L

    val state: StateFlow<AccountState> = current.asStateFlow()

    private val linked = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Emits each time [linkIdentity] linked an identity, whichever screen ran it: what lists the account's identities
     * (the account settings) reads them again.
     */
    val identityLinked: SharedFlow<Unit> = linked.asSharedFlow()

    /**
     * Loads the session from the `SecretStore` and sets [state]. Call once at startup. A store that fails to read
     * leaves the session stored and the device [SignedOutReason.SESSION_UNREADABLE].
     */
    suspend fun restore() {
        transitions.withLock {
            val session = try {
                sessions.load()
            } catch (e: AccountException) {
                // SessionVault logged the store's failure.
                current.value = AccountState.SignedOut(SignedOutReason.SESSION_UNREADABLE)
                return@withLock
            }
            val server = session?.let { stored ->
                try {
                    ServerUrl.parse(stored.serverUrl)
                } catch (e: AccountException) {
                    Log.w(TAG) { "The stored session's server isn't a server URL (${e.error}): signed out" }
                    null
                }
            }
            current.value = if (session == null) {
                AccountState.SignedOut(null)
            } else if (server == null) {
                AccountState.SignedOut(SignedOutReason.SESSION_UNREADABLE)
            } else {
                adoptLocalData(session.accountId)
                AccountState.SignedIn(server, session.accountId, session.tokens.deviceId)
            }
        }
    }

    // ---- Sign-in -------------------------------------------------------------------------------------------------

    /**
     * What the server at [serverUrl] offers, for the sign-in screen.
     *
     * @throws AccountException [AccountError.NotTortugaServer],
     *   [AccountError.IncompatibleServer], [AccountError.Network], [AccountError.Server], [AccountError.RateLimited].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun serverInfo(serverUrl: ServerUrl): ServerInfo = checkedInfo(serverUrl.value)

    /** Step one of a sign-in: the server's providers and a nonce for the provider's sign-in ([SignInAttempt]). */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun beginSignIn(serverUrl: ServerUrl): SignInAttempt {
        val info = checkedInfo(serverUrl.value)
        val nonce = api.nonce(serverUrl.value)
        return SignInAttempt(serverUrl, info, nonce.nonce)
    }

    /**
     * Step two: signs in with the provider's [credential]. When the account is another than the one whose data this
     * device holds (its session ended) and changes made here never reached that account, [discardUnsynced] is asked
     * with their count: true drops them with the rest of that data, false keeps everything as it is, ends the new
     * session and throws [AccountError.UnsyncedChanges].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun completeSignIn(
        attempt: SignInAttempt,
        credential: ProviderCredential,
        discardUnsynced: suspend (unsynced: Long) -> Boolean = { false },
    ): SignInResult {
        val request = SignInRequest(credential.provider, credential.idToken, credential.nonce, credential.authorizationCode, device)
        val response = api.signIn(attempt.serverUrl.value, request)
        val tokens = response.session.received(clock())
        signedIn(attempt.serverUrl, StoredSession(attempt.serverUrl.value, response.account.accountId, tokens), discardUnsynced)
        if (response.accountCreated) Log.i(TAG) { "Signed in to a new account" }
        return if (response.accountCreated) SignInResult.NEW_ACCOUNT else SignInResult.EXISTING_ACCOUNT
    }

    /** [beginSignIn], [obtain] (a provider flow, e.g. [NativeSignIn]), then [completeSignIn]. */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signIn(
        serverUrl: ServerUrl,
        discardUnsynced: suspend (unsynced: Long) -> Boolean = { false },
        obtain: suspend (SignInAttempt) -> ProviderCredential,
    ): SignInResult {
        val attempt = beginSignIn(serverUrl)
        return completeSignIn(attempt, obtain(attempt), discardUnsynced)
    }

    /** Signs in with the platform's native sign-in for [provider]. */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signIn(serverUrl: ServerUrl, provider: String, native: NativeSignIn): SignInResult =
        signIn(serverUrl) { attempt -> native.signIn(provider, attempt.nonce) }

    // ---- Device link: the new device ------------------------------------------------------------------------------

    /**
     * Opens a device link on [serverUrl] from this signed-out device (a TV, or anything without easy typing): it shows
     * a code that a signed-in device approves, and the approval signs this device in.
     *
     * @throws IllegalStateException if this device is signed in.
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun startDeviceLink(serverUrl: ServerUrl): PendingDeviceLink {
        check(current.value is AccountState.SignedOut) { "This device is already signed in" }
        checkedInfo(serverUrl.value)
        val secret = OAuth.randomToken()
        val start = api.linkStart(serverUrl.value, LinkStartRequest(OAuth.sha256Base64Url(secret), device))
        logLink { "Link started; waiting for approval" }
        return PendingDeviceLink(this, serverUrl, secret, start, linkPollIntervalMillis)
    }

    internal suspend fun completeDeviceLink(serverUrl: ServerUrl, answer: LinkPollResponse, discardUnsynced: suspend (Long) -> Boolean) {
        val receivedAt = clock()
        val accountId = answer.accountId ?: throw AccountException(AccountError.LinkFailed)
        val tokens = answer.session?.received(receivedAt) ?: throw AccountException(AccountError.LinkFailed)
        signedIn(serverUrl, StoredSession(serverUrl.value, accountId, tokens), discardUnsynced)
        logLink { "Link approved; signed in" }
    }

    // ---- Device link: the approving device -------------------------------------------------------------------------

    /**
     * Claims the link named by [input]: a code as typed (`abcd efgh`, `ABCD-EFGH`) or a [DeviceLinkQr]'s text. The
     * returned device is shown for confirmation.
     *
     * @throws AccountException [AccountError.LinkCodeInvalid], [AccountError.LinkOtherServer],
     *   [AccountError.LinkNotFound], [AccountError.NotSignedIn].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun claimDeviceLink(input: String): IncomingDeviceLink {
        requireSignedIn()
        DeviceLinkQr.parse(input)?.let { return claimDeviceLink(it) }
        return claimLinkCode(normalizeLinkCode(input) ?: throw AccountException(AccountError.LinkCodeInvalid))
    }

    /**
     * Claims the link a scanned [qr] names, on this account's server only. The returned device is shown for confirmation.
     *
     * @throws AccountException [AccountError.LinkOtherServer], [AccountError.LinkNotFound], [AccountError.NotSignedIn].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun claimDeviceLink(qr: DeviceLinkQr): IncomingDeviceLink {
        val signedIn = requireSignedIn()
        if (qr.serverUrl != signedIn.serverUrl) throw AccountException(AccountError.LinkOtherServer)
        return claimLinkCode(qr.code)
    }

    private suspend fun claimLinkCode(code: String): IncomingDeviceLink {
        val claim = linkCall { url, token -> api.linkClaim(url, token, code) }
        return IncomingDeviceLink(this, code, claim)
    }

    internal suspend fun approveDeviceLink(code: String) {
        linkCall { url, token -> api.linkApprove(url, token, code) }
        logLink { "Link approved on this device" }
    }

    internal suspend fun rejectDeviceLink(code: String) {
        linkCall { url, token -> api.linkReject(url, token, code) }
    }

    private suspend fun <T> linkCall(call: suspend (String, String) -> T): T = try {
        sessions.authorized(call)
    } catch (e: AccountException) {
        if (e.error == AccountError.NotFound) throw AccountException(AccountError.LinkNotFound, e)
        throw e
    }

    // ---- Account, devices, identities ---------------------------------------------------------------------------

    /** The account as the server has it: its id, creation time and sign-ins. */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun accountInfo(): AccountInfo = sessions.authorized { url, token -> api.account(url, token) }

    /** Every session of the account, this device's marked [DeviceInfo.current]. */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun devices(): List<DeviceInfo> = sessions.authorized { url, token -> api.devices(url, token) }.devices

    /**
     * Revokes a device's session; revoking this device signs it out (as [signOut], with its check of the changes not
     * yet synced, before the server is asked).
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun revokeDevice(deviceId: String, discardUnsynced: Boolean = false) {
        val own = sessions.session.value?.tokens?.deviceId == deviceId
        if (own && !discardUnsynced) refuseUnsynced()
        try {
            sessions.authorized { url, token -> api.revokeDevice(url, token, deviceId) }
        } catch (e: AccountException) {
            if (!(own && e.error == AccountError.SessionEnded)) throw e
        }
        if (own) signOutLocally(null, discardUnsynced)
    }

    /** A nonce on this device's server for linking a second provider identity (settings). */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun beginIdentityLink(): SignInAttempt {
        val server = requireSignedIn().serverUrl
        val info = checkedInfo(server.value)
        val nonce = api.nonce(server.value)
        return SignInAttempt(server, info, nonce.nonce)
    }

    /**
     * Links another provider identity to the account, then tells [identityLinked].
     *
     * @throws AccountException [AccountError.IdentityInUse].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun linkIdentity(credential: ProviderCredential): AccountInfo = sessions.authorized { url, token ->
        api.linkIdentity(url, token, LinkIdentityRequest(credential.provider, credential.idToken, credential.nonce, credential.authorizationCode))
    }.also { linked.tryEmit(Unit) }

    /** @throws AccountException [AccountError.LastIdentity] for the account's only identity. */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun unlinkIdentity(identityId: String): AccountInfo =
        sessions.authorized { url, token -> api.unlinkIdentity(url, token, identityId) }

    // ---- Sign-out and deletion ----------------------------------------------------------------------------------

    /**
     * Signs this device out: ends the session on the server (best effort; offline it just expires) and drops the
     * tokens and every synced record from this device. Device settings stay. Push first
     * ([com.edde746.tortuga.sync.engine.SyncEngine.pushNow]): changes the account doesn't have yet are dropped only
     * with [discardUnsynced].
     *
     * @throws AccountException [AccountError.UnsyncedChanges] when changes made here haven't synced and not
     *   [discardUnsynced]; or the secret store couldn't delete the session. Either way the device stays signed in with
     *   its data (after a session the server already ended, its next request signs it out and keeps the data).
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signOut(discardUnsynced: Boolean = false) {
        if (!discardUnsynced) refuseUnsynced()
        sessions.session.value?.let { endRemoteSession(it) }
        signOutLocally(null, discardUnsynced)
    }

    /**
     * Deletes the account and all its data on the server (App Store 5.1.1(v)), then everything of it
     * on this device. Other devices are signed out at their next request. Where the server couldn't delete the
     * GitHub grant, the result names where the user removes Tortuga by hand: under the GitHub issuer the server's
     * info lists, read after the deletion, else github.com.
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun deleteAccount(): AccountDeletion {
        val server = sessions.session.value?.serverUrl ?: throw AccountException(AccountError.NotSignedIn)
        val response = sessions.authorized { url, token -> api.deleteAccount(url, token) }
        signOutLocally(SignedOutReason.ACCOUNT_DELETED, discardUnsynced = true)
        Log.i(TAG) { "Account deleted" }
        val githubIssuer = if (response.githubRevocation == GitHubRevocation.FAILED) githubIssuer(server) else GitHubOAuth.ISSUER
        return AccountDeletion(response.appleRevocation, response.githubRevocation, githubIssuer)
    }

    /** The GitHub issuer [server] lists, or github.com when it lists none (or can't be asked: logged). */
    private suspend fun githubIssuer(server: String): String {
        val issuer = try {
            api.info(server).providers.firstOrNull { it.id == IdentityProvider.GITHUB }?.issuer
        } catch (e: AccountException) {
            Log.i(TAG) { "Reading the server's GitHub issuer failed: ${e.error}" }
            null
        }
        return issuer ?: GitHubOAuth.ISSUER
    }

    // ---- For the sync engine ------------------------------------------------------------------------------------

    /**
     * Runs [block] under the data lock if the account is still [target]'s (same account and [generation]); returns
     * null without running it otherwise. Sync writes go through here so none lands after a drop.
     */
    internal suspend fun <T> withSyncData(target: SyncTarget, block: suspend () -> T): T? = dataLock.withLock {
        val signedIn = current.value as? AccountState.SignedIn
        if (generation != target.generation || signedIn?.accountId != target.accountId) null else block()
    }

    /** The account the sync engine works for, or null unless [AccountState.SignedIn]. */
    internal suspend fun syncTarget(): SyncTarget? = dataLock.withLock {
        val state = current.value as? AccountState.SignedIn ?: return@withLock null
        SyncTarget(state.accountId, generation)
    }

    // ---- Internals ------------------------------------------------------------------------------------------------

    private suspend fun checkedInfo(server: String): ServerInfo {
        val info = api.info(server)
        if (info.protocolVersion < SyncProtocol.VERSION) throw AccountException(AccountError.IncompatibleServer(info.protocolVersion))
        return info
    }

    private fun requireSignedIn(): AccountState.SignedIn = current.value as? AccountState.SignedIn
        ?: throw AccountException(AccountError.NotSignedIn)

    /**
     * Makes [session] this device's: a previous session ends, another account's local data goes, asking
     * [discardUnsynced] first when some of it never synced. The session is stored before any data is dropped, so a
     * secret store that fails leaves everything as it was.
     */
    private suspend fun signedIn(server: ServerUrl, session: StoredSession, discardUnsynced: suspend (Long) -> Boolean) {
        transitions.withLock {
            val owner = data.syncState.dataAccount()
            if (owner != null && owner != session.accountId) {
                val unsynced = data.sync.unsyncedCount()
                val discard = unsynced == 0L || try {
                    discardUnsynced(unsynced)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { endRemoteSession(session) }
                    throw e
                }
                if (!discard) {
                    endRemoteSession(session)
                    Log.i(TAG) { "Sign-in to another account declined: $unsynced changes of this device's account haven't synced" }
                    throw AccountException(AccountError.UnsyncedChanges(unsynced))
                }
            }
            sessions.session.value?.let { previous -> endRemoteSession(previous) }
            sessions.set(session)
            adoptLocalData(session.accountId)
            current.value = AccountState.SignedIn(server, session.accountId, session.tokens.deviceId)
        }
    }

    /**
     * Makes the local synced data [accountId]'s: drops another account's first, then starts [accountId]'s pull cursor
     * unless it has one. Callers hold [transitions].
     */
    private suspend fun adoptLocalData(accountId: String) {
        val owner = data.syncState.dataAccount()
        if (owner != null && owner != accountId) dropLocal()
        if (owner != accountId) data.syncState.setPullCursor(accountId, 0)
    }

    /**
     * Drops the synced data and every pull cursor, in one transaction. The generation moves on even when the drop's
     * caller is cancelled as it commits, so no cycle started before it applies its records over it. Callers hold
     * [transitions].
     */
    private suspend fun dropLocal() {
        dataLock.withLock {
            try {
                data.sync.dropSyncedData()
            } finally {
                generation++
            }
        }
    }

    /** @throws AccountException [AccountError.UnsyncedChanges] while changes made here await a push. */
    private suspend fun refuseUnsynced() {
        val unsynced = data.sync.unsyncedCount()
        if (unsynced > 0) throw AccountException(AccountError.UnsyncedChanges(unsynced))
    }

    /**
     * Forgets the session, then drops the local data (unsynced changes only with [discardUnsynced]) and publishes
     * [AccountState.SignedOut]: a secret store that can't delete the session leaves the device signed in, data and all.
     */
    private suspend fun signOutLocally(reason: SignedOutReason?, discardUnsynced: Boolean) {
        transitions.withLock {
            if (!discardUnsynced) refuseUnsynced()
            sessions.clear()
            dropLocal()
            current.value = AccountState.SignedOut(reason)
        }
    }

    /** Best-effort sign-out of [session] on its server; a failure only leaves it to expire. */
    private suspend fun endRemoteSession(session: StoredSession) {
        try {
            if (sessions.session.value == session) {
                sessions.authorized { url, token -> api.signOut(url, token) }
            } else {
                api.signOut(session.serverUrl, session.tokens.accessToken)
            }
        } catch (e: AccountException) {
            Log.i(TAG) { "Ending the previous session on the server failed: ${e.error}" }
        }
    }

    private fun sessionEnded(session: StoredSession) {
        val state = current.value
        if (state !is AccountState.SignedOut) current.value = AccountState.SignedOut(SignedOutReason.SESSION_ENDED)
        Log.i(TAG) { "Signed out by the server (account ${session.accountId})" }
    }

    private companion object {
        const val TAG = "SyncAccount"
        const val LINK_POLL_INTERVAL_MILLIS = 2_000L
    }
}

/** What one sync cycle works for: the account and the data [generation][SyncAccount.withSyncData]. */
internal class SyncTarget(val accountId: String, val generation: Long)
