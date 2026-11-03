package com.edde746.tortuga.sync

import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.signin.OAuth
import com.edde746.tortuga.sync.api.AcceptedWrite
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DeviceInfo
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.IdentityInfo
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.LINK_CODE_ALPHABET
import com.edde746.tortuga.sync.api.LinkClaimResponse
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.LinkIdentityRequest
import com.edde746.tortuga.sync.api.LinkPollRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.LinkStartResponse
import com.edde746.tortuga.sync.api.LinkStatus
import com.edde746.tortuga.sync.api.NonceResponse
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordChange
import com.edde746.tortuga.sync.api.RefreshRequest
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SessionTokens
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.sync.api.normalizeLinkCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer

/**
 * An in-memory stand-in for the sync server (`server/`), speaking protocol v2 over Ktor's [MockEngine]: accounts
 * keyed by ID token ("the token is the subject"), sessions with rotating tokens, device links that mint the new
 * device's session, versioned records with optimistic concurrency, notice streams that a push tells, and account
 * deletion. Tests steer it with [down], [expireAccessTokens], [rateLimitNext], [refusePushes], [beforeNextPush],
 * [beforeNextPull], [refuseEvents], [endEventsAtOnce], [endStreams], [changed] and [snapshot]/[restore].
 */
internal class FakeSyncServer {
    /** The server's clock; [TestDevice] points it at the test's virtual time. */
    var clock: () -> Long = { NOW }

    val url = "https://sync.test"

    /** [url] as the apps hold it. */
    val serverUrl: ServerUrl = ServerUrl.parse(url)

    /** What [SyncPaths.INFO] answers; tests swap in other providers. */
    var serverInfo: ServerInfo = ServerInfo(
        SyncProtocol.VERSION, listOf(ProviderInfo(IdentityProvider.OIDC, "https://issuer.test", listOf("tortuga-test"), "Test")), false, false, 1L shl 26,
    )
    private val json = SyncProtocol.json

    class Account(val id: String, val subject: String) {
        var version = 0L
        val records = LinkedHashMap<String, RecordChange>()

        /** The sign-ins of the account; the first is the one it was created with. */
        val identities = mutableListOf(Identity(IdentityInfo("identity-$id", IdentityProvider.OIDC, null, 0), subject))
    }

    /** A linked sign-in and the subject its "ID token" names. */
    class Identity(val info: IdentityInfo, val subject: String)

    /** A device's session; [createdAt] by the server's clock is its first and, here, its last activity too. */
    class Session(
        val accountId: String,
        val deviceId: String,
        val device: DeviceDescriptor,
        val createdAt: Long,
        var accessToken: String,
        var refreshToken: String,
        var previousRefreshToken: String? = null,
        var accessExpired: Boolean = false,
    )

    class Link(
        val id: String,
        val code: String,
        val secretHash: String,
        val device: DeviceDescriptor,
        val expiresAt: Long,
        var approvedAccountId: String? = null,
        var rejected: Boolean = false,
    )

    val accounts = LinkedHashMap<String, Account>()
    val sessions = ArrayList<Session>()
    val links = ArrayList<Link>()
    private var counter = 0

    /** Every request as `METHOD path`, in order. */
    val requests = ArrayList<String>()
    val pushes = ArrayList<PushRequest>()
    var refreshes = 0

    /** Every request fails as if the network were down. */
    var down = false

    /** Answer the next N requests with 429 and this `Retry-After` (seconds). */
    var rateLimitNext = 0
    var rateLimitSeconds = 1L

    /**
     * While set, every push is refused with this code: 413 for [ApiErrorCode.PAYLOAD_TOO_LARGE] and
     * [ApiErrorCode.QUOTA_EXCEEDED], 400 for [ApiErrorCode.INVALID_REQUEST] (a push the server finds malformed).
     */
    var refusePushes: String? = null

    /** Runs once when the next push arrives, before it is decided: another device's write racing it. */
    var beforeNextPush: (suspend () -> Unit)? = null

    /** Writes answered stale so far. */
    var staleAnswers = 0

    /**
     * While set, opening a notice stream is answered with this status: 404 as a server older than the stream, 503
     * with `Retry-After` [eventsRetryAfterSeconds] as a busy one.
     */
    var refuseEvents: HttpStatusCode? = null
    var eventsRetryAfterSeconds = 60L

    /** Every notice stream ends as soon as it opens (a proxy that cuts it, a server that drops it). */
    var endEventsAtOnce = false

    /** Runs once when the next pull arrives, before it is answered. */
    var beforeNextPull: (suspend () -> Unit)? = null

    /** The open notice streams and the session each belongs to. */
    private val streams = ArrayList<Pair<Session, ByteChannel>>()

    /** What account deletion reports about Sign in with Apple, one of [AppleRevocation]. */
    var appleRevocation: String = AppleRevocation.NONE

