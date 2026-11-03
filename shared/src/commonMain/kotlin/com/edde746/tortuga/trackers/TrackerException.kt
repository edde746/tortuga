package com.edde746.tortuga.trackers

import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException

/**
 * A tracker service's failure. Messages carry statuses and categories only, never
 * tokens, URLs with credentials or response bodies.
 */
sealed class TrackerException(val service: TrackerService, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The failure says nothing about the write itself, so retrying later is right and a queued write keeps its
     * attempts.
     */
    abstract val transient: Boolean

    /** The service answered [status] outside what the call accepts. 429 and 5xx are transient; 4xx are about the write. */
    class Api(service: TrackerService, val status: Int, val category: ApiFailureCategory? = null) :
        TrackerException(service, "${service.id}: HTTP $status" + (category?.let { ", $it" } ?: "")) {
        override val transient: Boolean get() = status == 429 || status >= 500
    }

    /** HTTP 429 from a service that says when to come back ([retryAfterSeconds], from `Retry-After`). */
    class RateLimited(service: TrackerService, val retryAfterSeconds: Long? = null) :
        TrackerException(service, "${service.id}: rate limited" + (retryAfterSeconds?.let { ", retry after $it s" } ?: "")) {
        override val transient: Boolean get() = true
    }

    /**
     * The session doesn't work. [permanent]: the service's own verdict that it is dead (a refused refresh, a 401 where
     * tokens don't refresh, no refresh token), or the account server no longer has the connection; the connection is
     * dropped. Otherwise the refresh endpoint misbehaved (5xx, network) and a later try may work.
     */
    class Auth(service: TrackerService, message: String, val status: Int? = null, val permanent: Boolean) :
        TrackerException(service, "${service.id}: $message" + (status?.let { " (HTTP $it)" } ?: "")) {
        override val transient: Boolean get() = !permanent
    }

    /** The request never got an answer: a timeout or a transport error. */
    class Network(service: TrackerService, cause: Throwable) :
        TrackerException(service, "${service.id}: network (${cause::class.simpleName})", cause) {
        override val transient: Boolean get() = true
    }

    /**
     * The account server, which holds the connection's tokens, couldn't give or take them: it
     * was unreachable or failed, the device has no account session, or the service's token endpoint failed behind it;
     * or this device's secret store couldn't read or keep its copy of the access token ([AccountError.Unexpected] with
     * code `SessionVault.SECRET_STORE_FAILED`, [TrackerTokenStore]). [error] says which. Says nothing about the write.
     */
    class AccountServer(service: TrackerService, failure: AccountException) :
        TrackerException(service, "${service.id}: account server (${failure.error})", failure) {
        val error: AccountError = failure.error
        override val transient: Boolean get() = true
    }

    /** The service can't do this for this item (no rating for that kind, an id it can't take). */
    class Unsupported(service: TrackerService, what: String) : TrackerException(service, "${service.id}: $what unsupported") {
        override val transient: Boolean get() = false
    }

    /** A sign-in didn't complete: the relay or the device-code endpoint refused, or the answer was unusable. */
    class SignIn(service: TrackerService, reason: String) : TrackerException(service, "${service.id}: sign-in failed, $reason") {
        override val transient: Boolean get() = false
    }
}

enum class ApiFailureCategory {
    /** AniList answers GraphQL errors in a 200 body. */
    GRAPHQL_ERRORS,

    /** The answer's body isn't what the call reads: not JSON, or JSON without the shape the service documents. */
    UNREADABLE,
}

/**
 * Whether [error] is worth asking again later without counting an attempt: a transient [TrackerException], or a
 * timeout or transport failure a tracker didn't wrap. Everything else is an answer about the write.
 */
fun isTransientTrackerFailure(error: Throwable): Boolean = when (error) {
    is TrackerException -> error.transient
    is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException, is IOException -> true
    else -> false
}
