package com.edde746.tortuga.mpv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawBehind
import com.edde746.tortuga.mpv.video.GlRenderQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** How long the window may take to realize the canvas the video goes into before the playback fails. */
private val ATTACH_TIMEOUT = 5.seconds

/**
 * The video of [engine], filling its bounds. Place it under the player controls (e.g. as `PlayerScreen`'s `video`
 * slot) in [window], the one window the engine plays into:
 *
 * - Windows: transparent; mpv renders into a native child window under the whole Compose layer, so the
 *   video always fills the window's canvas.
 * - Linux: the current frame as a Skia image.
 *
 * The engine starts mpv once this has been composed and the window is showing. A window whose canvas doesn't appear
 * within 5 s (Skiko's components no longer what the host looks for) fails the playback with that error.
 */
@Composable
fun MpvVideoSurface(engine: MpvPlayerEngine, window: ComposeWindow, modifier: Modifier = Modifier) {
    LaunchedEffect(engine, window) {
        // The canvas (and its native handle) appears only once AWT has realized the window.
        withTimeoutOrNull(ATTACH_TIMEOUT) { while (!engine.attach(window)) delay(16) } ?: engine.attachTimedOut(ATTACH_TIMEOUT)
    }
    engine.host.Content(modifier.fillMaxSize())
}

/**
 * Put on the window's root content (Linux): mpv's GL render context is freed inside a draw of the window,
 * and after the player has left the screen only the root still draws. Nothing to do on Windows.
 */
fun Modifier.mpvRenderHook(): Modifier = drawBehind { with(GlRenderQueue) { drain() } }
