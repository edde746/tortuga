package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.format.formatTimecode
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.Anime4kQuality
import com.edde746.tortuga.playback.ArtCnnVariant
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.AudioDevice
import com.edde746.tortuga.playback.ShaderPreset
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.VideoLayout
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.math.abs

/** What the options menu shows, from the store's plain values. */
internal object PlayerOptions {
    /** Everything [build] lists, as the store holds it. */
    data class Values(
        val fit: VideoFit,
        val zoom: Double,
        val sleepMinutes: Int?,
        val sleepAtEndOfVideo: Boolean,
        val sleepSecondsLeft: Int,
        /** Null without a next episode: auto-play next isn't offered then. */
        val autoPlayNext: Boolean?,
        val audioDevices: List<AudioDevice>,
        val audioDevice: String,
        val audioNormalization: Boolean,
        val audioChannels: AudioChannels,
        val locale: String,
        /** Watch together in this player; null where the app offers none. */
        val watch: PlayerWatchUi? = null,
        /** The shader preset running; null where the player offers no shaders. */
        val shader: ShaderPreset? = null,
        /** The presets this device's renderer runs ([ShaderPreset.available]). */
        val shaders: List<ShaderPreset> = emptyList(),
        /** The Shaders page offers to import one: file dialogs, and a renderer that runs imported shaders. */
        val importsShaders: Boolean = false,
    )

    fun build(values: Values): List<PlayerOption> = listOfNotNull(
        values.watch?.let(::watchTogether),
        videoFit(values.fit),
        zoom(values.zoom),
        values.shader?.let { shaders(it, values.shaders, values.importsShaders) },
        sleepTimer(values),
        values.autoPlayNext?.let { switch(PlayerOptionId.AUTO_PLAY_NEXT, T.player.options.autoPlayNext, it, adjusted = !it, PlayerCommand.SetAutoPlayNext(!it)) },
        audioOutput(values.audioDevices, values.audioDevice),
        switch(
            PlayerOptionId.AUDIO_NORMALIZATION,
            T.player.options.audioNormalization,
            values.audioNormalization,
            adjusted = values.audioNormalization,
            PlayerCommand.SetAudioNormalization(!values.audioNormalization),
        ),
        audioChannels(values.audioChannels),
    )

    fun fitLabel(fit: VideoFit): String = when (fit) {
        VideoFit.CONTAIN -> T.player.options.fit.contain
        VideoFit.COVER -> T.player.options.fit.cover
        VideoFit.FILL -> T.player.options.fit.fill
    }

    /** The room panel is a page of its own ([PlayerMenu.WATCH_TOGETHER]): the views lay out its [PlayerWatchUi]. */
    private fun watchTogether(watch: PlayerWatchUi) = page(
        PlayerOptionId.WATCH_TOGETHER,
        watch.title,
        watch.room?.let { T.watch.player.optionRoom(it.code) } ?: T.player.options.off,
        adjusted = watch.room != null,
        PlayerMenu.WATCH_TOGETHER,
        emptyList(),
    )

    /** "120%". */
    fun zoomLabel(zoom: Double): String = T.player.options.percent(PlayerText.decimal(VideoLayout.percent(zoom).toDouble(), 0, 0))

    private fun videoFit(fit: VideoFit) = page(
        PlayerOptionId.VIDEO_FIT,
        T.player.options.videoFit,
        fitLabel(fit),
        adjusted = fit != VideoFit.CONTAIN,
        PlayerMenu.VIDEO_FIT,
        VideoFit.entries.map { OptionChoice(fitLabel(it), detail = null, selected = it == fit, PlayerCommand.SetVideoFit(it)) },
    )

    private fun zoom(zoom: Double) = page(
        PlayerOptionId.ZOOM,
        T.player.options.zoom,
        zoomLabel(zoom),
        adjusted = zoom != 1.0,
        PlayerMenu.ZOOM,
        listOf(OptionChoice(T.player.options.resetZoom, detail = null, selected = false, PlayerCommand.ResetZoom)) +
            VideoLayout.ZOOM_PRESETS.map { OptionChoice(zoomLabel(it), detail = null, selected = abs(it - zoom) < ZOOM_EPSILON, PlayerCommand.SetZoom(it)) },
    )

    /** The [available] presets, the imported ones deletable, and the import entry where [imports]. */
    private fun shaders(current: ShaderPreset, available: List<ShaderPreset>, imports: Boolean) = page(
        PlayerOptionId.SHADERS,
        T.player.options.shaders,
        shaderLabel(current),
        adjusted = current != ShaderPreset.Off,
        PlayerMenu.SHADERS,
        available.map { preset ->
            OptionChoice(
                shaderLabel(preset),
                shaderDetail(preset),
                selected = preset == current,
                PlayerCommand.SetShaderPreset(preset.id),
                delete = (preset as? ShaderPreset.Custom)?.let { PlayerCommand.DeleteShader(it.id) },
            )
        },
    ).copy(importLabel = if (imports) T.player.options.shader.importShader else null)

    /**
     * "NVScaler", "ArtCNN C4F16 Denoise", "Anime4K Fast A", an imported shader's name: the shaders' own names stay as
     * they are, their variants' and qualities' words are translated.
     */
    fun shaderLabel(preset: ShaderPreset): String = when (preset) {
        ShaderPreset.Off -> T.player.options.off
        ShaderPreset.NvScaler -> "NVScaler"
        is ShaderPreset.ArtCnn ->
            if (preset.variant == ArtCnnVariant.NEUTRAL) "ArtCNN ${preset.model.label}" else "ArtCNN ${preset.model.label} ${variantLabel(preset.variant)}"
        is ShaderPreset.Anime4k -> "Anime4K ${qualityLabel(preset.quality)} ${preset.mode.label}"
        is ShaderPreset.Custom -> preset.shader.name
    }

