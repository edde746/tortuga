package com.edde746.tortuga.presentation.player

/**
 * The screen's brightness and the system's media volume, which swipes up and down the video change on a touch screen
 * (`PlayerCommand.AdjustBrightness`, `AdjustSystemVolume`). Levels are 0 to 1. Called on the main thread.
 */
interface DeviceLevels {
    /** Changes the brightness by [delta] and returns the level it is at now. */
    fun adjustBrightness(delta: Double): Double

    /** Changes the system's media volume by [delta], without the system's own volume panel, and returns the level. */
    fun adjustVolume(delta: Double): Double

    /** The player closed: the brightness it set is gone, and the screen's own is back. */
    fun restoreBrightness()
}
