import SwiftUI
import TortugaKit

/// The screens of `stack` in a `NavigationStack`: its root is the stack's bottom (a destination's route; a development
/// session's first screen), the routes above it are the path, the player excepted, each Settings route followed by the
/// section open on it (`SettingsSectionPage`) and About's notice page while one shows (`SettingsNoticePage`). Popping
/// the path (the back button, swipe-back, the remote's Back) pops the navigator or closes the section; the stack shows
/// the shortened path until the navigator's (or the Settings store's) state, which reaches the view a moment later,
/// follows (`poppedTo`). A page replaced in place while its push still slides in waits for the slide's end
/// (`StackPath`). Every page is in the path, none pushed by a screen on its own: the Mac's split view keeps such a page
/// in its detail column after the sidebar has switched destinations. A hand-off to a player app shows over the bottom
/// of the stack (`playerAppBanner`), above the tab bar, and so do the app's notices (`appNotices`: a card menu's entry
/// that couldn't be carried out, what Keep synced downloaded on its own), over the stack on iPhone, iPad and Apple TV,
/// over each page on the Mac.
struct RouteStack: View {
    let app: AppModel
    let stack: [any Route]

    /// How many pages SwiftUI left when it last shortened the path, until the pages follow: meanwhile the root is on
    /// screen with the popped page still in `stack`, and a destination's root would show its title for those frames.
    @State private var poppedTo: Int?
    /// The path as NavigationStack was last given it (`StackPath`).
    @State private var given = StackPath()
    /// Counts the ends of the slides a replaced page waited for, so the stack is drawn again then.
    @State private var slidesEnded = 0
    #if os(iOS)
    /// The stack's top safe area, its navigation bar's not in it (`stackTop`).
    @State private var stackTop: CGFloat?
    #endif

    var body: some View {
        let root = stack[0]
        let pages = path()
        // Read so that the end of a slide a replaced page waits for draws the stack again.
        let _ = slidesEnded
        let shown = given.next(poppedTo.map { Array(pages.prefix($0)) } ?? pages, at: .now)
        NavigationStack(path: pathBinding(pages, shown: shown)) {
            RouteView(app: app, route: root, isRoot: true)
                #if !os(tvOS)
                // The chrome names the destination: no second title while its screen is on top.
                .destinationChrome(TopLevelDestination.companion.of(route: root), onTop: shown.isEmpty)
                #endif
                // Outside the chrome, which narrows the top safe area: the whole band.
                .windowToolbarBand()
                #if os(macOS)
                .appNotices(app)
                #endif
                .navigationDestination(for: StackPage.self) { page in
                    switch page {
                    case .route(let key):
                        RouteView(app: app, route: key.route, isRoot: false)
                            .windowToolbarBand()
                            #if os(macOS)
                            .appNotices(app)
                            #endif
                    case .settingsSection(let section):
                        SettingsSectionPage(app: app, section: section)
                            .windowToolbarBand()
                            #if os(macOS)
                            .appNotices(app)
                            #endif
                    case .settingsNotice:
                        SettingsNoticePage(app: app)
                            .windowToolbarBand()
                            #if os(macOS)
                            .appNotices(app)
                            #endif
                    }
                }
        }
        #if os(iOS)
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { stackTop = $0 }
        .environment(\.stackTop, stackTop)
        #endif
        // A stack of its own per root: a destination the sidebar switches to (the Mac's one detail column) starts on a
        // new stack rather than the previous destination's.
        .id(RouteKey(route: root))
        .onChange(of: pages) { poppedTo = nil }
        .task(id: given.due) {
            guard let due = given.due else { return }
            do { try await Task.sleep(for: .seconds(max(due.timeIntervalSinceNow, 0))) } catch { return }
            slidesEnded += 1
        }
        .playerAppBanner(app)
        // The Mac shows the notices on each page instead: drawn over its split view's stack, they reach no
        // accessibility client (VoiceOver, UI tests).
        #if !os(macOS)
        .appNotices(app)
        #endif
    }

    /// The pages above the root.
    private func path() -> [StackPage] {
        var path: [StackPage] = []
        for (index, route) in stack.enumerated() where !(route is RoutePlayer) {
            if index > 0 { path.append(.route(RouteKey(route: route))) }
            if route is RouteSettings, let settings = SettingsModel.retained(by: app), let section = settings.pushedSection {
                path.append(.settingsSection(section))
                if let notice = settings.pushedNotice { path.append(.settingsNotice(notice.index)) }
            }
        }
        return path
    }

    /// The stack's path: `shown`, the start of `pages` while a pop hasn't reached them yet.
    private func pathBinding(_ pages: [StackPage], shown: [StackPage]) -> Binding<[StackPage]> {
        Binding(
            get: { shown },
            set: { newPath in
                // SwiftUI only shortens the path (pushes go through the navigator and the Settings store): undo the
                // first page it dropped.
                guard newPath.count < shown.count else { return }
                poppedTo = newPath.count
                switch pages[newPath.count] {
                case .route(let key):
                    _ = app.navigator.pop(route: key.route)
                case .settingsSection:
                    SettingsModel.retained(by: app)?.store.closeSection()
                case .settingsNotice:
                    SettingsModel.retained(by: app)?.store.closeNotice()
                }
            }
        )
    }
}

/// A page of `RouteStack`'s path: a route, the Settings section open on the Settings route under it, or the page of
/// About's notice at its index over About.
enum StackPage: Hashable {
    case route(RouteKey)
    case settingsSection(SettingsSection)
    case settingsNotice(Int32)
}

/// The path `RouteStack` gives its NavigationStack: the pages as they are, but for a page replaced in place (its route
/// changed, the pages under it the same) within `slide` of the path growing, which keeps the page it replaces until
/// then. A path changed while NavigationStack slides a page in cuts the slide short, the page then in place at once: an
/// anime list entry's Detail is replaced by the title it is as soon as that is found, within a frame of the push once
/// the ids' mapping is at hand (iOS, 30 Oct). Replaced after the slide, the page shows the new screen in place. The
/// navigator holds the new route all along, so Back and the saved stacks have it.
@MainActor
final class StackPath {
    /// How long a push slides on iOS, iPadOS and macOS (about 0.4 s on iOS 27), and a margin.
    static let slide: TimeInterval = 0.6

    private var given: [StackPage] = []
    private var grew = Date.distantPast
    /// When the replaced page held back is due, nil while none is.
    private(set) var due: Date?

    /// The path to give for `pages` at `now`.
    func next(_ pages: [StackPage], at now: Date) -> [StackPage] {
        if pages.count > given.count { grew = now }
        let replacesTop = !pages.isEmpty && pages.count == given.count && pages.last != given.last && pages.dropLast() == given.dropLast()
        if replacesTop, now < grew.addingTimeInterval(Self.slide) {
            due = grew.addingTimeInterval(Self.slide)
            return given
        }
        due = nil
        given = pages
        return pages
    }
}

/// A route as a `NavigationStack` path element: routes are Kotlin values, equal and hashed as Kotlin compares them.
struct RouteKey: Hashable {
    let route: any Route

    static func == (lhs: RouteKey, rhs: RouteKey) -> Bool {
        (lhs.route as AnyObject).isEqual(rhs.route)
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine((route as AnyObject).hash)
    }
}
