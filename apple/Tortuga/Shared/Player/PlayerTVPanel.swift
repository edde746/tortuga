import SwiftUI
import TortugaKit

#if os(tvOS)
/// The Apple TV's panels, where the system player (AVKit) puts their equivalents. A menu (Subtitles, Audio, Playback
/// speed, Options) opens as AVKit's transport-bar menus do: a glass card above its button, its trailing edge on the
/// button's (`PlayerOverlay` places it), the menu's name at its top, then its rows (a check on the current one), and in
/// the track menus More subtitles and the delay after a divider. A track or speed takes effect and closes the menu, as
/// a pick from a menu does; a held Select on a subtitle row shows that track as the second subtitle, or removes it, and
/// the menu stays open, the second's row carrying the "2" badge (`PlayerMenuContent.Entry.hold`). More subtitles opens
/// its languages in place, a language its subtitles (a pick adds the subtitle, selects it and closes the menu; Menu goes
/// back a page); a delay opens in place. The Options menu lists the playback info and the player's options: an option
/// with a page opens its choices in place (a pick closes the menu, Menu goes back to the options), a switch flips where
/// it is; watch together's row opens the room panel in place (`PlayerTVWatchPanel`; Menu closes the menu from there).
/// The playback info opens as AVKit's info panel: its Info tab over a glass card across the bottom, the playback info's
/// sections in three columns in it, the transport bar out of the way (`TVControls` hides); Up from the tab or Menu
/// closes it. Menu closes a menu (the store's Back). Focus
/// stays inside while either is open (`TVControls` steps aside), and goes back to what opened it as it closes, as AVKit's
/// does: the menu's button, the options button for the playback info opened from the options, the timeline for the
/// playback info opened from it (`PlayerFocus.panelOpener`).
struct PlayerTVPanel: View {
    let tab: PlayerPanelTab
    let state: PlayerUiState
    let store: PlayerStore
    let focus: FocusState<PlayerFocus?>.Binding

    /// AVKit's menus are 450 pt across; the options' rows hold a symbol, a label and a value, so a little wider.
    static let menuWidth: CGFloat = 520

