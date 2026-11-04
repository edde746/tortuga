package com.edde746.tortuga.app

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SettingRange
import com.edde746.tortuga.data.settings.SubtitleColor
import com.edde746.tortuga.data.settings.SubtitleOutline
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.ShaderPreset
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * A device setting a development session starts with (`--dev-setting`, [DevLaunch]), as given on the command line:
 * [parse] reads `<key>=<value>` by [DeviceSetting.key]. The switches
 * (`video.hardwareDecoding`, `video.hdr`, `video.matchFrameRate`, `audio.passthrough`, `audio.normalization`,
 * `discord.presence`, `privacy.crashReports`) take
 * `true` or `false`, the read-ahead cache (`playback.cacheSizeMegabytes`) a size in MB within its range, or `auto`, the
 * player's fit (`video.fit`) and audio channels (`audio.channels`) a value by name (`video.fit=cover`,
 * `audio.channels=stereo`), the subtitle style (`subtitles.*`) a
 * value of the setting: a whole number within its range, `true` or `false`, or a colour or outline by name
 * (`subtitles.textColor=yellow`, `subtitles.outline=thick`), the watch-together relay (`watch.relay`) a relay's
 * `http(s)` URL, stored canonical, or `public` (or nothing) for
 * Open Watch Party's public relays, and the player (`playback.player`) a player's id (`playback.player=vlc`, `playback.player=custom:dev`;
 * `tortuga` or nothing for Tortuga's). `playback.customPlayer=<name>;<command>;<arguments>` makes the custom players
 * this one alone, with the id `dev` (its player id `custom:dev`): its command and arguments as the platform's custom
 * players read them (`CustomPlayerKind`), the arguments optional (`playback.customPlayer=Handoff;myplayer://?u={url}`);
 * its value is taken whole, `@` included. `offline=true` holds the app offline whatever the platform reports
 * ([Offline]: offline mode on desktop and simulators). `player.fastTimers=true` makes the sleep timer's minutes 6 s
 * each in the players opened from then on ([FastTimers]: a UI test's 5 minutes run out in 30 s). `@<seconds>` after
 * the value (`subtitles.sizePercent=200@60`) stores it that long after the session starts instead ([Later]: a change
 * while a video plays). `appearance.posterTemplate=<url template>` sets every profile's poster URL template, a profile
 * setting, as each profile appears ([PosterTemplate]): its value is taken whole, `@` included. `appearance.hideWatched`
 * and `appearance.hidePosterCaptions` take `true` or `false` alike ([ProfileSwitch]). [applyDevSettings] stores them.
 */
sealed interface DevSetting {
    /** The setting's [DeviceSetting.key] and the value as given, for logs and test reports. */
    val key: String
    val value: String

    data class Switch(val setting: DeviceSetting<Boolean>, val on: Boolean) : DevSetting {
        override val key: String get() = setting.key
        override val value: String get() = on.toString()
    }

    /** [megabytes] null is Automatic. */
    data class CacheSize(val megabytes: Int?) : DevSetting {
        override val key: String get() = DeviceSetting.CacheSizeMegabytes.key
        override val value: String get() = megabytes?.toString() ?: AUTOMATIC_CACHE
    }

    /**
     * A subtitle style setting (`subtitles.*`), a player choice (`video.fit`, `audio.channels`), the watch-together relay
     * (`watch.relay`) or the player (`playback.player`), [stored] as [value]
     * gives it.
     */
    data class Value<T>(val setting: DeviceSetting<T>, val stored: T, override val value: String) : DevSetting {
        override val key: String get() = setting.key

        internal suspend fun storeIn(settings: DeviceSettingsRepository) = settings.set(setting, stored)
    }

    /** The network held offline ([on]) or released to what the platform reports ([AppGraph.network]). Not stored. */
    data class Offline(val on: Boolean) : DevSetting {
        override val key: String get() = OFFLINE_KEY
        override val value: String get() = on.toString()
    }

    /**
     * The player's timers fast ([on]) or as in the product: while on, the sleep timer's minutes are a tenth as long in
     * the players opened from then on ([AppGraph.fastPlayerTimers]); "Still watching?"'s countdown and the other timers
     * (up next's) stay as they are. Not stored.
     */
    data class FastTimers(val on: Boolean) : DevSetting {
        override val key: String get() = FAST_TIMERS_KEY
        override val value: String get() = on.toString()
    }

    /** `playback.customPlayer`: the custom players are [player] alone (its id `dev`). */
    data class CustomPlayers(val player: CustomPlayer) : DevSetting {
        override val key: String get() = CUSTOM_PLAYER_KEY
        override val value: String get() = listOf(player.name, player.command, player.arguments).joinToString(";")
    }

    /** `appearance.posterTemplate`: every profile's poster URL template is [template], stored as each profile appears. */
    data class PosterTemplate(val template: String) : DevSetting {
        override val key: String get() = ProfileSetting.PosterTemplate.key
        override val value: String get() = template
    }

    /** A profile's switch ([PROFILE_SWITCHES]): every profile's is [on], stored as each profile appears. */
    data class ProfileSwitch(val setting: ProfileSetting<Boolean>, val on: Boolean) : DevSetting {
        override val key: String get() = setting.key
        override val value: String get() = on.toString()
    }

    /** [setting], stored [seconds] after the session starts rather than before it. */
    data class Later(val setting: DevSetting, val seconds: Double) : DevSetting {
        override val key: String get() = setting.key
        override val value: String get() = "${setting.value}@$seconds"
    }

    companion object {
        /** @throws IllegalArgumentException for an unknown key or a value the setting doesn't take, with the message to show. */
        @Throws(IllegalArgumentException::class)
        fun parse(spec: String): DevSetting {
            // A custom player's command or a poster URL template may hold an `@` of its own: never a later setting.
            if ('@' in spec && !spec.startsWith("$CUSTOM_PLAYER_KEY=") && !spec.startsWith("${ProfileSetting.PosterTemplate.key}=")) {
                val seconds = spec.substringAfterLast('@').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?: throw IllegalArgumentException("needs <key>=<value>@<seconds after the start>, got '$spec'")
                return Later(parseNow(spec.substringBeforeLast('@')), seconds)
            }
            return parseNow(spec)
        }

        private fun parseNow(spec: String): DevSetting {
            val key = spec.substringBefore('=')
            val value = spec.substringAfter('=', missingDelimiterValue = "")
            DEV_SWITCHES.firstOrNull { it.key == key }?.let { setting ->
                val on = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key takes true or false, got '$spec'")
                return Switch(setting, on)
            }
            if (key == OFFLINE_KEY) {
                val on = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key takes true or false, got '$spec'")
                return Offline(on)
            }
            if (key == FAST_TIMERS_KEY) {
                val on = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key takes true or false, got '$spec'")
                return FastTimers(on)
            }
            if (key == CUSTOM_PLAYER_KEY) {
                val parts = value.split(';', limit = 3)
                val name = parts[0].trim()
                val command = parts.getOrNull(1)?.trim().orEmpty()
                require(name.isNotEmpty() && command.isNotEmpty()) { "$key takes <name>;<command>;<arguments>, got '$spec'" }
                return CustomPlayers(CustomPlayer(DEV_CUSTOM_PLAYER_ID, name, command, parts.getOrNull(2)?.trim().orEmpty()))
            }
            if (key == ProfileSetting.PosterTemplate.key) return PosterTemplate(value)
            PROFILE_SWITCHES.firstOrNull { it.key == key }?.let { setting ->
                val on = value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key takes true or false, got '$spec'")
                return ProfileSwitch(setting, on)
            }
            if (key == DeviceSetting.CacheSizeMegabytes.key) {
                if (value == AUTOMATIC_CACHE) return CacheSize(null)
                val range = DeviceSetting.CacheSizeMegabytes.range
                val megabytes = value.toIntOrNull()?.takeIf { it in range }
                    ?: throw IllegalArgumentException("$key takes $AUTOMATIC_CACHE or a size in MB from ${range.min} to ${range.max}, got '$spec'")
                return CacheSize(megabytes)
            }
            valueSetting(key, value, spec)?.let { return it }
            val keys = (
                DEV_SWITCHES.map { it.key } + DeviceSetting.CacheSizeMegabytes.key + DEV_VALUE_KEYS + CUSTOM_PLAYER_KEY + OFFLINE_KEY +
                    FAST_TIMERS_KEY + ProfileSetting.PosterTemplate.key + PROFILE_SWITCHES.map { it.key }
                ).joinToString()
            throw IllegalArgumentException("needs <key>=<value> with a key of $keys, got '$spec'")
        }
    }
}

private const val AUTOMATIC_CACHE = "auto"

/** [DevSetting.Offline]'s key. */
private const val OFFLINE_KEY = "offline"

/** [DevSetting.FastTimers]' key. */
private const val FAST_TIMERS_KEY = "player.fastTimers"

/** [DevSetting.CustomPlayers]' key. */
private const val CUSTOM_PLAYER_KEY = "playback.customPlayer"

/** The id of [DevSetting.CustomPlayers]' player (its player id `custom:dev`). */
private const val DEV_CUSTOM_PLAYER_ID = "dev"

private val DEV_SWITCHES: List<DeviceSetting<Boolean>> = listOf(
    DeviceSetting.HardwareDecoding,
    DeviceSetting.Hdr,
    DeviceSetting.MatchFrameRate,
    DeviceSetting.AudioNormalization,
    DeviceSetting.SwipeGestures,
    DeviceSetting.DiscordPresence,
    DeviceSetting.CrashReports,
)

private val DEV_VALUE_KEYS: List<String> = listOf(
    DeviceSetting.VideoFit, DeviceSetting.AudioChannels, DeviceSetting.AudioPassthrough,
    DeviceSetting.SubtitleSize, DeviceSetting.SubtitleTextColor, DeviceSetting.SubtitleOutlineThickness, DeviceSetting.SubtitleOutlineColor,
    DeviceSetting.SubtitleBackground, DeviceSetting.SubtitleBold, DeviceSetting.SubtitleLift, DeviceSetting.SubtitleStyledOverride,
    DeviceSetting.WatchRelay, DeviceSetting.Player, DeviceSetting.ShaderPreset,
).map { it.key }

/** The profile switches a development session sets ([DevSetting.ProfileSwitch]). */
private val PROFILE_SWITCHES: List<ProfileSetting<Boolean>> = listOf(ProfileSetting.HideWatched, ProfileSetting.HidePosterCaptions)

/** `watch.relay`'s value for Open Watch Party's public relays (stored as no override). */
private const val PUBLIC_RELAYS = "public"

/**
 * [key] as a player choice, the video output, a subtitle style setting, the relay or the player ([DevSetting.Value]), or
 * null when it is none.
 */
private fun valueSetting(key: String, value: String, spec: String): DevSetting? {
    fun number(setting: DeviceSetting<Int>, range: SettingRange) = DevSetting.Value(
        setting,
        value.toIntOrNull()?.takeIf { it in range } ?: throw IllegalArgumentException("$key takes a whole number from ${range.min} to ${range.max}, got '$spec'"),
        value,
    )
    fun <E : Enum<E>> named(setting: DeviceSetting<E>, entries: List<E>) = DevSetting.Value(
        setting,
        entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("$key takes one of ${entries.joinToString { it.name.lowercase() }}, got '$spec'"),
        value,
    )
    fun switch(setting: DeviceSetting<Boolean>) = DevSetting.Value(
        setting,
        value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key takes true or false, got '$spec'"),
        value,
    )
    return when (key) {
        DeviceSetting.VideoFit.key -> named(DeviceSetting.VideoFit, VideoFit.entries)
        DeviceSetting.AudioChannels.key -> named(DeviceSetting.AudioChannels, AudioChannels.entries)
        DeviceSetting.SubtitleSize.key -> number(DeviceSetting.SubtitleSize, DeviceSetting.SubtitleSize.range)
        DeviceSetting.SubtitleTextColor.key -> named(DeviceSetting.SubtitleTextColor, SubtitleColor.entries)
        DeviceSetting.SubtitleOutlineThickness.key -> named(DeviceSetting.SubtitleOutlineThickness, SubtitleOutline.entries)
        DeviceSetting.SubtitleOutlineColor.key -> named(DeviceSetting.SubtitleOutlineColor, SubtitleColor.entries)
        DeviceSetting.SubtitleBackground.key -> number(DeviceSetting.SubtitleBackground, DeviceSetting.SubtitleBackground.range)
        DeviceSetting.SubtitleBold.key -> switch(DeviceSetting.SubtitleBold)
        DeviceSetting.SubtitleLift.key -> number(DeviceSetting.SubtitleLift, DeviceSetting.SubtitleLift.range)
        DeviceSetting.SubtitleStyledOverride.key -> switch(DeviceSetting.SubtitleStyledOverride)
        // Unset is the form factor's default (`DeviceSettings.audioPassthroughOn`); a run sets it either way.
        DeviceSetting.AudioPassthrough.key -> DevSetting.Value<Boolean?>(
            DeviceSetting.AudioPassthrough,
            value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$key takes true or false, got '$spec'"),
            value,
        )
        DeviceSetting.WatchRelay.key -> DevSetting.Value(
            DeviceSetting.WatchRelay,
            if (value.isEmpty() || value == PUBLIC_RELAYS) {
                null
            } else {
                WatchRelayEndpoint.parse(value)?.baseUrl ?: throw IllegalArgumentException("$key takes a relay's http(s) URL or $PUBLIC_RELAYS, got '$spec'")
            },
            value,
        )
        // Any id: the player chosen may be one this device doesn't have, as after an uninstall.
        DeviceSetting.Player.key -> DevSetting.Value(DeviceSetting.Player, DeviceSetting.Player.normalize(value), value)
        // A built-in preset: a run measures what each costs on its device.
        DeviceSetting.ShaderPreset.key -> DevSetting.Value(
            DeviceSetting.ShaderPreset,
            ShaderPreset.builtIn.firstOrNull { it.id == value }?.id
                ?: throw IllegalArgumentException("$key takes one of ${ShaderPreset.builtIn.joinToString { it.id }}, got '$spec'"),
            value,
        )
        else -> null
    }
}

/**
 * Stores [settings] as this device's settings: a development session's database is its own. A [DevSetting.Later] is
 * stored that long after this call, while the session runs.
 */
@Throws(CancellationException::class)
suspend fun AppGraph.applyDevSettings(settings: List<DevSetting>) {
    for (setting in settings) {
        if (setting is DevSetting.Later) {
            scope.launch {
                delay(setting.seconds.seconds)
                storeDevSetting(setting.setting)
            }
        } else {
            storeDevSetting(setting)
        }
    }
}

private suspend fun AppGraph.storeDevSetting(setting: DevSetting) {
    when (setting) {
        is DevSetting.Switch -> data.deviceSettings.set(setting.setting, setting.on)
        is DevSetting.CacheSize -> if (setting.megabytes == null) {
            data.deviceSettings.reset(DeviceSetting.CacheSizeMegabytes)
        } else {
            data.deviceSettings.set(DeviceSetting.CacheSizeMegabytes, setting.megabytes)
        }
        is DevSetting.Value<*> -> setting.storeIn(data.deviceSettings)
        is DevSetting.Later -> storeDevSetting(setting.setting)
        is DevSetting.Offline -> holdOffline(setting.on)
        is DevSetting.FastTimers -> fastPlayerTimers = setting.on
        is DevSetting.CustomPlayers -> data.deviceSettings.set(DeviceSetting.CustomPlayers, listOf(setting.player))
        // The session's profile is made after the settings are applied: each profile takes it as it appears.
        is DevSetting.PosterTemplate -> scope.launch {
            val done = HashSet<String>()
            data.profiles.observeProfiles().collect { profiles ->
                for (profile in profiles) if (done.add(profile.id)) data.settings.set(profile.id, ProfileSetting.PosterTemplate, setting.template)
            }
        }
        is DevSetting.ProfileSwitch -> scope.launch {
            val done = HashSet<String>()
            data.profiles.observeProfiles().collect { profiles ->
                for (profile in profiles) if (done.add(profile.id)) data.settings.set(profile.id, setting.setting, setting.on)
            }
        }
    }
}
