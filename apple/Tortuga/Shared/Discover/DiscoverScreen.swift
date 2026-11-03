import SwiftUI
import TortugaKit

/// The Discover route of `app`: its `DiscoverStore` is kept by the navigator for the app session (Discover is a tab:
/// shown again, it is on the catalog, filters and pages it was left on, scrolled where it was), opened on the catalog
/// `TortugaNavigator.openDiscover` asked for (a Board row's "See all", a genre link), else on the first catalog. A link
/// followed later moves it to the linked catalog.
struct DiscoverRoute: View {
    let app: AppModel
    let route: any Route

    var body: some View {
        if let model = retained() {
            let session = model.store
            let store = session.store
            DiscoverScreen(
                state: model.observer.state,
                firstFocus: session.firstFocus,
                scroll: session.scroll,
                isTop: app.isTop(route),
                onSelectType: { store.selectType(index: Int32($0)) },
                onSelectCatalog: { store.selectCatalog(index: Int32($0)) },
                onSelectFilter: { store.selectFilter(filterIndex: Int32($0), optionIndex: Int32($1)) },
                onLoadMore: store.loadNextPage,
                onRetry: store.retry,
                onOpenItem: { store.openItem(card: $0) },
                onItemMenu: { store.menuAction(card: $0, action: $1) },
                onOpenDownloads: store.openDownloads
            )
            .task { await model.observer.observe() }
            .task {
                for await link in app.navigator.discoverLink where link != nil {
                    if let link = app.navigator.takeDiscoverLink() {
                        session.firstFocus.ask()
                        store.open(link: link)
                    }
                }
            }
        }
    }

    private func retained() -> RetainedStore<DiscoverSession, DiscoverUiState>? {
        let app = app
        return app.retained(
            route,
            key: "discover",
            create: {
                // The store takes the link it opens on: whether there is one is read first.
                let linked = app.navigator.discoverLink.value != nil
                return DiscoverSession(store: app.session.discoverStore(), firstFocus: FirstFocus(pending: linked))
            },
            state: { $0.store.state },
            release: { $0.store.release() }
        )
    }
}

/// The Discover store, the view's first focus and where the grid was scrolled, kept together: the view's state outlives
/// the view.
final class DiscoverSession {
    let store: DiscoverStore
    let firstFocus: FirstFocus
    let scroll = ScrollMemory()

    init(store: DiscoverStore, firstFocus: FirstFocus) {
        self.store = store
        self.firstFocus = firstFocus
    }
}

/// One catalog as a poster grid, picked by type, catalog and the catalog's filters in one row
/// above it (`FilterBar`: the screen's header row, `screenHeader`, which stays at the top while the grid scrolls; tvOS:
/// `FilterRow`). The grid starts at the screen's content edge and asks for the next page (`onLoadMore`) as its end
/// comes into view; a failed first or later page offers `onRetry`. Offline, the offline state takes the grid's place
/// (`OfflineStateView`, with `onOpenDownloads`). The grid opens where it was last scrolled to (`scroll`), and at the top
/// of a catalog picked or loaded again.
///
/// tvOS: opened on a link (a Board shelf's "See all", a genre link), the first poster takes focus once the grid has one
/// (`FirstFocus`), unless the user has moved focus on Discover since (to the tab bar, along the filters); coming back
/// from a screen opened on top (Detail), focus goes back to the poster that opened it, unless the user moves first.
struct DiscoverScreen: View {
    let state: DiscoverUiState
    let firstFocus: FirstFocus
    let scroll: ScrollMemory
    /// Discover is the top screen of its stack.
    let isTop: Bool
    let onSelectType: (Int) -> Void
    let onSelectCatalog: (Int) -> Void
    let onSelectFilter: (_ filter: Int, _ option: Int) -> Void
    let onLoadMore: () -> Void
    let onRetry: () -> Void
    let onOpenItem: (MetaCard) -> Void
    /// A card's menu entry picked (`MetaCard.menu`).
    let onItemMenu: (MetaCard, MediaAction) -> Void
    let onOpenDownloads: () -> Void

    @FocusState private var focus: String?
    @State private var lastFocused: String?
    @State private var returns = 0
    /// The user's moves (`FocusRestore.userMoves`) at the last return: a move since ends that return's restore.
    @State private var returnMoves = 0

    var body: some View {
        page
            .navigationTitle(TopLevelDestination.discover.label)
            .inlineNavigationTitle()
            .onChange(of: focus) { _, key in
                if let key { lastFocused = key }
            }
            #if os(tvOS)
            .onChange(of: isTop) { wasTop, top in
                guard top, !wasTop else { return }
                returns += 1
                returnMoves = FocusRestore.userMoves
            }
            .firstFocus(firstFocus, to: items.first.map(Self.key), isTop: isTop, focus: $focus)
            .task(id: returns) {
                guard returns > 0 else { return }
                let keys = Set(items.map(Self.key))
                let target = lastFocused.flatMap { !$0.hasPrefix(Self.itemPrefix) || keys.contains($0) ? $0 : nil }
                await FocusRestore.restore($focus, to: target ?? items.first.map(Self.key), since: returnMoves)
            }
            #endif
    }

    #if os(tvOS)
    /// The grid's posters; none while something else takes its place.
    private var items: [MetaCard] {
        if case .grid(let grid) = onEnum(of: state.body) { return grid.items }
        return []
    }
    #endif

