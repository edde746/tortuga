import SwiftUI
import TortugaKit

/// The running profile's app: the navigation chrome over the shared navigator's back stacks, each route drawn by
/// `RouteView`, the player over everything, and `AppModel.overlay` (profile editor, adding a sign-in method, a link to
/// open on a phone) as a sheet; on Apple TV a link to open on a phone shows over whatever is up (`openOnPhoneCover`),
/// as does the install page of an addon's install link followed into the app (`installLinkCover`).
///
/// The destinations (`TopLevelDestination`): Home, Discover, Search, Library and Settings, in the system's `TabView`.
/// Addons is a section of Settings; Watch together opens over Home. Each destination has a back stack of its own
/// (`NavigationStacks`), which its tab shows in a `NavigationStack` and keeps while another tab shows; the Mac's
/// sidebar shows the selected destination's.
///
/// - iPhone: the tab bar, which names the destination: its screen shows no navigation bar, its content fading softly
///   under the status bar (`destinationChrome`); while changes can't sync, a line across the top says so
///   (`PhoneSyncLine`). iPad: the floating tab bar, which opens into a sidebar that ends with the running profile
///   (`ProfileHeader`, its avatar marked while changes sync or can't); both name the destination, whose screen then
///   shows no title of its own. Settings excepted on both: a settings page as iOS has them, its large title in the
///   navigation bar.
/// - Mac: the window's sidebar (`NavigationSplitView`) with the destinations, and the running profile at its bottom
///   (`ProfileHeader`); the toolbar, which is compact (the window's `unifiedCompact` style), holds navigation only
///   and shows no title over a destination's screen, whose header row of actions and filters sits on the toolbar's
///   band, level with the window's buttons, and stays there while the screen scrolls (`screenHeader`).
/// - Apple TV: the top tab bar.
/// - Before iOS 18 and tvOS 18: the split view on iPad, the tab bar elsewhere.
///
/// A stack with no top-level destination (a `--dev-open` start) shows its screens without the chrome. The navigator's
/// layout of the shown stack (`BackStackLayout`) tells the screens from the sheet on top: the screens go in the chrome,
/// the Streams sheet over them (`StreamsSheet`: over Detail, or over the player asking for the next episode's stream).
/// The player is never part of a `NavigationStack`: it covers the whole window while it is the top screen (a
/// full-screen cover on iOS and tvOS, on tvOS up at once: `showCover`; an overlay in the Mac window). A stream picked in
/// a sheet over Detail replaces the sheet with the player, which comes up once the sheet has gone (a view presents one
/// thing at a time); Back from the player returns to Detail. Tabs, the sidebar, the menus' destinations (`AppCommands`),
/// Back, swipe-back and closing a sheet all go through the navigator, which keeps the stacks and each route's store
/// (`TortugaNavigator.pick`, `.pop`): a tab picked shows its stack as it was left, the selected tab picked again pops its
/// stack to its root.
struct AppShell: View {
    let app: AppModel

    @Environment(\.formFactor) private var formFactor
    #if !os(macOS)
    /// The player's cover is up: while the player is the top screen, from the moment no sheet over the screens is up.
    @State private var coverShown = false
    /// The sheet over the screens is up, or still going away.
    @State private var screensSheetUp = false
    /// How many times the player's cover has gone (`playerDismissals`).
    @State private var playerDismissals = 0
    #endif
    #if os(iOS)
    /// The tabs show as iPad's sidebar (not as a tab bar).
    @State private var sidebarShown = false
    /// The tabs show as iPad's floating tab bar (not as the sidebar or iPhone's tab bar).
    @State private var barFloats = false
    /// The tab view has been on screen a moment: the selected tab shows its screens (`tabScreen`).
    @State private var tabsSettled = false
    /// iPad's sidebar bottom bar holds the running profile (`remakeBottomBar` takes it out for one update).
    @State private var bottomBarShown = true
    /// SwiftUI's accessibility is on: an assistive technology or a UI test reads the app.
    @Environment(\.accessibilityEnabled) private var accessibilityEnabled

