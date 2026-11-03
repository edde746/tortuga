import SwiftUI
import TortugaKit

#if os(macOS) || os(tvOS)
import Combine
#endif

#if os(macOS)
import Carbon.HIToolbox
#endif

/// The player (the shared `PlayerStore` decides, this only renders its state and forwards input): mpv's
/// surface under a layer of controls (`PlayerOverlay`). Input goes to the store's keymap first, as the platform's raw
/// events:
///
/// - iOS: taps on the video as clicks (the store tells a tap from a double tap and where it landed: double taps on the
///   sides seek); a pinch, which zooms the picture; one finger dragged clearly up or down as swipes, from where it went
///   down and with the edges iOS swipes from (the store changes the brightness or the volume through `TouchLevels`,
///   whose volume control sits hidden among these views); a finger held past the long-press time as a hold, until it
///   lifts (never also a tap); a hardware keyboard's keys, and its Tab's focus moves as `FocusMove`; an iPad's trackpad
///   or mouse pointer moves (over the controls or not), as the Mac's.
///   While the screen is locked (`ScreenLockShield`) a shield over everything takes every touch instead: its taps go to
///   the store as clicks (the unlock button's own as `UnlockPress`), the rest to nothing.
/// - macOS: clicks with their click count, pointer moves (over the controls or not), keys with key-up and repeats:
///   Space, the arrows, F for full screen, Esc and the rest of the shared desktop keymap; Tab between the controls as
///   `FocusMove`; the scroll wheel over the video (`PlayerScrollWheel`), which the keymap turns into the volume.
/// - tvOS: the Siri Remote's click-pad presses, Select, Play/Pause and Back as key presses while the controls are down
///   (left/right seek, Select pauses); with the controls up the focus engine moves between them (the TV keymap leaves
///   the D-pad alone then, but for left and right on the focused timeline, which scrub) and focus follows the store as
///   the Compose TV player's does. The prompt button (skip, up next) takes the remote's presses while it has focus:
///   Select is its own (the video layer is disabled meanwhile, so the store's Select never presses it a second time),
///   its moves go to the store. The focus engine's moves at the user's hand reach the store as `FocusMove`. A horizontal
///   swipe on the touch surface scrubs the timeline (not while live, which has none and seeks nothing); a swipe down
///   opens the playback info, as in the system player.
///
/// Whatever the store leaves alone falls through: Back with nothing left to back out of (a scrub, a menu, the prompt
/// button, the info panel, the controls) closes the player. On iOS VoiceOver's escape does the same.
///
/// While VoiceOver or Switch Control runs the store keeps the controls up (`PlayerStore.onAssistiveTechnologyChanged`):
/// they move through them one at a time, at their own pace.
///
/// While the controls show, subtitles move up clear of them: the view reports how much of the video surface the bottom
/// controls cover (`PlayerStore.onControlsHeightChanged`), none while picture in picture shows the video instead; the
/// engine lifts the subtitles by the part of that over the frame mpv places them in (on iOS the picture).
struct PlayerScreen: View {
    let playback: PlayerModel

    @FocusState private var focus: PlayerFocus?
    /// The video surface's frame and the top of the bottom controls, in global coordinates.
    @State private var surfaceFrame = CGRect.zero
    @State private var controlsTop: CGFloat?
    #if os(tvOS)
    /// The layers focus last followed.
    @State private var focusContext: PlayerFocusContext?
    /// What the open panel gives focus back to as it closes (`PlayerFocus.panelOpener`), taken as it opens: the
    /// playback info opened from the options keeps the options button.
    @State private var panelOpener: PlayerFocus?
    /// The transport bar's menu buttons that show and can take focus, by the tab each opens (`PlayerMenuButtonFrames`).
    @State private var menuButtons: Set<PlayerPanelTab> = []
    #endif
    #if os(macOS)
    /// The window's buttons for the title row in full screen (`PlayerWindowChrome`).
    @State private var windowButtons: PlayerWindowButtons?
    #endif
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl

