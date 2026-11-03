package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.auth.AppleClient
import com.edde746.tortuga.server.auth.GitHubClient
import com.edde746.tortuga.server.auth.IdTokenVerifier
import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.Tx
import com.edde746.tortuga.server.db.instant
import com.edde746.tortuga.server.db.isUniqueViolation
import com.edde746.tortuga.server.db.uuid
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.Bearer
import com.edde746.tortuga.server.http.notFound
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.IdentityInfo
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.NonceResponse
import com.edde746.tortuga.sync.api.SignInResponse
import io.ktor.http.HttpStatusCode
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import org.slf4j.LoggerFactory

/**
 * Accounts and their provider identities: sign-in (creating the account on first use), linking and unlinking a
 * provider, account info, and deletion with Sign in with Apple, GitHub and tracker revocation; a deleted account's
 * notice streams end with it.
 */
class Accounts(
    private val db: Database,
    private val verifier: IdTokenVerifier,
    private val apple: AppleClient?,
    private val github: GitHubClient?,
    private val cipher: RecordCipher,
    private val sessions: Sessions,
    private val trackers: TrackerConnections,
    private val events: SyncEvents,
    private val clock: Clock,
) {
    /**
     * A proof of identity: [provider]'s identity [subject] under [issuer], with the [email] the provider verified. It
     * comes from a verified ID token whose nonce and single use were checked, or from a GitHub token that GitHub reset
     * for this server. It carries what revokes the sign-in later: Apple's refresh token for [appleClientId], or the
     * token GitHub issued at the reset.
     */
    private class Proof(
        val provider: String,
        val issuer: String,
        val subject: String,
        val email: String?,
        val appleRefreshToken: String? = null,
        val appleClientId: String? = null,
        val githubToken: String? = null,
    )

    /** An identity row's account and its own id. */
    private class Owner(val accountId: UUID, val identityId: UUID)

    /** What revokes an identity's sign-in: Apple's refresh token and its client, or GitHub's token (opened). */
    private class Revocable(
        val provider: String,
        val appleRefreshToken: String?,
        val appleClientId: String?,
        val githubToken: String?,
    )

    suspend fun issueNonce(): NonceResponse {
        val nonce = Secrets.generate(Secrets.NONCE_PREFIX)
        val expiresAt = clock.instant() + NONCE_TTL.toJavaDuration()
        db.transaction { update("INSERT INTO auth_nonces (nonce, expires_at) VALUES (?, ?)", nonce, expiresAt) }
        return NonceResponse(nonce, NONCE_TTL.inWholeMilliseconds)
    }

    suspend fun signIn(
        provider: String,
        idToken: String,
        nonce: String?,
        authorizationCode: String?,
        device: DeviceDescriptor,
    ): SignInResponse {
        val proof = prove(provider, idToken, nonce, authorizationCode)
        // Two first sign-ins of one identity can race to create it; the loser retries and finds the winner's account.
        repeat(2) { attempt ->
            try {
                // The proof is spent and may hold a token just issued (GitHub's reset, Apple's refresh token), which a
                // shed request would lose: this waits for a connection however busy the pool is.
                return db.transaction(Duration.INFINITE) {
                    val now = clock.instant()
                    val existing = findIdentity(proof)
                    val accountId = existing?.accountId ?: UUID.randomUUID().also {
                        update("INSERT INTO accounts (id, created_at) VALUES (?, ?)", it, now)
                        insertIdentity(it, proof)
                    }
                    if (existing != null) refreshIdentity(existing, proof)
                    val tokens = sessions.create(this, accountId, device)
                    log.atInfo().addKeyValue("accountId", accountId).addKeyValue("provider", provider)
                        .addKeyValue("created", existing == null).log("Signed in")
                    SignInResponse(accountCreated = existing == null, account = info(accountId), session = tokens)
                }
            } catch (e: SQLException) {
                if (!e.isUniqueViolation() || attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    suspend fun accountInfo(bearer: Bearer): AccountInfo = sessions.transaction(bearer) { info(it.accountId) }

    /** Adds another provider identity to the session's account. */
    suspend fun linkIdentity(
        bearer: Bearer,
        provider: String,
        idToken: String,
        nonce: String?,
        authorizationCode: String?,
    ): AccountInfo {
        // Checked before the proof spends the nonce, so a device told its access token expired refreshes it and
        // retries with the same ID token.
        val session = sessions.authenticate(bearer)
        val proof = prove(provider, idToken, nonce, authorizationCode)
        return try {
            // As in signIn: the proof is spent, and a shed request would lose the token it holds.
            db.transaction(Duration.INFINITE) {
                val owner = findIdentity(proof)
                when (owner?.accountId) {
                    null -> insertIdentity(session.accountId, proof)
                    session.accountId -> refreshIdentity(owner, proof)
                    else -> throw ApiException(HttpStatusCode.Conflict, ApiErrorCode.IDENTITY_IN_USE, "Identity in use")
                }
                log.atInfo().addKeyValue("accountId", session.accountId).addKeyValue("provider", provider)
                    .log("Identity linked")
                info(session.accountId)
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) {
                throw ApiException(HttpStatusCode.Conflict, ApiErrorCode.IDENTITY_IN_USE, "Identity in use")
            }
            throw e
        }
    }

    /**
     * Removes an identity, unless it is the account's last; a removed Apple identity's token is then revoked and a
     * removed GitHub identity's grant deleted, best effort.
     */
    suspend fun unlinkIdentity(bearer: Bearer, identityId: UUID): AccountInfo {
        val (accountId, identity) = sessions.transaction(bearer) { session ->
            lockAccount(session.accountId)
            val count = queryOne("SELECT count(*) FROM identities WHERE account_id = ?", session.accountId) { it.getInt(1) }!!
            val row = queryOne(
                "SELECT $REVOCABLE_COLUMNS FROM identities WHERE id = ? AND account_id = ?",
                identityId, session.accountId,
            ) { it.revocable(session.accountId) } ?: throw notFound("No such identity")
            if (count <= 1) throw ApiException(HttpStatusCode.Conflict, ApiErrorCode.LAST_IDENTITY, "Last identity")
            update("DELETE FROM identities WHERE id = ?", identityId)
            session.accountId to row
        }
        revokeApple(listOf(identity), accountId)
        if (identity.provider == IdentityProvider.GITHUB) deleteGitHubGrants(listOf(identity), accountId)
        return db.transaction { info(accountId) }
    }

    /**
     * Deletes the account and everything under it (the foreign keys cascade to identities, sessions, records, tracker
     * connections and the device links it approved), after revoking Sign in with Apple where this server can and
     * deleting its GitHub grants (best effort); then revokes its tracker connections at their services (best effort).
     */
    suspend fun delete(bearer: Bearer): AccountDeletionResponse {
        val (accountId, identities) = sessions.transaction(bearer) { session ->
            lockAccount(session.accountId)
            session.accountId to query(
                "SELECT $REVOCABLE_COLUMNS FROM identities WHERE account_id = ?",
                session.accountId,
            ) { it.revocable(session.accountId) }
        }
        val appleIdentities = identities.filter { it.provider == IdentityProvider.APPLE }
        val appleRevocation = when {
            appleIdentities.isEmpty() -> AppleRevocation.NONE
            apple == null -> AppleRevocation.NOT_CONFIGURED
            else -> {
                val revoked = revokeApple(appleIdentities, accountId)
                when {
                    // Signed in before this server held the key: nothing to revoke with.
                    appleIdentities.any { it.appleRefreshToken == null } -> AppleRevocation.NOT_CONFIGURED
                    revoked -> AppleRevocation.REVOKED
                    else -> AppleRevocation.FAILED
                }
            }
        }
        val githubIdentities = identities.filter { it.provider == IdentityProvider.GITHUB }
        val githubRevocation = when {
            githubIdentities.isEmpty() -> GitHubRevocation.NONE
            deleteGitHubGrants(githubIdentities, accountId) -> GitHubRevocation.REVOKED
            else -> GitHubRevocation.FAILED
        }
        // The account row stays locked from reading the tracker connections to deleting them with it, so a connection
        // stored meanwhile waits and then finds no account: none is deleted without being revoked.
        val trackerConnections = db.transaction {
            lockAccount(accountId)
            afterCommit { events.endAccount(accountId) }
            trackers.readAll(this, accountId).also { update("DELETE FROM accounts WHERE id = ?", accountId) }
        }
        trackers.revoke(accountId, trackerConnections)
        log.atInfo().addKeyValue("accountId", accountId).addKeyValue("appleRevocation", appleRevocation)
            .addKeyValue("githubRevocation", githubRevocation).log("Account deleted")
        return AccountDeletionResponse(appleRevocation, githubRevocation)
    }

    /**
     * Verifies [idToken], consumes its nonce (which an Apple token must carry), marks it used until the verifier stops
     * accepting it, and for Apple (when this server holds the key) exchanges the authorization code for the refresh
     * token revocation needs. A GitHub [idToken] is an access token instead ([proveGitHub]).
     */
    private suspend fun prove(provider: String, idToken: String, nonce: String?, authorizationCode: String?): Proof {
        if (provider == IdentityProvider.GITHUB) return proveGitHub(idToken)
        val token = verifier.verify(provider, idToken)
        if (token.provider.id == IdentityProvider.APPLE && token.nonce == null) throw invalidToken("no nonce")
        db.transaction {
            val now = clock.instant()
            if (token.nonce != null) {
                if (nonce != token.nonce) throw invalidToken("nonce mismatch")
                val consumed = update("DELETE FROM auth_nonces WHERE nonce = ? AND expires_at > ?", nonce, now)
                if (consumed == 0) throw invalidToken("unknown or expired nonce")
            }
            val fresh = update(
                "INSERT INTO used_id_tokens (token_hash, expires_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
                token.tokenHash, token.acceptedUntil,
            )
            if (fresh == 0) throw invalidToken("already used")
        }
        val appleRefreshToken = if (token.provider.id == IdentityProvider.APPLE && apple != null) {
            val code = authorizationCode?.takeIf { it.isNotBlank() } ?: throw ApiException(
                HttpStatusCode.BadRequest, ApiErrorCode.APPLE_CODE_REQUIRED, "authorizationCode is required",
            )
            apple.exchangeCode(code, token.audience, token.subject)
        } else {
            null
        }
        return Proof(
            provider = token.provider.id,
            issuer = token.provider.issuer,
            subject = token.subject,
            email = token.email,
            appleRefreshToken = appleRefreshToken,
            appleClientId = appleRefreshToken?.let { token.audience },
        )
    }

    /**
     * Resets the GitHub [accessToken] at GitHub, which refuses another app's token and ends this one, so it signs in
     * once; the identity is the numeric user id GitHub answers, with the primary verified email the new token reads.
     */
    private suspend fun proveGitHub(accessToken: String): Proof {
        val client = github
            ?: throw ApiException(HttpStatusCode.BadRequest, ApiErrorCode.PROVIDER_NOT_ENABLED, "Provider not enabled")
        val reset = client.resetToken(accessToken)
        return Proof(
            provider = IdentityProvider.GITHUB,
            issuer = client.issuer,
            subject = reset.userId.toString(),
            email = client.primaryEmail(reset.token),
            githubToken = reset.token,
        )
    }

    private fun Tx.findIdentity(proof: Proof): Owner? = queryOne(
        "SELECT account_id, id FROM identities WHERE issuer = ? AND subject = ?",
        proof.issuer, proof.subject,
    ) { Owner(it.uuid("account_id"), it.uuid("id")) }

    private fun Tx.insertIdentity(accountId: UUID, proof: Proof) {
        val now = clock.instant()
        val identityId = UUID.randomUUID()
        update(
            """
            INSERT INTO identities (id, account_id, provider, issuer, subject, email, apple_refresh_token,
                                    apple_client_id, github_token, created_at, last_used_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            identityId, accountId, proof.provider, proof.issuer, proof.subject, proof.email, proof.appleRefreshToken,
            proof.appleClientId, proof.githubToken?.let { sealGitHubToken(accountId, identityId, it) }, now, now,
        )
    }

    /** Keeps the newest email, Apple refresh token and GitHub token of a returning identity. */
    private fun Tx.refreshIdentity(owner: Owner, proof: Proof) {
        update(
            """
            UPDATE identities SET last_used_at = ?, email = COALESCE(?::text, email),
                apple_refresh_token = COALESCE(?::text, apple_refresh_token),
                apple_client_id = COALESCE(?::text, apple_client_id),
                github_token = COALESCE(?::bytea, github_token)
            WHERE id = ?
            """,
            clock.instant(), proof.email, proof.appleRefreshToken, proof.appleClientId,
            proof.githubToken?.let { sealGitHubToken(owner.accountId, owner.identityId, it) }, owner.identityId,
        )
    }

    private fun Tx.info(accountId: UUID): AccountInfo {
        val createdAt = queryOne("SELECT created_at FROM accounts WHERE id = ?", accountId) { it.instant("created_at") }
            ?: throw accountGone()
        val identities = query(
            "SELECT id, provider, email, created_at FROM identities WHERE account_id = ? ORDER BY created_at",
            accountId,
        ) {
            IdentityInfo(
                identityId = it.uuid("id").toString(),
                provider = it.getString("provider"),
                email = it.getString("email"),
                createdAtMillis = it.instant("created_at").toEpochMilli(),
            )
        }
        return AccountInfo(accountId.toString(), createdAt.toEpochMilli(), identities)
    }

    /** Revokes every Apple refresh token among [identities]; true only if all of them were revoked. */
    private suspend fun revokeApple(identities: List<Revocable>, accountId: UUID): Boolean {
        val client = apple ?: return false
        var all = true
        for (identity in identities) {
            val refresh = identity.appleRefreshToken ?: continue
            val clientId = identity.appleClientId ?: continue
            if (!client.revoke(refresh, clientId)) {
                all = false
                log.atWarn().addKeyValue("accountId", accountId).log("Sign in with Apple revocation failed")
            }
        }
        return all
    }

    /**
     * Deletes the grant of each GitHub identity in [identities] with its kept token; true only if GitHub deleted every
     * one (never when this server no longer has GitHub configured).
     */
    private suspend fun deleteGitHubGrants(identities: List<Revocable>, accountId: UUID): Boolean {
        var all = true
        for (identity in identities) {
            val token = identity.githubToken
            if (github == null || token == null || !github.deleteGrant(token)) {
                all = false
                log.atWarn().addKeyValue("accountId", accountId).addKeyValue("githubConfigured", github != null)
                    .log("GitHub grant deletion failed")
            }
        }
        return all
    }

    private fun sealGitHubToken(accountId: UUID, identityId: UUID, token: String): ByteArray = cipher.seal(
        Field.GITHUB_TOKEN, accountId, identityId.toString().encodeToByteArray(), token.encodeToByteArray(),
    )

    /**
     * The identity row as [Revocable]. A GitHub token that doesn't open with the data key (sealed with a key that was
     * lost and replaced) is logged and left out: there is nothing to delete its grant with, which counts as failed.
     */
    private fun ResultSet.revocable(accountId: UUID): Revocable = Revocable(
        provider = getString("provider"),
        appleRefreshToken = getString("apple_refresh_token"),
        appleClientId = getString("apple_client_id"),
        githubToken = getBytes("github_token")?.let { sealed ->
            val index = uuid("id").toString().encodeToByteArray()
            val opened = cipher.open(Field.GITHUB_TOKEN, accountId, index, sealed)
            if (opened == null) {
                log.atWarn().addKeyValue("accountId", accountId).log("A stored GitHub token doesn't open with the data key")
            }
            opened?.decodeToString()
        },
    )

    private companion object {
        val log = LoggerFactory.getLogger(Accounts::class.java)

        /** How long a sign-in nonce stays usable. */
        val NONCE_TTL = 10.minutes

        /** The columns [revocable] reads. */
        const val REVOCABLE_COLUMNS = "id, provider, apple_refresh_token, apple_client_id, github_token"

        fun invalidToken(reason: String) =
            ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN, "ID token rejected: $reason")
    }
}

/** Locks the account row for the rest of the transaction; serializes writes that bump its counters. */
internal fun Tx.lockAccount(accountId: UUID): AccountRow =
    queryOne("SELECT sync_version, stored_bytes FROM accounts WHERE id = ? FOR UPDATE", accountId) {
        AccountRow(it.getLong("sync_version"), it.getLong("stored_bytes"))
    } ?: throw accountGone()

internal class AccountRow(val syncVersion: Long, val storedBytes: Long)

/** The session's account was deleted while the request ran. */
internal fun accountGone() = ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED, "Account is gone")