    private fun shaderDetail(preset: ShaderPreset): String? = with(T.player.options.shader) {
        when (preset) {
            ShaderPreset.Off -> null
            ShaderPreset.NvScaler -> nvscalerDetail
            is ShaderPreset.ArtCnn -> "${preset.model.label}$SEPARATOR${variantLabel(preset.variant)}"
            is ShaderPreset.Anime4k -> modeDetail(qualityLabel(preset.quality), preset.mode.label)
            is ShaderPreset.Custom -> customDetail
        }
    }

    private fun variantLabel(variant: ArtCnnVariant) = with(T.player.options.shader) {
        when (variant) {
            ArtCnnVariant.NEUTRAL -> neutral
            ArtCnnVariant.DENOISE -> denoise
            ArtCnnVariant.DENOISE_SHARPEN -> denoiseSharpen
        }
    }

    private fun qualityLabel(quality: Anime4kQuality) = when (quality) {
        Anime4kQuality.FAST -> T.player.options.shader.fast
        Anime4kQuality.HQ -> T.player.options.shader.highQuality
    }

    private fun sleepTimer(values: Values): PlayerOption {
        val minutes = values.sleepMinutes
        val value = when {
            minutes != null -> T.player.options.sleepLeft(formatTimecode(values.sleepSecondsLeft.coerceAtLeast(0).toDouble()))
            values.sleepAtEndOfVideo -> T.player.options.sleepEndOfVideo
            else -> T.player.options.off
        }
        return page(
            PlayerOptionId.SLEEP_TIMER,
            T.player.options.sleepTimer,
            value,
            adjusted = minutes != null || values.sleepAtEndOfVideo,
            PlayerMenu.SLEEP_TIMER,
            listOf(
                OptionChoice(T.player.options.off, detail = null, selected = minutes == null && !values.sleepAtEndOfVideo, PlayerCommand.CancelSleepTimer),
                OptionChoice(T.player.options.sleepEndOfVideo, detail = null, selected = values.sleepAtEndOfVideo, PlayerCommand.SleepAtEndOfVideo),
            ) + SleepTimer.MINUTES.map {
                OptionChoice(T.player.options.sleepMinutes(it), detail = null, selected = it == minutes, PlayerCommand.SetSleepTimer(it))
            },
        )
    }

    /**
     * Only where the platform offers more than one output to pick from. A device mpv lists through several audio
     * drivers (the Mac's Core Audio and AVFoundation both list the speakers) is told apart by its driver: the choice's
     * detail and the option's value ("MacBook Air Speakers · AVFoundation", the device's name isolated).
     */
    private fun audioOutput(devices: List<AudioDevice>, current: String): PlayerOption? {
        if (devices.size < 2) return null
        fun label(device: AudioDevice) = if (device.name == AUTO_DEVICE) T.player.options.systemOutput else device.description.ifBlank { device.name }
        val shared = devices.groupingBy(::label).eachCount().filterValues { it > 1 }.keys
        fun driver(device: AudioDevice) = if (label(device) in shared) audioDriver(device.name) else null
        fun value(device: AudioDevice) = driver(device)?.let { isolate(label(device)) + SEPARATOR + it } ?: label(device)
        return page(
            PlayerOptionId.AUDIO_OUTPUT,
            T.player.options.audioOutput,
            devices.firstOrNull { it.name == current }?.let(::value) ?: current,
            adjusted = current != AUTO_DEVICE,
            PlayerMenu.AUDIO_OUTPUT,
            devices.map { OptionChoice(label(it), detail = driver(it), selected = it.name == current, PlayerCommand.SetAudioDevice(it.name)) },
        )
    }

    /** The audio driver of mpv's device [name] (`coreaudio/BuiltInSpeakerDevice`), by its own name. */
    private fun audioDriver(name: String): String? =
        name.substringBefore('/', missingDelimiterValue = "").ifBlank { null }?.let(CodecNames::audioDriver)

    private fun audioChannels(channels: AudioChannels): PlayerOption {
        val text = T.player.options.channels
        fun label(limit: AudioChannels) = when (limit) {
            AudioChannels.ORIGINAL -> text.original
            AudioChannels.SURROUND_51 -> text.surround51
            AudioChannels.STEREO -> text.stereo
        }
        fun detail(limit: AudioChannels) = when (limit) {
            AudioChannels.ORIGINAL -> text.originalDetail
            AudioChannels.SURROUND_51 -> text.surround51Detail
            AudioChannels.STEREO -> text.stereoDetail
        }
        return page(
            PlayerOptionId.AUDIO_CHANNELS,
            T.player.options.audioChannels,
            label(channels),
            adjusted = channels != AudioChannels.ORIGINAL,
            PlayerMenu.AUDIO_CHANNELS,
            AudioChannels.entries.map { OptionChoice(label(it), detail(it), selected = it == channels, PlayerCommand.SetAudioChannels(it)) },
        )
    }

    private fun page(id: PlayerOptionId, label: String, value: String, adjusted: Boolean, page: PlayerMenu, choices: List<OptionChoice>) =
        PlayerOption(id, label, value, adjusted, page, choices, on = null, toggle = null)

    private fun switch(id: PlayerOptionId, label: String, on: Boolean, adjusted: Boolean, toggle: PlayerCommand) = PlayerOption(
        id,
        label,
        if (on) T.player.options.on else T.player.options.off,
        adjusted,
        page = null,
        choices = emptyList(),
        on = on,
        toggle = toggle,
    )

    /** mpv's `audio-device` for the system's choice. */
    const val AUTO_DEVICE = "auto"

    private const val SEPARATOR = " · "

    private const val ZOOM_EPSILON = 0.005
}
