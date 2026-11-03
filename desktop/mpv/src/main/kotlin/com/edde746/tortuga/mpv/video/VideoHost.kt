package com.edde746.tortuga.mpv.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.edde746.tortuga.mpv.core.MpvCore
import com.edde746.tortuga.playback.HardwareDecoder
import java.awt.Component
import java.awt.Container

/**
 * Where mpv's video goes on this OS. The engine drives it from its mpv thread; the UI side comes from
 * [Content] and [attach].
 *
 * Order: [attach] (UI, once the window shows) → `onReady` → [options] → mpv_initialize → [bind] → `onVideoReady` →
 * loadfile … [unbind] → [awaitUnbound] → mpv_terminate_destroy, which never runs while the host still uses the core.
 */
internal interface VideoHost {
    /**
     * Hooks the host into [window] (UI thread). Returns false while the window's canvas does not exist yet, so the
     * caller retries; after returning true it calls [onReady] (any thread) once [options] can be read. Idempotent.
     */
    fun attach(window: ComposeWindow, onReady: () -> Unit): Boolean

    /** mpv options the host needs before mpv_initialize (`vo`, `wid`, …). */
    fun options(): Map<String, String>

    /** The hardware decoder mpv can use on this host's video output; the engine applies each file's `hwdec`. */
    val hardwareDecoder: HardwareDecoder

    /**
     * Called on the mpv thread after mpv_initialize. Calls [onVideoReady] (any thread) with null once a file may be
     * loaded, or with the reason video cannot work.
     */
    fun bind(core: MpvCore, onVideoReady: (error: String?) -> Unit)

    /** Called on the mpv thread once its event loop has stopped: the host lets go of [core] ([awaitUnbound]). */
    fun unbind(core: MpvCore)

    /**
     * Blocks, off the mpv thread, until the host no longer touches the core [unbind] let go of. True once
     * mpv_terminate_destroy may run; false when it never may (Linux: the window went away with mpv's render context
     * still on the core, so both are left alive rather than freed out of order).
     */
    fun awaitUnbound(): Boolean

    /** Frees UI-side resources; the host is unusable afterwards. */
    fun close()

    /** The Compose element standing for the video: transparent on Windows, the video image on Linux. */
    @Composable
    fun Content(modifier: Modifier)

    companion object {
        fun forCurrentOs(): VideoHost {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Windows") -> Win32VideoHost()
                os.startsWith("Linux") -> GlVideoHost()
                else -> error("No desktop video host for $os (macOS uses the native Swift player)")
            }
        }
    }
}

private fun Class<*>.isNamed(name: String) = generateSequence(this) { it.superclass }.any { it.name == name }

/** First component in [root]'s tree whose class (or a superclass) is [className]. */
internal fun findComponent(root: Component, className: String): Component? {
    if (root.javaClass.isNamed(className)) return root
    if (root is Container) for (child in root.components) findComponent(child, className)?.let { return it }
    return null
}