    /** What account deletion reports about the GitHub grant, one of [GitHubRevocation]. */
    var githubRevocation: String = GitHubRevocation.NONE

    /** Runs once after the next account deletion commits, before it is answered: what else reaches the server then. */
    var afterNextDeletion: (suspend () -> Unit)? = null

    /** While set, [SyncPaths.INFO] fails as if the network were down; every other request is answered. */
    var infoUnreachable = false

    /** Every sign-in request, in order: which provider, token and nonce reached the server. */
    val signIns = ArrayList<SignInRequest>()

    private fun next(prefix: String) = "$prefix-${++counter}"

    fun mockEngine(dispatcher: CoroutineDispatcher? = null) = MockEngine.create {
        dispatcher?.let { this.dispatcher = it }
        addHandler { request -> handle(request) }
    }

    fun client(dispatcher: CoroutineDispatcher? = null) = HttpClient(mockEngine(dispatcher))

    fun account(): Account = accounts.values.single()

    /** Marks every access token expired: the next authenticated call gets `token_expired`. */
    fun expireAccessTokens() = sessions.forEach { it.accessExpired = true }

    /** A deep copy of the account's records and version, for [restore] (a backup). */
    fun snapshot(): Pair<Long, Map<String, RecordChange>> = account().let { it.version to LinkedHashMap(it.records) }

    /** Puts the account's records back to a [snapshot], as a restore from a backup does. */
    fun restore(snapshot: Pair<Long, Map<String, RecordChange>>) {
        val account = account()
        account.version = snapshot.first
        account.records.clear()
        account.records.putAll(snapshot.second)
    }

    /** The accounts of the notice streams the clients still hold open, one entry per stream. */
    fun openStreams(): List<String> = streams.filter { !it.second.isClosedForWrite }.map { it.first.accountId }

    /** Ends every notice stream, as a restart does. */
    suspend fun endStreams() {
        streams.forEach { it.second.flushAndClose() }
        streams.clear()
    }

