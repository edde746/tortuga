package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.Tx
import com.edde746.tortuga.server.db.instant
import com.edde746.tortuga.server.db.uuid
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.Bearer
import com.edde746.tortuga.server.http.notFound
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DeviceInfo
import com.edde746.tortuga.sync.api.SessionTokens
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import org.slf4j.LoggerFactory

/** An authenticated request's session. */
data class Session(val accountId: UUID, val sessionId: UUID)

/**
 * Per-device sessions: an access token (short-lived) and a rotating refresh token, both stored as SHA-256 only.
 * A device in the API is a session. A session that ends loses its notice stream once that commits ([SyncEvents]).
 */
class Sessions(
    private val db: Database,
    private val events: SyncEvents,
    private val clock: Clock,
    private val accessTtl: Duration,
    private val idleTtl: Duration,
) {
    /**
     * Opens a session for [device] on [accountId] inside the caller's transaction. An account holds at most
     * [SyncProtocol.MAX_SESSIONS]: a new one ends the least recently seen past that.
     */
    fun create(tx: Tx, accountId: UUID, device: DeviceDescriptor): SessionTokens {
        val now = clock.instant()
        val id = UUID.randomUUID()
        val access = Secrets.generate(Secrets.ACCESS_PREFIX)
        val refresh = Secrets.generate(Secrets.REFRESH_PREFIX)
        val accessExpires = now + accessTtl.toJavaDuration()
        val refreshExpires = now + idleTtl.toJavaDuration()
        // The account row's lock makes concurrent sign-ins take turns, so the limit holds.
        tx.lockAccount(accountId)
        val ended = tx.query(
            """
            DELETE FROM sessions WHERE id IN (
                SELECT id FROM sessions WHERE account_id = ? ORDER BY last_seen_at DESC, created_at DESC OFFSET ?
            )
            RETURNING id
            """,
            accountId, SyncProtocol.MAX_SESSIONS - 1,
        ) { it.uuid("id") }
        if (ended.isNotEmpty()) tx.afterCommit { ended.forEach { events.endSession(accountId, it) } }
        tx.update(
            """
            INSERT INTO sessions (id, account_id, device_name, platform, access_hash, access_expires_at, refresh_hash,
                                  refresh_expires_at, created_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id, accountId, device.name, device.platform, Secrets.sha256(access), accessExpires, Secrets.sha256(refresh),
            refreshExpires, now, now,
        )
        return tokens(id, access, refresh)
    }

    /**
     * Runs [block] in one transaction with the session [bearer]'s access token belongs to, looked up in that same
     * transaction. Fails with [ApiErrorCode.UNAUTHORIZED] for an unknown token and [ApiErrorCode.TOKEN_EXPIRED] for an
     * expired one. Records the device as seen (at most every five minutes) after [block], so the session row is locked
     * after the account row [block] may lock, in the order account deletion takes them.
     */
    suspend fun <T> transaction(bearer: Bearer, block: Tx.(Session) -> T): T = db.transaction {
        val now = clock.instant()
        val row = queryOne(
            "SELECT id, account_id, access_expires_at, last_seen_at FROM sessions WHERE access_hash = ?",
            Secrets.sha256(bearer.accessToken),
        ) { Row(it.uuid("id"), it.uuid("account_id"), it.instant("access_expires_at"), it.instant("last_seen_at")) }
            ?: throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED, "Unknown access token")
        if (!row.accessExpiresAt.isAfter(now)) {
            throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.TOKEN_EXPIRED, "Access token expired")
        }
        val session = Session(row.accountId, row.id)
        bearer.session = session
        val result = block(session)
        if (row.lastSeenAt.isBefore(now - SEEN_GRANULARITY.toJavaDuration())) {
            update("UPDATE sessions SET last_seen_at = ? WHERE id = ?", now, row.id)
        }
        result
    }

    /**
     * The session [bearer] belongs to, in a transaction of its own: for work that must know it before its first
     * transaction (linking an identity checks the session before spending the sign-in's nonce).
     */
    suspend fun authenticate(bearer: Bearer): Session = transaction(bearer) { it }

    /**
     * Rotates the session [refreshToken] belongs to (RFC 9700 §4.14.2, with a grace period):
     * - the current refresh token rotates and becomes the previous one, usable once, for [RETRY_GRACE]: a device whose
     *   refresh response was lost retries with it. The token before it is retired;
     * - that retry replaces the current token it was handed, which the session remembers as replaced;
     * - any other use of the previous token (a second one, or after the grace period), and any use of the retired or
     *   the replaced one, is a reuse: the session ends, as for a stolen token, and the call gets
     *   [ApiErrorCode.INVALID_REFRESH_TOKEN].
     *
     * A token older than the retired one is unknown (also [ApiErrorCode.INVALID_REFRESH_TOKEN]).
     */
    suspend fun refresh(refreshToken: String): SessionTokens {
        val hash = Secrets.sha256(refreshToken)
        val rotated = db.transaction<SessionTokens?> {
            val now = clock.instant()
            val row = queryOne(
                """
                SELECT id, account_id, refresh_hash = ? AS current,
                       coalesce(previous_refresh_hash = ? AND previous_refresh_valid_until > ?, false) AS retry
                FROM sessions
                WHERE (refresh_hash = ? OR previous_refresh_hash = ? OR retired_refresh_hash = ? OR replaced_refresh_hash = ?)
                    AND refresh_expires_at > ?
                FOR UPDATE
                """,
                hash, hash, now, hash, hash, hash, hash, now,
            ) { RefreshRow(it.uuid("id"), it.uuid("account_id"), it.getBoolean("current"), it.getBoolean("retry")) }
                ?: throw invalidRefreshToken("Unknown refresh token")
            if (!row.current && !row.retry) {
                update("DELETE FROM sessions WHERE id = ?", row.id)
                afterCommit { events.endSession(row.accountId, row.id) }
                log.atWarn().addKeyValue("accountId", row.accountId).addKeyValue("sessionId", row.id)
                    .log("A spent refresh token was used again; the session ended")
                return@transaction null
            }
            val access = Secrets.generate(Secrets.ACCESS_PREFIX)
            val refresh = Secrets.generate(Secrets.REFRESH_PREFIX)
            val accessExpires = now + accessTtl.toJavaDuration()
            val refreshExpires = now + idleTtl.toJavaDuration()
            if (row.current) {
                update(
                    """
                    UPDATE sessions SET access_hash = ?, access_expires_at = ?, retired_refresh_hash = previous_refresh_hash,
                        previous_refresh_hash = refresh_hash, previous_refresh_valid_until = ?, refresh_hash = ?,
                        refresh_expires_at = ?, last_seen_at = ?
                    WHERE id = ?
                    """,
                    Secrets.sha256(access), accessExpires, now + RETRY_GRACE.toJavaDuration(), Secrets.sha256(refresh),
                    refreshExpires, now, row.id,
                )
            } else {
                update(
                    """
                    UPDATE sessions SET access_hash = ?, access_expires_at = ?, replaced_refresh_hash = refresh_hash,
                        previous_refresh_valid_until = NULL, refresh_hash = ?, refresh_expires_at = ?, last_seen_at = ?
                    WHERE id = ?
                    """,
                    Secrets.sha256(access), accessExpires, Secrets.sha256(refresh), refreshExpires, now, row.id,
                )
            }
            tokens(row.id, access, refresh)
        }
        return rotated ?: throw invalidRefreshToken("Refresh token reused")
    }

    suspend fun list(bearer: Bearer): List<DeviceInfo> = transaction(bearer) { session ->
        query(
            """
            SELECT id, device_name, platform, created_at, last_seen_at FROM sessions
            WHERE account_id = ? ORDER BY last_seen_at DESC, created_at DESC
            """,
            session.accountId,
        ) {
            val id = it.uuid("id")
            DeviceInfo(
                deviceId = id.toString(),
                name = it.getString("device_name"),
                platform = it.getString("platform"),
                createdAtMillis = it.instant("created_at").toEpochMilli(),
                lastSeenAtMillis = it.instant("last_seen_at").toEpochMilli(),
                current = id == session.sessionId,
            )
        }
    }

    /** Ends device [deviceId] of the session's account; 404 for anything else. */
    suspend fun revoke(bearer: Bearer, deviceId: UUID) {
        val deleted = transaction(bearer) { session ->
            update("DELETE FROM sessions WHERE id = ? AND account_id = ?", deviceId, session.accountId).also {
                if (it > 0) afterCommit { events.endSession(session.accountId, deviceId) }
            }
        }
        if (deleted == 0) throw notFound("No such device")
    }

    /** Ends [bearer]'s own session. */
    suspend fun signOut(bearer: Bearer) {
        transaction(bearer) { session ->
            update("DELETE FROM sessions WHERE id = ?", session.sessionId)
            afterCommit { events.endSession(session.accountId, session.sessionId) }
        }
    }

    /** What a new or rotated session hands out: its tokens and their lifetimes from now. */
    private fun tokens(id: UUID, access: String, refresh: String) =
        SessionTokens(id.toString(), access, accessTtl.inWholeMilliseconds, refresh, idleTtl.inWholeMilliseconds)

    private class Row(val id: UUID, val accountId: UUID, val accessExpiresAt: Instant, val lastSeenAt: Instant)

    /** A session a refresh token named: whether the token is its current one, or its previous one within the retry. */
    private class RefreshRow(val id: UUID, val accountId: UUID, val current: Boolean, val retry: Boolean)

    private companion object {
        val SEEN_GRANULARITY = 5.minutes

        /** How long the previous refresh token stays usable, once, after a rotation: a lost response's retry. */
        val RETRY_GRACE = 10.minutes

        val log = LoggerFactory.getLogger(Sessions::class.java)

        fun invalidRefreshToken(message: String) =
            ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN, message)
    }
}