    var body: some View {
        let state = playback.state.state
        let store = playback.store
        ZStack {
            #if os(iOS)
            SystemVolumeControl()
                .frame(width: 1, height: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            #endif
            VideoSurfaceHost(surface: playback.surface)
                .ignoresSafeArea()
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { surfaceFrame = $0 }
            // Its taps seek on the side of the screen they land on, in every language, as the timeline runs.
            #if os(macOS)
            PlayerInputLayer(
                state: state,
                timeline: playback.videoTimeline,
                store: store,
                focus: $focus,
                commands: PlaybackCommands(state: state, store: store, pictureInPicture: playback.surface.pictureInPicture)
            )
            .environment(\.layoutDirection, .leftToRight)
            #else
            PlayerInputLayer(state: state, timeline: playback.videoTimeline, store: store, focus: $focus)
                .environment(\.layoutDirection, .leftToRight)
            #endif
            PlayerOverlay(playback: playback, state: state, focus: $focus) { controlsTop = $0 }
                #if os(macOS)
                .environment(\.playerWindowButtons, windowButtons)
                #endif
                #if os(iOS)
                // In landscape nothing of the system's sits along the top edge (an iPhone's sensor housing is at a side,
                // the status bar is hidden), yet iOS 27.1 gives the iPhone Duo's cover, its vertical bar off for the
                // player (`verticalBarDisabled`), an 82 pt top safe area there, which put the top bar on the picture.
                .ignoresSafeArea(edges: surfaceFrame.width > surfaceFrame.height ? .top : [])
                #endif
            #if os(iOS)
            // Over everything else: no touch reaches what lies under a locked screen.
            if let lock = state.screenLock { ScreenLockShield(lock: lock, store: store) }
            #endif
            #if DEBUG
            PlayerProbes(playback: playback)
            #endif
        }
        #if os(iOS)
        // VoiceOver's escape (a two-finger scrub) backs out as Back does: a menu, the prompt, then the player.
        .accessibilityElement(children: .contain)
        .accessibilityAction(.escape) { store.dispatch(command: PlayerCommandBack.shared) }
        #endif
        .background(.black)
        .environment(\.colorScheme, .dark)
        .environment(\.hdrPicture, state.hdrPicture)
        // Everything over the video draws in white, `.secondary` in its 60 % (`Palette.onVideo`). tvOS keeps the
        // system's tint (`tortugaTheme`); its buttons turn their symbols dark themselves under focus.
        .foregroundStyle(Palette.onVideo, Palette.onVideoSecondary)
        #if !os(tvOS)
        .tint(Palette.onVideo)
        #endif
        .task { await playback.state.observe() }
        // The video's position value (`player.video`) follows the timeline while an assistive technology runs, which
        // reads it there (the store keeps the controls up meanwhile, so the timeline shows too).
        .task(id: assistiveTechnology) {
            if assistiveTechnology { await playback.videoTimeline.observe() }
        }
        .onChange(of: subtitleLift, initial: true) { _, lift in store.onControlsHeightChanged(fraction: lift) }
        // VoiceOver and Switch Control move through the controls one at a time: the store keeps them up meanwhile.
        .onChange(of: assistiveTechnology, initial: true) { _, running in store.onAssistiveTechnologyChanged(running: running) }
        #if os(tvOS)
        // Wherever focus is (the video, a control), the remote's Play/Pause and Back go to the store.
        .onPlayPauseCommand { RemotePress.send(.mediaPlayPause, to: store) }
        .onExitCommand {
            if !RemotePress.send(.back, to: store) { store.dispatch(command: PlayerCommandBack.shared) }
        }
        .background {
            RemoteSwipe(store: store, scrubs: swipeScrubs(state), opensInfo: swipeOpensInfo(state))
        }
        .onPreferenceChange(PlayerMenuButtonFrames.self) { menuButtons = Set($0.keys) }
        // After the update that changed the layers, once the views that take focus exist.
        .task(id: PlayerFocusContext(state)) {
            let new = PlayerFocusContext(state)
            // The first state counts as a change from nothing up: a prompt button or a card already there as the player
            // opens (a play resumed inside an intro) takes focus as it would coming up later.
            let old = focusContext ?? .opening
            focusContext = new
            if new.panel != nil, old.panel == nil { panelOpener = PlayerFocus.panelOpener(state) }
            let target = PlayerFocus.target(from: old, to: new, focused: focus, opener: panelOpener, menuButtons: menuButtons, state: state)
            guard let target else { return }
            // When the focused view goes with the change (the menu entry that opened a delay control), tvOS picks the
            // next focus itself a moment later, over this one: the target is set again until it holds, or the user
            // moves focus.
            await FocusRestore.restore($focus, to: target, replacing: true)
        }
        .onChange(of: focus) { _, new in
            store.onTimelineFocusChanged(focused: new == .scrub)
        }
        #endif
        #if os(tvOS) || os(iOS)
        // Moving focus with the remote, or with an iPad keyboard's Tab, is using the player: the store sees no key for
        // it, so it hears it as a focus move, which keeps the controls up and stops the prompt's countdown. Only the
        // user's moves, the focus system's updates with a heading: not focus set by the player or by tvOS as a focused
        // view goes.
        .onReceive(NotificationCenter.default.publisher(for: UIFocusSystem.didUpdateNotification)) { notification in
            guard let context = notification.userInfo?[UIFocusSystem.focusUpdateContextUserInfoKey] as? UIFocusUpdateContext,
                  !context.focusHeading.isEmpty else { return }
            _ = store.onInput(event: InputEventFocusMove.shared)
        }
        #endif
        #if os(iOS)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .statePresentation(infoShown) { base, shown in
            base.sheet(isPresented: shown) {
                PlayerInfoSheet(sections: playback.state.state.info) {
                    if playback.state.state.infoVisible { store.dispatch(command: PlayerCommandToggleInfo.shared) }
                }
            }
        }
        // The screen's brightness, system-wide, is the screen's own again while picture in picture shows the video.
        .onChange(of: playback.surface.pictureInPicture.showsVideo) { _, shows in
            if shows { TouchLevels.shared.restoreBrightness() }
        }
        #endif
        #if os(iOS) || os(macOS)
        // The watch-together room panel (`PlayerMenu.watchTogether`), from the session indicator or the options menu. It
        // keeps the player's form factor: a sheet's own width class is compact on iPad too, where it would take an
        // iPhone's grabber in place of Close.
        .statePresentation(store.menuPresentation(.watchTogether, shown: state.menu == .watchTogether && state.watch != nil)) { base, shown in
            base.sheet(isPresented: shown) {
                if let watch = playback.state.state.watch {
                    PlayerWatchSheet(watch: watch, store: store) {
                        if store.state.value.menu == .watchTogether { store.dispatch(command: PlayerCommandCloseMenu.shared) }
                    }
                }
            }
        }
        #endif
        #if os(macOS)
        // Tab and Shift-Tab (its back tab) moving keyboard focus between the controls is using the player, as on iPad:
        // a focused control keeps the keys from the video's layer, so the store hears the move here and keeps the
        // controls up.
        .onKeyPress(keys: [.tab, KeyEquivalent("\u{19}")]) { _ in
            _ = store.onInput(event: InputEventFocusMove.shared)
            return .ignored
        }
        // Over the video the pointer goes with the controls; moving it brings both back (a pointer move).
        .onChange(of: state.controlsVisible) { _, visible in NSCursor.setHiddenUntilMouseMoves(!visible) }
        // The window's toolbar and title step aside for the player's own; its buttons come and go with the controls,
        // in full screen as copies on the title row. The pointer leaving the window hides the controls at once; under
        // another app's window over this one (the menu bar in full screen), or where a menu of the controls left it
        // (below the window after a pick), it is off them.
        .background {
            PlayerWindowChrome(controlsVisible: state.controlsVisible) {
                store.onFullscreenChanged(fullscreen: $0)
            } onWindowButtons: {
                windowButtons = $0
            } onPointerExit: {
                _ = store.onInput(event: InputEventPointerExit.shared)
            } onPointerOffControls: {
                _ = store.onInput(event: InputEventPointerMove(overControls: false))
            }
        }
        // A menu of the controls is open while AppKit tracks it: the store keeps the controls up meanwhile.
        .onReceive(NotificationCenter.default.publisher(for: NSMenu.didBeginTrackingNotification)) { notification in
            guard Self.isPopUpMenu(notification), store.state.value.menu == nil, let menu = PlayerMenuTracking.hovered else { return }
            store.dispatch(command: PlayerCommandOpenMenu(menu: menu))
        }
        .onReceive(NotificationCenter.default.publisher(for: NSMenu.didEndTrackingNotification)) { notification in
            guard Self.isPopUpMenu(notification), store.state.value.menu?.isNativeMenu == true else { return }
            store.dispatch(command: PlayerCommandCloseMenu.shared)
        }
        #endif
    }

