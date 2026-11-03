package com.edde746.tortuga.sync.api

import kotlinx.serialization.Serializable

/**
 * `GET` [SyncPaths.ACCOUNT]: the signed-in account.
 *
 * @property identities the provider identities that sign in to this account (at least one).
 */
@Serializable
data class AccountInfo(
    val accountId: String,
    val createdAtMillis: Long,
    val identities: List<IdentityInfo>,
)

/**
 * A provider identity linked to the account.
 *
 * @property email the address the provider shared and verified, if any (Apple's may be a private relay address).
 */
@Serializable
data class IdentityInfo(
    val identityId: String,
    val provider: String,
    val email: String? = null,
    val createdAtMillis: Long,
)

/**
 * `POST` [SyncPaths.ACCOUNT_IDENTITIES]: links a second provider to the signed-in account (settings), so losing one
 * provider account doesn't lock the user out. Fields as in [SignInRequest]. Fails with
 * [ApiErrorCode.IDENTITY_IN_USE] if the identity already signs in to another account.
 */
@Serializable
data class LinkIdentityRequest(
    val provider: String,
    val idToken: String,
    val nonce: String? = null,
    val authorizationCode: String? = null,
)

/**
 * `DELETE` [SyncPaths.ACCOUNT]: the account and everything under it are gone (identities, sessions, records,
 * pending device links). Every other device's next request gets [ApiErrorCode.UNAUTHORIZED].
 *
 * @property appleRevocation what happened to the account's Sign in with Apple authorization, one of [AppleRevocation].
 * @property githubRevocation what happened to the account's GitHub authorization, one of [GitHubRevocation]. A server
 *   older than GitHub sign-in sends none.
 */
@Serializable
data class AccountDeletionResponse(val appleRevocation: String, val githubRevocation: String = GitHubRevocation.NONE)

/** Values of [AccountDeletionResponse.appleRevocation]. */
object AppleRevocation {
    /** The account had no Apple identity. */
    const val NONE: String = "none"

    /** Apple revoked the tokens; Tortuga no longer appears under the user's Apple ID sign-ins. */
    const val REVOKED: String = "revoked"

    /**
     * This server can't revoke (self-hosted without Tortuga's Apple key, or no refresh token was kept): tell the user
     * to remove Tortuga under Settings → Apple ID → Sign in with Apple.
     */
    const val NOT_CONFIGURED: String = "not_configured"

    /** Apple couldn't be reached or refused; the data is deleted anyway. Same advice as [NOT_CONFIGURED]. */
    const val FAILED: String = "failed"
}

/**
 * Values of [AccountDeletionResponse.githubRevocation]. The server deletes the grant (`DELETE
 * /applications/{client_id}/grant`) with the token it kept from the last GitHub sign-in, best effort.
 */
object GitHubRevocation {
    /** The account had no GitHub identity. */
    const val NONE: String = "none"

    /** GitHub deleted the grant; Tortuga no longer appears under the user's authorized OAuth apps. */
    const val REVOKED: String = "revoked"

    /**
     * GitHub couldn't be reached or refused (the user already removed Tortuga, or GitHub ended a token unused for a
     * year), or this server no longer has its GitHub app; the data is deleted anyway. Tell the user they can remove
     * Tortuga at `<issuer>/settings/applications`.
     */
    const val FAILED: String = "failed"
}

/**
 * `GET` [SyncPaths.DEVICES]: every session of the account (at most [SyncProtocol.MAX_SESSIONS]), most recently seen
 * first.
 */
@Serializable
data class DeviceList(val devices: List<DeviceInfo>)

/**
 * One device (session). `DELETE $DEVICES/{deviceId}` revokes it at once; revoking [current] signs this device out.
 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val createdAtMillis: Long,
    val lastSeenAtMillis: Long,
    val current: Boolean,
)
