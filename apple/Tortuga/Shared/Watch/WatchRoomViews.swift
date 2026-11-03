import SwiftUI
import TortugaKit

/// What the room's controls do: the lobby's store (`WatchStore`), or the player's commands (`PlayerCommandWatch`).
struct WatchRoomActions {
    let makeHost: (String) -> Void
    /// The host removes a participant (`ParticipantUi.kick`, asked first).
    let kick: (String) -> Void
    /// The host sets who controls playback (`WatchRoomUi.controlChoices`).
    let setControlMode: (WatchControlMode) -> Void
    /// The host locks the room to new participants, or opens it again (`WatchRoomUi.lock`).
    let setLocked: (Bool) -> Void
    /// Leaves the room: a host's goes on with the next participant as host.
    let leave: () -> Void
    /// The host ends the room for everyone (`WatchRoomUi.end`).
    let end: () -> Void
    /// A guest back into the room's playback (`WatchRoomUi.joinPlayback`).
    let joinPlayback: () -> Void
}

/// The room as a form lays it out, in the lobby and in the player's room sheet (iOS, macOS): the code large beside
/// its QR code (the invite link, for a phone's camera), Copy code and the share sheet with the invite where the
/// platform has them (not tvOS); who hosts, who controls playback (a guest's line: the host chooses it below), that the
/// room is locked, the room's link while it is down, what plays or what to do; the participants, each with the host's
/// badge, its status in its tone, its offset and, for the host, Make host and Remove, which asks first; then the
/// host's own: who controls playback (the system's menu picker; on Apple TV a row per choice, the room's checked) and
/// the lock (a switch). Leave and End follow (`WatchRoomExitSection`).
///
/// Accessibility identifiers: `watch.room.code`, `watch.room.qr`, `watch.room.copy`, `watch.room.share`,
/// `watch.room.role`, `watch.room.controlMode`, `watch.room.locked`, `watch.room.connection`, `watch.room.nowPlaying`,
/// `watch.room.joinPlayback`, `watch.room.participant.<i>` (with `.name`, `.status`, `.offset`, `.makeHost`, `.kick`),
/// Remove's question's `watch.room.kick.confirm` and `.cancel`, `watch.room.control` (Apple TV: its choices
/// `watch.room.control.<hostOnly|anyone>`), `watch.room.lock`.
struct WatchRoomSections: View {
    let room: WatchRoomUi
    let actions: WatchRoomActions
    /// tvOS: the screen's focus, which the room's buttons take part in (`WatchRoomSections.focusKeys`).
    var focus: FocusState<String?>.Binding?

    #if !os(tvOS)
    /// "Code copied" shows in place of Copy code for a moment.
    @State private var copied = false
    #endif

    var body: some View {
        Section {
            codeBlock
        } header: {
            Text(room.codeLabel)
        }
        Section {
            #if !os(tvOS)
            roomLines
            #endif
            if let joinPlayback = room.joinPlayback {
                Button(action: actions.joinPlayback) {
                    Label(joinPlayback, systemImage: "play.fill")
                }
                .modifier(WatchFocus(focus: focus, key: "joinPlayback"))
                .accessibilityIdentifier("watch.room.joinPlayback")
            }
        }
        Section {
            ForEach(Array(room.participants.enumerated()), id: \.element.peerId) { index, participant in
                WatchParticipantRow(
                    participant: participant,
                    hostBadge: room.hostBadge,
                    makeHostLabel: room.makeHostLabel,
                    index: index,
                    focus: focus,
                    onMakeHost: { actions.makeHost(participant.peerId) },
                    onKick: { actions.kick(participant.peerId) }
                )
            }
        } header: {
            Text(room.participantsLabel)
        }
        hostSections
    }

