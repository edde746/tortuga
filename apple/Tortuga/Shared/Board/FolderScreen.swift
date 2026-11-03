import SwiftUI
import TortugaKit

/// A collection's folder (`RouteCollectionFolder`), opened from its tile on Home: its `FolderStore` lives while the
/// route is on the back stack. Once the folder is deleted (elsewhere: another device, the editor) the screen goes back.
struct CollectionFolderRoute: View {
    let app: AppModel
    let route: RouteCollectionFolder

    /// Popped, the page draws what it showed while it slides out (`LastDrawn`).
    @State private var drawn = LastDrawn<RetainedStore<FolderStore, FolderUiState>>()

    var body: some View {
        if let model = drawn(retained()) {
            let state = model.observer.state
            FolderScreen(state: state, intents: FolderIntents(model.store), isTop: app.isTop(route))
                .task { await model.observer.observe() }
                .onChange(of: state.gone, initial: true) { _, gone in
                    if gone { _ = app.navigator.pop(route: route) }
                }
        }
    }

    private func retained() -> RetainedStore<FolderStore, FolderUiState>? {
        let session = app.session
        let route = route
        return app.retained(
            route,
            key: FolderStore.companion.KEY,
            create: { session.folderStore(route: route) },
            state: { $0.state },
            release: { $0.release() }
        )
    }
}

/// What the folder screen sends its store; row and tab intents take the row's or the tab's index.
struct FolderIntents {
    let selectTab: (Int) -> Void
    let rowVisible: (Int) -> Void
    let loadNextPage: (Int) -> Void
    let gridNearEnd: () -> Void
    let retry: (Int) -> Void
    let openItem: (MetaCard) -> Void
    let menuAction: (MetaCard, MediaAction) -> Void
    let noteAction: (Int) -> Void

    init(_ store: FolderStore) {
        selectTab = { store.selectTab(index: Int32($0)) }
        rowVisible = { store.rowVisible(index: Int32($0)) }
        loadNextPage = { store.loadNextPage(index: Int32($0)) }
        gridNearEnd = { store.gridNearEnd() }
        retry = { store.retry(index: Int32($0)) }
        openItem = { store.openItem(card: $0) }
        menuAction = { store.menuAction(card: $0, action: $1) }
        noteAction = { store.noteAction(index: Int32($0)) }
    }
}

/// A folder of a collection: the collection's backdrop behind the top when it has one (Home's, faded into the page),
/// the folder's title as the page's, then its sources as the collection shows them (`FolderUiState.layout`):
/// - Tabs: a row of chips, one per tab (All first where the folder has it), the selected one in the primary fill, over
///   the selected tab's poster grid, which asks for more as its end comes into view, as Discover's does; a folder with
///   one tab has no chips. A chip picks its tab, whose grid starts at its top.
/// - Rows: a shelf per source, as Home's: loading, failed (Retry), or its cards, paging as their end comes into view.
///
/// A source that can't show (an addon not installed, a tracker not connected) shows its note (`FolderNote`) in place of
/// its cards, with the note's action where it has one (Go to Trackers, Go to Addons). A card's menu is the media menu,
/// as on Home. tvOS: the screen opens on the first card, else the selected chip; the chips' row is a focus section, so
/// Up from the grid's top row reaches it and Down returns to the grid; coming back from a Detail, focus goes back to the
/// card that opened it; Menu leaves the screen.
///
/// Accessibility identifiers (the Compose UI's names): `folder.tab.<i>`, `folder.grid.item.<i>`,
/// `folder.grid.note` (with `.action`), `folder.grid.failed`, `folder.grid.retry` (a later page's),
/// `folder.row.<r>.item.<i>`, `folder.row.<r>.source`, `folder.row.<r>.note` (with `.action`), `folder.row.<r>.retry`.
struct FolderScreen: View {
    let state: FolderUiState
    let intents: FolderIntents
    /// The folder is the top screen of its stack.
    let isTop: Bool

