package com.edde746.tortuga.app

import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** The shared core starts only once the platform has reported the system's settings ([SystemInputs.report]). */
class SystemInputsTest {
    private val reportedBefore = SystemInputs.reported

    @AfterTest
    fun restore() {
        SystemInputs.reported = reportedBefore
    }

    @Test
    fun aCoreBuiltBeforeTheSystemsSettingsAreReportedFailsInsteadOfShowingEnglish() {
        SystemInputs.reported = false
        val error = assertFailsWith<IllegalStateException> {
            AppBootstrap.local(inMemoryDatabase(), BuildFlavor.SIDELOADED, "test", DeviceDescriptor("Test", DevicePlatform.ANDROID)) {}.close()
        }
        assertContains(error.message.orEmpty(), "SystemInputs.report")
    }
}
