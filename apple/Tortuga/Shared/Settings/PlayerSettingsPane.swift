import SwiftUI
import TortugaKit

/// Playback's Player row over its store (`PlayerSettingsStore`): the player every play on this device opens in
/// ("Tortuga", "VLC", "VLC (not found)") and "This device only" under its title; the pane gives it a group of its own
/// (a device setting in a pane of the profile's). Its press opens the Player page (`PlayerSettingsPage`) as a sheet,
/// which the store's `pageOpen` drives, as the language editor is driven.
///
/// Accessibility identifier: `settings.playback.player`.
struct PlayerSettingItem: View {
    let model: StoreModel<PlayerSettingsStore, PlayerSettingsUiState>

    var body: some View {
        let store = model.store
        let state = model.observer.state
        SettingsLinkRow(title: state.row.title, subtitle: state.row.note, value: state.row.valueLabel, id: "settings.playback.player") {
            store.openPage()
        }
        .task { await model.observer.observe() }
        .settingsSheet(isPresented: Binding(get: { state.pageOpen }, set: { if !$0 { store.closePage() } })) {
            PlayerSettingsPage(model: model)
        }
    }
}

/// Settings → Playback → Player, in a form: the players to choose from, the chosen one checked (and focused first on
/// tvOS); picking one plays everything on this device in it from now on. Tortuga first, then the player apps found
/// here (a spinner after them while the page looks for them again), then one chosen but no longer found; under them
/// the page's note. Where the platform takes custom players, a group of them, each with Delete, and Add custom player,
/// which opens its form (`CustomPlayerSheet`) over the page. Close (Escape; an iPhone's sheet has the grabber instead;
/// tvOS: Menu) closes the page; on tvOS Menu in the form closes the form first.
///
/// Accessibility identifiers: `settings.player` (the form), `settings.player.close`, `settings.player.option.<player id>`
/// with a custom player's `.delete`, `settings.player.detecting` (the spinner), `settings.player.addCustom`.
struct PlayerSettingsPage: View {
    let model: StoreModel<PlayerSettingsStore, PlayerSettingsUiState>

    #if os(tvOS)
    @FocusState private var focused: String?
    #endif

    var body: some View {
        let store = model.store
        let state = model.observer.state
        let close = SheetAction(title: T.shared.settings.close, id: "settings.player.close", action: store.closePage)
        NavigationStack {
            Form {
                Section {
                    ForEach(state.players, id: \.id) { row in
                        choice(row, store: store)
                    }
                    if state.detecting {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .accessibilityIdentifier("settings.player.detecting")
                    }
                } footer: {
                    Text(state.note)
                }
                if let header = state.customHeader {
                    Section(header) {
                        ForEach(state.customPlayers, id: \.id) { row in
                            choice(row, store: store)
                        }
                        if let add = state.addCustomText {
                            Button(action: store.openAddCustom) {
                                Label(add, systemImage: "plus.circle")
                            }
                            .formRowButtonStyle()
                            .accessibilityIdentifier("settings.player.addCustom")
                        }
                    }
                }
                SheetActionsSection(actions: [close])
            }
            .settingsForm()
            .accessibilityIdentifier("settings.player")
            .pageTitle(state.title)
            .sheetToolbar(close: close)
            #if os(tvOS)
            .defaultFocus($focused, (state.players + state.customPlayers).first(where: \.selected)?.id)
            #endif
        }
        .backAction(store.closePage)
        .settingsSheet(item: state.customForm, onDismiss: store.closeAddCustom) { form in
            CustomPlayerSheet(form: form, store: store)
        }
    }

    private func choice(_ row: PlayerChoiceRow, store: PlayerSettingsStore) -> some View {
        #if os(tvOS)
        PlayerChoiceRowView(row: row, focus: $focused, onSelect: { store.select(id: row.id) }, onDelete: { store.deleteCustom(id: row.id) })
        #else
        PlayerChoiceRowView(row: row, onSelect: { store.select(id: row.id) }, onDelete: { store.deleteCustom(id: row.id) })
        #endif
    }
}

/// A player to choose: its name (one no longer found says so, beside a warning) and the line under it (what the player
/// reports back, a custom player's template), checked while it is the one chosen; a custom player's Delete at its
/// trailing end (`PlayerChoiceRow.deleteText`). On iOS and macOS the check and Delete go beside the text or under it
/// (`SettingsRowLayout`).
private struct PlayerChoiceRowView: View {
    let row: PlayerChoiceRow
    #if os(tvOS)
    /// The page's focus, by player id.
    let focus: FocusState<String?>.Binding
    #else
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #endif
    let onSelect: () -> Void
    let onDelete: () -> Void

