import SwiftUI
import TortugaKit

/// The profile picker of `root` (`AccountDestination.Profiles`) and the editor it opens. The picker's store stays
/// while the editor covers it, so managing continues where it was when the editor closes.
struct ProfilesRoute: View {
    let root: RootModel

    @State private var editor: EditorTarget?

    var body: some View {
        ZStack {
            LiveStore(
                create: { root.graph.profilePickerStore(gate: root.gate, host: PickerHost { editor = $0 }) },
                state: { $0.state },
                release: { $0.release() }
            ) { store, state in
                ProfilePickerScreen(state: state, store: store)
            }
            .opacity(editor == nil ? 1 : 0)
            .disabled(editor != nil)
            .accessibilityHidden(editor != nil)
            if let editor {
                ProfileEditorRoute(graph: root.graph, profileId: editor.profileId) { self.editor = nil }
                    .id(editor)
            }
        }
    }
}

/// The profile the editor opens: a new one for a nil `profileId`.
private struct EditorTarget: Hashable {
    let profileId: String?
}

@MainActor
private final class PickerHost: @preconcurrency ProfilePickerHost {
    private let open: (EditorTarget) -> Void

    init(open: @escaping (EditorTarget) -> Void) { self.open = open }

    func createProfile() { open(EditorTarget(profileId: nil)) }

    func editProfile(profileId: String) { open(EditorTarget(profileId: profileId)) }
}

/// "Who's watching?": the account's profiles as avatar tiles (locked ones marked), "Add profile",
/// managing (a tile opens its editor), going back to the running profile when switching, and the sync status line. A
/// locked profile's PIN pad takes the screen while it is asked. Every text comes from `state` or `T`.
struct ProfilePickerScreen: View {
    let state: ProfilePickerUiState
    let store: ProfilePickerStore

    @Environment(\.tortugaDimens) private var dimens
    @FocusState private var focusedTile: String?

    var body: some View {
        if let prompt = state.pinPrompt {
            PinPad(
                title: prompt.title,
                entered: Int(prompt.entered),
                length: Int(prompt.length),
                enteredText: prompt.enteredText,
                message: prompt.lockedOutText ?? prompt.error,
                acceptsInput: prompt.acceptsInput,
                onDigit: { store.pinDigit(digit: Int32($0)) },
                onBackspace: store.pinBackspace,
                onCancel: store.cancelPin
            ) {
                ProfileAvatarTile(initial: prompt.initial, avatar: prompt.avatar, size: 72)
            }
        } else {
            switch onEnum(of: state.body) {
            case .loading:
                LoadingStateView(text: T.shared.profiles.loading)
            case .empty(let empty):
                picker(profiles: [], emptyText: empty.text)
            case .tiles(let tiles):
                picker(profiles: tiles.profiles, emptyText: nil)
            }
        }
    }

    /// The focus id of "Add profile".
    private static let addTile = "add"

