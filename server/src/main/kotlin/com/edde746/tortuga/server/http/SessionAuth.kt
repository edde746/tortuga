package com.edde746.tortuga.server.http

import com.edde746.tortuga.server.service.Session
import com.edde746.tortuga.server.service.Sessions
import com.edde746.tortuga.sync.api.ApiErrorCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * A call's bearer token, not yet checked: [Sessions.transaction] looks up its [Session] inside the transaction of the
 * work the call asks for, so an authenticated request takes no transaction of its own for that.
 */
class Bearer(val accessToken: String) {
    /** The session the token belongs to, once authenticated; the request log line names its account. */
    @Volatile
    var session: Session? = null
        internal set

    override fun toString(): String = "Bearer(session=$session)"
}

/** The call's [Bearer], also read by the request log line. */
val BearerKey: AttributeKey<Bearer> = AttributeKey("tortuga.bearer")

/** The call's `Authorization: Bearer` token; 401 [ApiErrorCode.UNAUTHORIZED] when there is none. */
fun ApplicationCall.bearer(): Bearer {
    val token = bearerToken()
        ?: throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED, "Missing bearer token")
    return Bearer(token).also { attributes.put(BearerKey, it) }
}

/** The `Authorization: Bearer` token as [bearer] reads it (the scheme in any case, the token trimmed); null without one. */
fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val space = header.indexOf(' ')
    if (space <= 0 || !header.substring(0, space).equals("Bearer", ignoreCase = true)) return null
    return header.substring(space + 1).trim().takeIf { it.isNotEmpty() }
}
