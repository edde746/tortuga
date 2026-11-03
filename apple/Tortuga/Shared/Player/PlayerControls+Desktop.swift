import SwiftUI
import TortugaKit

#if os(macOS)
/// The Mac's controls (see `PlayerOverlay`).
struct DesktopControls: View {
    let playback: PlayerModel
    let state: PlayerUiState
    /// The control bar's frame in global coordinates, as it lays out: the subtitle lift, and the corner directly above
    /// it (`PlayerOverlay`).
    let onControlBar: (CGRect) -> Void
    /// How far the title row's buttons and the title's text reach from the overlay's leading side, as they lay out: the
    /// messages at the top go on the row where they clear them (`PlayerOverlay`).
    let onTopRowLeading: (CGFloat) -> Void

    var body: some View {
        let store = playback.store
        if state.controlsVisible {
            // The window's title bar is the top safe area (the window extends the player under it): the title sits
            // on its line, beside the window's buttons. In full screen there is none, and the row keeps its height in a
            // window around the copies of the buttons (`PlayerWindowButtons`).
            GeometryReader { proxy in
                let titleBar = proxy.safeAreaInsets.top
                ZStack {
                    PlayerScrim()
                    VStack(spacing: 0) {
                        DesktopTitleBar(
                            state: state,
                            store: store,
                            pictureInPicture: playback.surface.pictureInPicture,
                            height: titleBar,
                            onReach: onTopRowLeading
                        )
                        .controlRegion(store)
                        Spacer(minLength: 0)
                        // Over the error, the title row alone (`showsPlaybackControls`).
                        if state.showsPlaybackControls {
                            ControlBar(playback: playback, state: state)
                                .controlRegion(store)
                                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { onControlBar($0) }
                                .padding(.horizontal, 32)
                                .padding(.bottom, 20)
                        }
                    }
                    .ignoresSafeArea(edges: .top)
                }
            }
            .transition(.opacity)
        }
    }
}

/// Back, picture in picture (as on iOS, beside back; not over the error) and the title, on the line of the window's
/// buttons (the window has no toolbar or title while the player is up); in full screen the row hosts copies of the
/// buttons where the title bar has them in a window, and keeps its height. Back sits in the row's middle, so the
/// messages at the top centred in the row are level with it (`PlayerOverlay`).
struct DesktopTitleBar: View {
    let state: PlayerUiState
    let store: PlayerStore
    let pictureInPicture: PictureInPicture
    /// The title bar's height; none in full screen.
    let height: CGFloat
    /// The title's trailing edge in the overlay's coordinate space, as it lays out: how far the row's buttons and the
    /// title's text reach from its leading side.
    let onReach: (CGFloat) -> Void

    @Environment(\.playerWindowButtons) private var windowButtons

    /// The row's height: the title bar's, `titleBar`; in full screen, where there is none, the window's buttons' row's.
    static func height(titleBar: CGFloat, windowButtons: PlayerWindowButtons?) -> CGFloat {
        titleBar > 0 ? titleBar : windowButtons?.rowHeight ?? PlayerWindowButtons.titleBarHeight
    }

    var body: some View {
        HStack(spacing: 8) {
            PlayerIconButton(systemImage: "chevron.backward", label: T.shared.nav.player.close, id: "player.back") {
                store.dispatch(command: PlayerCommandClose.shared)
            }
            if state.showsPlaybackControls {
                PictureInPictureButton(pictureInPicture: pictureInPicture)
            }
            PlayerTitle(state: state)
                .onGeometryChange(for: CGFloat?.self) { proxy in
                    proxy.bounds(of: .named(PlayerOverlay.space)).map { proxy.size.width - $0.minX }
                } action: { if let reach = $0 { onReach(reach) } }
            Spacer(minLength: 0)
        }
        .padding(.leading, 76)
        .padding(.trailing, 16)
        .frame(height: Self.height(titleBar: height, windowButtons: windowButtons))
        .overlay(alignment: .topLeading) {
            if let windowButtons {
                PlayerWindowButtonsHost(buttons: windowButtons)
                    .frame(width: windowButtons.view.frame.width, height: windowButtons.view.frame.height)
                    .padding(.leading, windowButtons.origin.x)
                    .padding(.top, windowButtons.origin.y)
            }
        }
    }
}

/// The floating control bar (`player.bar.bottom`): who controls playback while a watch-together room keeps it from this
/// device (back, play/pause, forward and the timeline disabled then), the volume, back/play/forward (between the
/// previous and the next episode for an episode of a series), the session indicator, the menus, the options menu (with
/// the playback info) and full screen; under them the timeline with its times inline. Live, no timeline: the live label
/// right after play/pause, which stays in the middle, and the other buttons in their places; with a rewind window the
/// timeline is back as the window and the label goes to live.
private struct ControlBar: View {
    let playback: PlayerModel
    let state: PlayerUiState

    var body: some View {
        let store = playback.store
        VStack(spacing: 8) {
            if let locked = state.watch?.lockLine {
                PlayerControlsLockedLine(text: locked)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 0) {
                VolumeControl(volume: state.volume, store: store)
                    .frame(maxWidth: .infinity, alignment: .leading)
                PlayerTransport(playback: playback, state: state, spacing: 14)
                HStack(spacing: 4) {
                    if state.mode != .video {
                        LiveLabel(timeline: playback.timeline, window: state.mode == .liveWindow, store: store)
                            .padding(.leading, PlayerMetrics.liveLabelGap)
                        Spacer(minLength: 8)
                    }
                    if let indicator = state.watch?.indicator {
                        PlayerWatchIndicator(indicator: indicator, store: store)
                    }
                    PlayerMenuBar(playback: playback, state: state)
                    PlayerIconButton(
                        systemImage: state.fullscreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                        label: state.fullscreen ? T.shared.nav.player.exitFullscreen : T.shared.nav.player.fullscreen,
                        id: "player.fullscreen"
                    ) {
                        store.dispatch(command: PlayerCommandToggleFullscreen.shared)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
            if state.showsTimeline {
                TimelineRow(state: state, timeline: playback.timeline, store: store, seek: playback.config.steps.seekSeconds)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .frame(maxWidth: 720)
        .playerGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.bar.bottom")
        // The timeline moves as the video plays: followed while the bar shows it.
        .task { await playback.timeline.observe() }
    }
}

/// Mute and a volume slider (the Mac; phones and TVs have hardware volume).
private struct VolumeControl: View {
    let volume: VolumeState
    let store: PlayerStore

    var body: some View {
        HStack(spacing: 4) {
            PlayerIconButton(
                systemImage: volume.muted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                label: volume.muted ? T.shared.player.volume.unmute : T.shared.player.volume.mute,
                id: "player.mute"
            ) {
                store.dispatch(command: PlayerCommandToggleMute.shared)
            }
            Slider(
                value: Binding(
                    get: { volume.muted ? 0 : Double(volume.fraction) },
                    set: { store.dispatch(command: PlayerCommandSetVolume(percent: $0 * 100)) }
                )
            )
            .controlSize(.mini)
            .frame(width: 84)
            .accessibilityLabel(T.shared.player.volume.title)
            .accessibilityValue(volume.label)
        }
    }
}
#endif