    var body: some View {
        #if os(tvOS)
        HStack(spacing: 12) {
            Button(action: onSelect) {
                HStack(spacing: 12) {
                    text
                    checkmark
                }
                .contentShape(Rectangle())
            }
            .focused(focus, equals: row.id)
            .accessibilityAddTraits(row.selected ? .isSelected : [])
            .accessibilityIdentifier(id)
            if let delete = row.deleteText {
                IconButton(systemImage: "trash", label: delete, id: "\(id).delete", action: onDelete)
            }
        }
        #else
        SettingsRowLayout(
            leastTextWidth: SettingsRowLayout.leastTextWidth(title: row.name, subtitle: row.detail, dynamicTypeSize: dynamicTypeSize),
            spacing: 12
        ) {
            Button(action: onSelect) {
                text.contentShape(Rectangle())
            }
            // In a list row beside another button, each answers its own taps only.
            #if os(iOS)
            .buttonStyle(.borderless)
            #else
            .buttonStyle(.plain)
            #endif
            .accessibilityAddTraits(row.selected ? .isSelected : [])
            .accessibilityIdentifier(id)
            if row.selected || row.deleteText != nil {
                HStack(spacing: 12) {
                    checkmark
                    if let delete = row.deleteText {
                        Button(role: .destructive, action: onDelete) {
                            Image(systemName: "trash").hitTargetLabel()
                        }
                        .buttonStyle(.borderless)
                        #if os(macOS)
                        .tooltip(delete)
                        #else
                        .help(delete)
                        #endif
                        .accessibilityLabel(delete)
                        .accessibilityIdentifier("\(id).delete")
                        .hitTarget()
                    }
                }
            }
        }
        #endif
    }

    private var id: String { "settings.player.option.\(row.id)" }

    private var text: some View {
        HStack(spacing: 12) {
            if row.missing {
                Image(systemName: "exclamationmark.triangle")
                    .foregroundStyle(Palette.error)
                    .accessibilityHidden(true)
            }
            SettingsRowText(title: row.name, subtitle: row.detail)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var checkmark: some View {
        if row.selected {
            Image(systemName: "checkmark")
                .fontWeight(.semibold)
                .accessibilityHidden(true)
        }
    }
}

/// Adding a custom player, in a form: its name, then what the platform runs (`CustomPlayerForm.kind`: a program, a URL
/// template or a package name) and, for a program, its arguments, prefilled (`{url}`); under them the line explaining
/// the placeholders, and why the last Save was refused. Save hands the fields to the store, which closes the form once
/// it adds the player; Cancel (tvOS: Menu) closes it without adding.
///
/// Accessibility identifiers: `settings.player.custom.<name|command|arguments|save|cancel|error>`.
private struct CustomPlayerSheet: View {
    let form: CustomPlayerForm
    let store: PlayerSettingsStore

    @State private var name = ""
    @State private var command = ""
    /// The arguments' field, from the form's default.
    @State private var arguments: String

    init(form: CustomPlayerForm, store: PlayerSettingsStore) {
        self.form = form
        self.store = store
        _arguments = State(initialValue: form.argumentsDefault)
    }

    var body: some View {
        let save = SheetAction(title: form.saveText, id: "settings.player.custom.save") {
            store.saveCustom(name: name, command: command, arguments: arguments)
        }
        let cancel = SheetAction(title: form.cancelText, id: "settings.player.custom.cancel", action: store.closeAddCustom)
        NavigationStack {
            Form {
                Section(form.nameLabel) {
                    TextField(form.nameLabel, text: $name, prompt: Text(form.nameHint))
                        .labelsHidden()
                        .accessibilityIdentifier("settings.player.custom.name")
                }
                Section {
                    field(form.commandLabel, hint: form.commandHint, text: $command)
                        #if os(iOS)
                        .keyboardType(form.kind == .urlTemplate ? .URL : .default)
                        #endif
                        .accessibilityIdentifier("settings.player.custom.command")
                } header: {
                    Text(form.commandLabel)
                } footer: {
                    if form.argumentsLabel == nil { notes }
                }
                if let argumentsLabel = form.argumentsLabel {
                    Section {
                        field(argumentsLabel, hint: form.argumentsHint ?? "", text: $arguments)
                            .accessibilityIdentifier("settings.player.custom.arguments")
                    } header: {
                        Text(argumentsLabel)
                    } footer: {
                        notes
                    }
                }
                SheetActionsSection(actions: [save, cancel])
            }
            .settingsForm()
            .pageTitle(form.title)
            .sheetToolbar(cancel: cancel, confirm: save)
        }
        .backAction(store.closeAddCustom)
    }

    /// A field of a command line or a link: typed as is, never corrected or capitalised.
    private func field(_ label: String, hint: String, text: Binding<String>) -> some View {
        TextField(label, text: text, prompt: Text(hint))
            .labelsHidden()
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
    }

    /// The placeholders' line, then why the last Save was refused.
    @ViewBuilder
    private var notes: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let placeholders = form.placeholders {
                Text(placeholders)
            }
            if let error = form.error {
                Text(error)
                    .foregroundStyle(Palette.error)
                    .accessibilityIdentifier("settings.player.custom.error")
            }
        }
    }
}
