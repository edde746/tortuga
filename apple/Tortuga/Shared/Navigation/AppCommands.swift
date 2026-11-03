#if os(iOS) || os(macOS)
import SwiftUI
import TortugaKit

/// The app's commands: the Mac's menu bar, and on iPad the shortcuts listed while ⌘ is held (and iPadOS 26's menu
/// bar).
///
/// - Settings… (⌘,), the Mac's App menu: picks the Settings destination. iPad keeps the system's Settings item, which
///   opens the app's page in the iPadOS Settings app.
/// - View: the five destinations on ⌘1–⌘5, picked as a tab or the sidebar picks them (`TortugaNavigator.pick`: the
///   shown one goes back to its root).
/// - Playback: play/pause, rewind and fast forward, full screen (the Mac; iPad's player always is), picture in picture,
///   and the next subtitle and audio track. Each item but picture in picture presses one of the player's keys, its
///   shortcut, and the store's keymap decides what it does (`PlaybackCommands.press`): the menu and the key never
///   disagree, whichever of the two the system hands the key to. On iPad and iPhone the player's key view
///   (`HardwareKeys`), first responder while the items are enabled, gets a typed key before the menu's shortcuts (UIKit
///   hands the first responder a press before the main menu's key commands), so a held key repeats and comes up there
///   as the Mac's does; a key it passes on is one the keymap has no use for.
///
/// The destinations and Settings… are disabled while no profile runs (no `AppShell`) or the player is up: a pick shows
/// another stack, and the player only lives on the shown one. Playback's items are disabled unless the player takes
/// keys (`playbackCommands`), and those the controls show disabled are disabled here too. Their keys are the items'
/// shortcuts only while the items are enabled (`playerKey`): a menu's shortcut comes before the focused view, and
/// outside the player Space, the arrows and the letters belong to the text fields and lists.
struct AppCommands: Commands {
    @FocusedValue(\.destinationPicker) private var picker
    @FocusedValue(\.playbackCommands) private var player

    var body: some Commands {
        #if os(macOS)
        CommandGroup(replacing: .appSettings) {
            Button(T.shared.nav.destination.settings + "…") { picker?.navigator.pick(destination: .settings) }
                .keyboardShortcut(",")
                .disabled(picker == nil)
        }
        #endif
        CommandGroup(after: .sidebar) {
            Divider()
            ForEach(Array(TopLevelDestination.allCases.enumerated()), id: \.offset) { index, destination in
                Button(destination.label) { picker?.navigator.pick(destination: destination) }
                    .keyboardShortcut(KeyEquivalent(Character(String(index + 1))))
                    .disabled(picker == nil)
            }
        }
        CommandMenu(T.shared.nav.menu.playback) {
            let controls = player?.controls == true
            let seeks = player?.seeks == true
            Button(player?.playPauseLabel ?? T.shared.player.play) { player?.press(.space) }
                .keyboardShortcut(playerKey(.space, controls))
                .disabled(!controls)
            // The arrows as they are on the keyboard, in a right-to-left language too: the player's timeline and
            // transport run left to right.
            Button(T.shared.nav.player.rewind) { player?.press(.left) }
                .keyboardShortcut(playerKey(.leftArrow, seeks))
                .disabled(!seeks)
            Button(T.shared.nav.player.fastForward) { player?.press(.right) }
                .keyboardShortcut(playerKey(.rightArrow, seeks))
                .disabled(!seeks)
            Divider()
            #if os(macOS)
            Button(player?.fullscreen == true ? T.shared.nav.player.exitFullscreen : T.shared.nav.player.fullscreen) {
                player?.press(.f)
            }
            .keyboardShortcut(playerKey("f", player != nil))
            .disabled(player == nil)
            #endif
            Toggle(T.shared.nav.player.pictureInPicture, isOn: Binding(
                get: { player?.pictureInPictureActive == true },
                set: { _ in player?.pictureInPicture.toggle() }
            ))
            .disabled(player?.pictureInPictureAvailable != true)
            Divider()
            Button(T.shared.nav.menu.nextSubtitles) { player?.press(.s) }
                .keyboardShortcut(playerKey("s", player != nil))
                .disabled(player == nil)
            Button(T.shared.nav.menu.nextAudio) { player?.press(.a) }
                .keyboardShortcut(playerKey("a", player != nil))
                .disabled(player == nil)
        }
    }

    /// The player's `key`, without modifiers, as an item's shortcut while it is `enabled`.
    private func playerKey(_ key: KeyEquivalent, _ enabled: Bool) -> KeyboardShortcut? {
        enabled ? KeyboardShortcut(key, modifiers: [], localization: .withoutMirroring) : nil
    }
}

