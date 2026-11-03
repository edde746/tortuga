package com.edde746.tortuga.app

import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.ShaderFiles
import com.edde746.tortuga.playback.output.PlaybackOutputHost
import com.edde746.tortuga.playback.output.VideoCapabilities
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.presentation.homescreen.HomeScreen
import com.edde746.tortuga.presentation.player.DeviceLevels
import com.edde746.tortuga.presentation.player.DiscordPresenceHost
import com.edde746.tortuga.presentation.player.MediaControlsHost
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerStore
import com.edde746.tortuga.presentation.settings.DeviceFeatures

/**
 * What the running app ([AppSession], [RootSession]) needs from the OS it runs on (the glue stays native):
 * one per process, implemented by each app (Android, desktop, the Apple apps). Called on the main thread, but for
 * [bundledTexts].
 */
interface AppPlatform {
    /** The kind of device, for the output policy (a desktop switches the display mode only while fullscreen). */
    val formFactor: FormFactor

    /** The player's keymap profile and timings, with the platform's double-click interval. */
    val playerConfig: PlayerConfig

    /**
     * An addon's install link ([com.edde746.tortuga.model.DeepLink.InstallAddon]) shows its install page over the
     * screen on top ([AppSessionState.installPreview]) rather than opening Addons on it, and the user stays where they
     * were. Apple TV: its tab bar selects the tab whose button has focus, and Settings' tab coming up with the install
     * page presented left focus on the old tab's button, which selected that tab again.
     */
    val installLinksOverScreen: Boolean get() = false

    /** The OS side of a new playback: its own engine (on its own surface where the platform draws one per engine). */
    fun createPlayback(): PlatformPlayback

    /** Enters or leaves fullscreen: desktop windows. Where the player always fills the screen, nothing happens. */
    fun setFullscreen(fullscreen: Boolean)

    /**
     * The screen's brightness and the system's volume, which swipes up and down the video change: phones and tablets.
     * Null elsewhere.
     */
    val deviceLevels: DeviceLevels? get() = null

    /**
     * Opens the `http`/`https` [url] outside the app: the browser, or the app that handles it. False when nothing on
     * this device opens it (an Apple TV has no browser, an Android TV may have none): [AppSession.openUrl] then offers
     * it to a phone. [AppSession.openUrl] only passes web links, and each platform's opener refuses (false) any other
     * ([com.edde746.tortuga.net.WebUrl.isWeb]) on its own too.
     */
    fun openUrl(url: String): Boolean

    /**
     * Puts [text] on the clipboard as plain text, after the user asked to copy it: a share link, a room's code, an
     * addon's link. [sensitive]: the text holds a secret (an addon link's account key), so where the system can, the
     * clipboard keeps it off previews, other devices and clipboard managers: Android 13+ hides its preview, iOS keeps
     * it on this device for a few minutes, macOS marks it concealed. Windows and Linux copy it as any other text.
     */
    fun setClipboardText(text: String, sensitive: Boolean = false)

    /** The device settings that apply on this device (the display, the audio route), detected as Settings opens. */
    fun deviceFeatures(): DeviceFeatures

    /**
     * What the screen and the decoders take, which stream picks record and suggestions weigh streams against; where the
     * platform can't tell more, HDR as [deviceFeatures] says it and nothing else.
     */
    fun videoCapabilities(): VideoCapabilities = VideoCapabilities(hdr = deviceFeatures().hdr, dolbyVision = false, hardwareCodecs = emptySet())

    /**
     * Player apps on this device ([PlayerAppLauncher]): Settings → Player and "Play with…" offer them, and a play hands
     * off to one instead of opening the player. Null where the platform has none (tests, tools).
     */
    val playerApps: PlayerAppLauncher? get() = null

    /**
     * The system's save and open dialogs for a file exist here, usable with this device's input: Settings → Export and
     * import is offered ([com.edde746.tortuga.presentation.settings.SettingsSection.DATA]), and the player's Shaders page
     * imports a shader. Not on Apple TV or Android TV: no picker, the framework's stub, or one a remote can't operate
     * (AndroidPlatform.hasFileDialogs).
     */
    val hasFileDialogs: Boolean get() = false

    /**
     * Where the player's shaders are on this device ([com.edde746.tortuga.playback.ShaderPreset]): the app's own and the
     * imported ones. Every app's mpv runs them: Android moves a playback off its video plane to the GPU output while one
     * runs. Null hides the player's Shaders option (tests, tools).
     */
    val shaderFiles: ShaderFiles? get() = null

    /**
     * Discord's Rich Presence on this computer: the desktop and the Mac speak to the Discord app over its local IPC, and
     * Settings offers the switch ([DeviceFeatures.discordPresence]); null elsewhere, in a build given a blank Discord
     * application ([com.edde746.tortuga.presentation.player.DiscordConfig]) and in a development session given none.
     */
    val discordPresence: DiscordPresenceHost? get() = null

    /**
     * The TV's home screen beside the app, where the running profile's Continue Watching shows ([HomeScreen]): Android
     * TV's Watch Next row, the Apple TV's Top Shelf. Null where the device has none (phones, desktops, Fire TV).
     */
    val homeScreen: HomeScreen? get() = null

    /**
     * Every file under [folder] (a path such as `licenses/ktor`) in the app's bundled resources, at any depth: Android's
     * assets, the desktop app's resources folder (`compose.application.resources.dir`), the Apple app's bundle. Each
     * with its path relative to [folder] and its text read as UTF-8, sorted by path; none when the folder isn't there.
     * It reads files, so it is called off the main thread (About's licence texts).
     */
    fun bundledTexts(folder: String): List<BundledText>

    /** Leaves the app: Close with nothing left to go back to. Where the system leaves apps itself, nothing happens. */
    fun exit()
}

/**
 * The OS side of one playback, made by [AppPlatform.createPlayback]: the [engine], where the OS media controls show it,
 * and what switches the display for it. [PlaybackController] builds the shared parts over it and ends them in a fixed
 * order: the media controls and the display output first, then [release], then the player store, and last the
 * playback session, which releases [engine].
 */
interface PlatformPlayback {
    val engine: PlayerEngine

    /** The hardware decoder [engine] decodes with while the Hardware decoding setting is on (`PlaybackOptions`). */
    val hardwareDecoder: HardwareDecoder

    /** The OS media controls (notification, lock screen, media keys, Now Playing); null where the platform has none. */
    val mediaControls: MediaControlsHost?

    /** Display mode, HDR and passthrough switching for [engine]; null where the platform switches none of them. */
    val output: PlaybackOutputHost?

    /**
     * The playback's store exists, before its first file loads: glue that drives the store starts here (the Apple audio
     * session pauses and resumes it around interruptions). Called once.
     */
    fun attach(store: PlayerStore) {}

    /**
     * Keeps the display awake (no dimming, screen saver or lock) while [awake]: the player store's
     * [PlayerUiState.keepAwake][com.edde746.tortuga.presentation.player.PlayerUiState.keepAwake], which
     * [PlaybackController] passes on as it changes, and false as the playback ends, before [release]. Holds at most one
     * OS assertion, which the OS drops with the process however it ends.
     */
    fun keepDisplayAwake(awake: Boolean)

    /** The playback ended, before its store and engine are: picture in picture stops, the audio session ends. Called once. */
    fun release() {}
}

/** A text file of the app's bundled resources ([AppPlatform.bundledTexts]): its [path] under the folder asked for. */
data class BundledText(val path: String, val text: String)
