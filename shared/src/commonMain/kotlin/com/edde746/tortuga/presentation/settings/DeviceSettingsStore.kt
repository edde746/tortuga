package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * This device's settings screen: hardware decoding, HDR, frame-rate matching, the Mac's video output, audio
 * passthrough, the player's swipes (phones and tablets), the player's cache, Discord's Rich Presence and crash reports.
 * Values are never synced; every change is written at once and the screen follows the stored values. Rows this device
 * can't use ([features]) are left out. Passthrough shows its default for a [formFactor] device until it is switched,
 * and while loudness normalization is on (the player's choice, which needs decoded audio) its row says it is off in
 * place of its hint; the switch still keeps the choice for later.
 *
 * Call everything on the main thread; [release] ends it.
 */
class DeviceSettingsStore internal constructor(
    private val settings: DeviceSettingsRepository,
    private val features: DeviceFeatures,
    private val formFactor: FormFactor,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var stored: DeviceSettings? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<DeviceSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            settings.observe().collect {
                stored = it
                publish()
            }
        }
    }

    /**
     * Sets the choice row [key] ([SettingChoiceRow.id]) to its option [optionId]: the read-ahead cache to Automatic or one
     * of the preset sizes. An option the row doesn't offer changes nothing.
     */
    fun choose(key: String, optionId: String) {
        when (key) {
            "cacheSize" -> when (optionId) {
                AUTOMATIC_ID -> storeCacheSize(null)
                else -> optionId.toIntOrNull()?.takeIf { it in CACHE_PRESETS }?.let { storeCacheSize(it) }
            }
            else -> error("Device settings have no choice row $key")
        }
    }

    /** Switches the row [key] ([SettingToggleRow.id]) on or off. */
    fun toggle(key: String, checked: Boolean) {
        when (key) {
            "hardwareDecoding" -> write(DeviceSetting.HardwareDecoding, checked)
            "hdr" -> write(DeviceSetting.Hdr, checked)
            "matchFrameRate" -> write(DeviceSetting.MatchFrameRate, checked)
            "audioPassthrough" -> write(DeviceSetting.AudioPassthrough, checked)
            "swipeGestures" -> write(DeviceSetting.SwipeGestures, checked)
            "discordPresence" -> write(DeviceSetting.DiscordPresence, checked)
            "crashReports" -> write(DeviceSetting.CrashReports, checked)
            else -> error("Device settings have no switch $key")
        }
    }

    fun release() {
        scope.cancel()
    }

    private fun <T> write(setting: DeviceSetting<T>, value: T) {
        scope.launch { settings.set(setting, value) }
    }

    private fun storeCacheSize(megabytes: Int?) {
        scope.launch {
            if (megabytes == null) {
                settings.reset(DeviceSetting.CacheSizeMegabytes)
            } else {
                settings.set(DeviceSetting.CacheSizeMegabytes, megabytes)
            }
        }
    }

    private fun current(): DeviceSettings = stored ?: DeviceSettings.DEFAULT

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): DeviceSettingsUiState {
        val values = current()
        val text = T.settings.device
        val handheld = formFactor == FormFactor.HANDHELD
        fun toggle(available: Boolean, id: String, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true) =
            if (available) SettingToggleRow(id, title, subtitle, checked, enabled) else null
        return DeviceSettingsUiState(
            loading = stored == null,
            title = text.title,
            note = text.note,
            videoHeader = if (features.hardwareDecoding || features.hdr || features.matchFrameRate) text.videoHeader else null,
            hardwareDecoding = toggle(features.hardwareDecoding, "hardwareDecoding", text.hardwareDecoding, text.hardwareDecodingHint, values.hardwareDecoding),
            hdr = toggle(features.hdr, "hdr", text.hdr, text.hdrHint, values.hdr),
            matchFrameRate = toggle(features.matchFrameRate, "matchFrameRate", text.matchFrameRate, text.matchFrameRateHint, values.matchFrameRate),
            audioHeader = if (features.audioPassthrough) text.audioHeader else null,
            audioPassthrough = toggle(
                features.audioPassthrough,
                "audioPassthrough",
                text.audioPassthrough,
                if (values.audioNormalization) text.audioPassthroughNormalized else passthroughHint(features.passthroughTarget),
                values.audioPassthroughOn(formFactor),
                enabled = !values.audioNormalization,
            ),
            gesturesHeader = if (handheld) text.gesturesHeader else null,
            swipeGestures = toggle(handheld, "swipeGestures", text.swipeGestures, text.swipeGesturesHint, values.swipeGestures),
            streamingHeader = text.streamingHeader,
            cacheSize = cacheSizeRow(text.cacheSize, text.cacheSizeHint, values.cacheSizeMegabytes),
            discordHeader = if (features.discordPresence) text.discordHeader else null,
            discordPresence = toggle(features.discordPresence, "discordPresence", text.discordPresence, text.discordPresenceHint, values.discordPresence),
            privacyHeader = text.privacyHeader,
            crashReports = SettingToggleRow("crashReports", text.crashReports, text.crashReportsHint, values.crashReports),
        )
    }
}

/** The read-ahead cache sizes offered, in MiB, after Automatic. */
private val CACHE_PRESETS = listOf(64, 128, 256, 512, 1024, 2048, 4096)

private const val AUTOMATIC_ID = "auto"

/**
 * Automatic and the preset sizes, the stored one selected. A size no preset has (typed in an earlier version, or a
 * development session's `--dev-setting`) is listed in its place among them, so the stored value always shows selected.
 */
private fun cacheSizeRow(title: String, subtitle: String, megabytes: Int?): SettingChoiceRow {
    val sizes = (CACHE_PRESETS + listOfNotNull(megabytes)).distinct().sorted()
    val options = listOf(SettingChoiceOption(AUTOMATIC_ID, T.settings.automatic, megabytes == null)) +
        sizes.map { SettingChoiceOption(it.toString(), formatByteSize(it.toLong() * 1024 * 1024), it == megabytes) }
    return SettingChoiceRow("cacheSize", title, subtitle, options)
}

/** What passthrough does with [target]. */
private fun passthroughHint(target: PassthroughTarget): String = with(T.settings.device) {
    when (target) {
        PassthroughTarget.RECEIVER -> audioPassthroughHint
        PassthroughTarget.SYSTEM_DECODER -> audioPassthroughHintApple
        PassthroughTarget.SYSTEM_SPATIAL -> audioPassthroughHintAppleSpatial
    }
}
