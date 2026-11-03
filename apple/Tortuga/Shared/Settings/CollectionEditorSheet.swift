import SwiftUI
import TortugaKit

/// The collection editor over Settings → Home, in a sheet over `store`: one page at a time, a form each, the page's
/// heading its title.
/// - The collection: its title, how it shows its folders (Tabs, Rows, Follow the app), the All tab, its tiles' shape
///   (Poster, Landscape, Square: every folder's), its folders (a tap or click opens one; each moves by its drag handle
///   or its ⋯, on tvOS by ↑ and ↓, and ⋯ or tvOS's trash deletes it), Add folder, why the last Save was refused, and
///   Save (a spinner while it saves).
/// - A folder (`CollectionEditorUiState.folder`): its title, its cover's link, Hide the title, its sources (each with
///   its note in the error colour when it can't show now; moved as the folders are, removed from ⋯) and Add source.
/// - Add source (`CollectionEditorUiState.picker`): the addons' catalogs and each connected tracker's lists, by group,
///   a check on the ones the folder has; a press adds one (a list by its link asks for the link first, `ListUrlForm`).
///
/// Back (iOS and macOS: the bar's Cancel on the collection's page, Back on the others, Escape; tvOS: Menu) leaves the
/// page for the one before it, and from the collection closes the editor (`CollectionEditorStore.back`): at once when
/// nothing changed, else after "Discard changes?" (`CollectionEditorUiState.discard`), the system alert with Discard
/// as the destructive button and Keep editing as its cancel (focused first on tvOS). A swipe doesn't close the sheet:
/// the changes would go without the question. Save and Add are the bar's confirm on iOS and macOS, the form's last
/// button on tvOS.
///
/// Accessibility identifiers (the Compose UI's names): `collections.editor.back`, `collections.editor.title`,
/// `collections.editor.viewMode`, `collections.editor.allTab`, `collections.editor.shape`,
/// `collections.editor.folder.<i>` (with `.handle`, `.more`, `.up`, `.down`, `.delete`), `collections.editor.addFolder`,
/// `collections.editor.error`, `collections.editor.save`, `collections.editor.discard.confirm`,
/// `collections.editor.discard.keep`; `collections.folder.title`, `collections.folder.cover`,
/// `collections.folder.hideTitle`, `collections.folder.source.<i>` (with `.handle`, `.more`, `.up`, `.down`,
/// `.remove`), `collections.folder.addSource`; `collections.picker.<group>.<i>`, `collections.picker.url`,
/// `collections.picker.url.add`, `collections.picker.url.error`.
struct CollectionEditorSheet: View {
    let store: CollectionEditorStore

    @State private var observer: StoreObserver<CollectionEditorUiState>

    init(store: CollectionEditorStore) {
        self.store = store
        _observer = State(initialValue: StoreObserver(store.state))
    }

    var body: some View {
        let state = observer.state
        let page = EditorPage(state)
        NavigationStack {
            Group {
                switch page {
                case .collection: CollectionPage(state: state, store: store)
                case .folder(let folder): FolderPage(folder: folder, store: store)
                case .picker(let picker): PickerPage(picker: picker, store: store)
                case .link(let form): LinkPage(form: form, store: store)
                }
            }
            // A page of its own: its fields start from the store's text, its list at its top.
            .id(page.key)
            .settingsForm()
            .pageTitle(page.title(state))
            .sheetToolbar(
                cancel: SheetAction(
                    title: page == .collection ? T.shared.settings.cancel : T.shared.nav.back, id: "collections.editor.back", action: store.back
                ),
                confirm: page.confirm(state, store: store)
            )
        }
        .task { await observer.observe() }
        .backAction(store.back)
        .interactiveDismissDisabled()
        .confirmation(
            state.discard.map { question in
                Confirmation(
                    title: question.title,
                    message: "",
                    confirmLabel: question.discardLabel,
                    cancelLabel: question.keepLabel,
                    confirmId: "collections.editor.discard.confirm",
                    cancelId: "collections.editor.discard.keep"
                )
            },
            onConfirm: store.discard,
            onCancel: store.keepEditing
        )
    }
}

/// The editor's page on top: the list by link over the picker, over the folder's page, over the collection's.
private enum EditorPage: Equatable {
    case collection
    case folder(FolderEditorUiState)
    case picker(SourcePickerUiState)
    case link(ListUrlForm)

