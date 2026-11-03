package com.edde746.tortuga.input

/**
 * Phones and tablets. A tap shows or hides the controls (or closes an open menu); a double tap on the left or right
 * third seeks back or forward, in the middle it toggles pause. Taps wait for the double-tap interval, so a double tap
 * doesn't also flash the controls. A swipe up or down the left half of the video changes the screen's brightness, the
 * right half the system's volume, as the screen is seen in every language, unless it starts in an edge the system
 * swipes from or this device turned swipes off. A finger held on the video plays at 2× until it lifts, but live. A
 * hardware keyboard gets the desktop keys, except fullscreen: the player always is.
 */
internal class TouchKeymap(private val steps: InputSteps) : Keymap {
    private val keyboard = DesktopKeymap(steps)

    override fun key(key: InputKey, modifiers: KeyModifiers, context: InputContext): PlayerCommand? =
        when (val command = keyboard.key(key, modifiers, context.copy(fullscreen = false))) {
            PlayerCommand.ToggleFullscreen -> null
            else -> command
        }

    override fun click(x: Float, context: InputContext): PlayerCommand? = when {
        context.menuOpen -> PlayerCommand.CloseMenu
        context.errorShown -> null
        else -> PlayerCommand.ToggleControls
    }

    override fun doubleClick(x: Float, context: InputContext): PlayerCommand? = when {
        context.menuOpen || context.errorShown -> null
        x < 1f / 3 -> PlayerCommand.SeekBy(-steps.seekSeconds)
        x > 2f / 3 -> PlayerCommand.SeekBy(steps.seekSeconds)
        else -> PlayerCommand.TogglePause
    }

    override fun wheel(steps: Double, context: InputContext): PlayerCommand? = null

    override fun swipe(swipe: InputEvent.Swipe, context: InputContext): PlayerCommand? = when {
        !context.swipes || context.menuOpen || context.errorShown -> null
        swipe.edges.contain(swipe.x, swipe.y) -> null
        swipe.x < 0.5f -> PlayerCommand.AdjustBrightness(swipe.distance)
        else -> PlayerCommand.AdjustSystemVolume(swipe.distance)
    }

    override fun hold(held: Boolean, context: InputContext): PlayerCommand? = when {
        !held -> PlayerCommand.ReleaseSpeed
        context.live || context.menuOpen || context.errorShown -> null
        else -> PlayerCommand.HoldSpeed
    }
}
