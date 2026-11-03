package com.edde746.tortuga.sync.account

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.Backoff
import com.edde746.tortuga.net.readBody
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.AppleHandoff
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.GitHubHandoff
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.HandoffStartResponse
import com.edde746.tortuga.sync.api.LinkClaimResponse
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.LinkIdentityRequest
import com.edde746.tortuga.sync.api.LinkPollRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.LinkStartResponse
import com.edde746.tortuga.sync.api.NonceResponse
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RefreshRequest
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SessionTokens
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.connections.TrackerAccessToken
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionList
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import com.edde746.tortuga.trackers.connections.TrackerConnectionsApi
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.parseUrl
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLineStrict
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * The sync protocol's endpoints ([SyncPaths]) and the tracker connections' ([TrackerConnectionPaths]) over the app's
 * [HttpClient], JSON through [SyncProtocol.json].
 * Stateless: the caller passes the server URL and, for authenticated endpoints, the access token ([SessionManager]
 * refreshes it).
 *
 * Failures are [AccountException]s: [AccountError.Network] when no answer arrived, otherwise the error the answer's
 * [ApiError.code] names. [ApiErrorCode.TOKEN_EXPIRED] is a [TokenExpiredException] for the session layer. A 429, or a
 * 503 with `Retry-After` (the server too busy to take the request), is retried once when its `Retry-After` is at most
 * [maxRateLimitWaitMillis], after a random wait from that up to twice as long (a 429 without one, from 0 up to
 * [DEFAULT_RETRY_AFTER_MILLIS]), so the clients one limit stopped don't return together; otherwise it becomes
 * [AccountError.RateLimited] carrying the server's wait, which callers honour before trying again. The notice stream
 * ([events]) is never retried here.
 */
