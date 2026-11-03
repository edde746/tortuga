import SwiftUI
import TortugaKit

/// The Library destination of `app`: its sections (`AppSession.librarySections`): the profile's library, Continue
/// Watching and, where the device has downloads, Downloads, each with its own store kept by the navigator for the app
/// session (under "library", "continueWatching" and "downloads", as in Compose; Library is a tab), so filters, sort,
/// pages and where the grid was scrolled survive a visit to a title or another tab, and so does the section shown
/// (`LibraryViewState`). Library opens on the section `AppSession.librarySection()` gives (asked for once: one the app
/// asked for, Downloads while offline, else the library) and switches to the one the app asks for while it shows
/// (`TortugaNavigator.librarySection`: the offline states' "Go to Downloads").
struct LibraryRoute: View {
    let app: AppModel

    var body: some View {
        if let library = retained(key: "library", create: { $0.libraryStore() }),
           let continueWatching = retained(key: "continueWatching", create: { $0.continueWatchingStore() }),
           let view = viewState() {
            let downloads = downloadsStore()
            let sections = app.session.librarySections
            let shown = view.shown(in: app.session)
            let views = sections.map { section in
                switch section {
                case .continueWatching: continueWatching.observer.state.title
                // The section's own label (the store's title is the same): reading the store's state here would
                // redraw the whole Library on every progress report of a download.
                case .downloads: section.label
                default: library.observer.state.title
                }
            }
            let selectedView = sections.firstIndex(of: shown) ?? 0
            let onSelectView = { (index: Int) in view.picked = sections[index] }
            Group {
                #if !os(tvOS)
                if shown == .downloads, let downloads {
                    DownloadsScreen(
                        state: downloads.observer.state,
                        views: views,
                        selectedView: selectedView,
                        onSelectView: onSelectView,
                        store: downloads.store
                    )
                } else {
                    libraryScreen(shown == .continueWatching ? continueWatching : library, scroll: view.scroll(of: shown), views: views, selectedView: selectedView, onSelectView: onSelectView)
                }
                #else
                libraryScreen(shown == .continueWatching ? continueWatching : library, scroll: view.scroll(of: shown), views: views, selectedView: selectedView, onSelectView: onSelectView)
                #endif
            }
            .navigationTitle(library.observer.state.title)
            .task { await library.observer.observe() }
            .task { await continueWatching.observer.observe() }
            .task(id: downloads != nil) { await downloads?.observer.observe() }
            .task {
                for await section in app.navigator.librarySection where section != nil {
                    if let section = app.navigator.takeLibrarySection(), sections.contains(section) { view.picked = section }
                }
            }
        }
    }

    private func libraryScreen(
        _ store: RetainedStore<LibraryStore, LibraryUiState>,
        scroll: ScrollMemory,
        views: [String],
        selectedView: Int,
        onSelectView: @escaping (Int) -> Void
    ) -> some View {
        LibraryScreen(
            state: store.observer.state,
            views: views,
            selectedView: selectedView,
            onSelectView: onSelectView,
            scroll: scroll,
            intents: LibraryIntents(store.store)
        )
    }

    private func retained(
        key: String,
        create: @escaping (AppSession) -> LibraryStore
    ) -> RetainedStore<LibraryStore, LibraryUiState>? {
        let session = app.session
        return app.retained(
            RouteLibrary.shared,
            key: key,
            create: { create(session) },
            state: { $0.state },
            release: { $0.release() }
        )
    }

    /// The Downloads section's store, where the device has downloads (`AppSession.downloadsStore`).
    private func downloadsStore() -> RetainedStore<DownloadsStore, DownloadsUiState>? {
        let session = app.session
        guard session.librarySections.contains(.downloads) else { return nil }
        return app.retained(
            RouteLibrary.shared,
            key: "downloads",
            // The section is listed only where the store exists.
            create: { session.downloadsStore()! },
            state: { $0.state },
            release: { $0.release() }
        )
    }

    /// What the view keeps beside the stores, for as long as the navigator keeps them.
    private func viewState() -> LibraryViewState? {
        app.navigator.retain(route: RouteLibrary.shared, key: "view", create: { LibraryViewState() }, release: { _ in }) as? LibraryViewState
    }
}

