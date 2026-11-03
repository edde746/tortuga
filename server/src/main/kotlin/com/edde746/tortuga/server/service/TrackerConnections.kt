package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.Tx
import com.edde746.tortuga.server.db.instant
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.Bearer
import com.edde746.tortuga.server.http.MAX_TOKEN_ANSWER_BYTES
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.TokenRefresher
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerProvider
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerSession
import com.edde746.tortuga.trackers.connections.TrackerAccessToken
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionList
import com.edde746.tortuga.trackers.connections.TrackerConnectionsApi
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import com.edde746.tortuga.trackers.trackerProvider
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import java.sql.ResultSet
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * Tracker connections (protocol: `TrackerConnectionsApi` in shared): each profile's sign-in to a
 * tracker service, held for every device of the account. This server is the only one that refreshes their tokens, and
 * it refreshes and revokes through the service's own provider (`trackerProvider`) as the client the grant was issued to.
 *
 * A row is keyed by (account, profile id, service). The session and the client id are sealed by [cipher] with the row's
 * `profileId/service` as the index, so they open only in their own row.
 *
 * A token that needs no refresh is read and handed out. A refresh never runs inside a transaction: it claims the row
 * for [CLAIM_LEASE] (`refreshing_until`), asks the service with no connection held, and stores the result only if the
 * row still holds the session it refreshed (a compare-and-set on the sealed bytes, which a random nonce makes unique
 * per seal). Concurrent requests for one connection, on this instance or another, find the claim and wait for that
 * refresh's result, so a rotating refresh token is spent once.
 *
 * Every connection added, replaced or removed moves the account's `trackers_version`, which pulls and listings report,
 * so the apps list the connections only when they changed, and once that commits the account's notice streams hear of
 * it, but the one of the session that asked ([SyncEvents]); the push that deletes a profile's connections tells them
 * itself. Writes lock the account row before connection rows.
 *
 * Logs name the account and the service only: never tokens, usernames or client ids.
 */