    /// The code and its QR code, the line saying to scan it, and Copy code and Share (iOS, macOS). Apple TV: the room's
    /// lines beside the QR code too, as in the player's panel, and the QR code smaller than the sign-in's, so a room of
    /// two shows whole under the screen's title (focus can't reach rows the form hasn't built below a screenful of text).
    /// The Mac: the code, the line and the buttons beside a smaller QR code, so the room sheet shows the room's lines
    /// and its participants without scrolling.
    @ViewBuilder
    private var codeBlock: some View {
        #if os(tvOS) || os(macOS)
        HStack(alignment: .center, spacing: WatchMetrics.roomQRSpacing) {
            QRCodeImage(payload: room.inviteUrl, description: room.scanLabel, size: WatchMetrics.roomQR)
                .accessibilityIdentifier("watch.room.qr")
                .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
            VStack(alignment: .leading, spacing: 10) {
                CodeText(code: room.code, id: "watch.room.code")
                scanLine
                #if os(tvOS)
                roomLines
                    .font(.callout)
                    .labelStyle(WatchLineLabelStyle())
                #else
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { shareButtons }
                    VStack(alignment: .leading, spacing: 8) { shareButtons }
                }
                .glassButtonStyle()
                .task(id: copied) { await endCopied() }
                #endif
            }
        }
        .padding(.vertical, 8)
        #else
        VStack(alignment: .leading, spacing: 12) {
            CodeAndQR(code: room.code, codeId: "watch.room.code", qrPayload: room.inviteUrl, qrDescription: room.scanLabel, qrId: "watch.room.qr")
            scanLine
            HStack(spacing: 12) { shareButtons }
                .glassButtonStyle()
                .glassGroup(spacing: 12)
        }
        .padding(.vertical, 8)
        .task(id: copied) { await endCopied() }
        #endif
    }

    /// "Scan with a phone to join".
    private var scanLine: some View {
        Text(room.scanLabel)
            .font(.footnote)
            .foregroundStyle(Palette.onSurfaceVariant)
    }

    #if !os(tvOS)
    /// Copy code ("Code copied" for a moment after, also announced: the button's label changes under VoiceOver unsaid)
    /// and the share sheet with the invite, their symbols inline at the text's size (`InlineButtonLabelStyle`).
    @ViewBuilder
    private var shareButtons: some View {
        Button {
            Pasteboard.copy(room.code)
            copied = true
            announce(room.codeCopied)
        } label: {
            Label(copied ? room.codeCopied : room.copyCodeLabel, systemImage: copied ? "checkmark" : "doc.on.doc")
                .labelStyle(InlineButtonLabelStyle())
        }
        .accessibilityIdentifier("watch.room.copy")
        ShareLink(item: room.shareText) {
            Label(room.shareLabel, systemImage: "square.and.arrow.up")
                .labelStyle(InlineButtonLabelStyle())
        }
        .accessibilityIdentifier("watch.room.share")
    }

    /// Puts Copy code back 2 s after a copy.
    private func endCopied() async {
        guard copied else { return }
        try? await Task.sleep(for: .seconds(2))
        copied = false
    }
    #endif

    /// Who hosts, who controls playback (a guest's line: the host has the picker), that the room is locked, the room's
    /// link while it is down, and what plays or what to do.
    @ViewBuilder
    private var roomLines: some View {
        Label(room.role, systemImage: room.isHost ? "crown" : "person.crop.circle")
            .accessibilityIdentifier("watch.room.role")
        if room.controlChoices.isEmpty {
            Label(room.controlMode, systemImage: "hand.tap")
                .accessibilityIdentifier("watch.room.controlMode")
        }
        if let locked = room.lockedText {
            Label(locked, systemImage: "lock.fill")
                .accessibilityIdentifier("watch.room.locked")
        }
        if let connection = room.connection {
            ProgressLine(text: connection)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("watch.room.connection")
                // The room's link dropping ("Reconnecting…") is its sync trouble.
                .announcing(connection)
        }
        if let nowPlaying = room.nowPlaying {
            Label(nowPlaying, systemImage: "play.rectangle")
                .accessibilityIdentifier("watch.room.nowPlaying")
        } else if let idle = room.idleText {
            Text(idle)
                .foregroundStyle(Palette.onSurfaceVariant)
                .accessibilityIdentifier("watch.room.nowPlaying")
        }
    }

    /// The host's: who controls playback and the lock; nothing for a guest.
    @ViewBuilder
    private var hostSections: some View {
        #if os(tvOS)
        if !room.controlChoices.isEmpty {
            Section {
                ForEach(room.controlChoices, id: \.mode) { choice in
                    controlChoiceRow(choice)
                }
            } header: {
                Text(room.controlTitle)
            }
        }
        if let lock = room.lock {
            Section { lockRow(lock) }
        }
        #else
        if !room.controlChoices.isEmpty || room.lock != nil {
            Section {
                if !room.controlChoices.isEmpty { controlPicker }
                if let lock = room.lock { lockRow(lock) }
            }
        }
        #endif
    }

    #if os(tvOS)
    /// A way to run the room as a row of its own, checked while it is the room's: Apple TV's menu picker in a form shows
    /// the value alone, without the row's title.
    private func controlChoiceRow(_ choice: ControlModeChoice) -> some View {
        let selected = choice.mode == room.selectedMode
        return Button {
            actions.setControlMode(choice.mode)
        } label: {
            HStack(spacing: 24) {
                SettingsRowText(title: choice.label, subtitle: choice.detail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if selected { Image(systemName: "checkmark").accessibilityHidden(true) }
            }
        }
        .modifier(WatchFocus(focus: focus, key: "control.\(choice.mode.identifier)"))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("watch.room.control.\(choice.mode.identifier)")
    }
    #else
    /// Who controls playback: the system's menu picker, its row saying what the room's choice means, as Settings' pickers
    /// are (`SettingsChoicePicker`; on the Mac the row lays out the title and the picker itself, `SettingsControlRow`).
    @ViewBuilder
    private var controlPicker: some View {
        let selection = Binding(get: { room.selectedMode }, set: { actions.setControlMode($0) })
        let detail = room.controlChoices.first { $0.mode == room.selectedMode }?.detail
        #if os(macOS)
        SettingsControlRow(title: room.controlTitle, subtitle: detail) {
            Picker(room.controlTitle, selection: selection) { controlOptions }
                .pickerStyle(.menu)
                .labelsHidden()
                .fixedSize()
                .accessibilityIdentifier("watch.room.control")
        }
        #else
        Picker(selection: selection) {
            controlOptions
        } label: {
            SettingsRowText(title: room.controlTitle, subtitle: detail)
        }
        .pickerStyle(.menu)
        .accessibilityIdentifier("watch.room.control")
        #endif
    }

    private var controlOptions: some View {
        ForEach(room.controlChoices, id: \.mode) { choice in
            Text(choice.label).tag(choice.mode)
        }
    }
    #endif

    /// The lock: the system's switch with what it does.
    private func lockRow(_ lock: ToggleUi) -> some View {
        SettingsToggleRow(title: lock.label, subtitle: lock.detail, checked: lock.checked, id: "watch.room.lock", onChange: actions.setLocked)
            .modifier(WatchFocus(focus: focus, key: "lock"))
    }

    /// The keys of the room's focusable buttons in order (tvOS), Leave's and End's (`WatchRoomExitSection`) last, the
    /// first of which takes focus when the room shows.
    static func focusKeys(_ room: WatchRoomUi) -> [String] {
        var keys: [String] = []
        if room.joinPlayback != nil { keys.append("joinPlayback") }
        for (index, participant) in room.participants.enumerated() {
            if participant.canMakeHost { keys.append("makeHost.\(index)") }
            if participant.kick != nil { keys.append("kick.\(index)") }
        }
        keys += room.controlChoices.map { "control.\($0.mode.identifier)" }
        if room.lock != nil { keys.append("lock") }
        keys.append("leave")
        if room.end != nil { keys.append("end") }
        return keys
    }
}

