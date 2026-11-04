package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.presentation.account.DeviceCodeUiState
import com.edde746.tortuga.trackers.TrackerService

/**
 * The Trackers section of Settings: the tracker services this build can connect, each with its
 * connection on this profile (connections belong to the Tortuga account and apply on every device of it),
 * connecting and disconnecting, and a connected account's switches (the profile's, on every device of it too).
 *
 * @param note what a connection is and where it applies: Connect, Disconnect and the switches on every device.
 * @param services one row per service this build has a registration for, in [TrackerService] order, plus any
 *   connection whose service this build can no longer connect ([TrackerServiceRow.available] false), so it can still
 *   be disconnected. Services without a registration are left out.
 * @param connect the connect flow in progress (a sheet or dialog over the section), or null.
 * @param dialog a confirmation the user answers with `confirmDialog` or `dismissDialog`, or null.
 * @param notice what the last action did or why it failed ("Trakt disconnected."), until `dismissNotice` or the next
 *   action.
 */
data class TrackersSettingsUiState(
    val title: String,
    val note: String,
    val services: List<TrackerServiceRow>,
    val connect: TrackerConnectUiState?,
    val dialog: TrackerConfirmDialog?,
    val notice: String?,
)

/**
 * One tracker service. Intents take [service].
 *
 * @param name "Trakt".
 * @param status "Connected as alex", "Not connected", or why the service isn't offered.
 * @param available this build can connect the service; false only for a leftover connection, which can only be
 *   disconnected.
 * @param canConnect `connect(service)` is offered: [available], not connected, and no connect flow is running.
 * @param connectLabel the Connect button's word, "Connect"; [connectNamedLabel] its name with the service's, "Connect
 *   Trakt", which screen readers say.
 * @param disconnectLabel the Disconnect button's word; [disconnectNamedLabel] its name, "Disconnect Trakt".
 * @param account the connected account's switches; null while not connected, and for a connection that isn't
 *   [available].
 */
data class TrackerServiceRow(
    val service: TrackerService,
    val name: String,
    val status: String,
    val connected: Boolean,
    val username: String?,
    val available: Boolean,
    val canConnect: Boolean,
    val connectLabel: String,
    val connectNamedLabel: String,
    val disconnectLabel: String,
    val disconnectNamedLabel: String,
    val account: TrackerAccountUiState?,
)

/**
 * A connected account's switches, the profile's on every device: whether anything goes to the service ([scrobble]:
 * live playback reports and watched marks), and which content types do (movie / series / anime).
 *
 * @param filtersHeader the heading above [movies], [series] and [anime].
 * @param queued "2 changes waiting to be sent": watched marks and ratings that failed and wait for a retry; null when
 *   none.
 * @param failed "2 changes couldn’t be sent (last: Trakt answered error 422)": writes given up on (the service refused
 *   them at every attempt, or they could no longer be read), with why the latest was; null when none.
 */
data class TrackerAccountUiState(
    val scrobble: SettingToggleRow,
    val filtersHeader: String,
    val movies: SettingToggleRow,
    val series: SettingToggleRow,
    val anime: SettingToggleRow,
    val queuedCount: Int,
    val queued: String?,
    val failedCount: Int,
    val failed: String?,
)

/**
 * Where a connect flow stands; each step carries its status line ([message]): a progress line while the app asks or
 * waits ([Starting], [Code], [Link]), an error line with `retryConnect` once it didn't connect ([Expired], [Declined],
 * [Failed]).
 */
sealed interface TrackerConnectStep {
    val message: String

    /** Asking the service (or the OAuth relay) for a code or a sign-in link. */
    data class Starting(override val message: String) : TrackerConnectStep

    /**
     * A device code is shown: the code to enter at its verification URL, with its QR payload and countdown; the app polls
     * the service until the user approves.
     *
     * @param canOpenInBrowser `openInBrowser` is offered: it opens the verification page in this device's browser. Never
     *   on a TV (its user scans the QR code) or on a device without a browser.
     */
    data class Code(val code: DeviceCodeUiState, override val message: String, val canOpenInBrowser: Boolean) : TrackerConnectStep

    /**
     * A sign-in link is shown: the user signs in in a browser (`openInBrowser`, or the QR code on another device); the
     * app waits for the relay's answer.
     *
     * @param canOpenInBrowser `openInBrowser` is offered, as for [Code].
     */
    data class Link(val link: TrackerLinkUiState, override val message: String, val canOpenInBrowser: Boolean) : TrackerConnectStep

    /** The code expired before the user approved it. `retryConnect` gets a new one. */
    data class Expired(override val message: String) : TrackerConnectStep

    /** The user declined on the service's page. */
    data class Declined(override val message: String) : TrackerConnectStep

    /** Getting a code or finishing the sign-in failed; [message] says why. */
    data class Failed(override val message: String) : TrackerConnectStep
}

/**
 * Connecting one service: device code (Trakt, Simkl, MDBList) or a sign-in link through the OAuth relay (MyAnimeList,
 * AniList). On success the flow closes and the service's row shows the account; `cancelConnect` gives up at any step.
 *
 * @param title "Connect Trakt".
 * @param cancelLabel Cancel while the flow asks or waits, Close once it ended.
 */
data class TrackerConnectUiState(
    val service: TrackerService,
    val title: String,
    val step: TrackerConnectStep,
    val openInBrowserLabel: String,
    val retryLabel: String,
    val cancelLabel: String,
)

/**
 * A relay sign-in link: open [url] in a browser, or scan [qrPayload] on a phone (TVs).
 *
 * @param instructions what to do with the link.
 * @param scanHint the line that offers the QR code to a phone.
 */
data class TrackerLinkUiState(
    val url: String,
    val qrPayload: String,
    val instructions: String,
    val scanHint: String,
)

/** A confirmation (disconnecting [service]) the user answers with `confirmDialog` or `dismissDialog`. */
data class TrackerConfirmDialog(
    val service: TrackerService,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
    val destructive: Boolean,
)

/** What the Trackers section asks of the app around it; called on the main thread. */
interface TrackersSettingsHost {
    /** Open [url] in the browser: a device code's verification page, or a relay sign-in link. */
    fun openUrl(url: String)
}
