package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.presentation.account.AccountGate
import com.edde746.tortuga.presentation.account.nextTickMillis
import com.edde746.tortuga.presentation.account.remainingText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One profile on the picker.
 *
 * @param initial the name's first letter, drawn on the [avatar] tile.
 * @param locked the profile has a PIN.
 * @param current the profile this process runs as (when the picker was opened to switch).
 * @param badge the mark on the avatar's corner; null for none.
 * @param stateText what the tile says after its name to assistive technologies, each that holds: "Edit profile" while
 *   managing, "Locked with a PIN", "Current profile"; null for none.
 */
data class ProfileTile(
    val id: String,
    val name: String,
    val initial: String,
    val avatar: ProfileAvatar,
    val locked: Boolean,
    val current: Boolean,
    val badge: ProfileBadge?,
    val stateText: String?,
)

/** A [ProfileTile]'s corner mark: the first that holds, in this order. */
enum class ProfileBadge {
    /** The picker is managing profiles: choosing the tile edits it (a pencil). */
    EDIT,

    /** The profile has a PIN (a lock). */
    LOCK,

    /** The profile this process runs as (a check). */
    CURRENT,
}

/**
 * The PIN pad over the picker.
 *
 * @param title "Enter the PIN for Alice".
 * @param entered how many digits are in (the pad shows that many dots of [length]).
 * @param error "Wrong PIN. Try again." after a wrong PIN.
 * @param lockedOutText "Too many attempts. Try again in 0:30.", counting down while entry is locked.
 * @param acceptsInput digits can be entered: not locked out and not checking.
 */
data class PinPromptUiState(
    val profileId: String,
    val title: String,
    val initial: String,
    val avatar: ProfileAvatar,
    val entered: Int,
    val length: Int,
    val error: String?,
    val lockedOutText: String?,
    val acceptsInput: Boolean,
) {
    /** The pad's dots, spoken ([pinEnteredText]). */
    val enteredText: String
        get() = pinEnteredText(entered, length)
}

/** A PIN pad's dots as assistive technologies say them: "2 of 4 digits entered". */
internal fun pinEnteredText(entered: Int, length: Int): String = T.profiles.pin.entered(entered.toString(), length.toString())

/**
 * The profile picker ("Who's watching?"). Static labels (Add profile, Manage profiles, Done) come from
 * `T.profiles`.
 *
 * @param title "Who's watching?", or "Manage profiles" while [managing].
 * @param body the profiles, or why there are none ([ProfilePickerBody]).
 * @param managing choosing a profile opens it for editing instead of entering it.
 * @param canClose the picker was opened from the app and can go back to the running profile.
 * @param pinPrompt the PIN pad, while a locked profile is being opened.
 * @param syncStatus the sync status line.
 */
data class ProfilePickerUiState(
    val title: String,
    val body: ProfilePickerBody,
    val managing: Boolean,
    val canClose: Boolean,
    val pinPrompt: PinPromptUiState?,
    val syncStatus: String,
)

/**
 * The picker under its title, the first that applies: a spinner while the account's profiles are still arriving (the
 * first sync after linking a device) ([Loading]); the prompt to create one while there is none ([Empty]); else the
 * profiles' tiles ([Tiles]). Add profile shows with [Empty] and [Tiles].
 */
sealed interface ProfilePickerBody {
    data object Loading : ProfilePickerBody

    /** [text]: the prompt to create a profile. */
    data class Empty(val text: String) : ProfilePickerBody

    /** [profiles]: the account's profiles, never none. */
    data class Tiles(val profiles: List<ProfileTile>) : ProfilePickerBody
}

/** Where the profile picker goes; called on the main thread. */
interface ProfilePickerHost {
    /** Open the editor for a new profile ([ProfileEditorStore] with no profile id). */
    fun createProfile()

    /** Open the editor for [profileId] (its PIN, if it has one, was entered). */
    fun editProfile(profileId: String)
}

