@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parent's PIN pad against the profile picker's: one schedule of wrong PINs, whichever pad takes them. */
class ParentPinStoreTest {
    private class Pad(val data: LocalData, val throttle: PinThrottle, val parentId: String)

    private suspend fun TestScope.pad(block: suspend Pad.() -> Unit) {
        val clock = { 1_790_000_000_000L + testScheduler.currentTime }
        LocalData(inMemoryDatabase(), clock, StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS).use { data ->
            val parent = data.profiles.create("Parent", null)
            data.profiles.setPin(parent.id, "1234")
            data.profiles.create("Mia", null, kids = true)
            Pad(data, PinThrottle(data.deviceSettings, clock), parent.id).block()
        }
    }

    private fun TestScope.open(pad: Pad) =
        ParentPinStore(ParentCheck.Leave, pad.data.profiles, pad.throttle, { 1_790_000_000_000L + testScheduler.currentTime }, {}, {}, backgroundScope)
            .also { runCurrent() }

    private fun ParentPinStore.type(pin: String) = pin.forEach { digit(it.digitToInt()) }

    @Test
    fun aParentsPinLockedOnThePickerLocksTheParentPadToo() = runTest {
        pad {
            repeat(PinThrottle.FREE_ATTEMPTS + 1) { throttle.failed(parentId) }
            val store = open(this)
            assertEquals(T.profiles.pin.lockedOut("0:30"), store.state.value.error)
            assertFalse(store.state.value.acceptsInput)
            store.type("1234")
            runCurrent()
            assertEquals(0, store.state.value.entered, "the right PIN isn't taken while locked out")

            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(store.state.value.acceptsInput)
            store.release()
        }
    }

    @Test
    fun wrongPinsOnTheParentPadCountAgainstEachParentsOwnEntry() = runTest {
        pad {
            val store = open(this)
            repeat(PinThrottle.FREE_ATTEMPTS) {
                store.type("0000")
                runCurrent()
            }
            assertTrue(store.state.value.acceptsInput)
            assertNull(throttle.lockedUntil(parentId))
            store.type("0000")
            assertFalse(store.state.value.acceptsInput, "dimmed while the PIN is checked")
            runCurrent()
            assertFalse(store.state.value.acceptsInput)
            assertNotNull(throttle.lockedUntil(parentId), "the picker's pad for the parent is locked out as well")
            store.release()
        }
    }
}
