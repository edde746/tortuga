package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.tracker.TrackerAccount
import com.edde746.tortuga.data.tracker.TrackerQueueCounts
import com.edde746.tortuga.data.tracker.TrackerWriteFailure
import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.account.DeviceCodeUiState
import com.edde746.tortuga.presentation.account.displayHost
import com.edde746.tortuga.presentation.account.nextTickMillis
import com.edde746.tortuga.presentation.account.remainingText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.RelayStart
import com.edde746.tortuga.trackers.SignInPrompt
import com.edde746.tortuga.trackers.TrackerAccounts
import com.edde746.tortuga.trackers.TrackerContentType
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Trackers section's store for [profileId]: the services [accounts] can connect and the profile's
 * connections, which belong to the Tortuga account and apply on every device of it, following them live
 * (a connection another device or screen changed, one the account server dropped); connecting by device code or
 * relay link, with the code's countdown; disconnecting after a confirmation; a connected account's switches on this
 * device (sending at all, and movies / series / anime); and each account's queued writes, and those given up on. Without
 * an account session Connect only says that one is needed.
 *
 * "Open in browser" ([TrackerConnectStep.Code.canOpenInBrowser]) is offered where the user can sign in in a browser on
 * this device: not on a [tv], whose user signs in on a phone through the QR code (most Android TVs have no browser,
 * Apple TV has none, and a password typed with a remote is slow), and not where [signIn] finds no browser
 * ([SignInPlatform.canOpenBrowser]). A relay link opened there goes through [signIn]'s browser, which comes back to the
 * app with nothing to confirm; without [signIn] (a development session), and for a relay that can't return to the
 * device, it opens the link the QR code shows, whose page asks to confirm.
 *
 * One connect flow runs at a time; leaving it (`cancelConnect`, [release]) stops polling. Tokens never reach the state
 * or the logs.
 *
 * Call everything on the main thread; [release] ends it.
 */