/**
 * The profile picker's store: the account's profiles with their avatars, entering one (its PIN first if it has one,
 * with wrong attempts throttled: [PinThrottle]), and managing them (a locked profile asks for its PIN before its
 * editor opens). Entering a profile makes it the device's profile at the next launch
 * ([com.edde746.tortuga.data.settings.DeviceSetting.ActiveProfileId]); the [AccountGate] then shows the app.
 *
 * Call everything on the main thread; [release] ends it.
 */
class ProfilePickerStore internal constructor(
    private val profiles: ProfileRepository,
    private val gate: AccountGate,
    private val host: ProfilePickerHost,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var list: List<Profile>? = null
    private var managing = false

    /** Counts pads opened and closed: a check finishing for an earlier pad leaves the current one alone. */
    private var prompt = 0
    private var promptProfileId: String? = null
    private var promptForEdit = false
    private var digits = ""
    private var pinError: String? = null
    private var checking = false
    private var lockoutJob: Job? = null

    /** When the open pad's lockout ends, while it is locked out. */
    private var lockedUntil: Long? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<ProfilePickerUiState> = mutableState.asStateFlow()

    init {
        gate.takeLockedProfile()?.let { openPrompt(it, forEdit = false) }
        scope.launch {
            profiles.observeProfiles().collect { profiles ->
                list = profiles
                if (promptProfileId != null && profiles.none { it.id == promptProfileId }) closePrompt()
                publish()
                // A brand-new account has no profiles to choose from: straight to creating the first.
                if (profiles.isEmpty() && gate.takeNewAccount()) host.createProfile()
            }
        }
        scope.launch { gate.syncStatus.collect { publish() } }
    }

    /** A profile tile was chosen: enter it, or edit it while managing (after its PIN, if it has one). */
    fun select(profileId: String) {
        if (promptProfileId != null) return
        val profile = list?.firstOrNull { it.id == profileId } ?: return
        when {
            profile.hasPin -> openPrompt(profileId, forEdit = managing)
            managing -> host.editProfile(profileId)
            else -> gate.enterProfile(profileId)
        }
    }

    fun addProfile() {
        if (promptProfileId != null) return
        host.createProfile()
    }

    fun manage() {
        managing = true
        publish()
    }

    fun doneManaging() {
        managing = false
        publish()
    }

    /** Back to the running profile ([ProfilePickerUiState.canClose]). */
    fun close() {
        closePrompt()
        gate.cancelSwitch()
        publish()
    }

    // ---- PIN pad ----------------------------------------------------------------------------------------------------

    /** A digit (0–9) on the pad or the keyboard; the fourth checks the PIN. */
    fun pinDigit(digit: Int) {
        if (digit !in 0..9 || !acceptsInput()) return
        digits += digit.toString()
        pinError = null
        if (digits.length == PIN_LENGTH) check() else publish()
    }

    fun pinBackspace() {
        if (!acceptsInput() || digits.isEmpty()) return
        digits = digits.dropLast(1)
        publish()
    }

    fun cancelPin() {
        closePrompt()
        publish()
    }

    fun release() {
        scope.cancel()
    }

    // ---- Internals --------------------------------------------------------------------------------------------------

    private fun openPrompt(profileId: String, forEdit: Boolean) {
        prompt++
        promptProfileId = profileId
        promptForEdit = forEdit
        digits = ""
        pinError = null
        checking = false
        val opened = prompt
        scope.launch { gate.pinThrottle.lockedUntil(profileId)?.let { if (prompt == opened) startLockout(it) } }
        publish()
    }

    private fun closePrompt() {
        prompt++
        promptProfileId = null
        digits = ""
        pinError = null
        checking = false
        lockoutJob?.cancel()
        lockoutJob = null
        lockedUntil = null
    }

    private fun acceptsInput(): Boolean = promptProfileId != null && !checking && lockoutJob?.isActive != true

    /**
     * Checks the entered PIN. The throttle counts the outcome even if the pad was closed meanwhile (closing it is no
     * free guess); the pad shows it only if it is still the same one. A PIN entered while the profile is locked out (the
     * pad opened before the lockout was read) isn't checked.
     */
    private fun check() {
        val profileId = promptProfileId ?: return
        val pin = digits
        val checked = prompt
        checking = true
        publish()
        scope.launch {
            val locked = gate.pinThrottle.lockedUntil(profileId)
            if (locked != null) {
                if (prompt != checked) return@launch
                checking = false
                digits = ""
                startLockout(locked)
                return@launch
            }
            val right = profiles.verifyPin(profileId, pin)
            val lockout = if (right) {
                gate.pinThrottle.succeeded(profileId)
                null
            } else {
                gate.pinThrottle.failed(profileId)
            }
            if (prompt != checked) return@launch
            checking = false
            if (right) {
                val forEdit = promptForEdit
                closePrompt()
                publish()
                if (forEdit) host.editProfile(profileId) else gate.enterProfile(profileId)
            } else {
                digits = ""
                pinError = T.profiles.pin.wrong
                lockout?.let(::startLockout)
                publish()
            }
        }
    }

    private fun startLockout(until: Long) {
        lockoutJob?.cancel()
        lockedUntil = until
        lockoutJob = scope.launch {
            while (true) {
                val now = gate.clock()
                if (now >= until) break
                publish()
                delay(nextTickMillis(until, now))
            }
            lockoutJob = null
            lockedUntil = null
            pinError = null
            publish()
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): ProfilePickerUiState {
        val profiles = list
        val current = gate.currentProfileId
        val status = gate.syncStatus.value
        val tiles = profiles.orEmpty().map { profile ->
            val locked = profile.hasPin
            val isCurrent = profile.id == current
            ProfileTile(
                id = profile.id,
                name = profile.name,
                initial = profileInitial(profile.name),
                avatar = ProfileAvatars.forProfile(profile.avatar, profile.id),
                locked = locked,
                current = isCurrent,
                badge = when {
                    managing -> ProfileBadge.EDIT
                    locked -> ProfileBadge.LOCK
                    isCurrent -> ProfileBadge.CURRENT
                    else -> null
                },
                stateText = listOfNotNull(
                    T.profiles.editor.editTitle.takeIf { managing },
                    T.profiles.editor.pinOn.takeIf { locked },
                    T.profiles.current.takeIf { isCurrent },
                ).takeIf { it.isNotEmpty() }?.joinToString(T.detail.listSeparator),
            )
        }
        val loading = profiles == null || (profiles.isEmpty() && status.lastSyncedAtMillis == null &&
            (status.kind == SyncStatus.Kind.Syncing || status.kind == SyncStatus.Kind.Idle))
        return ProfilePickerUiState(
            title = if (managing) T.profiles.manageTitle else T.profiles.title,
            body = when {
                loading -> ProfilePickerBody.Loading
                tiles.isEmpty() -> ProfilePickerBody.Empty(T.profiles.empty)
                else -> ProfilePickerBody.Tiles(tiles)
            },
            managing = managing,
            canClose = gate.switching,
            pinPrompt = promptProfileId?.let { id -> prompt(tiles.firstOrNull { it.id == id }) },
            syncStatus = status.text,
        )
    }

    private fun prompt(tile: ProfileTile?): PinPromptUiState? {
        tile ?: return null
        val lockedUntil = lockedUntil?.takeIf { it > gate.clock() }
        return PinPromptUiState(
            profileId = tile.id,
            title = T.profiles.pin.title(isolate(tile.name)),
            initial = tile.initial,
            avatar = tile.avatar,
            entered = digits.length,
            length = PIN_LENGTH,
            error = pinError.takeIf { lockedUntil == null },
            lockedOutText = lockedUntil?.let { T.profiles.pin.lockedOut(remainingText(it, gate.clock())) },
            acceptsInput = lockedUntil == null && !checking,
        )
    }

    companion object {
        /** Its name on the store probe while its screen shows (test reports read it there). */
        const val KEY: String = "profiles"

        /** Profile PINs are four digits, entered on a pad (TV remotes included). */
        const val PIN_LENGTH: Int = 4
    }
}