/// The running profile's navigator for the destination commands, while a destination can be picked (`AppShell`), and
/// the interface language its titles are in: a new language makes a new value, which builds the menus again.
struct DestinationPicker: Equatable {
    let navigator: TortugaNavigator
    let language: String

    static func == (lhs: DestinationPicker, rhs: DestinationPicker) -> Bool {
        lhs.navigator === rhs.navigator && lhs.language == rhs.language
    }
}

/// The player as the Playback menu shows it, published while its keys reach the store: on the Mac by the video while
/// it holds keyboard focus (`PlayerInputLayer`; a control holding it takes Space and the arrows itself), on iPad and
/// iPhone by the scene's root while the player is up and no menu, popover or sheet of its is (`playbackCommands(_:)`).
struct PlaybackCommands: Equatable {
    let store: PlayerStore
    let pictureInPicture: PictureInPicture
    let playPauseLabel: String
    /// Play/pause is enabled, as the controls have it (`canPlayPause`).
    let controls: Bool
    /// Rewind and fast forward are: they are shown (`showsSeekButtons`: not live) and enabled.
    let seeks: Bool
    let fullscreen: Bool
    let pictureInPictureActive: Bool
    let pictureInPictureAvailable: Bool

    @MainActor
    init(state: PlayerUiState, store: PlayerStore, pictureInPicture: PictureInPicture) {
        self.store = store
        self.pictureInPicture = pictureInPicture
        playPauseLabel = state.playPauseLabel
        controls = state.showsPlaybackControls && state.canPlayPause
        seeks = state.showsPlaybackControls && state.canControl && state.showsSeekButtons
        fullscreen = state.fullscreen
        pictureInPictureActive = pictureInPicture.active
        pictureInPictureAvailable = state.showsPlaybackControls && (pictureInPicture.possible || pictureInPicture.active)
    }

    /// Presses `key` as the keyboard would: the menu's own shortcut repeating while held (the Mac) as the key's repeat.
    /// A key's release reaches the player as it comes up, so a held seek key's repeats move its target until then
    /// (`PlayerStore`); a click (on iPad in the menu bar), or a key the key view passed on, whose release the player
    /// never sees, releases it here.
    @MainActor
    func press(_ key: InputKey) {
        #if os(macOS)
        let event = NSApp.currentEvent
        let typed = event?.type == .keyDown
        let repeating = typed && event?.isARepeat == true
        #else
        let typed = false
        let repeating = false
        #endif
        _ = PlayerKeys.down(key, modifiers: KeyModifiers.companion.NONE, repeat: repeating, to: store)
        if !typed { _ = PlayerKeys.up(key, to: store) }
    }

    static func == (lhs: PlaybackCommands, rhs: PlaybackCommands) -> Bool {
        lhs.store === rhs.store && lhs.pictureInPicture === rhs.pictureInPicture && lhs.playPauseLabel == rhs.playPauseLabel
            && lhs.controls == rhs.controls && lhs.seeks == rhs.seeks && lhs.fullscreen == rhs.fullscreen
            && lhs.pictureInPictureActive == rhs.pictureInPictureActive
            && lhs.pictureInPictureAvailable == rhs.pictureInPictureAvailable
    }
}

private struct DestinationPickerKey: FocusedValueKey {
    typealias Value = DestinationPicker
}

private struct PlaybackCommandsKey: FocusedValueKey {
    typealias Value = PlaybackCommands
}

extension FocusedValues {
    /// The destination commands' navigator (`AppCommands`).
    var destinationPicker: DestinationPicker? {
        get { self[DestinationPickerKey.self] }
        set { self[DestinationPickerKey.self] = newValue }
    }

    /// The Playback menu's player (`AppCommands`).
    var playbackCommands: PlaybackCommands? {
        get { self[PlaybackCommandsKey.self] }
        set { self[PlaybackCommandsKey.self] = newValue }
    }
}

#if os(iOS)
extension View {
    /// Publishes `playback` to the Playback menu while its keys reach the store (`PlayerUiState.takesKeys`), nil for
    /// none. The player is a full-screen cover, whose own focused values never reach the scene's commands: the scene's
    /// root, under the cover, publishes it.
    func playbackCommands(_ playback: PlayerModel?) -> some View {
        modifier(PlaybackCommandsSource(playback: playback))
    }
}

/// Reads the player's state in a body of its own, so a state change redraws this alone, not the scene's root.
private struct PlaybackCommandsSource: ViewModifier {
    let playback: PlayerModel?

    func body(content: Content) -> some View {
        content.focusedSceneValue(\.playbackCommands, commands)
    }

    private var commands: PlaybackCommands? {
        guard let playback else { return nil }
        let state = playback.state.state
        guard state.takesKeys else { return nil }
        return PlaybackCommands(state: state, store: playback.store, pictureInPicture: playback.surface.pictureInPicture)
    }
}
#endif
#endif
