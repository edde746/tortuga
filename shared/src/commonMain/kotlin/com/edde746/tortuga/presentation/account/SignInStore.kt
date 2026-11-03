package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.message
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.sync.account.signin.ProviderProgress
import com.edde746.tortuga.sync.account.signin.SignInMethod
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.account.signin.SyncServers
import com.edde746.tortuga.sync.api.ServerInfo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the sign-in screen is; each step carries what its page shows. */
sealed interface SignInStep {
    /** The server and the sign-in buttons, with [error]: why the last attempt failed, if it did. */
    data class Choose(val error: String?) : SignInStep

    /** A provider flow runs: [progress] says what it waits for; it can be cancelled. */
    data class Working(val progress: String) : SignInStep

    /** A TV's device flow: [code] to enter elsewhere, [progress] the waiting line. */
    data class DeviceCode(val code: DeviceCodeUiState, val progress: String) : SignInStep

    /** Linking from a signed-in device. */
    data class Link(val link: DeviceLinkUiState) : SignInStep
}

/**
 * The server the sign-in goes to, and the "use your own server" entry.
 *
 * @param label the server as shown ("api.usetortuga.app", "sync.example.com").
 * @param text the server line under the buttons ("Server: api.usetortuga.app").
 * @param custom a self-hosted server is chosen (the screen offers going back to the hosted one).
 * @param editing the server address field is open.
 * @param field the field's text.
 * @param checking the typed server is being asked what it offers.
 * @param error why the typed address can't be used.
 */
data class SignInServerUiState(
    val label: String,
    val text: String,
    val custom: Boolean,
    val editing: Boolean,
    val field: String,
    val checking: Boolean,
    val error: String?,
) {
    /** Check server (its button, Return or Enter in the field) can run: something is typed and no check is running. */
    val canSubmit: Boolean get() = !checking && this.field.isNotBlank()
}

/**
 * The sign-in screen. Static labels (title, intro, "Link from another device", "Use your own server")
 * come from `T.account.signIn` and `T.account.link`.
 *
 * @param options the provider buttons this device can use on [server], in order.
 * @param optionsLoading the server is being asked for its providers.
 * @param optionsRoom how many provider buttons' room the options stand in, from the moment the hosted server is asked
 *   for them: empty while [optionsLoading], then holding what came (fewer buttons than room, [optionsNote], the retry),
 *   so that what lies under them stays where a tap, click or the focus aims whatever the server answers: as many as the
 *   hosted server's providers make on this device ([ProviderFlows.hostedOptionCount]). 0 once an own server's check
 *   brought its options (in the server sheet), which take their own room.
 * @param optionsNote a line in place of (or under) the buttons: the server couldn't be reached (with [canRetry]), or
 *   it offers nothing this device can use.
 * @param confirm a question the sign-in or device link waits on ([SignInStore.discardUnsynced],
 *   [SignInStore.keepUnsynced]), shown as a dialog over any step.
 */
data class SignInUiState(
    val step: SignInStep,
    val server: SignInServerUiState,
    val options: List<SignInOptionUi>,
    val optionsLoading: Boolean,
    val optionsRoom: Int,
    val optionsNote: String?,
    val canRetry: Boolean,
    val confirm: SignInConfirmUiState? = null,
)

/**
 * The account signed in to (or linked) is another than the one whose data this device holds, and changes of that data
 * never synced: deleting them is the user's choice. [message] says how many.
 */
data class SignInConfirmUiState(val title: String, val message: String, val confirmLabel: String, val cancelLabel: String)

/**
 * The sign-in screen's store: the server (the hosted one, or the user's own, checked through
 * [SyncAccount.serverInfo]), the providers the server and this device ([SignInPlatform]) support, the provider flow
 * with progress and cancel. Once signed in the [AccountGate] moves on by itself: to the profiles, and for a new
 * account straight to creating its first profile.
 *
 * "Link from another device" skips the provider: a signed-in device approves this one ([SignInStep.Link]).
 *
 * Call everything on the main thread; [release] ends it.
 *
 * @param hostedServer the server signed-out devices start on: [SyncServers.HOSTED], or in a UI test a fake of it.
 */
