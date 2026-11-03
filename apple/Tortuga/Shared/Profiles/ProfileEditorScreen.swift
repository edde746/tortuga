import SwiftUI
import TortugaKit

/// The profile editor for `profileId` (nil: a new profile); its store lives while it shows. Opened from the picker
/// (`ProfilesRoute`) and over the app (`AppOverlay.editProfile`). `onClose` runs when it is done or given up.
struct ProfileEditorRoute: View {
    let graph: AppGraph
    let profileId: String?
    let onClose: () -> Void

    var body: some View {
        LiveStore(
            create: { graph.profileEditorStore(profileId: profileId, host: EditorHost(onClose: onClose)) },
            state: { $0.state },
            release: { $0.release() }
        ) { store, state in
            ProfileEditorScreen(state: state, store: store)
        }
    }
}

@MainActor
private final class EditorHost: @preconcurrency ProfileEditorHost {
    private let onClose: () -> Void

    init(onClose: @escaping () -> Void) { self.onClose = onClose }

    func closeEditor() { onClose() }

    func profileDeleted(profileId: String) { onClose() }
}

/// Creating or editing a profile: name, avatar (the shared set), setting, changing or removing its PIN (the pad takes
/// the screen: the PIN, then the same again), the Kids profile section (`KidsSection`), saving, and
/// deleting after the store's confirmation (`EditorActions`, the page's actions: pinned under the form in a Mac sheet).
/// Leaving without saving is Back on the picker's full-screen page and Cancel
/// in a sheet over the app (`editor.cancel`: the bar's cancellation slot on iOS, the page's own button on the Mac);
/// Escape presses either. Every text comes from `state` or `T`.
struct ProfileEditorScreen: View {
    let state: ProfileEditorUiState
    let store: ProfileEditorStore

    var body: some View {
        if let setup = state.pinSetup {
            PinPad(
                title: setup.title,
                entered: Int(setup.entered),
                length: Int(setup.length),
                enteredText: setup.enteredText,
                message: setup.error,
                acceptsInput: true,
                onDigit: { store.pinDigit(digit: Int32($0)) },
                onBackspace: store.pinBackspace,
                onCancel: store.cancelPinSetup
            ) {
                ProfileAvatarTile(initial: state.initial, avatar: state.avatar, size: 72)
            }
        } else if !state.loaded {
            LoadingStateView()
        } else {
            AccountPage(
                title: state.title,
                onBack: store.cancel,
                backId: "editor.back",
                exit: PageExit(title: T.shared.profiles.editor.cancel, id: "editor.cancel")
            ) {
                EditorForm(state: state, store: store)
            } actions: {
                EditorActions(state: state, store: store)
            }
        }
    }
}

private struct EditorForm: View {
    let state: ProfileEditorUiState
    let store: ProfileEditorStore

    /// The field's text, the store's as the form is built (seeded here, not on appear, where it went back to the store
    /// as if typed and cleared a failed save's error on the way back from the PIN pad).
    @State private var name: String
    /// True while the name field has focus (optional for `FocusRestore`).
    @FocusState private var nameFocused: Bool?
    /// The avatar that has focus, and whether Set (Change) PIN has: a move into either group lands on the avatar chosen
    /// and on Set PIN (tvOS).
    @FocusState private var focusedAvatar: String?
    @FocusState private var pinFocused: Bool

    init(state: ProfileEditorUiState, store: ProfileEditorStore) {
        self.state = state
        self.store = store
        _name = State(initialValue: state.name)
    }

    var body: some View {
        let editor = T.shared.profiles.editor
        HStack(spacing: 16) {
            ProfileAvatarTile(initial: state.initial, avatar: state.avatar, size: 72)
            VStack(alignment: .leading, spacing: 4) {
                TextField(editor.name, text: $name)
                    #if os(iOS)
                    .textInputAutocapitalization(.words)
                    .textFieldStyle(.roundedBorder)
                    #endif
                    .focused($nameFocused, equals: true)
                    .accessibilityIdentifier("editor.name")
                    .onSubmit { if state.canSave { store.save() } }
                    .onChange(of: name) { _, text in store.setName(text: text) }
                if let error = state.nameError { ErrorLine(text: error) }
            }
        }
        .onAppear { nameFocused = true }
        #if os(tvOS)
        // Over the app the editor is a panel, and the screen under it keeps focus until it is moved.
        .task { await FocusRestore.restore($nameFocused, to: true) }
        #endif
        Text(editor.avatar).font(.headline)
        FlowLayout(spacing: 12) {
            ForEach(Array(state.avatars.enumerated()), id: \.element.avatar.id) { index, choice in
                Button { store.selectAvatar(id: choice.avatar.id) } label: {
                    Circle()
                        .fill(Color(argb: choice.avatar.background))
                        .frame(width: 48, height: 48)
                        .overlay {
                            if choice.selected {
                                Image(systemName: "checkmark").foregroundStyle(Color(argb: choice.avatar.foreground))
                            }
                        }
                        .focusRing(radius: 24)
                }
                .buttonStyle(TileButtonStyle())
                #if os(macOS)
                .tooltip(choice.avatar.name)
                #endif
                .focused($focusedAvatar, equals: choice.avatar.id)
                .accessibilityLabel(choice.avatar.name)
                .accessibilityAddTraits(choice.selected ? .isSelected : [])
                .accessibilityIdentifier("editor.avatar.\(index)")
            }
        }
        .focusSectionIfAvailable()
        #if os(tvOS)
        .defaultFocus($focusedAvatar, (state.avatars.first(where: \.selected) ?? state.avatars.first)?.avatar.id, priority: .userInitiated)
        #endif
        pinSection
        KidsSection(kids: state.kids, store: store)
    }

