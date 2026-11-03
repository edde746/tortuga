import SwiftUI
import TortugaKit

// Watch together in the player (`PlayerUiState.watch`): the session indicator in the controls, which opens the room
// panel (`PlayerMenu.watchTogether`: a sheet on iOS and the Mac, the Options tab's page of the panel on Apple TV), the
// pill over the video ("Waiting for Anna…"), a guest's search for its file and its prompt in place of the loading
// spinner (`PlayerUiState.centre`), the line saying who controls playback while this device may not (its controls
// disabled, `PlayerUiState.canControl`) or that its player paused here alone (`PlayerWatchUi.lockLine`), and the room's
// toasts, which the store shows as the OSD.

/// The session indicator: how many watch together, the host's badge on the host's device, the sync symbol while the
/// room waits for someone; it opens the room panel. Where the badge doesn't fit (an iPhone's top bar in portrait), a
/// crown stands for it, else the count goes alone. Accessibility identifier `player.watch`, its label "3 watching
/// together".
struct PlayerWatchIndicator: View {
    let indicator: WatchIndicatorUi
    let store: PlayerStore

    /// The symbols' and count's size (`PlayerMetrics.icon` at the default text size), following Dynamic Type as the
    /// player's buttons' symbols do (`PlayerIconButton`).
    @ScaledMetric(relativeTo: .body) private var symbolSize: CGFloat = PlayerMetrics.icon

    var body: some View {
        Button {
            store.dispatch(command: PlayerCommandOpenMenu(menu: .watchTogether))
        } label: {
            ViewThatFits(in: .horizontal) {
                content(host: .badge)
                content(host: .crown)
                content(host: nil)
            }
            .font(.system(size: symbolSize, weight: .semibold))
            #if !os(tvOS)
            .padding(.horizontal, 10)
            .frame(minHeight: PlayerMetrics.button)
            .contentShape(Capsule())
            #endif
        }
        #if os(tvOS)
        .buttonStyle(PlayerTVButtonStyle(shape: Capsule(), insets: EdgeInsets(top: 14, leading: 22, bottom: 14, trailing: 22)))
        #else
        .buttonStyle(PlayerPressStyle())
        #endif
        #if os(macOS)
        .tooltip(indicator.label)
        #endif
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(indicator.label)
        .accessibilityValue(indicator.isHost ? indicator.hostBadge : "")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("player.watch")
        #if os(iOS)
        .accessibilityShowsLargeContentViewer { Label(indicator.label, systemImage: "person.2.fill") }
        #endif
    }

    /// How the host's device marks itself: the badge's text, or a crown where that doesn't fit.
    private enum HostMark { case badge, crown }

    private func content(host mark: HostMark?) -> some View {
        HStack(spacing: mark == .badge ? 6 : 3) {
            Image(systemName: "person.2.fill")
            Text(Int(indicator.count), format: .number).monospacedDigit()
            if indicator.isHost {
                switch mark {
                case .badge:
                    Text(indicator.hostBadge)
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Palette.onVideo.opacity(0.25)))
                case .crown:
                    Image(systemName: "crown.fill").font(.caption2.weight(.bold))
                case nil:
                    EmptyView()
                }
            }
            if indicator.syncing {
                Image(systemName: "arrow.triangle.2.circlepath").font(.caption.weight(.bold))
            }
        }
        .lineLimit(1)
        .fixedSize()
    }
}

/// "Waiting for Anna, Ben…" or "Syncing…", over the video (`player.watch.pill`).
struct PlayerWatchPill: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "person.2.fill")
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16)
            #if os(macOS)
            // Back's height: on the title row it sits level with back and as tall (`PlayerOverlay`).
            .frame(height: PlayerMetrics.button)
            #else
            .padding(.vertical, 8)
            #endif
            .playerGlass(in: Capsule())
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("player.watch.pill")
    }
}

/// A guest finding a file that matches the host's ("Finding a file that matches Sam's…"), in the loading
/// spinner's place (`player.watch.matching`).
struct PlayerWatchMatching: View {
    let text: String

