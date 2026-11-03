package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.CustomShader
import com.edde746.tortuga.playback.ShaderPreset
import com.edde746.tortuga.playback.VideoFit
import kotlinx.coroutines.flow.Flow

/**
 * The player's options that outlive a playback: the picture's fit, the shader
 * preset and the imported shaders, loudness normalization and the audio channels, kept as this device's settings
 * (`DeviceSetting.VideoFit`, `ShaderPreset`, `CustomShaders`, `AudioNormalization`, `AudioChannels`). The zoom and the
 * sleep timer stay with the player; the audio output stays with the playback. With them, whether swipes change the
 * brightness and the volume (`DeviceSetting.SwipeGestures`), which Settings sets.
 */
interface PlayerPreferences {
    /** The stored choices, now and whenever they change. */
    val choices: Flow<PlayerChoices>

    fun setVideoFit(fit: VideoFit)
    fun setShaderPreset(id: String)

    /** Keeps the imported [shader] and makes it the stored preset, in that order. */
    fun addCustomShader(shader: CustomShader)

    /** Forgets the imported [shader]; the stored preset goes [ShaderPreset.Off] if it was this one. */
    fun removeCustomShader(shader: CustomShader)

    fun setAudioNormalization(enabled: Boolean)
    fun setAudioChannels(channels: AudioChannels)
}

data class PlayerChoices(
    val videoFit: VideoFit = VideoFit.CONTAIN,
    /** [ShaderPreset.id]. */
    val shaderPreset: String = ShaderPreset.Off.id,
    val customShaders: List<CustomShader> = emptyList(),
    val audioNormalization: Boolean = false,
    val audioChannels: AudioChannels = AudioChannels.ORIGINAL,
    val swipeGestures: Boolean = true,
)