    /// The share of the video surface the bottom controls cover, and the gap that keeps a lifted line clear of the
    /// title over them, for the subtitle lift; none while picture in picture shows the video.
    private var subtitleLift: Double {
        guard surfaceFrame.height > 0, let top = controlsTop, surfaceFrame.maxY > top else { return 0 }
        #if os(iOS) || os(macOS)
        if playback.surface.pictureInPicture.active { return 0 }
        #endif
        return Double((surfaceFrame.maxY - top + PlayerMetrics.subtitleGap) / surfaceFrame.height)
    }

    /// VoiceOver or Switch Control runs; in debug builds also the UI tests' stand-in (`SimulatedAssistiveTechnology`).
    private var assistiveTechnology: Bool {
        #if DEBUG
        if SimulatedAssistiveTechnology.running { return true }
        #endif
        return voiceOver || switchControl
    }

    #if os(iOS)
    /// The playback info's sheet shows while the store shows the info; dismissing it hides that.
    private var infoShown: Binding<Bool> {
        let store = playback.store
        return Binding(
            get: { playback.state.state.infoVisible },
            set: { shown in
                if !shown, store.state.value.infoVisible { store.dispatch(command: PlayerCommandToggleInfo.shared) }
            }
        )
    }
    #endif

    #if os(macOS)
    /// A pop-up menu (the controls' menus), not the menu bar's.
    private static func isPopUpMenu(_ notification: Notification) -> Bool {
        guard let menu = notification.object as? NSMenu else { return false }
        return menu !== NSApp.mainMenu && menu.supermenu == nil
    }
    #endif