    var body: some View {
        VStack(spacing: 12) {
            ProgressView()
                .largeSpinner()
                .tint(Palette.onVideo)
            Text(text)
                .font(.subheadline.weight(.medium))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .playerGlass(in: Capsule())
        }
        .padding(24)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("player.watch.matching")
    }
}

/// What waits for a guest (its file is another length than the host's, there is none, it declined the closest one, a
/// pick that failed): the message, what it means, and its actions (Play anyway, Choose another stream, Don't play), the
/// first the prominent one, which takes focus on Apple TV (`PlayerFocus.watchPrompt`). The message is announced as it
/// shows and changes. Accessibility identifiers `player.watch.prompt`, `player.watch.prompt.<i>`.
struct PlayerWatchPromptCard: View {
    let prompt: WatchPromptUi
    let store: PlayerStore
    let focus: FocusState<PlayerFocus?>.Binding

    @ScaledMetric(relativeTo: .title) private var symbolSize: CGFloat = 36

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "film.stack")
                .font(.system(size: symbolSize))
            Text(prompt.message)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("player.watch.prompt.message")
            if let detail = prompt.detail {
                Text(detail).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            // Side by side where they fit, else one above the other (a phone in portrait).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PlayerMetrics.promptActionSpacing) { actions }
                VStack(spacing: PlayerMetrics.promptActionSpacing) { actions }
            }
            // The buttons' glass, one row or column of it, on the card's.
            .glassGroup(spacing: PlayerMetrics.promptActionSpacing)
            .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: PlayerMetrics.promptMaxWidth)
        .playerGlass(in: RoundedRectangle(cornerRadius: PlayerMetrics.promptRadius, style: .continuous))
        .padding(24)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.watch.prompt")
        .announcing(prompt.message)
    }

    private var actions: some View {
        ForEach(Array(prompt.actions.enumerated()), id: \.offset) { index, action in
            Button(action.label) { store.dispatch(command: PlayerCommandWatch(command: action.command)) }
                .playerActionStyle(prominent: index == 0)
                .modifier(PromptFocus(focus: focus, first: index == 0))
                .accessibilityIdentifier("player.watch.prompt.\(index)")
        }
    }

    private struct PromptFocus: ViewModifier {
        let focus: FocusState<PlayerFocus?>.Binding
        let first: Bool

        func body(content: Content) -> some View {
            if first { content.focused(focus, equals: .watchPrompt) } else { content }
        }
    }
}

/// Who controls playback while this device may not ("Only Sam controls playback in this room"), or that its player
/// paused here alone while the room plays on ("Paused on this device…"), over the timeline, whose controls show
/// disabled, play/pause aside in the second case (`player.watch.locked`).
struct PlayerControlsLockedLine: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "lock.fill")
            .font(.footnote.weight(.semibold))
            .playerMuted()
            .lineLimit(2)
            .shadow(color: .black.opacity(0.4), radius: 6)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("player.watch.locked")
    }
}