    init(_ state: CollectionEditorUiState) {
        if let form = state.picker?.urlForm {
            self = .link(form)
        } else if let picker = state.picker {
            self = .picker(picker)
        } else if let folder = state.folder {
            self = .folder(folder)
        } else {
            self = .collection
        }
    }

    var key: Int {
        switch self {
        case .collection: 0
        case .folder: 1
        case .picker: 2
        case .link: 3
        }
    }

    static func == (lhs: EditorPage, rhs: EditorPage) -> Bool { lhs.key == rhs.key }

    func title(_ state: CollectionEditorUiState) -> String {
        switch self {
        case .collection: state.heading
        case .folder(let folder): folder.heading
        case .picker: T.shared.collections.editor.addSource
        case .link(let form): form.title
        }
    }

    /// The collection page's Save; none on the others (the link page's Add is its own, beside its field's text).
    @MainActor
    func confirm(_ state: CollectionEditorUiState, store: CollectionEditorStore) -> SheetAction? {
        switch self {
        case .collection:
            SheetAction(title: T.shared.collections.editor.save, id: "collections.editor.save", enabled: state.loaded && !state.saving, action: store.save)
        case .folder, .picker, .link:
            nil
        }
    }
}

/// The collection's page: its title, its view, All tab and shape, its folders and Add folder, the refusal, Save.
private struct CollectionPage: View {
    let state: CollectionEditorUiState
    let store: CollectionEditorStore

    #if os(macOS)
    @State private var dragged: String?
    #endif

