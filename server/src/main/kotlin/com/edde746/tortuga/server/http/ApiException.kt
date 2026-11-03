package com.edde746.tortuga.server.http

import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/** A request that ends with [status] and an [ApiError] body. [message] is diagnostic English, never user data. */
class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

fun badRequest(message: String): ApiException = ApiException(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST, message)

fun notFound(message: String): ApiException = ApiException(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND, message)

fun tooLarge(message: String): ApiException =
    ApiException(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE, message)

/**
 * The server can't take the request now (every database connection busy or the database unreachable, no memory free
 * for its bytes, every notice stream taken, the database's disk nearly full): 503 [ApiErrorCode.SERVER_BUSY] with
 * Retry-After [retryAfterSeconds]. [message], the answer's, is diagnostic English, never user data.
 */
class ServerBusy(message: String, val retryAfterSeconds: Int = BUSY_RETRY_AFTER_SECONDS) : RuntimeException(message)

/** What a 503 [ApiErrorCode.SERVER_BUSY] asks the device to wait, in seconds. */
internal const val BUSY_RETRY_AFTER_SECONDS = 5

private val log = LoggerFactory.getLogger("com.edde746.tortuga.server.errors")

/**
 * Maps every failure to an [ApiError] body. A request body that doesn't decode is 400 (Ktor wraps that failure in
 * [BadRequestException]); any other exception, a decode of the server's own data included, is logged (without request
 * bodies) and answered 500.
 */
fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondError(e.status, ApiError(e.code, e.message.orEmpty())) }
        exception<ServerBusy> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds)
            call.respondError(HttpStatusCode.ServiceUnavailable, ApiError(ApiErrorCode.SERVER_BUSY, e.message.orEmpty()))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondError(HttpStatusCode.PayloadTooLarge, ApiError(ApiErrorCode.PAYLOAD_TOO_LARGE, "Request body too large"))
        }
        exception<NotFoundException> { call, _ ->
            call.respondError(HttpStatusCode.NotFound, ApiError(ApiErrorCode.NOT_FOUND, "Not found"))
        }
        exception<BadRequestException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, ApiError(ApiErrorCode.INVALID_REQUEST, requestProblem(e)))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ApiError(ApiErrorCode.INVALID_REQUEST, "Unreadable request body"))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {} {}", call.request.local.method.value, call.request.local.uri.substringBefore('?'), e)
            call.respondError(HttpStatusCode.InternalServerError, ApiError(ApiErrorCode.INTERNAL, "Internal error"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondError(status, ApiError(ApiErrorCode.RATE_LIMITED, "Too many requests"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondError(status, ApiError(ApiErrorCode.NOT_FOUND, "No such endpoint"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondError(status, ApiError(ApiErrorCode.INVALID_REQUEST, "Method not allowed"))
        }
        status(HttpStatusCode.UnsupportedMediaType) { call, status ->
            call.respondError(status, ApiError(ApiErrorCode.INVALID_REQUEST, "Send application/json"))
        }
    }
}

/**
 * [error] as JSON, whatever the request accepts: through content negotiation, a request that accepts only
 * `text/event-stream` (the notice stream's) would get 406 without the error or its Retry-After.
 */
private suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: ApiError) =
    respondText(SyncProtocol.json.encodeToString(ApiError.serializer(), error), ContentType.Application.Json, status)

/** Ktor wraps body decoding failures in BadRequestException; name them without echoing the body. */
private fun requestProblem(e: BadRequestException): String {
    var cause: Throwable? = e
    while (cause != null) {
        if (cause is SerializationException) return "Malformed JSON or missing field"
        cause = cause.cause
    }
    return "Bad request"
}