/// One participant: its initial in a circle, its name (the store's, "Anna (you)" for this device) with the host's
/// badge, its status in its tone (`ParticipantTone`) and its offset ("Offset +2.5 s"), then, where this device hosts,
/// Make host (a glass button) and Remove (a round one), which asks first (`ParticipantUi.kick`). Apple TV moves focus
/// between the two along the row.
struct WatchParticipantRow: View {
    let participant: ParticipantUi
    let hostBadge: String
    let makeHostLabel: String
    /// Its place in the list: its identifier `watch.room.participant.<index>`.
    let index: Int
    var focus: FocusState<String?>.Binding?
    let onMakeHost: () -> Void
    let onKick: () -> Void

    /// Remove's question shows.
    @State private var confirmingKick = false

    private var id: String { "watch.room.participant.\(index)" }

    var body: some View {
        HStack(spacing: 12) {
            details
            if participant.canMakeHost {
                Button(makeHostLabel, action: onMakeHost)
                    .glassButtonStyle()
                    .modifier(WatchFocus(focus: focus, key: "makeHost.\(index)"))
                    .accessibilityLabel(T.shared.watch.room.makeHostNamed(name: IsolateKt.isolate(text: participant.name)))
                    .accessibilityInputLabels([makeHostLabel, T.shared.watch.room.makeHostNamed(name: participant.name)])
                    .accessibilityIdentifier("\(id).makeHost")
            }
            if let kick = participant.kick {
                IconButton(systemImage: "person.badge.minus", label: kick.label, id: "\(id).kick") { confirmingKick = true }
                    .modifier(WatchFocus(focus: focus, key: "kick.\(index)"))
            }
        }
        // Make host's and Remove's glass, one row of it.
        .glassGroup(spacing: 12)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
        .confirmation(
            confirmingKick ? participant.kick.map { Confirmation($0, id: "watch.room.kick") } : nil,
            onConfirm: {
                confirmingKick = false
                onKick()
            },
            onCancel: { confirmingKick = false }
        )
        // Remove went (this device hosts no more): its question goes with it.
        .onChange(of: participant.kick == nil) { _, gone in
            if gone { confirmingKick = false }
        }
    }

