package com.edde746.tortuga.data.settings

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.db.Device_setting
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.CustomShader
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppPrompt
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * A setting of this device only: never synced, [default] while unset. A stored value that doesn't decode is logged
 * and reads as the default, and stays stored until the setting changes. Each [key] is one setting's: the device's own
 * settings are declared here, a profile's under the prefix of its kind (`navigation.stacks.<profile>`).
 */
abstract class DeviceSetting<T>(val key: String, val default: T, internal val serializer: KSerializer<T>) {
    open fun normalize(value: T): T = value

    /** Decode video in hardware where the platform can. */
    data object HardwareDecoding : DeviceSetting<Boolean>("video.hardwareDecoding", true, Boolean.serializer())

    /** Output HDR video as HDR when the display supports it; off tone-maps to SDR. */
    data object Hdr : DeviceSetting<Boolean>("video.hdr", true, Boolean.serializer())

    /**
     * Force the display to the video's refresh rate, whatever the system's own setting, where the app can switch the
     * mode itself (an Android TV's preferred mode, a Windows monitor's mode while fullscreen). Off by default: the
     * video's rate still goes to the system, whose own setting decides ([com.edde746.tortuga.playback.output.OutputPlan]).
     */
    data object MatchFrameRate : DeviceSetting<Boolean>("video.matchFrameRate", false, Boolean.serializer())

    /**
     * Send compressed surround audio (AC-3, E-AC-3, DTS, TrueHD) to the receiver undecoded, or on Apple platforms to the
     * system's own decoder; `null` for the form factor's default ([DeviceSettings.audioPassthroughOn]).
     */
    data object AudioPassthrough : DeviceSetting<Boolean?>("audio.passthrough", null, Boolean.serializer().nullable)

    /** The player's read-ahead cache in MiB, or `null` for Automatic (mpv's own size; `PlaybackOptions`). */
    data object CacheSizeMegabytes : DeviceSetting<Int?>("playback.cacheSizeMegabytes", null, Int.serializer().nullable) {
        val range = SettingRange(16, 4096)
        override fun normalize(value: Int?) = value?.let(range::clamp)
    }

    /** How the picture fills the player, as last chosen in the player's options. */
    data object VideoFit : DeviceSetting<com.edde746.tortuga.playback.VideoFit>(
        "video.fit",
        com.edde746.tortuga.playback.VideoFit.CONTAIN,
        com.edde746.tortuga.playback.VideoFit.serializer(),
    )

    /** Even out the audio's loudness, as last chosen in the player's options. */
    data object AudioNormalization : DeviceSetting<Boolean>("audio.normalization", false, Boolean.serializer())

    /** The most channels the audio plays with, as last chosen in the player's options. */
    data object AudioChannels : DeviceSetting<com.edde746.tortuga.playback.AudioChannels>(
        "audio.channels",
        com.edde746.tortuga.playback.AudioChannels.ORIGINAL,
        com.edde746.tortuga.playback.AudioChannels.serializer(),
    )

    /**
     * The video shader preset ([com.edde746.tortuga.playback.ShaderPreset.id]), as last chosen in the player's options.
     * A device's, as its GPU decides what runs.
     */
    data object ShaderPreset : DeviceSetting<String>("video.shaderPreset", com.edde746.tortuga.playback.ShaderPreset.Off.id, String.serializer())

    /** The shaders imported in the player's options, in the order imported; their files are this device's (`ShaderFiles`). */
    data object CustomShaders : DeviceSetting<List<CustomShader>>("video.customShaders", emptyList(), ListSerializer(CustomShader.serializer()))

    /** Swipes up and down the video change the screen's brightness and the system's volume: phones and tablets. */
    data object SwipeGestures : DeviceSetting<Boolean>("player.swipeGestures", true, Boolean.serializer())

    /** The profile this device opens with, or `null` to ask. */
    data object ActiveProfileId : DeviceSetting<String?>("app.activeProfileId", null, String.serializer().nullable)

    /**
     * The PIN entries' wrong-PIN counts and lockouts on this device, by profile id (or `PinThrottle.PARENT` for a
     * parent's PIN), so relaunching the app doesn't reset them (`PinThrottle`).
     */
    internal data object PinLockouts : DeviceSetting<Map<String, PinLockout>>(
        "profiles.pinLockouts",
        emptyMap(),
        MapSerializer(String.serializer(), PinLockout.serializer()),
    )

