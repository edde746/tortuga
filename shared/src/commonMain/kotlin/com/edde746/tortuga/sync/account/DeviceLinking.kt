package com.edde746.tortuga.sync.account

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.Backoff
import com.edde746.tortuga.sync.api.LinkClaimResponse
import com.edde746.tortuga.sync.api.LinkPollRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartResponse
import com.edde746.tortuga.sync.api.LinkStatus
import com.edde746.tortuga.sync.api.formatLinkCode
import com.edde746.tortuga.sync.api.normalizeLinkCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a device-link QR code carries: the new device's sync server and its link code, as
 * `tortuga://link?server=<percent-encoded URL>&code=<8 characters>`. A phone camera that scans it opens Tortuga on that
 * link, which shows the approval in Settings → Account
 * ([com.edde746.tortuga.presentation.settings.AccountSettingsStore.submitScannedLink], then
 * [SyncAccount.claimDeviceLink]).
 */
data class DeviceLinkQr(val serverUrl: ServerUrl, val code: String) {
    fun encode(): String = "$SCHEME://$HOST?server=${serverUrl.value.encodeURLParameter()}&code=$code"

    companion object {
        private const val SCHEME = "tortuga"
        private const val HOST = "link"

        /** Parses [encode]'s form; null for any other text, or one whose server isn't a server URL ([ServerUrl.parse]). */
        fun parse(text: String): DeviceLinkQr? {
            val trimmed = text.trim()
            val prefix = "$SCHEME://$HOST?"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
            val parameters = trimmed.substring(prefix.length).split('&').mapNotNull { part ->
                val name = part.substringBefore('=', "")
                if (name.isEmpty()) null else name to part.substringAfter('=').decodeURLQueryComponent()
            }.toMap()
            val server = try {
                ServerUrl.parse(parameters["server"] ?: return null)
            } catch (_: AccountException) {
                return null
            }
            val code = parameters["code"]?.let(::normalizeLinkCode) ?: return null
            return DeviceLinkQr(server, code)
        }
    }
}

/** How a device link ended for the new device ([PendingDeviceLink.awaitResult]). */
enum class DeviceLinkOutcome {
    /** The approval arrived with this device's session: the account is [AccountState.SignedIn]. */
    LINKED,

    /** The user declined on the other device. */
    REJECTED,

    /** Nobody answered before the code expired; start a new link for a new code. */
    EXPIRED,
}

/**
 * The new device's side of a device link: show [code] (and [qrPayload] as a QR code) until
 * [awaitResult] returns. Cancelling the coroutine that awaits abandons the link; its code expires on the server.
 * The link's secret, which alone collects the session, lives only in this object: a link that outlives the process
 * starts over.
 */
class PendingDeviceLink internal constructor(
    private val account: SyncAccount,
    val serverUrl: ServerUrl,
    private val secret: String,
    private val start: LinkStartResponse,
    private val pollIntervalMillis: Long,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The code to type on the signed-in device, `ABCD-EFGH`. */
    val code: String = formatLinkCode(start.code)

    /** The text of the QR code to show ([DeviceLinkQr]). */
    val qrPayload: String = DeviceLinkQr(serverUrl, start.code).encode()

    /**
     * When the code runs out, by this device's clock: the link's lifetime from the server, counted from when its
     * answer arrived (this object is made then), so a clock that differs from the server's doesn't move it.
     */
    val expiresAtMillis: Long = account.clock() + start.expiresInMillis

    /** Polls at once instead of at the next interval; call it when the app returns to the foreground. */
    fun pollNow() {
        wake.trySend(Unit)
    }

    /**
     * Polls with the link's secret until the link is answered or expires. On approval it stores the session the server
     * made for this device and returns [DeviceLinkOutcome.LINKED]; an approval by another account than the one whose
     * data this device holds asks [discardUnsynced] first, as [SyncAccount.completeSignIn] does. Network failures and
     * server errors only delay the next poll, longer with each one in a row (with jitter, up to
     * [MAX_ERROR_WAIT_MILLIS]); a rate limit waits at least its `Retry-After`.
     *
     * @throws AccountException [AccountError.LinkFailed] if the approval came without a session;
     *   [AccountError.UnsyncedChanges] when [discardUnsynced] said no.
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun awaitResult(discardUnsynced: suspend (unsynced: Long) -> Boolean = { false }): DeviceLinkOutcome {
        var errors = 0
        while (true) {
            val remaining = expiresAtMillis - account.clock()
            if (remaining <= 0) return DeviceLinkOutcome.EXPIRED
            var wait = pollIntervalMillis
            val answer: LinkPollResponse? = try {
                account.api.linkPoll(serverUrl.value, LinkPollRequest(start.linkId, secret)).also { errors = 0 }
            } catch (e: AccountException) {
                when (val error = e.error) {
                    AccountError.NotFound -> return DeviceLinkOutcome.EXPIRED
                    AccountError.Network, is AccountError.Server -> null.also { wait = Backoff.poll(++errors, pollIntervalMillis, MAX_ERROR_WAIT_MILLIS) }
                    is AccountError.RateLimited -> null.also { wait = maxOf(wait, error.retryAfterMillis) }
                    else -> throw e
                }
            }
            when (answer?.status) {
                LinkStatus.APPROVED -> {
                    account.completeDeviceLink(serverUrl, answer, discardUnsynced)
                    return DeviceLinkOutcome.LINKED
                }
                LinkStatus.REJECTED -> return DeviceLinkOutcome.REJECTED
            }
            withTimeoutOrNull(minOf(wait, remaining)) { wake.receive() }
        }
    }

    private companion object {
        /** The longest wait between polls after failures in a row: the code stays usable for minutes. */
        const val MAX_ERROR_WAIT_MILLIS = 30_000L
    }
}

/**
 * The signed-in device's side of a device link: the device that asked, for the confirmation screen ("Link
 * <device>?"), found by its code ([SyncAccount.claimDeviceLink]).
 */
class IncomingDeviceLink internal constructor(
    private val account: SyncAccount,
    private val normalizedCode: String,
    private val claim: LinkClaimResponse,
) {
    /** `ABCD-EFGH`. */
    val code: String = formatLinkCode(normalizedCode)
    val deviceName: String get() = claim.device.name

    /** One of [com.edde746.tortuga.sync.api.DevicePlatform]. */
    val platform: String get() = claim.device.platform

    /** When the link runs out, by this device's clock: what was left of it on the server when the claim arrived. */
    val expiresAtMillis: Long = account.clock() + claim.expiresInMillis

    /**
     * Approves the link: the new device's next poll signs it in to this account with its own session.
     *
     * @throws AccountException [AccountError.LinkNotFound] once the link expired or was answered.
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun approve() = account.approveDeviceLink(normalizedCode)

    /** Declines the link; the new device's poll reports [DeviceLinkOutcome.REJECTED]. */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun reject() = account.rejectDeviceLink(normalizedCode)

    override fun toString(): String = "IncomingDeviceLink(code=$code, platform=$platform)"
}

internal fun logLink(message: () -> String) = Log.i("DeviceLink", null, message)