    /// The initial, the name with the host's badge, the status and the offset.
    private var details: some View {
        HStack(spacing: 12) {
            Text(participant.initial)
                .font(.headline)
                // White on the circle's grey, in a focused (white) row on Apple TV too.
                .foregroundStyle(.white)
                .frame(width: WatchMetrics.avatar, height: WatchMetrics.avatar)
                // The highest surface: the sheet's rows are already the high one.
                .background(Circle().fill(Palette.surfaceContainerHighest))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(participant.name)
                        .lineLimit(1)
                        .accessibilityIdentifier("\(id).name")
                    if participant.isHost { BadgeView(text: hostBadge, emphasis: .strong) }
                }
                if let status = participant.status {
                    WatchStatusLine(text: status, tone: participant.tone)
                        .accessibilityIdentifier("\(id).status")
                }
                if let offset = participant.offset {
                    WatchStatusLine(text: offset, tone: .neutral)
                        .accessibilityIdentifier("\(id).offset")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Leave, and for the host End for everyone (`WatchRoomUi.end`), each asking first: the room's last section in the
/// lobby and the player's room sheet. A host's Leave hands the room to the next participant.
///
/// Accessibility identifiers: `watch.room.leave`, `watch.room.end`, each with its question's `.confirm` and `.cancel`.
struct WatchRoomExitSection: View {
    let room: WatchRoomUi
    let actions: WatchRoomActions
    /// tvOS: the screen's focus (`WatchRoomSections.focusKeys`).
    var focus: FocusState<String?>.Binding?

    @State private var confirmingLeave = false
    @State private var confirmingEnd = false

    var body: some View {
        Section {
            Button(role: .destructive) {
                confirmingLeave = true
            } label: {
                Label(room.leave.label, systemImage: "rectangle.portrait.and.arrow.right")
            }
            .modifier(WatchFocus(focus: focus, key: "leave"))
            .accessibilityIdentifier("watch.room.leave")
            .confirmation(
                confirmingLeave ? Confirmation(room.leave, id: "watch.room.leave") : nil,
                onConfirm: {
                    confirmingLeave = false
                    actions.leave()
                },
                onCancel: { confirmingLeave = false }
            )
            // End went (this device hosts no more): its question goes with it.
            .onChange(of: room.end == nil) { _, gone in
                if gone { confirmingEnd = false }
            }
            if let end = room.end {
                Button(role: .destructive) {
                    confirmingEnd = true
                } label: {
                    Label(end.label, systemImage: "xmark.circle")
                }
                .modifier(WatchFocus(focus: focus, key: "end"))
                .accessibilityIdentifier("watch.room.end")
                .confirmation(
                    confirmingEnd ? Confirmation(end, id: "watch.room.end") : nil,
                    onConfirm: {
                        confirmingEnd = false
                        actions.end()
                    },
                    onCancel: { confirmingEnd = false }
                )
            }
        }
    }
}

extension Confirmation {
    /// The room's question `action` (Leave, End, Remove, an invite's), its answers' identifiers `<id>.confirm` and
    /// `<id>.cancel`; the confirm button destructive where `action.destructive`.
    init(_ action: ConfirmAction, id: String) {
        self.init(
            title: action.title,
            message: "",
            confirmLabel: action.confirm,
            cancelLabel: action.cancel,
            destructive: action.destructive,
            confirmId: "\(id).confirm",
            cancelId: "\(id).cancel"
        )
    }
}

/// A participant's status ("Finding a file", "12 s longer") or offset ("Offset +2.5 s", `neutral`) with its tone's
/// symbol, in its tone's colour.
struct WatchStatusLine: View {
    let text: String
    let tone: ParticipantTone

    var body: some View {
        HStack(spacing: 4) {
            if let symbol = tone.systemImage { Image(systemName: symbol).accessibilityHidden(true) }
            Text(text)
        }
        .font(.footnote)
        .foregroundStyle(tone.style)
        .lineLimit(2)
        .accessibilityElement(children: .combine)
    }
}

extension ParticipantTone {
    /// Ready or a matching file in green, a file of another length or none and a lost link in orange, the rest
    /// secondary: the text's own, which a focused Apple TV row turns dark with it.
    var style: AnyShapeStyle {
        switch self {
        case .good: AnyShapeStyle(Color.green)
        case .warning: AnyShapeStyle(Color.orange)
        case .busy, .neutral: AnyShapeStyle(HierarchicalShapeStyle.secondary)
        }
    }

    var systemImage: String? {
        switch self {
        case .good: "checkmark.circle.fill"
        case .warning: "exclamationmark.triangle.fill"
        case .busy: "hourglass"
        case .neutral: nil
        }
    }
}

extension WatchControlMode {
    /// The mode's symbol among the player's start choices: the host's crown, or everyone.
    var systemImage: String {
        switch self {
        case .hostOnly: "crown"
        case .anyone: "person.3"
        }
    }

    /// The mode's accessibility identifier suffix: `player.watch.start.hostOnly`, `watch.room.control.hostOnly`.
    var identifier: String { (self as __WatchControlMode).probeName }
}

/// `focused` for a view that may have no focus binding (outside tvOS).
struct WatchFocus: ViewModifier {
    let focus: FocusState<String?>.Binding?
    let key: String

    func body(content: Content) -> some View {
        if let focus { content.focused(focus, equals: key) } else { content }
    }
}

enum WatchMetrics {
    #if os(tvOS)
    static let avatar: CGFloat = 56
    /// The room's QR code, as tall as the lines beside it.
    static let roomQR: CGFloat = 280
    /// Between the room's QR code and the code and lines beside it.
    static let roomQRSpacing: CGFloat = 40
    #elseif os(macOS)
    static let avatar: CGFloat = 32
    /// The room's QR code, a little taller than the code and the buttons beside it.
    static let roomQR: CGFloat = 180
    /// Between the room's QR code and the code and buttons beside it.
    static let roomQRSpacing: CGFloat = 24
    #else
    static let avatar: CGFloat = 32
    #endif
}

#if os(tvOS)
/// A room line beside the QR code (the lobby's room, the player's panel): its symbol centred in a column of one width,
/// so the lines' texts start together.
struct WatchLineLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 16) {
            configuration.icon.frame(width: 44)
            configuration.title
        }
    }
}
#endif

#if !os(tvOS)
/// A bordered button's title with its symbol just before it, at the text's size, as the app's other bordered buttons
/// draw theirs. In a form row a label takes the row's icon style instead, `.titleAndIcon` too: a larger symbol in a
/// column of its own, set well apart from the text.
private struct InlineButtonLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon
            configuration.title
        }
    }
}
#endif