#if !os(tvOS)
/// The room panel on iOS (a sheet, half height on iPhone) and the Mac (a sheet over the window), as a form: what runs
/// and the last failure, then the room (`WatchRoomSections`, through the player's commands), this device's offset while
/// it plays the room's media (`PlayerWatchOffsetSection`), Leave and the host's End (`WatchRoomExitSection`), which,
/// confirmed, close it with the room; or the start choices that start a room with what plays. A room that ends some
/// other way (the host removed this device or ended it, the link lost) leaves the sheet open on why. Close (Escape; an
/// iPhone's sheet has the grabber instead) closes it.
struct PlayerWatchSheet: View {
    let watch: PlayerWatchUi
    let store: PlayerStore
    let close: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                if let failure = watch.failure {
                    Section {
                        HStack(spacing: 12) {
                            ErrorLine(text: failure)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .accessibilityElement(children: .combine)
                                .accessibilityIdentifier("player.watch.failure")
                            Button { send(WatchCommandDismissFailure.shared) } label: { Image(systemName: "xmark").hitTargetLabel() }
                                .buttonStyle(.borderless)
                                #if os(macOS)
                                .tooltip(watch.dismissLabel)
                                #endif
                                .accessibilityLabel(watch.dismissLabel)
                                .accessibilityIdentifier("player.watch.failure.dismiss")
                                .hitTarget()
                        }
                    }
                }
                if let busy = watch.busy {
                    Section {
                        ProgressLine(text: busy)
                            .accessibilityElement(children: .combine)
                            .accessibilityIdentifier("player.watch.busy")
                    }
                }
                if let room = watch.room {
                    WatchRoomSections(room: room, actions: roomActions)
                    if let offset = watch.offset {
                        PlayerWatchOffsetSection(offset: offset, run: send)
                    }
                    WatchRoomExitSection(room: room, actions: roomActions)
                } else if watch.startChoices.isEmpty {
                    // A live stream can't start a room; the line says so.
                    Section {
                        Text(watch.startTitle)
                            .accessibilityIdentifier("player.watch.start.none")
                    }
                } else {
                    Section {
                        ForEach(watch.startChoices, id: \.mode) { choice in
                            SettingsLinkRow(
                                title: choice.label,
                                subtitle: choice.detail,
                                systemImage: choice.mode.systemImage,
                                id: "player.watch.start.\(choice.mode.identifier)"
                            ) {
                                send(WatchCommandStart(mode: choice.mode))
                            }
                        }
                    } header: {
                        Text(watch.startTitle)
                    }
                    .disabled(watch.busy != nil)
                }
            }
            .formStyle(.grouped)
            .accessibilityIdentifier("player.watch.panel")
            .pageTitle(watch.title)
            .sheetToolbar(close: SheetAction(title: T.shared.nav.close, id: "player.watch.close", action: close))
        }
        .sheetFrame(.player)
        #if os(iOS)
        .playerSheetBackground()
        #endif
        .tortugaTheme()
    }

    private var roomActions: WatchRoomActions {
        WatchRoomActions(
            makeHost: { send(WatchCommandMakeHost(peerId: $0)) },
            kick: { send(WatchCommandKick(peerId: $0)) },
            setControlMode: { send(WatchCommandSetControlMode(mode: $0)) },
            setLocked: { send(WatchCommandLock(locked: $0)) },
            leave: {
                send(WatchCommandLeave.shared)
                close()
            },
            end: {
                send(WatchCommandEnd.shared)
                close()
            },
            joinPlayback: { send(WatchCommandJoinPlayback.shared) }
        )
    }

    private func send(_ command: any WatchCommand) {
        store.dispatch(command: PlayerCommandWatch(command: command))
    }
}

/// This device's offset from the room (`PlayerWatchUi.offset`) in the room sheet: its title with the value, then its
/// steps and Reset as glass buttons (on as many lines as they take), what it is for under them.
///
/// Accessibility identifiers: `player.watch.offset.value`, `player.watch.offset.<i>` (its actions in order: -5 s, -0.5 s,
/// +0.5 s, +5 s, Reset).
private struct PlayerWatchOffsetSection: View {
    let offset: WatchOffsetUi
    let run: (any WatchCommand) -> Void

    var body: some View {
        Section {
            HStack(spacing: 12) {
                Text(offset.title)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(offset.value)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("player.watch.offset.value")
            }
            .accessibilityElement(children: .contain)
            FlowLayout(spacing: 8) {
                ForEach(Array(offset.actions.enumerated()), id: \.offset) { index, action in
                    Button {
                        run(action.command)
                    } label: {
                        Text(action.label).monospacedDigit()
                    }
                    .accessibilityIdentifier("player.watch.offset.\(index)")
                }
            }
            // A style of their own: in a form row, buttons without one would all take the row's tap.
            .glassButtonStyle()
            .glassGroup(spacing: 8)
        } footer: {
            Text(offset.detail)
        }
    }
}
#endif