    @FocusState private var focus: String?
    @State private var lastFocused: String?
    #if os(tvOS)
    @State private var returns = 0
    /// The user's moves (`FocusRestore.userMoves`) at the last return: a move since ends that return's restore.
    @State private var returnMoves = 0
    /// The user's moves as the screen opened: the first focus waits for none since.
    @State private var openMoves: Int?
    #endif

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(alignment: .top) {
                if let backdrop = state.backdropUrl { Backdrop(url: backdrop) }
            }
            .pageTitle(state.title)
            .onChange(of: focus) { _, key in
                if let key { lastFocused = key }
            }
            #if os(tvOS)
            .task(id: firstFocus) {
                if openMoves == nil { openMoves = FocusRestore.userMoves }
                await FocusRestore.restore($focus, to: firstFocus, since: openMoves, replacing: true)
            }
            .onChange(of: isTop) { wasTop, top in
                guard top, !wasTop else { return }
                returns += 1
                returnMoves = FocusRestore.userMoves
            }
            .task(id: returns) {
                guard returns > 0 else { return }
                await FocusRestore.restore($focus, to: lastFocused ?? firstFocus, since: returnMoves)
            }
            #endif
    }

    @ViewBuilder
    private var content: some View {
        switch state.layout {
        case .tabs:
            VStack(spacing: 0) {
                if state.tabs.count > 1 {
                    FolderTabs(tabs: state.tabs, selected: Int(state.selectedTab), focus: $focus, onSelect: intents.selectTab)
                }
                if let grid = state.grid {
                    TabGrid(grid: grid, tab: Int(state.selectedTab), focus: $focus, intents: intents)
                        // A tab's grid of its own: another tab's starts at its top.
                        .id(state.selectedTab)
                }
            }
        case .rows:
            SourceRows(rows: state.rows, focus: $focus, intents: intents)
        }
    }

    /// Where focus goes as the screen opens: the first card, else the selected chip.
    private var firstFocus: String? {
        switch state.layout {
        case .tabs:
            if let card = state.grid?.items.first { return Self.gridKey(card) }
            return state.tabs.count > 1 ? Self.tabKey(Int(state.selectedTab)) : nil
        case .rows:
            for row in state.rows {
                if let card = row.items.first { return Self.rowKey(row, card) }
            }
            return nil
        }
    }

    fileprivate static func tabKey(_ index: Int) -> String { "tab:\(index)" }

    fileprivate static func gridKey(_ card: MetaCard) -> String { "item:\(card.id)" }

    fileprivate static func rowKey(_ row: FolderRow, _ card: MetaCard) -> String { "\(row.key)|\(card.id)" }
}

/// The tabs as chips in one row that scrolls sideways when they don't fit, the selected one in the primary fill.
private struct FolderTabs: View {
    let tabs: [FolderTab]
    let selected: Int
    let focus: FocusState<String?>.Binding
    let onSelect: (Int) -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: formFactor == .tv ? 16 : 8) {
                ForEach(Array(tabs.enumerated()), id: \.element.key) { index, tab in
                    TabChip(title: tab.title, selected: index == selected, id: "folder.tab.\(index)") { onSelect(index) }
                        .focused(focus, equals: FolderScreen.tabKey(index))
                }
            }
            .padding(sides.insets)
            // Room for the focus ring (keyboard focus), which the row doesn't clip.
            .padding(.vertical, dimens.focusRoom)
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .padding(.vertical, 4)
        .shelfFocusSection()
        #if os(tvOS)
        .defaultFocus(focus, FolderScreen.tabKey(selected), priority: .userInitiated)
        #endif
    }
}

