import SwiftUI
import TortugaKit

/// The Board route of `app`: its `BoardStore` is kept by the navigator while the Board is on the back stack (it is the
/// root, under "board" as in Compose), so coming back from Detail or another tab finds the rows loaded as they were,
/// scrolled where they were.
struct BoardRoute: View {
    let app: AppModel
    let route: any Route

    var body: some View {
        if let model = retained() {
            let session = model.store
            BoardScreen(
                state: model.observer.state,
                memory: session.focus,
                scroll: session.scroll,
                isTop: app.isTop(route),
                onRowVisible: { session.store.rowVisible(index: Int32($0)) },
                onRetryRow: { session.store.retryRow(index: Int32($0)) },
                onLoadMore: { session.store.loadNextPage(index: Int32($0)) },
                onSeeAll: { session.store.seeAll(index: Int32($0)) },
                onOpenItem: { session.store.openItem(card: $0) },
                onOpenContinueWatching: { session.store.openContinueWatching(card: $0) },
                onItemMenu: { session.store.menuAction(card: $0, action: $1) },
                onContinueWatchingMenu: { session.store.continueWatchingAction(card: $0, action: $1) },
                onOpenDownloads: session.store.openDownloads,
                onOpenShortcut: { session.store.openShortcut(key: $0) },
                onOpenFolder: { session.store.openFolder(rowIndex: Int32($0), folderId: $1) }
            )
            .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<BoardSession, BoardUiState>? {
        let app = app
        return app.retained(
            route,
            key: "board",
            create: { BoardSession(store: app.session.boardStore()) },
            state: { $0.store.state },
            release: { $0.store.release() }
        )
    }
}

/// The Board's store, the view's focus memory and where the shelves were scrolled, kept together: the memory outlives
/// the Board's view (the Mac's sidebar builds a destination's stack again each time it shows it).
@MainActor
final class BoardSession {
    let store: BoardStore
    let focus = BoardFocusMemory()
    let scroll = ScrollMemory()

    init(store: BoardStore) {
        self.store = store
    }
}

/// Where focus was on the Board (tvOS): the first card takes focus once, when the Board first has one, unless the user
/// has moved focus while the Board was on top before it came (`FirstFocus`); later the card or shortcut that was opened
/// gets it back.
@MainActor
final class BoardFocusMemory {
    let firstFocus = FirstFocus(pending: true)
    var last: String?
}

/// The home screen: Continue Watching with progress bars, a series' card counting its new episodes
/// in its corner, then Home's shortcuts (`BoardShortcuts`: Watch together, Downloads, Library, Calendar and the
/// trackers' lists as chips, `onOpenShortcut` with the shortcut's key; at the
/// top of the shelves without Continue Watching), then one shelf per catalog. Nothing sits above the first of them: no
/// header and no buttons on any device. The profile is at the foot of iPad's and the Mac's sidebar; iPhone and Apple TV
/// switch it from Settings' profile card. A shelf reports itself with `onRowVisible` as it comes near the screen (the
/// store loads rows lazily); each shows its own loading, failure (`onRetryRow`) and items, with See all (`onSeeAll`)
/// opening its catalog in Discover where the row has it (`BoardRow.seeAll`: in its heading, or on tvOS a card after its
/// items), and asks for its next page as its end comes into view (`onLoadMore`), the heading's progress indicator
/// showing while it comes. On tvOS and wide windows the backdrop of the focused item fades in behind the top of the page,
/// dimmed (`Backdrop`). Row intents take the index of the row in `BoardUiState.rows` as rendered. Offline, the catalogs
/// give way to the offline state (`OfflineStateView`: "Go to Downloads", `onOpenDownloads`, where the device has
/// downloads) under Continue Watching and the shortcuts. The shelves open where they were last scrolled to (`scroll`).
/// A collection's row (`BoardRow.folders`) is a shelf of its folders' tiles (`FolderTileCard`, `board.row.<r>.folder.<i>`)
/// in the collection's one shape, its source "Collection" beside its title; a tile opens its folder (`onOpenFolder` with
/// the row's index and the folder's id), and coming back focus is on that tile (tvOS). On tvOS a Home whose first row is
/// a collection opens with focus on its first tile.
///
/// tvOS: the first card takes focus when the Board first shows one, unless the user has moved focus on the Board before
/// (to the tab bar, onto a shortcut; moves on a screen over it don't count); coming back from a screen opened on top
/// (Detail, Watch together), focus goes back to the card or shortcut that had it, a See all card too, unless the user
/// moves first. Down from Continue Watching lands on the first shortcut, Down from the shortcuts on the next shelf, and
/// Up from that shelf on the first shortcut again. A See all card opens Discover's tab, as the heading's See all did:
/// Menu there moves focus to the tab bar, as on every tab's first screen (on Android TV Back returns to the card).
/// Showing the Home tab again leaves focus on the tab bar, and Up from the top row reaches it.
struct BoardScreen: View {
    let state: BoardUiState
    let memory: BoardFocusMemory
    let scroll: ScrollMemory
    /// The Board is the top screen of its stack.
    let isTop: Bool
    let onRowVisible: (Int) -> Void
    let onRetryRow: (Int) -> Void
    let onLoadMore: (Int) -> Void
    let onSeeAll: (Int) -> Void
    let onOpenItem: (MetaCard) -> Void
    let onOpenContinueWatching: (ContinueWatchingCard) -> Void
    /// A catalog card's menu entry picked (`MetaCard.menu`).
    let onItemMenu: (MetaCard, MediaAction) -> Void
    /// A Continue Watching card's menu entry picked (`ContinueWatchingCard.menu`).
    let onContinueWatchingMenu: (ContinueWatchingCard, MediaAction) -> Void
    let onOpenDownloads: () -> Void
    /// A shortcut picked, by its key (`BoardShortcut.key`).
    let onOpenShortcut: (String) -> Void
    /// A collection's folder tile picked: the row's index and the folder's id.
    let onOpenFolder: (Int, String) -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.posterTemplate) private var posterTemplate
    @FocusState private var focus: String?
    @State private var backdrop: String?
    @State private var returns = 0
    /// The user's moves (`FocusRestore.userMoves`) at the last return: a move since ends that return's restore.
    @State private var returnMoves = 0

    var body: some View {
        content
            // Behind the page as a background, whose size never sets the screen's: the backdrop is a share of the
            // screen's height, and laid out beside the shelves it made that share the screen's minimum height (a Mac
            // window unzoomed from 985 pt tall came back no shorter than 734).
            .background(alignment: .top) {
                if formFactor != .compact {
                    Backdrop(url: backdrop)
                }
            }
            // The window's title (the Mac's Window menu); the tabs and the sidebar name the screen (`destinationChrome`).
            .navigationTitle(TopLevelDestination.board.label)
            #if os(tvOS)
            .firstFocus(memory.firstFocus, to: firstCard, isTop: isTop, focus: $focus, hold: 1.5)
            #endif
    }

    /// Home's shortcuts, nothing while there are none.
    @ViewBuilder
    private var shortcuts: some View {
        if !state.shortcuts.isEmpty {
            BoardShortcuts(shortcuts: state.shortcuts, focus: $focus, onOpen: onOpenShortcut)
        }
    }

    /// The shelves, or what takes their place (`BoardBody`).
    @ViewBuilder
    private var content: some View {
        switch onEnum(of: state.body) {
        case .offline(let offline):
            offlinePage(offline.offline)
        case .loading:
            LoadingStateView()
        case .empty(let empty):
            EmptyStateView(message: empty.text)
        case .rows(let rows):
            shelves(rows.rows)
        }
    }

    /// The catalogs' rows; none while something else takes their place.
    private var rows: [BoardRow] {
        if case .rows(let rows) = onEnum(of: state.body) { return rows.rows }
        return []
    }

    /// Offline: Continue Watching (to play what is downloaded) and the shortcuts above the offline state, in place of the
    /// catalogs; without Continue Watching the offline state takes the rest of the screen, its message in the middle.
    @ViewBuilder
    private func offlinePage(_ offline: OfflineState) -> some View {
        if state.continueWatching.isEmpty {
            VStack(spacing: 0) {
                shortcuts
                    .padding(.top, dimens.headingGap)
                OfflineStateView(state: offline, onOpenDownloads: onOpenDownloads)
            }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: dimens.sectionSpacing) {
                    continueWatching
                    shortcuts
                    OfflineStateView(state: offline, onOpenDownloads: onOpenDownloads)
                }
                .padding(.top, dimens.headingGap)
                .padding(.bottom, dimens.sectionSpacing)
            }
        }
    }

    private func shelves(_ rows: [BoardRow]) -> some View {
        ScrollView {
            shelfStack(rows)
                .padding(.top, dimens.headingGap)
                .padding(.bottom, dimens.sectionSpacing)
        }
        .scrollMemory(scroll)
        .onChange(of: focus) { _, key in
            guard let key else { return }
            memory.last = key
            if let card = card(key) { backdrop = card.backgroundUrl }
        }
        #if os(tvOS)
        .onChange(of: isTop) { wasTop, top in
            guard top, !wasTop else { return }
            returns += 1
            returnMoves = FocusRestore.userMoves
        }
        .task(id: returns) {
            guard returns > 0 else { return }
            await FocusRestore.restore($focus, to: memory.last.flatMap { isShown($0) ? $0 : nil } ?? firstCard, since: returnMoves)
        }
        #endif
    }

    /// Continue Watching, Home's shortcuts and the catalogs' shelves.
    private func shelfStack(_ rows: [BoardRow]) -> some View {
        LazyVStack(alignment: .leading, spacing: dimens.sectionSpacing) {
            if !state.continueWatching.isEmpty {
                continueWatching
            }
            shortcuts
            ForEach(Array(rows.enumerated()), id: \.element.key) { index, row in
                Group {
                    if row.folders.isEmpty {
                        CatalogShelf(
                            row: row,
                            index: index,
                            focus: $focus,
                            onRetry: { onRetryRow(index) },
                            onLoadMore: { onLoadMore(index) },
                            onSeeAll: { onSeeAll(index) },
                            onOpenItem: onOpenItem,
                            onItemMenu: onItemMenu
                        )
                    } else {
                        FolderShelf(row: row, index: index, focus: $focus) { onOpenFolder(index, $0) }
                    }
                }
                // Built means on screen or about to be: the store loads it and keeps the row shown after it requested,
                // whichever row that becomes. A loaded row reports too, for the row after it: requested only once built
                // itself, that row would scroll in as a loading shelf.
                .task(id: row.status is RowStatusIdle) { onRowVisible(index) }
            }
        }
    }

    private var continueWatching: some View {
        ShelfRow(title: T.shared.browse.continueWatching.title, items: Array(state.continueWatching.enumerated()), id: \.element.id) { index, card in
            let poster = posterTemplate.poster(type: card.type, id: card.id, imdbId: nil, addon: card.posterUrl)
            PosterCard(
                title: card.title,
                imageURL: poster.url,
                fallbackImageURL: poster.fallback,
                shape: card.posterShape,
                subtitle: card.detail,
                progress: card.shownProgress?.doubleValue,
                badge: card.badge,
                badgeDescription: card.badgeDescription,
                accessibilityId: "board.cw.\(index)",
                focus: CardFocus(binding: $focus, key: Self.key(card)),
                menu: card.menu,
                onMenu: { onContinueWatchingMenu(card, $0) },
                action: { onOpenContinueWatching(card) }
            )
            #if os(tvOS)
            // The remote's Play/Pause plays the card, as Select does; its menu is on a held Select.
            .onPlayPauseCommand { onOpenContinueWatching(card) }
            #endif
        }
    }

    /// The first card on the page: Continue Watching's, else the first row's first tile or card once every row above it
    /// has failed (a row still loading above would push it down when it fills).
    private var firstCard: String? {
        if let card = state.continueWatching.first { return Self.key(card) }
        for row in rows {
            if let tile = row.folders.first { return Self.key(row, tile) }
            if let card = row.items.first { return Self.key(row, card) }
            guard case .failed = onEnum(of: row.status) else { return nil }
        }
        return nil
    }

    /// The catalog item whose focus key is `key`.
    private func card(_ key: String) -> MetaCard? {
        for row in rows where key.hasPrefix("\(row.key)|") {
            return row.items.first { Self.key(row, $0) == key }
        }
        return nil
    }

    /// Whether what the focus key `key` names is on the page: a catalog item, a folder's tile, Continue Watching's, a
    /// See all card, a shortcut.
    private func isShown(_ key: String) -> Bool {
        card(key) != nil
            || rows.contains { row in row.folders.contains { Self.key(row, $0) == key } }
            || state.continueWatching.contains { Self.key($0) == key }
            || rows.contains { $0.seeAll == .card && Self.seeAllKey($0) == key }
            || state.shortcuts.contains { BoardShortcuts.focusKey($0) == key }
    }

    fileprivate static func key(_ card: ContinueWatchingCard) -> String { "cw:\(card.id)" }

    fileprivate static func key(_ row: BoardRow, _ card: MetaCard) -> String { "\(row.key)|\(card.id)" }

    fileprivate static func key(_ row: BoardRow, _ tile: FolderTile) -> String { "\(row.key)|folder:\(tile.id)" }

    fileprivate static func seeAllKey(_ row: BoardRow) -> String { "seeAll:\(row.key)" }
}

