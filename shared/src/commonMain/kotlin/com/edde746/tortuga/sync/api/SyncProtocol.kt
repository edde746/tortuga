package com.edde746.tortuga.sync.api

import kotlinx.serialization.json.Json

/**
 * The Tortuga sync protocol, version 2: the HTTP API between the apps and the sync server. The server
 * (`server/`) and the client sync engine both use the types in this package, and their KDoc is the specification.
 *
 * **Transport.** HTTPS; request and response bodies are JSON ([json]) in UTF-8; every path starts with [PATH_PREFIX]
 * (the protocol's major version) and is listed in [SyncPaths]. Timestamps are Unix epoch milliseconds (`…AtMillis`).
 * Lifetimes the server grants (nonces, tokens, device links) are milliseconds from the response (`…InMillis`): a
 * client adds them to its own clock on receipt, so they hold however far its clock is from the server's, while the
 * server enforces them on its own clock. Unknown JSON fields are ignored on both sides, so later minor revisions add
 * fields without a new prefix. A non-2xx response carries an [ApiError].
 *
 * **Authentication.** Endpoints marked *authenticated* take `Authorization: Bearer <accessToken>` from
 * [SessionTokens]. Each device has its own session (a "device" in [DeviceInfo] is a session). Access tokens are
 * short-lived: on [ApiErrorCode.TOKEN_EXPIRED] the client calls [SyncPaths.AUTH_REFRESH] once and retries. Tokens are
 * opaque random strings; the server stores only their SHA-256.
 *
 * **What the server sees.** Everything synced: records travel as readable JSON ([RecordWrite.payload]) with natural
 * ids ([RecordWrite.recordId]), and the server can read them. At rest it encrypts each record's payload and id with
 * its own data key, kept outside the database, and finds records through a keyed index, so a database dump or backup
 * holds none of an account's addon URLs, ids or titles. Provider identities (issuer, subject, a verified email if the
 * provider shares one) and devices (name, platform, timestamps) are stored as they are.
 *
 * **Flows.**
 * 1. Sign in: [SyncPaths.AUTH_NONCE] → the platform's Apple/Google/OIDC sign-in with that nonce → [SignInRequest].
 *    The device gets the account's data from the next pull; a new account ([SignInResponse.accountCreated]) has none.
 * 2. Device link, a sign-in for devices without easy typing: [LinkStartRequest] (new device) → [LinkCodeRequest]
 *    claim and approve (signed-in device) → [LinkPollRequest] (new device), which collects the new device's session.
 * 3. Sync: [SyncPaths.SYNC_PULL] with a cursor, [PushRequest] with each record's base version; see [PushRequest].
 * 4. Account deletion: `DELETE` [SyncPaths.ACCOUNT] removes everything and revokes Sign in with Apple
 *    ([AccountDeletionResponse]).
 */
object SyncProtocol {
    /** Protocol version this build speaks; the server reports its own in [ServerInfo.protocolVersion]. */
    const val VERSION: Int = 2

    /** Prefix of every path, the protocol's major version. */
    const val PATH_PREFIX: String = "/v2"

    /** Longest [RecordWrite.recordId], in characters. */
    const val MAX_RECORD_ID_LENGTH: Int = 1024

    /** Most writes in one [PushRequest]. */
    const val MAX_WRITES_PER_PUSH: Int = 500

    /** Largest [RecordWrite.payload], in bytes of its compact JSON encoding ([json]) in UTF-8. */
    const val MAX_RECORD_BYTES: Int = 256 * 1024

    /**
     * Largest payload of a tombstone ([RecordWrite.deleted]), in bytes as [MAX_RECORD_BYTES] counts them: the server
     * keeps tombstones until the account is deleted. The apps' tombstone is the record's key and its clock, about the
     * record id's length plus 100 bytes, under 4.2 KiB for the longest id.
     */
    const val MAX_TOMBSTONE_BYTES: Int = 8 * 1024

    /**
     * Largest request body, in bytes ([ApiErrorCode.PAYLOAD_TOO_LARGE] above it). Pushes of large records are split
     * into several requests below this size.
     */
    const val MAX_REQUEST_BYTES: Int = 8 * 1024 * 1024

    /** Default and largest `limit` of a pull. */
    const val DEFAULT_PULL_LIMIT: Int = 500
    const val MAX_PULL_LIMIT: Int = 1000