    /// "No PIN" / "Locked with a PIN", set or change it, remove it, and what a PIN is.
    private var pinSection: some View {
        let editor = T.shared.profiles.editor
        return VStack(alignment: .leading, spacing: 12) {
            Label(state.pinStatus, systemImage: "lock").font(.headline)
            // Wraps rather than truncating where the height is tight (the Mac sheet).
            Text(editor.pinNote).foregroundStyle(Palette.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                ActionButton(
                    title: state.hasPin ? editor.changePin : editor.setPin,
                    id: "editor.pin.set",
                    fullWidth: false,
                    action: store.startPinSetup
                )
                .focused($pinFocused)
                if state.hasPin {
                    ActionButton(title: editor.removePin, id: "editor.pin.remove", prominence: .plain, fullWidth: false, action: store.removePin)
                }
            }
            // The buttons' glass (both on Apple TV), one row of it.
            .glassGroup(spacing: 8)
            #if os(tvOS)
            // As wide as the section: Down from any avatar reaches Set PIN at the line's start.
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            .defaultFocus($pinFocused, true, priority: .userInitiated)
            #endif
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
    }
}

/// The editor's error line and buttons: Save (Create), the page's Cancel and Delete profile, or the delete confirmation
/// in their place.
private struct EditorActions: View {
    let state: ProfileEditorUiState
    let store: ProfileEditorStore

    var body: some View {
        let editor = T.shared.profiles.editor
        if let error = state.error { ErrorLine(text: error) }
        if let confirmation = state.deleteConfirmation {
            DeleteConfirmation(text: confirmation, deleting: state.saving, store: store)
        } else {
            if state.saving { ProgressLine(text: nil) }
            ActionButton(
                title: state.creating ? editor.create : editor.save,
                id: "editor.save",
                prominence: .primary,
                systemImage: "checkmark",
                enabled: state.canSave,
                action: store.save
            )
            PageDismissButton()
            if state.canDelete {
                ActionButton(title: editor.delete, id: "editor.delete", prominence: .destructive, systemImage: "trash", action: store.requestDelete)
            }
        }
    }
}

/// The editor's Kids profile section, a panel like the PIN's: the switch, why it can't
/// be turned on (`blockedReason`: no other profile that isn't a kids one has a PIN, or the profile isn't new), then
/// while it is on the highest rating (Little, Kids or Teen, each with the ratings it takes in, the chosen one checked),
/// Allow unrated titles, the Home rows it starts with (optional), each with its switch, the titles a parent allowed and
/// blocked one by one, each with its remove button (a line saying there are none yet otherwise), and what a kids
/// profile takes and doesn't do. The settings' rows on every system: switches (`SettingsToggleRow`), and the ratings as
/// rows that pick on a tap, a click or Select (on Apple TV the system's rows, as its switches are); on Apple TV each
/// title's row is a focus section, so Up and Down reach its remove button.
///
/// Accessibility identifiers: `editor.kids` (the switch), `editor.kids.blocked` (why it can't be turned on),
/// `editor.kids.cap.<index>`, `editor.kids.unrated`, `editor.kids.catalog.<index>`, `editor.kids.allowed.<index>` and
/// `editor.kids.blockedTitle.<index>` (each title's remove button), `editor.kids.allowed.none`,
/// `editor.kids.blockedTitle.none`, `editor.kids.leaving`.
private struct KidsSection: View {
    let kids: KidsSectionUiState
    let store: ProfileEditorStore

