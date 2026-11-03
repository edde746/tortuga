@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.presentation.account.AccountDestination
import com.edde746.tortuga.presentation.account.AccountTestApp
import com.edde746.tortuga.presentation.account.await
import com.edde746.tortuga.presentation.account.awaitDestination
import com.edde746.tortuga.presentation.profiles
import com.edde746.tortuga.presentation.account.awaitMain
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The profile picker: tiles, PIN entry and its throttling, managing, switching. */
class ProfilePickerStoreTest {
    private val server = FakeSyncServer()

    private class RecordingHost : ProfilePickerHost {
        val edited = ArrayList<String>()
        var creates = 0
        override fun createProfile() {
            creates++
        }
        override fun editProfile(profileId: String) {
            edited += profileId
        }
    }

    /** A ready account with an open profile and one locked with 1234. */
    private suspend fun TestScope.withProfiles(block: suspend (app: AccountTestApp, kids: Profile, parents: Profile) -> Unit) {
        AccountTestApp(server, this).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            app.account.signIn(server.serverUrl) { TestDevice.credential() }
            val kids = app.data.profiles.create("kids", "sea")
            val parents = app.data.profiles.create("Parents", null)
            app.data.profiles.setPin(parents.id, "1234")
            app.gate.awaitDestination(AccountDestination.Profiles)
            block(app, kids, parents)
        }
    }

    private fun ProfilePickerStore.type(pin: String) = pin.forEach { pinDigit(it.digitToInt()) }

    @Test
    fun tilesShowTheAvatarsAndWhichProfilesAreLocked() = runTest {
        withProfiles { app, kids, parents ->
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            val state = picker.state.await { it.profiles.size == 2 }
            assertEquals(T.profiles.title, state.title)
            assertTrue(state.body is ProfilePickerBody.Tiles)
            val first = state.profiles.single { it.id == kids.id }
            val second = state.profiles.single { it.id == parents.id }
            assertEquals("K", first.initial)
            assertEquals("sea", first.avatar.id)
            assertFalse(first.locked)
            assertTrue(second.locked)
            // No stored avatar: a steady one from the set.
            assertEquals(ProfileAvatars.forProfile(null, parents.id), second.avatar)
            assertFalse(state.canClose)
        }
    }

    @Test
    fun anOpenProfileEntersAtOnceAndBecomesTheDevicesProfile() = runTest {
        withProfiles { app, kids, _ ->
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            picker.state.await { it.profiles.size == 2 }
            picker.select(kids.id)
            assertEquals(kids.id, app.gate.awaitMain().profileId)
            runCurrent()
            assertEquals(kids.id, app.data.deviceSettings.settings().activeProfileId)
        }
    }

    @Test
    fun wrongPinsAreThrottledWithGrowingPauses() = runTest {
        withProfiles { app, _, parents ->
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            picker.state.await { it.profiles.size == 2 }
            picker.select(parents.id)
            val prompt = assertNotNull(picker.state.value.pinPrompt)
            assertEquals(T.profiles.pin.title(isolate("Parents")), prompt.title)
            assertEquals(0, prompt.entered)
            assertEquals(4, prompt.length)

            picker.type("12")
            assertEquals(2, picker.state.value.pinPrompt?.entered)
            picker.pinBackspace()
            picker.pinBackspace()
            repeat(PinThrottle.FREE_ATTEMPTS) {
                picker.type("0000")
                runCurrent()
                val wrong = assertNotNull(picker.state.value.pinPrompt)
                assertEquals(T.profiles.pin.wrong, wrong.error)
                assertEquals(0, wrong.entered)
                assertTrue(wrong.acceptsInput)
            }
            picker.type("9999")
            runCurrent()
            val locked = assertNotNull(picker.state.value.pinPrompt)
            assertEquals(T.profiles.pin.lockedOut("0:30"), locked.lockedOutText)
            assertFalse(locked.acceptsInput)
            // Digits are ignored while locked, even the right PIN.
            picker.type("1234")
            runCurrent()
            assertEquals(AccountDestination.Profiles, app.gate.state.value.destination)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(T.profiles.pin.lockedOut("0:20"), picker.state.value.pinPrompt?.lockedOutText)
            advanceTimeBy(20_000)
            runCurrent()
            assertTrue(picker.state.value.pinPrompt!!.acceptsInput)
            assertNull(picker.state.value.pinPrompt!!.lockedOutText)

            // The next wrong PIN pauses twice as long; closing and reopening the pad doesn't reset it.
            picker.type("5555")
            runCurrent()
            assertEquals(T.profiles.pin.lockedOut("1:00"), picker.state.value.pinPrompt?.lockedOutText)
            picker.cancelPin()
            assertNull(picker.state.value.pinPrompt)
            picker.select(parents.id)
            runCurrent()
            assertFalse(picker.state.value.pinPrompt!!.acceptsInput)
            advanceTimeBy(60_000)
            runCurrent()

            picker.type("1234")
            assertEquals(parents.id, app.gate.awaitMain().profileId)
        }
    }

    @Test
    fun closingThePadWhileAPinIsCheckedStillCountsAWrongOne() = runTest {
        withProfiles { app, _, parents ->
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            picker.state.await { it.profiles.size == 2 }
            repeat(PinThrottle.FREE_ATTEMPTS + 1) {
                picker.select(parents.id)
                picker.type("0000")
                // Closed before the check answers.
                picker.cancelPin()
                runCurrent()
            }
            picker.select(parents.id)
            runCurrent()
            val locked = assertNotNull(picker.state.value.pinPrompt)
            assertEquals(T.profiles.pin.lockedOut("0:30"), locked.lockedOutText)
            assertFalse(locked.acceptsInput)
        }
    }

    /** The wrong PINs are kept on the device: relaunching the app doesn't give a locked-out pad fresh guesses. */
    @Test
    fun aLockoutOutlivesARelaunch() = runTest {
        withProfiles { app, _, parents ->
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            picker.state.await { it.profiles.size == 2 }
            picker.select(parents.id)
            repeat(PinThrottle.FREE_ATTEMPTS + 1) {
                picker.type("0000")
                runCurrent()
            }
            assertFalse(picker.state.value.pinPrompt!!.acceptsInput)
            picker.release()

            val relaunched = com.edde746.tortuga.presentation.account.AccountGate(
                app.account, app.device.engine.status, app.data.profiles, app.data.deviceSettings, {}, app.clock, backgroundScope,
            )
            relaunched.awaitDestination(AccountDestination.Profiles)
            val again = ProfilePickerStore(app.data.profiles, relaunched, RecordingHost(), backgroundScope)
            again.state.await { it.profiles.size == 2 }
            again.select(parents.id)
            runCurrent()
            val locked = assertNotNull(again.state.value.pinPrompt)
            assertEquals(T.profiles.pin.lockedOut("0:30"), locked.lockedOutText)
            assertFalse(locked.acceptsInput)
            again.type("1234")
            runCurrent()
            assertEquals(AccountDestination.Profiles, relaunched.state.value.destination, "the right PIN isn't taken while locked out")
        }
    }

    @Test
    fun aCheckOfAnEarlierPadLeavesTheReopenedOneAlone() = runTest {
        withProfiles { app, _, parents ->
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            picker.state.await { it.profiles.size == 2 }
            picker.select(parents.id)
            picker.type("0000")
            picker.cancelPin()
            picker.select(parents.id)
            picker.type("12")
            // The first pad's wrong PIN is answered now: the new pad keeps its digits and shows no error.
            runCurrent()
            val prompt = assertNotNull(picker.state.value.pinPrompt)
            assertEquals(2, prompt.entered)
            assertNull(prompt.error)
            assertTrue(prompt.acceptsInput)
            picker.type("34")
            assertEquals(parents.id, app.gate.awaitMain().profileId)
        }
    }

    @Test
    fun theDevicesLockedProfileAsksForItsPinAtLaunch() = runTest {
        withProfiles { app, _, parents ->
            app.data.deviceSettings.set(DeviceSetting.ActiveProfileId, parents.id)
            val relaunched = com.edde746.tortuga.presentation.account.AccountGate(
                app.account, app.device.engine.status, app.data.profiles, app.data.deviceSettings, {}, app.clock, backgroundScope,
            )
            relaunched.awaitDestination(AccountDestination.Profiles)
            val picker = ProfilePickerStore(app.data.profiles, relaunched, RecordingHost(), backgroundScope)
            assertEquals(parents.id, picker.state.await { it.pinPrompt?.title != null }.pinPrompt?.profileId)
            picker.type("1234")
            assertEquals(parents.id, relaunched.awaitMain().profileId)
        }
    }

    @Test
    fun managingEditsProfilesAfterTheirPinAndSwitchingCanGoBack() = runTest {
        withProfiles { app, kids, parents ->
            app.gate.enterProfile(kids.id)
            app.gate.awaitMain()
            app.gate.switchProfile()
            assertEquals(AccountDestination.Profiles, app.gate.state.value.destination)

            val host = RecordingHost()
            val picker = ProfilePickerStore(app.data.profiles, app.gate, host, backgroundScope)
            val switching = picker.state.await { it.profiles.size == 2 }
            assertTrue(switching.canClose)
            assertTrue(switching.profiles.first { it.id == kids.id }.current)

            picker.manage()
            assertEquals(T.profiles.manageTitle, picker.state.value.title)
            picker.select(kids.id)
            assertEquals(listOf(kids.id), host.edited)
            picker.select(parents.id)
            assertEquals(listOf(kids.id), host.edited)
            picker.type("1234")
            runCurrent()
            assertEquals(listOf(kids.id, parents.id), host.edited)
            // Managing never enters a profile.
            assertEquals(AccountDestination.Profiles, app.gate.state.value.destination)
            picker.addProfile()
            assertEquals(1, host.creates)

            picker.doneManaging()
            picker.close()
            assertEquals(kids.id, app.gate.awaitMain().profileId)
        }
    }

    @Test
    fun aTileIsMarkedForEditingWhileManagingElseLockedElseCurrentAndSaysEachThatHolds() = runTest {
        withProfiles { app, kids, parents ->
            app.gate.enterProfile(parents.id)
            app.gate.awaitMain()
            app.gate.switchProfile()
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            val switching = picker.state.await { it.profiles.size == 2 }
            val current = switching.profiles.single { it.id == parents.id }
            assertEquals(ProfileBadge.LOCK, current.badge, "the lock before the current profile's mark")
            assertEquals(T.profiles.editor.pinOn + T.detail.listSeparator + T.profiles.current, current.stateText)
            val open = switching.profiles.single { it.id == kids.id }
            assertNull(open.badge)
            assertNull(open.stateText)

            picker.manage()
            val managing = picker.state.value.profiles
            assertEquals(listOf(ProfileBadge.EDIT, ProfileBadge.EDIT), managing.map { it.badge }, "the pencil before any other mark")
            assertEquals(
                listOf(T.profiles.editor.editTitle, T.profiles.editor.pinOn, T.profiles.current).joinToString(T.detail.listSeparator),
                managing.single { it.id == parents.id }.stateText,
            )
            assertEquals(T.profiles.editor.editTitle, managing.single { it.id == kids.id }.stateText)
        }
    }

    @Test
    fun aNewAccountAsksForAFirstProfile() = runTest {
        AccountTestApp(server, this).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            app.account.signIn(server.serverUrl) { TestDevice.credential() }
            app.gate.awaitDestination(AccountDestination.Profiles)
            val picker = ProfilePickerStore(app.data.profiles, app.gate, RecordingHost(), backgroundScope)
            val empty = picker.state.await { it.body != ProfilePickerBody.Loading }
            assertEquals(ProfilePickerBody.Empty(T.profiles.empty), empty.body)
        }
    }
}
