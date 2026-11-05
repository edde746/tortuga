import SwiftUI
import TortugaKit

extension View {
    /// A screen of `RouteStack` on the Mac, a top-level destination's or a pushed one's. The window's toolbar draws no
    /// background over it (macOS 15 and later), in a window and in full screen, where the window's buttons stay too
    /// (`WindowLikeFullScreenTitleBar`): the screen's black reaches up under the toolbar, which reads as black while the
    /// window is key. On macOS 26 and later, while the screen's content is scrolled up under the toolbar, it fades to the
    /// window's black in the band, most at the top, so the window's buttons, Back, the title and the toolbar's items stay
    /// readable over it (`WindowToolbarBand`); at rest the band shows what it did, the screen's black or the top of Home's
    /// and Detail's backdrop. A header row on the band draws the fade under itself (`screenHeader`). The toolbar's
    /// background was drawn over each scroll view under the band, as wide as that scroll
    /// view: once the window wasn't key (another app in front, a sheet over the window) it showed as a grey box over
    /// Addons' and Settings' centred column and over Detail's details column, and with the window key the pointer coming
    /// down from the band into a screen whose content reaches under it (Home, Settings, Discover, Library) faded that
    /// screen to black for about a third of a second before it snapped back (macOS 26). The toolbar's own scroll edge
    /// did the same, soft or automatic; hard, it was a dark bar with a hard edge. Without the background a click on a
    /// control on the band (a destination's header row) reaches the control, which the background would take. The band's
    /// empty parts are the title bar's (`WindowDragArea`): a header row's, and over a screen without one the whole band,
    /// where content scrolled up under it takes no clicks, as under a title bar. No blur: macOS has no public progressive
    /// blur, and the fade was chosen over a private one. Elsewhere it does nothing.
    @ViewBuilder
    func windowToolbarBand() -> some View {
        #if os(macOS)
        if #available(macOS 26, *) {
            modifier(WindowToolbarBand())
                .background(WindowLikeFullScreenTitleBar())
        } else if #available(macOS 15, *) {
            toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
                .background(WindowLikeFullScreenTitleBar())
        } else {
            self
        }
        #else
        self
        #endif
    }
}

#if os(macOS)
@available(macOS 26, *)
private struct WindowToolbarBand: ViewModifier {
    /// A header row sits on the band and draws the fade under itself, its empty parts the title bar (`HeaderRowKey`).
    @State private var bandRow = false
    /// The band's height: the screen's top safe area.
    @State private var band: CGFloat = 0
    @State private var scrolled = false

    func body(content: Content) -> some View {
        content
            .environment(\.bandScrolled, scrolled)
            .scrollEdgeEffectHidden(true, for: .top)
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > 0.5 } action: {
                scrolled = $1
            }
            .overlay(alignment: .top) {
                if !bandRow {
                    ZStack {
                        BandFade(shown: scrolled)
                        WindowDragArea()
                    }
                    .frame(height: band)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .ignoresSafeArea(.container, edges: .top)
                }
            }
            .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { band = $0 }
            .onPreferenceChange(HeaderRowKey.self) { bandRow = $0 }
            .toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
    }
}

extension View {
    /// A top-level destination's screen (`destination`, nil for any other), which the sidebar names. The toolbar is
    /// navigation chrome alone (`screenHeader` has the rules): while the screen is on top (`onTop`) it shows the
    /// sidebar's toggle and no title (the window keeps the title for the Window menu; before macOS 15 the toolbar shows
    /// it), over no background (`windowToolbarBand`). On macOS 26 and later the screen's header row, its actions at its
    /// end, sits on the band's line, level with the window's buttons, its height going down as `toolbarBand`, and the
    /// screen's content starts right under the band. A screen without a header row (Settings, Home) starts right under
    /// the band too: its first row's own top padding (`firstRowPadding`) lies under the band, whatever is pushed over the
    /// screen, so it doesn't move on a push or pop. A pushed screen's toolbar holds Back, its title and its actions.
    func destinationChrome(_ destination: TopLevelDestination?, onTop: Bool) -> some View {
        modifier(DestinationChrome(isDestination: destination != nil, onTop: onTop))
    }
}

