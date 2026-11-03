package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Limits
import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.bearer
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.LinkPollRequest
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Device links: started and polled by the signed-out device, claimed and answered by a signed-in one. */
fun Route.linkRoutes(services: Services) {
    val links = services.links

    rateLimit(Limits.LINK_START) {
        post(SyncPaths.LINK_START) { call.respond(links.start(call.receive<LinkStartRequest>())) }
    }

    rateLimit(Limits.LINK_ANSWER) {
        post(SyncPaths.LINK_CLAIM) {
            val bearer = call.bearer()
            call.respond(links.claim(bearer, call.receive<LinkCodeRequest>().code))
        }
        post(SyncPaths.LINK_APPROVE) {
            val bearer = call.bearer()
            links.approve(bearer, call.receive<LinkCodeRequest>().code)
            call.respond(HttpStatusCode.NoContent)
        }
        post(SyncPaths.LINK_REJECT) {
            val bearer = call.bearer()
            links.reject(bearer, call.receive<LinkCodeRequest>().code)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    rateLimit(Limits.LINK_POLL) {
        post(SyncPaths.LINK_POLL) {
            val request = call.receive<LinkPollRequest>()
            call.respond(links.poll(request.linkId, request.secret))
        }
    }
}
