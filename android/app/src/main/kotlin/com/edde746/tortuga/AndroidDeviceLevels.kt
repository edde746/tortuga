package com.edde746.tortuga

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import com.edde746.tortuga.presentation.player.DeviceLevels
import kotlin.math.roundToInt

/**
 * The player's swipes on a phone or tablet. The brightness is the activity [window]'s own (`screenBrightness`), which
 * the system applies only while the window is in front and which [restoreBrightness] drops as the player closes,
 * leaving the system's. The volume is the system's media volume, set without the system's volume panel: the player
 * shows its own level. It moves in the system's steps (15 to 25 of them), so a swipe's small moves add up here, and a
 * step taken elsewhere meanwhile (the volume keys) starts it over from the system's.
 */
internal class AndroidDeviceLevels(context: Context, private val window: () -> Window?) : DeviceLevels {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val resolver = context.contentResolver

    /** The volume the swipes left, finer than the system's steps, and the step it set. */
    private var volume = 0.0
    private var volumeStep = -1

    override fun adjustBrightness(delta: Double): Double {
        val window = window() ?: return systemBrightness()
        val attributes = window.attributes
        val current = attributes.screenBrightness.takeIf { it >= 0f }?.toDouble() ?: systemBrightness()
        val level = (current + delta).coerceIn(MIN_BRIGHTNESS, 1.0)
        attributes.screenBrightness = level.toFloat()
        window.attributes = attributes
        return level
    }

    override fun adjustVolume(delta: Double): Double {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (step != volumeStep) volume = step.toDouble() / max
        volume = (volume + delta).coerceIn(0.0, 1.0)
        volumeStep = (volume * max).roundToInt()
        if (volumeStep != step) audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeStep, 0)
        return volume
    }

    override fun restoreBrightness() {
        val window = window() ?: return
        val attributes = window.attributes
        if (attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    /** The system's brightness setting (0 to 255), where a swipe starts before the window has one of its own. */
    private fun systemBrightness(): Double =
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MAX_SYSTEM_BRIGHTNESS / 2).toDouble() / MAX_SYSTEM_BRIGHTNESS

    private companion object {
        const val MAX_SYSTEM_BRIGHTNESS = 255

        /** The dimmest a swipe goes: 0 would ask for the backlight's lowest, darker than the system's own slider goes. */
        const val MIN_BRIGHTNESS = 0.01
    }
}
