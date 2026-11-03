package com.edde746.tortuga.input

/**
 * D-pad remotes (Android TV, Fire TV, tvOS). With the controls down the D-pad drives playback: Select pauses (which
 * brings the controls up), up/down bring the controls up, left/right scrub. With the controls up, the D-pad belongs to
 * the platform's focus navigation, except left/right on a focused seek bar, which scrub.
 *
 * While scrubbing, Select commits the preview and Back drops it. The sleep timer's question and the error screen hold
 * focusable buttons of their own, so the D-pad is left to them. The prompt button (the skip button, up next) takes
 * focus as it shows: with the controls down, Select presses it, Down brings the controls up (focus moves to them) and
 * left and right scrub; Back dismisses it (the store's Back order).
 */
internal class TvKeymap(private val steps: InputSteps) : Keymap {
    override fun key(key: InputKey, modifiers: KeyModifiers, context: InputContext): PlayerCommand? {
        commonKey(key, context, steps)?.let { return it }
        if (context.scrubbing) {
            when (key) {
                InputKey.LEFT, InputKey.RIGHT -> return PlayerCommand.DpadScrub(forward = key == InputKey.RIGHT)
                InputKey.SELECT -> return PlayerCommand.CommitScrub
                InputKey.ESCAPE -> return PlayerCommand.CancelScrub
                else -> {}
            }
        }
        return when (key) {
            InputKey.ESCAPE -> PlayerCommand.Back
            InputKey.INFO -> PlayerCommand.ToggleInfo
            InputKey.MENU -> PlayerCommand.OpenMenu(PlayerMenu.SUBTITLES)
            else -> when {
                context.stillWatchingShown || context.errorShown -> null
                context.prompt != null && !context.controlsVisible -> promptKey(key, context.prompt, context)
                else -> dpad(key, context)
            }
        }
    }

    private fun promptKey(key: InputKey, prompt: PlayerCommand, context: InputContext): PlayerCommand? = when (key) {
        InputKey.SELECT -> prompt
        InputKey.DOWN -> PlayerCommand.ShowControls
        InputKey.UP -> null
        else -> dpad(key, context)
    }

    private fun dpad(key: InputKey, context: InputContext): PlayerCommand? = when (key) {
        InputKey.LEFT, InputKey.RIGHT ->
            if (!context.controlsVisible || context.timelineFocused) PlayerCommand.DpadScrub(forward = key == InputKey.RIGHT)
            else null
        InputKey.UP, InputKey.DOWN -> if (context.controlsVisible) null else PlayerCommand.ShowControls
        InputKey.SELECT -> if (context.controlsVisible) null else PlayerCommand.TogglePause
        else -> null
    }

    override fun click(x: Float, context: InputContext): PlayerCommand? = null

    override fun doubleClick(x: Float, context: InputContext): PlayerCommand? = null

    override fun wheel(steps: Double, context: InputContext): PlayerCommand? = null
}
