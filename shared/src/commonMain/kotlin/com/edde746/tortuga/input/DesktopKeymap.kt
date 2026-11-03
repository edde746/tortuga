package com.edde746.tortuga.input

/**
 * Keyboard and mouse (Windows, Linux, macOS). mpv's defaults where there is one (`[`/`]` speed, Backspace resets it,
 * `Z`/`X` subtitle delay, Ctrl `+`/`-` audio delay, Alt `+`/`-` zoom and Alt Backspace resets it, `I` info, Page
 * Up/Down chapters), the usual player keys otherwise (Space/K pause, arrows seek and change the volume, `F`
 * fullscreen, digits jump to tenths of the video, `N`/`P` the next and previous episode), and `G` toggles the
 * shaders. Enter presses the prompt button (the skip button, up next) while it shows.
 *
 * A click toggles pause, or shows and hides the controls as the profile has it ([InputContext.videoClick]), and a
 * double click toggles fullscreen; the store holds the click back until it knows the click isn't the first half of a
 * double click, so a double click never pauses or flashes the controls.
 */
internal class DesktopKeymap(private val steps: InputSteps) : Keymap {
    override fun key(key: InputKey, modifiers: KeyModifiers, context: InputContext): PlayerCommand? {
        commonKey(key, context, steps)?.let { return it }
        if (modifiers.alt) {
            // The zoom keys (mpv's); other Alt combinations belong to the system and the window menu.
            if (modifiers.command || modifiers.shift) return null
            return when (key) {
                InputKey.PLUS -> PlayerCommand.ZoomIn
                InputKey.MINUS -> PlayerCommand.ZoomOut
                InputKey.BACKSPACE -> PlayerCommand.ResetZoom
                else -> null
            }
        }
        if (modifiers.command) {
            return when (key) {
                InputKey.PLUS -> PlayerCommand.AdjustAudioDelay(steps.delaySeconds)
                InputKey.MINUS -> PlayerCommand.AdjustAudioDelay(-steps.delaySeconds)
                else -> null
            }
        }
        key.digitValue?.let { return PlayerCommand.SeekToFraction(it / 10.0) }
        val seek = if (modifiers.shift) steps.longSeekSeconds else steps.seekSeconds
        return when (key) {
            InputKey.SPACE, InputKey.K -> PlayerCommand.TogglePause
            InputKey.LEFT -> PlayerCommand.SeekBy(-seek)
            InputKey.RIGHT -> PlayerCommand.SeekBy(seek)
            InputKey.UP -> PlayerCommand.AdjustVolume(steps.volumePercent)
            InputKey.DOWN -> PlayerCommand.AdjustVolume(-steps.volumePercent)
            InputKey.HOME -> PlayerCommand.SeekToFraction(0.0)
            InputKey.M -> PlayerCommand.ToggleMute
            InputKey.F, InputKey.F11 -> PlayerCommand.ToggleFullscreen
            InputKey.ESCAPE ->
                if (context.fullscreen && !context.menuOpen && !context.scrubbing) PlayerCommand.ExitFullscreen
                else PlayerCommand.Back
            InputKey.BRACKET_LEFT -> PlayerCommand.SpeedDown
            InputKey.BRACKET_RIGHT -> PlayerCommand.SpeedUp
            InputKey.BACKSPACE -> PlayerCommand.ResetSpeed
            InputKey.Z -> PlayerCommand.AdjustSubtitleDelay(-steps.delaySeconds)
            InputKey.X -> PlayerCommand.AdjustSubtitleDelay(steps.delaySeconds)
            InputKey.S -> PlayerCommand.CycleSubtitles
            InputKey.A -> PlayerCommand.CycleAudio
            InputKey.I -> PlayerCommand.ToggleInfo
            InputKey.G -> PlayerCommand.ToggleShaders
            InputKey.N -> if (context.nextEpisodeAvailable) PlayerCommand.NextEpisode else null
            InputKey.P -> if (context.previousEpisodeAvailable) PlayerCommand.PreviousEpisode else null
            // mpv's input.conf: Page Up is the next chapter.
            InputKey.PAGE_UP -> PlayerCommand.NextChapter
            InputKey.PAGE_DOWN -> PlayerCommand.PreviousChapter
            InputKey.SELECT -> context.prompt
            else -> null
        }
    }

    override fun click(x: Float, context: InputContext): PlayerCommand? = when {
        context.menuOpen -> PlayerCommand.CloseMenu
        context.errorShown -> null
        context.videoClick == VideoClickAction.CONTROLS -> PlayerCommand.ToggleControls
        else -> PlayerCommand.TogglePause
    }

    override fun doubleClick(x: Float, context: InputContext): PlayerCommand = PlayerCommand.ToggleFullscreen

    override fun wheel(steps: Double, context: InputContext): PlayerCommand? =
        if (steps == 0.0) null else PlayerCommand.AdjustVolume(steps * this.steps.volumePercent)
}