/// A collection's row: its title and "Collection" (`sourceName`, beside it where a catalog row's addon is), then a tile
/// per folder in the collection's shape (`BoardRow.folderShape`), scrolling sideways as any shelf; no See all. A tile
/// runs `onOpen` with its folder's id.
private struct FolderShelf: View {
    let row: BoardRow
    let index: Int
    let focus: FocusState<String?>.Binding
    let onOpen: (String) -> Void

    var body: some View {
        ShelfRow(title: row.title, items: Array(row.folders.enumerated()), id: \.element.id) {
            AddonLabel(name: row.sourceName)
                .accessibilityIdentifier("board.row.\(index).source")
        } content: { position, tile in
            FolderTileCard(
                tile: tile,
                shape: row.folderShape,
                accessibilityId: "board.row.\(index).folder.\(position)",
                focus: CardFocus(binding: focus, key: BoardScreen.key(row, tile)),
                action: { onOpen(tile.id) }
            )
        }
    }
}

/// One catalog: its items with See all where it has it (`BoardRow.seeAll`: in the heading, or a `SeeAllCard` after the
/// items) and its source (`board.row.<index>.source`: the addon or tracker service), a failure with retry, or a loading
/// row.
private struct CatalogShelf: View {
    let row: BoardRow
    let index: Int
    let focus: FocusState<String?>.Binding
    let onRetry: () -> Void
    let onLoadMore: () -> Void
    let onSeeAll: () -> Void
    let onOpenItem: (MetaCard) -> Void
    let onItemMenu: (MetaCard, MediaAction) -> Void

