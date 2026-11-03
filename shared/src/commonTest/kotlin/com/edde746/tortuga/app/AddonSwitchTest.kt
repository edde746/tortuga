package com.edde746.tortuga.app

import com.edde746.tortuga.model.transportUrl
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An installed addon's switch, across the app graph's real screens, player and library actions. */
class AddonSwitchTest {
    @Test
    fun anAddonTurnedOffIsAskedForNothingAndTurnedBackOnItIsAskedEverywhereAgain() = runTest {
        val w = everythingWorld()
        val p = w.data.profiles.create("Ann", null).id
        val registry = w.graph.addonRegistry(p)
        registry.install(EVERYTHING_URL.transportUrl(), watchActivity = true)
        registry.setEnabled(EVERYTHING_URL, false)
        runCurrent()

        val off = askedOfEverything(w, p)
        assertEquals(ASKED_OF_EVERYTHING.keys.associateWith { emptySet<String>() }, off, "turned off, nothing asks it anything")

        registry.setEnabled(EVERYTHING_URL, true)
        runCurrent()
        assertEquals(ASKED_OF_EVERYTHING, askedOfEverything(w, p))
    }
}
