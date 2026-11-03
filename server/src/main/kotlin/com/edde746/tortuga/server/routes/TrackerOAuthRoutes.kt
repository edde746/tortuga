package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Limits
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.PageForm
import com.edde746.tortuga.server.http.clientAddress
import com.edde746.tortuga.server.http.respondAppReturn
import com.edde746.tortuga.server.http.respondStaticPage
import com.edde746.tortuga.server.service.TrackerOAuthRelay
import com.edde746.tortuga.server.service.TrackerOAuthRelay.Callback
import com.edde746.tortuga.server.service.TrackerOAuthRelay.CallbackPage
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.trackers.relay.OAuthRelayApi
import com.edde746.tortuga.trackers.relay.OAuthRelayPaths
import com.edde746.tortuga.trackers.relay.OAuthRelayResultRequest
import com.edde746.tortuga.trackers.relay.OAuthRelayStartRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The tracker OAuth relay (protocol: `OAuthRelayApi` in shared). The app's two endpoints are rate limited per client
 * address; the browser's carry an unguessable `state`, answer with a page or a redirect, and share the per-address limit
 * of the other sign-in return pages.
 */
fun Route.trackerOAuthRoutes(relay: TrackerOAuthRelay) {
    rateLimit(Limits.TRACKER_OAUTH_START) {
        post(OAuthRelayPaths.START) {
            call.noStore()
            call.respond(relay.start(call.receive<OAuthRelayStartRequest>(), call.clientAddress))
        }
    }

    rateLimit(Limits.TRACKER_OAUTH_RESULT) {
        post(OAuthRelayPaths.RESULT) {
            call.noStore()
            val request = call.receive<OAuthRelayResultRequest>()
            when (val poll = relay.poll(request.session, request.proof)) {
                is TrackerOAuthRelay.Poll.Ready -> call.respond(poll.result)
                TrackerOAuthRelay.Poll.Pending -> call.respond(HttpStatusCode.NoContent)
                TrackerOAuthRelay.Poll.Gone ->
                    throw ApiException(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND, "No such sign-in, or it ended")
            }
        }
    }

    rateLimit(Limits.SIGN_IN_RETURN) {
        get(OAuthRelayPaths.authorize("{service}")) {
            val url = relay.authorizationUrl(call.parameters["service"].orEmpty(), call.request.queryParameters["state"])
            if (url == null) {
                call.respondPage(CallbackPage.INVALID)
            } else {
                call.noStore()
                call.respondRedirect(url, permanent = false)
            }
        }

        get(OAuthRelayPaths.callback("{service}")) {
            val query = call.request.queryParameters
            when (val answer = relay.callback(call.parameters["service"].orEmpty(), query["state"], query["code"], query["error"])) {
                is Callback.Page -> call.respondPage(answer.page)
                is Callback.Confirm -> call.respondConfirm(answer)
                is Callback.AppReturn -> call.respondAppReturn(answer.location)
            }
        }

        post(OAuthRelayPaths.callback("{service}")) {
            val form = call.receiveParameters()
            call.respondPage(relay.confirm(call.parameters["service"].orEmpty(), form["state"], form["confirm"]))
        }
    }
}

/** Keeps an answer that carries tokens or secrets out of every cache. */
internal fun ApplicationCall.noStore() {
    response.header("Cache-Control", "no-store, private")
}

private suspend fun ApplicationCall.respondPage(page: CallbackPage) {
    val (status, title, body) = when (page) {
        CallbackPage.SIGNED_IN -> Triple(HttpStatusCode.OK, "Signed in", "You can close this page and return to Tortuga.")
        CallbackPage.CANCELLED -> Triple(HttpStatusCode.OK, "Sign-in cancelled", "Return to Tortuga to try again.")
        CallbackPage.FAILED -> Triple(HttpStatusCode.OK, "Sign-in failed", "Return to Tortuga and try again.")
        CallbackPage.INCOMPLETE -> Triple(HttpStatusCode.BadRequest, "Sign-in failed", "The answer was incomplete. Return to Tortuga and try again.")
        CallbackPage.UNAVAILABLE -> Triple(HttpStatusCode.BadGateway, "Sign-in failed", "The sign-in couldn't be completed. Return to Tortuga and try again.")
        // 410, not 404: StatusPages answers every 404 with the API's JSON error, which would replace this page.
        CallbackPage.INVALID -> Triple(HttpStatusCode.Gone, "Link expired", "This sign-in link is no longer valid. Start again from Tortuga.")
    }
    respondStaticPage(status, title, body)
}

/** Asks the person on another device to confirm which device gets the tokens: one button, nothing to type. */
private suspend fun ApplicationCall.respondConfirm(confirm: Callback.Confirm) {
    val service = when (confirm.service) {
        OAuthRelayApi.MYANIMELIST -> "MyAnimeList"
        else -> "AniList"
    }
    val device = confirm.device?.let { "“$it”" } ?: "another device"
    respondStaticPage(
        HttpStatusCode.OK,
        "Connect $service to Tortuga on $device?",
        "Tortuga on $device asked to connect your $service account. Continue only if you started this on that device.",
        PageForm(
            action = OAuthRelayPaths.callback(confirm.service),
            fields = mapOf("state" to confirm.state, "confirm" to confirm.confirm),
            button = "Connect",
            note = "Didn't start this? Close this page: nothing is connected.",
        ),
    )
}
