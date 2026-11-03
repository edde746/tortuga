import SwiftUI
import TortugaKit

/// The screen of one route. The player is drawn by the shell over everything, so its route draws only the background.
struct RouteView: View {
    let app: AppModel
    let route: any Route
    /// The bottom of the shown stack: the running profile's button goes in its navigation bar (iPad's under the floating
    /// tab bar, its avatar carrying the sync mark as the sidebar's does) unless the destination keeps its bar empty
    /// (`destinationBarEmpty`: iPhone's destinations show no bar; beside iPad's sidebar the sidebar's foot holds it), and
    /// never over Settings, whose profile card right under the bar switches profiles. The others are pushed over it
    /// (`isPushedScreen`).
    let isRoot: Bool

    #if os(iOS)
    @Environment(\.destinationBarEmpty) private var barEmpty
    #endif

    var body: some View {
        screen
            #if DEBUG && os(iOS)
            // The UI tests' probe of how the screen moves (`PageMotionTracker`); none while the screen draws nothing.
            .background { PageMotionTracker(isRoot: isRoot) }
            #endif
            // A route replaced in place (an anime list entry's Detail by the title it is) is a new screen: SwiftUI
            // would otherwise keep the page at that depth, its `.task` still observing the replaced route's store.
            .id(RouteKey(route: route))
            .environment(\.isPushedScreen, !isRoot)
            .background(Palette.background)
            #if os(tvOS)
            // The tab bar names a destination's screen: no second title under it. The guide needs the screen's height
            // for its channels, which the large title would take two of: its day chips open the screen.
            .toolbar(isRoot && TopLevelDestination.companion.of(route: route) != nil || route is RouteGuide ? .hidden : .automatic, for: .navigationBar)
            // Detail lays its page out on the whole screen (`DetailTvPage`), and the guide needs its height for the
            // channels: pushed from a tab, each hides the tab bar while it is on screen, as when it is the only screen;
            // going back shows the bar again.
            .toolbar(route is RouteDetail || route is RouteGuide ? .hidden : .automatic, for: .tabBar)
            #endif
            #if os(iOS)
            .toolbar {
                if isRoot, !barEmpty, !(route is RouteSettings), app.session.canSwitchProfile {
                    ToolbarItem(placement: .topBarLeading) { ProfileHeader(app: app) }
                }
            }
            #endif
    }

    @ViewBuilder
    private var screen: some View {
        switch onEnum(of: route) {
        case .streams(let streams):
            StreamsRoute(app: app, route: streams)
        case .detail(let detail):
            DetailRoute(app: app, route: detail)
        case .library:
            LibraryRoute(app: app)
        case .player:
            // The shell shows the player over the whole window.
            Palette.background
        case .board:
            BoardRoute(app: app, route: route)
        case .discover:
            DiscoverRoute(app: app, route: route)
        case .search:
            SearchRoute(app: app, route: route)
        case .settings:
            SettingsRoute(app: app)
        case .watchTogether:
            WatchTogetherRoute(app: app)
        case .keepSynced:
            #if os(tvOS)
            // Never opened here: Apple TV has no downloads, so nothing leads to Keep synced.
            Palette.background
            #else
            KeepSyncedRoute(app: app)
            #endif
        case .guide:
            GuideRoute(app: app, route: route)
        case .calendar:
            CalendarRoute(app: app)
        case .collectionFolder(let folder):
            CollectionFolderRoute(app: app, route: folder)
        }
    }
}

/// A screen's store with its state mirrored for the view, kept by the navigator for as long as the screen's route is on
/// the back stack, or for a tab's route for the app session (`TortugaNavigator.retain`).
@MainActor
final class RetainedStore<Store: AnyObject, State> {
    let store: Store
    let observer: StoreObserver<State>

    fileprivate init(store: Store, observer: StoreObserver<State>) {
        self.store = store
        self.observer = observer
    }
}

/// What a pushed screen last drew from (its `RetainedStore`), kept in the screen's `@State`. A popped screen's route
/// leaves the stacks as its pop starts, and with it the store (`AppModel.retained` gives nil), while the page still
/// slides out: it draws what it showed rather than nothing. A blank page leaving looked like the screen under it
/// pushed in from the side (iPhone, 30 Oct).
@MainActor
final class LastDrawn<Value> {
    private var value: Value?

