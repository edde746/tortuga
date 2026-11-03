@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The root store: account state, profiles and the profile choice to the top-level screen. */
class AccountGateTest {
    private val server = FakeSyncServer()

    @Test
    fun followsTheAccountFromSignInToTheProfilesAndTheApp() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            phone.signUp(server)
            AccountTestApp(server, this, name = "Tablet").use { tablet ->
                assertEquals(AccountDestination.Starting, tablet.gate.state.value.destination)
                val signedOut = tablet.gate.awaitDestination(AccountDestination.SignIn)
                assertEquals(SyncStatus.Kind.SignedOut, signedOut.syncKind)
                assertEquals(T.account.status.signedOut, signedOut.syncStatus)

                tablet.account.signIn(server.serverUrl) { TestDevice.credential() }
                tablet.gate.awaitDestination(AccountDestination.Profiles)
                // The status line follows the account a moment later.
                val synced = tablet.gate.state.await { it.syncKind == SyncStatus.Kind.Idle }
                assertEquals(T.account.status.idle, synced.syncStatus)
                assertEquals(AccountDestination.Profiles, synced.destination)

                val profile = tablet.data.profiles.create("Wednesday", null)
                tablet.gate.enterProfile(profile.id)
                val main = tablet.gate.awaitMain()
                assertEquals(profile.id, main.profileId)
                runCurrent()
                assertEquals(profile.id, tablet.data.deviceSettings.settings().activeProfileId)

                // Changing profile, then changing one's mind.
                tablet.gate.switchProfile()
                assertEquals(AccountDestination.Profiles, tablet.gate.state.value.destination)
                tablet.gate.cancelSwitch()
                assertIs<AccountDestination.Main>(tablet.gate.state.value.destination)

                // The running profile deleted (on any device) goes back to the picker.
                tablet.data.profiles.delete(profile.id)
                tablet.gate.awaitDestination(AccountDestination.Profiles)
            }
        }
    }

    @Test
    fun theLastProfileOpensAtLaunchUnlessItHasAPin() = runTest {
        val secrets = MemorySecretStore()
        TestDevice("Phone", server, this, backgroundScope, secrets).use { phone ->
            phone.signUp(server)
            val open = phone.data.profiles.create("Kids", null)
            val locked = phone.data.profiles.create("Parents", null)
            phone.data.profiles.setPin(locked.id, "2468")

            phone.data.deviceSettings.set(DeviceSetting.ActiveProfileId, open.id)
            val gate = AccountGate(phone.account, phone.engine.status, phone.data.profiles, phone.data.deviceSettings, { phone.account.restore() }, { 0L }, backgroundScope)
            assertEquals(open.id, gate.awaitMain().profileId)
            gate.release()

            phone.data.deviceSettings.set(DeviceSetting.ActiveProfileId, locked.id)
            val relaunched = AccountGate(phone.account, phone.engine.status, phone.data.profiles, phone.data.deviceSettings, { phone.account.restore() }, { 0L }, backgroundScope)
            relaunched.awaitDestination(AccountDestination.Profiles)
            // The picker asks for this profile's PIN at once.
            assertEquals(locked.id, relaunched.takeLockedProfile())
            relaunched.release()
        }
    }

    @Test
    fun aSessionEndedElsewhereSignsOutWithANotice() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            phone.signUp(server)
            AccountTestApp(server, this, name = "Tablet").use { tablet ->
                tablet.gate.awaitDestination(AccountDestination.SignIn)
                tablet.account.signIn(server.serverUrl) { TestDevice.credential() }
                tablet.gate.awaitDestination(AccountDestination.Profiles)

                val tabletId = phone.account.devices().single { !it.current }.deviceId
                phone.account.revokeDevice(tabletId)
                // The tablet's next request finds its session gone.
                runCatchingAccount { tablet.account.accountInfo() }
                val signedOut = tablet.gate.awaitDestination(AccountDestination.SignIn)
                assertEquals(T.account.error.sessionEnded, signedOut.notice)
                tablet.gate.dismissNotice()
                assertNull(tablet.gate.state.value.notice)
            }
        }
    }

    @Test
    fun deletingTheAccountSignsOutAndSaysSo() = runTest {
        AccountTestApp(server, this).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            app.account.signIn(server.serverUrl) { TestDevice.credential() }
            app.gate.awaitDestination(AccountDestination.Profiles)
            app.account.deleteAccount()
            runCurrent()
            val signedOut = app.gate.awaitDestination(AccountDestination.SignIn)
            assertEquals(T.account.deletion.done, signedOut.notice)
            // Settings hands over what the deletion said (Apple's manual step, when it applies).
            app.gate.showAccountDeleted(T.account.deletion.appleManual)
            assertEquals(T.account.deletion.appleManual, app.gate.state.value.notice)
        }
    }

    private suspend fun runCatchingAccount(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: AccountException) {
            // The session is gone: that is what the test waits for.
        }
    }
}
