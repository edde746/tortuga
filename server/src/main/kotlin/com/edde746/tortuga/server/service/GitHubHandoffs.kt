package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.auth.GitHubClient
import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.http.notFound
import com.edde746.tortuga.server.service.AppleHandoffs.Companion.PROOF_PREFIX
import com.edde746.tortuga.server.service.AppleHandoffs.Companion.appReturn
import com.edde746.tortuga.server.service.AppleHandoffs.Companion.checkCollect
import com.edde746.tortuga.server.service.AppleHandoffs.Companion.checkStart
import com.edde746.tortuga.server.service.AppleHandoffs.Companion.providerError
import com.edde746.tortuga.sync.api.GitHubHandoff
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.HandoffStartResponse
import com.edde746.tortuga.sync.api.SyncPaths
import java.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * GitHub web flows between the app's start, GitHub's redirect to the return page and the app's pickup (protocol on
 * `HandoffStartRequest`), as [AppleHandoffs] keeps Apple's: under a state this server issued, for the verifier that
 * started it and the proof its return page sent the browser back with.
 */
class GitHubHandoffs(
    private val db: Database,
    private val github: GitHubClient,
    private val clock: Clock,
) {
    /** Issues the state of a flow for [request]'s challenge and return. */
    suspend fun start(request: HandoffStartRequest): HandoffStartResponse {
        checkStart(request)
        val state = Secrets.generate("")
        db.transaction {
            update(
                "INSERT INTO github_handoffs (state, challenge, redirect_uri, expires_at) VALUES (?, ?, ?, ?)",
                state, request.challenge, request.redirectUri, clock.instant() + TTL.toJavaDuration(),
            )
        }
        return HandoffStartResponse(state, TTL.inWholeMilliseconds)
    }

    /**
     * Keeps GitHub's authorization [code] for [state], once, and returns where the browser goes back to the app with
     * the proof; null (nothing written) for a state that isn't waiting: never issued, expired or already returned.
     */
    suspend fun returned(state: String, code: String): String? {
        val proof = Secrets.generate(PROOF_PREFIX)
        val redirectUri = db.transaction {
            queryOne(
                """
                UPDATE github_handoffs SET proof_hash = ?, code = ?
                WHERE state = ? AND proof_hash IS NULL AND expires_at > ? RETURNING redirect_uri
                """,
                Secrets.sha256Base64Url(proof), code, state, clock.instant(),
            ) { it.getString("redirect_uri") }
        } ?: return null
        return appReturn(redirectUri, SyncPaths.RETURN_STATE to state, SyncPaths.RETURN_PROOF to proof)
    }

    /**
     * The user declined, or GitHub refused the request: ends the flow for [state] and returns where the browser takes
     * the [error] back.
     */
    suspend fun declined(state: String, error: String): String? = db.transaction {
        queryOne(
            "DELETE FROM github_handoffs WHERE state = ? AND proof_hash IS NULL AND expires_at > ? RETURNING redirect_uri",
            state, clock.instant(),
        ) { it.getString("redirect_uri") }
    }?.let { appReturn(it, SyncPaths.RETURN_STATE to state, SyncPaths.RETURN_ERROR to providerError(error)) }

    /**
     * Hands the result over once, to [request]'s verifier and proof: deletes it, then exchanges its code at GitHub, so
     * a code GitHub refuses (401) or that GitHub couldn't take (502) is spent as well.
     */
    suspend fun collect(request: HandoffCollectRequest): GitHubHandoff {
        checkCollect(request)
        val code = db.transaction {
            queryOne(
                """
                DELETE FROM github_handoffs WHERE state = ? AND challenge = ? AND proof_hash = ? AND expires_at > ?
                RETURNING code
                """,
                request.state, Secrets.sha256Base64Url(request.verifier), Secrets.sha256Base64Url(request.proof),
                clock.instant(),
            ) { it.getString("code") }
        } ?: throw notFound("Nothing to collect")
        return GitHubHandoff(github.exchangeCode(code))
    }

    private companion object {
        /** How long a flow may take, from its start to the app's pickup. */
        val TTL = 10.minutes
    }
}