    /// The title, `emptyText` (the prompt to create a profile, while there is none), the tiles of `profiles` then Add
    /// profile, the buttons and the sync status line.
    private func picker(profiles tiles: [ProfileTile], emptyText: String?) -> some View {
        let profiles = T.shared.profiles
        return GeometryReader { proxy in
            ScrollView {
                VStack(spacing: dimens.sectionSpacing) {
                    Text(state.title)
                        .font(.largeTitle.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    if let emptyText {
                        Text(emptyText).foregroundStyle(Palette.onSurfaceVariant)
                    }
                    FlowLayout(spacing: dimens.gutter * 2) {
                        ForEach(Array(tiles.enumerated()), id: \.element.id) { index, tile in
                            ProfileTileButton(tile: tile) { store.select(profileId: tile.id) }
                                .focused($focusedTile, equals: tile.id)
                                .accessibilityIdentifier("profiles.tile.\(index)")
                        }
                        AddProfileButton(action: store.addProfile)
                            .focused($focusedTile, equals: Self.addTile)
                            .accessibilityIdentifier("profiles.add")
                    }
                    .frame(maxWidth: 960)
                    .focusSectionIfAvailable()
                    #if os(tvOS)
                    // A move into the tiles lands on the first, wherever it comes from.
                    .defaultFocus($focusedTile, tiles.first?.id ?? Self.addTile, priority: .userInitiated)
                    #endif
                    HStack(spacing: 12) {
                        if state.managing {
                            ActionButton(title: profiles.done, id: "profiles.done", prominence: .primary, systemImage: "checkmark", fullWidth: false, action: store.doneManaging)
                        } else if !tiles.isEmpty {
                            ActionButton(title: profiles.manage, id: "profiles.manage", systemImage: "pencil", fullWidth: false, action: store.manage)
                        }
                        // tvOS: the remote's Back goes back.
                        #if !os(tvOS)
                        if state.canClose {
                            ActionButton(
                                title: T.shared.nav.back,
                                id: "profiles.close",
                                prominence: .plain,
                                systemImage: "chevron.backward",
                                fullWidth: false,
                                action: store.close
                            )
                            .keyboardShortcut(.cancelAction)
                        }
                        #endif
                    }
                    #if os(tvOS)
                    // As wide as the screen: Down from any tile reaches the button, centred under them.
                    .frame(maxWidth: .infinity)
                    .focusSection()
                    #endif
                    Text(state.syncStatus)
                        .font(.footnote)
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
                .padding(dimens.screenPadding)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
        .background(Palette.background)
        .backAction(state.canClose ? store.close : nil)
        .onAppear { focusedTile = tiles.first?.id ?? Self.addTile }
    }
}

/// A profile: its avatar (with its badge: a pencil while managing, a lock for a PIN, a check on the current one) and
/// name; the whole tile is the button. To accessibility it is named by the name, its value the tile's `stateText`.
private struct ProfileTileButton: View {
    let tile: ProfileTile
    let action: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                ProfileAvatarTile(initial: tile.initial, avatar: tile.avatar, size: dimens.avatar)
                    .overlay(alignment: .bottomTrailing) {
                        if let badgeSymbol {
                            Image(systemName: badgeSymbol)
                                .font(.footnote.weight(.semibold))
                                .frame(width: 32, height: 32)
                                .background(Palette.surfaceContainerHighest, in: Circle())
                        }
                    }
                    .focusRing(radius: dimens.avatar / 2)
                // At the accessibility text sizes whole on as many lines as it takes, twice as wide as the avatar's
                // column.
                let large = dynamicTypeSize.isAccessibilitySize
                Text(tile.name)
                    .font(.headline)
                    .lineLimit(large ? nil : 1)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: large)
                    .frame(maxWidth: (dimens.avatar + 24) * (large ? 2 : 1))
            }
            .padding(12)
        }
        .buttonStyle(TileButtonStyle())
        .accessibilityLabel(tile.name)
        .accessibilityValue(tile.stateText ?? "")
    }

    private var badgeSymbol: String? {
        switch tile.badge {
        case .edit: "pencil"
        case .lock: "lock.fill"
        case .current: "checkmark"
        case nil: nil
        }
    }
}

/// "Add profile": an empty avatar with a plus.
private struct AddProfileButton: View {
    let action: () -> Void

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Circle()
                    .fill(Palette.surfaceContainerHigh)
                    .frame(width: dimens.avatar, height: dimens.avatar)
                    .overlay { Image(systemName: "plus").font(.largeTitle).accessibilityHidden(true) }
                    .focusRing(radius: dimens.avatar / 2)
                let large = dynamicTypeSize.isAccessibilitySize
                Text(T.shared.profiles.add)
                    .font(.headline)
                    .lineLimit(large ? nil : 1)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: large)
                    .frame(maxWidth: (dimens.avatar + 24) * (large ? 2 : 1))
            }
            .padding(12)
        }
        .buttonStyle(TileButtonStyle())
    }
}
