package com.edde746.tortuga.presentation.settings

/**
 * This device's settings: never synced, as [note] says. A row is null where this device can't
 * use the setting ([DeviceFeatures]), and a header is null when its group has no row left. The switches go to
 * [DeviceSettingsStore.toggle], the choices to [DeviceSettingsStore.choose], by their row's id.
 */
data class DeviceSettingsUiState(
    val loading: Boolean,
    val title: String,
    val note: String,
    val videoHeader: String?,
    val hardwareDecoding: SettingToggleRow?,
    val hdr: SettingToggleRow?,
    val matchFrameRate: SettingToggleRow?,
    val audioHeader: String?,
    /**
     * While loudness normalization is on (the player's choice) not enabled, its subtitle the note that passthrough is
     * off meanwhile, since the filter needs decoded audio; else its hint, worded for [DeviceFeatures.passthroughTarget].
     */
    val audioPassthrough: SettingToggleRow?,
    val gesturesHeader: String? = null,
    /** Swipes up and down the video change the brightness and the volume: phones and tablets, on by default. */
    val swipeGestures: SettingToggleRow? = null,
    val streamingHeader: String,
    /** The read-ahead cache: Automatic or a preset size. */
    val cacheSize: SettingChoiceRow,
    val discordHeader: String? = null,
    /** Discord's Rich Presence: the desktop and the Mac ([DeviceFeatures.discordPresence]). */
    val discordPresence: SettingToggleRow? = null,
    val privacyHeader: String,
    /** Send crash reports: on every device, on by default. */
    val crashReports: SettingToggleRow,
)

/**
 * Which device settings apply here, as the platform detects them (capabilities are reported to shared
 * code): HDR output, refresh-rate switching and passthrough depend on the display, the OS and the audio route, and
 * Discord's Rich Presence is offered where the app speaks to Discord (`AppPlatform.discordPresence`: the desktop and
 * the Mac).
 *
 * @param matchFrameRate the app can force the display's mode ([com.edde746.tortuga.data.settings.DeviceSetting.MatchFrameRate]):
 *   an Android TV, a Windows monitor with more than one refresh rate at its resolution. Where only the system switches
 *   (tvOS, phones, tablets) there is nothing to force, and no row.
 * @param passthroughTarget what passthrough hands the encoded audio to here, which its hint says.
 */
data class DeviceFeatures(
    val hardwareDecoding: Boolean,
    val hdr: Boolean,
    val matchFrameRate: Boolean,
    val audioPassthrough: Boolean,
    val discordPresence: Boolean = false,
    val passthroughTarget: PassthroughTarget = PassthroughTarget.RECEIVER,
)

/** What audio passthrough sends Dolby and DTS to, undecoded ([DeviceFeatures.passthroughTarget]). */
enum class PassthroughTarget {
    /** A receiver or TV over HDMI or optical, which must decode it (Android, the desktop). */
    RECEIVER,

    /** The system's Dolby decoder (Apple); DTS and TrueHD play as multichannel PCM. */
    SYSTEM_DECODER,

    /** The system's Dolby decoder, and TrueHD Atmos and DTS:X as spatial audio (iOS and tvOS 26 and later). */
    SYSTEM_SPATIAL,
}