private struct DestinationChrome: ViewModifier {
    let isDestination: Bool
    let onTop: Bool

    /// The toolbar's band over the screen: the height of its top safe area.
    @State private var band: CGFloat?

    /// The top padding of a screen's first row under the band.
    private static let firstRowPadding: CGFloat = 8

    func body(content: Content) -> some View {
        toolbar(content)
            // A negative spacing narrows the safe area by as much.
            .safeAreaInset(edge: .top, spacing: isDestination ? -Self.firstRowPadding : 0) { Color.clear.frame(height: 0) }
            .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { band = $0 > 0 ? $0 : nil }
    }

    @ViewBuilder
    private func toolbar(_ content: Content) -> some View {
        let topLevel = isDestination && onTop
        if #available(macOS 26, *) {
            content
                .environment(\.toolbarBand, isDestination ? band : nil)
                .toolbarTitleHidden(topLevel)
        } else {
            content.toolbarTitleHidden(topLevel)
        }
    }
}
#elseif os(iOS)
extension View {
    /// A top-level destination's screen (`destination`, nil for any other) while it is on top (`onTop`), which the tabs
    /// name. iPhone (the tab bar at the bottom): no navigation bar at all, the content fading softly under the status
    /// bar (`softTopEdge`). iPad (the sidebar, or the floating tab bar at the top): the bar without its title, the
    /// content fading softly under it (iOS 26 and later). Hidden beside the sidebar, the bar left the app never idle
    /// once iPad turned to landscape (every tap waited out XCUITest's animation timeout), with the soft edge effect and
    /// with a plain fade alike. Beside the sidebar on iOS 26 and later the bar stays, empty and clear, and the screen
    /// starts on its line as iPhone's does under the status bar: the header row, its actions at its end, level with the
    /// sidebar's toggle, so no empty bar shows above it (Library's Calendar alone in the bar above its filters wasted a
    /// row). The floating tab bar holds the bar's line itself, so there the row stays under it. Before iOS 18 iPad's
    /// split view keeps the title. Settings is a settings page as iOS has them on both: the bar with its
    /// large title, which goes inline as the list scrolls up under it, its sections' pages pushed under the same bar.
    ///
    /// A page pushed over iPhone's destination shows the bar, which comes with it and goes with it: the destination's
    /// content keeps its top where it is at rest (`stackTop`, the stack's top safe area without the bar) all the while,
    /// and the bar holds no title for it (iOS 18 and later): the destination's title is inline, the bar's title item,
    /// which the bar leaves out. Large, it isn't the bar's title item: iOS 27.1's vertical bar (the iPhone Duo's cover
    /// turned) showed Home's and Library's large title over their content while a page went back. Following the
    /// bar, the content moved down by the bar's height as the page came and back up as it went, once the page had gone
    /// (iPhone: Settings back from a section, Home back from Downloads or Detail).
    @ViewBuilder
    func destinationChrome(_ destination: TopLevelDestination?, onTop: Bool) -> some View {
        if #available(iOS 18, *) {
            modifier(DestinationChrome(destination: destination, onTop: onTop))
        } else {
            modifier(LegacyDestinationChrome(destination: destination, onTop: onTop))
        }
    }

    /// While `enabled`, content scrolled up under the status bar or the navigation bar fades out softly there, and only
    /// then: at rest the top shows what it does (the effect hidden until the content scrolls). The system's soft scroll
    /// edge effect on iOS 26 and later, carried by a bar of no height (the effect shows under bars). On iOS 26 the
    /// effect blurs only under a navigation bar: under a header row on a screen without one (`screenHeader`) it only
    /// dims, and under the clear bar of no height it draws nothing, so content under the status bar alone stayed sharp
    /// (iOS 26.5, Settings on iPhone before it had its bar). There (`statusBar`: no navigation bar, no header row) iOS
    /// 26 takes the black fade of earlier systems (`StatusBarFade`); iOS 27 draws the effect for the clear bar, blurred.
    /// Disabled, the bar is empty, and an empty bar takes the navigation bar's own effect away (iPad's Home showed its
    /// shelves sharp under the bar and its buttons). Before iOS 26, the black fade over the status bar.
    @available(iOS 18, *)
    func softTopEdge(_ enabled: Bool, statusBar: Bool) -> some View {
        modifier(SoftTopEdge(enabled: enabled, statusBar: statusBar))
    }
}

