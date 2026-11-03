package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.presentation.profiles.PinThrottle
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.SignedOutReason
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The app's top-level screen, which [AccountGate] decides; only [Main] runs as a profile. */
sealed interface AccountDestination {
    /** The account and profiles are loading (the first moments after launch). */
    data object Starting : AccountDestination

    /** No session: the sign-in screen ([SignInStore]). */
    data object SignIn : AccountDestination

    /** Choose (or unlock, create, manage) a profile ([com.edde746.tortuga.presentation.profiles.ProfilePickerStore]). */
    data object Profiles : AccountDestination

    /** The app itself, running as [profileId]. */
    data class Main(val profileId: String) : AccountDestination
}

/**
 * @param destination the screen to show.
 * @param syncStatus the sync status line ("Up to date", "Offline. Changes sync when the connection is back.").
 * @param syncKind [syncStatus] to branch on (an icon, a warning colour).
 * @param notice why this device was signed out without asking (its session ended elsewhere, the account was deleted,
 *   or the secret store couldn't read the session), shown on the sign-in screen until dismissed or the next sign-in.
 */
data class AccountGateUiState(
    val destination: AccountDestination,
    val syncStatus: String,
    val syncKind: SyncStatus.Kind,
    val notice: String?,
)

/**
 * The root store: maps the account ([AccountState]), the profiles and this process's profile choice to
 * the app's top-level screen, and carries the sync status line.
 *
 * - Signed out → sign-in; signed in → the profile picker, or the app once a profile is chosen. A brand-new account's
 *   picker opens the editor for its first profile at once ([takeNewAccount]).
 * - At launch the device's last profile ([DeviceSetting.ActiveProfileId]) opens directly, unless it has a PIN: then
 *   the picker asks for it first. A deleted profile (here or on another device) goes back to the picker.
 *
 * Create one per process through `AppGraph.accountGate`, which restores the account and starts the sync engine.
 * Call everything on the main thread; [release] ends it.
 *
 * @param startup what runs before the first screen ([AccountDestination.Starting] until it returns); what it throws
 *   ends the gate's start unhandled.
 */
