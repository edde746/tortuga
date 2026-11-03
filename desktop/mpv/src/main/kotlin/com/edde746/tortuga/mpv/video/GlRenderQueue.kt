package com.edde746.tortuga.mpv.video

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities

/**
 * GL work that must run on Skiko's render thread with its context current, i.e. inside a Compose draw of the window
 * ([GlVideoHost]): freeing mpv's render context once the player has left the screen, when the video itself
 * is no longer drawn. The window's root draws it ([com.edde746.tortuga.mpv.mpvRenderHook]).
 */
internal object GlRenderQueue {
    private val tick = mutableLongStateOf(0L)
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()

    /** Runs [task] in the window's next draw. Any thread. */
    fun post(task: () -> Unit) {
        tasks += task
        SwingUtilities.invokeLater { tick.longValue++ }
    }

    /** In the root's draw: every posted task. Reading [tick] makes each post invalidate this draw. */
    fun DrawScope.drain() {
        tick.longValue
        while (true) (tasks.poll() ?: return)()
    }
}