    /** Longest [DeviceDescriptor.name] and [DeviceDescriptor.platform], in characters. */
    const val MAX_DEVICE_NAME_LENGTH: Int = 64
    const val MAX_PLATFORM_LENGTH: Int = 32

    /**
     * Most sessions (devices, [DeviceInfo]) an account holds: a household's devices with room for installs that never
     * signed out. A sign-in or device link past it ends the least recently seen session, which that device learns as
     * any revoked one does ([ApiErrorCode.UNAUTHORIZED], [ApiErrorCode.INVALID_REFRESH_TOKEN]).
     */
    const val MAX_SESSIONS: Int = 50

    /** How often the [SyncPaths.SYNC_EVENTS] stream carries a keepalive comment while there is nothing to notify. */
    const val EVENTS_KEEPALIVE_MILLIS: Long = 30_000

    /**
     * Whether [recordId] is a valid record id: 1 to [MAX_RECORD_ID_LENGTH] characters, none of them a control
     * character. The apps make it from the record's natural key ([SyncProtocol.recordId]).
     */
    fun isValidRecordId(recordId: String): Boolean =
        recordId.length in 1..MAX_RECORD_ID_LENGTH && recordId.none { it.isISOControl() }

    /** The kind of a profile's record: its tombstone deletes the profile's tracker connections on the server. */
    const val PROFILE_RECORD: String = "profile"

    /**
     * The apps' record id of a key: [parts] (the record's kind, then the key's parts) joined by `/`, each part with `%`
     * and `/` percent-encoded (`library/<profile id>/tt0111161`), so no two keys share an id whatever characters they
     * hold.
     */
    fun recordId(parts: List<String>): String = parts.joinToString("/") { it.replace("%", "%25").replace("/", "%2F") }

    /** The profile id of a profile's record id ([recordId] of [PROFILE_RECORD] and the id), or null for any other id. */
    fun profileIdOf(recordId: String): String? {
        val encoded = recordId.removePrefix("$PROFILE_RECORD/")
        if (encoded.length == recordId.length || encoded.isEmpty() || '/' in encoded) return null
        return encoded.replace("%2F", "/").replace("%25", "%")
    }

    /**
     * The JSON configuration both sides use: unknown keys are ignored (forward compatibility), defaults are encoded
     * and `null` fields are omitted.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Every endpoint, relative to the server's base URL. The KDoc of each request/response type names its endpoint. */
object SyncPaths {
    /** `GET`, public: [ServerInfo]. */
    const val INFO: String = "/v2/info"

    /** `POST`, public, no body: [NonceResponse]. */
    const val AUTH_NONCE: String = "/v2/auth/nonce"

    /** `POST`, public: [SignInRequest] → [SignInResponse]. */
    const val AUTH_SIGN_IN: String = "/v2/auth/signin"

    /** `POST`, public: [RefreshRequest] → [SessionTokens]. */
    const val AUTH_REFRESH: String = "/v2/auth/refresh"

    /** `POST`, authenticated, no body → 204: ends this device's session. */
    const val AUTH_SIGN_OUT: String = "/v2/auth/signout"

    /** `POST`, public: [HandoffStartRequest] → [HandoffStartResponse], a Sign in with Apple web flow's `state`. */
    const val APPLE_START: String = "/v2/auth/apple/start"

    /**
     * `POST`, public, `application/x-www-form-urlencoded`: the Sign in with Apple web-flow return page (`redirect_uri`
     * with `response_mode=form_post`). Served only where [ServerInfo.appleWebReturn] is true. See [HandoffStartRequest].
     */
    const val APPLE_RETURN: String = "/v2/auth/apple/return"

    /** `POST`, public: [HandoffCollectRequest] → [AppleHandoff]. */
    const val APPLE_HANDOFF: String = "/v2/auth/apple/handoff"

    /** `POST`, public: [HandoffStartRequest] → [HandoffStartResponse], a GitHub web flow's `state`. */
    const val GITHUB_START: String = "/v2/auth/github/start"

    /**
     * `GET`, public: the GitHub web-flow return page, the GitHub OAuth App's callback URL (`?code=…&state=…`, or
     * `?error=access_denied&state=…`). Served where [ServerInfo.providers] lists [IdentityProvider.GITHUB]. See
     * [HandoffStartRequest].
     */
    const val GITHUB_RETURN: String = "/v2/auth/github/return"

