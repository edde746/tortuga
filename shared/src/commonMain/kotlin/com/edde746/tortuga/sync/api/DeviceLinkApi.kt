package com.edde746.tortuga.sync.api

import kotlinx.serialization.Serializable

/**
 * [SyncPaths.LINK_START]: a signed-out device opens a device link, the sign-in for devices without
 * easy typing (TVs) and a shortcut anywhere else.
 *
 * 1. The new device draws a random secret (32 bytes, unpadded base64url), keeps it in memory, posts its hash here
 *    ([secretHash]) and shows [LinkStartResponse.code] as text and as a QR code (with the server URL).
 * 2. A signed-in device takes the code (typed or scanned), claims it ([LinkCodeRequest] at [SyncPaths.LINK_CLAIM]) to
 *    show which device asks, and on the user's confirmation approves ([SyncPaths.LINK_APPROVE]) or rejects
 *    ([SyncPaths.LINK_REJECT]) it.
 * 3. The new device polls with its secret ([LinkPollRequest]) until the link is approved, rejected or gone. The poll
 *    that finds it approved creates the new device's own session on the approver's account and returns it once.
 *
 * Only the device that started the link can collect the session: the server keeps only the secret's hash, and the
 * code a user types or scans names the link without authorizing its poll.
 *
 * Links are single-use and expire after ten minutes. Starting and polling are rate limited per client address;
 * claiming, approving and rejecting per session.
 *
 * @property secretHash unpadded base64url of SHA-256 of the secret's UTF-8 bytes (43 characters).
 */
@Serializable
data class LinkStartRequest(val secretHash: String, val device: DeviceDescriptor)

/**
 * Response to [LinkStartRequest].
 *
 * @property code eight random characters from [LINK_CODE_ALPHABET], shown as `ABCD-EFGH`. The server ignores case,
 *   spaces and hyphens in codes it receives.
 * @property expiresInMillis the link's lifetime from this response; the code stops working when it runs out.
 */
@Serializable
data class LinkStartResponse(val linkId: String, val code: String, val expiresInMillis: Long)

/** [SyncPaths.LINK_CLAIM], [SyncPaths.LINK_APPROVE] and [SyncPaths.LINK_REJECT]: names a link by its code. */
@Serializable
data class LinkCodeRequest(val code: String)

/**
 * Response to a claim: the device that started the link, for the confirmation screen. [ApiErrorCode.NOT_FOUND] if
 * the code is unknown, expired, or already approved or rejected (as for approving and rejecting).
 *
 * @property expiresInMillis what is left of the link's lifetime at this response.
 */
@Serializable
data class LinkClaimResponse(val device: DeviceDescriptor, val expiresInMillis: Long)

/**
 * [SyncPaths.LINK_POLL]: the new device asks whether its link was answered.
 *
 * @property secret the secret whose hash started the link ([LinkStartRequest.secretHash]).
 */
@Serializable
data class LinkPollRequest(val linkId: String, val secret: String)

/**
 * Response to [LinkPollRequest]. [ApiErrorCode.NOT_FOUND] once the link has expired or its answer was collected, and
 * for a secret that doesn't match.
 *
 * @property status one of [LinkStatus]. An approved or rejected answer is returned once and the link is deleted.
 * @property accountId set when approved: the account joined.
 * @property session set when approved: the new device's own session on the account.
 */
@Serializable
data class LinkPollResponse(
    val status: String,
    val accountId: String? = null,
    val session: SessionTokens? = null,
)

/** Values of [LinkPollResponse.status]. */
object LinkStatus {
    const val PENDING: String = "pending"
    const val APPROVED: String = "approved"
    const val REJECTED: String = "rejected"
}

/** The 32 characters of device-link codes: A–Z and 2–9 without the look-alikes I, O, 0 and 1. */
const val LINK_CODE_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Length of a device-link code; 8 characters carry 40 bits. */
const val LINK_CODE_LENGTH: Int = 8

/**
 * [code] as typed: upper-cased, without spaces and hyphens. Returns null unless the result is [LINK_CODE_LENGTH]
 * characters of [LINK_CODE_ALPHABET].
 */
fun normalizeLinkCode(code: String): String? {
    val normalized = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()
    return normalized.takeIf { it.length == LINK_CODE_LENGTH && it.all { c -> c in LINK_CODE_ALPHABET } }
}

/**
 * What a link-code field keeps of [text] as typed: its letters and digits, either case, at most [LINK_CODE_LENGTH];
 * a dash, a space or a character past the length is refused. [normalizeLinkCode] checks the alphabet on submit.
 */
fun typedLinkCode(text: String): String = text.filter { it.uppercaseChar() in 'A'..'Z' || it in '0'..'9' }.take(LINK_CODE_LENGTH)

/** A device-link code as shown: capitals, a dash after every four characters (`ABCD-EFGH`, `ABCD-EF` while typed). */
fun formatLinkCode(code: String): String = code.uppercase().chunked(4).joinToString("-")
