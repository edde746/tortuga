package com.edde746.tortuga.trackers

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An RFC 8628 device code: the user enters [userCode] at [verificationUrl] (or scans [verificationUrlComplete] as a QR
 * code, where the service offers one); the app polls every [intervalSeconds] until [expiresInSeconds] pass.
 */
data class DeviceCodeGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlComplete: String? = null,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    override fun toString(): String = "DeviceCodeGrant(userCode=$userCode, expiresIn=$expiresInSeconds, interval=$intervalSeconds)"
}

/** One poll of a device code's token endpoint. */
sealed interface DevicePoll {
    /** The user hasn't approved yet. */
    data object Pending : DevicePoll

    /** Poll less often: the interval grows by [DeviceCodePoller.SLOW_DOWN_STEP]. */
    data object SlowDown : DevicePoll

    data object Denied : DevicePoll

    data object Expired : DevicePoll

    /** Approved: the token endpoint's answer. */
    class Success(val token: JsonObject) : DevicePoll {
        override fun toString(): String = "Success"
    }
}

/** The RFC 8628 poll loop shared by the device-code services. */
object DeviceCodePoller {
    val SLOW_DOWN_STEP = 5.seconds

    /**
     * Waits [DeviceCodeGrant.intervalSeconds] and asks [probe], until it answers [DevicePoll.Success],
     * [DevicePoll.Denied] or [DevicePoll.Expired], or the code's lifetime is over ([DevicePoll.Expired]).
     * Cancelling the caller stops it.
     */
    suspend fun poll(grant: DeviceCodeGrant, timeSource: TimeSource = TimeSource.Monotonic, probe: suspend () -> DevicePoll): DevicePoll {
        var interval = grant.intervalSeconds.coerceAtLeast(1).seconds
        val deadline = timeSource.markNow() + grant.expiresInSeconds.seconds
        while (deadline.hasNotPassedNow()) {
            delay(interval)
            when (val event = probe()) {
                is DevicePoll.Success, DevicePoll.Denied, DevicePoll.Expired -> return event
                DevicePoll.SlowDown -> interval += SLOW_DOWN_STEP
                DevicePoll.Pending -> {}
            }
        }
        return DevicePoll.Expired
    }
}

/**
 * Where the OAuth relay sends the user: [url] shown as a QR code
 * and a link for another device, whose page asks to confirm; [browserUrl] for this device's own browser, which comes
 * back to the app with `state` = [browserState] (both null where the device has no browser to come back from).
 */
data class RelayStart(val session: String, val url: String, val browserUrl: String? = null, val browserState: String? = null) {
    override fun toString(): String = "RelayStart"
}

/** The relay's answer once the user authorized. */
data class RelayResult(val accessToken: String, val refreshToken: String? = null, val expiresInSeconds: Long? = null) {
    override fun toString(): String = "RelayResult(expiresIn=$expiresInSeconds)"
}

/** The OAuth relay's client, for MyAnimeList and AniList, whose secrets only the relay holds. */
interface OAuthRelay {
    /**
     * Opens a relay session for [service]; with [redirectUri] (this device's browser comes back there) its start has
     * [RelayStart.browserUrl]. @throws TrackerException.SignIn when the relay can't serve it.
     */
    suspend fun start(service: TrackerService, redirectUri: String?): RelayStart

    /**
     * Long-polls [session] (opened for [service]) until the user authorized on another device and confirmed there (the
     * tokens), declined (`null`), or the session ended ([TrackerException.SignIn]). Transient failures are retried
     * inside; cancelling the caller stops it.
     */
    suspend fun poll(service: TrackerService, session: String): RelayResult?

    /**
     * The tokens of [session] for the [proof] this device's own browser came back with.
     * @throws TrackerException.SignIn when the relay has none for it.
     */
    suspend fun collect(service: TrackerService, session: String, proof: String): RelayResult
}

/** How a service's account connects. */
sealed interface TrackerSignIn {
    /** Device code, direct with the service (Trakt, Simkl, MDBList). */
    interface DeviceCode : TrackerSignIn {
        suspend fun requestCode(): DeviceCodeGrant

        /** One poll of the token endpoint for [grant]. */
        suspend fun probe(grant: DeviceCodeGrant): DevicePoll

        /** The session from the approved [token] answer, with the account's username looked up. */
        suspend fun session(token: JsonObject): TrackerSession
    }

    /** Through the [OAuthRelay] (MyAnimeList with PKCE, AniList). */
    interface Relay : TrackerSignIn {
        /** The session from the relay's [result], with the account's username looked up. */
        suspend fun session(result: RelayResult): TrackerSession
    }
}