    var body: some View {
        let text = T.shared.collections.editor
        Form {
            if !state.loaded {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else {
                Section {
                    EditorField(value: state.title, label: text.titleLabel, prompt: nil, id: "collections.editor.title", onEdit: store.setTitle)
                }
                Section {
                    SettingsChoicePicker(row: state.viewMode, id: "collections.editor.viewMode") {
                        store.choose(rowId: state.viewMode.id, optionId: $0)
                    }
                    SettingsToggleRow(row: state.showAllTab, id: "collections.editor.allTab") {
                        store.toggle(rowId: state.showAllTab.id, checked: $0)
                    }
                    SettingsChoicePicker(row: state.shape, id: "collections.editor.shape") {
                        store.choose(rowId: state.shape.id, optionId: $0)
                    }
                }
                Section {
                    if state.folders.isEmpty {
                        Text(text.noFolders).foregroundStyle(.secondary)
                    }
                    folders
                    Button(action: store.addFolder) {
                        Label(text.addFolder, systemImage: "plus")
                    }
                    .accessibilityIdentifier("collections.editor.addFolder")
                } header: {
                    Text(text.foldersHeader)
                }
                if let error = state.error {
                    Section { ErrorLine(text: error).accessibilityIdentifier("collections.editor.error") }
                }
                if state.saving {
                    Section { ProgressView().frame(maxWidth: .infinity) }
                }
                SheetActionsSection(actions: [EditorPage.collection.confirm(state, store: store)].compactMap { $0 })
            }
        }
    }

    private var folders: some View {
        #if os(macOS)
        let ids = state.folders.map(\.id)
        #endif
        return ForEach(Array(state.folders.enumerated()), id: \.element.id) { index, row in
            let probe = "collections.editor.folder.\(index)"
            let moves = Moves(
                probe: probe, canMoveUp: row.canMoveUp, canMoveDown: row.canMoveDown, moveUpLabel: row.moveUpLabel,
                moveDownLabel: row.moveDownLabel, removeLabel: row.deleteLabel, removeId: "\(probe).delete",
                onMoveUp: { store.moveFolderUp(index: Int32(index)) },
                onMoveDown: { store.moveFolderDown(index: Int32(index)) },
                onRemove: { store.deleteFolder(index: Int32(index)) }
            )
            let open = Button { store.openFolder(index: Int32(index)) } label: {
                SettingsRowText(title: row.title, subtitle: row.detail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier(probe)
            #if os(tvOS)
            moves.tvRow { open }
            #elseif os(macOS)
            OrderedRow(
                id: row.id, index: index, ids: ids, title: row.title, probe: probe, handleLabel: row.dragLabel, moreLabel: row.moreLabel,
                entries: moves.entries, dragged: $dragged
            ) { _, to in
                store.moveFolderTo(index: Int32(index), to: Int32(to))
            } content: {
                open.buttonStyle(.plain).accessibilityActions(moves.entries)
            }
            #else
            OrderedRow(title: row.title, probe: probe, handleLabel: row.dragLabel, moreLabel: row.moreLabel, entries: moves.entries) {
                open.buttonStyle(.plain).accessibilityActions(moves.entries)
            }
            #endif
        }
        #if os(iOS)
        .onMove { from, to in
            guard let source = from.first else { return }
            // `to` counts the list with the row still in place; the store takes the index it ends at.
            store.moveFolderTo(index: Int32(source), to: Int32(to > source ? to - 1 : to))
        }
        #endif
    }
}

/// A folder's page: its title, cover link, Hide the title, its sources and Add source.
private struct FolderPage: View {
    let folder: FolderEditorUiState
    let store: CollectionEditorStore

    #if os(macOS)
    @State private var dragged: String?
    #endif

    var body: some View {
        let text = T.shared.collections.editor
        Form {
            Section {
                EditorField(value: folder.title, label: text.folderTitleLabel, prompt: nil, id: "collections.folder.title", onEdit: store.setFolderTitle)
                EditorField(value: folder.coverUrl, label: text.coverLabel, prompt: text.coverHint, id: "collections.folder.cover", link: true, onEdit: store.setCoverUrl)
            }
            Section {
                SettingsToggleRow(row: folder.hideTitle, id: "collections.folder.hideTitle") {
                    store.toggle(rowId: folder.hideTitle.id, checked: $0)
                }
            }
            Section {
                if folder.sources.isEmpty {
                    Text(text.noSources).foregroundStyle(.secondary)
                }
                sources
                Button(action: store.openPicker) {
                    Label(text.addSource, systemImage: "plus")
                }
                .accessibilityIdentifier("collections.folder.addSource")
            } header: {
                Text(text.sourcesHeader)
            }
        }
    }

    private var sources: some View {
        #if os(macOS)
        // Two sources may read alike: a row's place keys it.
        let ids = folder.sources.indices.map { "source:\($0)" }
        #endif
        return ForEach(Array(folder.sources.enumerated()), id: \.offset) { index, row in
            let probe = "collections.folder.source.\(index)"
            let moves = Moves(
                probe: probe, canMoveUp: row.canMoveUp, canMoveDown: row.canMoveDown, moveUpLabel: row.moveUpLabel,
                moveDownLabel: row.moveDownLabel, removeLabel: row.removeLabel, removeId: "\(probe).remove",
                onMoveUp: { store.moveSourceUp(index: Int32(index)) },
                onMoveDown: { store.moveSourceDown(index: Int32(index)) },
                onRemove: { store.removeSource(index: Int32(index)) }
            )
            let text = SettingsRowText(title: row.title, subtitle: row.detail, error: row.note)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(probe)
            #if os(tvOS)
            moves.tvRow { text }
            #elseif os(macOS)
            OrderedRow(
                id: "source:\(index)", index: index, ids: ids, title: row.title, probe: probe, handleLabel: row.dragLabel,
                moreLabel: row.moreLabel, entries: moves.entries, dragged: $dragged
            ) { _, to in
                store.moveSourceTo(index: Int32(index), to: Int32(to))
            } content: {
                text.accessibilityActions(moves.entries)
            }
            #else
            OrderedRow(title: row.title, probe: probe, handleLabel: row.dragLabel, moreLabel: row.moreLabel, entries: moves.entries) {
                text.accessibilityActions(moves.entries)
            }
            #endif
        }
        #if os(iOS)
        .onMove { from, to in
            guard let source = from.first else { return }
            store.moveSourceTo(index: Int32(source), to: Int32(to > source ? to - 1 : to))
        }
        #endif
    }
}

/// Add source: each group under its heading, its choices (a check on the ones the folder has), a spinner while it
/// loads, its notes.
private struct PickerPage: View {
    let picker: SourcePickerUiState
    let store: CollectionEditorStore

    var body: some View {
        Form {
            ForEach(Array(picker.groups.enumerated()), id: \.offset) { groupIndex, group in
                Section {
                    ForEach(Array(group.choices.enumerated()), id: \.element.key) { index, choice in
                        Button { store.pick(key: choice.key) } label: {
                            HStack(spacing: 12) {
                                SettingsRowText(title: choice.title, subtitle: choice.detail)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                if choice.added {
                                    Image(systemName: "checkmark").accessibilityHidden(true)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        #if !os(tvOS)
                        .buttonStyle(.plain)
                        #endif
                        .accessibilityAddTraits(choice.added ? .isSelected : [])
                        .accessibilityIdentifier("collections.picker.\(groupIndex).\(index)")
                    }
                    if group.loading {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                    ForEach(Array(group.notes.enumerated()), id: \.offset) { _, note in
                        Text(note).font(.callout).foregroundStyle(.secondary)
                    }
                } header: {
                    Text(group.title)
                }
            }
        }
    }
}

/// A list by its link: the field, why the last link was refused, a spinner while it is looked up, and Add.
private struct LinkPage: View {
    let form: ListUrlForm
    let store: CollectionEditorStore

    @State private var link = ""

    var body: some View {
        Form {
            Section {
                TextField(form.hint, text: $link, prompt: Text(form.hint))
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    #endif
                    .submitLabel(.go)
                    .onSubmit(submit)
                    .accessibilityIdentifier("collections.picker.url")
            } footer: {
                if let error = form.error { ErrorLine(text: error).accessibilityIdentifier("collections.picker.url.error") }
            }
            if form.resolving {
                Section { ProgressView().frame(maxWidth: .infinity) }
            }
            SheetActionsSection(actions: [action])
        }
        #if !os(tvOS)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(form.addLabel, action: submit)
                    .disabled(form.resolving)
                    .accessibilityIdentifier("collections.picker.url.add")
            }
        }
        #endif
    }

    private var action: SheetAction {
        SheetAction(title: form.addLabel, id: "collections.picker.url.add", enabled: !form.resolving, action: submit)
    }

    private func submit() {
        let url = link.trimmingCharacters(in: .whitespaces)
        guard !form.resolving, !url.isEmpty else { return }
        store.submitListUrl(url: url)
    }
}

/// One line of the editor's fields: `label` over it on tvOS and as its name elsewhere, `prompt` while it is empty. It
/// starts from `value` and keeps its own text, each change going to `onEdit`: the store's state comes a moment after,
/// and typing must not wait for it. `link` takes an address: the URL keyboard, no capitals.
private struct EditorField: View {
    let label: String
    let prompt: String?
    let id: String
    var link = false
    let onEdit: (String) -> Void

    @State private var text: String

    init(value: String, label: String, prompt: String?, id: String, link: Bool = false, onEdit: @escaping (String) -> Void) {
        self.label = label
        self.prompt = prompt
        self.id = id
        self.link = link
        self.onEdit = onEdit
        _text = State(initialValue: value)
    }

    var body: some View {
        TextField(label, text: $text, prompt: Text(prompt ?? label))
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(link ? .never : .sentences)
            .keyboardType(link ? .URL : .default)
            #endif
            .onChange(of: text) { _, text in onEdit(text) }
            .accessibilityIdentifier(id)
    }
}

/// A folder's or a source's Move up, Move down and Delete (Remove): its ⋯ menu's entries (iOS, macOS; each
/// `<probe>.up`, `.down`, and `removeId`), or on tvOS round buttons beside the row (`tvRow`).
@MainActor
private struct Moves {
    let probe: String
    let canMoveUp: Bool
    let canMoveDown: Bool
    let moveUpLabel: String
    let moveDownLabel: String
    let removeLabel: String
    let removeId: String
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onRemove: () -> Void

    var entries: [ItemMenuEntry] {
        let text = T.shared.settings.home
        var entries: [ItemMenuEntry] = []
        if canMoveUp { entries.append(ItemMenuEntry(label: text.moveUp, systemImage: "arrow.up", id: "\(probe).up", action: onMoveUp)) }
        if canMoveDown { entries.append(ItemMenuEntry(label: text.moveDown, systemImage: "arrow.down", id: "\(probe).down", action: onMoveDown)) }
        entries.append(ItemMenuEntry(label: removeLabel, systemImage: "trash", id: removeId, destructive: true, action: onRemove))
        return entries
    }

    #if os(tvOS)
    /// The row's `content`, then ↑, ↓ and the trash as round buttons, each a stop of the remote.
    func tvRow(@ViewBuilder content: () -> some View) -> some View {
        HStack(spacing: 24) {
            content()
            IconButton(systemImage: "arrow.up", label: moveUpLabel, id: "\(probe).up", enabled: canMoveUp, symbolBox: 40, action: onMoveUp)
            IconButton(systemImage: "arrow.down", label: moveDownLabel, id: "\(probe).down", enabled: canMoveDown, symbolBox: 40, action: onMoveDown)
            IconButton(systemImage: "trash", label: removeLabel, id: removeId, symbolBox: 40, action: onRemove)
        }
        .glassGroup(spacing: 24)
    }
    #endif
}
