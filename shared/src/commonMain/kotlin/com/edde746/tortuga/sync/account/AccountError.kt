package com.edde746.tortuga.sync.account

import com.edde746.tortuga.strings.T

/**
 * Why an account or sync operation failed, carried by [AccountException]. Screens show [message]; code branches on
 * the type. Server error codes ([com.edde746.tortuga.sync.api.ApiErrorCode]) map to the cases named after them.
 */
sealed interface AccountError {
    /** The server couldn't be reached: no connection, DNS, TLS, a timeout. */
    data object Network : AccountError

    /** A 5xx answer, or [com.edde746.tortuga.sync.api.ApiErrorCode.INTERNAL]. */
    data class Server(val status: Int) : AccountError

    /** A 429, or a 503 with `Retry-After` (the server too busy), whose wait was longer than the client waits on its own. */
    data class RateLimited(val retryAfterMillis: Long) : AccountError

    /** Not an http(s) URL with a host. */
    data object InvalidServerUrl : AccountError

    /**
     * A plain-http URL of a host that isn't on this device or its network ([ServerUrl.parse]): the sign-in, the
     * session's tokens and the synced data would cross the internet unencrypted.
     */
    data object InsecureServerUrl : AccountError

    /** The URL answered, but not with a Tortuga server's [com.edde746.tortuga.sync.api.ServerInfo]. */
    data object NotTortugaServer : AccountError

    /** The server speaks an older protocol than this build needs. */
    data class IncompatibleServer(val protocolVersion: Int) : AccountError

    data object ProviderNotEnabled : AccountError

    /**
     * The server refused the provider's ID token (signature, audience, expiry, nonce, reuse), or an Apple sign-in
     * lacked the authorization code the server needs.
     */
    data object SignInRejected : AccountError

    data object AppleCodeRejected : AccountError

    /** The provider (Apple, Google, the OIDC issuer) couldn't be reached, by the server or by this device. */
    data object ProviderUnavailable : AccountError

    /** The user closed or declined the provider's sign-in. */
    data object SignInCancelled : AccountError

    /** The provider's code or browser sign-in expired before it finished. */
    data object SignInExpired : AccountError

    data object IdentityInUse : AccountError
    data object LastIdentity : AccountError

    /** The server no longer knows this device's session (revoked, expired, or the account was deleted). */
    data object SessionEnded : AccountError

    data object NotSignedIn : AccountError

    /** A device, identity or other named thing is gone. */
    data object NotFound : AccountError

    /** A device-link code that isn't 8 characters of the code alphabet. */
    data object LinkCodeInvalid : AccountError

    /** No open link has this code (mistyped, expired or answered). */
    data object LinkNotFound : AccountError

    /** A scanned QR code names another sync server than this device's. */
    data object LinkOtherServer : AccountError

    /** The server answered an approved link without the new device's session. */
    data object LinkFailed : AccountError

    data object PayloadTooLarge : AccountError
    data object QuotaExceeded : AccountError

    /**
     * Signing out, or signing in to another account, would delete [count] changes made on this device that its
     * account doesn't have yet; nothing was changed ([SyncAccount.signOut]).
     */
    data class UnsyncedChanges(val count: Long) : AccountError

    /** A request the server found malformed: a client bug. [detail] is the server's diagnostic text. */
    data class InvalidRequest(val detail: String) : AccountError

    /**
     * A push based a write on a version of a record the server doesn't have
     * ([com.edde746.tortuga.sync.api.ApiErrorCode.UNKNOWN_BASE_VERSION]): the server lost writes this device saw.
     */
    data object UnknownBaseVersion : AccountError

    /**
     * Any other answer; [status] 0 is a failure on this device that [code] names (the secret store's,
     * [SessionVault.SECRET_STORE_FAILED]; a re-sync that kept finding the server restored).
     */
    data class Unexpected(val status: Int, val code: String) : AccountError
}

/** The user-visible description of this error. */
val AccountError.message: String
    get() = when (this) {
        AccountError.Network -> T.account.error.network
        is AccountError.Server -> T.account.error.server
        is AccountError.RateLimited -> T.account.error.rateLimited
        AccountError.InvalidServerUrl -> T.account.error.invalidServerUrl
        AccountError.InsecureServerUrl -> T.account.error.insecureServerUrl
        AccountError.NotTortugaServer -> T.account.error.notTortugaServer
        is AccountError.IncompatibleServer -> T.account.error.incompatibleServer
        AccountError.ProviderNotEnabled -> T.account.error.providerNotEnabled
        AccountError.SignInRejected -> T.account.error.signInRejected
        AccountError.AppleCodeRejected -> T.account.error.appleCodeRejected
        AccountError.ProviderUnavailable -> T.account.error.providerUnavailable
        AccountError.SignInCancelled -> T.account.error.signInCancelled
        AccountError.SignInExpired -> T.account.error.signInExpired
        AccountError.IdentityInUse -> T.account.error.identityInUse
        AccountError.LastIdentity -> T.account.error.lastIdentity
        AccountError.SessionEnded -> T.account.error.sessionEnded
        AccountError.NotSignedIn -> T.account.error.notSignedIn
        AccountError.NotFound -> T.account.error.notFound
        AccountError.LinkCodeInvalid -> T.account.error.linkCodeInvalid
        AccountError.LinkNotFound -> T.account.error.linkNotFound
        AccountError.LinkOtherServer -> T.account.error.linkOtherServer
        AccountError.LinkFailed -> T.account.error.linkFailed
        AccountError.PayloadTooLarge -> T.account.error.tooLarge
        AccountError.QuotaExceeded -> T.account.error.quotaExceeded
        is AccountError.UnsyncedChanges -> T.account.error.unsynced(count.toInt())
        is AccountError.InvalidRequest, AccountError.UnknownBaseVersion, is AccountError.Unexpected -> T.account.error.unexpected
    }

/** An account or sync operation failed; see [error]. */
class AccountException(val error: AccountError, cause: Throwable? = null) : Exception(error.toString(), cause)