/// What Library's view keeps beside its stores (`TortugaNavigator.retain`, under "view"): the section shown and where
/// each section's grid was scrolled.
@Observable
final class LibraryViewState {
    /// The section picked in the view menu, or asked for by the app while Library shows; nil until then.
    var picked: LibrarySection?
    /// The section Library opened on, asked for once.
    @ObservationIgnored private var opening: LibrarySection?
    @ObservationIgnored private var scrolls: [LibrarySection: ScrollMemory] = [:]

    /// The section shown: the one picked, else the one Library opened on (`AppSession.librarySection`, asked once).
    func shown(in session: AppSession) -> LibrarySection {
        if let picked { return picked }
        if let opening { return opening }
        let section = session.librarySection()
        opening = section
        return section
    }

    /// Where `section`'s grid was scrolled.
    func scroll(of section: LibrarySection) -> ScrollMemory {
        if let scroll = scrolls[section] { return scroll }
        let scroll = ScrollMemory()
        scrolls[section] = scroll
        return scroll
    }
}

/// What the Library screen sends the shown tab's store.
struct LibraryIntents {
    let selectType: (String?) -> Void
    let selectSort: (LibrarySort) -> Void
    let open: (String) -> Void
    let menuAction: (String, MediaAction) -> Void
    let loadMore: () -> Void
    /// The Calendar button.
    let openCalendar: () -> Void

    init(_ store: LibraryStore) {
        selectType = { store.selectType(type: $0) }
        selectSort = { store.selectSort(sort: $0) }
        open = { store.open(id: $0) }
        menuAction = { store.menuAction(id: $0, action: $1) }
        loadMore = { store.loadNextPage() }
        openCalendar = { store.openCalendar() }
    }
}

/// The library or Continue Watching: one row of filters (the screen's header row, `screenHeader`,
/// which stays at the top while the grid scrolls; tvOS: `FilterRow`), the view first (the library, Continue Watching or
/// Downloads, its capsule naming the one shown: `LibraryViewMenu`), then the type and sort (on a phone its symbol
/// alone), and the Calendar's button at the row's trailing end, where
/// the platform puts a screen's actions (`screenHeader`: on the first line of iPhone's row, iPad's navigation bar, the
/// end of the Mac's row on the toolbar's band; tvOS: the row's trailing end), which opens it over Library. Then a grid
/// of posters with their progress and watched marks, a series' count of new episodes, paged as it scrolls. The row and
/// the grid share one leading edge. A poster opens its title, in Continue Watching resumes its video, as the remote's
/// Play/Pause does there; its menu (`LibraryRow.menu`: a long press, a right click, a long press of Select on the
/// remote) marks it watched or unwatched or removes it from the library. Every text comes from
/// `LibraryUiState`. Nothing here takes focus by itself, so the tvOS tab bar keeps it until the remote moves down into
/// the screen.
///
/// Accessibility identifiers: `library.view`, `library.type` and `library.sort` with their `.option.<i>`,
/// `library.calendar`, `library.item.<i>`, its menu's entries `library.item.<i>.menu.<action>`.
struct LibraryScreen: View {
    let state: LibraryUiState
    /// The views' names (Library, Continue Watching, Downloads).
    let views: [String]
    let selectedView: Int
    let onSelectView: (Int) -> Void
    /// Where the grid was scrolled: it opens there.
    let scroll: ScrollMemory
    let intents: LibraryIntents

