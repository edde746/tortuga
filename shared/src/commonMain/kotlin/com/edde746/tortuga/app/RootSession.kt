package com.edde746.tortuga.app

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.account.AccountDestination
import com.edde746.tortuga.presentation.account.AccountGate
import com.edde746.tortuga.presentation.account.AccountGateUiState
import com.edde746.tortuga.presentation.account.accountGate
import com.edde746.tortuga.presentation.navigation.SavedNavigation
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.engine.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the account shell shows ([RootSession.screen]). */
sealed interface RootScreen {
    /** The splash: the account and profiles are loading ([RootSession.startup] says how far). */
    data object Starting : RootScreen

    data object SignIn : RootScreen

    /** The profile picker (and its editor). */
    data object Profiles : RootScreen

    /** The running profile's app. */
    data class Main(val app: AppSession) : RootScreen
}

/**
 * The product's account shell: the account [gate] decides the top level ([screen]): a splash while the
 * account loads, sign-in, the profile picker, or the app of the chosen profile.
 *
 * The app ([AppSession]) is created when a profile starts running, on the back stacks the profile's last session on
 * this device left ([SavedNavigation]), and closed when another one does, the account signs out; while the picker
 * switches profiles it stays, without its playback or overlay, so leaving the picker returns to it as it was.
 *
 * Built once per process on the main thread from the product bootstrap's graph, account and sync engine
 * ([AppBootstrap.product]); the gate restores the account and starts the engine. [close] when the process's window or
 * activity goes for good.
 *
 * @param signIn what this device can do to sign in (the browser, a loopback redirect, native sheets).
 * @param startup the start the platform began before the bootstrap: the splash draws its status line, and it is done
 *   once the gate leaves its first screen.
 */
class RootSession(
    val graph: AppGraph,
    val account: SyncAccount,
    val sync: SyncEngine,
    val signIn: SignInPlatform,
    private val platform: AppPlatform,
    val startup: Startup,
) {
    val gate: AccountGate = graph.accountGate(account, sync, startup)

    private val scope = CoroutineScope(graph.scope.coroutineContext + SupervisorJob(graph.scope.coroutineContext[Job]))
    private val mutableScreen = MutableStateFlow<RootScreen>(RootScreen.Starting)
    private var closed = false

    /** What the shell shows. */
    val screen: StateFlow<RootScreen> = mutableScreen.asStateFlow()

    /** The gate's state: the sync status line and the sign-in notice. */
    val gateState: StateFlow<AccountGateUiState> get() = gate.state

    /** The running profile's app, also while the picker switches profiles; null before one runs and after it closed. */
    var app: AppSession? = null
        private set

    /** A link waiting for a profile ([openUrl]). */
    private var pendingLink: PendingLink? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { gate.state.collect { onGate(it) } }
    }

    /**
     * Follows a link the OS handed the app (a watch-together invite, a device link's QR code, an item of the TV's home
     * screen, a `stremio:` link; [IncomingLink], parsed here once) in the running profile's app
     * ([AppSession.openIncomingLink]); before a profile runs (sign-in, the picker), once one does; a link this app doesn't
     * open is logged and dropped. An item of the TV's home screen ([IncomingLink.HomeScreen]) opens only in its own
     * profile: for any other, the picker opens (and asks for its PIN, if it has one; from a kids profile a parent's PIN
     * comes first, [AppSession.switchProfile]), and the link waits for the profile entered next, which drops it if it
     * isn't the item's.
     */
    fun openUrl(url: String) {
        val link = IncomingLink.parse(url) ?: return Log.w(TAG) { "No link this app opens: $url" }
        // The picker on screen already counts as shown: the profile entered there decides the link.
        val pending = PendingLink(link, pickerShown = mutableScreen.value == RootScreen.Profiles)
        val running = app?.takeIf { mutableScreen.value is RootScreen.Main }
        when {
            running == null -> pendingLink = pending
            pending.opensIn(running.profileId) -> running.openIncomingLink(link)
            else -> {
                pendingLink = pending
                running.switchProfile()
            }
        }
    }

    /** Ends the running app and the gate. Later calls do nothing. */
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        startup.finish()
        closeApp()
        gate.release()
    }

    private suspend fun onGate(state: AccountGateUiState) {
        val destination = state.destination
        if (destination != AccountDestination.Starting) startup.finish()
        mutableScreen.value = when (destination) {
            AccountDestination.Starting -> RootScreen.Starting.also { closeApp() }
            AccountDestination.SignIn -> RootScreen.SignIn.also {
                closeApp()
                // Signed out: no profile's titles stay on the TV's home screen.
                if (mutableScreen.value != RootScreen.SignIn) platform.homeScreen?.publish(emptyList())
            }
            AccountDestination.Profiles -> RootScreen.Profiles.also { app?.leave() }
            is AccountDestination.Main -> RootScreen.Main(appFor(destination.profileId))
        }
        val pending = pendingLink ?: return
        when (destination) {
            AccountDestination.Profiles -> pending.pickerShown = true
            is AccountDestination.Main -> {
                when {
                    pending.opensIn(destination.profileId) -> {
                        pendingLink = null
                        app?.openIncomingLink(pending.link)
                    }
                    // Another profile was entered in the picker: the item isn't opened in it.
                    pending.pickerShown -> pendingLink = null
                    // The launch went straight to the device's last profile, which isn't the item's.
                    else -> app?.switchProfile()
                }
            }
            AccountDestination.Starting, AccountDestination.SignIn -> Unit
        }
    }

    /**
     * The app of [profileId]: the running one, or a new one in place of another profile's, once the stacks the profile's
     * last session on this device left are read, and the profile: as the gate last read it, else as stored (a profile
     * the picker listed before the gate's list had it). The shell shows what it showed until then.
     */
    private suspend fun appFor(profileId: String): AppSession {
        app?.let { if (it.profileId == profileId) return it }
        val saved = graph.data.deviceSettings.value(SavedNavigation(profileId))
        val profile = gate.profile(profileId) ?: graph.data.profiles.profile(profileId)
        closeApp()
        return AppSession(graph, profileId, platform, start = null, ProfileAccount(account, sync, signIn, gate), saved = saved, startProfile = profile)
            .also { app = it }
    }

    private fun closeApp() {
        app?.close()
        app = null
    }
}

/**
 * A link that waits for a profile ([RootSession.openUrl]): one that arrived before a profile ran, or an item of the TV's
 * home screen of another profile than the running one.
 *
 * @param link the link, parsed.
 * @param pickerShown the picker showed since the link arrived: the profile entered next decides it.
 */
private class PendingLink(val link: IncomingLink, var pickerShown: Boolean) {
    /** Whether it opens in [running]'s app: an item of the TV's home screen only in its own profile's. */
    fun opensIn(running: String): Boolean = link !is IncomingLink.HomeScreen || link.item.profileId == running
}

private const val TAG = "RootSession"
