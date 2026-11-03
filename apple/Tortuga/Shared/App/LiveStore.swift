import SwiftUI
import TortugaKit

/// A screen's store for as long as the screen is on screen, as Compose's `rememberLiveStore`: made by `create` when
/// the view appears, its `StateFlow` mirrored for `content`, and released by `release` when the view disappears (a
/// view that comes back gets a new store). For the screens outside the back stack: sign-in, the key screens, the
/// profile picker and editor, the app's overlays. Back-stack screens keep theirs in the navigator
/// (`TortugaNavigator.retain`).
struct LiveStore<Store: AnyObject, State, Content: View>: View {
    let create: @MainActor () -> Store
    let state: @MainActor (Store) -> SkieSwiftStateFlow<State>
    let release: @MainActor (Store) -> Void
    @ViewBuilder let content: (Store, State) -> Content

    @SwiftUI.State private var live: Live?

    var body: some View {
        Group {
            if let live {
                content(live.store, live.observer.state)
                    .task { await live.observer.observe() }
            } else {
                Color.clear
            }
        }
        .onAppear {
            if live == nil {
                let store = create()
                live = Live(store: store, observer: StoreObserver(state(store)))
            }
        }
        .onDisappear {
            guard let live else { return }
            self.live = nil
            release(live.store)
        }
    }

    @MainActor
    private final class Live {
        let store: Store
        let observer: StoreObserver<State>

        init(store: Store, observer: StoreObserver<State>) {
            self.store = store
            self.observer = observer
        }
    }
}