class AccountGate internal constructor(
    internal val account: SyncAccount,
    internal val syncStatus: StateFlow<SyncStatus>,
    private val profiles: ProfileRepository,
    private val deviceSettings: DeviceSettingsRepository,
    startup: suspend () -> Unit,
    internal val clock: () -> Long,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    internal val pinThrottle = PinThrottle(deviceSettings, clock)

    private var started = false
    private var accountState: AccountState = account.state.value
    private var profileList: List<Profile>? = null
    private var storedActiveId: String? = null
    private var settingsLoaded = false

    /** The profile this process runs as; null until one is chosen. */
    private var sessionProfileId: String? = null

    /** The device's last profile, which has a PIN: the picker asks for it at once. */
    private var lockedProfileId: String? = null

    /** The launch choice ([DeviceSetting.ActiveProfileId]) was made for this signed-in account. */
    private var decided = false
    private var choosing = false

    /** The sign-in just created the account; the picker hasn't taken it yet. */
    private var newAccount = false
    private var notice: String? = null
    private var deletionNotice: String? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<AccountGateUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            startup()
            started = true
            publish()
        }
        scope.launch { account.state.collect { onAccountState(it) } }
        scope.launch { syncStatus.collect { publish() } }
        scope.launch {
            profiles.observeProfiles().collect { list ->
                profileList = list
                if (sessionProfileId != null && list.none { it.id == sessionProfileId }) {
                    sessionProfileId = null
                    choosing = false
                }
                if (lockedProfileId != null && list.none { it.id == lockedProfileId }) lockedProfileId = null
                publish()
            }
        }
        scope.launch {
            deviceSettings.observe().collect { settings ->
                storedActiveId = settings.activeProfileId
                settingsLoaded = true
                publish()
            }
        }
    }

    /** Back to the profile picker from the app, to change profile. */
    fun switchProfile() {
        if (sessionProfileId == null || choosing) return
        choosing = true
        publish()
    }

    /**
     * The account was deleted from this device's settings: the sign-in screen's notice says [text] (what
     * [SyncAccount.deleteAccount] returned: [com.edde746.tortuga.sync.account.AccountDeletion.text], which may ask the
     * user to remove Tortuga under their Apple ID or their GitHub account) instead of the plain "deleted" line.
     */
    fun showAccountDeleted(text: String) {
        deletionNotice = text
        val state = accountState
        if (state is AccountState.SignedOut && state.reason == SignedOutReason.ACCOUNT_DELETED) {
            notice = text
            publish()
        }
    }

    /** Hides [AccountGateUiState.notice]. */
    fun dismissNotice() {
        if (notice == null) return
        notice = null
        publish()
    }

    fun release() {
        scope.cancel()
    }

    // ---- For the account and profile stores ---------------------------------------------------------------------

    /** The sign-in created the account: the picker starts with its first profile ([takeNewAccount]). */
    internal fun accountCreated() {
        newAccount = true
    }

    /** Whether the account was just created; true once, for the picker to open the first profile's editor. */
    internal fun takeNewAccount(): Boolean = newAccount.also { newAccount = false }

    /** The picker chose [profileId] (after its PIN, if it has one): run the app as it, now and at the next launch. */
    internal fun enterProfile(profileId: String) {
        sessionProfileId = profileId
        lockedProfileId = null
        decided = true
        choosing = false
        publish()
        scope.launch { deviceSettings.set(DeviceSetting.ActiveProfileId, profileId) }
    }

    /** The picker was left without choosing: back to the running profile, if there is one. */
    internal fun cancelSwitch() {
        if (sessionProfileId == null || !choosing) return
        choosing = false
        publish()
    }

    /** The picker was opened from the app ([switchProfile]) and can go back to it. */
    internal val switching: Boolean get() = choosing && sessionProfileId != null

    /** The profile this process runs as, or null. */
    internal val currentProfileId: String? get() = sessionProfileId

    /** [profileId] as the gate last read the account's profiles, or null (not read yet, or gone). */
    internal fun profile(profileId: String): Profile? = profileList?.firstOrNull { it.id == profileId }

    /** The device's last profile if it waits for its PIN; the picker asks for it at once. */
    internal fun takeLockedProfile(): String? = lockedProfileId.also { lockedProfileId = null }

    // ---- Internals --------------------------------------------------------------------------------------------------

    private fun onAccountState(state: AccountState) {
        val wasSignedIn = accountState is AccountState.SignedIn
        accountState = state
        if (state !is AccountState.SignedIn && wasSignedIn) {
            sessionProfileId = null
            lockedProfileId = null
            decided = false
            choosing = false
            newAccount = false
        }
        when (state) {
            is AccountState.SignedOut -> {
                notice = when (state.reason) {
                    SignedOutReason.SESSION_ENDED -> T.account.error.sessionEnded
                    SignedOutReason.SESSION_UNREADABLE -> T.account.error.sessionUnreadable
                    SignedOutReason.ACCOUNT_DELETED -> deletionNotice ?: T.account.deletion.done
                    null -> null
                }
            }
            is AccountState.SignedIn -> {
                notice = null
                deletionNotice = null
            }
        }
        publish()
    }

    /** The launch choice: the device's last profile opens directly, or asks for its PIN first. */
    private fun decide(list: List<Profile>) {
        if (decided || sessionProfileId != null || !settingsLoaded) return
        val stored = storedActiveId?.let { id -> list.firstOrNull { it.id == id } }
        when {
            stored != null -> {
                if (stored.hasPin) lockedProfileId = stored.id else sessionProfileId = stored.id
                decided = true
            }
            // The last profile isn't here (deleted, or not synced yet while the list is empty).
            list.isNotEmpty() -> decided = true
        }
    }

    private fun publish() {
        if (started && accountState is AccountState.SignedIn) profileList?.let(::decide)
        mutableState.value = buildState()
    }

    private fun buildState(): AccountGateUiState {
        val destination = destination()
        val status = syncStatus.value
        return AccountGateUiState(
            destination = destination,
            syncStatus = status.text,
            syncKind = status.kind,
            notice = notice.takeIf { destination == AccountDestination.SignIn },
        )
    }

    private fun destination(): AccountDestination {
        if (!started) return AccountDestination.Starting
        return when (accountState) {
            is AccountState.SignedOut -> AccountDestination.SignIn
            is AccountState.SignedIn -> {
                val profileId = sessionProfileId
                when {
                    profileList == null || !settingsLoaded -> AccountDestination.Starting
                    profileId != null && !choosing -> AccountDestination.Main(profileId)
                    else -> AccountDestination.Profiles
                }
            }
        }
    }
}