    #if os(tvOS)
    /// A swipe scrubs while the video has the remote (the controls down) or the timeline has focus, and nothing else
    /// holds it (`PlayerUiState.remoteHeld`); never while this device can't seek (`canSeek`: live, a guest of a
    /// host-only room).
    private func swipeScrubs(_ state: PlayerUiState) -> Bool {
        guard state.canSeek, !state.remoteHeld else { return false }
        return !state.controlsVisible || focus == .scrub
    }

    /// A swipe down opens the playback info from the same places (live: play/pause in the timeline's).
    private func swipeOpensInfo(_ state: PlayerUiState) -> Bool {
        guard !state.remoteHeld else { return false }
        return !state.controlsVisible || focus == .scrub || focus == .playPause
    }
    #endif
}

/// The video's own input: a full-screen layer under the controls. It is also the video's accessibility element
/// (`player.video`): the title, with the position as its value (`PositionValue`), which the UI tests read to see playback
/// advance.
private struct PlayerInputLayer: View {
    let state: PlayerUiState
    /// The video's timeline (`PlayerModel.videoTimeline`), for its position value.
    let timeline: StoreObserver<TimelineState>
    let store: PlayerStore
    let focus: FocusState<PlayerFocus?>.Binding
    #if os(macOS)
    /// The Playback menu's player (`AppCommands`), published while the video has the keyboard.
    let commands: PlaybackCommands
    #endif

    #if os(macOS)
    @FocusState private var focused: Bool
    /// The pointer is on the video, not on the controls over it: the scroll wheel there is the player's
    /// (`PlayerScrollWheel`).
    @State private var pointerOnVideo = false
    #elseif os(iOS)
    /// A pinch on the video is under way; it goes back to false as the pinch ends or is cancelled.
    @GestureState private var pinching = false
    /// A finger has rested on the video past the long-press time (`hold`); false again as it lifts or is cancelled.
    @GestureState private var holding = false
    /// The one-finger drag under way (`swipe`).
    @GestureState private var drag: DragTrack?
    #endif

