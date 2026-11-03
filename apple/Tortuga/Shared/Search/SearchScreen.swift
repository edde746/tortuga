import SwiftUI
import TortugaKit

/// The Search route of `app`: its `SearchStore` is kept by the navigator for the app session (Search is a tab: shown
/// again, it has the query and results it was left with, scrolled where they were). The field's
/// text lives in the view beside the store: typing goes to the store as it happens, and what the store searches on its
/// own (a picked recent search, a `stremio:///search` link) is written back into the field, so the text never changes
/// under the user's fingers.
struct SearchRoute: View {
    let app: AppModel
    let route: any Route

    var body: some View {
        if let model = retained() {
            let session = model.store
            let store = session.store
            SearchScreen(
                state: model.observer.state,
                initialQuery: model.observer.state.query,
                firstFocus: session.firstFocus,
                scroll: session.scroll,
                isTop: app.isTop(route),
                navigator: app.navigator,
                onQueryChange: { store.setQuery(text: $0) },
                onSubmitQuery: store.submit,
                onSearch: { store.search(text: $0) },
                onPickRecent: { store.pickRecent(index: Int32($0)) },
                onRemoveRecent: { store.removeRecent(index: Int32($0)) },
                onClearRecent: store.clearRecent,
                onRetry: store.retry,
                onLoadMore: { store.loadNextPage(index: Int32($0)) },
                onOpenItem: { store.openItem(card: $0) },
                onItemMenu: { store.menuAction(card: $0, action: $1) },
                onOpenDownloads: store.openDownloads
            )
            .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<SearchSession, SearchUiState>? {
        let app = app
        return app.retained(
            route,
            key: "search",
            create: {
                // The store takes the query it opens on: whether there is one is read first.
                let linked = app.navigator.searchQuery.value != nil
                return SearchSession(store: app.session.searchStore(), firstFocus: FirstFocus(pending: linked))
            },
            state: { $0.store.state },
            release: { $0.store.release() }
        )
    }
}

/// The Search store, the view's first focus and where the results were scrolled, kept together: the view's state
/// outlives the view.
final class SearchSession {
    let store: SearchStore
    let firstFocus: FirstFocus
    let scroll = ScrollMemory()

    init(store: SearchStore, firstFocus: FirstFocus) {
        self.store = store
        self.firstFocus = firstFocus
    }
}

/// Search: the field, then the recent searches while it is blank (each can be searched again or
/// removed, or all cleared), else the results grouped by catalog as shelves, each loading or failing on its own and
/// asking for its next page as its end comes into view (`onLoadMore`, the heading's progress indicator showing while
/// it comes). Offline, the offline state takes their place (`OfflineStateView`, with `onOpenDownloads`).
///
/// - tvOS: the system search keyboard (`searchable`) over the results; opened on a link (a Detail link's cast member),
///   the first result takes focus once there is one (`FirstFocus`), unless the user has moved focus on Search since;
///   coming back from a screen opened on top (Detail), focus goes back to the result that opened it, unless the user
///   moves first.
/// - iOS, macOS: the field is the screen's header row (`screenHeader`; on the Mac on the toolbar's band), staying at
///   the top while the recent searches or the results scroll under it, keeping its place and focus as the results
///   replace the recent searches. Picking Search in the tabs or the sidebar, again while it shows too, puts the cursor
///   in the field, the software keyboard up (`TortugaNavigator.pick`); Search shown otherwise (Back from Detail, the
///   app starting on it) leaves the field be, its text kept. Return (the keyboard's search key) searches at once and
///   keeps the query as a recent search.
struct SearchScreen: View {
    let state: SearchUiState
    let firstFocus: FirstFocus
    /// Where the results were scrolled: they open there.
    let scroll: ScrollMemory
    /// Search is the top screen of its stack.
    let isTop: Bool
    /// Where a `stremio:///search` link followed while Search is on the stack waits (`TortugaNavigator.searchQuery`).
    let navigator: TortugaNavigator
    let onQueryChange: (String) -> Void
    let onSubmitQuery: () -> Void
    /// Sets the query and searches it now (a linked query).
    let onSearch: (String) -> Void
    let onPickRecent: (Int) -> Void
    let onRemoveRecent: (Int) -> Void
    let onClearRecent: () -> Void
    let onRetry: () -> Void
    let onLoadMore: (Int) -> Void
    let onOpenItem: (MetaCard) -> Void
    /// A result's menu entry picked (`MetaCard.menu`).
    let onItemMenu: (MetaCard, MediaAction) -> Void
    let onOpenDownloads: () -> Void

    @State private var field: String
    @FocusState private var focus: String?
    @State private var lastFocused: String?
    @State private var returns = 0
    /// The user's moves (`FocusRestore.userMoves`) at the last return: a move since ends that return's restore.
    @State private var returnMoves = 0
    #if !os(tvOS)
    @FocusState private var fieldFocused: Bool
    #endif

    init(
        state: SearchUiState,
        initialQuery: String,
        firstFocus: FirstFocus,
        scroll: ScrollMemory,
        isTop: Bool,
        navigator: TortugaNavigator,
        onQueryChange: @escaping (String) -> Void,
        onSubmitQuery: @escaping () -> Void,
        onSearch: @escaping (String) -> Void,
        onPickRecent: @escaping (Int) -> Void,
        onRemoveRecent: @escaping (Int) -> Void,
        onClearRecent: @escaping () -> Void,
        onRetry: @escaping () -> Void,
        onLoadMore: @escaping (Int) -> Void,
        onOpenItem: @escaping (MetaCard) -> Void,
        onItemMenu: @escaping (MetaCard, MediaAction) -> Void,
        onOpenDownloads: @escaping () -> Void
    ) {
        self.state = state
        self.firstFocus = firstFocus
        self.scroll = scroll
        self.isTop = isTop
        self.navigator = navigator
        self.onQueryChange = onQueryChange
        self.onSubmitQuery = onSubmitQuery
        self.onSearch = onSearch
        self.onPickRecent = onPickRecent
        self.onRemoveRecent = onRemoveRecent
        self.onClearRecent = onClearRecent
        self.onRetry = onRetry
        self.onLoadMore = onLoadMore
        self.onOpenItem = onOpenItem
        self.onItemMenu = onItemMenu
        self.onOpenDownloads = onOpenDownloads
        _field = State(initialValue: initialQuery)
    }

    var body: some View {
        page
            .navigationTitle(TopLevelDestination.search.label)
            .inlineNavigationTitle()
            .onChange(of: field) { _, text in onQueryChange(text) }
            .onChange(of: focus) { _, key in
                if let key { lastFocused = key }
            }
            .task {
                for await query in navigator.searchQuery where query != nil {
                    if let query = navigator.takeSearchQuery() {
                        firstFocus.ask()
                        field = query
                        onSearch(query)
                    }
                }
            }
            #if os(tvOS)
            .onChange(of: isTop) { wasTop, top in
                guard top, !wasTop else { return }
                returns += 1
                returnMoves = FocusRestore.userMoves
            }
            .task(id: returns) {
                guard returns > 0, let lastFocused else { return }
                await FocusRestore.restore($focus, to: lastFocused, since: returnMoves)
            }
            .firstFocus(firstFocus, to: firstResult, isTop: isTop, focus: $focus)
            #endif
    }

    /// The first result on the page: the first group's with items once every group above it has failed (a group still
    /// loading above would push it down when it fills).
    private var firstResult: String? {
        guard case .results(let results) = onEnum(of: state.body) else { return nil }
        for group in results.groups {
            if let card = group.items.first { return Self.resultKey(group, card) }
            guard case .failed = onEnum(of: group.status) else { return nil }
        }
        return nil
    }

    /// A result's key in `focus`.
    fileprivate static func resultKey(_ group: SearchGroup, _ card: MetaCard) -> String { "result:\(group.key)|\(card.id)" }

    @ViewBuilder
    private var page: some View {
        #if os(tvOS)
        content
            .searchable(text: $field, prompt: Text(T.shared.browse.search.placeholder))
            .onSubmit(of: .search, onSubmitQuery)
        #else
        content
            .screenHeader(row: {
                SearchField(
                    text: $field,
                    loading: state.searching,
                    focused: $fieldFocused,
                    onSubmitQuery: onSubmitQuery
                )
            })
            .task { await focusPickedField() }
        #endif
    }

    #if !os(tvOS)
    /// The field takes focus each time the user picks Search (`TortugaNavigator.searchFocus`), as the screen takes the
    /// pick: this runs while the screen is on screen, its field with it, so a pick from another tab is taken as Search
    /// appears and one while it shows at once. No wait: a tap on a recent search or a result before a delayed focus
    /// would have the keyboard come up after it, over what it opened.
    private func focusPickedField() async {
        for await _ in navigator.searchFocus {
            if navigator.takeSearchFocus() { fieldFocused = true }
        }
    }
    #endif

    @ViewBuilder
    private var content: some View {
        Group {
            switch onEnum(of: state.body) {
            case .results(let results):
                Results(
                    groups: results.groups, scroll: scroll, focus: $focus, onRetry: onRetry, onLoadMore: onLoadMore,
                    onOpenItem: onOpenItem, onItemMenu: onItemMenu
                )
            case .offline(let offline):
                // Another query's results open at the top.
                OfflineStateView(state: offline.offline, onOpenDownloads: onOpenDownloads).onAppear { scroll.y = 0 }
            case .recent(let recent):
                RecentSearches(
                    recent: recent.recent,
                    focus: $focus,
                    onPick: { index in
                        if index < recent.recent.count { field = recent.recent[index].query }
                        onPickRecent(index)
                    },
                    onRemove: onRemoveRecent,
                    onClear: onClearRecent
                )
                .onAppear { scroll.y = 0 }
            case .hint:
                EmptyStateView(message: T.shared.browse.search.hint, systemImage: "magnifyingglass").onAppear { scroll.y = 0 }
            case .loading:
                LoadingStateView().onAppear { scroll.y = 0 }
            case .empty(let empty):
                EmptyStateView(message: empty.text, systemImage: "magnifyingglass").onAppear { scroll.y = 0 }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

#if !os(tvOS)
/// The search field (iOS, macOS), the screen's header row (`screenHeader`): the magnifier, the text, a spinner while
/// results load and a clear button that keeps focus in the field. iOS: a capsule as wide as the recent searches'
/// column, as the system's search field is, on iOS 26 and later in the header row's glass, as the filters and Home's
/// shortcuts are (`glassCapsule`), before it the surface fill; the Mac: a glass capsule as the window toolbar's controls
/// are, as wide as a column of text.
private struct SearchField: View {
    @Binding var text: String
    let loading: Bool
    let focused: FocusState<Bool>.Binding
    let onSubmitQuery: () -> Void

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        let line = HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Palette.onSurfaceVariant)
                .accessibilityHidden(true)
            TextField(T.shared.browse.search.placeholder, text: $text)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                // First in the capsule's accessibility order, the clear button after it, as iOS 26 lists them: the
                // accessibility hit test takes the last element under a point, and iOS 27 lists the field last, its
                // frame the whole capsule, so it found the field over the clear button (not hittable to XCUITest,
                // though a tap reached it, 17 Oct).
                .accessibilitySortPriority(1)
                #endif
                .focused(focused)
                .onSubmit(onSubmitQuery)
                .accessibilityIdentifier("search.field")
            if loading {
                ProgressView()
                    .controlSize(.small)
            }
            if !text.isEmpty {
                Button {
                    text = ""
                    focused.wrappedValue = true
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .hitTargetLabel()
                }
                .buttonStyle(.borderless)
                #if os(macOS)
                .tooltip(T.shared.browse.search.clearQuery)
                #endif
                .accessibilityLabel(T.shared.browse.search.clearQuery)
                .accessibilityIdentifier("search.clear")
                // Reaching past the capsule's height, which stays the system field's.
                .hitTarget()
            }
        }
        #if os(macOS)
        line
            .padding(.horizontal, HeaderMetrics.capsuleInset)
            .frame(minHeight: HeaderMetrics.controlHeight)
            .frame(minWidth: Self.minWidth, idealWidth: Self.width, maxWidth: Self.width)
            .glassCapsule()
        #else
        Group {
            let capsule = line
                .padding(.horizontal, 12)
                .frame(minHeight: Self.height)
            if #available(iOS 26, *) {
                capsule.glassCapsule()
            } else {
                capsule.background(Palette.surfaceContainer, in: Capsule())
            }
        }
        // The field's sort priority orders the capsule's elements, not the header row's.
        .accessibilityElement(children: .contain)
        .frame(maxWidth: dimens.contentMaxWidth)
        .frame(maxWidth: .infinity)
        #endif
    }

    #if os(macOS)
    /// The field's width in the header row, and the least it narrows to in a narrow window.
    private static let width: CGFloat = 480
    private static let minWidth: CGFloat = 200
    #else
    /// The system search field's height.
    private static let height: CGFloat = 38
    #endif
}
#endif

/// The recent searches, newest first, as plain rows (on tvOS they turn white with focus): each searches again or is
/// removed; the heading's button clears them all.
private struct RecentSearches: View {
    let recent: [RecentSearch]
    let focus: FocusState<String?>.Binding
    let onPick: (Int) -> Void
    let onRemove: (Int) -> Void
    let onClear: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Self.rowGap) {
                HStack(alignment: .firstTextBaseline) {
                    Text(T.shared.browse.search.recent)
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: dimens.gutter)
                    Button(action: onClear) {
                        Text(T.shared.browse.search.clearRecent).hitTargetLabel()
                    }
                        #if os(tvOS)
                        .pillButtonStyle()
                        #else
                        .buttonStyle(.borderless)
                        #endif
                        .accessibilityIdentifier("search.recent.clear")
                        .focused(focus, equals: "recent:clear")
                        .hitTarget()
                }
                .padding(.bottom, 4)
                ForEach(Array(recent.enumerated()), id: \.element.query) { index, item in
                    let query = item.query
                    HStack(spacing: 8) {
                        Button {
                            onPick(index)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "clock.arrow.circlepath")
                                    .foregroundStyle(.secondary)
                                    .accessibilityHidden(true)
                                Text(query)
                                    .lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, Self.rowInset)
                            .padding(.vertical, Self.rowPadding)
                            .contentShape(Rectangle())
                        }
                        #if os(tvOS)
                        .buttonStyle(SurfaceButtonStyle(fill: .clear, radius: Radius.medium))
                        #else
                        .buttonStyle(PressDimButtonStyle())
                        #endif
                        .accessibilityIdentifier("search.recent.\(index)")
                        .focused(focus, equals: "recent:\(query)")
                        #if os(tvOS)
                        IconButton(systemImage: "xmark", label: item.removeLabel, id: "search.recent.\(index).remove") {
                            onRemove(index)
                        }
                        #else
                        Button {
                            onRemove(index)
                        } label: {
                            Image(systemName: "xmark")
                                .foregroundStyle(Palette.onSurfaceVariant)
                                .padding(8)
                                .hitTargetLabel()
                        }
                        .buttonStyle(.borderless)
                        #if os(macOS)
                        .tooltip(item.removeLabel)
                        #endif
                        .accessibilityLabel(item.removeLabel)
                        .accessibilityIdentifier("search.recent.\(index).remove")
                        .hitTarget()
                        #endif
                    }
                    #if !os(tvOS)
                    .overlay(alignment: .bottom) { Divider().padding(.leading, 32) }
                    #endif
                }
            }
            #if os(tvOS)
            // Clear's and the remove circles' glass, one column of it.
            .glassGroup(spacing: Self.rowGap)
            #endif
            .frame(maxWidth: dimens.contentMaxWidth)
            .padding(sides.insets)
            // Room for the focused row to grow (tvOS).
            .padding(.vertical, dimens.focusRoom + 8)
            .frame(maxWidth: .infinity)
        }
        #if os(tvOS)
        // The focused row grows past the scroll view's edges.
        .scrollClipDisabled()
        #endif
    }

    #if os(tvOS)
    private static let rowGap: CGFloat = 8
    private static let rowInset: CGFloat = 24
    private static let rowPadding: CGFloat = 14
    #else
    private static let rowGap: CGFloat = 0
    private static let rowInset: CGFloat = 0
    private static let rowPadding: CGFloat = 10
    #endif
}

