import SwiftUI
import TortugaKit

/// The Watch together route of `app`, pushed over Home (`AppSession.openWatchTogether`: an invite link): its
/// `WatchStore` is kept by the navigator while the route is on the stack (under "watch"), so the screen outlasts a trip
/// to the player.
struct WatchTogetherRoute: View {
    let app: AppModel

    /// Popped, the page draws what it showed while it slides out (`LastDrawn`).
    @State private var drawn = LastDrawn<RetainedStore<WatchStore, WatchLobbyUiState>>()

    var body: some View {
        if let model = drawn(retained()) {
            WatchTogetherScreen(state: model.observer.state, store: model.store)
                .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<WatchStore, WatchLobbyUiState>? {
        let session = app.session
        return app.retained(
            RouteWatchTogether.shared,
            key: "watch",
            create: { session.watchStore() },
            state: { $0.state },
            release: { $0.release() }
        )
    }
}

/// Watch together, as a native form like the Settings panes; a room is started from the player.
/// Outside a room: the intro, the relay's check (while it runs, or why it failed with Try again), Join a room (one row:
/// a plain field that takes a code, with or without its hyphen, or a pasted invite link and joins from the keyboard's
/// return key, Join at its end, and why its text isn't one under it), the recent rooms (a row enters one again, its code
/// as the room shows it; a swipe on iPhone and iPad, the context menu, or on Apple TV a long press of Select removes
/// it), and the relay rooms go through, a row naming it that opens its editor (`WatchRelaySheet`). In
/// a room: the room (`WatchRoomSections`), where a guest waits for the host's title, then Leave and the host's End
/// (`WatchRoomExitSection`). An invite link that needs a yes asks it (`WatchLobbyUiState.inviteQuestion`): in a room,
/// the room's Leave question; out of one, whether to join a room on another relay. Over both: what runs ("Joining the
/// room…", the actions disabled meanwhile), the last failure with its dismiss button, and the room's latest event as a
/// toast at the bottom. Every text comes from `state`.
///
/// tvOS: the room's first button (Make host, else the host's choices, else Leave) takes focus once in a room, the join
/// field once in the lobby (as the screen opens, after leaving or ending a room, after a join that failed), so focus
/// never stays on a button that went. The join field opens the system keyboard, whose Join key joins: no Join button.
///
/// Accessibility identifiers: `watch.relay.status`, `watch.relay.retry`, `watch.join.field`, `watch.join` (not tvOS),
/// `watch.join.error`, `watch.recent.<i>` (with `.remove`), `watch.relay.setting`, `watch.busy`, `watch.failure`,
/// `watch.failure.dismiss`, `watch.toast`, and the room's (`WatchRoomSections`, `WatchRoomExitSection`) and the relay
/// editor's (`WatchRelaySheet`).
struct WatchTogetherScreen: View {
    let state: WatchLobbyUiState
    let store: WatchStore

    /// The join field's text: typing doesn't wait for the store's state, which follows it.
    @State private var joinText = ""
    /// The relay's editor is open over the screen.
    @State private var editingRelay = false
    #if os(tvOS)
    @FocusState private var focus: String?
    #endif

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                if let failure = state.failure {
                    Section { failureRow(failure) }
                }
                if let busy = state.busy {
                    Section {
                        ProgressLine(text: busy)
                            .accessibilityElement(children: .combine)
                            .accessibilityIdentifier("watch.busy")
                    }
                }
                if let room = state.room {
                    WatchRoomSections(room: room, actions: roomActions, focus: tvFocus)
                    WatchRoomExitSection(room: room, actions: roomActions, focus: tvFocus)
                } else {
                    lobby
                }
            }
            #if os(iOS)
            // The keyboard can cover the join field's footer: once it says why the text isn't a room code, the field
            // goes to the middle of what the keyboard leaves, the footer under it.
            .onChange(of: state.joinError) { _, error in
                if error != nil { proxy.scrollTo(Self.joinRow, anchor: .center) }
            }
            #endif
        }
        .settingsForm(idle: state.busy != nil)
        .disabled(state.busy != nil)
        .pageTitle(state.title)
        .toast(state.toast, id: "watch.toast", onShown: store.toastShown)
        // The field keeps its own text (the store's state arrives a moment later and would undo fast typing): it
        // starts from the store's, and Join empties it, as the store does.
        .onAppear { joinText = store.state.value.joinText }
        .onChange(of: joinText) { _, text in
            if text != store.state.value.joinText { store.setJoinText(text: text) }
        }
        .settingsSheet(isPresented: $editingRelay) {
            WatchRelaySheet(row: store.state.value.relaySetting, store: store) { editingRelay = false }
        }
        // An invite link to another room asks the room's own Leave question first; out of a room, one to a room on
        // another relay asks whether to join it there.
        .confirmation(
            state.inviteQuestion.map { Confirmation($0, id: state.room == nil ? "watch.invite" : "watch.room.leave") },
            onConfirm: store.joinInvite,
            onCancel: store.dismissInvite
        )
        #if os(tvOS)
        .task(id: focusTarget) { await FocusRestore.restore($focus, to: focusTarget) }
        #endif
    }

    @ViewBuilder
    private var lobby: some View {
        Section {
            relayRow
        } header: {
            Text(state.intro)
                .textCase(nil)
                .font(.subheadline)
                .foregroundStyle(Palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 4)
        }
        Section {
            joinField
        } header: {
            Text(state.joinTitle)
        } footer: {
            #if os(tvOS)
            // Always there, empty without an error: a footer that comes or goes rebuilds the section, which puts the
            // system keyboard away after the first letter.
            joinError(state.joinError ?? "")
            #else
            if let error = state.joinError { joinError(error) }
            #endif
        }
        if !state.recentRooms.isEmpty {
            Section(state.recentTitle) {
                ForEach(Array(state.recentRooms.enumerated()), id: \.element.key) { index, recent in
                    recentRow(recent, index: index)
                }
            }
        }
        Section {
            let relay = state.relaySetting
            SettingsLinkRow(title: relay.title, subtitle: relay.subtitle, value: relay.valueLabel, id: "watch.relay.setting") {
                editingRelay = true
            }
        }
    }

    /// The relay's check: a spinner while it runs; why it failed, with Try again; nothing once it answered.
    @ViewBuilder
    private var relayRow: some View {
        if let status = state.relay.status {
            if state.relay.unreachable {
                HStack(spacing: 12) {
                    ErrorLine(text: status)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("watch.relay.status")
                    Button(state.relay.retryLabel, action: store.retryRelay)
                        #if !os(tvOS)
                        .glassButtonStyle()
                        #endif
                        .accessibilityIdentifier("watch.relay.retry")
                }
            } else {
                ProgressLine(text: status)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("watch.relay.status")
            }
        }
    }

    /// The field and Join in one row on iOS and the Mac: the field plain on the row (no fill or label of its own, its
    /// prompt inside it), joining from the return key, Join an inline text button at the row's end. Apple TV: the field
    /// alone, which opens the keyboard and joins from its Join key (`onSubmit`), why its text isn't a room code under it.
    @ViewBuilder
    private var joinField: some View {
        #if os(tvOS)
        TextField(state.joinHint, text: $joinText)
            .font(.body.monospaced())
            .autocorrectionDisabled()
            .submitLabel(.join)
            .onSubmit(join)
            .focused($focus, equals: Self.joinRow)
            .accessibilityIdentifier("watch.join.field")
        #else
        HStack(spacing: 12) {
            TextField(text: $joinText, prompt: Text(state.joinHint)) {
                Text(state.joinTitle)
            }
            .labelsHidden()
            .textFieldStyle(.plain)
            .font(.body.monospaced())
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            .keyboardType(.asciiCapable)
            .submitLabel(.join)
            #endif
            .onSubmit(join)
            .accessibilityIdentifier("watch.join.field")
            Button(action: join) {
                Text(state.joinLabel).hitTargetLabel()
            }
                .buttonStyle(.borderless)
                .fontWeight(.semibold)
                .disabled(!state.canJoin)
                .accessibilityIdentifier("watch.join")
                .hitTarget()
        }
        .id(Self.joinRow)
        #endif
    }

    /// The join row's scroll identity.
    private static let joinRow = "join"

    /// Why the join field's text isn't a room code (`watch.join.error` while there is one).
    private func joinError(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(Palette.error)
            .accessibilityIdentifier(text.isEmpty ? "" : "watch.join.error")
    }

    private func recentRow(_ recent: RecentRoomUi, index: Int) -> some View {
        let id = "watch.recent.\(index)"
        let remove = { store.removeRecent(code: recent.code, relay: recent.relay) }
        return Button {
            store.enterRecent(code: recent.code, relay: recent.relay)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "clock.arrow.circlepath").foregroundStyle(.secondary).accessibilityHidden(true)
                Text(recent.label)
                    .font(.body.monospaced().weight(.semibold))
                Spacer(minLength: 12)
                Text(recent.detail)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .contentShape(Rectangle())
        }
        .formRowButtonStyle()
        .accessibilityIdentifier(id)
        .itemMenu(
            entries: [ItemMenuEntry(label: recent.removeLabel, systemImage: "trash", id: "\(id).remove", action: remove)],
            probe: "\(id).menu"
        )
        #if os(iOS)
        .swipeActions {
            Button(role: .destructive, action: remove) {
                Label(recent.removeLabel, systemImage: "trash")
            }
        }
        #endif
    }

    private func failureRow(_ failure: String) -> some View {
        HStack(spacing: 12) {
            ErrorLine(text: failure)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("watch.failure")
            Button(action: store.dismissFailure) {
                Image(systemName: "xmark").hitTargetLabel()
            }
            #if os(tvOS)
            .glassButtonStyle()
            .buttonBorderShape(.circle)
            #else
            .buttonStyle(.borderless)
            #endif
            #if os(macOS)
            .tooltip(state.dismissLabel)
            #endif
            .accessibilityLabel(state.dismissLabel)
            .accessibilityIdentifier("watch.failure.dismiss")
            .hitTarget()
        }
    }

    private var roomActions: WatchRoomActions {
        WatchRoomActions(
            makeHost: { store.makeHost(peerId: $0) },
            kick: { store.kick(peerId: $0) },
            setControlMode: { store.setControlMode(mode: $0) },
            setLocked: { store.setLocked(locked: $0) },
            leave: store.leave,
            end: store.end,
            joinPlayback: store.joinPlayback
        )
    }

    private func join() {
        store.setJoinText(text: joinText)
        guard store.state.value.canJoin else { return }
        store.join()
        joinText = ""
    }

    private var tvFocus: FocusState<String?>.Binding? {
        #if os(tvOS)
        $focus
        #else
        nil
        #endif
    }

    #if os(tvOS)
    /// What takes focus: the room's first button in a room, the join field in the lobby, nothing while busy (the form
    /// is disabled).
    private var focusTarget: String? {
        guard state.busy == nil else { return nil }
        guard let room = state.room else { return Self.joinRow }
        return WatchRoomSections.focusKeys(room).first
    }
    #endif
}