    /// The filters above the grid, or the state in its place.
    @ViewBuilder
    private var page: some View {
        let filters = FilterBar(state: state, focus: $focus, onSelectType: onSelectType, onSelectCatalog: onSelectCatalog, onSelectFilter: onSelectFilter)
        let grid = Group {
            switch onEnum(of: state.body) {
            case .grid(let grid):
                ItemGrid(grid: grid, scroll: scroll, focus: $focus, onLoadMore: onLoadMore, onRetry: onRetry, onOpenItem: onOpenItem, onItemMenu: onItemMenu)
            case .offline(let offline):
                // A catalog picked, or loaded again: its grid opens at the top.
                OfflineStateView(state: offline.offline, onOpenDownloads: onOpenDownloads).onAppear { scroll.y = 0 }
            case .loading:
                LoadingStateView().onAppear { scroll.y = 0 }
            case .failed(let failed):
                ErrorStateView(message: failed.text, onRetry: onRetry).onAppear { scroll.y = 0 }
            case .empty(let empty):
                EmptyStateView(message: empty.text).onAppear { scroll.y = 0 }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(tvOS)
        VStack(spacing: 0) {
            FilterRow { filters }
            grid
        }
        #else
        grid.screenHeader(filters: { filters })
        #endif
    }

    fileprivate static let itemPrefix = "item:"

    fileprivate static func key(_ card: MetaCard) -> String { "\(itemPrefix)\(card.id)" }
}

/// The type, the catalog and each of the catalog's filters: one `FilterMenu` each, side by side in the row that holds
/// them (`screenHeader`; tvOS: `FilterRow`).
private struct FilterBar: View {
    let state: DiscoverUiState
    let focus: FocusState<String?>.Binding
    let onSelectType: (Int) -> Void
    let onSelectCatalog: (Int) -> Void
    let onSelectFilter: (Int, Int) -> Void

    var body: some View {
        if let types = state.types {
            filter(types, id: "discover.type", onSelect: onSelectType)
        }
        if let catalogs = state.catalogs {
            filter(catalogs, id: "discover.catalog", onSelect: onSelectCatalog)
        }
        ForEach(Array(state.filters.enumerated()), id: \.offset) { index, picker in
            filter(picker, id: "discover.filter.\(index)") { onSelectFilter(index, $0) }
        }
    }

    private func filter(_ picker: DiscoverPicker, id: String, onSelect: @escaping (Int) -> Void) -> FilterMenu {
        FilterMenu(
            chip: picker.chip,
            options: picker.options.map { FilterOption(label: $0.label, detail: $0.detail, selected: $0.selected) },
            multiSelect: picker.multiSelect,
            id: id,
            focus: focus,
            onSelect: onSelect
        )
    }
}

/// The posters of every page loaded, in as many columns of the card width as fit; the next page is asked for once a
/// poster near the end comes into view.
private struct ItemGrid: View {
    let grid: DiscoverBodyGrid
    let scroll: ScrollMemory
    let focus: FocusState<String?>.Binding
    let onLoadMore: () -> Void
    let onRetry: () -> Void
    let onOpenItem: (MetaCard) -> Void
    let onItemMenu: (MetaCard, MediaAction) -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        GeometryReader { geometry in
            let layout = dimens.grid(width: geometry.size.width, sides: sides, shape: grid.items.first?.posterShape ?? .poster)
            ScrollView {
                VStack(spacing: 0) {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.fixed(layout.cell), spacing: dimens.gutter, alignment: .top), count: layout.columns),
                        alignment: .leading,
                        spacing: dimens.gutter * 1.5
                    ) {
                        ForEach(Array(grid.items.enumerated()), id: \.element.id) { index, card in
                            MetaPoster(
                                card: card,
                                width: layout.cell,
                                accessibilityId: "discover.item.\(index)",
                                focus: CardFocus(binding: focus, key: DiscoverScreen.key(card)),
                                action: { onOpenItem(card) },
                                onMenu: { onItemMenu(card, $0) }
                            )
                            .onAppear {
                                if index >= grid.items.count - Int(DiscoverStore.companion.LOAD_AHEAD) { loadMore() }
                            }
                        }
                    }
                    .padding(sides.insets)
                    // Room for the focused poster to lift (tvOS) or its focus ring.
                    .padding(.vertical, dimens.focusRoom + 4)
                    switch onEnum(of: grid.more) {
                    case .loading:
                        MoreFooter(error: nil, onRetry: onRetry)
                    case .failed(let failed):
                        MoreFooter(error: failed.text, onRetry: onRetry)
                    case .none, .available:
                        EmptyView()
                    }
                }
            }
            .scrollMemory(scroll)
        }
    }

    /// The next page, when there is one and nothing is loading or failed.
    private func loadMore() {
        if case .available = onEnum(of: grid.more) { onLoadMore() }
    }
}

private struct MoreFooter: View {
    let error: String?
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            if let error {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
                ActionButton(title: T.shared.browse.retry, id: "discover.more.retry", systemImage: "arrow.clockwise", fullWidth: false, action: onRetry)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity)
        #if os(tvOS)
        // As wide as the grid: Down from a poster in any column reaches Retry.
        .focusSection()
        #endif
        .padding(.vertical, 16)
    }
}
