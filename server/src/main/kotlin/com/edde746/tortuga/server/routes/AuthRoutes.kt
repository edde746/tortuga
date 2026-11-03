package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Limits
import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.server.http.bearer
import com.edde746.tortuga.server.service.validate
import com.edde746.tortuga.sync.api.RefreshRequest
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Nonce, sign-in, refresh and sign-out. */
fun Route.authRoutes(services: Services) {
    rateLimit(Limits.AUTH) {
        post(SyncPaths.AUTH_NONCE) { call.respond(services.accounts.issueNonce()) }

        post(SyncPaths.AUTH_SIGN_IN) {
            val request = call.receive<SignInRequest>()
            validate(request.device)
            checkTokenSizes(request.idToken, request.nonce, request.authorizationCode)
            call.respond(
                services.accounts.signIn(
                    request.provider, request.idToken, request.nonce, request.authorizationCode, request.device,
                ),
            )
        }

        post(SyncPaths.AUTH_REFRESH) {
            val request = call.receive<RefreshRequest>()
            call.respond(services.sessions.refresh(request.refreshToken))
        }
    }

    post(SyncPaths.AUTH_SIGN_OUT) {
        services.sessions.signOut(call.bearer())
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Bounds on provider tokens, so oversized strings never reach parsing or the database. */
internal fun checkTokenSizes(idToken: String, nonce: String?, authorizationCode: String?) {
    if (idToken.length > 16_384) throw badRequest("idToken too long")
    if ((nonce?.length ?: 0) > 256) throw badRequest("nonce too long")
    if ((authorizationCode?.length ?: 0) > 2048) throw badRequest("authorizationCode too long")
}
