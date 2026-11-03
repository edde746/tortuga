package com.edde746.tortuga.sync.account

import com.edde746.tortuga.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A session on an account server that authenticated calls run with: this device's ([SessionManager]), or a
 * development session's stand-in for the fake tracker server (the dev module's `DevServerSession`).
 */
interface ServerSession {
    /** There is a session; without one every [authorized] call fails with [AccountError.NotSignedIn]. */
    val signedIn: Boolean

    /**
     * Runs [call] (one request) with the server URL and a valid access token.
     *
     * @throws AccountException [AccountError.NotSignedIn] without a session, [AccountError.SessionEnded] once the
     *   server refuses it, or whatever [call] throws.
     */
    suspend fun <T> authorized(call: suspend (serverUrl: String, accessToken: String) -> T): T
}

/**
 * This device's session: the tokens in the `SecretStore` ([SessionVault]) and in memory, and the access-token
 * refresh every authenticated call goes through ([authorized]).
 *
 * - An access token about to run out ([StoredTokens.accessExpiresAtMillis], a deadline by this device's clock) is
 *   refreshed before the call; a call answered [TokenExpiredException] refreshes and runs once more.
 * - Refreshes are single-flight: concurrent callers wait for one refresh and use its tokens. Refresh tokens rotate,
 *   so two refreshes with the same token would race.
 * - [AccountError.SessionEnded] (unauthorized, or an invalid refresh token) forgets the session and reports it through
 *   [onEnded], also when the secret store can't delete it; the user signs in again.
 * - The secret store's failures are [AccountException]s ([SessionVault]); a refresh whose tokens it can't keep fails,
 *   and the session keeps the stored tokens, whose refresh token the server accepts once more within ten minutes.
 * - A refresh that got no answer keeps the old tokens: the server accepts the previous refresh token once more within
 *   ten minutes, so the next attempt retries it (a second lost answer ends the session).
 */
internal class SessionManager(
    private val api: SyncApi,
    private val vault: SessionVault,
    private val clock: () -> Long,
    private val onEnded: (StoredSession) -> Unit,
) : ServerSession {
    private val current = MutableStateFlow<StoredSession?>(null)
    private val refreshLock = Mutex()

    val session: StateFlow<StoredSession?> = current.asStateFlow()

    override val signedIn: Boolean get() = current.value != null

    suspend fun load(): StoredSession? = vault.load().also { current.value = it }

    suspend fun set(session: StoredSession) {
        vault.store(session)
        current.value = session
    }

    suspend fun clear() {
        vault.clear()
        current.value = null
    }

    override suspend fun <T> authorized(call: suspend (serverUrl: String, accessToken: String) -> T): T {
        var session = current.value ?: throw AccountException(AccountError.NotSignedIn)
        if (session.tokens.accessExpiresAtMillis - EXPIRY_MARGIN_MILLIS <= clock()) {
            session = refresh(session)
        }
        var refreshed = false
        while (true) {
            try {
                return call(session.serverUrl, session.tokens.accessToken)
            } catch (e: TokenExpiredException) {
                if (refreshed) throw AccountException(AccountError.Unexpected(401, "token_expired"), e)
                refreshed = true
                session = refresh(session)
            } catch (e: AccountException) {
                if (e.error == AccountError.SessionEnded) end(session)
                throw e
            }
        }
    }

    /** New tokens for [stale], unless another caller replaced them meanwhile. */
    private suspend fun refresh(stale: StoredSession): StoredSession = refreshLock.withLock {
        val latest = current.value ?: throw AccountException(AccountError.SessionEnded)
        if (latest.tokens.accessToken != stale.tokens.accessToken) return latest
        val tokens = try {
            api.refresh(latest.serverUrl, latest.tokens.refreshToken)
        } catch (e: AccountException) {
            if (e.error == AccountError.SessionEnded) end(latest)
            throw e
        }
        val next = latest.copy(tokens = tokens.received(clock()))
        vault.store(next)
        current.value = next
        Log.d(TAG) { "Session refreshed" }
        next
    }

    /**
     * The server ended [session]: it is forgotten here and reported through [onEnded] even when the secret store can't
     * delete it (logged by [SessionVault]); the next start loads it again, and its first call ends it again.
     */
    private suspend fun end(session: StoredSession) {
        if (current.value?.tokens?.refreshToken != session.tokens.refreshToken &&
            current.value?.tokens?.accessToken != session.tokens.accessToken
        ) {
            return
        }
        Log.i(TAG) { "The server ended this device's session" }
        current.value = null
        try {
            vault.clear()
        } catch (e: AccountException) {
            Log.w(TAG) { "The ended session stays in the secret store until the next start ends it again" }
        }
        onEnded(session)
    }

    private companion object {
        const val TAG = "SessionManager"

        /** Refresh a little before the server's expiry, so a request doesn't arrive just after it. */
        const val EXPIRY_MARGIN_MILLIS = 30_000L
    }
}
