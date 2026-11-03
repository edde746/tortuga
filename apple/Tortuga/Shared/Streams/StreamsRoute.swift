import SwiftUI
import TortugaKit

/// The Streams route of `app`: its `StreamsStore` is kept by the navigator while the route is on the stack (under
/// "streams", as on desktop), over the item the route was opened with (`TortugaNavigator.openStreams`). `onClose`, for
/// the sheet, closes it (the route leaves the stack).
struct StreamsRoute: View {
    let app: AppModel
    let route: RouteStreams
    var onClose: (() -> Void)?

    /// Closed, the sheet draws what it showed while it goes (`LastDrawn`).
    @State private var drawn = LastDrawn<RetainedStore<StreamsStore, StreamsUiState>>()

    var body: some View {
        if let model = drawn(retained()) {
            StreamsScreen(
                state: model.observer.state,
                onPick: { model.store.pick(id: $0) },
                onReload: model.store.reload,
                onToggleFiltered: model.store.toggleFiltered,
                onDownload: { model.store.download(id: $0) },
                onPlayWith: { model.store.playWith(id: $0, playerId: $1) },
                onDismissDownloadMessage: model.store.dismissDownloadMessage,
                onSelectAddon: { model.store.selectAddon(id: $0) },
                onRefresh: model.store.refresh,
                onToggleSort: { model.store.toggleSort(id: $0) },
                onClose: onClose
            )
            .task { await model.observer.observe() }
        }
    }

    /// Nil once the route has left the stack; while it is on it, the navigator keeps its item for the store.
    private func retained() -> RetainedStore<StreamsStore, StreamsUiState>? {
        let session = app.session
        let route = route
        return app.retained(
            route,
            key: "streams",
            create: { session.streamsStore(route: route) },
            state: { $0.state },
            release: { $0.release() }
        )
    }
}

/// The Streams route as a sheet (`RoutePresentation.sheet`), over Detail or over the player asking for the next
/// episode's stream, headed by the title and the episode line: in a compact window (an iPhone, the iPhone Duo's cover)
/// a sheet at half height that pulls up to full, with the grabber and no close button (Apple's sheets: a resizable
/// sheet closes by a swipe down, a tap on the dimmed screen or VoiceOver's escape), in a regular one (iPad, the Duo's
/// inner screen) a form sheet at its full height with Close (half of it holds about one
/// stream), both with the title and the episode line in their bar; the Mac a window sheet (Close, Escape); Apple TV a
/// `TrailingPanel` over the dimmed screen, which holds focus and the remote's Menu closes (`streamsPresentation`:
/// tvOS's own sheet is a small card that leaves focus behind it). The Mac and Apple TV head the list themselves (a
/// navigation bar's title is one line, cut short in the panel). Picking a stream replaces the sheet with the player;
/// closing it pops the route (`AppShell`). A hand-off to a player app shows over its bottom (`playerAppBanner`), as
/// the sheet covers the screens' own.
struct StreamsSheet: View {
    let app: AppModel
    let route: RouteStreams
    let onClose: () -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        stack
            .sheetFrame(.streams)
            #if os(iOS)
            .formSheetSizing()
            #endif
    }

    @ViewBuilder
    private var stack: some View {
        #if os(tvOS)
        StreamsRoute(app: app, route: route, onClose: onClose)
            .playerAppBanner(app)
            .formFactorEnvironment()
            .tortugaTheme()
        #else
        NavigationStack {
            StreamsRoute(app: app, route: route, onClose: onClose)
        }
        .playerAppBanner(app)
        #if os(iOS)
        // The presenting window's form factor, not the sheet's own width class: an iPhone's sheet is compact also on
        // the iPhone Duo's regular inner screen, where the sheet is iPad's, with Close and the rows' full lines.
        .environment(\.formFactor, formFactor)
        .environment(\.tortugaDimens, .of(formFactor))
        #else
        .formFactorEnvironment()
        #endif
        .tortugaTheme()
        #endif
    }
}

extension View {
    /// Presents `item`'s Streams sheet (`StreamsSheet`): the system's sheet on iOS and macOS, a `TrailingPanel`
    /// (`streams.panel`) on tvOS; `statePresentation` either way.
    func streamsPresentation<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        #if os(tvOS)
        trailingPanel(item: item, id: "streams.panel", onDismiss: onDismiss, content: content)
        #else
        statePresentation(item) { base, item in
            base.sheet(item: item, onDismiss: onDismiss, content: content)
        }
        #endif
    }
}