    var body: some View {
        if tab == .info {
            let shape = RoundedRectangle(cornerRadius: 40, style: .continuous)
            VStack(alignment: .leading, spacing: 28) {
                Button(T.shared.player.info.tab) {}
                    .buttonStyle(PanelTabStyle(selected: true))
                    .focused(focus, equals: .infoPanelTab)
                    .onMoveCommand { if $0 == .up { store.dispatch(command: PlayerCommandToggleInfo.shared) } }
                    .accessibilityAddTraits(.isSelected)
                    .accessibilityIdentifier("player.panel.info")
                PlayerInfoColumns(sections: state.info, columns: 3)
                    .padding(.horizontal, PlayerMetrics.infoPanelPadding)
                    .padding(.vertical, 32)
                    .frame(width: PlayerMetrics.infoPanelWidth)
                    // Its small text reads over a darker glass, whatever the video under it shows.
                    .background(Color.black.opacity(0.45), in: shape)
                    .playerGlass(in: shape)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("player.info.panel")
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text(state.menu.map { state.menuTitle(menu: $0) } ?? "")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Palette.onVideoSecondary)
                    .lineLimit(1)
                    .padding(.horizontal, 32)
                content
            }
            .padding(.vertical, 24)
            .frame(width: Self.menuWidth)
            .playerGlass(in: RoundedRectangle(cornerRadius: 40, style: .continuous))
            .focusSection()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("player.menu")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state.menu {
        case .subtitleDelay:
            DelayControl(delay: state.subtitleDelay, store: store, focus: focus)
        case .audioDelay:
            DelayControl(delay: state.audioDelay, store: store, focus: focus)
        case .options:
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    PanelRow(label: T.shared.player.info.title, detail: nil, symbol: "info.circle", selected: false) {
                        // The info shows before the options go, so the panel never closes on the way; focus stays on
                        // the panel, on its Info tab.
                        if !state.infoVisible { store.dispatch(command: PlayerCommandToggleInfo.shared) }
                        store.dispatch(command: PlayerCommandCloseMenu.shared)
                        focus.wrappedValue = .infoPanelTab
                    }
                    .focused(focus, equals: .optionInfo)
                    .accessibilityIdentifier("player.more.info")
                    Divider().padding(.vertical, 8)
                    ForEach(Array(state.options.enumerated()), id: \.offset) { _, option in
                        PanelRow(
                            label: option.label,
                            detail: nil,
                            value: option.value,
                            symbol: option.id.systemImage,
                            chevron: option.page != nil,
                            selected: false
                        ) {
                            if let page = option.page {
                                store.dispatch(command: PlayerCommandOpenMenu(menu: page))
                            } else if let toggle = option.toggle {
                                store.dispatch(command: toggle)
                            }
                        }
                        .focused(focus, equals: .option(option.id))
                        .accessibilityIdentifier(option.id.identifier)
                    }
                }
                // Rows in from the card's edges, as AVKit's menus have them.
                .padding(.horizontal, 12)
            }
            .frame(maxHeight: 600)
        case .watchTogether:
            if let watch = state.watch {
                PlayerTVWatchPanel(watch: watch, store: store, focus: focus)
            }
        case .some(let menu):
            let content = PlayerMenuContent(menu, state: state, store: store)
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(content.entries.enumerated()), id: \.offset) { index, entry in
                        PanelRow(
                            label: entry.label,
                            detail: entry.detail,
                            value: entry.value,
                            selected: entry.selected,
                            second: entry.second,
                            hold: entry.hold,
                            action: entry.perform
                        )
                        .focused(focus, equals: .menuItem(index))
                        .accessibilityIdentifier("player.menu.item.\(index)")
                    }
                    if content.more != nil || content.delay != nil {
                        Divider().padding(.vertical, 8)
                    }
                    if let more = content.more {
                        PanelRow(label: T.shared.player.menu.moreSubtitles, detail: nil, value: more, symbol: "plus.bubble", selected: false) {
                            store.dispatch(command: PlayerCommandOpenMenu(menu: .moreSubtitles))
                        }
                        .focused(focus, equals: .moreSubtitles)
                        .accessibilityIdentifier("player.menu.more")
                    }
                    if let delay = content.delay {
                        PanelRow(label: delay.title, detail: nil, value: delay.label, symbol: "timer", selected: false) {
                            store.dispatch(command: PlayerCommandOpenMenu(menu: delay.menu))
                        }
                        .accessibilityIdentifier("player.menu.delay")
                    }
                }
                // Rows in from the card's edges, as AVKit's menus have them.
                .padding(.horizontal, 12)
            }
            .frame(maxHeight: 600)
            // Each page's rows are its own: a page opens scrolled to its top, and focus set on its row lands on a new
            // row (a row kept from the page before, relabelled, looked focused while no element reported focus).
            .id(menu)
        case nil:
            // The playback info: the info panel (`body`).
            EmptyView()
        }
    }
}

/// The info panel's tab, as AVKit's: a pill of the player's glass tint with the tab's name, white under focus,
/// shrinking a little while clicked (`tvPressed`).
struct PanelTabStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        Face(configuration: configuration, selected: selected)
    }

    private struct Face: View {
        let configuration: Configuration
        let selected: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.callout.weight(.medium))
                .foregroundStyle(focused ? Color.black : Palette.onVideo)
                .padding(.horizontal, 34)
                .padding(.vertical, 17)
                .background(Capsule().fill(focused ? Palette.onVideo : Palette.onVideo.opacity(selected ? 0.3 : 0.16)))
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
        }
    }
}

/// A row of the panel: a check on the current choice (the "2" badge on the second subtitle's), the label and its detail
/// line; or a row's own `symbol` with a value, and a chevron for a row that opens more. Under focus it turns white with
/// black text, as the system player's rows do; a click shrinks it a little (`tvPressed`). A row with a `hold` runs it on
/// a held Select (`PanelRowStyle`), the click that ends the press doing nothing, and offers it to VoiceOver by its name.
struct PanelRow: View {
    let label: String
    let detail: String?
    var value: String?
    /// The row's own symbol, shown always (the delay's timer, an option's); without one, a check on the selected row.
    var symbol: String?
    var chevron = true
    let selected: Bool
    /// The subtitle track showing as the second subtitle: its badge where the check goes, named for VoiceOver.
    var second = false
    var hold: PlayerMenuContent.Hold?
    let action: () -> Void
    @State private var press = HeldPress()

