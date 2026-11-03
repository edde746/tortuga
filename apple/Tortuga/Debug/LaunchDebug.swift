import Foundation
import OSLog
import TortugaKit

#if os(iOS) || os(tvOS)
import AVFoundation
#endif
#if os(tvOS)
import UIKit
#endif

private let log = Logger(subsystem: "com.edde746.tortuga", category: "app")

extension LaunchOptions {
    /// The development options a Debug build reads (`parse`), the same on every platform (the dev module's
    /// `DevLaunch.parse`, which lists them): `--dev` and the session options that need it, `--dev-sign-in-server` and
    /// `--dev-slow-start` for the product's start. Here `--dev-data <name>` keeps the dev session's database under the
    /// app's caches, `--dev-discord` shows on the Mac, and while a session runs the link
    /// `tortuga://dev-setting?<key>=<value>` (the spec percent-encoded as the query) applies a `--dev-setting` at once
    /// (`AppModel.followDevLink`). `--dev-browser` fails the launch: the Apple apps' sign-in pages open in the system's
    /// web authentication session.
    ///
    /// Pass them after the app on the command line (`xcrun simctl launch booted com.edde746.tortuga --dev …`, the Mac
    /// binary's arguments, or a UI test's `launchArguments`). Other arguments are the system's (Xcode adds `-NS…` pairs)
    /// and are left alone; an unknown `--dev…` option fails the launch. On the Mac, AppKit takes the values
    /// (`detail:movie:tt0111161`) for documents to open and then opens no window, unless `-NSTreatUnknownArgumentsAsOpen
    /// NO` comes first.
    mutating func parseDev(_ arguments: [String]) throws(Failure) {
        let launch: DevLaunch
        do {
            launch = try DevLaunch.companion.parse(args: arguments)
        } catch {
            throw Failure(message: error.localizedDescription)
        }
        if launch.browser != nil {
            throw Failure(message: "--dev-browser: the Apple apps' sign-in pages open in the system's web authentication session")
        }
        dev = launch
        devSignInServer = launch.signInServer
        devSlowStartMillis = launch.slowStartMillis
    }
}

extension AppLaunch {
    /// A `--dev` launch: a local graph without an account (`AppBootstrap.dev`), then the dev module's start before any
    /// screen exists, and the session's app (`Phase.dev`), without an account.
    func startDev(_ launch: DevLaunch) async {
        let version = ApplePlatform.appVersion
        let device = UncheckedBox(ApplePlatform.device)
        let trackers = UncheckedBox(launch.trackers)
        let cachesURL = Self.caches
        let caches = cachesURL.path(percentEncoded: false)
        // `--dev-data`: the dev database in the caches, apart from the product's.
        let directory = launch.data.map {
            cachesURL.appending(path: "dev-data/\($0)", directoryHint: .isDirectory).path(percentEncoded: false)
        }
        // The graph opens the database: off the main thread.
        let boot = await Task.detached(priority: .userInitiated) {
            UncheckedBox(AppBootstrap.companion.dev(
                database: directory.map { DatabaseFactory(directory: $0) } ?? DatabaseFactory.companion.inMemory(),
                flavor: .apple,
                appVersion: version,
                device: device.value,
                cacheDirectory: caches,
                trackers: trackers.value
            ))
        }.value.value
        // Crash reports only where the launch names an endpoint, never the build's.
        attach(boot, crashReports: boot.crashReports(
            dsn: launch.crashReports ?? "", environment: CrashReports.companion.DEVELOPMENT
        ))
        do {
            // A `--dev-relay` session's tracker sign-ins open this device's own browser, as the product's do.
            let start = try await Self.startDevSession(
                UncheckedBox(boot.graph), UncheckedBox(launch), UncheckedBox(AppleSignInPlatform())
            ).value
            let platform = AppleAppPlatform(discordApplicationId: launch.discord)
            let app = AppModel(boot.graph.devAppSession(start: start, platform: platform))
            log.notice("dev session: \(start.screens.count) screen(s) opened")
            phase = .dev(app)
        } catch {
            fail(error.localizedDescription)
        }
    }

    /// The dev module's start, run before any screen exists (the desktop runs it before its window too): the
    /// `--dev-setting` device settings, then the session `launch` asks for.
    nonisolated private static func startDevSession(
        _ graph: UncheckedBox<AppGraph>,
        _ launch: UncheckedBox<DevLaunch>,
        _ signIn: UncheckedBox<AppleSignInPlatform>
    ) async throws -> UncheckedBox<DevStart> {
        try await graph.value.applyDevSettings(settings: launch.value.settings)
        return UncheckedBox(try await graph.value.startDevSession(launch: launch.value, signIn: signIn.value))
    }
}

