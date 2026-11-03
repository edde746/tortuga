package com.edde746.tortuga.presentation.player

/**
 * What the player store asks of the platform around it. Implemented by the Compose screen or the SwiftUI view;
 * called on the main thread.
 */
interface PlayerHost {
    /** Leave the player (Back with nothing left to close, Stop, the close button). */
    fun close()

    /**
     * Enter or leave fullscreen. Desktop windows only; the store never asks elsewhere. A change the user makes
     * outside the player (the window's own button, the OS) comes back through `PlayerStore.onFullscreenChanged`.
     */
    fun setFullscreen(fullscreen: Boolean)

    /** The brightness and the system's volume the swipes change on a touch screen; null where nothing changes them. */
    val levels: DeviceLevels? get() = null
}