internal class SyncApi(
    private val http: HttpClient,
    private val maxRateLimitWaitMillis: Long = 30_000,
) {
    private val json = SyncProtocol.json

    /**
     * The server's [ServerInfo]. An answer that isn't one (a 404, another body, any other error) is
     * [AccountError.NotTortugaServer]; no answer, a server error and a rate limit stay what they are.
     */
    suspend fun info(server: String): ServerInfo = try {
        exchange(HttpMethod.Get, server + SyncPaths.INFO, null, null, ServerInfo.serializer())
    } catch (e: AccountException) {
        when (e.error) {
            AccountError.Network, is AccountError.Server, is AccountError.RateLimited -> throw e
            else -> throw AccountException(AccountError.NotTortugaServer, e)
        }
    }

    suspend fun nonce(server: String): NonceResponse =
        exchange(HttpMethod.Post, server + SyncPaths.AUTH_NONCE, null, null, NonceResponse.serializer())

    suspend fun signIn(server: String, request: SignInRequest): SignInResponse =
        post(server + SyncPaths.AUTH_SIGN_IN, null, SignInRequest.serializer(), request, SignInResponse.serializer())

    suspend fun refresh(server: String, refreshToken: String): SessionTokens =
        post(server + SyncPaths.AUTH_REFRESH, null, RefreshRequest.serializer(), RefreshRequest(refreshToken), SessionTokens.serializer())

    suspend fun signOut(server: String, token: String) {
        exchangeUnit(HttpMethod.Post, server + SyncPaths.AUTH_SIGN_OUT, token, null)
    }

    /** Starts a Sign in with Apple web flow ([HandoffStartRequest]): the `state` for Apple's authorize URL. */
    suspend fun appleStart(server: String, request: HandoffStartRequest): HandoffStartResponse =
        post(server + SyncPaths.APPLE_START, null, HandoffStartRequest.serializer(), request, HandoffStartResponse.serializer())

    /** The Apple web flow's result, once; [AccountError.SignInExpired] when the server has none for [request] (a 404). */
    suspend fun appleHandoff(server: String, request: HandoffCollectRequest): AppleHandoff = try {
        post(server + SyncPaths.APPLE_HANDOFF, null, HandoffCollectRequest.serializer(), request, AppleHandoff.serializer())
    } catch (e: AccountException) {
        if (e.error == AccountError.NotFound) throw AccountException(AccountError.SignInExpired, e) else throw e
    }

    /** Starts a GitHub web flow ([HandoffStartRequest]): the `state` for GitHub's authorize URL. */
    suspend fun githubStart(server: String, request: HandoffStartRequest): HandoffStartResponse =
        post(server + SyncPaths.GITHUB_START, null, HandoffStartRequest.serializer(), request, HandoffStartResponse.serializer())

    /** The GitHub web flow's result, once; [AccountError.SignInExpired] when the server has none for [request] (a 404). */
    suspend fun githubHandoff(server: String, request: HandoffCollectRequest): GitHubHandoff = try {
        post(server + SyncPaths.GITHUB_HANDOFF, null, HandoffCollectRequest.serializer(), request, GitHubHandoff.serializer())
    } catch (e: AccountException) {
        if (e.error == AccountError.NotFound) throw AccountException(AccountError.SignInExpired, e) else throw e
    }

    suspend fun account(server: String, token: String): AccountInfo =
        exchange(HttpMethod.Get, server + SyncPaths.ACCOUNT, token, null, AccountInfo.serializer())

    suspend fun deleteAccount(server: String, token: String): AccountDeletionResponse =
        exchange(HttpMethod.Delete, server + SyncPaths.ACCOUNT, token, null, AccountDeletionResponse.serializer())

    suspend fun linkIdentity(server: String, token: String, request: LinkIdentityRequest): AccountInfo =
        post(server + SyncPaths.ACCOUNT_IDENTITIES, token, LinkIdentityRequest.serializer(), request, AccountInfo.serializer())

    suspend fun unlinkIdentity(server: String, token: String, identityId: String): AccountInfo =
        exchange(HttpMethod.Delete, "$server${SyncPaths.ACCOUNT_IDENTITIES}/${identityId.encodeURLPathPart()}", token, null, AccountInfo.serializer())

    suspend fun devices(server: String, token: String): DeviceList =
        exchange(HttpMethod.Get, server + SyncPaths.DEVICES, token, null, DeviceList.serializer())

    suspend fun revokeDevice(server: String, token: String, deviceId: String) {
        exchangeUnit(HttpMethod.Delete, "$server${SyncPaths.DEVICES}/${deviceId.encodeURLPathPart()}", token, null)
    }

    suspend fun linkStart(server: String, request: LinkStartRequest): LinkStartResponse =
        post(server + SyncPaths.LINK_START, null, LinkStartRequest.serializer(), request, LinkStartResponse.serializer())

    suspend fun linkClaim(server: String, token: String, code: String): LinkClaimResponse =
        post(server + SyncPaths.LINK_CLAIM, token, LinkCodeRequest.serializer(), LinkCodeRequest(code), LinkClaimResponse.serializer())

    suspend fun linkApprove(server: String, token: String, code: String) {
        exchangeUnit(HttpMethod.Post, server + SyncPaths.LINK_APPROVE, token, encode(LinkCodeRequest.serializer(), LinkCodeRequest(code)))
    }

    suspend fun linkReject(server: String, token: String, code: String) {
        exchangeUnit(HttpMethod.Post, server + SyncPaths.LINK_REJECT, token, encode(LinkCodeRequest.serializer(), LinkCodeRequest(code)))
    }

    suspend fun linkPoll(server: String, request: LinkPollRequest): LinkPollResponse =
        post(server + SyncPaths.LINK_POLL, null, LinkPollRequest.serializer(), request, LinkPollResponse.serializer())

    suspend fun pull(server: String, token: String, since: Long, limit: Int): PullResponse =
        exchange(
            HttpMethod.Get,
            "$server${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=$since&${SyncPaths.PULL_LIMIT}=$limit",
            token,
            null,
            PullResponse.serializer(),
        )

    /** [body] is an encoded [com.edde746.tortuga.sync.api.PushRequest]: the engine sizes batches by it. */
    suspend fun push(server: String, token: String, body: String): PushResponse =
        exchange(HttpMethod.Post, server + SyncPaths.SYNC_PUSH, token, body, PushResponse.serializer())

    suspend fun trackerConnections(server: String, token: String): TrackerConnectionList =
        exchange(HttpMethod.Get, server + TrackerConnectionPaths.CONNECTIONS, token, null, TrackerConnectionList.serializer())

    suspend fun connectTracker(server: String, token: String, request: TrackerConnectRequest): TrackerConnection =
        post(server + TrackerConnectionPaths.CONNECTIONS, token, TrackerConnectRequest.serializer(), request, TrackerConnection.serializer())

    /** The connection's access token, or null when the server no longer has the connection (a 410). */
    suspend fun trackerToken(server: String, token: String, request: TrackerTokenRequest): TrackerAccessToken? = try {
        post(server + TrackerConnectionPaths.TOKEN, token, TrackerTokenRequest.serializer(), request, TrackerAccessToken.serializer())
    } catch (e: AccountException) {
        if (e.error == AccountError.Unexpected(410, TrackerConnectionsApi.DISCONNECTED)) null else throw e
    }

    suspend fun disconnectTracker(server: String, token: String, profileId: String, service: String) {
        exchangeUnit(HttpMethod.Delete, server + TrackerConnectionPaths.connection(profileId, service), token, null)
    }

    /**
     * Holds [SyncPaths.SYNC_EVENTS] open: [onOpen] once the server took the stream, then [onChanged] for each `changed`
     * notice, until the server ends it (a normal return). No answer, a connection that drops or a stream silent for
     * [EVENTS_SOCKET_TIMEOUT_MILLIS] is [AccountError.Network]; a 429, or a 503 with `Retry-After`, is
     * [AccountError.RateLimited] at once; any other refusal fails as every call does (a 404: a server without the
     * stream).
     */
    suspend fun events(server: String, token: String, onOpen: () -> Unit, onChanged: () -> Unit) {
        val url = server + SyncPaths.SYNC_EVENTS
        val refused = try {
            http.prepareGet(url) {
                expectSuccess = false
                // JSON as the second choice, so the error answers of any server come as an ApiError.
                header(HttpHeaders.Accept, EVENTS_ACCEPT)
                header(HttpHeaders.Authorization, "Bearer $token")
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = EVENTS_SOCKET_TIMEOUT_MILLIS
                }
            }.execute { response ->
                val status = response.status
                if (!status.isSuccess() || response.contentType()?.match(ContentType.Text.EventStream) != true) {
                    return@execute Answer(status, response.bodyAsText(), parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
                }
                onOpen()
                response.readBody { readEvents(it, onChanged) }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG) { "GET ${path(url)} failed: ${e::class.simpleName}" }
            throw AccountException(AccountError.Network, e)
        }
        if (refused == null) return
        val error = decodeError(refused.text)
        askedWait(refused, error)?.let { throw AccountException(AccountError.RateLimited(it)) }
        throw failure(HttpMethod.Get, url, refused.status, error)
    }

    /** Reads the stream's lines until the server ends it; only `changed` events matter, comments are keepalives. */
    private suspend fun readEvents(channel: ByteReadChannel, onChanged: () -> Unit) {
        var event = ""
        while (true) {
            val line = channel.readLineStrict(MAX_EVENT_LINE_BYTES, LineEnding.Lenient) ?: return
            if (line.isEmpty()) {
                if (event == SyncPaths.EVENT_CHANGED) onChanged()
                event = ""
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length).removePrefix(" ")
            }
        }
    }

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): String = json.encodeToString(serializer, value)

    private suspend fun <Q, R> post(
        url: String,
        token: String?,
        requestSerializer: SerializationStrategy<Q>,
        request: Q,
        response: DeserializationStrategy<R>,
    ): R = exchange(HttpMethod.Post, url, token, encode(requestSerializer, request), response)

    private suspend fun exchangeUnit(method: HttpMethod, url: String, token: String?, body: String?) {
        send<Unit>(method, url, token, body, null)
    }

    private suspend fun <R> exchange(
        method: HttpMethod,
        url: String,
        token: String?,
        body: String?,
        response: DeserializationStrategy<R>,
    ): R = send(method, url, token, body, response)!!

    /** Sends the request and decodes the answer with [response], or returns null for a null [response]. */
    private suspend fun <R> send(
        method: HttpMethod,
        url: String,
        token: String?,
        body: String?,
        response: DeserializationStrategy<R>?,
    ): R? {
        var rateLimitRetried = false
        while (true) {
            val answer = transmit(method, url, token, body)
            if (answer.status.isSuccess()) {
                if (response == null) return null
                return try {
                    json.decodeFromString(response, answer.text)
                } catch (e: IllegalArgumentException) {
                    // SerializationException is an IllegalArgumentException.
                    throw AccountException(AccountError.Unexpected(answer.status.value, MALFORMED_RESPONSE), e)
                }
            }
            val error = decodeError(answer.text)
            val asked = askedWait(answer, error) ?: throw failure(method, url, answer.status, error)
            if (rateLimitRetried || asked > maxRateLimitWaitMillis) throw AccountException(AccountError.RateLimited(asked))
            rateLimitRetried = true
            val wait = if (answer.retryAfterMillis != null) {
                Backoff.afterRetryAfter(answer.retryAfterMillis)
            } else {
                Backoff.fullJitter(1, DEFAULT_RETRY_AFTER_MILLIS, DEFAULT_RETRY_AFTER_MILLIS)
            }
            Log.i(TAG) { "${answer.status.value} on ${method.value} ${path(url)}; retrying in $wait ms" }
            delay(wait)
        }
    }

    private fun decodeError(text: String): ApiError? = try {
        json.decodeFromString(ApiError.serializer(), text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * The wait the server asked for when [answer] is a rate limit (a 429 or [ApiErrorCode.RATE_LIMITED], its
     * `Retry-After` or [DEFAULT_RETRY_AFTER_MILLIS]) or a busy server (a 503 with `Retry-After`); null for any other
     * failure.
     */
    private fun askedWait(answer: Answer, error: ApiError?): Long? {
        val busy = answer.status == HttpStatusCode.ServiceUnavailable && answer.retryAfterMillis != null
        val limited = answer.status == HttpStatusCode.TooManyRequests || error?.code == ApiErrorCode.RATE_LIMITED
        return if (busy || limited) answer.retryAfterMillis ?: DEFAULT_RETRY_AFTER_MILLIS else null
    }

    /** The failure an answer with [status] and [error] is: a [TokenExpiredException] or an [AccountException]. */
    private fun failure(method: HttpMethod, url: String, status: HttpStatusCode, error: ApiError?): Exception {
        if (error?.code == ApiErrorCode.TOKEN_EXPIRED) return TokenExpiredException()
        Log.i(TAG) { "${method.value} ${path(url)} → ${status.value} ${error?.code.orEmpty()}" }
        return AccountException(errorFor(status.value, error))
    }

    private suspend fun transmit(method: HttpMethod, url: String, token: String?, body: String?): Answer = try {
        val response = http.request(url) {
            this.method = method
            expectSuccess = false
            accept(ContentType.Application.Json)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        Answer(response.status, response.bodyAsText(), parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG) { "${method.value} ${path(url)} failed: ${e::class.simpleName}" }
        throw AccountException(AccountError.Network, e)
    }

    private data class Answer(val status: HttpStatusCode, val text: String, val retryAfterMillis: Long?)

    companion object {
        private const val TAG = "SyncApi"
        private const val MALFORMED_RESPONSE = "malformed_response"

        /** When a 429 carries no usable `Retry-After`. */
        const val DEFAULT_RETRY_AFTER_MILLIS: Long = 5_000

        /** Notices first; JSON for the error answers. */
        private const val EVENTS_ACCEPT = "text/event-stream, application/json;q=0.5"

        /** A notice stream silent through two keepalives and a margin is dead. */
        const val EVENTS_SOCKET_TIMEOUT_MILLIS: Long = 2 * SyncProtocol.EVENTS_KEEPALIVE_MILLIS + 15_000

        /** The stream's lines are tens of bytes; a longer one isn't the protocol. */
        private const val MAX_EVENT_LINE_BYTES = 4_096L

        /** `Retry-After` in delta-seconds; an HTTP date (which the server doesn't send) counts as absent. */
        internal fun parseRetryAfter(value: String?): Long? =
            value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }

        /** Only the path, for logs: queries carry cursors, never secrets, but keep log lines uniform. */
        private fun path(url: String) = parseUrl(url)?.encodedPath ?: "?"

        /**
         * The error an answer with [status] and [error] is. Only the server's own codes end the session
         * ([ApiErrorCode.UNAUTHORIZED], [ApiErrorCode.INVALID_REFRESH_TOKEN]): a 401 without one (a login proxy in front
         * of a self-hosted server) is [AccountError.Unexpected], which leaves the session as it is.
         */
        internal fun errorFor(status: Int, error: ApiError?): AccountError = when (error?.code) {
            ApiErrorCode.INVALID_REQUEST -> AccountError.InvalidRequest(error.message)
            ApiErrorCode.UNKNOWN_BASE_VERSION -> AccountError.UnknownBaseVersion
            ApiErrorCode.UNAUTHORIZED, ApiErrorCode.INVALID_REFRESH_TOKEN -> AccountError.SessionEnded
            ApiErrorCode.INVALID_ID_TOKEN, ApiErrorCode.APPLE_CODE_REQUIRED -> AccountError.SignInRejected
            ApiErrorCode.PROVIDER_NOT_ENABLED -> AccountError.ProviderNotEnabled
            ApiErrorCode.APPLE_CODE_REJECTED -> AccountError.AppleCodeRejected
            ApiErrorCode.UPSTREAM_UNAVAILABLE -> AccountError.ProviderUnavailable
            ApiErrorCode.IDENTITY_IN_USE -> AccountError.IdentityInUse
            ApiErrorCode.LAST_IDENTITY -> AccountError.LastIdentity
            ApiErrorCode.NOT_FOUND -> AccountError.NotFound
            ApiErrorCode.PAYLOAD_TOO_LARGE -> AccountError.PayloadTooLarge
            ApiErrorCode.QUOTA_EXCEEDED -> AccountError.QuotaExceeded
            ApiErrorCode.INTERNAL -> AccountError.Server(status)
            else -> when {
                status >= 500 -> AccountError.Server(status)
                status == 404 -> AccountError.NotFound
                status == 413 -> AccountError.PayloadTooLarge
                else -> AccountError.Unexpected(status, error?.code ?: "")
            }
        }
    }
}

/** [ApiErrorCode.TOKEN_EXPIRED]: the session layer refreshes and retries; it never reaches the app. */
internal class TokenExpiredException : Exception("Access token expired")
