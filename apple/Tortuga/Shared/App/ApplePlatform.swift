import Foundation
import TortugaKit

#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// What the app needs from the Apple platform it runs on, as `TortugaPlatform` is for the Compose apps. Main thread.
@MainActor
enum ApplePlatform {
    /// The version About shows and the HTTP client sends (`User-Agent: Tortuga/<version>`).
    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    /// What the sync server lists this device as: its name and kind.
    static var device: DeviceDescriptor {
        #if os(macOS)
        let name = Host.current().localizedName ?? "Mac"
        return DeviceDescriptor(name: deviceName(name), platform: DevicePlatform.shared.MACOS)
        #elseif os(tvOS)
        return DeviceDescriptor(name: deviceName(UIDevice.current.name), platform: DevicePlatform.shared.TVOS)
        #else
        let platform = UIDevice.current.userInterfaceIdiom == .pad ? DevicePlatform.shared.IPADOS : DevicePlatform.shared.IOS
        return DeviceDescriptor(name: deviceName(UIDevice.current.name), platform: platform)
        #endif
    }

    /// `name` cut to the protocol's longest device name, which counts UTF-16 units as the server does, not characters:
    /// whole characters only, so an emoji or an accented letter is never split.
    private static func deviceName(_ name: String) -> String {
        let limit = Int(SyncProtocol.shared.MAX_DEVICE_NAME_LENGTH)
        var units = 0
        var kept = ""
        for character in name {
            units += character.utf16.count
            if units > limit { break }
            kept.append(character)
        }
        return kept
    }

    /// Opens the web link `url` outside the app: the browser, or the app that claims it. False for anything but an
    /// `http`/`https` link (`WebUrl.isWeb`; on a Mac a `file:` link would open the file or launch the app it names), and
    /// on Apple TV, which has no browser: a web page goes to a phone there (`AppSession.openUrl`'s QR code).
    @discardableResult
    static func openURL(_ url: String) -> Bool {
        #if os(tvOS)
        return false
        #else
        guard WebUrl.shared.isWeb(url: url), let url = URL(string: url) else { return false }
        #if os(macOS)
        return NSWorkspace.shared.open(url)
        #else
        UIApplication.shared.open(url)
        return true
        #endif
        #endif
    }
}

/// The shared app's platform port (`AppPlatform`) on Apple platforms: the device's kind, player profile and actions,
/// `ApplePlatform`'s values, and a new `ApplePlayback` (a surface and its engine, Now Playing, the display output, the
/// audio session) for each playback. One per process.
@MainActor
final class AppleAppPlatform: @preconcurrency AppPlatform {
    /// The kind of device the shared output policy decides for: a Mac is a desktop (the display mode switches only
    /// while the player is fullscreen), an iPhone or iPad a handheld, an Apple TV a TV. The app's own `FormFactor`
    /// (Design/Dimens.swift) is the layout's size class, hence the module prefix.
    var formFactor: TortugaKit.FormFactor {
        #if os(tvOS)
        .tv
        #elseif os(macOS)
        .desktop
        #else
        .handheld
        #endif
    }

    /// The player's keymap profile and timings, with the platform's double-click interval.
    var playerConfig: PlayerConfig {
        #if os(tvOS)
        PlayerConfig.companion.defaults(profile: .tv, doubleClickIntervalMillis: 300)
        #elseif os(macOS)
        PlayerConfig.companion.defaults(profile: .desktop, doubleClickIntervalMillis: Int64(NSEvent.doubleClickInterval * 1000))
        #else
        // UIKit's double-tap recognizer waits about this long for the second tap.
        PlayerConfig.companion.defaults(profile: .touch, doubleClickIntervalMillis: 300)
        #endif
    }

    func createPlayback() -> any PlatformPlayback {
        ApplePlayback(seekSeconds: playerConfig.steps.seekSeconds)
    }

    /// Enters or leaves full screen: the Mac's window. iOS and tvOS always play full screen.
    func setFullscreen(fullscreen: Bool) {
        #if os(macOS)
        guard let window = NSApp.keyWindow ?? NSApp.windows.first(where: \.isVisible) else { return }
        if window.styleMask.contains(.fullScreen) != fullscreen { window.toggleFullScreen(nil) }
        #endif
    }

