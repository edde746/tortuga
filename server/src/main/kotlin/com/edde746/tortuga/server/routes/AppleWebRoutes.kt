package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Limits
import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.respondAppReturn
import com.edde746.tortuga.server.http.respondStaticPage
import com.edde746.tortuga.server.service.AppleHandoffs
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * The Sign in with Apple web flow's start, return page and pickup (protocol on [HandoffStartRequest]). The return page
 * sends the browser back to the app for a state this server issued; for any other it is a static error page without
 * script, and writes nothing.
 */
fun Route.appleWebRoutes(services: Services) {
    rateLimit(Limits.SIGN_IN_RETURN) {
        post(SyncPaths.APPLE_RETURN) {
            val form = call.receiveParameters()
            val state = form["state"]?.takeIf { AppleHandoffs.STATE_PATTERN.matches(it) }
            val idToken = form["id_token"]
            val error = form["error"]
            val location = when {
                state == null -> null
                error != null -> services.handoffs.declined(state, error)
                idToken == null || idToken.length > 16_384 || (form["code"]?.length ?: 0) > 2048 ||
                    (form["user"]?.length ?: 0) > 4096 -> null
                else -> services.handoffs.returned(state, idToken, form["code"], form["user"])
            }
            if (location != null) call.respondAppReturn(location) else call.respondFailed()
        }
    }

    rateLimit(Limits.AUTH) {
        post(SyncPaths.APPLE_START) { call.respond(services.handoffs.start(call.receive<HandoffStartRequest>())) }
        post(SyncPaths.APPLE_HANDOFF) { call.respond(services.handoffs.collect(call.receive<HandoffCollectRequest>())) }
    }
}

/** The return page for a state that isn't waiting, or a post that isn't Apple's ([respondStaticPage]). */
internal suspend fun ApplicationCall.respondFailed() =
    respondStaticPage(HttpStatusCode.BadRequest, "Sign-in failed", "Return to Tortuga and start again.")