    var body: some View {
        GeometryReader { proxy in
            #if os(tvOS)
            // With the controls down this is the only focusable view, so the remote's presses land here, unless
            // something over the video holds the remote (`remoteHeld`): the prompt button's Select is its own.
            Button { RemotePress.send(.select, to: store) } label: {
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
            }
            .buttonStyle(VideoLayerButtonStyle())
            .disabled(state.controlsVisible || state.remoteHeld)
            .focused(focus, equals: .video)
            .onMoveCommand { direction in
                switch direction {
                case .up: RemotePress.send(.up, to: store)
                case .down: RemotePress.send(.down, to: store)
                case .left: RemotePress.send(.left, to: store)
                case .right: RemotePress.send(.right, to: store)
                @unknown default: break
                }
            }
            .accessibilityLabel(state.title)
            .modifier(PositionValue(timeline: timeline))
            .accessibilityIdentifier("player.video")
            #else
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(coordinateSpace: .local) { location in
                    let x = Float(location.x / max(proxy.size.width, 1))
                    _ = store.onInput(event: InputEventClick(x: x, clickCount: Int32(Self.clickCount)))
                }
                #if os(iOS)
                // A finger held down, below the tap in precedence: a short touch is a tap, a long one a hold, never both.
                .gesture(hold)
                #endif
                // A pointer moving over the video brings the controls up: the Mac's, and an iPad's trackpad or mouse.
                // Its leaving is no exit: it went onto the controls, or a touch hid it.
                .onContinuousHover { phase in
                    #if os(macOS)
                    pointerOnVideo = phase != .ended
                    #endif
                    if case .active = phase { _ = store.onInput(event: InputEventPointerMove(overControls: false)) }
                }
                #if os(macOS)
                .background {
                    PlayerScrollWheel(onVideo: pointerOnVideo) { store.onInput(event: InputEventWheel(steps: $0)) }
                }
                .focusable()
                .focusEffectDisabled()
                .focused($focused)
                // The Playback menu's player while the video has the keyboard, as the keys do.
                .focusedValue(\.playbackCommands, commands)
                .onKeyPress(phases: .all) { press in
                    handle(press) ? .handled : .ignored
                }
                .onAppear { focused = true }
                // Keys must keep reaching the store: when the delay popover, which a click moved focus into, closes,
                // the video takes focus back.
                .onChange(of: state.menu) { _, menu in if menu == nil { focused = true } }
                #else
                // A pinch zooms the picture (the store's zoom from the pinch's start, by the pinch's scale), beside the
                // taps.
                .simultaneousGesture(
                    MagnifyGesture()
                        .updating($pinching) { _, pinching, _ in pinching = true }
                        .onChanged { _ = store.onInput(event: InputEventPinch(scale: Double($0.magnification))) }
                )
                .onChange(of: pinching) { _, pinching in
                    if !pinching { _ = store.onInput(event: InputEventPinchEnd.shared) }
                }
                // A swipe up or down the video: the brightness or the volume, as the store has it.
                .simultaneousGesture(
                    DragGesture(minimumDistance: Self.dragDistance, coordinateSpace: .local)
                        .updating($drag) { value, track, _ in track = swipe(value, track: track, size: proxy.size) }
                )
                .onChange(of: holding) { _, held in
                    // Two fingers resting before a pinch moves them are no hold.
                    if held, pinching { return }
                    _ = store.onInput(event: InputEventHold(held: held))
                }
                // A hardware keyboard's keys, without the software keyboard (`HardwareKeys`). The Playback menu's
                // player is the scene's (`playbackCommands(_:)`).
                .background {
                    HardwareKeys(store: store, active: state.takesKeys).frame(width: 0, height: 0)
                }
                #endif
                .accessibilityElement()
                .accessibilityLabel(state.title)
                .modifier(PositionValue(timeline: timeline))
                .accessibilityIdentifier("player.video")
            #endif
        }
        .ignoresSafeArea()
    }

    #if !os(tvOS)
    /// The platform's click count (a Mac's double click); touch counts in the store.
    private static var clickCount: Int {
        #if os(macOS)
        NSApp.currentEvent?.clickCount ?? 1
        #else
        1
        #endif
    }
    #endif

