package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.instant
import com.edde746.tortuga.server.db.uuid
import com.edde746.tortuga.server.http.Bearer
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.server.http.notFound
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.LINK_CODE_ALPHABET
import com.edde746.tortuga.sync.api.LINK_CODE_LENGTH
import com.edde746.tortuga.sync.api.LinkClaimResponse
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.LinkStartResponse
import com.edde746.tortuga.sync.api.LinkStatus
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.sync.api.normalizeLinkCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import org.slf4j.LoggerFactory

/**
 * Device links (protocol on [LinkStartRequest]): a signed-out device asks to sign in, a signed-in device
 * approves it, and the poll that finds the approval opens the new device's own session on the approving account. The
 * server keeps only the hash of the new device's secret, so only that device can collect the session; no key travels.
 */
class DeviceLinks(
    private val db: Database,
    private val sessions: Sessions,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    /** Opens a link under a fresh random code, unique among the stored links. */
    suspend fun start(request: LinkStartRequest): LinkStartResponse {
        val secretHash = decodeSecretHash(request.secretHash)
            ?: throw badRequest("secretHash must be unpadded base64url of a SHA-256 hash")
        validate(request.device)
        val id = UUID.randomUUID()
        val now = clock.instant()
        val expiresAt = now + TTL.toJavaDuration()
        // 40-bit codes collide only by a fluke; another draw settles it.
        repeat(CODE_ATTEMPTS) {
            val code = newCode()
            val inserted = db.transaction {
                // An expired link the janitor hasn't removed yet mustn't hold its code.
                update("DELETE FROM device_links WHERE code = ? AND expires_at <= ?", code, now)
                update(
                    """
                    INSERT INTO device_links (id, code, secret_hash, device_name, platform, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (code) DO NOTHING
                    """,
                    id, code, secretHash, request.device.name, request.device.platform, LinkStatus.PENDING, now,
                    expiresAt,
                )
            }
            if (inserted == 1) return LinkStartResponse(id.toString(), code, TTL.inWholeMilliseconds)
        }
        error("No free link code after $CODE_ATTEMPTS draws")
    }

    /** The device behind an open link, for the approving device's confirmation screen. */
    suspend fun claim(bearer: Bearer, code: String): LinkClaimResponse {
        val normalized = normalized(code)
        val now = clock.instant()
        return sessions.transaction(bearer) { _ ->
            queryOne(
                "SELECT device_name, platform, expires_at FROM device_links WHERE code = ? AND $OPEN",
                normalized, LinkStatus.PENDING, now,
            ) {
                LinkClaimResponse(
                    device = DeviceDescriptor(it.getString("device_name"), it.getString("platform")),
                    expiresInMillis = it.instant("expires_at").toEpochMilli() - now.toEpochMilli(),
                )
            }
        } ?: throw notFound("No open link with this code")
    }

    /** Answers an open link with the session's account: the new device's next poll signs it in there. */
    suspend fun approve(bearer: Bearer, code: String) {
        val accountId = answer(bearer, code, LinkStatus.APPROVED)
        log.atInfo().addKeyValue("accountId", accountId).log("Device link approved")
    }

    suspend fun reject(bearer: Bearer, code: String) {
        answer(bearer, code, LinkStatus.REJECTED)
    }

    /** Answers the link with [status]; an approval names the session's account. Returns that account. */
    private suspend fun answer(bearer: Bearer, code: String, status: String): UUID {
        val normalized = normalized(code)
        val (accountId, updated) = sessions.transaction(bearer) { session ->
            val approvedAccount = session.accountId.takeIf { status == LinkStatus.APPROVED }
            session.accountId to update(
                "UPDATE device_links SET status = ?, approved_account_id = ? WHERE code = ? AND $OPEN",
                status, approvedAccount, normalized, LinkStatus.PENDING, clock.instant(),
            )
        }
        if (updated == 0) throw notFound("No open link with this code")
        return accountId
    }

    /**
     * The new device's poll. Only [secret] whose hash started the link sees it; anything else is "no such link" and
     * changes nothing. An answer is handed over once and the link deleted; an approval opens the device's session.
     *
     * The approving account's row is locked before the link's, the order account deletion takes them in (the account,
     * then its approved links by cascade), so a poll racing its approver's deletion waits for it instead of deadlocking.
     */
    suspend fun poll(linkId: String, secret: String): LinkPollResponse {
        val id = runCatching { UUID.fromString(linkId) }.getOrNull() ?: throw notFound("No such link")
        val presented = Secrets.sha256(secret)
        return db.transaction {
            val locked = queryOne(
                """
                SELECT a.id FROM device_links l JOIN accounts a ON a.id = l.approved_account_id
                WHERE l.id = ? FOR UPDATE OF a
                """,
                id,
            ) { it.uuid("id") }
            val link = queryOne(
                """
                SELECT secret_hash, device_name, platform, status, approved_account_id FROM device_links
                WHERE id = ? AND expires_at > ? FOR UPDATE
                """,
                id, clock.instant(),
            ) {
                StoredLink(
                    secretHash = it.getBytes("secret_hash"),
                    device = DeviceDescriptor(it.getString("device_name"), it.getString("platform")),
                    status = it.getString("status"),
                    approvedAccount = it.getObject("approved_account_id", UUID::class.java),
                )
            }
            if (link == null || !MessageDigest.isEqual(link.secretHash, presented)) throw notFound("No such link")
            when (link.status) {
                LinkStatus.REJECTED -> {
                    update("DELETE FROM device_links WHERE id = ?", id)
                    LinkPollResponse(LinkStatus.REJECTED)
                }
                LinkStatus.APPROVED -> {
                    val accountId = checkNotNull(link.approvedAccount)
                    // Approved after the account lock was looked for: the device's next poll collects it.
                    if (accountId != locked) return@transaction LinkPollResponse(LinkStatus.PENDING)
                    update("DELETE FROM device_links WHERE id = ?", id)
                    val tokens = sessions.create(this, accountId, link.device)
                    log.atInfo().addKeyValue("accountId", accountId).log("Device linked")
                    LinkPollResponse(LinkStatus.APPROVED, accountId.toString(), tokens)
                }
                else -> LinkPollResponse(LinkStatus.PENDING)
            }
        }
    }

    private fun newCode(): String =
        String(CharArray(LINK_CODE_LENGTH) { LINK_CODE_ALPHABET[random.nextInt(LINK_CODE_ALPHABET.length)] })

    private fun normalized(code: String): String = normalizeLinkCode(code) ?: throw notFound("No open link with this code")

    private class StoredLink(
        val secretHash: ByteArray,
        val device: DeviceDescriptor,
        val status: String,
        val approvedAccount: UUID?,
    )

    private companion object {
        val log = LoggerFactory.getLogger(DeviceLinks::class.java)
        const val CODE_ATTEMPTS = 5
        const val SHA256_BYTES = 32

        /** How long a link waits for its approval and its pickup. */
        val TTL = 10.minutes

        /** Unpadded base64url of 32 bytes. */
        const val SECRET_HASH_LENGTH = 43

        /** Parameters: [LinkStatus.PENDING], now. Unanswered and unexpired. */
        const val OPEN = "status = ? AND expires_at > ?"

        fun decodeSecretHash(value: String): ByteArray? {
            if (value.length != SECRET_HASH_LENGTH) return null
            return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()?.takeIf { it.size == SHA256_BYTES }
        }
    }
}

/** Checks a device name and platform against the protocol's limits. */
fun validate(device: DeviceDescriptor) {
    if (device.name.isBlank() || device.name.length > SyncProtocol.MAX_DEVICE_NAME_LENGTH) {
        throw badRequest("Device name must be 1..${SyncProtocol.MAX_DEVICE_NAME_LENGTH} characters")
    }
    if (device.platform.isBlank() || device.platform.length > SyncProtocol.MAX_PLATFORM_LENGTH) {
        throw badRequest("Platform must be 1..${SyncProtocol.MAX_PLATFORM_LENGTH} characters")
    }
}