extension RecentRoomUi {
    /// A recent room is its code on its relay.
    var key: String { "\(code)|\(relay)" }
}

/// Editing the relay rooms go through, in a form: a field with the stored relay (empty for Open Watch Party's public
/// relays, an example address as the placeholder), what the setting does under it, or why Save refused the text. Save
/// stores it (an empty field goes back to the public relays), "Use the public relays" (while another is set) goes back
/// to them, Cancel leaves it as it was. tvOS types it with the system keyboard.
///
/// Accessibility identifiers: `watch.relay.<field|error|save|reset|cancel>`.
private struct WatchRelaySheet: View {
    let row: WatchRelayRow
    let store: WatchStore
    let onClose: () -> Void

    @State private var text: String
    /// Save refused the text: it isn't a server's address.
    @State private var refused = false

    init(row: WatchRelayRow, store: WatchStore, onClose: @escaping () -> Void) {
        self.row = row
        self.store = store
        self.onClose = onClose
        _text = State(initialValue: row.value)
    }

    var body: some View {
        let save = SheetAction(title: row.saveLabel, id: "watch.relay.save") {
            if store.setRelay(url: text) {
                onClose()
            } else {
                refused = true
            }
        }
        let reset = SheetAction(title: row.resetLabel, id: "watch.relay.reset", role: .destructive, enabled: row.custom) {
            store.resetRelay()
            onClose()
        }
        let cancel = SheetAction(title: T.shared.settings.cancel, id: "watch.relay.cancel", action: onClose)
        NavigationStack {
            Form {
                Section {
                    TextField(row.title, text: $text, prompt: Text(row.placeholder))
                        .labelsHidden()
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        #endif
                        .onSubmit(save.action)
                        .accessibilityIdentifier("watch.relay.field")
                } footer: {
                    if refused {
                        Text(row.invalid)
                            .foregroundStyle(Palette.error)
                            .accessibilityIdentifier("watch.relay.error")
                    } else {
                        Text(row.subtitle)
                    }
                }
                #if !os(tvOS)
                if row.custom {
                    Section {
                        Button(reset.title, role: .destructive, action: reset.action)
                            .accessibilityIdentifier(reset.id)
                    }
                }
                #endif
                SheetActionsSection(actions: row.custom ? [save, reset, cancel] : [save, cancel])
            }
            .settingsForm()
            .pageTitle(row.title)
            .sheetToolbar(cancel: cancel, confirm: save)
        }
        .backAction(onClose)
        .onChange(of: text) { refused = false }
    }
}
