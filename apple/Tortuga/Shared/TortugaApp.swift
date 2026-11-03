import SwiftUI
import TortugaKit

#if os(iOS) || os(tvOS)
import UIKit
#endif

@main
struct TortugaApp: App {
    #if os(macOS)
    @NSApplicationDelegateAdaptor private var appDelegate: AppDelegate
    /// Sparkle, for the DMG build (AppKit/Updates.swift).
    private let updater = AppUpdater()
    #elseif os(iOS)
    @UIApplicationDelegateAdaptor private var appDelegate: AppDelegate
    #endif

    /// The process's start: the shared core, then the account shell or a `--dev` session. One per process: the video
    /// surface can only live in one view hierarchy, so there is one window.
    @State private var launch = AppLaunch()

    init() {
        #if os(iOS) || os(tvOS)
        PlaybackAudioSession.setUpForTheApp()
        #endif
        #if os(tvOS)
        // A screen's focus restore ends at the user's first move after it was asked for.
        FocusRestore.countUserMoves()
        // Page titles in the primary text colour, white: in the dark appearance tvOS draws a navigation bar's title in
        // white at 25 % (`_UINavigationBarTVVisualStyle`'s default title colour), #404040 on the black pages, 2.0:1.
        UINavigationBar.appearance().titleTextAttributes = [.foregroundColor: UIColor.label]
        #endif
    }

    var body: some Scene {
        #if os(macOS)
        Window(T.shared.app.name, id: "main") {
            // The window narrows no further than the sidebar beside a screen's least readable width: past it a settings
            // row broke its title after every letter.
            LaunchView(launch: launch)
                .frame(minWidth: 680)
        }
        .defaultSize(width: 1280, height: 720)
        // The toolbar no taller than the row of the window's buttons, and navigation only: on the top-level screens,
        // whose toolbar holds no title, the screen's header row sits on its band (`screenHeader`); pushed screens keep
        // their back button and title there.
        .windowToolbarStyle(.unifiedCompact)
        .commands {
            // View → Show Sidebar / Hide Sidebar (⌃⌘S).
            SidebarCommands()
            CommandGroup(after: .appInfo) {
                CheckForUpdatesButton(updater: updater.controller.updater)
            }
            AppCommands()
        }
        #else
        WindowGroup {
            LaunchView(launch: launch)
        }
        #if os(iOS)
        .commands { AppCommands() }
        #endif
        #endif
    }
}

/// The window's content for each launch phase, in the app's theme and with the window's form factor.
struct LaunchView: View {
    let launch: AppLaunch

    @Environment(\.scenePhase) private var scenePhase

    /// The interface language: the strings are read as the views build, so a new language builds every view again, and
    /// Arabic lays out right to left whether the system or the profile's setting chose it.
    @State private var language = StoreObserver(InterfaceLocale.shared.tag)

