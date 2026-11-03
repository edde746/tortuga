import AVFoundation
import MediaAccessibility
import Network
import Observation
import os
import OSLog
import TortugaKit

private let log = Logger(subsystem: "com.edde746.tortuga", category: "app")

/// The process's start (the composition root): builds the shared core off the main thread, then the product's account
/// shell, or in DEBUG builds a `--dev` session (`LaunchOptions`).
@MainActor
@Observable
final class AppLaunch {
    enum Phase {
        /// The shared core is being built (the database opens), or a `--dev` session is being set up.
        case starting
        /// The product: the account shell decides the screens.
        case product(RootModel)
        #if DEBUG
        case dev(AppModel)
        #endif
        /// The launch can't start (a `--dev` launch's arguments or session): the screen says so in the interface
        /// language (`app.launchFailed`), the log says why.
        case failed
    }

    /// Leaving `.starting` (the session up, or the launch failed) follows the link that arrived meanwhile.
    var phase: Phase = .starting {
        didSet {
            if case .starting = oldValue { followPendingLink() }
        }
    }

    /// The product's start, for the splash: begun once the launch has read its options, before the bootstrap; nil
    /// before then and for a `--dev` session.
    private(set) var startup: StoreObserver<StartupUiState>?

    @ObservationIgnored private var boot: AppBootstrap?
    /// The network's path: the shared core hears whether it's up and metered (`AppGraph.network`).
    @ObservationIgnored private let network = NWPathMonitor()
    /// A link the system handed the app while the core was starting: followed once the session is up.
    @ObservationIgnored private var pendingLink: String?
    /// Whether the scene is in the foreground, for the core that is still starting when the first phase arrives.
    @ObservationIgnored private var inForeground = false
    #if os(macOS)
    /// Whether the window can be seen, whichever app is frontmost, for the core that is still starting; nil before the
    /// first report.
    @ObservationIgnored private var windowVisible: Bool?
    #endif

    init() {
        // The shared core's log lines go to the unified log from the start, before the core exists.
        Log.shared.sink = UnifiedLogSink()
        // The system's settings the shared code follows, before anything reads them (`SystemInputs`): the user's
        // preferred languages, the interface's until the profile chooses one (the app's own first where the system's
        // per-app language setting is used); the Region setting, which streams' country lists are checked against and
        // a per-app language leaves as it is; the caption and audio-description settings, for the track choice.
        let media = Self.mediaAccessibility()
        SystemInputs.shared.report(
            languages: Locale.preferredLanguages,
            region: Locale.current.region?.identifier,
            captions: media.captions,
            audioDescriptions: media.audioDescriptions
        )
        followMediaAccessibility()
        Task { await start() }
    }