    var body: some View {
        Group {
            #if os(tvOS)
            VStack(alignment: .leading, spacing: 0) {
                FilterRow { viewAndFilters } trailing: { calendarButton }
                content
            }
            #else
            content.screenHeader(filters: { viewAndFilters }, actions: { calendarButton })
            #endif
        }
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: state.body) {
        case .loading:
            LoadingStateView()
        case .empty(let empty):
            // The grid shown next (another type picked, an item added) opens at the top.
            EmptyStateView(message: empty.text, systemImage: "books.vertical").onAppear { scroll.y = 0 }
        case .grid(let grid):
            // One grid per view (the library, Continue Watching): a grid opens where its own view's was scrolled
            // (`scrollMemory` reads it once per grid), not where the other view's was.
            LibraryGrid(grid: grid, plays: state.continueWatching, scroll: scroll, intents: intents)
                .id(selectedView)
        }
    }

    /// The view menu, then the filters.
    @ViewBuilder
    private var viewAndFilters: some View {
        LibraryViewMenu(views: views, selected: selectedView, onSelect: onSelectView)
        filters
    }

    /// The type filter, when there is more than one type, and the sort, while they have something to act on
    /// (`LibraryUiState.showsFilters`).
    @ViewBuilder
    private var filters: some View {
        if state.showsFilters {
            if state.showsTypes, let chip = state.typeChip {
                FilterMenu(
                    chip: chip,
                    options: state.types.map { FilterOption(label: $0.label, selected: $0.selected) },
                    id: "library.type"
                ) { intents.selectType(state.types[$0].type) }
            }
            if let chip = state.sortChip {
                FilterMenu(
                    chip: chip,
                    symbol: "arrow.up.arrow.down",
                    options: state.sorts.map { FilterOption(label: $0.label, selected: $0.selected) },
                    id: "library.sort"
                ) { intents.selectSort(state.sorts[$0].sort) }
            }
        }
    }

    /// The Calendar button: a glass circle on tvOS and in iPhone's row (`screenHeader`'s
    /// actions), elsewhere an action of the header row with its title (the Mac) or in the navigation bar (iPad).
    @ViewBuilder
    private var calendarButton: some View {
        #if os(tvOS)
        IconButton(systemImage: "calendar", label: T.shared.calendar.title, id: "library.calendar", action: intents.openCalendar)
        #else
        Button(action: intents.openCalendar) {
            Label(T.shared.calendar.title, systemImage: "calendar")
        }
        .accessibilityIdentifier("library.calendar")
        #endif
    }
}

/// Library's view menu, the first capsule of its row of filters: the views (the library, Continue Watching and, where
/// the device has downloads, Downloads), naming the one shown (`library.view`, its options `library.view.option.<i>`).
struct LibraryViewMenu: View {
    let views: [String]
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        FilterMenu(
            chip: FilterChip(name: T.shared.library.title, text: views[selected], spoken: views[selected], narrowed: false),
            options: views.enumerated().map { FilterOption(label: $0.element, selected: $0.offset == selected) },
            id: "library.view",
            onSelect: onSelect
        )
    }
}

/// The posters, in as many columns as fit between the content edges, the next page asked for once the last rows come
/// into view.
private struct LibraryGrid: View {
    let grid: LibraryBodyGrid
    /// Continue Watching: Select resumes an item's video.
    let plays: Bool
    let scroll: ScrollMemory
    let intents: LibraryIntents

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        GeometryReader { geometry in
            let layout = dimens.grid(width: geometry.size.width, sides: sides, shape: .poster)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.fixed(layout.cell), spacing: dimens.gutter, alignment: .top), count: layout.columns),
                        alignment: .leading,
                        spacing: dimens.gutter * 1.5
                    ) {
                        ForEach(Array(grid.items.enumerated()), id: \.element.id) { index, row in
                            LibraryCard(row: row, index: index, width: layout.cell, plays: plays, intents: intents)
                                .onAppear {
                                    if grid.hasMore, index >= grid.items.count - Int(LibraryStore.companion.LOAD_AHEAD) { intents.loadMore() }
                                }
                        }
                    }
                    .padding(sides.insets)
                    // Room for the focused poster to lift (tvOS) or its focus ring.
                    .padding(.vertical, dimens.focusRoom + 4)
                }
            }
            .scrollMemory(scroll)
        }
        // Down from any control of the row above lands on the grid.
        .shelfFocusSection()
    }
}

private struct LibraryCard: View {
    let row: LibraryRow
    let index: Int
    let width: CGFloat
    /// Continue Watching: Select resumes the item's video, and on tvOS Play/Pause does too.
    let plays: Bool
    let intents: LibraryIntents
    @Environment(\.posterTemplate) private var posterTemplate

    var body: some View {
        let poster = posterTemplate.poster(type: row.type, id: row.id, imdbId: nil, addon: row.poster)
        PosterCard(
            title: row.name,
            imageURL: poster.url,
            fallbackImageURL: poster.fallback,
            shape: row.posterShape,
            caption: row.caption,
            progress: row.progress?.doubleValue,
            watched: row.watched,
            badge: row.badge,
            badgeDescription: row.badgeDescription,
            width: width,
            accessibilityId: "library.item.\(index)",
            menu: row.menu,
            onMenu: { intents.menuAction(row.id, $0) },
            action: { intents.open(row.id) }
        )
        #if os(tvOS)
        .onPlayPauseCommand(perform: plays ? { intents.open(row.id) } : nil)
        #endif
    }
}