    /** `POST`, public: [HandoffCollectRequest] → [GitHubHandoff]. */
    const val GITHUB_HANDOFF: String = "/v2/auth/github/handoff"

    /**
     * The one [HandoffStartRequest.redirectUri] that isn't a loopback address: where Apple's web authentication session
     * (iPhone, iPad, Mac) catches the return page's redirect back to the app.
     */
    const val HANDOFF_APP_RETURN: String = "tortuga://signin"

    /**
     * Query names of the redirect a web flow's return page sends the browser to ([HandoffStartRequest.redirectUri]):
     * the flow's `state` always, then its `proof` (for [HandoffCollectRequest.proof]) or, when the provider or server
     * refused, an OAuth 2.0 `error` code. They are OAuth's own names, so one receiver reads providers' and ours.
     */
    const val RETURN_STATE: String = "state"
    const val RETURN_PROOF: String = "proof"
    const val RETURN_ERROR: String = "error"

    /** `GET`, authenticated: [AccountInfo]. `DELETE`, authenticated: [AccountDeletionResponse]. */
    const val ACCOUNT: String = "/v2/account"

    /**
     * `POST`, authenticated: [LinkIdentityRequest] → [AccountInfo]. `DELETE $ACCOUNT_IDENTITIES/{identityId}`,
     * authenticated → [AccountInfo].
     */
    const val ACCOUNT_IDENTITIES: String = "/v2/account/identities"

    /** `GET`, authenticated: [DeviceList]. `DELETE $DEVICES/{deviceId}`, authenticated → 204. */
    const val DEVICES: String = "/v2/devices"

    /** `POST`, public: [LinkStartRequest] → [LinkStartResponse]. */
    const val LINK_START: String = "/v2/link/start"

    /** `POST`, authenticated: [LinkCodeRequest] → [LinkClaimResponse]. */
    const val LINK_CLAIM: String = "/v2/link/claim"

    /** `POST`, authenticated: [LinkCodeRequest] → 204. */
    const val LINK_APPROVE: String = "/v2/link/approve"

    /** `POST`, authenticated: [LinkCodeRequest] → 204. */
    const val LINK_REJECT: String = "/v2/link/reject"

    /** `POST`, public: [LinkPollRequest] → [LinkPollResponse]. */
    const val LINK_POLL: String = "/v2/link/poll"

    /** `GET ?[PULL_SINCE]=<cursor>&[PULL_LIMIT]=<n>`, authenticated: [PullResponse]. */
    const val SYNC_PULL: String = "/v2/sync/pull"

    /** [SYNC_PULL]'s query names: the cursor to pull after, and the most records to return. */
    const val PULL_SINCE: String = "since"
    const val PULL_LIMIT: String = "limit"

    /** `POST`, authenticated: [PushRequest] → [PushResponse]. */
    const val SYNC_PUSH: String = "/v2/sync/push"

    /**
     * `GET`, authenticated, `Accept: text/event-stream`: the account's change notices, a stream held open while an app
     * is in front so it pulls as soon as another device changed something. The answer is
     * `200 text/event-stream` (`Cache-Control: no-store, no-transform`, `X-Accel-Buffering: no`) carrying:
     * - `event: `[EVENT_CHANGED] with `data: <the account's version after the change>` whenever anything a pull would return
     *   differently changed through another session: a push, a tracker connection. The version may equal the
     *   client's cursor (a tracker change moves no record), so every notice is worth one pull.
     * - a comment line (`:`) every [SyncProtocol.EVENTS_KEEPALIVE_MILLIS], so idle streams stay open through proxies.
     *
     * The server may end the stream at any time (a restart, a client that doesn't read); the client opens it again
     * after a backoff. 401 as everywhere ([ApiErrorCode.TOKEN_EXPIRED] or [ApiErrorCode.UNAUTHORIZED]; a revoked
     * session's open stream is closed), 503 with `Retry-After` when the server is busy or holds as many streams as it
     * takes. 404 from a server that predates the stream: the client keeps to its periodic pulls.
     */
    const val SYNC_EVENTS: String = "/v2/sync/events"

    /** The event name of a [SYNC_EVENTS] change notice. */
    const val EVENT_CHANGED: String = "changed"
}