    /**
     * The watch-together relay this device uses (a self-hosted Open Watch Party relay's base URL), or `null` for the
     * public relays (`WatchRelayEndpoint.HOSTED`). Stored canonical; a value that isn't a relay URL is dropped.
     */
    data object WatchRelay : DeviceSetting<String?>("watch.relay", null, String.serializer().nullable) {
        override fun normalize(value: String?) = value?.let { WatchRelayEndpoint.parse(it)?.baseUrl }
    }

    /**
     * Discord's Rich Presence shows the user's Discord friends what plays (`DiscordPresence`): the desktop and the
     * Mac. Off until the user turns it on, as it tells others what they watch.
     */
    data object DiscordPresence : DeviceSetting<Boolean>("discord.presence", false, Boolean.serializer())

    /** Send crash reports where the build or session has an endpoint (`CrashReports`). */
    data object CrashReports : DeviceSetting<Boolean>("privacy.crashReports", true, Boolean.serializer())

    // ---- Player apps (playback/players) ------------------------------------------------------------------------------

    /**
     * The player every play on this device opens in: a player app's id (`PlayerApp.id`), or `null` for Tortuga's own
     * (`PlayerApp.TORTUGA_ID` is stored as `null`).
     */
    data object Player : DeviceSetting<String?>("playback.player", null, String.serializer().nullable) {
        override fun normalize(value: String?) = value?.takeIf { it.isNotBlank() && it != PlayerApp.TORTUGA_ID }
    }

    /** The players the user added in Settings → Player, in the order added. */
    data object CustomPlayers : DeviceSetting<List<CustomPlayer>>("playback.customPlayers", emptyList(), ListSerializer(CustomPlayer.serializer()))

    /**
     * The name of the player [Player] names when it was chosen ("VLC"), so Settings and the error of a play can still
     * name it once it isn't found on this device any more; `null` when unknown (Tortuga, a development session's choice).
     */
    data object PlayerName : DeviceSetting<String?>("playback.playerName", null, String.serializer().nullable)

    /**
     * The "Finished watching?" prompts Detail shows after hand-offs to players that report nothing back, one per profile
     * and title, oldest first ([PlayerAppPrompt]). Kept here so a prompt outlives the app being closed while the other
     * app plays.
     */
    data object PlayerAppPrompts : DeviceSetting<List<PlayerAppPrompt>>(
        "playback.playerAppPrompts",
        emptyList(),
        ListSerializer(PlayerAppPrompt.serializer()),
    )

    // ---- Subtitle appearance (SubtitleAppearance) ----------------------------------------------------------------

    /** Plain-text subtitles' size in percent of the player's own. */
    data object SubtitleSize : DeviceSetting<Int>("subtitles.sizePercent", 100, Int.serializer()) {
        val range = SettingRange(50, 200)
        override fun normalize(value: Int) = range.clamp(value)
    }

    data object SubtitleTextColor : DeviceSetting<SubtitleColor>("subtitles.textColor", SubtitleColor.WHITE, SubtitleColor.serializer())

    data object SubtitleOutlineThickness : DeviceSetting<SubtitleOutline>("subtitles.outline", SubtitleOutline.THIN, SubtitleOutline.serializer())

    data object SubtitleOutlineColor : DeviceSetting<SubtitleColor>("subtitles.outlineColor", SubtitleColor.BLACK, SubtitleColor.serializer())

    /** The opacity of a box behind subtitle text in percent; 0 draws no box. */
    data object SubtitleBackground : DeviceSetting<Int>("subtitles.backgroundPercent", 0, Int.serializer()) {
        val range = SettingRange(0, 100)
        override fun normalize(value: Int) = range.clamp(value)
    }

    data object SubtitleBold : DeviceSetting<Boolean>("subtitles.bold", false, Boolean.serializer())

    /** How far bottom subtitles sit above their own place, in percent of the picture's height. */
    data object SubtitleLift : DeviceSetting<Int>("subtitles.liftPercent", 0, Int.serializer()) {
        val range = SettingRange(0, 50)
        override fun normalize(value: Int) = range.clamp(value)
    }

    /** Styled subtitles (ASS/SSA) take the subtitle style too, in place of their own. */
    data object SubtitleStyledOverride : DeviceSetting<Boolean>("subtitles.styledOverride", false, Boolean.serializer())

    // ---- Downloads (DownloadManager) ---------------------------------------------------------------------------------

    /** Downloads use unmetered networks only; `null` for the platform's default (`DownloadPlatform.wifiOnlyDefault`). */
    data object DownloadWifiOnly : DeviceSetting<Boolean?>("downloads.wifiOnly", null, Boolean.serializer().nullable)

