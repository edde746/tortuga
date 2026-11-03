@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.app

import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AppBootstrapTest {
    private val originalSink = Log.sink

    @AfterTest
    fun restore() {
        Log.sink = originalSink
        Dispatchers.resetMain()
    }

    @Test
    fun aStoresCoroutineThatThrowsIsLoggedAndTheProductRunsOn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The system's settings, as a platform reports them before it builds a core.
        SystemInputs.report(listOf("en"), region = null, captions = false, audioDescriptions = false)
        val errors = mutableListOf<String>()
        Log.sink = Log.Sink { level, _, _, error -> if (level == Log.Level.ERROR && error != null) errors += error }
        val device = DeviceDescriptor("Test", DevicePlatform.WINDOWS)
        AppBootstrap.product(inMemoryDatabase(), MemorySecretStore(), BuildFlavor.SIDELOADED, "test", device, "cache", Startup()).use { boot ->
            val app = boot.graph.scope
            // A store's scope, as every store builds it from the graph's.
            val store = CoroutineScope(app.coroutineContext + SupervisorJob(app.coroutineContext[Job]))
            store.launch { throw IllegalStateException("a store's work failed") }
            runCurrent()

            var ranAfter = false
            store.launch { ranAfter = true }
            runCurrent()

            assertTrue(errors.any { "a store's work failed" in it }, errors.joinToString("\n"))
            assertTrue(ranAfter, "the store's next coroutine runs")
            assertTrue(app.isActive, "the app's scope goes on")
        }
    }
}
