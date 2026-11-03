@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.account.signin.noProviderCalls
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Account settings' "… ago" texts move on as time passes while the screen is open. */
class AccountSettingsAgoTest {
    private val server = FakeSyncServer()

    @Test
    fun anotherDevicesLastActivityAgesWhileTheScreenIsOpen() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            phone.account.restore()
            phone.account.signIn(server.serverUrl) { TestDevice.credential("alice") }
            TestDevice("Tablet", server, this, backgroundScope).use { tablet ->
                tablet.account.restore()
                tablet.account.signIn(server.serverUrl) { TestDevice.credential("alice") }
                val flows = ProviderFlows(noProviderCalls, TestSignInPlatform(), phone.account.clock, { error("no Apple web flow") }, { error("no GitHub web flow") })
                val store = AccountSettingsStore(
                    phone.account, phone.engine, flows,
                    object : AccountSettingsHost {
                        override fun linkIdentity() = Unit

                        override fun accountDeleted(message: String) = Unit
                    },
                    phone.account.clock, backgroundScope,
                )
                runCurrent()
                fun tablet() = store.state.value.devices.single { it.name == "Tablet" }.detail
                fun detail(ago: String) = T.settings.account.deviceDetail(isolate(T.settings.account.platform.linux), T.settings.account.lastActive(ago))
                assertEquals(detail(T.settings.time.justNow), tablet())

                advanceTimeBy(1.minutes + 1.seconds)
                runCurrent()
                assertEquals(detail(T.settings.time.minutesAgo(1)), tablet())

                advanceTimeBy(1.hours)
                runCurrent()
                assertEquals(detail(T.settings.time.hoursAgo(1)), tablet())
            }
        }
    }
}