    /// Hosts iPad's sidebar bottom bar anew once SwiftUI's accessibility has switched on (VoiceOver or Switch Control
    /// turned on; under a UI test, about 2 s into the launch). UIKit's sidebar keeps the bar it got before then
    /// without accessibility elements for good, whatever its content does: launched with the sidebar shown, the
    /// profile was drawn but neither assistive technologies nor UI tests could find it (iPadOS 27.0: a change of its
    /// content, even made anew, left the bar with none; a bar first hosted after the switch had them). Out in one
    /// update and back in the next, the bar is hosted in a new view.
    private func remakeBottomBar() {
        bottomBarShown = false
        Task { bottomBarShown = true }
    }
    #endif

    var body: some View {
        let layout = BackStackLayout.companion.of(backStack: app.backStack)
        let screens = layout.screens
        let sheet = layout.sheet as? RouteStreams
        let destinations = TopLevelDestination.allCases
        let selected = TopLevelDestination.companion.selectedIn(backStack: screens)
        let playerShown = screens.last is RoutePlayer
        // Read on every pass, so an ended playback's model goes as the playback does (`AppModel.playback`).
        let playback = app.playback
        chrome(stack: screens, destinations: destinations, selected: selected)
            #if os(iOS)
            .safeAreaInset(edge: .top, spacing: 0) {
                if formFactor == .compact, let gateState = app.gateState { PhoneSyncLine(gateState: gateState) }
            }
            #endif
            // Messages the screens show apart from where VoiceOver is.
            .background { SessionAnnouncements(app: app) }
            .task { await app.stacks.observe() }
            .task { await app.state.observe() }
            .environment(\.posterTemplate, app.state.state.posterTemplate)
            .task { await app.gateState?.observe() }
            #if !os(tvOS)
            // The menus' destinations (`AppCommands`), but while the player is up.
            .focusedSceneValue(
                \.destinationPicker,
                playerShown ? nil : DestinationPicker(navigator: app.navigator, language: InterfaceLocale.shared.tag.value as String)
            )
            #endif
            #if os(iOS)
            // The Playback menu's player while its cover is up without the Streams sheet over it.
            .playbackCommands(playerShown && coverShown && sheet == nil ? playback : nil)
            #endif
            #if os(macOS)
            .overlay {
                if playerShown, let playback {
                    PlayerScreen(playback: playback).id(ObjectIdentifier(playback))
                }
            }
            // A window sheet, over the screens or the player alike.
            .streamsPresentation(item: sheetBinding(sheet)) { item in streamsSheet(item) }
            #else
            .streamsPresentation(item: sheetBinding(playerShown ? nil : sheet), onDismiss: screensSheetGone) { item in
                streamsSheet(item).onAppear { screensSheetUp = true }
            }
            .fullScreenCover(isPresented: playerBinding, onDismiss: { playerDismissals += 1 }) {
                ZStack {
                    Palette.background.ignoresSafeArea()
                    if let playback {
                        PlayerScreen(playback: playback).id(ObjectIdentifier(playback))
                    }
                }
                .announcementProbe()
                #if os(iOS)
                // The iPhone's player asks for a turn once the one under way has ended.
                .background(PlayerOrientation.Transitions())
                #endif
                // Nothing of the player goes in the vertical bar: its controls centre on the video.
                .verticalBarDisabled()
                // The sheet over the player comes from the cover, which is what is on screen.
                .streamsPresentation(item: sheetBinding(playerShown ? sheet : nil)) { item in streamsSheet(item) }
            }
            // The screens learn when the player's cover has gone (`DetailPage` takes focus back then).
            .environment(\.playerDismissals, playerDismissals)
            .onChange(of: playerShown, initial: true) { _, shown in
                if !shown {
                    coverShown = false
                } else if !screensSheetUp {
                    showCover()
                }
            }
            #endif
            #if os(iOS)
            // The iPhone's player turns to landscape while its cover is up.
            .onChange(of: coverShown, initial: true) { _, shown in PlayerOrientation.playerShown(shown) }
            #endif
            #if os(tvOS)
            .trailingPanel(item: overlayBinding, id: "overlay.panel") { item in overlayPage(item) }
            .installLinkCover(app)
            .openOnPhoneCover(app)
            #else
            .statePresentation(overlayBinding) { base, item in base.sheet(item: item) { overlayPage($0) } }
            #endif
            .watchlistSheet(app)
    }

    /// What covers the screens (`OverlayView`), as the sheet or panel shows it.
    private func overlayPage(_ item: OverlayItem) -> some View {
        OverlayView(app: app, overlay: item.overlay)
            .formFactorEnvironment()
            .tortugaTheme()
            .announcementProbe()
    }