class SignInStore internal constructor(
    private val account: SyncAccount,
    private val flows: ProviderFlows,
    private val gate: AccountGate,
    private val clock: () -> Long,
    parentScope: CoroutineScope,
    private val hostedServer: ServerUrl = ServerUrl.HOSTED,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var serverUrl = hostedServer
    private var optionsJob: Job? = null
    private var serverCheckJob: Job? = null
    private var signInJob: Job? = null

    private val mutableState = MutableStateFlow(
        SignInUiState(
            step = SignInStep.Choose(error = null),
            server = serverState(serverUrl, custom = false, editing = false, field = "", checking = false, error = null),
            options = emptyList(),
            optionsLoading = true,
            optionsRoom = flows.hostedOptionCount,
            optionsNote = null,
            canRetry = false,
        ),
    )
    val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

    private val deviceCode = DeviceCodeTicker(scope, clock) { code ->
        mutableState.update { it.copy(step = SignInStep.DeviceCode(code, T.account.signIn.deviceCode.waiting)) }
    }

    private val link = DeviceLinkRunner(scope, account, clock, { serverUrl }, ::askDiscard) { link ->
        mutableState.update {
            it.copy(step = if (link == null) SignInStep.Choose(error = null) else SignInStep.Link(link))
        }
    }

    /** The answer [askDiscard] waits for. */
    private var discardAnswer: CompletableDeferred<Boolean>? = null

    init {
        loadOptions()
    }

    // ---- Server ---------------------------------------------------------------------------------------------------

    /** Opens the "use your own server" field. */
    fun editServer() {
        mutableState.update {
            val field = if (it.server.custom) serverUrl.value else ""
            it.copy(server = it.server.copy(editing = true, field = field, error = null))
        }
    }

    fun setServerField(text: String) {
        mutableState.update { it.copy(server = it.server.copy(field = text, error = null)) }
    }

    /**
     * Checks the typed server and signs in there from now on if it is a Tortuga server this build can use; nothing
     * while [SignInServerUiState.canSubmit] is false.
     */
    fun submitServer() {
        if (!state.value.server.canSubmit) return
        val typed = state.value.server.field
        val normalized = try {
            ServerUrl.parse(typed)
        } catch (e: AccountException) {
            mutableState.update { it.copy(server = it.server.copy(error = e.error.message)) }
            return
        }
        serverCheckJob?.cancel()
        mutableState.update { it.copy(server = it.server.copy(checking = true, error = null)) }
        serverCheckJob = scope.launch {
            try {
                val info = account.serverInfo(normalized)
                optionsJob?.cancel()
                useServer(normalized, info)
            } catch (e: AccountException) {
                mutableState.update { it.copy(server = it.server.copy(checking = false, error = e.error.message)) }
            }
        }
    }

    fun cancelServerEdit() {
        serverCheckJob?.cancel()
        mutableState.update { it.copy(server = it.server.copy(editing = false, checking = false, error = null)) }
    }

    /** Back to the hosted server. */
    fun useHostedServer() {
        serverCheckJob?.cancel()
        serverUrl = hostedServer
        mutableState.update {
            it.copy(server = serverState(serverUrl, custom = false, editing = false, field = "", checking = false, error = null))
        }
        loadOptions()
    }

    /** Asks the server for its providers again (after [SignInUiState.canRetry]). */
    fun retry() = loadOptions()

    // ---- Sign-in --------------------------------------------------------------------------------------------------

    /** Signs in with the button of [provider] ([SignInOptionUi.provider]). */
    fun signIn(provider: String) {
        if (signInJob?.isActive == true || link.active) return
        val option = state.value.options.firstOrNull { it.provider == provider } ?: return
        signInJob = scope.launch {
            try {
                mutableState.update { it.copy(step = SignInStep.Working(T.account.signIn.starting)) }
                val attempt = account.beginSignIn(serverUrl)
                val credential = flows.obtain(attempt, option.provider, option.method, ::onProgress)
                deviceCode.stop()
                mutableState.update { it.copy(step = SignInStep.Working(T.account.signIn.finishing)) }
                if (account.completeSignIn(attempt, credential, ::askDiscard) == SignInResult.NEW_ACCOUNT) gate.accountCreated()
                // The gate moves on to the profiles.
                mutableState.update { it.copy(step = SignInStep.Choose(error = null)) }
            } catch (e: AccountException) {
                val quiet = e.error == AccountError.SignInCancelled && option.method == SignInMethod.NATIVE
                val error = when {
                    quiet -> null
                    e.error is AccountError.UnsyncedChanges -> T.account.switchAccount.kept
                    else -> e.error.message
                }
                mutableState.update { it.copy(step = SignInStep.Choose(error)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A platform failure outside the account layer (the loopback port taken, a broken native sheet).
                Log.w(TAG, e) { "Sign-in failed" }
                mutableState.update { it.copy(step = SignInStep.Choose(T.account.error.unexpected)) }
            } finally {
                deviceCode.stop()
            }
        }
    }

    /** Stops the running sign-in or device link; back to the buttons. */
    fun cancel() {
        signInJob?.cancel()
        signInJob = null
        deviceCode.stop()
        if (link.active || state.value.step is SignInStep.Link) link.cancel()
        mutableState.update { it.copy(step = SignInStep.Choose(error = null)) }
    }

    /**
     * The app came back to the foreground (from approving on another device): looks for the device link's result at
     * once instead of at its next poll.
     */
    fun onForeground() = link.pollNow()

    /** Deletes the changes [SignInUiState.confirm] is about and signs in. */
    fun discardUnsynced() {
        discardAnswer?.complete(true)
    }

    /** Keeps the changes [SignInUiState.confirm] is about: no sign-in, the new session ends. */
    fun keepUnsynced() {
        discardAnswer?.complete(false)
    }

    // ---- Device link ----------------------------------------------------------------------------------------------

    /** "Link from another device": shows a code for a signed-in device to approve. */
    fun linkDevice() {
        if (signInJob?.isActive == true) return
        link.start()
    }

    /** A new code after a decline, an expiry or a failure. */
    fun restartLink() = link.start()

    fun release() {
        scope.cancel()
    }

    // ---- Internals --------------------------------------------------------------------------------------------------

    private fun loadOptions() {
        optionsJob?.cancel()
        val server = serverUrl
        mutableState.update { it.copy(optionsLoading = true, optionsRoom = flows.hostedOptionCount, optionsNote = null, canRetry = false) }
        optionsJob = scope.launch {
            try {
                val options = flows.options(server, account.serverInfo(server), exclude = emptySet()).map { it.toUi() }
                // Only the options: the server sheet, opened while they load, stays up with what was typed in it.
                mutableState.update { it.withOptions(options) }
            } catch (e: AccountException) {
                mutableState.update { it.copy(options = emptyList(), optionsLoading = false, optionsNote = e.error.message, canRetry = true) }
            }
        }
    }

    /** Signs in at [url] from now on, its check passed with [info]: the server sheet closes. */
    private fun useServer(url: ServerUrl, info: ServerInfo) {
        serverUrl = url
        val options = flows.options(url, info, exclude = emptySet()).map { it.toUi() }
        mutableState.update {
            it.withOptions(options).copy(
                optionsRoom = 0,
                server = serverState(url = url, custom = url != hostedServer, editing = false, field = "", checking = false, error = null),
            )
        }
    }

    private fun SignInUiState.withOptions(options: List<SignInOptionUi>) = copy(
        options = options,
        optionsLoading = false,
        optionsNote = if (options.isEmpty()) T.account.signIn.noOptions else null,
        canRetry = false,
        step = if (step is SignInStep.Choose) SignInStep.Choose(error = null) else step,
    )

    private fun onProgress(progress: ProviderProgress) {
        if (progress is ProviderProgress.DeviceCode) {
            deviceCode.show(progress.code)
        } else {
            mutableState.update { it.copy(step = SignInStep.Working(progressText(progress))) }
        }
    }

    /** Asks whether to delete [unsynced] changes of the data this device holds ([SyncAccount.completeSignIn]). */
    private suspend fun askDiscard(unsynced: Long): Boolean {
        val answer = CompletableDeferred<Boolean>()
        discardAnswer = answer
        val text = T.account.switchAccount
        mutableState.update { it.copy(confirm = SignInConfirmUiState(text.title, text.message(unsynced.toInt()), text.confirm, text.cancel)) }
        return try {
            answer.await()
        } finally {
            discardAnswer = null
            mutableState.update { it.copy(confirm = null) }
        }
    }

    companion object {
        /** Its name on the store probe while its screen shows (test reports read it there). */
        const val KEY: String = "signIn"

        private const val TAG = "SignInStore"
    }
}

private fun serverState(url: ServerUrl, custom: Boolean, editing: Boolean, field: String, checking: Boolean, error: String?) =
    SignInServerUiState(displayHost(url.value), T.account.signIn.server(isolate(displayHost(url.value))), custom, editing, field, checking, error)