class TrackersSettingsStore internal constructor(
    private val profileId: String,
    private val accounts: TrackerAccounts,
    private val queue: TrackerWriteQueue,
    private val host: TrackersSettingsHost,
    signIn: SignInPlatform?,
    tv: Boolean,
    private val now: () -> Long,
    parentScope: CoroutineScope,
) {
    /** "Open in browser" works here: not a TV, and a browser to sign in in (a development session without [signIn] has one). */
    private val opensBrowser: Boolean = !tv && signIn?.canOpenBrowser != false
    private val browser: SignInPlatform? = signIn?.takeIf { opensBrowser }
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var connections: List<TrackerAccount> = emptyList()
    private var counts: Map<TrackerService, TrackerQueueCounts> = emptyMap()

    private var connect: Connect? = null
    private var connectJob: Job? = null
    private var tickJob: Job? = null
    private var dialog: TrackerService? = null
    private var notice: String? = null

    /** One connect flow: where it stands, and whether it opened this device's sign-in browser (closed with the flow). */
    private class Connect(val service: TrackerService) {
        var phase: ConnectPhase = ConnectPhase.Starting
        var browserOpened = false
    }

    /** Where a connect flow stands, with what each step needs ([TrackerConnectStep] is what it shows). */
    private sealed interface ConnectPhase {
        data object Starting : ConnectPhase

        /** [grant] shows, its code expiring at [expiresAtMillis]. */
        class Code(val grant: DeviceCodeGrant, val expiresAtMillis: Long) : ConnectPhase

        class Link(val start: RelayStart) : ConnectPhase

        data object Expired : ConnectPhase

        data object Declined : ConnectPhase

        class Failed(val error: String) : ConnectPhase
    }

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<TrackersSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            accounts.observe(profileId).collect {
                connections = it
                // Connected, by this flow or elsewhere (another screen, sync): the flow has nothing left to do.
                connect?.let { flow -> it.firstOrNull { account -> account.service == flow.service }?.let(::finishConnect) }
                publish()
            }
        }
        scope.launch {
            queue.observeCounts(profileId).collect {
                counts = it
                publish()
            }
        }
    }

    /** Connects [service]: gets a code or a sign-in link and waits for the user's answer ([TrackersSettingsUiState.connect]). */
    fun connect(service: TrackerService) {
        if (service !in accounts.services || connections.any { it.service == service }) return
        closeConnect()
        if (!accounts.canConnect) {
            notice = T.settings.trackers.needsAccount(serviceName(service))
            publish()
            return
        }
        notice = null
        val flow = Connect(service)
        connect = flow
        publish()
        connectJob = scope.launch {
            try {
                val account = accounts.signIn(profileId, service, browser?.let { it::openRedirectReceiver }) { prompt -> onPrompt(flow, prompt) }
                if (connect !== flow) return@launch
                if (account != null) {
                    finishConnect(account)
                    return@launch
                }
                // The poller doesn't say which: a code whose lifetime is over expired, anything earlier was declined.
                val shown = flow.phase
                flow.phase = if (shown is ConnectPhase.Code && now() >= shown.expiresAtMillis) ConnectPhase.Expired else ConnectPhase.Declined
                stopTicking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connect !== flow) return@launch
                Log.w(TAG, e) { "${service.id} sign-in failed" }
                flow.phase = ConnectPhase.Failed(failureText(service, e))
                stopTicking()
            }
            publish()
        }
    }

    /** Starts the flow of [TrackersSettingsUiState.connect] over, with a new code or link. */
    fun retryConnect() {
        val service = connect?.service ?: return
        connect(service)
    }

    /** Gives up the connect flow; polling stops (a shown code simply expires at the service). */
    fun cancelConnect() {
        closeConnect()
        publish()
    }

    /**
     * Opens the connect flow's verification page (a device code's, with the code filled in where the service can) or
     * sign-in link: the one for this device's own sign-in browser where it has one.
     */
    fun openInBrowser() {
        val flow = connect ?: return
        when (val phase = flow.phase) {
            is ConnectPhase.Code -> host.openUrl(phase.grant.verificationUrlComplete ?: phase.grant.verificationUrl)
            is ConnectPhase.Link -> {
                val link = phase.start
                val own = link.browserUrl
                if (own != null && browser != null) {
                    flow.browserOpened = true
                    browser.openBrowser(own)
                } else {
                    host.openUrl(link.url)
                }
            }
            ConnectPhase.Starting, ConnectPhase.Expired, ConnectPhase.Declined, is ConnectPhase.Failed -> Unit
        }
    }

    /** Asks before disconnecting [service] ([TrackersSettingsUiState.dialog]). */
    fun disconnect(service: TrackerService) {
        if (connections.none { it.service == service }) return
        dialog = service
        publish()
    }

    /** Disconnects the dialog's service on every device: the account server drops it and revokes its token. */
    fun confirmDialog() {
        val service = dialog ?: return
        dialog = null
        notice = null
        publish()
        scope.launch {
            notice = try {
                accounts.disconnect(profileId, service)
                T.settings.trackers.disconnectedNotice(serviceName(service))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "${service.id} disconnect failed" }
                (e as? TrackerException.AccountServer)?.let { accountServerText(service, it) } ?: T.settings.trackers.actionFailed
            }
            publish()
        }
    }

    fun dismissDialog() {
        dialog = null
        publish()
    }

    fun dismissNotice() {
        notice = null
        publish()
    }

    /** Whether anything goes to [service]: live playback reports and watched marks. */
    fun setScrobbling(service: TrackerService, on: Boolean) = change(service) { accounts.setEnabled(profileId, service, on) }

    /** Whether movies go to [service]. */
    fun setMovies(service: TrackerService, on: Boolean) = filter(service, TrackerContentType.MOVIE, on)

    /** Whether series go to [service]. */
    fun setSeries(service: TrackerService, on: Boolean) = filter(service, TrackerContentType.SERIES, on)

    /** Whether anime goes to [service]. */
    fun setAnime(service: TrackerService, on: Boolean) = filter(service, TrackerContentType.ANIME, on)

    /** Ends the store: a connect flow still open closes as [cancelConnect] closes it, its sign-in browser with it. */
    fun release() {
        closeConnect()
        scope.cancel()
    }

    /** Stores one content type's switch on its own: switches flipped faster than the rows follow don't undo each other. */
    private fun filter(service: TrackerService, type: TrackerContentType, on: Boolean) =
        change(service) { accounts.setContentFilter(profileId, service, type, on) }

    /** Stores a switch; the rows follow the stored connection. */
    private fun change(service: TrackerService, write: suspend () -> Unit) {
        if (connections.none { it.service == service }) return
        scope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "${service.id} setting not stored" }
                notice = T.settings.trackers.actionFailed
                publish()
            }
        }
    }

    private fun onPrompt(flow: Connect, prompt: SignInPrompt) {
        if (connect !== flow) return
        when (prompt) {
            is SignInPrompt.DeviceCode -> {
                flow.phase = ConnectPhase.Code(prompt.grant, now() + prompt.grant.expiresInSeconds * 1000)
                startTicking(flow)
            }
            is SignInPrompt.Link -> {
                flow.phase = ConnectPhase.Link(prompt.start)
            }
        }
        publish()
    }

    /** Republishes while a code shows, whenever its countdown text changes. */
    private fun startTicking(flow: Connect) {
        stopTicking()
        tickJob = scope.launch {
            while (connect === flow) {
                val phase = flow.phase as? ConnectPhase.Code ?: break
                delay(nextTickMillis(phase.expiresAtMillis, now()))
                publish()
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun closeConnect() {
        connectJob?.cancel()
        connectJob = null
        stopTicking()
        if (connect?.browserOpened == true) browser?.closeBrowser()
        connect = null
    }

    /** The flow's service is connected as [account]: the flow closes and the notice says so. */
    private fun finishConnect(account: TrackerAccount) {
        closeConnect()
        val name = serviceName(account.service)
        notice = account.username?.let { T.settings.trackers.connectedNotice(name, isolate(it)) } ?: T.settings.trackers.connectedNoticeNoName(name)
        publish()
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): TrackersSettingsUiState {
        val text = T.settings.trackers
        val available = accounts.services
        val shown = available + connections.map { it.service }.filter { it !in available }
        return TrackersSettingsUiState(
            title = text.title,
            note = text.note,
            services = TrackerService.entries.filter { it in shown }.map { service -> row(service, service in available) },
            connect = connect?.let(::connectState),
            dialog = dialog?.let { service ->
                TrackerConfirmDialog(
                    service = service,
                    title = text.disconnectTitle(serviceName(service)),
                    message = text.disconnectMessage(serviceName(service)),
                    confirmLabel = text.disconnect,
                    cancelLabel = T.settings.cancel,
                    destructive = true,
                )
            },
            notice = notice,
        )
    }

    private fun row(service: TrackerService, available: Boolean): TrackerServiceRow {
        val text = T.settings.trackers
        val account = connections.firstOrNull { it.service == service }
        val status = when {
            !available -> text.unavailable
            account != null -> account.username?.let { text.connectedAs(isolate(it)) } ?: text.connected
            else -> text.notConnected
        }
        val name = serviceName(service)
        return TrackerServiceRow(
            service = service,
            name = name,
            status = status,
            connected = account != null,
            username = account?.username,
            available = available,
            canConnect = available && account == null && connect == null,
            connectLabel = text.connect,
            connectNamedLabel = text.connectNamed(name),
            disconnectLabel = text.disconnect,
            disconnectNamedLabel = text.disconnectNamed(name),
            // A connection this build can't make any more has nothing to switch: it can only be disconnected.
            account = account?.takeIf { available }?.let {
                val writes = counts[service]
                val queued = writes?.queued ?: 0
                val failed = writes?.failed ?: 0
                TrackerAccountUiState(
                    scrobble = SettingToggleRow("${service.name}.scrobble", text.scrobble, text.scrobbleHint(serviceName(service)), it.enabled),
                    filtersHeader = text.filtersHeader,
                    movies = SettingToggleRow("${service.name}.movies", text.movies, null, it.syncMovies),
                    series = SettingToggleRow("${service.name}.series", text.series, null, it.syncSeries),
                    anime = SettingToggleRow("${service.name}.anime", text.anime, null, it.syncAnime),
                    queuedCount = queued,
                    queued = if (queued > 0) text.queued(queued) else null,
                    failedCount = failed,
                    failed = writes?.lastFailure?.let { last -> text.notSent(failed, notSentReason(service, last)) },
                )
            },
        )
    }

    private fun connectState(flow: Connect): TrackerConnectUiState {
        val text = T.settings.trackers
        val name = serviceName(flow.service)
        val step = when (val phase = flow.phase) {
            ConnectPhase.Starting -> TrackerConnectStep.Starting(text.starting(name))
            is ConnectPhase.Code -> TrackerConnectStep.Code(
                DeviceCodeUiState(
                    userCode = phase.grant.userCode,
                    verificationUrl = phase.grant.verificationUrl,
                    qrPayload = phase.grant.verificationUrlComplete ?: phase.grant.verificationUrl,
                    instructions = T.account.signIn.deviceCode.instructions(isolate(displayHost(phase.grant.verificationUrl))),
                    scanHint = T.account.signIn.deviceCode.scan,
                    expiresText = T.account.signIn.deviceCode.expires(remainingText(phase.expiresAtMillis, now())),
                ),
                text.waiting(name),
                canOpenInBrowser = opensBrowser,
            )
            is ConnectPhase.Link -> TrackerConnectStep.Link(
                TrackerLinkUiState(url = phase.start.url, qrPayload = phase.start.url, instructions = text.linkInstructions(name), scanHint = text.linkScan),
                text.waiting(name),
                canOpenInBrowser = opensBrowser,
            )
            ConnectPhase.Expired -> TrackerConnectStep.Expired(text.expired)
            ConnectPhase.Declined -> TrackerConnectStep.Declined(text.declined(name))
            is ConnectPhase.Failed -> TrackerConnectStep.Failed(phase.error)
        }
        val ended = step is TrackerConnectStep.Expired || step is TrackerConnectStep.Declined || step is TrackerConnectStep.Failed
        return TrackerConnectUiState(
            service = flow.service,
            title = text.connectTitle(name),
            step = step,
            openInBrowserLabel = text.openInBrowser,
            retryLabel = text.retry,
            cancelLabel = if (ended) T.settings.close else T.settings.cancel,
        )
    }

    private companion object {
        const val TAG = "trackers"

        fun failureText(service: TrackerService, error: Exception): String {
            val text = T.settings.trackers
            val name = serviceName(service)
            return when (error) {
                is TrackerException.AccountServer -> accountServerText(service, error) ?: text.failed(name)
                is TrackerException.Network -> text.unreachable(name)
                is TrackerException.RateLimited -> text.busy(name)
                is TrackerException.SignIn -> text.refused(name)
                is TrackerException.Api -> if (error.transient) text.busy(name) else text.refused(name)
                else -> text.failed(name)
            }
        }

        /** Why [service]'s latest failed write was given up on, for the account's failed line. */
        fun notSentReason(service: TrackerService, failure: TrackerWriteFailure): String {
            val reason = T.settings.trackers.notSentReason
            return when (failure) {
                TrackerWriteFailure.Unreadable -> reason.unreadable
                is TrackerWriteFailure.Refused -> failure.status?.let { reason.answered(service = serviceName(service), status = it.toString()) }
                    ?: reason.refused(serviceName(service))
            }
        }

        /** Why the account server didn't take a connect or disconnect, or `null` when the answer says nothing useful. */
        fun accountServerText(service: TrackerService, error: TrackerException.AccountServer): String? = when (error.error) {
            AccountError.NotSignedIn, AccountError.SessionEnded -> T.settings.trackers.needsAccount(serviceName(service))
            AccountError.Network, is AccountError.Server, is AccountError.RateLimited -> T.settings.trackers.serverUnreachable
            else -> null
        }
    }
}

/** The service's name as the user knows it: "Trakt", "MyAnimeList". */
internal fun serviceName(service: TrackerService): String {
    val names = T.settings.trackers.service
    return when (service) {
        TrackerService.TRAKT -> names.trakt
        TrackerService.SIMKL -> names.simkl
        TrackerService.MDBLIST -> names.mdblist
        TrackerService.MYANIMELIST -> names.myanimelist
        TrackerService.ANILIST -> names.anilist
    }
}