    #if os(iOS)
    /// How far a finger moves before it drags rather than taps or holds: UIKit's long press allows 10 pt.
    private static let dragDistance: CGFloat = 10

    /// A finger resting on the video for UIKit's long-press time (0.5 s) without moving `dragDistance`, held from then
    /// until it lifts.
    private var hold: some Gesture {
        LongPressGesture(minimumDuration: 0.5, maximumDistance: Self.dragDistance)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .updating($holding) { value, holding, _ in
                if case .second(true, _) = value { holding = true }
            }
    }

    /// A one-finger drag on the video, from its first move past `dragDistance`: a swipe for good where that move went
    /// clearly up or down (twice as far as across) and no hold or pinch had the fingers, nothing for good otherwise.
    private struct DragTrack {
        let swipe: Bool
        /// Where the finger was at the last move.
        let y: CGFloat
    }

    /// The drag's next move: as a swipe, the store's `InputEventSwipe` with the way it moved since the last one, up in
    /// the player's heights, and where it went down.
    private func swipe(_ value: DragGesture.Value, track: DragTrack?, size: CGSize) -> DragTrack {
        let move = value.translation
        let swipe = track?.swipe ?? (abs(move.height) > 2 * abs(move.width) && !holding && !pinching)
        if swipe, !pinching, size.width > 0, size.height > 0 {
            let start = value.startLocation
            _ = store.onInput(event: InputEventSwipe(
                x: Float(start.x / size.width),
                y: Float(start.y / size.height),
                distance: Double(((track?.y ?? start.y) - value.location.y) / size.height),
                edges: Self.systemEdges(size: size)
            ))
        }
        return DragTrack(swipe: swipe, y: value.location.y)
    }

    /// Where iOS takes a swipe from the screen's edge, in shares of the player (which fills the window): Notification
    /// Center and Control Center at the top, the home indicator at the bottom, nothing at the sides. The window's safe
    /// area insets, which cover the status bar and the home indicator, at least `systemEdge`: the player hides the
    /// status bar, which leaves a landscape iPhone no inset at the top, where the system still takes a swipe down from
    /// the edge. (The video's layer ignores the safe area, so its own geometry reports none.)
    private static func systemEdges(size: CGSize) -> SystemEdges {
        let insets = UIApplication.shared.connectedScenes.lazy.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.safeAreaInsets
        return SystemEdges(
            left: 0,
            top: Float(max(insets?.top ?? 0, systemEdge) / size.height),
            right: 0,
            bottom: Float(max(insets?.bottom ?? 0, systemEdge) / size.height)
        )
    }

    /// The least edge the system swipes from: about the status bar's height (20–24 pt with no sensor housing in it)
    /// and the home indicator's area (21 pt in landscape, 34 pt upright on an iPhone), with room for a finger's width.
    private static let systemEdge: CGFloat = 30
    #endif

    #if os(macOS)
    private func handle(_ press: KeyPress) -> Bool {
        // The key's place, for a layout of another script (`InputKey(typed:usCharacter:)`): the event being handled.
        let keyCode = NSApp.currentEvent.flatMap { $0.type == .keyDown || $0.type == .keyUp ? $0.keyCode : nil }
        guard let key = InputKey(press.key, characters: press.characters, keyCode: keyCode) else { return false }
        switch press.phase {
        case .down, .repeat:
            let modifiers = KeyModifiers(
                shift: press.modifiers.contains(.shift),
                ctrl: press.modifiers.contains(.control),
                alt: press.modifiers.contains(.option),
                meta: press.modifiers.contains(.command)
            )
            return PlayerKeys.down(key, modifiers: modifiers, repeat: press.phase == .repeat, to: store)
        case .up:
            return PlayerKeys.up(key, to: store)
        default:
            return false
        }
    }
    #endif
}

/// The video's accessibility value: the position (`TimelineState.positionLabel`) as `timeline` mirrors it, read here
/// alone, so the position moving redraws nothing else of the video's layer.
private struct PositionValue: ViewModifier {
    let timeline: StoreObserver<TimelineState>

    func body(content: Content) -> some View {
        content.accessibilityValue(timeline.state.positionLabel)
    }
}