/// A tab's chip: its title in the chips' size and font (`ChipMetrics`); selected, the primary fill (on tvOS, where
/// focus fills a chip with the primary colour, a lighter fill and the semibold title), else the cards' surface.
private struct TabChip: View {
    let title: String
    let selected: Bool
    let id: String
    let action: () -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        let height = ChipMetrics.height(formFactor)
        Button(action: action) {
            Text(title)
                .chipName(maxWidth: ChipMetrics.maxNameWidth(formFactor))
                .font(ChipMetrics.font(formFactor).weight(selected ? .semibold : .regular))
                #if !os(tvOS)
                .foregroundStyle(selected ? Palette.onPrimary : Palette.onBackground)
                #endif
                .padding(.horizontal, ChipMetrics.inset(formFactor))
                .frame(minHeight: height)
                .contentShape(Capsule())
        }
        .buttonStyle(SurfaceButtonStyle(fill: fill, radius: height / 2))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    private var fill: Color {
        #if os(tvOS)
        selected ? Palette.surfaceContainerHighest : Palette.surfaceContainer
        #else
        selected ? Palette.primary : Palette.surfaceContainer
        #endif
    }
}

/// The selected tab's titles as a poster grid, or its note, its loading or its failure.
private struct TabGrid: View {
    let grid: FolderGrid
    let tab: Int
    let focus: FocusState<String?>.Binding
    let intents: FolderIntents

    var body: some View {
        if let note = grid.note {
            EmptyStateView(message: note.text, systemImage: note.action.systemImage) {
                NoteButton(note: note, id: "folder.grid.note.action") { intents.noteAction(tab) }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("folder.grid.note")
        } else if !grid.items.isEmpty {
            ItemGrid(grid: grid, focus: focus, intents: intents) { intents.retry(tab) }
        } else {
            switch onEnum(of: grid.status) {
            case .failed(let failed):
                ErrorStateView(message: failed.text) { intents.retry(tab) }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("folder.grid.failed")
            // A source with no titles comes with its note.
            case .ready:
                EmptyView()
            case .idle, .loading:
                LoadingStateView()
            }
        }
    }
}

/// The posters of every page loaded, in as many columns of the card width as fit; the next page is asked for once a
/// poster near the end comes into view, and under the grid a spinner while it comes, or why it failed with Retry.
private struct ItemGrid: View {
    let grid: FolderGrid
    let focus: FocusState<String?>.Binding
    let intents: FolderIntents
    let onRetry: () -> Void

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
                                accessibilityId: "folder.grid.item.\(index)",
                                focus: CardFocus(binding: focus, key: FolderScreen.gridKey(card)),
                                action: { intents.openItem(card) },
                                onMenu: { intents.menuAction(card, $0) }
                            )
                            .onAppear {
                                if index >= grid.items.count - Int(RowPagesKt.SHELF_LOAD_AHEAD) { intents.gridNearEnd() }
                            }
                        }
                    }
                    .padding(sides.insets)
                    // Room for the focused poster to lift (tvOS) or its focus ring.
                    .padding(.vertical, dimens.focusRoom + 4)
                    footer
                }
            }
        }
    }

    @ViewBuilder
    private var footer: some View {
        switch onEnum(of: grid.status) {
        case .loading:
            GridFooter(error: nil, onRetry: onRetry)
        case .ready(let ready):
            if ready.loadingMore { GridFooter(error: nil, onRetry: onRetry) }
        case .failed(let failed):
            GridFooter(error: failed.text, onRetry: onRetry)
        case .idle:
            EmptyView()
        }
    }
}

/// Under the grid: the spinner while the next page comes, or why it failed and Retry (`folder.grid.retry`).
private struct GridFooter: View {
    let error: String?
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 16) {
            if let error {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
                ActionButton(title: T.shared.browse.retry, id: "folder.grid.retry", systemImage: "arrow.clockwise", fullWidth: false, action: onRetry)
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

/// The folder's sources as Home's shelves, each reporting itself as it comes near the screen (the store loads them
/// lazily, as Home's rows).
private struct SourceRows: View {
    let rows: [FolderRow]
    let focus: FocusState<String?>.Binding
    let intents: FolderIntents

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: dimens.sectionSpacing) {
                ForEach(Array(rows.enumerated()), id: \.element.key) { index, row in
                    SourceRow(row: row, index: index, focus: focus, intents: intents)
                        .task(id: row.status is RowStatusIdle) { intents.rowVisible(index) }
                }
            }
            .padding(.top, dimens.headingGap)
            .padding(.bottom, dimens.sectionSpacing)
        }
    }
}