    #if os(iOS)
    /// The tab view's tint: white, as the tab bars want it (they draw the selected tab in the tint on a grey platter),
    /// but grey in iPadOS 18's and 27's sidebar, which fill the selected row with the tint under white content, where
    /// white hides the row. iPadOS 26's sidebar draws the selected icon and label in the tint over a grey platter, where
    /// the grey read 2.36:1: white there.
    private var tabTint: Color {
        guard sidebarShown else { return Palette.primary }
        if #available(iOS 27, *) { return Palette.sidebarTint }
        if #available(iOS 26, *) { return Palette.primary }
        return Palette.sidebarTint
    }
    #endif

    @ViewBuilder
    private func chrome(stack: [any Route], destinations: [TopLevelDestination], selected: TopLevelDestination?) -> some View {
        if let selected {
            #if os(macOS)
            // The Mac's own sidebar (`NavigationSplitView`): a `TabView`'s sidebar rows carry no accessibility
            // identifiers there, and the player's overlay loses the keyboard to it.
            splitChrome(stack: stack, destinations: destinations, selected: selected)
            #else
            // A `Tab` carries its accessibility identifier to the tab bar's button (`nav.board`).
            if #available(iOS 18, tvOS 18, *) {
                #if os(iOS)
                let settled = tabsSettled
                #else
                let settled = true
                #endif
                TabView(selection: tabBinding(selected)) {
                    ForEach(destinations, id: \.self) { destination in
                        Tab(value: destination) {
                            tabScreen(destination, selected: selected, screens: stack, settled: settled)
                                #if os(iOS)
                                // The screens keep the app's tint under the chrome's own (below).
                                .tint(Palette.primary)
                                .modifier(TabBarPlacementReader { sidebarShown = $0 == .sidebar; barFloats = $0 == .topBar })
                                #endif
                        } label: {
                            tabLabelView(destination)
                        }
                        .accessibilityIdentifier(destination.accessibilityId)
                    }
                }
                #if os(iOS)
                // iPhone: the tab bar. iPad: the floating tab bar, which opens into the sidebar, the running profile at
                // its bottom.
                .tabViewStyle(.sidebarAdaptable)
                // The bar centres what it holds, down at the screen's edge: the profile is drawn at the destinations'
                // icons and the sidebar's margin above the home indicator (`SidebarBarProfile`). Hosted anew when
                // SwiftUI's accessibility switches on (`remakeBottomBar`).
                .tabViewSidebarBottomBar {
                    if bottomBarShown { SidebarBarProfile(app: app) }
                }
                .onChange(of: accessibilityEnabled) { _, on in
                    if on { remakeBottomBar() }
                }
                .tint(tabTint)
                // The selected tab's screens, a moment after the tab view (`tabScreen`); not when the tab view went
                // within that moment (its `onDisappear` has unsettled it).
                .task {
                    do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
                    tabsSettled = true
                }
                .onDisappear { tabsSettled = false }
                #endif
            } else {
                legacyChrome(stack: stack, destinations: destinations, selected: selected)
            }
            #endif
        } else {
            RouteStack(app: app, stack: stack)
        }
    }

    /// The sidebar (`Sidebar`) beside the selected destination's stack: the Mac, and iPad before iOS 18.
    private func splitChrome(stack: [any Route], destinations: [TopLevelDestination], selected: TopLevelDestination) -> some View {
        NavigationSplitView {
            Sidebar(app: app, selected: selected)
        } detail: {
            RouteStack(app: app, stack: stack)
        }
    }

    /// Before iOS 18 and tvOS 18: the split view on iPad, a tab bar elsewhere. An identifier on a `tabItem` label
    /// reaches the button on tvOS but not on iOS, so iOS 17 goes without.
    @ViewBuilder
    private func legacyChrome(stack: [any Route], destinations: [TopLevelDestination], selected: TopLevelDestination) -> some View {
        switch formFactor {
        case .regular:
            splitChrome(stack: stack, destinations: destinations, selected: selected)
        case .compact, .tv:
            TabView(selection: tabBinding(selected)) {
                ForEach(destinations, id: \.self) { destination in
                    tabScreen(destination, selected: selected, screens: stack)
                        .tabItem {
                            tabLabelView(destination)
                                .accessibilityIdentifier(destination.accessibilityId)
                        }
                        .tag(destination)
                }
            }
        }
    }

    /// A tab's label: the destination's name, but its short name in iPad's floating tab bar, which holds the five in one
    /// row too narrow for some languages' names (iPadOS 26.5's 13" iPad in portrait put Settings behind the bar's scroll
    /// arrow in de, fr and pl). iPad's sidebar draws the rows' symbols in the tint, grey on iPadOS 18 and 27 (`tabTint`):
    /// there they are white images drawn as they are.
    @ViewBuilder
    private func tabLabelView(_ destination: TopLevelDestination) -> some View {
        #if os(iOS)
        if sidebarShown {
            Label {
                Text(destination.label)
            } icon: {
                Image(uiImage: UIImage(systemName: destination.systemImage)!
                    .withTintColor(UIColor(Palette.onBackground), renderingMode: .alwaysOriginal))
                    .renderingMode(.original)
            }
        } else {
            Label(barFloats ? destination.shortLabel : destination.label, systemImage: destination.systemImage)
        }
        #else
        Label(destination.label, systemImage: destination.systemImage)
        #endif
    }

    /// The screen of the tab `destination`: its back stack, `screens` (the shown stack's, the sheet left out) while it is
    /// the `selected` one; nothing until the destination first shows. Each tab keeps its screens while another shows,
    /// so on tvOS, where the tab bar selects the tab whose button takes focus, a screen that opens another tab (a Board
    /// shelf's "See all" opens Discover, a Detail link Search) keeps focus until the tab bar has switched, which moves
    /// focus to the new tab's button. On iOS 18 and later the tabs show their screens only once the tab view has been up
    /// a moment (`settled`, 100 ms): made with the tab view, iPad's navigation bar put a destination's bar buttons
    /// (Downloads' Pause all) in a row under the floating tab bar, not in its row, until another tab was shown. No event
    /// came late enough: screens shown on the tab view's `onAppear`, on its `task`, once the tab bar's placement was
    /// known or once the scene was active still had the row under; so did the buttons placed `topBarTrailing`, added
    /// 300 ms later, or the bar shown 300 ms later. 50 ms and 200 ms delays both put them in the tab bar's row.
    @ViewBuilder
    private func tabScreen(
        _ destination: TopLevelDestination,
        selected: TopLevelDestination,
        screens: [any Route],
        settled: Bool = true
    ) -> some View {
        if settled, let stack = destination == selected ? screens : app.stacks.state.stack(destination: destination) {
            RouteStack(app: app, stack: stack)
        } else {
            Palette.background
        }
    }

    /// The tab bar's selection: a tap on a tab, the selected one too, is a pick (`TortugaNavigator.pick`).
    private func tabBinding(_ selected: TopLevelDestination) -> Binding<TopLevelDestination> {
        Binding(get: { selected }, set: { app.navigator.pick(destination: $0) })
    }

    /// The Streams sheet `route` (nil for none); closing it (a swipe, Close, Escape, the remote's Menu) pops it.
    private func sheetBinding(_ route: RouteStreams?) -> Binding<StreamsSheetItem?> {
        Binding(
            get: { route.map(StreamsSheetItem.init) },
            set: { if $0 == nil, let route { _ = app.navigator.pop(route: route) } }
        )
    }

    private func streamsSheet(_ item: StreamsSheetItem) -> some View {
        let route = item.route
        return StreamsSheet(app: app, route: route) { _ = app.navigator.pop(route: route) }
            .announcementProbe()
    }

    #if !os(macOS)
    /// Dismissing the cover (the remote's Back on tvOS when the store leaves it alone) pops the player.
    private var playerBinding: Binding<Bool> {
        Binding(get: { coverShown }, set: { shown in
            guard !shown else { return }
            coverShown = false
            _ = app.navigator.pop(route: RoutePlayer.shared)
        })
    }

    /// The sheet over the screens has gone: a player that came up meanwhile (a stream picked in it) shows now.
    private func screensSheetGone() {
        screensSheetUp = false
        if BackStackLayout.companion.of(backStack: app.backStack).screens.last is RoutePlayer { showCover() }
    }

    /// Puts the player's cover up: on Apple TV at once, without the system's cross-fade, as the Compose player replaces
    /// the screen it opens over. Through a fade tvOS keeps focus, and with it the remote's presses, on the covered
    /// screen's focused control (a second Select on a stream's row would pick it again and restart the playback), and
    /// the player takes focus only once the fade has ended.
    private func showCover() {
        #if os(tvOS)
        withTransaction(\.disablesAnimations, true) { coverShown = true }
        #else
        coverShown = true
        #endif
    }
    #endif

    /// The overlay as the sheet's (tvOS: the panel's) item. On Apple TV a link to open on a phone has its own cover
    /// (`openOnPhoneCover`), which comes over a page already presented too.
    private var overlayBinding: Binding<OverlayItem?> {
        Binding(get: { Self.presented(app.overlay).map(OverlayItem.init) }, set: { if $0 == nil { app.session.closeOverlay() } })
    }

    private static func presented(_ overlay: (any AppOverlay)?) -> (any AppOverlay)? {
        #if os(tvOS)
        overlay is AppOverlayOpenOnPhone ? nil : overlay
        #else
        overlay
        #endif
    }
}

