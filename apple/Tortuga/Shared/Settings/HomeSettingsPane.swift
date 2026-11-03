import SwiftUI
import TortugaKit
#if !os(tvOS)
import UniformTypeIdentifiers
#endif

/// The home section over its store, with what its collections open over it: the delete question
/// (`HomeSettingsUiState.deleteDialog`, the system alert: Cancel, and Delete as the destructive button), the import's
/// or the editor's sheet while `HomeSettingsUiState.page` says so, and on iPhone, iPad and the Mac the system's save
/// dialog for Export (`tortuga-collections-<date>.json`; a save that fails puts the store's notice at the top of the
/// page). Apple TV has no save dialog nor share sheet, and no Export.
struct HomeSettingsRoute: View {
    let model: StoreModel<HomeSettingsStore, HomeSettingsUiState>

    #if !os(tvOS)
    /// The file Export saves, while its save dialog is up.
    @State private var export: CollectionExport?
    #endif

    var body: some View {
        let store = model.store
        let state = model.observer.state
        HomeSettingsPane(state: state, store: store, onExport: startExport)
            .task { await model.observer.observe() }
            .confirmation(
                state.deleteDialog.map { dialog in
                    Confirmation(
                        title: dialog.title,
                        message: dialog.message,
                        confirmLabel: dialog.confirmLabel,
                        cancelLabel: dialog.cancelLabel,
                        confirmId: "settings.home.delete.confirm",
                        cancelId: "settings.home.delete.cancel"
                    )
                },
                onConfirm: store.confirmDelete,
                onCancel: store.dismissDelete
            )
            .settingsSheet(item: state.page == .`import` ? store.collectionImport : nil, size: .fullHeightForm, onDismiss: store.closePage) {
                CollectionImportSheet(store: $0, onClose: store.closePage).id(ObjectIdentifier($0))
            }
            .settingsSheet(item: state.page == .editor ? store.collectionEditor : nil, size: .fullHeightForm, onDismiss: store.closePage) {
                CollectionEditorSheet(store: $0).id(ObjectIdentifier($0))
            }
            #if !os(tvOS)
            .fileExporter(
                isPresented: Binding(get: { export != nil }, set: { if !$0 { export = nil } }),
                item: export.map { ExportFile(text: $0.text) },
                contentTypes: [.json],
                defaultFilename: export?.fileName,
                onCompletion: { result in
                    if case .failure = result { store.exportFailed() }
                },
                onCancellation: {}
            )
            #endif
    }

    private func startExport() {
        #if !os(tvOS)
        export = model.store.export()
        #endif
    }
}

/// Home's rows for the profile (per profile, synced, as the note says): Continue Watching, which stays first and is
/// only shown or hidden, then every other row in Home's order, each shown or hidden by its switch and moved; the
/// profile's collections among them, each edited or deleted. macOS, iPhone and iPad: each an `OrderedRow` (its drag
/// handle, its switch, then ⋯ with Move up and Move down, and for a collection Edit and Delete; on the Mac a right click
/// opens the same menu). tvOS: its switch (an eye), Move up and Move down as round buttons beside its title, a
/// collection's Edit and Delete before them; the arrows keep focus on the moved row, so Select again moves it further.
/// Reset to default brings back Home's own rows and order. While the store reads the addons and the layout, a spinner
/// holds the place of the rows and Reset. The collection actions, New collection, Import collections and Export
/// collections (`onExport`; off while the profile has none), are the bar's round buttons named by their labels; on tvOS
/// New and Import head the form, without Export. A failed export's notice heads the page until dismissed.
///
/// Accessibility identifiers: `settings.home.continueWatching`, `settings.home.row.<i>` (the switch of the row at
/// `i`; on tvOS a toggle button, selected while the row shows) with `.up` and `.down` (its buttons on tvOS, its menu's
/// entries on iOS and macOS), `.edit` and `.delete` (a collection's, the same), `.handle` and `.more` (⋯; iOS and
/// macOS), `settings.home.reset`, `settings.home.new`, `settings.home.import`, `settings.home.export`,
/// `settings.home.notice`.
struct HomeSettingsPane: View {
    let state: HomeSettingsUiState
    let store: HomeSettingsStore
    let onExport: () -> Void

    #if os(tvOS)
    @FocusState private var focus: HomeRowControl?
    /// The button last pressed: focus stays on its row as the row moves.
    @State private var moved: HomeRowControl?
    #endif
    #if os(macOS)
    /// The id of the row being dragged by its handle.
    @State private var dragged: String?
    #endif