/// One source of a rows folder: its note in a filled row, its cards, its failure with Retry, or a loading shelf.
private struct SourceRow: View {
    let row: FolderRow
    let index: Int
    let focus: FocusState<String?>.Binding
    let intents: FolderIntents

    var body: some View {
        if let note = row.note {
            NoteShelf(title: row.title, note: note, id: "folder.row.\(index).note") { intents.noteAction(index) }
        } else if !row.items.isEmpty {
            ShelfRow(title: row.title, items: Array(row.items.enumerated()), id: \.element.id, loading: busy) {
                AddonLabel(name: row.sourceName)
                    .accessibilityIdentifier("folder.row.\(index).source")
            } content: { position, card in
                MetaPoster(
                    card: card,
                    accessibilityId: "folder.row.\(index).item.\(position)",
                    focus: CardFocus(binding: focus, key: FolderScreen.rowKey(row, card)),
                    action: { intents.openItem(card) },
                    onMenu: { intents.menuAction(card, $0) }
                )
                .nearShelfEnd(position, of: row.items.count) { intents.loadNextPage(index) }
            }
        } else {
            switch onEnum(of: row.status) {
            case .failed(let failed):
                FailedShelf(title: row.title, message: failed.text, retryId: "folder.row.\(index).retry") { intents.retry(index) }
            // A source with no titles comes with its note.
            case .ready:
                EmptyView()
            case .idle, .loading:
                LoadingShelf(title: row.title, id: "folder.row.\(index).loading") { AddonLabel(name: row.sourceName) }
            }
        }
    }

    /// Reloading, or loading the next page.
    private var busy: Bool {
        switch onEnum(of: row.status) {
        case .loading: true
        case .ready(let ready): ready.loadingMore
        case .idle, .failed: false
        }
    }
}

/// A source that can't show, in a rows folder: its heading, then one filled row (a failed shelf's form) with the note's
/// symbol, its text and its action at the end (`<id>.action`). Accessibility identifier `id` on the filled row.
private struct NoteShelf: View {
    let title: String
    let note: FolderNote
    let id: String
    let onAction: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        VStack(alignment: .leading, spacing: dimens.headingGap) {
            Text(title)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 16) {
                Image(systemName: note.action.systemImage)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .accessibilityHidden(true)
                Text(note.text)
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Whole: the note's text wraps beside it instead (an iPhone's row).
                NoteButton(note: note, id: "\(id).action", action: onAction)
                    .fixedSize()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(id)
            #if os(tvOS)
            // One focus group as wide as the row: Down from any card above reaches the action at its end.
            .focusSection()
            #endif
        }
        .padding(sides.insets)
    }
}

/// A note's action (`FolderNote.actionLabel`): Go to Trackers, Go to Addons; nothing for a note without one.
private struct NoteButton: View {
    let note: FolderNote
    let id: String
    let action: () -> Void

    var body: some View {
        if let label = note.actionLabel {
            ActionButton(title: label, id: id, prominence: .primary, fullWidth: false, action: action)
        }
    }
}

private extension Optional where Wrapped == FolderNoteAction {
    /// A note's symbol: a tracker to connect (Settings → Trackers' symbol), else an addon (one missing, or without the
    /// catalog; Settings → Addons' symbol).
    var systemImage: String {
        self == .connectTracker ? "arrow.triangle.2.circlepath" : "puzzlepiece.extension"
    }
}
