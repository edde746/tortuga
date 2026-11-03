package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputContext
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.Keymap
import com.edde746.tortuga.input.PlayerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Tells single clicks from double clicks. Where the keymap gives a double click a meaning, a click waits [interval]
 * before it acts; a second click within it (or one the platform counts as the second) runs the double-click command
 * instead, and the first click never acts. So a desktop double click toggles fullscreen without pausing twice, and a
 * double tap on a phone seeks without flashing the controls.
 */
internal class ClickArbiter(
    private val scope: CoroutineScope,
    private val interval: Duration,
    private val run: (PlayerCommand) -> Unit,
) {
    private var pending: PlayerCommand? = null
    private var pendingJob: Job? = null

    fun onClick(event: InputEvent.Click, keymap: Keymap, context: InputContext): Boolean {
        // A triple click's third click: the double click already acted.
        if (event.clickCount > 2) return true
        if (event.clickCount == 2 || pendingJob != null) {
            val double = keymap.doubleClick(event.x, context)
            if (double != null) {
                pendingJob?.cancel()
                pendingJob = null
                pending = null
                run(double)
                return true
            }
            // No double click here: the first click acts now, this one on its own.
            firePending()
        }
        val single = keymap.click(event.x, context) ?: return false
        if (keymap.doubleClick(event.x, context) == null) {
            run(single)
            return true
        }
        pending = single
        pendingJob = scope.launch {
            delay(interval)
            pendingJob = null
            firePending()
        }
        return true
    }

    private fun firePending() {
        pendingJob?.cancel()
        pendingJob = null
        val command = pending ?: return
        pending = null
        run(command)
    }
}