    /** How many downloads transfer at once. */
    data object DownloadsAtOnce : DeviceSetting<Int>("downloads.atOnce", 1, Int.serializer()) {
        val range = SettingRange(1, 3)
        override fun normalize(value: Int) = range.clamp(value)
    }

    /** Watched downloads are deleted (never the one playing). */
    data object DownloadAutoRemoveWatched : DeviceSetting<Boolean>("downloads.autoRemoveWatched", false, Boolean.serializer())

    /** Where new downloads go: a `DownloadLocation.id`; `null` for the platform's first location. */
    data object DownloadLocation : DeviceSetting<String?>("downloads.location", null, String.serializer().nullable)

    /** The subtitle languages saved with a download, as language tags; `null` for the profile's subtitle languages. */
    data object DownloadSubtitleLanguages : DeviceSetting<List<String>?>(
        "downloads.subtitleLanguages",
        null,
        ListSerializer(String.serializer()).nullable,
    )
}

/** One PIN entry's wrong PINs in a row and when its lockout ends (epoch milliseconds; 0 for none yet). */
@Serializable
internal data class PinLockout(val failures: Int, val lockedUntil: Long)

/**
 * Every device setting, resolved against its default. [videoFit], [audioNormalization], [audioChannels],
 * [shaderPreset] and [customShaders] are the player's own choices, made in its options and kept for the next video
 * (`PlayerPreferences`); [swipeGestures] goes to the player the same way.
 */
data class DeviceSettings(
    val hardwareDecoding: Boolean,
    val hdr: Boolean,
    val matchFrameRate: Boolean,
    /** [DeviceSetting.AudioPassthrough]: null for the form factor's default ([audioPassthroughOn]). */
    val audioPassthrough: Boolean?,
    val cacheSizeMegabytes: Int?,
    val activeProfileId: String?,
    val subtitles: SubtitleAppearance,
    val videoFit: VideoFit,
    val audioNormalization: Boolean,
    val audioChannels: AudioChannels,
    /** [DeviceSetting.SwipeGestures]. */
    val swipeGestures: Boolean,
    /** [DeviceSetting.WatchRelay]. */
    val watchRelay: String?,
    /** [DeviceSetting.DownloadWifiOnly]: null for the platform's default. */
    val downloadWifiOnly: Boolean?,
    val downloadsAtOnce: Int,
    val downloadAutoRemoveWatched: Boolean,
    /** [DeviceSetting.DownloadLocation]: null for the platform's first location. */
    val downloadLocation: String?,
    /** [DeviceSetting.DownloadSubtitleLanguages]: null for the profile's subtitle languages. */
    val downloadSubtitleLanguages: List<String>?,
    /** [DeviceSetting.Player]: null for Tortuga's own player. */
    val player: String? = null,
    val customPlayers: List<CustomPlayer> = emptyList(),
    /** [DeviceSetting.PlayerName]. */
    val playerName: String? = null,
    val playerAppPrompts: List<PlayerAppPrompt> = emptyList(),
    /** [DeviceSetting.DiscordPresence]. */
    val discordPresence: Boolean = false,
    /** [DeviceSetting.CrashReports]. */
    val crashReports: Boolean = DeviceSetting.CrashReports.default,
    /** [DeviceSetting.ShaderPreset]. */
    val shaderPreset: String = DeviceSetting.ShaderPreset.default,
    /** [DeviceSetting.CustomShaders]. */
    val customShaders: List<CustomShader> = emptyList(),
) {
    /**
     * Whether passthrough is on for a [formFactor] device: the stored choice, else on for a TV (Android TV, Apple TV),
     * which hands the bitstream to the TV or receiver over HDMI, and off on phones, tablets and desktops, whose routes
     * (headphones, speakers) are verified less.
     */
    fun audioPassthroughOn(formFactor: FormFactor): Boolean = audioPassthrough ?: (formFactor == FormFactor.TV)

    companion object {
        val DEFAULT: DeviceSettings = fromStored(emptyMap())

        internal fun fromStored(values: Map<String, String>): DeviceSettings {
            fun <T> DeviceSetting<T>.value() = decodeSetting(values[key])
            return DeviceSettings(
                hardwareDecoding = DeviceSetting.HardwareDecoding.value(),
                hdr = DeviceSetting.Hdr.value(),
                matchFrameRate = DeviceSetting.MatchFrameRate.value(),
                audioPassthrough = DeviceSetting.AudioPassthrough.value(),
                cacheSizeMegabytes = DeviceSetting.CacheSizeMegabytes.value(),
                activeProfileId = DeviceSetting.ActiveProfileId.value(),
                subtitles = SubtitleAppearance(
                    sizePercent = DeviceSetting.SubtitleSize.value(),
                    textColor = DeviceSetting.SubtitleTextColor.value(),
                    outline = DeviceSetting.SubtitleOutlineThickness.value(),
                    outlineColor = DeviceSetting.SubtitleOutlineColor.value(),
                    backgroundPercent = DeviceSetting.SubtitleBackground.value(),
                    bold = DeviceSetting.SubtitleBold.value(),
                    liftPercent = DeviceSetting.SubtitleLift.value(),
                    styledOverride = DeviceSetting.SubtitleStyledOverride.value(),
                ),
                videoFit = DeviceSetting.VideoFit.value(),
                audioNormalization = DeviceSetting.AudioNormalization.value(),
                audioChannels = DeviceSetting.AudioChannels.value(),
                swipeGestures = DeviceSetting.SwipeGestures.value(),
                watchRelay = DeviceSetting.WatchRelay.value(),
                downloadWifiOnly = DeviceSetting.DownloadWifiOnly.value(),
                downloadsAtOnce = DeviceSetting.DownloadsAtOnce.value(),
                downloadAutoRemoveWatched = DeviceSetting.DownloadAutoRemoveWatched.value(),
                downloadLocation = DeviceSetting.DownloadLocation.value(),
                downloadSubtitleLanguages = DeviceSetting.DownloadSubtitleLanguages.value(),
                player = DeviceSetting.Player.value(),
                customPlayers = DeviceSetting.CustomPlayers.value(),
                playerName = DeviceSetting.PlayerName.value(),
                playerAppPrompts = DeviceSetting.PlayerAppPrompts.value(),
                discordPresence = DeviceSetting.DiscordPresence.value(),
                crashReports = DeviceSetting.CrashReports.value(),
                shaderPreset = DeviceSetting.ShaderPreset.value(),
                customShaders = DeviceSetting.CustomShaders.value(),
            )
        }
    }
}