class TrackerConnections(
    private val db: Database,
    private val sessions: Sessions,
    private val events: SyncEvents,
    private val cipher: RecordCipher,
    private val http: HttpClient,
    private val clock: Clock,
) {
    /** A stored connection, opened. [sealed] is its `sealed_session` as read: it names this state of the row. */
    class Stored(
        val profileId: String,
        val service: TrackerService,
        val connectedAtMillis: Long,
        val clientId: String,
        val session: TrackerSession,
        val sealed: ByteArray,
    )

    /** The account's connections, every profile's, oldest first, without tokens, and the counter they are at. */
    suspend fun list(bearer: Bearer): TrackerConnectionList = sessions.transaction(bearer) { session ->
        // One statement, so one snapshot: the counter is the one these rows are at.
        val rows = query(
            """
            SELECT a.trackers_version, t.profile_id, t.service, t.sealed_session, t.connected_at
            FROM accounts a LEFT JOIN tracker_connections t ON t.account_id = a.id
            WHERE a.id = ? ORDER BY t.connected_at, t.profile_id, t.service
            """,
            session.accountId,
        ) { row -> row.getLong("trackers_version") to row.getString("profile_id")?.let { row.toStored(session.accountId) } }
        val version = rows.firstOrNull()?.first ?: throw accountGone()
        TrackerConnectionList(
            rows.mapNotNull { it.second }.map { TrackerConnection(it.profileId, it.service.id, it.session.username, it.connectedAtMillis) },
            version,
        )
    }

    /**
     * Stores a sign-in, replacing the profile's connection to that service (its tokens aren't revoked, and a refresh of
     * them still running stores nothing). The new connection's time is now, or a millisecond after the replaced one's
     * if the clock went back. An account holds at most [TrackerConnectionsApi.MAX_CONNECTIONS]: a new one past it is a
     * 413 [ApiErrorCode.QUOTA_EXCEEDED]; a replacement always fits.
     */
    suspend fun store(bearer: Bearer, request: TrackerConnectRequest): TrackerConnection {
        val service = validate(request)
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val stored = TrackerSession(
            accessToken = request.accessToken,
            refreshToken = request.refreshToken,
            expiresAt = request.expiresInSeconds?.let { now.epochSecond + it },
            username = request.username,
            createdAt = now.epochSecond,
        )
        val (accountId, connectedAt) = sessions.transaction(bearer) { session ->
            // Account deletion reads the connections it revokes under this lock: a sign-in stored meanwhile waits for
            // it, then finds no account.
            val account = lockAccount(session.accountId)
            val others = queryOne(
                "SELECT count(*) FROM tracker_connections WHERE account_id = ? AND NOT (profile_id = ? AND service = ?)",
                session.accountId, request.profileId, service.id,
            ) { it.getInt(1) }!!
            if (others >= TrackerConnectionsApi.MAX_CONNECTIONS) {
                throw ApiException(
                    HttpStatusCode.PayloadTooLarge, ApiErrorCode.QUOTA_EXCEEDED,
                    "At most ${TrackerConnectionsApi.MAX_CONNECTIONS} tracker connections per account",
                )
            }
            val sealed = seal(session.accountId, request.profileId, service, SealedSession(request.clientId, stored))
            val connectedAt = queryOne(
                """
                INSERT INTO tracker_connections (account_id, profile_id, service, sealed_session, connected_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, profile_id, service) DO UPDATE SET sealed_session = excluded.sealed_session,
                    refreshing_until = NULL,
                    connected_at = GREATEST(excluded.connected_at, tracker_connections.connected_at + interval '1 millisecond')
                RETURNING connected_at
                """,
                session.accountId, request.profileId, service.id, sealed, now,
            ) { it.instant("connected_at") }!!
            bumpVersion(session.accountId)
            afterCommit { events.changed(session.accountId, session.sessionId, account.syncVersion) }
            session.accountId to connectedAt
        }
        log.atInfo().addKeyValue("accountId", accountId).addKeyValue("service", service.id).log("Tracker connected")
        return TrackerConnection(request.profileId, service.id, request.username, connectedAt.toEpochMilli())
    }

    /**
     * The access token of the sign-in [request] names, refreshed first when it expires within the window or the service
     * rejected it. 410 when the connection is gone or the service refused it (it is then deleted), 502 when the
     * service's token endpoint failed (the stored session stays).
     */
    suspend fun token(bearer: Bearer, request: TrackerTokenRequest): TrackerAccessToken {
        val service = TrackerService.fromId(request.service) ?: throw badRequest("Unknown service")
        val (session, found) = sessions.transaction(bearer) { session ->
            session to find(session.accountId, request.profileId, service)
        }
        val accountId = session.accountId
        var current = found
        while (true) {
            val stored = current?.takeIf { it.connectedAtMillis == request.connectedAtMillis } ?: throw disconnected()
            val provider = trackerProvider(http, service, stored.clientId, MAX_TOKEN_ANSWER_BYTES)
            if (!refreshDue(stored, provider, request.rejected)) return answer(stored.session)
            val refresher = provider.refresher
            if (refresher == null) {
                // The service refused a token that doesn't refresh (AniList): the sign-in is over.
                if (db.transaction { deleteUnchanged(session, stored) }) {
                    log.atInfo().addKeyValue("accountId", accountId).addKeyValue("service", service.id)
                        .log("Tracker token rejected and not refreshable; connection deleted")
                }
                throw disconnected()
            }
            // Not cancellable from the claim on: once the service has answered, the old refresh token may be spent
            // (Trakt), so the new session is stored, and the claim let go, even when the request has gone.
            val refreshed = withContext(NonCancellable) {
                if (db.transaction { claim(accountId, stored) }) refreshClaimed(session, stored, refresher) ?: throw disconnected() else null
            }
            if (refreshed != null) return refreshed
            // Another request holds the claim: its result is read once it has stored it.
            delay(CLAIM_POLL)
            current = db.transaction { find(accountId, request.profileId, service) }
        }
    }

    /**
     * Deletes the profile's connection to [service] and revokes it at the service; nothing when there is none, and no
     * revocation for one whose sealed session doesn't open ([toStored]).
     */
    suspend fun disconnect(bearer: Bearer, profileId: String, service: String) {
        val known = TrackerService.fromId(service)
        val (accountId, removed) = sessions.transaction(bearer) { session ->
            val removed = known?.let {
                val account = lockAccount(session.accountId)
                val deleted = query(
                    "DELETE FROM tracker_connections WHERE account_id = ? AND profile_id = ? AND service = ? RETURNING $COLUMNS",
                    session.accountId, profileId, it.id,
                ) { row -> row.toStored(session.accountId) }
                if (deleted.isNotEmpty()) {
                    bumpVersion(session.accountId)
                    afterCommit { events.changed(session.accountId, session.sessionId, account.syncVersion) }
                }
                deleted.filterNotNull()
            }
            session.accountId to removed.orEmpty()
        }
        revoke(accountId, removed)
    }

    /**
     * Every connection of [accountId], locked, in the caller's transaction: account deletion reads them to revoke them.
     * A refresh still running finds the row gone when it would store, and revokes the tokens it got itself.
     */
    fun readAll(tx: Tx, accountId: UUID): List<Stored> =
        tx.query("SELECT $COLUMNS FROM tracker_connections WHERE account_id = ? FOR UPDATE", accountId) { it.toStored(accountId) }
            .filterNotNull()

    /**
     * Deletes the connections of [profileIds] in the caller's transaction, which holds the account row's lock, and
     * returns those to [revoke] after commit. The caller's push tells the notice streams.
     */
    fun deleteProfiles(tx: Tx, accountId: UUID, profileIds: Collection<String>): List<Stored> {
        val removed = profileIds.flatMap { profileId ->
            tx.query(
                "DELETE FROM tracker_connections WHERE account_id = ? AND profile_id = ? RETURNING $COLUMNS",
                accountId, profileId,
            ) { it.toStored(accountId) }
        }
        if (removed.isNotEmpty()) tx.bumpVersion(accountId)
        return removed.filterNotNull()
    }

    /**
     * Revokes deleted [connections] at their services, all at once, best effort: the providers log failures and never
     * throw, and each gives up after its own timeout, so a deleted account or profile waits for one revoke at most.
     */
    suspend fun revoke(accountId: UUID, connections: List<Stored>) = coroutineScope {
        for (connection in connections) {
            launch {
                trackerProvider(http, connection.service, connection.clientId, MAX_TOKEN_ANSWER_BYTES).revoke(connection.session)
                log.atInfo().addKeyValue("accountId", accountId).addKeyValue("service", connection.service.id)
                    .log("Tracker disconnected")
            }
        }
    }

    /**
     * Refreshes [stored], which this request of [session] has claimed, at its service and stores the result; null when
     * the service refused it (the row is then gone) or the connection was signed in again meanwhile.
     */
    private suspend fun refreshClaimed(session: Session, stored: Stored, refresher: TokenRefresher): TrackerAccessToken? {
        val accountId = session.accountId
        val refreshed = try {
            refresher.refresh(stored.session)
        } catch (e: TrackerException.Auth) {
            if (!e.permanent) {
                db.transaction { release(accountId, stored) }
                throw unavailable(accountId, e)
            }
            db.transaction { deleteUnchanged(session, stored) }
            log.atInfo().addKeyValue("accountId", accountId).addKeyValue("service", stored.service.id)
                .addKeyValue("status", e.status).log("Tracker refresh refused; connection deleted")
            return null
        } catch (e: Throwable) {
            db.transaction { release(accountId, stored) }
            if (e is TrackerException.Network) throw unavailable(accountId, e)
            throw e
        }
        val sealed = seal(accountId, stored.profileId, stored.service, SealedSession(stored.clientId, refreshed))
        // The old refresh token may be spent: the new session is stored, or revoked below, however busy the pool is.
        val kept = db.transaction(wait = Duration.INFINITE) {
            update(
                """
                UPDATE tracker_connections SET sealed_session = ?, refreshing_until = NULL
                WHERE account_id = ? AND profile_id = ? AND service = ? AND sealed_session = ?
                """,
                sealed, accountId, stored.profileId, stored.service.id, stored.sealed,
            ) == 1
        }
        if (kept) {
            log.atInfo().addKeyValue("accountId", accountId).addKeyValue("service", stored.service.id).log("Tracker token refreshed")
            return answer(refreshed)
        }
        // The row changed while the service answered. Disconnected or deleted with its profile or account: their
        // revocation had the old tokens, so this one revokes the new. Signed in again: those tokens are the new
        // sign-in's to keep. Refreshed by another request after this claim lapsed: that result stands.
        val now = db.transaction(wait = Duration.INFINITE) { find(accountId, stored.profileId, stored.service) }
        if (now == null) {
            revoke(accountId, listOf(Stored(stored.profileId, stored.service, stored.connectedAtMillis, stored.clientId, refreshed, sealed)))
        }
        return now?.takeIf { it.connectedAtMillis == stored.connectedAtMillis }?.let { answer(it.session) }
    }

    /** The token must be refreshed first: the service rejected it, or it expires within the window and refreshes. */
    private fun refreshDue(stored: Stored, provider: TrackerProvider, rejected: String?): Boolean =
        rejected == stored.session.accessToken ||
            (provider.refresher != null && stored.session.needsRefresh(clock.instant().epochSecond))

    private fun answer(session: TrackerSession) = TrackerAccessToken(
        accessToken = session.accessToken,
        expiresInSeconds = session.expiresAt?.let { (it - clock.instant().epochSecond).coerceAtLeast(0) },
    )

    private fun validate(request: TrackerConnectRequest): TrackerService {
        val service = TrackerService.fromId(request.service) ?: throw badRequest("Unknown service")
        if (request.profileId.isEmpty() || request.profileId.length > TrackerConnectionsApi.MAX_PROFILE_ID_LENGTH) {
            throw badRequest("profileId out of range")
        }
        if (request.clientId.length > TrackerConnectionsApi.MAX_CLIENT_ID_LENGTH) throw badRequest("clientId too long")
        if (request.accessToken.isBlank()) throw badRequest("accessToken is blank")
        if (request.accessToken.length > TrackerConnectionsApi.MAX_TOKEN_LENGTH) throw badRequest("accessToken too long")
        if ((request.refreshToken?.length ?: 0) > TrackerConnectionsApi.MAX_TOKEN_LENGTH) throw badRequest("refreshToken too long")
        if ((request.username?.length ?: 0) > TrackerConnectionsApi.MAX_USERNAME_LENGTH) throw badRequest("username too long")
        if (trackerProvider(http, service, request.clientId, MAX_TOKEN_ANSWER_BYTES).refresher != null && request.refreshToken.isNullOrBlank()) {
            throw badRequest("${service.id} needs a refresh token")
        }
        return service
    }

    private fun Tx.find(accountId: UUID, profileId: String, service: TrackerService): Stored? = queryOne(
        "SELECT $COLUMNS FROM tracker_connections WHERE account_id = ? AND profile_id = ? AND service = ?",
        accountId, profileId, service.id,
    ) { it.toStored(accountId) }

    /** Claims [stored] for a refresh until the lease ends; false if the row changed or another refresh holds it. */
    private fun Tx.claim(accountId: UUID, stored: Stored): Boolean {
        val now = clock.instant()
        return update(
            """
            UPDATE tracker_connections SET refreshing_until = ?
            WHERE account_id = ? AND profile_id = ? AND service = ? AND sealed_session = ?
                AND (refreshing_until IS NULL OR refreshing_until <= ?)
            """,
            now + CLAIM_LEASE.toJavaDuration(), accountId, stored.profileId, stored.service.id, stored.sealed, now,
        ) == 1
    }

    /** Lets go of a claim whose refresh stored nothing, so the next request tries again. */
    private fun Tx.release(accountId: UUID, stored: Stored) {
        update(
            """
            UPDATE tracker_connections SET refreshing_until = NULL
            WHERE account_id = ? AND profile_id = ? AND service = ? AND sealed_session = ?
            """,
            accountId, stored.profileId, stored.service.id, stored.sealed,
        )
    }

    /** Deletes [stored] if the row still holds it, for a request of [session]; true if it did. */
    private fun Tx.deleteUnchanged(session: Session, stored: Stored): Boolean {
        val account = lockAccount(session.accountId)
        val deleted = update(
            "DELETE FROM tracker_connections WHERE account_id = ? AND profile_id = ? AND service = ? AND sealed_session = ?",
            session.accountId, stored.profileId, stored.service.id, stored.sealed,
        ) == 1
        if (deleted) {
            bumpVersion(session.accountId)
            afterCommit { events.changed(session.accountId, session.sessionId, account.syncVersion) }
        }
        return deleted
    }

    /** Moves the account's tracker connections counter on; the caller holds the account row's lock. */
    private fun Tx.bumpVersion(accountId: UUID) {
        update("UPDATE accounts SET trackers_version = trackers_version + 1 WHERE id = ?", accountId)
    }

    private fun seal(accountId: UUID, profileId: String, service: TrackerService, value: SealedSession): ByteArray =
        cipher.seal(
            Field.TRACKER_SESSION, accountId, index(profileId, service),
            SyncProtocol.json.encodeToString(SealedSession.serializer(), value).encodeToByteArray(),
        )

    /**
     * The row, opened; null when its sealed session doesn't open with the data key (sealed with a key that was lost and
     * replaced), logged. Such a connection is gone (server/README.md, The data key): listings leave it out, a token
     * request answers 410, and deleting it (a disconnect, its profile, the account) revokes nothing; a new sign-in to
     * the service replaces it. A session that opens but doesn't decode is the server's own bug: an
     * [IllegalStateException] naming only the parser's exception type, whose message could quote the tokens.
     */
    private fun ResultSet.toStored(accountId: UUID): Stored? {
        val profileId = getString("profile_id")
        val service = checkNotNull(TrackerService.fromId(getString("service"))) { "A stored tracker connection names an unknown service" }
        val sealed = getBytes("sealed_session")
        val opened = cipher.open(Field.TRACKER_SESSION, accountId, index(profileId, service), sealed)
        if (opened == null) {
            log.atWarn().addKeyValue("accountId", accountId).addKeyValue("service", service.id)
                .log("A stored tracker connection doesn't open with the data key; treated as gone")
            return null
        }
        val value = try {
            SyncProtocol.json.decodeFromString(SealedSession.serializer(), opened.decodeToString())
        } catch (e: SerializationException) {
            throw IllegalStateException("A stored tracker connection's session doesn't decode (${e::class.simpleName})")
        }
        return Stored(profileId, service, instant("connected_at").toEpochMilli(), value.clientId, value.session, sealed)
    }

    /** The row's identity, bound into its sealed session; service ids hold no `/`, so it is unambiguous. */
    private fun index(profileId: String, service: TrackerService): ByteArray = "$profileId/${service.id}".encodeToByteArray()

    private fun unavailable(accountId: UUID, e: TrackerException): ApiException {
        // TrackerException messages carry the service and a status or failure category, never tokens or bodies.
        log.atWarn().addKeyValue("accountId", accountId).addKeyValue("service", e.service.id)
            .log("Tracker refresh failed, kept for a later try: {}", e.message)
        return ApiException(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE, "${e.service.id} couldn't refresh the token")
    }

    private fun disconnected() = ApiException(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED, "No such tracker connection")

    /** What `sealed_session` holds: the session and the client id its grant was issued to. */
    @Serializable
    private class SealedSession(val clientId: String, val session: TrackerSession)

    private companion object {
        val log = LoggerFactory.getLogger(TrackerConnections::class.java)
        const val COLUMNS = "profile_id, service, sealed_session, connected_at"

        /**
         * How long a refresh holds its claim: past the outbound client's 15 s request timeout, so a claim lapses only
         * when its instance died mid-refresh.
         */
        val CLAIM_LEASE = 30.seconds

        /** How often a request waiting for another one's refresh reads the row again. */
        val CLAIM_POLL = 100.milliseconds
    }
}