    /** Tells the account's open notice streams, except [from]'s, that it changed. */
    suspend fun changed(account: Account = account(), from: Session? = null) {
        streams.removeAll { it.second.isClosedForWrite }
        for ((session, channel) in streams) {
            if (session.accountId != account.id || session === from) continue
            channel.writeStringUtf8("event: ${SyncPaths.EVENT_CHANGED}\ndata: ${account.version}\n\n")
            channel.flush()
        }
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        if (down) throw IOException("Network is unreachable")
        if (rateLimitNext > 0) {
            rateLimitNext--
            return respond(
                json.encodeToString(ApiError.serializer(), ApiError(ApiErrorCode.RATE_LIMITED, "slow down")),
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter to listOf(rateLimitSeconds.toString()), HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        if (path == SyncPaths.SYNC_PUSH) beforeNextPush?.let { hook ->
            beforeNextPush = null
            hook()
        }
        if (path == SyncPaths.SYNC_PULL) beforeNextPull?.let { hook ->
            beforeNextPull = null
            hook()
        }
        return try {
            route(request, path)
        } catch (e: Failure) {
            error(e.status, e.code)
        }
    }

    private class Failure(val status: HttpStatusCode, val code: String) : Exception(code)

    private fun fail(status: HttpStatusCode, code: String): Nothing = throw Failure(status, code)

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) = respond(
        json.encodeToString(ApiError.serializer(), ApiError(code, code)),
        status,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun <T> MockRequestHandleScope.ok(serializer: KSerializer<T>, value: T) =
        respond(json.encodeToString(serializer, value), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.noContent() = respond("", HttpStatusCode.NoContent)

    private fun <T> body(request: HttpRequestData, serializer: KSerializer<T>): T {
        val text = (request.body as? TextContent)?.text ?: (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            ?: fail(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        return json.decodeFromString(serializer, text)
    }

    private fun session(request: HttpRequestData): Session {
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            ?: fail(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        val session = sessions.firstOrNull { it.accessToken == token } ?: fail(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        if (session.accessExpired) fail(HttpStatusCode.Unauthorized, ApiErrorCode.TOKEN_EXPIRED)
        return session
    }

    private fun accountOf(session: Session) = accounts.getValue(session.accountId)

    private fun newSession(accountId: String, device: DeviceDescriptor): SessionTokens {
        val session = Session(accountId, next("device"), device, clock(), next("access"), next("refresh"))
        sessions += session
        return session.tokens()
    }

    private fun Session.tokens() = SessionTokens(deviceId, accessToken, ACCESS_TTL_MILLIS, refreshToken, 180L * 86_400_000)

    private fun Account.info() = AccountInfo(id, 0, identities.map { it.info })

    private suspend fun MockRequestHandleScope.route(request: HttpRequestData, path: String): HttpResponseData {
        val method = request.method
        return when {
            method == HttpMethod.Get && path == SyncPaths.INFO -> {
                if (infoUnreachable) throw IOException("Network is unreachable")
                ok(ServerInfo.serializer(), serverInfo)
            }
            method == HttpMethod.Post && path == SyncPaths.AUTH_NONCE -> ok(NonceResponse.serializer(), NonceResponse(next("nonce"), 600_000))
            method == HttpMethod.Post && path == SyncPaths.AUTH_SIGN_IN -> {
                val signIn = body(request, SignInRequest.serializer())
                signIns += signIn
                val existing = accounts.values.firstOrNull { it.subject == signIn.idToken }
                val account = existing ?: Account(next("account"), signIn.idToken).also { accounts[it.id] = it }
                ok(SignInResponse.serializer(), SignInResponse(existing == null, account.info(), newSession(account.id, signIn.device)))
            }
            method == HttpMethod.Post && path == SyncPaths.AUTH_REFRESH -> {
                refreshes++
                val refresh = body(request, RefreshRequest.serializer()).refreshToken
                val session = sessions.firstOrNull { it.refreshToken == refresh || it.previousRefreshToken == refresh }
                    ?: fail(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
                // As the server's: the previous token keeps working until the current one is used.
                if (session.refreshToken == refresh) session.previousRefreshToken = session.refreshToken
                session.refreshToken = next("refresh")
                session.accessToken = next("access")
                session.accessExpired = false
                ok(SessionTokens.serializer(), session.tokens())
            }
            method == HttpMethod.Post && path == SyncPaths.AUTH_SIGN_OUT -> {
                sessions.remove(session(request))
                noContent()
            }
            path == SyncPaths.ACCOUNT && method == HttpMethod.Get -> ok(AccountInfo.serializer(), accountOf(session(request)).info())
            path == SyncPaths.ACCOUNT && method == HttpMethod.Delete -> {
                val account = accountOf(session(request))
                accounts.remove(account.id)
                sessions.removeAll { it.accountId == account.id }
                links.removeAll { it.approvedAccountId == account.id }
                afterNextDeletion?.let { hook ->
                    afterNextDeletion = null
                    hook()
                }
                ok(AccountDeletionResponse.serializer(), AccountDeletionResponse(appleRevocation, githubRevocation))
            }
            path == SyncPaths.DEVICES && method == HttpMethod.Get -> {
                val session = session(request)
                ok(
                    DeviceList.serializer(),
                    DeviceList(
                        sessions.filter { it.accountId == session.accountId }
                            .map { DeviceInfo(it.deviceId, it.device.name, it.device.platform, it.createdAt, it.createdAt, it === session) },
                    ),
                )
            }
            path.startsWith(SyncPaths.DEVICES + "/") && method == HttpMethod.Delete -> {
                val session = session(request)
                val id = path.substringAfterLast('/')
                if (!sessions.removeAll { it.accountId == session.accountId && it.deviceId == id }) fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
                noContent()
            }
            path == SyncPaths.LINK_START -> {
                val start = body(request, LinkStartRequest.serializer())
                // Unique per server run, from the alphabet like the real server's random codes.
                val serial = ++counter
                val code = "ABCDEF" + LINK_CODE_ALPHABET[(serial / 32) % 32] + LINK_CODE_ALPHABET[serial % 32]
                val link = Link(next("link"), code, start.secretHash, start.device, clock() + LINK_TTL_MILLIS)
                links += link
                ok(LinkStartResponse.serializer(), LinkStartResponse(link.id, code, LINK_TTL_MILLIS))
            }
            path == SyncPaths.LINK_CLAIM -> {
                session(request)
                val link = openLink(body(request, LinkCodeRequest.serializer()).code)
                ok(LinkClaimResponse.serializer(), LinkClaimResponse(link.device, link.expiresAt - clock()))
            }
            path == SyncPaths.LINK_APPROVE -> {
                val session = session(request)
                openLink(body(request, LinkCodeRequest.serializer()).code).approvedAccountId = session.accountId
                noContent()
            }
            path == SyncPaths.LINK_REJECT -> {
                session(request)
                openLink(body(request, LinkCodeRequest.serializer()).code).rejected = true
                noContent()
            }
            path == SyncPaths.LINK_POLL -> {
                val poll = body(request, LinkPollRequest.serializer())
                val link = links.firstOrNull {
                    it.id == poll.linkId && it.secretHash == OAuth.sha256Base64Url(poll.secret) && it.expiresAt > clock()
                } ?: fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
                when {
                    link.rejected -> {
                        links.remove(link)
                        ok(LinkPollResponse.serializer(), LinkPollResponse(LinkStatus.REJECTED))
                    }
                    link.approvedAccountId != null -> {
                        links.remove(link)
                        val accountId = link.approvedAccountId!!
                        ok(LinkPollResponse.serializer(), LinkPollResponse(LinkStatus.APPROVED, accountId, newSession(accountId, link.device)))
                    }
                    else -> ok(LinkPollResponse.serializer(), LinkPollResponse(LinkStatus.PENDING))
                }
            }
            path == SyncPaths.SYNC_PULL -> {
                val account = accountOf(session(request))
                val since = request.url.parameters[SyncPaths.PULL_SINCE]?.toLong() ?: 0
                val limit = request.url.parameters[SyncPaths.PULL_LIMIT]?.toInt() ?: SyncProtocol.DEFAULT_PULL_LIMIT
                val newer = account.records.values.filter { it.version > since }.sortedBy { it.version }
                val page = newer.take(limit)
                ok(
                    PullResponse.serializer(),
                    PullResponse(page, page.lastOrNull()?.version ?: since, newer.size > limit, account.version),
                )
            }
            path == SyncPaths.SYNC_PUSH -> {
                val account = accountOf(session(request))
                val push = body(request, PushRequest.serializer())
                pushes += push
                refusePushes?.let { fail(if (it == ApiErrorCode.INVALID_REQUEST) HttpStatusCode.BadRequest else HttpStatusCode.PayloadTooLarge, it) }
                if (push.writes.size > SyncProtocol.MAX_WRITES_PER_PUSH) fail(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
                if (push.writes.map { it.recordId }.toSet().size != push.writes.size) fail(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
                if (push.writes.any { !SyncProtocol.isValidRecordId(it.recordId) }) fail(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
                if (push.writes.any { it.baseVersion != 0L && account.records[it.recordId] == null }) {
                    fail(HttpStatusCode.Conflict, ApiErrorCode.UNKNOWN_BASE_VERSION)
                }
                val accepted = ArrayList<AcceptedWrite>()
                val stale = ArrayList<RecordChange>()
                for (write in push.writes) {
                    val current = account.records[write.recordId]
                    if ((current?.version ?: 0L) != write.baseVersion) {
                        staleAnswers++
                        stale += current!!
                        continue
                    }
                    account.version++
                    account.records[write.recordId] = RecordChange(write.recordId, account.version, write.deleted, write.payload, clock())
                    accepted += AcceptedWrite(write.recordId, account.version)
                }
                if (accepted.isNotEmpty()) changed(account, from = session(request))
                ok(PushResponse.serializer(), PushResponse(accepted, stale))
            }
            path == SyncPaths.SYNC_EVENTS -> {
                session(request)
                refuseEvents?.let { status ->
                    val code = if (status == HttpStatusCode.NotFound) ApiErrorCode.NOT_FOUND else ApiErrorCode.SERVER_BUSY
                    return respond(
                        json.encodeToString(ApiError.serializer(), ApiError(code, code)),
                        status,
                        headersOf(HttpHeaders.RetryAfter to listOf(eventsRetryAfterSeconds.toString()), HttpHeaders.ContentType to listOf("application/json")),
                    )
                }
                val channel = ByteChannel()
                if (endEventsAtOnce) channel.flushAndClose() else streams += session(request) to channel
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            path == SyncPaths.ACCOUNT_IDENTITIES && method == HttpMethod.Post -> {
                val account = accountOf(session(request))
                val link = body(request, LinkIdentityRequest.serializer())
                val inUse = accounts.values.any { other ->
                    other.identities.any { it.subject == link.idToken && it.info.provider == link.provider }
                }
                if (inUse) fail(HttpStatusCode.Conflict, ApiErrorCode.IDENTITY_IN_USE)
                account.identities += Identity(IdentityInfo(next("identity"), link.provider, "${link.idToken}@example.com", clock()), link.idToken)
                ok(AccountInfo.serializer(), account.info())
            }
            path.startsWith(SyncPaths.ACCOUNT_IDENTITIES + "/") && method == HttpMethod.Delete -> {
                val account = accountOf(session(request))
                val id = path.substringAfterLast('/')
                val identity = account.identities.firstOrNull { it.info.identityId == id } ?: fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
                if (account.identities.size == 1) fail(HttpStatusCode.Conflict, ApiErrorCode.LAST_IDENTITY)
                account.identities.remove(identity)
                ok(AccountInfo.serializer(), account.info())
            }
            else -> fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        }
    }

    private fun openLink(code: String): Link {
        val normalized = normalizeLinkCode(code) ?: fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        return links.firstOrNull {
            it.code == normalized && it.approvedAccountId == null && !it.rejected && it.expiresAt > clock()
        } ?: fail(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    companion object {
        const val NOW = 1_790_000_000_000L

        /** Lifetimes the fake grants, as the real server's defaults. */
        const val ACCESS_TTL_MILLIS = 3_600_000L
        const val LINK_TTL_MILLIS = 600_000L
    }
}