    /// The screen's brightness and the system's volume the player's swipes change: iPhone and iPad (`TouchLevels`);
    /// the Mac and Apple TV have no swipes on the video.
    var deviceLevels: (any DeviceLevels)? {
        #if os(iOS)
        TouchLevels.shared
        #else
        nil
        #endif
    }

    func openUrl(url: String) -> Bool {
        ApplePlatform.openURL(url)
    }

    func setClipboardText(text: String, sensitive: Bool) {
        Pasteboard.copy(text, sensitive: sensitive)
    }

    func deviceFeatures() -> DeviceFeatures {
        ApplePlatform.deviceFeatures
    }

    func videoCapabilities() -> VideoCapabilities {
        ApplePlatform.videoCapabilities
    }

    /// Player apps on this device: the apps Settings → Player and "Play with…" offer, and the hand-off to one.
    let playerApps: (any PlayerAppLauncher)? = ApplePlayerApps()

    /// Discord's Rich Presence: the Mac speaks to the Discord app there (`MacDiscordPresence`, one for the process) as
    /// the Discord application the platform was made with, and without one has none (no Discord switch, nothing
    /// connects); iPhone, iPad and Apple TV have no Discord to speak to.
    let discordPresence: (any DiscordPresenceHost)?

    /// The platform, Rich Presence on the Mac showing as the Discord application `discordApplicationId`: by default
    /// this build's (`BuildDiscordConfig`), a dev session's its `--dev-discord`.
    init(discordApplicationId: String? = BuildDiscordConfig.shared.config.applicationId) {
        #if os(macOS)
        discordPresence = discordApplicationId.map { MacDiscordPresence(applicationId: $0) }
        #else
        discordPresence = nil
        #endif
    }

    /// The system's save and open dialogs (`fileExporter`, `fileImporter`): everywhere but Apple TV, which has none.
    var hasFileDialogs: Bool {
        #if os(tvOS)
        false
        #else
        true
        #endif
    }

    /// The player's shaders: the presets' files in the bundle's `shaders` folder, mpv reading them where they are, and
    /// the imported ones in Application Support's `shaders` (the app's own folder there on the Mac).
    let shaderFiles: (any ShaderFiles)? = {
        let support = NSSearchPathForDirectoriesInDomains(.applicationSupportDirectory, .userDomainMask, true).first
            ?? NSHomeDirectory() + "/Library/Application Support"
        #if os(macOS)
        let custom = support + "/" + (Bundle.main.bundleIdentifier ?? "com.edde746.tortuga") + "/shaders"
        #else
        let custom = support + "/shaders"
        #endif
        return ShaderDirectories(
            renderer: .libplacebo,
            bundledDirectory: Bundle.main.resourcePath! + "/shaders",
            customDirectory: custom,
            installBundled: {}
        )
    }()

    /// The TV's home screen beside the app (`AppPlatform.homeScreen`): the Apple TV's Top Shelf; iOS, iPadOS and macOS
    /// have none.
    #if os(tvOS)
    let homeScreen: (any HomeScreen)? = TopShelfPublisher()
    #else
    var homeScreen: (any HomeScreen)? { nil }
    #endif

    /// Every file under `folder` in the app bundle's resources (`AppPlatform.bundledTexts`), at any depth, its path
    /// relative to `folder` and its text as UTF-8, sorted by path; none when the bundle has no such folder. The shared
    /// core calls it off the main thread, so it is nonisolated: it only reads the bundle.
    nonisolated func bundledTexts(folder: String) -> [BundledText] {
        guard let root = Bundle.main.resourceURL?.appendingPathComponent(folder),
              let paths = FileManager.default.enumerator(atPath: root.path) else { return [] }
        return paths.compactMap { $0 as? String }.compactMap { path -> BundledText? in
            var directory: ObjCBool = false
            let file = root.appendingPathComponent(path).path
            guard FileManager.default.fileExists(atPath: file, isDirectory: &directory), !directory.boolValue,
                  let data = FileManager.default.contents(atPath: file) else { return nil }
            return BundledText(path: path, text: String(decoding: data, as: UTF8.self))
        }.sorted { $0.path < $1.path }
    }

    /// Leaves the app: Close with nothing left to go back to. Only a Mac app quits itself; iOS and tvOS leave that
    /// to the system (the Home button, the remote's TV button).
    func exit() {
        #if os(macOS)
        NSApp.terminate(nil)
        #endif
    }
}