/// A Streams route as a sheet's item: identified as the navigator compares routes.
private struct StreamsSheetItem: Identifiable {
    let route: RouteStreams

    var id: RouteKey { RouteKey(route: route) }
}

extension EnvironmentValues {
    /// How many times the player's full-screen cover has gone (iOS, tvOS): a screen it covered acts once it is back.
    @Entry var playerDismissals = 0
    /// Where the screens' posters come from in place of the addons' (`AppSessionState.posterTemplate`): nil for the
    /// addons' own.
    @Entry var posterTemplate: PosterTemplate? = nil
}

extension TopLevelDestination {
    /// This destination's SF Symbol in the tab bar and the sidebar.
    var systemImage: String {
        switch self {
        case .board: "house"
        case .discover: "safari"
        case .search: "magnifyingglass"
        case .library: "books.vertical"
        case .settings: "gearshape"
        }
    }

    /// This destination's accessibility identifier in the tab bar and the sidebar (`nav.board`).
    var accessibilityId: String { "nav.\((self as __TopLevelDestination).probeName)" }
}

/// The session's messages that show where VoiceOver isn't, announced as each starts and as its wording changes: sync
/// trouble (`SyncKind.needsAttention`: on iPhone a line across the top, `PhoneSyncLine`; elsewhere a mark on the
/// profile's avatar, `ProfileHeader`) and a hand-off to a player app (`PlayerAppBannerView`'s line, which both the
/// screens and the Streams sheet show). Reading the states here keeps their changes from drawing the screens again.
private struct SessionAnnouncements: View {
    let app: AppModel

