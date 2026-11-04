package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.PinLockout

/**
 * Slows down guessing a PIN, per PIN entry (a profile's id, or [PARENT] for a parent's PIN) on this device: [FREE_ATTEMPTS]
 * wrong PINs in a row are free, then each wrong PIN locks that entry for [FIRST_LOCKOUT_MILLIS], doubling up to
 * [MAX_LOCKOUT_MILLIS]. A right PIN clears the count. The counts and lockouts are kept in a device setting
 * ([DeviceSetting.PinLockouts]), so relaunching the app doesn't reset them. PINs are a lock, not access control, so
 * this only has to make guessing tedious at the screen. A pad that tries several PINs at once (the parent's) counts
 * against each of their entries and stays shut while any of them is locked ([ParentPinStore]).
 *
 * @param clock the wall clock, in epoch milliseconds: a lockout outlives the process.
 */
internal class PinThrottle(private val settings: DeviceSettingsRepository, private val clock: () -> Long) {
    /** When [key]'s PIN entry opens again, or null if it is open. */
    suspend fun lockedUntil(key: String): Long? = lockedUntil(listOf(key))

    /** When every one of [keys]' PIN entries is open again, or null if all are open. */
    suspend fun lockedUntil(keys: Collection<String>): Long? {
        val all = settings.value(DeviceSetting.PinLockouts)
        val now = clock()
        return keys.mapNotNull { key -> all[key]?.lockedUntil?.takeIf { it > now } }.maxOrNull()
    }

    /** Counts a wrong PIN; returns when entry opens again if this one locked it. */
    suspend fun failed(key: String): Long? = failed(listOf(key))

    /** Counts a wrong PIN against each of [keys]; returns when they all open again if this one locked any. */
    suspend fun failed(keys: Collection<String>): Long? {
        var lockedUntil: Long? = null
        settings.update(DeviceSetting.PinLockouts) { stored ->
            lockedUntil = null
            keys.fold(stored) { all, key ->
                val entry = all[key] ?: PinLockout(0, 0)
                val failures = entry.failures + 1
                val until = if (failures <= FREE_ATTEMPTS) {
                    null
                } else {
                    // How many locking failures came before this one: each doubles the pause.
                    val over = failures - FREE_ATTEMPTS - 1
                    clock() + if (over >= MAX_DOUBLINGS) MAX_LOCKOUT_MILLIS else minOf(MAX_LOCKOUT_MILLIS, FIRST_LOCKOUT_MILLIS shl over)
                }
                if (until != null) lockedUntil = maxOf(lockedUntil ?: until, until)
                all + (key to PinLockout(failures, until ?: entry.lockedUntil))
            }
        }
        return lockedUntil
    }

    suspend fun succeeded(key: String) {
        settings.update(DeviceSetting.PinLockouts) { it - key }
    }

    companion object {
        /** Wrong PINs in a row that don't lock: the one after them locks for [FIRST_LOCKOUT_MILLIS]. */
        const val FREE_ATTEMPTS: Int = 4
        const val FIRST_LOCKOUT_MILLIS: Long = 30_000
        const val MAX_LOCKOUT_MILLIS: Long = 15 * 60_000

        /** The parent's PIN entry ([ParentPinStore]): one for the device, whichever profile's PIN is tried. */
        const val PARENT: String = "parent"
        private const val MAX_DOUBLINGS = 10
    }
}