    var body: some View {
        if let last = row.items.last {
            // Reloading, or loading the next page, keeps the items on screen.
            ShelfRow(title: row.title, items: Array(row.items.enumerated()), id: \.element.id, loading: busy) {
                HStack(spacing: 16) {
                    AddonLabel(name: row.sourceName, onPhones: row.fromTracker)
                        .accessibilityIdentifier("board.row.\(index).source")
                    if row.seeAll == .heading {
                        HeadingButton(title: T.shared.browse.board.seeAll, id: "board.row.\(index).seeAll", action: onSeeAll)
                    }
                }
            } content: { position, card in
                MetaPoster(
                    card: card,
                    accessibilityId: "board.row.\(index).item.\(position)",
                    focus: CardFocus(binding: focus, key: BoardScreen.key(row, card)),
                    action: { onOpenItem(card) },
                    onMenu: { onItemMenu(card, $0) }
                )
                .nearShelfEnd(position, of: row.items.count, perform: onLoadMore)
            } end: {
                if row.seeAll == .card {
                    SeeAllCard(
                        shape: last.posterShape,
                        accessibilityId: "board.row.\(index).seeAllCard",
                        focus: CardFocus(binding: focus, key: BoardScreen.seeAllKey(row)),
                        action: onSeeAll
                    )
                }
            }
        } else if case .failed(let failed) = onEnum(of: row.status) {
            FailedShelf(title: row.title, message: failed.text, retryId: "board.row.\(index).retry", onRetry: onRetry)
        } else {
            LoadingShelf(title: row.title, id: "board.row.\(index).loading") { AddonLabel(name: row.sourceName, onPhones: row.fromTracker) }
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

/// The card ending a row cut short on tvOS (`BoardRow.seeAll` `.card`): a chevron over "See all" on the cards' fill,
/// as large as the artwork of the row's last card (`shape`), focused and lifted as the cards are. It opens the row's
/// catalog in Discover. Accessibility identifier `board.row.<index>.seeAllCard`.
private struct SeeAllCard: View {
    let shape: PosterShape
    let accessibilityId: String
    let focus: CardFocus
    let action: () -> Void

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        let width = dimens.cardWidth(shape)
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: "chevron.forward")
                    .font(.title2.weight(.semibold))
                    .accessibilityHidden(true)
                Text(T.shared.browse.board.seeAll)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(Palette.onBackground)
            .frame(width: width, height: width / shape.aspectRatio)
            .background(Palette.surfaceContainerHigh)
            .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
            .focusRing(radius: Radius.medium)
        }
        .cardButtonStyle()
        .accessibilityIdentifier(accessibilityId)
        .focused(focus.binding, equals: focus.key)
    }
}

/// The backdrop of the focused item, in colour and faint (30 %), fading into the background towards the bottom and
/// the leading edge so the shelves stay readable: over it the first shelf's heading and source read as clearly as they
/// did over the backdrop in grey at 35 % (a saturated picture is brighter in colour than in grey), and Home's shortcuts
/// sit on their own opaque fill. It follows focus once focus rests (a remote held down doesn't load every image). It
/// runs under iPad's and the Mac's sidebar without `DetailBackdrop`'s dim: its fade from the leading edge, over the
/// picture at 30 %, is darker than that dim wherever the dim reaches.
struct Backdrop: View {
    let url: String?

    @State private var shown: String?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let shown {
                    RemoteImage(url: shown, alignment: .topTrailing)
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .opacity(0.3)
                        .transition(.opacity)
                        .id(shown)
                }
                LinearGradient(
                    stops: [.init(color: Palette.background.opacity(0), location: 0.3), .init(color: Palette.background, location: 1)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                LinearGradient(
                    stops: [.init(color: Palette.background, location: 0), .init(color: Palette.background.opacity(0), location: 0.55)],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            }
        }
        .containerRelativeFrame(.vertical) { height, _ in height * 0.7 }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: url) {
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.6)) { shown = url }
        }
    }
}