@available(iOS 18, *)
private struct SoftTopEdge: ViewModifier {
    let enabled: Bool
    let statusBar: Bool

    /// The content is scrolled up under the top of the screen.
    @State private var scrolled = false

    func body(content: Content) -> some View {
        edge(content)
            .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > 0.5 } action: {
                scrolled = $1
            }
    }

    @ViewBuilder
    private func edge(_ content: Content) -> some View {
        if #available(iOS 26, *) {
            content
                .scrollEdgeEffectStyle(enabled ? ScrollEdgeEffectStyle.soft : .automatic, for: .top)
                .scrollEdgeEffectHidden(enabled && !scrolled, for: .top)
                .safeAreaBar(edge: .top, spacing: 0) {
                    if enabled { StatusBarFade(shown: fades && scrolled) }
                }
        } else {
            content.safeAreaInset(edge: .top, spacing: 0) {
                if enabled { StatusBarFade(shown: scrolled) }
            }
        }
    }

    /// The status bar alone lies over the content on iOS 26, whose soft edge draws nothing there.
    private var fades: Bool {
        if #available(iOS 27, *) { return false }
        return statusBar
    }
}

extension EnvironmentValues {
    /// The destination's screen shows no navigation bar (iPhone) or an empty one over its content (iPad beside the
    /// sidebar, `destinationChrome`): nothing goes in the bar. Its actions go in the screen's header row
    /// (`screenHeader`'s: Library's Calendar, Downloads' Pause all); the running profile stays in the sidebar's foot
    /// (iPad) or Settings' profile card (iPhone) (`RouteView`).
    @Entry var destinationBarEmpty = false
    /// The top safe area of the screen's stack (`RouteStack`), without the stack's navigation bar: where iPhone's
    /// destinations keep their content's top while a pushed page's bar comes and goes (`destinationChrome`). Nil until
    /// measured.
    @Entry var stackTop: CGFloat? = nil
}

@available(iOS 18, *)
private struct DestinationChrome: ViewModifier {
    let destination: TopLevelDestination?
    let onTop: Bool

    @Environment(\.tabBarPlacement) private var placement
    @Environment(\.stackTop) private var stackTop
    /// The screen shows a header row (`screenHeader`), which carries the soft edge on iOS 26.
    @State private var headerRow = false

    func body(content: Content) -> some View {
        let compact = placement != .sidebar && placement != .topBar
        // Settings' bar shows its title, as on a pushed page.
        let untitled = destination != nil && destination != .settings
        let phone = untitled && onTop && compact
        let pad = untitled && onTop && !compact
        // iPhone's, and iPad's beside the sidebar, whether or not a page is pushed over it.
        let keptTop = untitled && (compact || onBarLine) ? stackTop : nil
        content
            .environment(\.destinationBarEmpty, phone || pad && onBarLine)
            .toolbar(phone ? .hidden : .automatic, for: .navigationBar)
            .softTopEdge(softEdge(untitled: untitled, phone: phone), statusBar: phone && !headerRow)
            .onPreferenceChange(HeaderRowKey.self) { headerRow = $0 }
            // No title for the destination in iPhone's bar, which a page pushed over it brings and takes away: the
            // tab's name crossed the header row kept under it. Inline, so the title is the bar's title item that goes;
            // iPad's bar too.
            .toolbar(removing: untitled && (compact || onTop) ? .title : nil)
            .navigationBarTitleDisplayMode(untitled ? .inline : destination == .settings ? .large : .automatic)
            .safeAreaPadding(.top, keptTop ?? 0)
            .ignoresSafeArea(.container, edges: keptTop == nil ? [] : .top)
    }

