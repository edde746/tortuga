package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.server.http.notFound
import com.edde746.tortuga.sync.api.AppleHandoff
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.HandoffStartResponse
import com.edde746.tortuga.sync.api.SyncPaths
import java.net.URLEncoder
import java.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Sign in with Apple web flows between the app's start, Apple's form post and the app's pickup (protocol on
 * `HandoffStartRequest`). A result is kept only under a state this server issued, and handed over only to the
 * verifier that started it with the proof its return page sent the browser back with.
 */
class AppleHandoffs(private val db: Database, private val clock: Clock) {
    /** Issues the state of a flow for [request]'s challenge and return. */
    suspend fun start(request: HandoffStartRequest): HandoffStartResponse {
        checkStart(request)
        val state = Secrets.generate("")
        db.transaction {
            update(
                "INSERT INTO apple_handoffs (state, challenge, redirect_uri, expires_at) VALUES (?, ?, ?, ?)",
                state, request.challenge, request.redirectUri, clock.instant() + TTL.toJavaDuration(),
            )
        }
        return HandoffStartResponse(state, TTL.inWholeMilliseconds)
    }

    /**
     * Keeps what Apple posted for [state], once, and returns where the browser goes back to the app with the proof;
     * null (nothing written) for a state that isn't waiting: never issued, expired or already returned.
     */
    suspend fun returned(state: String, idToken: String, authorizationCode: String?, userJson: String?): String? {
        val proof = Secrets.generate(PROOF_PREFIX)
        val redirectUri = db.transaction {
            queryOne(
                """
                UPDATE apple_handoffs SET proof_hash = ?, id_token = ?, authorization_code = ?, user_json = ?
                WHERE state = ? AND proof_hash IS NULL AND expires_at > ? RETURNING redirect_uri
                """,
                Secrets.sha256Base64Url(proof), idToken, authorizationCode, userJson, state, clock.instant(),
            ) { it.getString("redirect_uri") }
        } ?: return null
        return appReturn(redirectUri, SyncPaths.RETURN_STATE to state, SyncPaths.RETURN_PROOF to proof)
    }

    /** The user declined at Apple: ends the flow for [state] and returns where the browser takes the [error] back. */
    suspend fun declined(state: String, error: String): String? = db.transaction {
        queryOne(
            "DELETE FROM apple_handoffs WHERE state = ? AND proof_hash IS NULL AND expires_at > ? RETURNING redirect_uri",
            state, clock.instant(),
        ) { it.getString("redirect_uri") }
    }?.let { appReturn(it, SyncPaths.RETURN_STATE to state, SyncPaths.RETURN_ERROR to providerError(error)) }

    /** Hands the result over once, to [request]'s verifier and proof. */
    suspend fun collect(request: HandoffCollectRequest): AppleHandoff {
        checkCollect(request)
        return db.transaction {
            queryOne(
                """
                DELETE FROM apple_handoffs
                WHERE state = ? AND challenge = ? AND proof_hash = ? AND expires_at > ?
                RETURNING id_token, authorization_code, user_json
                """,
                request.state, Secrets.sha256Base64Url(request.verifier), Secrets.sha256Base64Url(request.proof),
                clock.instant(),
            ) { AppleHandoff(it.getString("id_token"), it.getString("authorization_code"), it.getString("user_json")) }
        } ?: throw notFound("Nothing to collect")
    }

    companion object {
        /** How long a flow may take, from its start to the app's pickup. */
        private val TTL = 10.minutes

        /** A state this server issues, and a verifier's challenge: unpadded base64url of 32 bytes. */
        val STATE_PATTERN: Regex = Regex("^[A-Za-z0-9_-]{43}$")

        /** A verifier: PKCE's (RFC 7636 §4.1), 43 to 128 unreserved characters. */
        private val VERIFIER_PATTERN = Regex("^[A-Za-z0-9._~-]{43,128}$")

        /** A redirect back to the app on this device: the loopback receiver of Android and desktop. */
        private val LOOPBACK_RETURN = Regex("^http://127\\.0\\.0\\.1:[0-9]{1,5}/[A-Za-z0-9._~/-]{0,64}$")

        internal const val PROOF_PREFIX = "ttp_"

        /**
         * A return to the app on the browser's own device: the loopback receiver of Android and desktop, or Apple's
         * web authentication session ([SyncPaths.HANDOFF_APP_RETURN]). The web sign-ins and the tracker relay send a
         * proof only there.
         */
        internal fun isAppReturn(uri: String): Boolean = uri == SyncPaths.HANDOFF_APP_RETURN || LOOPBACK_RETURN.matches(uri)

        /** Refuses a start whose challenge isn't one, or whose return leaves the device ([HandoffStartRequest]). */
        internal fun checkStart(request: HandoffStartRequest) {
            if (!STATE_PATTERN.matches(request.challenge)) throw badRequest("challenge must be 43 base64url characters")
            if (!isAppReturn(request.redirectUri)) {
                throw badRequest("redirectUri must be a loopback address or ${SyncPaths.HANDOFF_APP_RETURN}")
            }
        }

        internal fun checkCollect(request: HandoffCollectRequest) {
            if (!STATE_PATTERN.matches(request.state)) throw badRequest("state must be 43 base64url characters")
            if (!VERIFIER_PATTERN.matches(request.verifier)) throw badRequest("verifier must be 43..128 characters")
            if (request.proof.length > 64) throw badRequest("proof too long")
        }

        /** A provider's `error` as the app gets it: its code when it is one, else `invalid_request`. */
        internal fun providerError(error: String): String =
            error.takeIf { it.matches(Regex("^[a-z_]{1,64}$")) } ?: "invalid_request"

        /** [redirectUri] with [query], percent-encoded. */
        internal fun appReturn(redirectUri: String, vararg query: Pair<String, String>): String =
            redirectUri + query.joinToString("&", prefix = "?") { (name, value) ->
                "$name=${URLEncoder.encode(value, Charsets.UTF_8)}"
            }
    }
}
