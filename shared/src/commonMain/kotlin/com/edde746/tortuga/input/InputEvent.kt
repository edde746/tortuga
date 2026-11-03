package com.edde746.tortuga.input

/** Modifier keys held with a key press. */
data class KeyModifiers(
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    /** Command on Apple keyboards, the Windows/Super key elsewhere. */
    val meta: Boolean = false,
) {
    /** Ctrl, or Command on Apple keyboards: the modifier shortcuts use. */
    val command: Boolean get() = ctrl || meta

    companion object {
        val NONE = KeyModifiers()
    }
}

/**
 * What a platform forwards to the player store from the player surface (the platform only translates).
 * Pointer events are the ones that land on the video itself; the views' own buttons, sliders and menus handle their
 * clicks and send commands instead.
 */
sealed interface InputEvent {
    /**
     * A key went down. [repeat] marks the platform's auto-repeat while the key is held (Android `repeatCount > 0`,
     * Compose/AWT repeated key-downs); platforms without auto-repeat (tvOS presses) send one [KeyDown] and a [KeyUp].
     */
    data class KeyDown(
        val key: InputKey,
        val modifiers: KeyModifiers = KeyModifiers.NONE,
        val repeat: Boolean = false,
    ) : InputEvent

    /** A key came up: a held D-pad key's scrub (TV) stops moving, a held seek key seeks to where its repeats took it. */
    data class KeyUp(val key: InputKey) : InputEvent

    /**
     * A mouse click or a touch tap on the video.
     * @param x where, across the player: 0 at the left edge, 1 at the right (touch uses it for double-tap seeking).
     * @param clickCount the platform's click count (2 for the second click of a double click). Platforms that don't
     *   count send 1 each time; the store then pairs clicks itself within [PlayerConfig][com.edde746.tortuga.presentation.player.PlayerConfig]'s
     *   double-click interval.
     */
    data class Click(val x: Float, val clickCount: Int = 1) : InputEvent

    /**
     * The mouse moved over the player (hover). [overControls] while it rests on the control bar or a menu: the
     * controls then stay up.
     */
    data class PointerMove(val overControls: Boolean) : InputEvent

    /** The mouse left the player window. */
    data object PointerExit : InputEvent

    /** Scroll wheel or trackpad scroll: [steps] in wheel notches (fractional for trackpads), positive scrolling up. */
    data class Wheel(val steps: Double) : InputEvent

    /**
     * Two fingers pinching the video on a touch screen: [scale] is their distance apart over the distance they started
     * at (1 when the pinch begins, 2 when they spread twice as far). Platforms send one per change while the pinch
     * lasts, then [PinchEnd]; the store zooms the picture by it.
     */
    data class Pinch(val scale: Double) : InputEvent

    /** The pinch's fingers lifted (or the system cancelled it). */
    data object PinchEnd : InputEvent

    /**
     * One finger dragging up or down the video on a touch screen, once the drag is clearly vertical: it went down at
     * ([x], [y]) (0 at the player's left and top edges, 1 at its right and bottom) and has moved [distance] since the
     * last one, in the player's heights, positive upward. Platforms send one per move while the finger stays down;
     * [edges] are the screen's edges where the system takes swipes, in the same units.
     */
    data class Swipe(val x: Float, val y: Float, val distance: Double, val edges: SystemEdges = SystemEdges.NONE) : InputEvent

    /**
     * One finger held on the video on a touch screen past the platform's long-press time without moving, or (false)
     * lifted after that, or the touch cancelled.
     */
    data class Hold(val held: Boolean) : InputEvent

    /**
     * The remote moved focus among the player's controls without a key the store sees (tvOS: the focus engine moves it
     * on a swipe or a press, outside the views that forward presses). It keeps the controls up, as using them does.
     */
    data object FocusMove : InputEvent
}

/**
 * How far in from each edge of the player the system takes a swipe as its own, in shares of the player's width
 * ([left], [right]) and height ([top], [bottom]): Android's back gesture at the sides, the notification shade at the
 * top and the home gesture at the bottom; iOS's Notification Center and Control Center at the top and the home
 * indicator at the bottom.
 */
data class SystemEdges(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** The point ([x], [y], as [InputEvent.Swipe]'s) lies in one of the edges. */
    fun contain(x: Float, y: Float): Boolean = x < left || x > 1 - right || y < top || y > 1 - bottom

    companion object {
        val NONE = SystemEdges(0f, 0f, 0f, 0f)
    }
}
