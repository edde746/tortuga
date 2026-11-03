package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.presentation.account.nextTickMillis
import com.edde746.tortuga.presentation.account.remainingText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a parent's PIN is asked for on a kids profile ([ParentPinUiState.subtitle]). */
sealed interface ParentCheck {
    /** Allowing the title [title] on the kids profile; null when its name isn't known yet (opened from a link). */
    data class Allow(val title: String?) : ParentCheck

    /** Blocking the title [title] on the kids profile. */
    data class Block(val title: String) : ParentCheck

    /** Leaving the kids profile: the profile picker. */
    data object Leave : ParentCheck

    /** Settings the kids profile doesn't reach: the profile picker's managing, from Settings. */
    data object Settings : ParentCheck
}

/**
 * The parent's PIN pad over a kids profile ([com.edde746.tortuga.app.AppOverlay.ParentPin]).
 *
 * @param title "Enter a parent's PIN".
 * @param subtitle what it is for: "To allow Frozen on this profile", "To leave this profile", ...
 * @param entered digits in so far (the pad shows that many dots of [length]).
 * @param error "Wrong PIN. Try again." after a wrong PIN, or "Too many attempts. Try again in 0:30.", counting down
 *   while entry is locked out (digits are ignored then).
 */
data class ParentPinUiState(val title: String, val subtitle: String, val entered: Int, val length: Int, val error: String?) {
    /** The pad's dots, spoken ([pinEnteredText]). */
    val enteredText: String
        get() = pinEnteredText(entered, length)
}

/**
 * A parent's PIN, asked on a kids profile before what it guards ([ParentCheck]): the PIN of any profile on the account
 * that isn't a kids profile unlocks it. Wrong PINs count in this device's parent PIN throttle ([PinThrottle.PARENT]),
 * which a relaunch doesn't reset; a wrong PIN counts even if the pad is closed before the check answers.
 *
 * Call everything on the main thread; [release] ends it.
 *
 * @param unlocked a right PIN was entered: what the check guards goes ahead.
 * @param cancelled the pad was closed without one.
 */
class ParentPinStore internal constructor(
    private val check: ParentCheck,
    private val profiles: ProfileRepository,
    private val throttle: PinThrottle,
    private val clock: () -> Long,
    private val unlocked: () -> Unit,
    private val cancelled: () -> Unit,
    /** The app session's scope: a check started before [release] still counts in the throttle. */
    private val parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var digits = ""
    private var wrong = false
    private var checking = false
    private var lockedUntil: Long? = null
    private var lockoutJob: Job? = null
    private var done = false

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<ParentPinUiState> = mutableState.asStateFlow()

    init {
        scope.launch { throttle.lockedUntil(PinThrottle.PARENT)?.let(::startLockout) }
    }

    /** A digit (0–9) on the pad or the keyboard; the fourth checks the PIN. */
    fun digit(digit: Int) {
        if (digit !in 0..9 || !acceptsInput()) return
        digits += digit.toString()
        wrong = false
        if (digits.length == ProfilePickerStore.PIN_LENGTH) verify() else publish()
    }

    fun backspace() {
        if (!acceptsInput() || digits.isEmpty()) return
        digits = digits.dropLast(1)
        publish()
    }

    /** Closes the pad: what the check guards doesn't happen. */
    fun cancel() {
        if (done) return
        done = true
        cancelled()
    }

    fun release() {
        scope.cancel()
    }

    private fun acceptsInput(): Boolean = !done && !checking && lockoutJob?.isActive != true

    /** Checks the PIN against every profile that isn't a kids profile; a lockout read meanwhile refuses it unchecked. */
    private fun verify() {
        val pin = digits
        checking = true
        publish()
        parentScope.launch {
            val locked = throttle.lockedUntil(PinThrottle.PARENT)
            if (locked != null) {
                checking = false
                digits = ""
                startLockout(locked)
                return@launch
            }
            val parents = profiles.profiles().filter { !it.kids && it.hasPin }
            val right = parents.any { profiles.verifyPin(it.id, pin) }
            val lockout = if (right) {
                throttle.succeeded(PinThrottle.PARENT)
                null
            } else {
                throttle.failed(PinThrottle.PARENT)
            }
            checking = false
            digits = ""
            if (right) {
                if (!done) {
                    done = true
                    unlocked()
                }
                publish()
            } else {
                wrong = true
                lockout?.let(::startLockout) ?: publish()
            }
        }
    }

    private fun startLockout(until: Long) {
        lockoutJob?.cancel()
        lockedUntil = until
        lockoutJob = scope.launch {
            while (true) {
                val now = clock()
                if (now >= until) break
                publish()
                delay(nextTickMillis(until, now))
            }
            lockoutJob = null
            lockedUntil = null
            wrong = false
            publish()
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): ParentPinUiState {
        val text = T.kids.pin
        val lockedUntil = lockedUntil?.takeIf { it > clock() }
        return ParentPinUiState(
            title = text.title,
            subtitle = when (check) {
                is ParentCheck.Allow -> check.title?.let { text.allow(isolate(it)) } ?: text.allowThis
                is ParentCheck.Block -> text.block(isolate(check.title))
                ParentCheck.Leave -> text.leave
                ParentCheck.Settings -> text.settings
            },
            entered = digits.length,
            length = ProfilePickerStore.PIN_LENGTH,
            error = when {
                lockedUntil != null -> T.profiles.pin.lockedOut(remainingText(lockedUntil, clock()))
                wrong -> T.profiles.pin.wrong
                else -> null
            },
        )
    }
}