    var body: some View {
        Button {
            if press.held { press.held = false } else { action() }
        } label: {
            HStack(spacing: 16) {
                Image(systemName: second ? PlayerMenuContent.secondBadgeSymbol : symbol ?? "checkmark")
                    .font(.body.weight(.semibold))
                    .opacity(selected || second || symbol != nil ? 1 : 0)
                    .frame(width: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.body).lineLimit(1)
                    if let detail {
                        Text(detail).font(.caption).opacity(0.7).lineLimit(1)
                    }
                }
                Spacer(minLength: 16)
                if let value {
                    Text(value).font(.body.monospacedDigit()).opacity(0.7)
                }
                if value != nil, chevron {
                    Image(systemName: "chevron.forward").font(.body.weight(.semibold)).opacity(0.7)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(PanelRowStyle(hold: hold.map { (press: press, action: $0.perform) }))
        .accessibilityLabel(label)
        .accessibilityValue(accessibilityValue)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityActions {
            if let hold, let name = hold.name { Button(name, action: hold.perform) }
        }
    }

    /// The value, the badge's name and the detail. Both value and detail: the Make host row's value is the action, its
    /// detail the participant's status. A detail after either is isolated: it can start with a track's or a device's
    /// name.
    private var accessibilityValue: String {
        let lead = [value, second ? T.shared.player.track.second : nil].compactMap { $0 }
        let tail = detail.map { lead.isEmpty ? $0 : IsolateKt.isolate(text: $0) }
        return (lead + [tail].compactMap { $0 }).joined(separator: T.shared.detail.listSeparator)
    }
}

/// One press of a row's Select (`PanelRow`): whether it ran the row's hold, so the click that ends it does nothing.
@MainActor
final class HeldPress {
    var held = false
}

/// A panel row's look, and its `hold`: Select down for half a second, as long as the system's long press takes, runs
/// the action and marks the press held.
struct PanelRowStyle: ButtonStyle {
    var hold: (press: HeldPress, action: () -> Void)?

    func makeBody(configuration: Configuration) -> some View {
        Face(configuration: configuration, hold: hold)
    }

    private struct Face: View {
        let configuration: Configuration
        let hold: (press: HeldPress, action: () -> Void)?
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Color.black : Palette.onVideo)
                .background(Capsule().fill(focused ? Palette.onVideo : .clear))
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
                .task(id: configuration.isPressed) {
                    guard configuration.isPressed, let hold else { return }
                    hold.press.held = false
                    // Let go before: a click.
                    do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
                    hold.press.held = true
                    hold.action()
                }
        }
    }
}

/// A delay: − value + (disabled at the ends of the range; VoiceOver names them by the way they move the delay,
/// "Subtitle delay: earlier") and Reset, disabled at zero, under the card's title, the delay's name.
private struct DelayControl: View {
    let delay: DelayState
    let store: PlayerStore
    let focus: FocusState<PlayerFocus?>.Binding

    var body: some View {
        VStack(spacing: 20) {
            HStack(spacing: 32) {
                PlayerIconButton(systemImage: "minus", label: delay.decreaseLabel, value: delay.label, id: "player.delay.decrease") {
                    store.dispatch(command: delay.decrease)
                }
                .disabled(!delay.canDecrease)
                .focused(focus, equals: .delayDecrease)
                Text(delay.label)
                    .font(.title2.monospacedDigit())
                    .frame(minWidth: 200)
                PlayerIconButton(systemImage: "plus", label: delay.increaseLabel, value: delay.label, id: "player.delay.increase") {
                    store.dispatch(command: delay.increase)
                }
                .disabled(!delay.canIncrease)
            }
            Button(T.shared.player.menu.reset) { store.dispatch(command: delay.reset) }
                .playerActionStyle()
                .disabled(!delay.canReset)
                .accessibilityIdentifier("player.delay.reset")
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
    }
}
#endif
