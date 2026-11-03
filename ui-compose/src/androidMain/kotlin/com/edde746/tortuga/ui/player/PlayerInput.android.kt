package com.edde746.tortuga.ui.player

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerIcon

// Android reports no click count; the player store pairs taps within the double-tap timeout itself.
internal actual fun PointerEvent.platformClickCount(): Int = 1

// Touch and remotes have no cursor to hide; a mouse keeps the system pointer.
internal actual val HiddenPointerIcon: PointerIcon = PointerIcon.Default