    var body: some View {
        let sync = app.gateState?.state
        Color.clear
            .accessibilityHidden(true)
            .announcing(sync?.syncKind.needsAttention == true ? sync?.syncStatus : nil)
            .announcing(app.state.state.playerApp?.text)
    }
}

extension SyncStatusKind {
    /// The sync status's symbol: the avatar's sync mark, iPhone's sync line.
    var systemImage: String {
        switch onEnum(of: self) {
        case .idle: "checkmark.icloud"
        case .syncing: "arrow.triangle.2.circlepath.icloud"
        case .error: "exclamationmark.icloud"
        case .offline, .signedOut: "icloud.slash"
        }
    }
}

/// What covers the app's screens (`AppModel.overlay`): a sheet on iOS and macOS, a `TrailingPanel` on tvOS (the system's
/// sheet there squeezed the page to a sliver). A Mac sheet takes its content's ideal size, which an account page (a
/// scrolling column) doesn't have: the sheet gets one that shows the profile editor whole, and tells the page it is in
/// a sheet (`isInSheet`), which has no toolbar for a back button. Apple TV draws a link to open on a phone in a panel of
/// its own (`openOnPhoneCover`).
private struct OverlayView: View {
    let app: AppModel
    let overlay: any AppOverlay

    var body: some View {
        page.sheetFrame(.page)
    }

    @ViewBuilder
    private var page: some View {
        switch onEnum(of: overlay) {
        case .editProfile(let editor):
            ProfileEditorRoute(graph: app.graph, profileId: editor.profileId, onClose: app.session.closeOverlay)
        case .linkIdentity:
            IdentityLinkRoute(app: app)
        case .openOnPhone(let link):
            OpenOnPhonePage(link: link, onClose: app.session.closeOverlay)
        case .parentPin:
            ParentPinRoute(app: app)
        }
    }
}
