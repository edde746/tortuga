package com.edde746.tortuga.sync.api

import kotlinx.serialization.Serializable

/**
 * Body of every non-2xx response. Clients branch on [code] (one of [ApiErrorCode]; unknown codes are treated by their
 * HTTP status). [message] is English diagnostic text for logs, never shown to users.
 */
@Serializable
data class ApiError(val code: String, val message: String)

/** Values of [ApiError.code], with the HTTP status each comes with. */
object ApiErrorCode {
    /** 400: malformed JSON, a missing field or a value out of range. */
    const val INVALID_REQUEST: String = "invalid_request"

    /** 401: no access token, or one the server doesn't know (revoked device, deleted account). Sign in again. */
    const val UNAUTHORIZED: String = "unauthorized"

    /** 401: the access token has expired. Refresh ([SyncPaths.AUTH_REFRESH]) and retry. */
    const val TOKEN_EXPIRED: String = "token_expired"

    /** 401: the refresh token is unknown, expired or superseded. Sign in again. */
    const val INVALID_REFRESH_TOKEN: String = "invalid_refresh_token"

    /**
     * 401: the ID token failed verification: signature, issuer, audience, expiry, nonce, or it was already used (each
     * ID token signs in once); for GitHub, GitHub refused the access token (another app's, revoked) or the web
     * flow's code.
     */
    const val INVALID_ID_TOKEN: String = "invalid_id_token"

    /** 400: the provider isn't enabled on this server ([ServerInfo.providers]). */
    const val PROVIDER_NOT_ENABLED: String = "provider_not_enabled"

    /**
     * 400: this server revokes Sign in with Apple tokens ([ServerInfo.appleTokenRevocation]), so an Apple sign-in must
     * include [SignInRequest.authorizationCode].
     */
    const val APPLE_CODE_REQUIRED: String = "apple_code_required"

    /** 401: Apple refused the authorization code (expired, already used, or not from this sign-in). */
    const val APPLE_CODE_REJECTED: String = "apple_code_rejected"

    /** 502: a provider (Apple, Google, GitHub, the OIDC issuer) couldn't be reached. Retry later. */
    const val UPSTREAM_UNAVAILABLE: String = "upstream_unavailable"

    /** 409: the provider identity already belongs to another account. */
    const val IDENTITY_IN_USE: String = "identity_in_use"

    /** 409: removing the account's only identity would lock it out. */
    const val LAST_IDENTITY: String = "last_identity"

    /**
     * 409: a push based a write on a version of a record the server doesn't have ([RecordWrite.baseVersion]): it lost
     * writes the device saw (restored from a backup, or its data key lost). Pull from 0, then push every local record
     * again with the base versions that pull returned.
     */
    const val UNKNOWN_BASE_VERSION: String = "unknown_base_version"

    /** 404: no such device, identity, link code or Apple or GitHub handoff (or it expired). */
    const val NOT_FOUND: String = "not_found"

    /** 413: the request or a record exceeds its limit ([SyncProtocol]). */
    const val PAYLOAD_TOO_LARGE: String = "payload_too_large"

    /**
     * 413: the push would take the account past [ServerInfo.accountQuotaBytes], or a new tracker connection past the
     * account's limit (`TrackerConnectionsApi.MAX_CONNECTIONS`).
     */
    const val QUOTA_EXCEEDED: String = "quota_exceeded"

    /** 429: too many requests; wait for the `Retry-After` header (seconds). */
    const val RATE_LIMITED: String = "rate_limited"

    /** 503: the server is at capacity; wait for the `Retry-After` header (seconds) before trying again. */
    const val SERVER_BUSY: String = "server_busy"

    /** 500: a server bug. */
    const val INTERNAL: String = "internal"
}