    var body: some View {
        let tag = language.state as String
        Group {
            switch launch.phase {
            case .starting:
                StartingView(startup: launch.startup)
            case .product(let root):
                RootView(root: root)
            #if DEBUG
            case .dev(let app): AppShell(app: app)
            #endif
            case .failed:
                ErrorStateView(message: T.shared.app.launchFailed, title: T.shared.app.name)
            }
        }
        .announcementProbe()
        #if DEBUG && os(iOS)
        // The UI tests' log of how the screens moved (`PageMotionLog`).
        .overlay { PageMotionLogProbe() }
        #endif
        .id(tag)
        // Numbers, percentages and dates format in the interface language too, not the system's.
        .environment(\.locale, Locale(identifier: tag))
        .environment(\.layoutDirection, InterfaceLocale.shared.isRightToLeft(tag: tag) ? .rightToLeft : .leftToRight)
        .task { await language.observe() }
        .formFactorEnvironment()
        .verticalBarEnvironment()
        .tortugaTheme()
        #if os(iOS)
        .dismissesKeyboard()
        #endif
        .background(Palette.background)
        // The shared core's foreground (sync's periodic pulls and notice stream, the trackers' listing): the scene's
        // phase from the start. Background is the home screen, or the app running on for audio or picture in picture;
        // inactive is a passing overlay (Control Center, the app switcher), but on the Mac, where it counts as
        // background. The Mac's scene stays active while another app is frontmost (16 Oct: the notice stream stayed
        // open with Finder in front), so there the app's own activation decides too. On the Mac the window can still be
        // seen then: its occlusion, reported apart, keeps watch together's player in the room until it is minimized,
        // hidden or covered whole.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active:
                #if os(macOS)
                if NSApp.isActive { launch.onForeground() }
                #else
                launch.onForeground()
                #endif
            case .background:
                launch.onBackground()
            #if os(macOS)
            case .inactive:
                launch.onBackground()
            #endif
            default:
                break
            }
        }
        #if os(macOS)
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            if scenePhase == .active { launch.onForeground() }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
            launch.onBackground()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didChangeOcclusionStateNotification)) { _ in
            launch.onWindowVisibleChanged(NSApp.occlusionState.contains(.visible))
        }
        .onAppear {
            launch.onWindowVisibleChanged(NSApp.occlusionState.contains(.visible))
        }
        #endif
        // The URLs the app is asked to open (the Info.plist's URL types): Google's sign-in returning to the app (its
        // reversed client ID's scheme), which its SDK takes, on iPhone, iPad and the Mac; watch-together invites
        // (`tortuga://watch?code=…&relay=…`), which open Watch together and join the room (asking first when it is on
        // another relay than the device's, or leaves another room); Stremio links (an addon's
        // `stremio://…/manifest.json`, which opens its install in Settings → Addons, and `stremio:///detail/…` and the
        // other screens'); a title's link, which opens its Detail: the Universal Link `https://usetortuga.app/t/…` on
        // iPhone, iPad and the Mac, and its page's `tortuga://detail?type=…&id=…`; and in a DEBUG dev session
        // `tortuga://dev-setting?<key>=<value>`, a UI test's change of a
        // device setting, on iOS and tvOS `tortuga://dev-route-change?reason=<n>`, a UI test's audio route change, and
        // on tvOS `tortuga://dev-increase-contrast?on=<true|false>`, a UI test's Increase Contrast
        // (`AppLaunch.openLink`). On the Mac a link that starts the app opens its window and arrives here too.
        .onOpenURL { url in
            #if !os(tvOS)
            if AppleSignInPlatform.handleOpenURL(url) { return }
            #endif
            launch.openLink(url.absoluteString)
        }
    }
}

#if os(macOS)
final class AppDelegate: NSObject, NSApplicationDelegate {
    /// The window is the app; closing it quits instead of leaving audio playing with no window.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    /// The system's chrome in the app's one theme, dark, whatever the Mac's appearance: the window's title bar and
    /// toolbar, its sheets, alerts and file panels, the menus and popovers, Sparkle's windows. Set before the window
    /// opens, so its first frame is dark too.
    func applicationWillFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .darkAqua)
    }

    /// SwiftUI opens the window on the open-application event LaunchServices sends. Started without one (the binary
    /// run from a shell, or by a UI test), the app would run with no window at all: it asks itself to reopen, which
    /// opens the window as a click on its Dock icon does. The toolbar's band takes the title bar's double click
    /// (`TitleBarDoubleClick`).
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Downloads' background session, before anything else: transfers that ended while the app wasn't running
        // report as it opens.
        BackgroundTransport.activate()
        TitleBarDoubleClick.install()
        DispatchQueue.main.async {
            guard !NSApp.windows.contains(where: \.isVisible) else { return }
            let reopen = NSAppleEventDescriptor(
                eventClass: AEEventClass(kCoreEventClass),
                eventID: AEEventID(kAEReopenApplication),
                targetDescriptor: NSAppleEventDescriptor(processIdentifier: ProcessInfo.processInfo.processIdentifier),
                returnID: AEReturnID(kAutoGenerateReturnID),
                transactionID: AETransactionID(kAnyTransactionID)
            )
            _ = try? reopen.sendEvent(options: [.noReply], timeout: 1)
        }
    }
}
#endif

#if os(iOS)
/// Downloads' background session on iPhone and iPad (`BackgroundTransport`): made as the app finishes launching, so
/// the system can deliver the events of transfers that ended while the app wasn't running, and told when the system
/// launches or wakes the app in the background for them.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BackgroundTransport.activate()
        return true
    }

    func application(
        _ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        BackgroundTransport.handleEvents(of: identifier, completion: completionHandler)
    }
}
#endif
