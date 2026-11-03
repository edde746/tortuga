@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.app

import com.edde746.tortuga.presentation.account.await
import com.edde746.tortuga.presentation.account.signUp
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The splash's status line: the step the product's start is on, named once the start has taken half a second. */
class StartupTest {
    private val server = FakeSyncServer()

    @Test
    fun theStatusNamesTheStepOnlyAfterHalfASecondFollowsItAndGoesOnceTheStartIsDone() = runTest {
        val startup = Startup(backgroundScope, Duration.ZERO)
        assertEquals(StartupUiState(StartupStep.DATABASE, null), startup.state.value)

        startup.begin(StartupStep.MIGRATION)
        advanceTimeBy(Startup.STATUS_DELAY - 1.milliseconds)
        runCurrent()
        assertEquals(StartupUiState(StartupStep.MIGRATION, null), startup.state.value)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(StartupUiState(StartupStep.MIGRATION, T.app.startup.migration), startup.state.value)

        startup.begin(StartupStep.ACCOUNT)
        assertEquals(StartupUiState(StartupStep.ACCOUNT, T.app.startup.account), startup.state.value)
        startup.begin(StartupStep.PROFILES)
        assertEquals(StartupUiState(StartupStep.PROFILES, T.app.startup.profiles), startup.state.value)

        startup.finish()
        assertEquals(StartupUiState(null, null), startup.state.value)
        startup.begin(StartupStep.ACCOUNT)
        advanceTimeBy(10.seconds)
        assertEquals(StartupUiState(null, null), startup.state.value)
    }

    @Test
    fun aStartDoneWithinHalfASecondShowsTheLoaderAlone() = runTest {
        val startup = Startup(backgroundScope, Duration.ZERO)
        val seen = record(startup)

        startup.begin(StartupStep.ACCOUNT)
        advanceTimeBy(Startup.STATUS_DELAY - 1.milliseconds)
        startup.finish()
        advanceTimeBy(10.seconds)

        assertEquals(
            listOf(StartupUiState(StartupStep.DATABASE, null), StartupUiState(StartupStep.ACCOUNT, null), StartupUiState(null, null)),
            seen,
        )
    }

    @Test
    fun theProductStartGoesThroughItsStepsToTheFirstScreen() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { device ->
            device.signUp(server)
            // A debug launch's slow start holds the account step past the status delay.
            val startup = Startup(backgroundScope, 1.seconds)
            val seen = record(startup)

            val root = RootSession(testGraph(device.data), device.account, device.engine, TestSignInPlatform(), FakeAppPlatform(), startup)
            root.screen.await { it == RootScreen.Profiles }

            assertEquals(
                listOf(
                    StartupUiState(StartupStep.DATABASE, null),
                    StartupUiState(StartupStep.ACCOUNT, null),
                    StartupUiState(StartupStep.ACCOUNT, T.app.startup.account),
                    StartupUiState(StartupStep.PROFILES, T.app.startup.profiles),
                    StartupUiState(null, null),
                ),
                seen,
            )
            root.close()
        }
    }

    /** Every state [startup] goes through from now on. */
    private fun TestScope.record(startup: Startup): List<StartupUiState> =
        mutableListOf<StartupUiState>().also { seen -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { startup.state.toList(seen) } }
}