    var body: some View {
        let text = T.shared.profiles.editor.kids
        VStack(alignment: .leading, spacing: 12) {
            SettingsToggleRow(title: text.title, subtitle: text.subtitle, checked: kids.enabled, id: "editor.kids", enabled: kids.canSwitch) {
                store.setKids(enabled: $0)
            }
            if let reason = kids.blockedReason {
                SettingsNoteLabel(text: reason, systemImage: "info.circle")
                    .accessibilityIdentifier("editor.kids.blocked")
            }
            if kids.enabled {
                Text(text.capTitle).font(.headline)
                ForEach(Array(kids.caps.enumerated()), id: \.offset) { index, cap in
                    capRow(cap, id: "editor.kids.cap.\(index)")
                }
                SettingsToggleRow(
                    title: text.allowUnrated,
                    subtitle: text.allowUnratedNote,
                    checked: kids.allowUnrated,
                    id: "editor.kids.unrated"
                ) { store.setAllowUnrated(allow: $0) }
                if !kids.homeCatalogs.isEmpty {
                    Text(text.homeRows).font(.headline)
                    ForEach(Array(kids.homeCatalogs.enumerated()), id: \.offset) { index, catalog in
                        SettingsToggleRow(
                            title: catalog.title,
                            subtitle: catalog.subtitle,
                            checked: catalog.selected,
                            id: "editor.kids.catalog.\(index)"
                        ) { store.setHomeCatalog(index: Int32(index), selected: $0) }
                    }
                    SettingsNoteLabel(text: text.homeRowsNote, systemImage: "house")
                }
                titles(text.allowed, none: text.allowedNone, kids.allowed, id: "editor.kids.allowed") { store.removeAllowed(key: $0) }
                titles(text.blocked, none: text.blockedNone, kids.blocked, id: "editor.kids.blockedTitle") { store.removeBlocked(key: $0) }
                SettingsNoteLabel(text: text.leaving, systemImage: "lock").accessibilityIdentifier("editor.kids.leaving")
                SettingsNoteLabel(text: text.off, systemImage: "nosign")
                SettingsNoteLabel(text: text.addons, systemImage: "puzzlepiece.extension")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
    }

    /// One step of the highest rating: its name and ratings, checked while chosen; picking it chooses it. VoiceOver reads
    /// the chosen one as selected.
    private func capRow(_ cap: KidsCapChoice, id: String) -> some View {
        Button { store.setKidsCap(step: cap.step) } label: {
            HStack(spacing: 12) {
                SettingsRowText(title: cap.title, subtitle: cap.ages)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "checkmark")
                    .opacity(cap.selected ? 1 : 0)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        #if !os(tvOS)
        .buttonStyle(.plain)
        #endif
        .accessibilityAddTraits(cap.selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    /// The titles a parent allowed or blocked under `header`: each its name and kind with a remove button named by its
    /// `removeLabel` (`<id>.<index>`, `onRemove` with its key), or `none` (`<id>.none`) when there are none.
    @ViewBuilder
    private func titles(_ header: String, none: String, _ rows: [KidsTitleRow], id: String, onRemove: @escaping (String) -> Void) -> some View {
        Text(header).font(.headline)
        if rows.isEmpty {
            SettingsNoteLabel(text: none, systemImage: "info.circle").accessibilityIdentifier("\(id).none")
        }
        ForEach(Array(rows.enumerated()), id: \.element.key) { index, row in
            HStack(spacing: 12) {
                SettingsRowText(title: row.title, subtitle: row.subtitle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                IconButton(systemImage: "minus", label: row.removeLabel, id: "\(id).\(index)") { onRemove(row.key) }
            }
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }
}

/// The store's delete question with Delete and Cancel; Cancel, the safe choice, takes focus.
private struct DeleteConfirmation: View {
    let text: String
    let deleting: Bool
    let store: ProfileEditorStore

    @FocusState private var cancelFocused: Bool

    var body: some View {
        let editor = T.shared.profiles.editor
        VStack(alignment: .leading, spacing: 12) {
            Text(text)
            if deleting { ProgressLine(text: nil) }
            HStack(spacing: 8) {
                ActionButton(
                    title: editor.deleteAction,
                    id: "editor.delete.confirm",
                    prominence: .destructive,
                    systemImage: "trash",
                    fullWidth: false,
                    enabled: !deleting,
                    action: store.confirmDelete
                )
                ActionButton(title: editor.cancel, id: "editor.delete.cancel", prominence: .plain, fullWidth: false, enabled: !deleting, action: store.cancelDelete)
                    .focused($cancelFocused)
            }
            // The buttons' glass (both on Apple TV), one row of it.
            .glassGroup(spacing: 8)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
        .onAppear { cancelFocused = true }
    }
}