    var body: some View {
        Form {
            if let notice = state.notice {
                Section {
                    SettingsNotice(text: notice, error: true, onDismiss: store.dismissNotice)
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("settings.home.notice")
                }
            }
            #if os(tvOS)
            Section {
                Button(action: store.doNewCollection) {
                    Label(state.collections.newLabel, systemImage: "plus")
                }
                .accessibilityIdentifier("settings.home.new")
                Button(action: store.openImport) {
                    Label(state.collections.importLabel, systemImage: "square.and.arrow.down")
                }
                .accessibilityIdentifier("settings.home.import")
            }
            #endif
            Section {
                SettingsToggleRow(row: state.continueWatching, id: "settings.home.\(state.continueWatching.id)") {
                    store.toggle(key: state.continueWatching.id, checked: $0)
                }
            } footer: {
                Text(state.note)
            }
            switch onEnum(of: state.body) {
            case .loading:
                Section { ProgressView().frame(maxWidth: .infinity) }
            case .empty(let empty):
                Section {
                    Text(empty.text).foregroundStyle(.secondary)
                } header: {
                    Text(state.rowsHeader)
                }
                reset
            case .rows(let shown):
                Section {
                    rows(shown.rows)
                } header: {
                    Text(state.rowsHeader)
                } footer: {
                    #if os(iOS)
                    if shown.rows.count > 1 { Text(T.shared.settings.home.dragToReorder) }
                    #endif
                }
                reset
            }
        }
        // Continue Watching takes changes once the layout is read; until then the page has nothing to focus.
        .settingsForm(idle: !state.continueWatching.enabled)
        #if os(tvOS)
        .onChange(of: state) { _, state in
            guard let moved else { return }
            self.moved = nil
            switch onEnum(of: state.body) {
            case .rows(let shown):
                if let row = shown.rows.first(where: { $0.id == moved.rowId }) { focus = moved.kept(in: row) }
            case .loading, .empty:
                break
            }
        }
        #else
        .screenActions {
            let actions = state.collections
            Button(action: store.doNewCollection) {
                Label(actions.newLabel, systemImage: "plus")
            }
            .help(actions.newLabel)
            .accessibilityIdentifier("settings.home.new")
            Button(action: store.openImport) {
                Label(actions.importLabel, systemImage: "square.and.arrow.down")
            }
            .help(actions.importLabel)
            .accessibilityIdentifier("settings.home.import")
            Button(action: onExport) {
                Label(actions.exportLabel, systemImage: "square.and.arrow.up")
            }
            .help(actions.exportLabel)
            .disabled(!actions.canExport)
            .accessibilityIdentifier("settings.home.export")
        }
        #endif
    }

    /// Reset to default, under the rows or why there are none.
    private var reset: some View {
        Section {
            Button(action: store.reset) {
                Label(state.resetLabel, systemImage: "arrow.counterclockwise")
            }
            .disabled(!state.canReset)
            #if !os(tvOS)
            // The app's tint draws a disabled form button like an enabled one: dimmed, it reads as unavailable.
            .opacity(state.canReset ? 1 : 0.45)
            #endif
            .accessibilityIdentifier("settings.home.reset")
        }
    }

    #if os(tvOS)
    /// Each row: its title and source, then a collection's Edit and Delete, its switch, Move up and Move down as round
    /// buttons, each focused on its own. A system switch would take the whole row's focus (the form's row as one button)
    /// and leave the arrows beside it out of reach: the switch is a button showing an eye, crossed out while the row is
    /// hidden (dimmed then).
    private func rows(_ rows: [HomeRowSetting]) -> some View {
        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
            let id = "settings.home.row.\(index)"
            HStack(spacing: 24) {
                SettingsRowText(title: row.title, subtitle: row.sourceName)
                    .opacity(row.shown ? 1 : 0.5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let collection = row.collectionId {
                    if let edit = row.editLabel {
                        IconButton(systemImage: "pencil", label: edit, id: "\(id).edit", symbolBox: Self.symbolBox) {
                            store.editCollection(collectionId: collection)
                        }
                    }
                    if let delete = row.deleteLabel {
                        IconButton(systemImage: "trash", label: delete, id: "\(id).delete", symbolBox: Self.symbolBox) {
                            store.askDelete(collectionId: collection)
                        }
                    }
                }
                IconButton(systemImage: row.shown ? "eye" : "eye.slash", label: row.title, id: id, symbolBox: Self.symbolBox) {
                    store.setShown(id: row.id, shown: !row.shown)
                }
                .accessibilityAddTraits(row.shown ? [.isToggle, .isSelected] : .isToggle)
                .focused($focus, equals: .toggle(row.id))
                IconButton(systemImage: "arrow.up", label: row.moveUpLabel, id: "\(id).up", enabled: row.canMoveUp, symbolBox: Self.symbolBox) {
                    moved = .up(row.id)
                    store.moveUp(id: row.id)
                }
                .focused($focus, equals: .up(row.id))
                IconButton(systemImage: "arrow.down", label: row.moveDownLabel, id: "\(id).down", enabled: row.canMoveDown, symbolBox: Self.symbolBox) {
                    moved = .down(row.id)
                    store.moveDown(id: row.id)
                }
                .focused($focus, equals: .down(row.id))
            }
            // The circles' glass, one row of it.
            .glassGroup(spacing: 24)
        }
    }