/** This device's settings. */
class DeviceSettingsRepository internal constructor(private val db: Db) {
    fun observe(): Flow<DeviceSettings> =
        db.observeList(db.database.deviceSettingQueries.selectAll()).map { it.toSettings() }

    suspend fun settings(): DeviceSettings = db.read { deviceSettingQueries.selectAll().executeAsList().toSettings() }

    /** One setting's value, its default while unset. */
    suspend fun <T> value(setting: DeviceSetting<T>): T = setting.decodeSetting(
        db.read { deviceSettingQueries.selectAll().executeAsList().firstOrNull { it.setting_key == setting.key }?.value_json },
    )

    suspend fun <T> set(setting: DeviceSetting<T>, value: T) {
        val json = settingsJson.encodeToString(setting.serializer, setting.normalize(value))
        db.write { q.deviceSettingQueries.upsert(Device_setting(setting.key, json)) }
    }

    /**
     * Stores what [change] makes of the stored value (the default while unset), read in the same transaction: edits
     * made one after another (two quick steps, two list edits) each start from the one before. Nothing is written when
     * the value stays the same.
     */
    suspend fun <T> update(setting: DeviceSetting<T>, change: (T) -> T) {
        db.write {
            val stored = q.deviceSettingQueries.selectAll().executeAsList().firstOrNull { it.setting_key == setting.key }
            val current = setting.decodeSetting(stored?.value_json)
            val next = setting.normalize(change(current))
            if (next == current) return@write
            q.deviceSettingQueries.upsert(Device_setting(setting.key, settingsJson.encodeToString(setting.serializer, next)))
        }
    }

    suspend fun reset(setting: DeviceSetting<*>) {
        db.write { q.deviceSettingQueries.delete(setting.key) }
    }
}

/** Forgets the active profile when it is [profileId] (it's being deleted). */
internal fun Tx.clearActiveProfileIf(profileId: String) {
    val key = DeviceSetting.ActiveProfileId.key
    val stored = q.deviceSettingQueries.selectAll().executeAsList().firstOrNull { it.setting_key == key } ?: return
    if (DeviceSetting.ActiveProfileId.decodeSetting(stored.value_json) == profileId) q.deviceSettingQueries.delete(key)
}

private fun <T> DeviceSetting<T>.decodeSetting(json: String?): T {
    val decoded = json?.let { decodeStored(key, it, serializer) } ?: return default
    return normalize(decoded.value)
}

private fun List<Device_setting>.toSettings() = DeviceSettings.fromStored(associate { it.setting_key to it.value_json })