#if os(tvOS)
/// The room panel on Apple TV: the Options tab's page (`PlayerMenu.watchTogether`), opened from its row or the session
/// indicator. What runs and the last failure; in a room the code large beside its QR code, who hosts, who controls
/// playback (a guest's line), that the room is locked and what plays, Join playback for a guest that left it, the
/// participants (the host's Make host on a guest's row, its Remove on a row under it, which asks first), the host's
/// choice of who controls playback (the room's checked) and the lock (checked while locked), this device's offset
/// while it plays the room's media (its value, its steps and Reset in a row), Leave and the host's End, which ask
/// first and, confirmed, close the panel with the room (one that ends some other way leaves it open on why); else the
/// start choices, or for a live stream, which can't start a room, the line that says so and Close, which takes focus and
/// closes the panel (focus back on the Options button). Its buttons take focus as `PlayerFocus.menuItem` in order
/// (`PlayerTVWatchPanel.entries`).
struct PlayerTVWatchPanel: View {
    let watch: PlayerWatchUi
    let store: PlayerStore
    let focus: FocusState<PlayerFocus?>.Binding

    /// The question the panel asks.
    @State private var asking: Question?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text(watch.title).font(.headline).padding(.horizontal, 20).padding(.bottom, 8)
                if let busy = watch.busy {
                    ProgressLine(text: busy)
                        .padding(.horizontal, 20)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("player.watch.busy")
                }
                if let failure = watch.failure {
                    ErrorLine(text: failure)
                        .padding(.horizontal, 20)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("player.watch.failure")
                }
                ForEach(Array(Self.entries(watch).enumerated()), id: \.offset) { index, entry in
                    row(entry, index: index)
                }
            }
            // Room for the focused row's lift.
            .padding(12)
        }
        .frame(maxHeight: 600)
        .accessibilityIdentifier("player.watch.panel")
        .confirmation(
            confirmation,
            onConfirm: {
                guard let question = asking else { return }
                asking = nil
                switch question {
                case .leave:
                    send(WatchCommandLeave.shared)
                    store.dispatch(command: PlayerCommandCloseMenu.shared)
                case .end:
                    send(WatchCommandEnd.shared)
                    store.dispatch(command: PlayerCommandCloseMenu.shared)
                case .kick(let peerId): send(WatchCommandKick(peerId: peerId))
                }
            },
            onCancel: { asking = nil }
        )
        // A question whose button went (the participant left, this device hosts no more) goes with it.
        .onChange(of: confirmation == nil) { _, gone in
            if gone { asking = nil }
        }
    }

    /// Leave, End, or Remove for the participant with this peer id.
    enum Question: Equatable {
        case leave
        case end
        case kick(String)
    }

    /// The question `asking` names, while its button is there.
    private var confirmation: Confirmation? {
        guard let asking, let room = watch.room else { return nil }
        switch asking {
        case .leave:
            return Confirmation(room.leave, id: "watch.room.leave")
        case .end:
            return room.end.map { Confirmation($0, id: "watch.room.end") }
        case .kick(let peerId):
            return room.participants.first { $0.peerId == peerId }?.kick.map { Confirmation($0, id: "watch.room.kick") }
        }
    }

    /// One line of the panel, in order.
    enum Entry {
        case start(ControlModeChoice)
        case code(WatchRoomUi)
        case participant(ParticipantUi, index: Int, room: WatchRoomUi)
        /// The host's Remove for the participant on the line above.
        case kick(ParticipantUi, index: Int, ConfirmAction)
        /// A heading over the lines under it.
        case heading(String)
        /// A live stream's line: no room can start with it.
        case liveNote(String)
        /// Closes the panel, where it offers nothing else (a live stream's).
        case close
        case controlChoice(ControlModeChoice, selected: Bool)
        case lock(ToggleUi)
        case offset(WatchOffsetUi)
        case dismissFailure(String)
        case joinPlayback(String)
        case leave(WatchRoomUi)
        case end(ConfirmAction)

        /// How many of the line's buttons take focus.
        var focusCount: Int {
            switch self {
            case .code, .heading, .liveNote: 0
            case .participant(let participant, _, _): participant.canMakeHost ? 1 : 0
            case .offset(let offset): offset.actions.count
            case .start, .kick, .controlChoice, .lock, .dismissFailure, .joinPlayback, .leave, .end, .close: 1
            }
        }
    }

    static func entries(_ watch: PlayerWatchUi) -> [Entry] {
        var entries: [Entry] = []
        if watch.failure != nil { entries.append(.dismissFailure(watch.dismissLabel)) }
        if let room = watch.room {
            entries.append(.code(room))
            if let join = room.joinPlayback { entries.append(.joinPlayback(join)) }
            for (index, participant) in room.participants.enumerated() {
                entries.append(.participant(participant, index: index, room: room))
                if let kick = participant.kick { entries.append(.kick(participant, index: index, kick)) }
            }
            if !room.controlChoices.isEmpty {
                entries.append(.heading(room.controlTitle))
                entries += room.controlChoices.map { Entry.controlChoice($0, selected: $0.mode == room.selectedMode) }
            }
            if let lock = room.lock { entries.append(.lock(lock)) }
            if let offset = watch.offset { entries.append(.offset(offset)) }
            entries.append(.leave(room))
            if let end = room.end { entries.append(.end(end)) }
        } else if watch.startChoices.isEmpty {
            entries += [.liveNote(watch.startTitle), .close]
        } else {
            entries += watch.startChoices.map(Entry.start)
        }
        return entries
    }

    /// The focus key of the `index`th entry's first button: the count of the focusable buttons before it.
    private func focusIndex(_ index: Int) -> Int {
        Self.entries(watch).prefix(index).map(\.focusCount).reduce(0, +)
    }

    @ViewBuilder
    private func row(_ entry: Entry, index: Int) -> some View {
        let key = PlayerFocus.menuItem(focusIndex(index))
        switch entry {
        case .start(let choice):
            PanelRow(label: choice.label, detail: choice.detail, symbol: choice.mode.systemImage, chevron: false, selected: false) {
                send(WatchCommandStart(mode: choice.mode))
            }
            .disabled(watch.busy != nil)
            .focused(focus, equals: key)
            .accessibilityIdentifier("player.watch.start.\(choice.mode.identifier)")
        case .dismissFailure(let label):
            PanelRow(label: label, detail: nil, symbol: "xmark", chevron: false, selected: false) {
                send(WatchCommandDismissFailure.shared)
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("player.watch.failure.dismiss")
        case .code(let room):
            HStack(alignment: .center, spacing: 32) {
                QRCodeImage(payload: room.inviteUrl, description: room.scanLabel, size: 220)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
                    .accessibilityIdentifier("watch.room.qr")
                VStack(alignment: .leading, spacing: 8) {
                    Text(room.codeLabel).font(.caption).foregroundStyle(.secondary)
                    CodeText(code: room.code, id: "watch.room.code")
                    Text(room.scanLabel).font(.caption).foregroundStyle(.secondary)
                    Group {
                        Label(room.role, systemImage: room.isHost ? "crown" : "person.crop.circle")
                        // The host chooses it below.
                        if room.controlChoices.isEmpty {
                            Label(room.controlMode, systemImage: "hand.tap")
                        }
                        if let locked = room.lockedText {
                            Label(locked, systemImage: "lock.fill")
                                .accessibilityIdentifier("watch.room.locked")
                        }
                    }
                    .font(.callout)
                    .labelStyle(WatchLineLabelStyle())
                    if let connection = room.connection { ProgressLine(text: connection) }
                    if let line = room.nowPlaying ?? room.idleText {
                        Text(line).font(.callout).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        case .participant(let participant, let index, let room):
            let id = "watch.room.participant.\(index)"
            if participant.canMakeHost {
                let detail = [participant.status, participant.offset].compactMap { $0 }.reduce(String?.none) { joined, part in
                    joined.map { T.shared.detail.infoJoin(first: $0, second: part) } ?? part
                }
                PanelRow(
                    label: participant.name,
                    detail: detail,
                    value: room.makeHostLabel,
                    symbol: participant.tone.systemImage ?? "person.fill",
                    chevron: false,
                    selected: false
                ) {
                    send(WatchCommandMakeHost(peerId: participant.peerId))
                }
                .focused(focus, equals: key)
                .accessibilityIdentifier("\(id).makeHost")
            } else {
                HStack(spacing: 16) {
                    // The status line carries the tone's symbol.
                    Image(systemName: participant.isHost ? "crown.fill" : "person.fill")
                        .font(.body.weight(.semibold))
                        .frame(width: 36)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 10) {
                            Text(participant.name).font(.body).lineLimit(1)
                            if participant.isHost { BadgeView(text: room.hostBadge, emphasis: .strong) }
                        }
                        if let status = participant.status {
                            WatchStatusLine(text: status, tone: participant.tone).font(.caption)
                        }
                        if let offset = participant.offset {
                            WatchStatusLine(text: offset, tone: .neutral).font(.caption)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(id)
            }
        case .kick(let participant, let index, let kick):
            PanelRow(label: kick.label, detail: nil, symbol: "person.badge.minus", chevron: false, selected: false) {
                asking = .kick(participant.peerId)
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("watch.room.participant.\(index).kick")
        case .heading(let title):
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 20)
                .padding(.top, 16)
        case .liveNote(let text):
            Text(text)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .accessibilityIdentifier("player.watch.start.none")
        case .close:
            PanelRow(label: T.shared.nav.close, detail: nil, symbol: "xmark", chevron: false, selected: false) {
                store.dispatch(command: PlayerCommandCloseMenu.shared)
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("player.watch.close")
        case .controlChoice(let choice, let selected):
            PanelRow(label: choice.label, detail: choice.detail, chevron: false, selected: selected) {
                send(WatchCommandSetControlMode(mode: choice.mode))
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("watch.room.control.\(choice.mode.identifier)")
        case .lock(let lock):
            // A switch as the panel's checked rows show one: checked while the room is locked.
            PanelRow(label: lock.label, detail: lock.detail, chevron: false, selected: lock.checked) {
                send(WatchCommandLock(locked: !lock.checked))
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("watch.room.lock")
        case .offset(let offset):
            offsetControl(offset, firstKey: focusIndex(index))
        case .joinPlayback(let label):
            PanelRow(label: label, detail: nil, symbol: "play.fill", chevron: false, selected: false) {
                send(WatchCommandJoinPlayback.shared)
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("watch.room.joinPlayback")
        case .leave(let room):
            PanelRow(label: room.leave.label, detail: nil, symbol: "rectangle.portrait.and.arrow.right", chevron: false, selected: false) {
                asking = .leave
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("watch.room.leave")
        case .end(let end):
            PanelRow(label: end.label, detail: nil, symbol: "xmark.circle", chevron: false, selected: false) {
                asking = .end
            }
            .focused(focus, equals: key)
            .accessibilityIdentifier("watch.room.end")
        }
    }

    /// This device's offset: its title and value, its steps and Reset in a row (focus keys from `firstKey` on), and
    /// what it is for. Accessibility identifiers `player.watch.offset.value`, `player.watch.offset.<i>`.
    private func offsetControl(_ offset: WatchOffsetUi, firstKey: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 16) {
                Text(offset.title)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(offset.value)
                    .monospacedDigit()
                    .accessibilityIdentifier("player.watch.offset.value")
            }
            HStack(spacing: 16) {
                ForEach(Array(offset.actions.enumerated()), id: \.offset) { index, action in
                    Button {
                        send(action.command)
                    } label: {
                        Text(action.label).monospacedDigit()
                    }
                    .playerActionStyle()
                    .focused(focus, equals: .menuItem(firstKey + index))
                    .accessibilityIdentifier("player.watch.offset.\(index)")
                }
            }
            // Down and Up from the rows around it land on the first step, whatever the steps' widths.
            .focusSection()
            .defaultFocus(focus, .menuItem(firstKey), priority: .userInitiated)
            Text(offset.detail)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.watch.offset")
    }

    private func send(_ command: any WatchCommand) {
        store.dispatch(command: PlayerCommandWatch(command: command))
    }
}
#endif
