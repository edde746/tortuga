package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.message
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.sync.account.signin.ProviderProgress
import com.edde746.tortuga.sync.account.signin.SignInMethod
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The providers the "add a sign-in method" screen offers. One case at a time: [Loading] while the account and the
 * server are read, then [Failed] (with Retry) when they couldn't be, [Empty] when every provider is linked already,
 * else [Buttons].
 */
sealed interface IdentityLinkOptions {
    data object Loading : IdentityLinkOptions

    /** The account or the server couldn't be read: [text] says why, and Retry reads them again. */
    data class Failed(val text: String) : IdentityLinkOptions

    /** Every provider this device can link is linked already. */
    data class Empty(val text: String) : IdentityLinkOptions

    /** The providers this device can link that the account doesn't have yet. */
    data class Buttons(val options: List<SignInOptionUi>) : IdentityLinkOptions
}

/** Where the "add a sign-in method" screen is; each step carries what its page shows. */
sealed interface IdentityLinkStep {
    /** The providers not linked yet, with [error]: why the last attempt failed (the identity belongs to another account, the sign-in was declined, …), if it did. */
    data class Choose(val options: IdentityLinkOptions, val error: String?) : IdentityLinkStep

    /** A provider flow runs: [progress] says what it waits for; it can be cancelled. */
    data class Working(val progress: String) : IdentityLinkStep

    /** A TV's device flow: [code] to enter elsewhere, [progress] the waiting line. */
    data class DeviceCode(val code: DeviceCodeUiState, val progress: String) : IdentityLinkStep
}

/** The "add a sign-in method" screen (settings). Static labels come from `T.account.identityLink`. */
data class IdentityLinkUiState(val step: IdentityLinkStep)

/** Where the "add a sign-in method" screen goes; called on the main thread. */
interface IdentityLinkHost {
    /**
     * The identity is linked: back to the account settings, which read the identities again by themselves
     * ([com.edde746.tortuga.sync.account.SyncAccount.identityLinked]).
     */
    fun identityLinked()

    /** Leave without linking. */
    fun closeIdentityLink()
}

/**
 * Links a second provider identity to the signed-in account ([SyncAccount.linkIdentity]), so losing one provider
 * account doesn't lock the user out: offers the providers the server and this device support that the account
 * doesn't have yet, and runs the same provider flows as sign-in ([SignInStore]).
 *
 * Call everything on the main thread; [release] ends it.
 */
class IdentityLinkStore internal constructor(
    private val account: SyncAccount,
    private val flows: ProviderFlows,
    private val host: IdentityLinkHost,
    clock: () -> Long,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var loadJob: Job? = null
    private var linkJob: Job? = null

    /** The providers on offer, kept while a flow runs to show again when it ends. */
    private var options: IdentityLinkOptions = IdentityLinkOptions.Loading

    private val mutableState = MutableStateFlow(IdentityLinkUiState(IdentityLinkStep.Choose(options, error = null)))
    val state: StateFlow<IdentityLinkUiState> = mutableState.asStateFlow()

    private val deviceCode = DeviceCodeTicker(scope, clock) { code ->
        mutableState.update { it.copy(step = IdentityLinkStep.DeviceCode(code, T.account.signIn.deviceCode.waiting)) }
    }

    init {
        load()
    }

    /** Links the identity of [provider]'s button ([SignInOptionUi.provider]). */
    fun link(provider: String) {
        if (linkJob?.isActive == true) return
        val option = (options as? IdentityLinkOptions.Buttons)?.options?.firstOrNull { it.provider == provider } ?: return
        linkJob = scope.launch {
            try {
                mutableState.update { it.copy(step = IdentityLinkStep.Working(T.account.signIn.starting)) }
                val attempt = account.beginIdentityLink()
                val credential = flows.obtain(attempt, option.provider, option.method) { progress ->
                    if (progress is ProviderProgress.DeviceCode) {
                        deviceCode.show(progress.code)
                    } else {
                        mutableState.update { it.copy(step = IdentityLinkStep.Working(progressText(progress))) }
                    }
                }
                deviceCode.stop()
                mutableState.update { it.copy(step = IdentityLinkStep.Working(T.account.identityLink.linking)) }
                account.linkIdentity(credential)
                choose(error = null)
                host.identityLinked()
            } catch (e: AccountException) {
                val quiet = e.error == AccountError.SignInCancelled && option.method == SignInMethod.NATIVE
                choose(error = if (quiet) null else e.error.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "Linking a sign-in failed" }
                choose(T.account.error.unexpected)
            } finally {
                deviceCode.stop()
            }
        }
    }

    /** Stops the running flow; back to the buttons. */
    fun cancel() {
        linkJob?.cancel()
        linkJob = null
        deviceCode.stop()
        choose(error = null)
    }

    /** Reads the account and the server again (after [IdentityLinkOptions.Failed]). */
    fun retry() = load()

    fun close() {
        cancel()
        host.closeIdentityLink()
    }

    fun release() {
        scope.cancel()
    }

    private fun load() {
        loadJob?.cancel()
        showOptions(IdentityLinkOptions.Loading)
        loadJob = scope.launch {
            try {
                val server = (account.state.value as? AccountState.SignedIn)?.serverUrl ?: throw AccountException(AccountError.NotSignedIn)
                val linked = account.accountInfo().identities.map { it.provider }.toSet()
                val options = flows.options(server, account.serverInfo(server), exclude = linked).map { it.toUi() }
                showOptions(if (options.isEmpty()) IdentityLinkOptions.Empty(T.account.identityLink.allLinked) else IdentityLinkOptions.Buttons(options))
            } catch (e: AccountException) {
                showOptions(IdentityLinkOptions.Failed(e.error.message))
            }
        }
    }

    /** Back to the providers, with [error]. */
    private fun choose(error: String?) {
        mutableState.update { it.copy(step = IdentityLinkStep.Choose(options, error)) }
    }

    /** Offers [options] from now on; the providers' step shows them at once, its error kept. */
    private fun showOptions(options: IdentityLinkOptions) {
        this.options = options
        mutableState.update { state -> (state.step as? IdentityLinkStep.Choose)?.let { state.copy(step = it.copy(options = options)) } ?: state }
    }

    companion object {
        /** Its name on the store probe while its screen shows (test reports read it there). */
        const val KEY: String = "identityLink"

        private const val TAG = "IdentityLinkStore"
    }
}