/// The results, one shelf per catalog in addon order: its items, its failure with retry, or a loading row.
private struct Results: View {
    let groups: [SearchGroup]
    let scroll: ScrollMemory
    let focus: FocusState<String?>.Binding
    let onRetry: () -> Void
    let onLoadMore: (Int) -> Void
    let onOpenItem: (MetaCard) -> Void
    let onItemMenu: (MetaCard, MediaAction) -> Void

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: dimens.sectionSpacing) {
                ForEach(Array(groups.enumerated()), id: \.element.key) { groupIndex, group in
                    if !group.items.isEmpty {
                        let loadingMore = if case .ready(let ready) = onEnum(of: group.status) { ready.loadingMore } else { false }
                        ShelfRow(title: group.title, items: Array(group.items.enumerated()), id: \.element.id, loading: loadingMore) {
                            AddonLabel(name: group.addonName)
                        } content: { index, card in
                            MetaPoster(
                                card: card,
                                accessibilityId: "search.group.\(groupIndex).item.\(index)",
                                focus: CardFocus(binding: focus, key: SearchScreen.resultKey(group, card)),
                                action: { onOpenItem(card) },
                                onMenu: { onItemMenu(card, $0) }
                            )
                            .nearShelfEnd(index, of: group.items.count) { onLoadMore(groupIndex) }
                        }
                    } else if case .failed(let failed) = onEnum(of: group.status) {
                        FailedShelf(
                            title: group.title,
                            message: failed.text,
                            retryId: "search.group.\(groupIndex).retry",
                            onRetry: onRetry
                        )
                    } else {
                        LoadingShelf(title: group.title, id: "search.group.\(groupIndex).loading") { AddonLabel(name: group.addonName) }
                    }
                }
            }
            .padding(.vertical, dimens.gutter)
        }
        .scrollMemory(scroll)
    }
}