    /// The caption and audio-description settings again whenever MediaAccessibility says either changed.
    private func followMediaAccessibility() {
        for name in [kMACaptionAppearanceSettingsChangedNotification, kMAAudibleMediaSettingsChangedNotification] {
            _ = NotificationCenter.default.addObserver(forName: Notification.Name(name as String), object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    let media = Self.mediaAccessibility()
                    SystemMediaAccessibility.shared.report(captions: media.captions, audioDescriptions: media.audioDescriptions)
                }
            }
        }
    }

    /// Closed Captions + SDH is the `alwaysOn` display type; Audio Descriptions puts "describes video" among the
    /// preferred characteristics of audible media.
    private static func mediaAccessibility() -> (captions: Bool, audioDescriptions: Bool) {
        let audible = MAAudibleMediaCopyPreferredCharacteristics().takeRetainedValue() as? [String] ?? []
        return (
            MACaptionAppearanceGetDisplayType(.user) == .alwaysOn,
            audible.contains(AVMediaCharacteristic.describesVideoForAccessibility.rawValue)
        )
    }

    /// The app came to the foreground: sync, and the trackers' queued writes, catch up.
    func onForeground() {
        inForeground = true
        boot?.onForeground()
    }

    /// The app went to the background (or, on the Mac, another app is frontmost): sync stops its periodic pulls.
    func onBackground() {
        inForeground = false
        boot?.onBackground()
    }

    #if os(macOS)
    /// The window can be seen, or no longer can (minimized, hidden, covered whole), whichever app is frontmost and whether
    /// or not the app is active: watch together's player follows the room while it can.
    func onWindowVisibleChanged(_ visible: Bool) {
        windowVisible = visible
        boot?.onWindowVisibleChanged(visible: visible)
    }
    #endif

    /// Follows a link the system handed the app (a watch-together invite, `tortuga://watch?code=…&relay=…`, or a
    /// `stremio:` link: an addon's install, a title, a catalog): the product's account shell takes it
    /// (`RootSession.openUrl`, which keeps it until a profile runs); while the core starts, it waits. In DEBUG builds a
    /// dev session's app follows it at once (`AppModel.followDevLink`).
    func openLink(_ url: String) {
        switch phase {
        case .product(let root):
            root.root.openUrl(url: url)
        #if DEBUG
        case .dev(let app): app.followDevLink(url)
        #endif
        case .starting:
            pendingLink = url
        case .failed:
            break
        }
    }

    /// The link that arrived while the core was starting, now that the session is up.
    private func followPendingLink() {
        guard let link = pendingLink else { return }
        pendingLink = nil
        openLink(link)
    }

    private func start() async {
        let options: LaunchOptions
        do {
            options = try LaunchOptions.current()
        } catch {
            return fail(error.message)
        }
        #if DEBUG
        if let dev = options.dev, dev.dev { return await startDev(dev) }
        #endif
        let version = ApplePlatform.appVersion
        let device = UncheckedBox(ApplePlatform.device)
        // The product's start begins before the core, so the splash can name its steps.
        let startup = Startup(slowStartMillis: options.devSlowStartMillis)
        self.startup = StoreObserver(startup.state)
        let productStart = UncheckedBox(startup)
        let caches = Self.caches.path(percentEncoded: false)
        // A Debug build's core ends the app when one of its coroutines throws; a release build's reports it and runs on.
        #if DEBUG
        let development = true
        #else
        let development = false
        #endif
        // The graph opens the database: off the main thread.
        let boot = await Task.detached(priority: .userInitiated) {
            UncheckedBox(AppBootstrap.companion.product(
                database: DatabaseFactory(),
                secrets: KeychainSecretStore(),
                flavor: .apple,
                appVersion: version,
                device: device.value,
                cacheDirectory: caches,
                startup: productStart.value,
                development: development
            ))
        }.value.value
        // Crash reports to this build's endpoint (a release build's; others have none) while this device's switch is on.
        attach(boot, crashReports: boot.crashReports(
            dsn: BuildCrashReportConfig.shared.config.dsn, environment: CrashReports.companion.PRODUCTION
        ))
        guard let account = boot.account, let sync = boot.sync else {
            return fail("the product's graph has no account")
        }
        phase = .product(RootModel(
            boot: boot, account: account, sync: sync, platform: AppleAppPlatform(), signInServer: options.devSignInServer, startup: startup
        ))
    }

    /// Files the app can download again (the trackers' anime datasets): the Caches directory, on the Mac (whose Caches
    /// every app shares) the app's own folder there.
    static var caches: URL {
        var url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(filePath: NSTemporaryDirectory(), directoryHint: .isDirectory)
        #if os(macOS)
        url.append(path: Bundle.main.bundleIdentifier ?? "com.edde746.tortuga", directoryHint: .isDirectory)
        #endif
        return url
    }

    /// The built core, product or dev: it hears the scene's state so far and the network, offline downloads start, and
    /// `crashReports` (the launch's endpoint) follow this device's switch.
    func attach(_ boot: AppBootstrap, crashReports: CrashReports) {
        self.boot = boot
        if inForeground { boot.onForeground() }
        #if os(macOS)
        if let windowVisible { boot.onWindowVisibleChanged(visible: windowVisible) }
        #endif
        #if !os(tvOS)
        // Offline downloads (none on Apple TV), product and dev alike: the queue picks up where the last process left it,
        // the background session's transfers reattached (the session itself exists since the app finished launching).
        _ = boot.graph.installDownloads(platform: AppleDownloadPlatform())
        #endif
        watchNetwork()
        Task { await CrashReporting.follow(crashReports) }
    }

    /// Tells the shared core how the network is (online, and whether the path is expensive or constrained) as it changes.
    private func watchNetwork() {
        network.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            let metered = path.isExpensive || path.isConstrained
            Task { @MainActor in
                self?.boot?.onNetworkChanged(online: satisfied, metered: metered)
            }
        }
        network.start(queue: .main)
    }

    /// The launch can't start: `reason` (English, a developer's text) goes to the log alone.
    func fail(_ reason: String) {
        log.error("launch failed: \(reason, privacy: .public)")
        phase = .failed
    }
}

/// The shared core's log (`Log`) in the unified log beside the app's own lines: subsystem com.edde746.tortuga, the
/// shared tag (`SyncEngine`) as the category. Info goes in as notice, the level the system keeps (as the app's own
/// lines), where `log show` and a sysdiagnose find it. The shared `Redactor` has already taken the secrets out, so the
/// text is public.
private final class UnifiedLogSink: NSObject, LogSink {
    private let loggers = OSAllocatedUnfairLock(initialState: [String: Logger]())

    func write(level: Log.Level, tag: String, message: String, error: String?) {
        let logger = loggers.withLock { loggers in
            if let logger = loggers[tag] { return logger }
            let logger = Logger(subsystem: "com.edde746.tortuga", category: tag)
            loggers[tag] = logger
            return logger
        }
        let text = error.map { "\(message)\n\($0)" } ?? message
        switch level {
        case .debug: logger.debug("\(text, privacy: .public)")
        case .info: logger.notice("\(text, privacy: .public)")
        case .warn: logger.warning("\(text, privacy: .public)")
        default: logger.error("\(text, privacy: .public)")
        }
    }
}