    /// `current`, kept; the value kept last when `current` is nil.
    func callAsFunction(_ current: Value?) -> Value? {
        if let current { value = current }
        return value
    }
}

/// How far down a tab's scroll view was scrolled (`scrollMemory`). The screen keeps it beside its store, which the
/// navigator keeps for the session, so the view, built again whenever its tab is shown again (the tab view shows only
/// the selected tab's screens; the Mac's sidebar starts a new stack per destination), opens where it was.
final class ScrollMemory {
    /// The content's offset from its top at rest, in points.
    var y: CGFloat = 0
}

extension View {
    /// This scroll view opens at `memory`'s offset and keeps there the offset each scroll comes to rest at. Before iOS
    /// 18, macOS 15 and tvOS 18 (no scroll geometry to read the offset by) it opens at its top.
    @ViewBuilder
    func scrollMemory(_ memory: ScrollMemory) -> some View {
        if #available(iOS 18, macOS 15, tvOS 18, *) {
            modifier(ScrollMemoryModifier(memory: memory))
        } else {
            self
        }
    }
}

/// The offset restored to the point (`ScrollPosition`): restored by the identity of the item at its top
/// (`scrollPosition(id:)`), a page came back up to an item higher, Discover a row of posters and Home a whole shelf, at
/// its top (iOS 26.5, 12 Oct). The point asked for isn't always where the view comes to rest: on iOS 26.5 Discover's
/// grid came back 106 pt short of it, whether the offset was kept at every change of the scroll view's geometry or
/// where a scroll came to rest (12 Oct; iOS 27.0 came back exact). So until the view is scrolled, the point asked for
/// is corrected by the difference between the offset measured and `memory`'s. A scroll view never scrolled is left to
/// open at its top on its own.
@available(iOS 18, macOS 15, tvOS 18, *)
private struct ScrollMemoryModifier: ViewModifier {
    let memory: ScrollMemory
    @State private var position: ScrollPosition
    /// The point asked of `position`, until the view is scrolled.
    @State private var asked: CGFloat?

    init(memory: ScrollMemory) {
        self.memory = memory
        let y = memory.y > 0 ? memory.y : nil
        _position = State(initialValue: y.map { ScrollPosition(y: $0) } ?? ScrollPosition())
        _asked = State(initialValue: y)
    }

    func body(content: Content) -> some View {
        content
            .scrollPosition($position)
            .onScrollGeometryChange(for: CGFloat.self, of: Self.offset) { _, y in
                guard let asked, abs(y - memory.y) > 0.5 else { return }
                self.asked = asked + memory.y - y
                position.scrollTo(y: asked + memory.y - y)
            }
            .onScrollPhaseChange { _, phase, context in
                if phase != .idle { asked = nil }
                if phase == .idle, asked == nil { memory.y = Self.offset(context.geometry) }
            }
    }

    /// How far the content is scrolled from its top at rest.
    private nonisolated static func offset(_ geometry: ScrollGeometry) -> CGFloat {
        geometry.contentOffset.y + geometry.contentInsets.top
    }
}

extension AppModel {
    /// The store kept for `route` under `key`: made by `create` on first use, released by `release` once the route
    /// has left every stack (a destination's route when the app session closes). Nil while `route` is on no stack
    /// (`TortugaNavigator.retain`: a popped screen still draws while it animates out; making a store for it then would
    /// leak one).
    func retained<Store: AnyObject, State>(
        _ route: any Route,
        key: String,
        create: @escaping () -> Store,
        state: @escaping (Store) -> SkieSwiftStateFlow<State>,
        release: @escaping (Store) -> Void
    ) -> RetainedStore<Store, State>? {
        // The navigator calls both on the main thread: `create` within this call, `release` when the route leaves.
        let retained = navigator.retain(
            route: route,
            key: key,
            create: {
                let retained: RetainedStore<Store, State> = MainActor.assumeIsolated {
                    let store = create()
                    return RetainedStore(store: store, observer: StoreObserver(state(store)))
                }
                return retained
            },
            release: { value in
                guard let value = value as? RetainedStore<Store, State> else { return }
                MainActor.assumeIsolated { release(value.store) }
            }
        )
        return retained as? RetainedStore<Store, State>
    }
}