    /// The square of the buttons' symbols: the eye and the crossed-out eye are wider than the arrows, and the circles
    /// stay one size.
    private static let symbolBox: CGFloat = 40
    #elseif os(macOS)
    private func rows(_ rows: [HomeRowSetting]) -> some View {
        let ids = rows.map(\.id)
        return ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
            let id = "settings.home.row.\(index)"
            let entries = menuEntries(row, id: id)
            OrderedRow(
                id: row.id,
                index: index,
                ids: ids,
                title: row.title,
                probe: id,
                handleLabel: row.dragLabel,
                moreLabel: row.moreLabel,
                entries: entries,
                dragged: $dragged
            ) { rowId, to in
                store.moveTo(id: rowId, index: Int32(to))
            } content: {
                SettingsToggleRow(title: row.title, subtitle: row.sourceName, checked: row.shown, id: id) {
                    store.setShown(id: row.id, shown: $0)
                }
                .accessibilityActions(entries)
            }
        }
    }
    #else
    private func rows(_ rows: [HomeRowSetting]) -> some View {
        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
            let id = "settings.home.row.\(index)"
            let entries = menuEntries(row, id: id)
            OrderedRow(title: row.title, probe: id, handleLabel: row.dragLabel, moreLabel: row.moreLabel, entries: entries) {
                SettingsToggleRow(title: row.title, subtitle: row.sourceName, checked: row.shown, id: id) {
                    store.setShown(id: row.id, shown: $0)
                }
                .accessibilityActions(entries)
            }
        }
        .onMove { from, to in
            guard let source = from.first else { return }
            // `to` counts the list with the row still in place; the store takes the index it ends at.
            store.moveTo(id: rows[source].id, index: Int32(to > source ? to - 1 : to))
        }
    }
    #endif

    #if !os(tvOS)
    /// Move up and Move down, each where `row` can go that way; for a collection Edit, then Delete last.
    private func menuEntries(_ row: HomeRowSetting, id: String) -> [ItemMenuEntry] {
        let text = T.shared.settings.home
        var entries: [ItemMenuEntry] = []
        if row.canMoveUp {
            entries.append(ItemMenuEntry(label: text.moveUp, systemImage: "arrow.up", id: "\(id).up") { store.moveUp(id: row.id) })
        }
        if row.canMoveDown {
            entries.append(ItemMenuEntry(label: text.moveDown, systemImage: "arrow.down", id: "\(id).down") { store.moveDown(id: row.id) })
        }
        if let collection = row.collectionId {
            if let edit = row.editLabel {
                entries.append(ItemMenuEntry(label: edit, systemImage: "pencil", id: "\(id).edit") { store.editCollection(collectionId: collection) })
            }
            if let delete = row.deleteLabel {
                entries.append(
                    ItemMenuEntry(label: delete, systemImage: "trash", id: "\(id).delete", destructive: true) { store.askDelete(collectionId: collection) }
                )
            }
        }
        return entries
    }
    #endif
}

#if os(tvOS)
/// A control of a row on tvOS, by the row's id, so focus follows the row as it moves.
private enum HomeRowControl: Hashable {
    case toggle(String)
    case up(String)
    case down(String)

    var rowId: String {
        switch self {
        case .toggle(let id), .up(let id), .down(let id): id
        }
    }

    /// Where focus goes once `row` has moved by this button: the same button while it can move the row further, else
    /// the other one (a row at the top can only go down), else the row's switch.
    func kept(in row: HomeRowSetting) -> HomeRowControl {
        switch self {
        case .up where !row.canMoveUp: row.canMoveDown ? .down(row.id) : .toggle(row.id)
        case .down where !row.canMoveDown: row.canMoveUp ? .up(row.id) : .toggle(row.id)
        default: self
        }
    }
}
#endif