    /// iPad beside the sidebar on iOS 26 and later: the screen starts on the bar's line (the type's comment).
    private var onBarLine: Bool {
        if #available(iOS 26, *) { return placement == .sidebar }
        return false
    }

    /// The soft top edge (`softTopEdge`): on iOS 26 and later every untitled destination's, under iPad's bar too, and
    /// whether or not a page is pushed over it; before, iPhone's on top only (the fade over the status bar). Settings'
    /// bar has its own. Covered, the destination took the navigation bar's own edge effect, and an interactive swipe back
    /// keeps it covered until the swipe ends, so iOS 27.1's vertical bar (the iPhone Duo's cover) drew a dark, blurred
    /// band over Library's header row and the posters' tops all through the swipe.
    private func softEdge(untitled: Bool, phone: Bool) -> Bool {
        if #available(iOS 26, *) { untitled } else { phone }
    }
}

/// iOS 17: iPhone's destinations as on iOS 18, but for the fade over the status bar, always shown (iOS 17 has no
/// scroll geometry to show it by); iPad's split view keeps their titles.
private struct LegacyDestinationChrome: ViewModifier {
    let destination: TopLevelDestination?
    let onTop: Bool

    @Environment(\.formFactor) private var formFactor
    @Environment(\.stackTop) private var stackTop

    func body(content: Content) -> some View {
        let untitled = destination != nil && destination != .settings && formFactor == .compact
        let phone = untitled && onTop
        let keptTop = untitled ? stackTop : nil
        content
            .environment(\.destinationBarEmpty, phone)
            .toolbar(phone ? .hidden : .automatic, for: .navigationBar)
            .safeAreaInset(edge: .top, spacing: 0) {
                if phone { StatusBarFade(shown: true) }
            }
            .safeAreaPadding(.top, keptTop ?? 0)
            .ignoresSafeArea(.container, edges: keptTop == nil ? [] : .top)
    }
}

/// A black fade over the status bar while `shown`, into which content scrolling under it fades (`softTopEdge`): from
/// the window's top to 16 pt into the safe area. Of no height itself, it goes at the safe area's top as a bar or inset
/// of no height, and the fade reaches up from there over the status bar: placed from a reader's frame and safe area,
/// it came out 140 pt tall on iOS 26.5, both reading the status bar's 62 pt, and darkened Settings' profile at rest.
/// It takes no touch.
private struct StatusBarFade: View {
    let shown: Bool

    var body: some View {
        Color.clear
            .frame(height: 0)
            .overlay(alignment: .top) {
                Color.clear
                    .frame(height: 16)
                    .background {
                        LinearGradient(colors: [Palette.background, Palette.background.opacity(0)], startPoint: .top, endPoint: .bottom)
                            .ignoresSafeArea(edges: .top)
                    }
            }
            .opacity(shown ? 1 : 0)
            .animation(.easeOut(duration: 0.2), value: shown)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// Calls `action` with where the tabs show (iPad's sidebar or floating tab bar, iPhone's tab bar), as that changes, from
/// inside a tab.
@available(iOS 18, *)
struct TabBarPlacementReader: ViewModifier {
    let action: (TabBarPlacement?) -> Void

    @Environment(\.tabBarPlacement) private var placement

    func body(content: Content) -> some View {
        content.onChange(of: placement, initial: true) { _, placement in action(placement) }
    }
}
#endif