extension AppModel {
    /// A dev session's app follows a link the system handed the app (`AppLaunch.openLink`) at once
    /// (`AppSession.openIncomingLink`), and also takes `tortuga://dev-setting?<key>=<value>` (`followDevSettingLink`),
    /// `tortuga://dev-crash` (`followDevCrashLink`), on iOS and tvOS `tortuga://dev-route-change?reason=<n>`
    /// (`PlaybackAudioSession.followDevRouteChangeLink`) and on tvOS `tortuga://dev-increase-contrast?on=<true|false>`
    /// (`followDevIncreaseContrastLink`).
    func followDevLink(_ url: String) {
        if followDevSettingLink(url) { return }
        if followDevCrashLink(url) { return }
        #if os(iOS) || os(tvOS)
        if PlaybackAudioSession.followDevRouteChangeLink(url) { return }
        #endif
        #if os(tvOS)
        if followDevIncreaseContrastLink(url) { return }
        #endif
        if !session.openIncomingLink(url: url) { log.notice("dev session: link not followed") }
    }

    /// The crash reporting tests' crash: the link `tortuga://dev-crash` throws the dev module's crash probe (a Kotlin
    /// exception whose message holds an addon URL with an API key) into this call, which nothing catches, so the
    /// process ends as it does on a Kotlin crash. False when `url` is another link.
    private func followDevCrashLink(_ url: String) -> Bool {
        guard let components = URLComponents(string: url), components.scheme == "tortuga", components.host == "dev-crash" else {
            return false
        }
        log.notice("dev crash link: crashing")
        DevCrash.shared.crash()
        return true
    }

    #if os(tvOS)
    /// A UI test's Increase Contrast on Apple TV, which its simulator can't turn on (`simctl ui … increase_contrast`
    /// answers that the tvOS runtime doesn't support it): the link `tortuga://dev-increase-contrast?on=<true|false>`
    /// sets or clears the contrast trait of every window of the app (`traitOverrides.accessibilityContrast`), the trait
    /// the setting changes, from which SwiftUI's `colorSchemeContrast` and the app's colours (`Color(_:increased:)`)
    /// resolve. False when `url` is another link.
    private func followDevIncreaseContrastLink(_ url: String) -> Bool {
        guard let components = URLComponents(string: url), components.scheme == "tortuga", components.host == "dev-increase-contrast" else {
            return false
        }
        let on = components.queryItems?.first(where: { $0.name == "on" })?.value == "true"
        log.notice("dev increase contrast link: \(on, privacy: .public)")
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in scene.windows {
                if on {
                    window.traitOverrides.accessibilityContrast = .high
                } else {
                    window.traitOverrides.remove(UITraitAccessibilityContrast.self)
                }
            }
        }
        return true
    }
    #endif

    /// A dev session's device setting changed while it runs, as `--dev-setting` sets one at the start: the link
    /// `tortuga://dev-setting?<key>=<value>` carries the setting's spec, percent-encoded, as its query (a UI test
    /// opens it in the app under test: the subtitle style changed over the paused player). The dev module's
    /// `DevSetting.parse` reads it and `applyDevSettings` applies it at once; a spec it refuses is logged. False when
    /// `url` is another link.
    private func followDevSettingLink(_ url: String) -> Bool {
        guard let components = URLComponents(string: url), components.scheme == "tortuga", components.host == "dev-setting" else {
            return false
        }
        let spec = components.query ?? ""
        let setting: DevSetting
        do {
            setting = try DevSettingCompanion.shared.parse(spec: spec)
        } catch {
            log.error("dev setting link: \(error.localizedDescription, privacy: .public)")
            return true
        }
        log.notice("dev setting link: \(setting.key, privacy: .public)=\(setting.value, privacy: .public)")
        let call = (graph: UncheckedBox(graph), settings: UncheckedBox([setting]))
        Task {
            do {
                try await Self.applyDevSettings(call.graph, call.settings)
            } catch {
                log.error("dev setting link: \(error.localizedDescription, privacy: .public)")
            }
        }
        return true
    }

    /// The dev module's `applyDevSettings`, off the main actor as the dev start runs it.
    nonisolated private static func applyDevSettings(_ graph: UncheckedBox<AppGraph>, _ settings: UncheckedBox<[DevSetting]>) async throws {
        try await graph.value.applyDevSettings(settings: settings.value)
    }
}

#if os(iOS) || os(tvOS)
private let playerLog = Logger(subsystem: "com.edde746.tortuga", category: "player")

extension PlaybackAudioSession {
    /// A UI test's audio route change, which a simulator can't make (it has no headphones to plug in or out): the link
    /// `tortuga://dev-route-change?reason=<AVAudioSession.RouteChangeReason raw value>`, which a dev session follows
    /// (`AppModel.followDevLink`), posts the session's `routeChangeNotification` with that reason, as the system does.
    /// False when `url` is another link.
    static func followDevRouteChangeLink(_ url: String) -> Bool {
        guard let components = URLComponents(string: url), components.scheme == "tortuga", components.host == "dev-route-change" else {
            return false
        }
        guard let reason = components.queryItems?.first(where: { $0.name == "reason" })?.value.flatMap(UInt.init) else {
            playerLog.error("dev route change link without a reason: \(url, privacy: .public)")
            return true
        }
        playerLog.notice("dev route change link: reason \(reason, privacy: .public)")
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            userInfo: [AVAudioSessionRouteChangeReasonKey: reason]
        )
        return true
    }
}
#endif
