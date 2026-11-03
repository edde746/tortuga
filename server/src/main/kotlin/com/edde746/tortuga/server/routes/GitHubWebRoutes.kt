package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Limits
import com.edde746.tortuga.server.http.respondAppReturn
import com.edde746.tortuga.server.service.AppleHandoffs
import com.edde746.tortuga.server.service.GitHubHandoffs
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The GitHub web flow's start, return page (the GitHub OAuth App's callback URL) and pickup, served where GitHub is
 * configured (protocol on [HandoffStartRequest]). The return page sends the browser back to the app for a state this
 * server issued; for any other it is a static error page without script, and writes nothing.
 */
fun Route.gitHubWebRoutes(handoffs: GitHubHandoffs) {
    rateLimit(Limits.SIGN_IN_RETURN) {
        get(SyncPaths.GITHUB_RETURN) {
            val query = call.request.queryParameters
            val state = query["state"]?.takeIf { AppleHandoffs.STATE_PATTERN.matches(it) }
            val code = query["code"]
            val error = query["error"]
            val location = when {
                state == null -> null
                // The user declined (`access_denied`) or GitHub refused the request: the app stops waiting at once.
                error != null -> handoffs.declined(state, error)
                code.isNullOrEmpty() || code.length > 2048 -> null
                else -> handoffs.returned(state, code)
            }
            if (location != null) call.respondAppReturn(location) else call.respondFailed()
        }
    }

    rateLimit(Limits.AUTH) {
        post(SyncPaths.GITHUB_START) { call.respond(handoffs.start(call.receive<HandoffStartRequest>())) }
        post(SyncPaths.GITHUB_HANDOFF) { call.respond(handoffs.collect(call.receive<HandoffCollectRequest>())) }
    }
}
