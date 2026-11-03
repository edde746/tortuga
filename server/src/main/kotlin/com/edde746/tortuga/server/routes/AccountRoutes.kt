package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.bearer
import com.edde746.tortuga.server.http.notFound
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.LinkIdentityRequest
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/** Account info and deletion, provider identities and devices. */
fun Route.accountRoutes(services: Services) {
    val sessions = services.sessions

    get(SyncPaths.ACCOUNT) { call.respond(services.accounts.accountInfo(call.bearer())) }

    delete(SyncPaths.ACCOUNT) { call.respond(services.accounts.delete(call.bearer())) }

    post(SyncPaths.ACCOUNT_IDENTITIES) {
        val bearer = call.bearer()
        val request = call.receive<LinkIdentityRequest>()
        checkTokenSizes(request.idToken, request.nonce, request.authorizationCode)
        call.respond(
            services.accounts.linkIdentity(
                bearer, request.provider, request.idToken, request.nonce, request.authorizationCode,
            ),
        )
    }

    delete("${SyncPaths.ACCOUNT_IDENTITIES}/{identityId}") {
        val bearer = call.bearer()
        call.respond(services.accounts.unlinkIdentity(bearer, call.uuidParameter("identityId")))
    }

    get(SyncPaths.DEVICES) {
        call.respond(DeviceList(sessions.list(call.bearer())))
    }

    delete("${SyncPaths.DEVICES}/{deviceId}") {
        val bearer = call.bearer()
        sessions.revoke(bearer, call.uuidParameter("deviceId"))
        call.respond(HttpStatusCode.NoContent)
    }
}

/** A UUID path parameter; anything else names nothing, so 404. */
internal fun RoutingCall.uuidParameter(name: String): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw notFound("No such $name")
