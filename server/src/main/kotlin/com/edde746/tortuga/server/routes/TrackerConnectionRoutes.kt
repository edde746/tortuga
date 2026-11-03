package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.bearer
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The account's tracker connections (protocol: `TrackerConnectionsApi` in shared); every route needs a session. */
fun Route.trackerConnectionRoutes(services: Services) {
    val connections = services.trackerConnections

    get(TrackerConnectionPaths.CONNECTIONS) { call.respond(connections.list(call.bearer())) }

    post(TrackerConnectionPaths.CONNECTIONS) {
        val bearer = call.bearer()
        call.respond(connections.store(bearer, call.receive<TrackerConnectRequest>()))
    }

    post(TrackerConnectionPaths.TOKEN) {
        val bearer = call.bearer()
        call.noStore()
        call.respond(connections.token(bearer, call.receive<TrackerTokenRequest>()))
    }

    delete("${TrackerConnectionPaths.CONNECTIONS}/{profileId}/{service}") {
        val bearer = call.bearer()
        connections.disconnect(bearer, call.parameters["profileId"].orEmpty(), call.parameters["service"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
}
