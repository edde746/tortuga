@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.crash

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.sync.HybridClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportsTest {
    private val dsn = "https://public@crashes.example/1"

    private fun TestScope.data() = LocalData(
        inMemoryDatabase(), { 1_790_000_000_000L },
        StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )

    @Test
    fun reportsGoToTheDsnWhileTheDeviceSettingIsOn() = runTest {
        val data = data()
        val reports = CrashReports(data.deviceSettings, dsn, "tortuga@1.0.0", CrashReports.PRODUCTION, backgroundScope, emptyFlow())
        runCurrent()
        assertEquals(dsn, reports.active.value, "on while the setting is unset")

        data.deviceSettings.set(DeviceSetting.CrashReports, false)
        runCurrent()
        assertNull(reports.active.value)

        data.deviceSettings.set(DeviceSetting.CrashReports, true)
        runCurrent()
        assertEquals(dsn, reports.active.value)
        data.close()
    }

    @Test
    fun aBuildWithoutADsnNeverReports() = runTest {
        val data = data()
        data.deviceSettings.set(DeviceSetting.CrashReports, true)
        val reports = CrashReports(data.deviceSettings, " ", "tortuga@1.0.0", CrashReports.PRODUCTION, backgroundScope, emptyFlow())
        runCurrent()
        assertNull(reports.active.value)
        data.close()
    }

    @Test
    fun aCoroutineTheProductSurvivedIsReportedOnlyWhileTheDeviceSettingIsOn() = runTest {
        val data = data()
        val boundary = FailureBoundary(crash = false)
        val app = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + boundary)
        val reports = CrashReports(data.deviceSettings, dsn, "tortuga@1.0.0", CrashReports.PRODUCTION, app, boundary.failures)
        val sent = mutableListOf<String?>()
        backgroundScope.launch { reports.failures.collect { sent += it.message } }
        // A store's scope, as every store builds it from the app's.
        val store = CoroutineScope(app.coroutineContext + SupervisorJob(app.coroutineContext[Job]))
        runCurrent()

        store.launch { error("while on") }
        runCurrent()
        data.deviceSettings.set(DeviceSetting.CrashReports, false)
        runCurrent()
        store.launch { error("while off") }
        runCurrent()

        assertEquals(listOf<String?>("while on"), sent)
        data.close()
    }

    @Test
    fun scrubbedTextHasNoAddonPathAndNoHomeDirectory() {
        val addon = "https://addon.example/key=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST/manifest.json"
        val windows = CrashReports.scrub("""failed: $addon while reading C:\Users\Jane Doe\AppData\Roaming\Tortuga\tortuga.db""", """C:\Users\Jane Doe""")
        assertEquals("""failed: https://addon.example/… while reading ~\AppData\Roaming\Tortuga\tortuga.db""", windows)

        val slashes = CrashReports.scrub("open c:/users/jane doe/Videos/a.mkv", """C:\Users\Jane Doe""")
        assertEquals("open ~/Videos/a.mkv", slashes)

        val linux = CrashReports.scrub("/home/jane/.local/share/tortuga/x.db", "/home/jane")
        assertTrue(linux.startsWith("~/"))
        assertFalse("jane" in CrashReports.scrub("/home/jane/x", "/home/jane"))
        assertEquals("/data/user/0/x", CrashReports.scrub("/data/user/0/x", "/"), "a root home changes nothing")
    }
}
