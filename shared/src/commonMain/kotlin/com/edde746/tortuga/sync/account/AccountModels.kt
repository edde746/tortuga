package com.edde746.tortuga.sync.account

import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate

/** Where this device stands with its account; [SyncAccount.state]. */
sealed interface AccountState {
    /**
     * No session: the sign-in screen. [reason] says why a session ended without the user signing out (null for a
     * fresh install or a sign-out). After [SignedOutReason.SESSION_ENDED] the synced data stays on the device and is
     * used again if the same account signs in; another account's sign-in drops it.
     */
    data class SignedOut(val reason: SignedOutReason?) : AccountState

    /** Signed in to [accountId] on [serverUrl] as device [deviceId]: profiles and sync work. */
    data class SignedIn(val serverUrl: ServerUrl, val accountId: String, val deviceId: String) : AccountState
}

enum class SignedOutReason {
    /** The server no longer accepts this device's session: revoked from another device, or unused too long. */
    SESSION_ENDED,

    /** The account was deleted on this device. */
    ACCOUNT_DELETED,

    /**
     * The platform's secret store failed to read this device's session at start (Android's Keystore can fail for a
     * moment). The session stays stored and the next start reads it again; a sign-in now makes this a new device.
     */
    SESSION_UNREADABLE,
}

/** What [SyncAccount.completeSignIn] found. */
enum class SignInResult {
    /** The sign-in created the account: it has no data yet, and the app goes on to create its first profile. */
    NEW_ACCOUNT,

    /** An existing account: its data arrives with the next sync. */
    EXISTING_ACCOUNT,
}

/**
 * A sign-in in progress on [serverUrl]: the server's [serverInfo] (which providers to offer) and the single-use
 * [nonce] to pass unchanged to the provider's sign-in (Sign in with Apple's and Google's `nonce`, the OIDC `nonce`
 * parameter).
 */
class SignInAttempt internal constructor(
    val serverUrl: ServerUrl,
    val serverInfo: ServerInfo,
    val nonce: String,
) {
    /** The server's entry for provider [id] ([com.edde746.tortuga.sync.api.IdentityProvider]), or null if disabled. */
    fun provider(id: String): ProviderInfo? = serverInfo.providers.firstOrNull { it.id == id }
}

/**
 * A provider's proof of identity for [SyncAccount.completeSignIn] or [SyncAccount.linkIdentity].
 *
 * @property provider one of [com.edde746.tortuga.sync.api.IdentityProvider].
 * @property idToken the provider's ID token; for GitHub, which issues none, a GitHub OAuth access token.
 * @property nonce the [SignInAttempt.nonce] the token was requested with, or null for tokens without a `nonce` claim
 *   (a device flow's, and GitHub's access tokens).
 * @property authorizationCode Sign in with Apple's authorization code, when the flow returned one.
 */
class ProviderCredential(
    val provider: String,
    val idToken: String,
    val nonce: String?,
    val authorizationCode: String? = null,
) {
    override fun toString(): String = "ProviderCredential(provider=$provider)"
}

/**
 * The platform's native sign-in: Sign in with Apple through AuthenticationServices on Apple platforms,
 * Google through its SDK (Credential Manager on Android). It runs the provider's UI with [nonce] passed unchanged
 * (not hashed) and returns the ID token, plus Apple's authorization code. A user who closes the sheet ends it with
 * [AccountException] ([AccountError.SignInCancelled]).
 *
 * Swift can instead call [SyncAccount.beginSignIn], run the SDK itself and pass the result to
 * [SyncAccount.completeSignIn].
 */
fun interface NativeSignIn {
    suspend fun signIn(provider: String, nonce: String): ProviderCredential
}

/** The result of [SyncAccount.deleteAccount]. [text] says what else the user has to do, if anything. */
class AccountDeletion internal constructor(
    /** One of [AppleRevocation]. */
    val appleRevocation: String,
    /** One of [GitHubRevocation]. */
    val githubRevocation: String,
    /** The server's GitHub issuer (`https://github.com`), under which the user removes Tortuga by hand. */
    private val githubIssuer: String,
) {
    /** True when the user should still remove Tortuga under their Apple ID's Sign in with Apple settings. */
    val appleRevocationNeeded: Boolean
        get() = appleRevocation == AppleRevocation.NOT_CONFIGURED || appleRevocation == AppleRevocation.FAILED

    /** What happened, and on a line of its own where GitHub kept the grant, where the user removes Tortuga there. */
    val text: String
        get() {
            val deleted = when {
                appleRevocationNeeded -> T.account.deletion.appleManual
                appleRevocation == AppleRevocation.REVOKED -> T.account.deletion.appleRevoked
                else -> T.account.deletion.done
            }
            if (githubRevocation != GitHubRevocation.FAILED) return deleted
            val applications = githubIssuer.substringAfter("://").trimEnd('/') + "/settings/applications"
            return deleted + "\n" + T.account.deletion.githubManual(isolate(applications))
        }
}
