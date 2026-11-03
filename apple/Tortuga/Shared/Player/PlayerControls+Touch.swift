import SwiftUI
import TortugaKit

#if os(iOS)
/// The iOS and iPadOS controls (see `PlayerOverlay`). An iPad's pointer resting on them keeps them up
/// (`controlRegion`).
struct TouchControls: View {
    let playback: PlayerModel
    let state: PlayerUiState
    /// An error, a guest's prompt or its search takes the middle (`PlayerOverlay`): no transport, no bottom bar.
    let centreAlone: Bool
    /// Picture in picture runs: close and its button alone, up all along; no menus, transport or bottom bar.
    let pictureInPicture: Bool
    let onControlsTop: (CGFloat) -> Void
    /// The trailing edge of the title lines and the top of the timeline in global coordinates, as they lay out: the
    /// corner goes between the two where it fits (`PlayerOverlay`).
    let onTitleTrailing: (CGFloat) -> Void
    let onTimelineTop: (CGFloat) -> Void
    /// How far the top bar's leading group of buttons reaches from the overlay's leading side, and its trailing group
    /// from the trailing side, as they lay out: the messages at the top go between the two where they fit
    /// (`PlayerOverlay`).
    let onTopRowLeading: (CGFloat) -> Void
    let onTopRowTrailing: (CGFloat) -> Void
    /// The centre transport's frame in global coordinates, as it lays out; nil once it is gone.
    let onTransport: (CGRect?) -> Void

    var body: some View {
        let store = playback.store
        if state.controlsVisible || pictureInPicture {
            ZStack {
                PlayerScrim()
                VStack(spacing: 0) {
                    HStack(alignment: .top, spacing: 12) {
                        HStack(spacing: 0) {
                            PlayerIconButton(systemImage: "xmark", label: T.shared.nav.player.close, id: "player.back") {
                                store.dispatch(command: PlayerCommandClose.shared)
                            }
                            // Over the error, close alone (`showsPlaybackControls`): nothing to act on but the error.
                            if state.showsPlaybackControls {
                                PictureInPictureButton(pictureInPicture: playback.surface.pictureInPicture)
                            }
                        }
                        .playerGlass(in: Capsule())
                        .onGeometryChange(for: CGFloat?.self) { proxy in
                            proxy.bounds(of: .named(PlayerOverlay.space)).map { proxy.size.width - $0.minX }
                        } action: { if let reach = $0 { onTopRowLeading(reach) } }
                        Spacer(minLength: 0)
                        HStack(alignment: .top, spacing: 12) {
                            if state.showsPlaybackControls && !pictureInPicture {
                                if let indicator = state.watch?.indicator {
                                    PlayerWatchIndicator(indicator: indicator, store: store)
                                        .playerGlass(in: Capsule())
                                }
                                PlayerMenuBar(playback: playback, state: state)
                                    .playerGlass(in: Capsule())
                            }
                        }
                        .onGeometryChange(for: CGFloat?.self) { proxy in
                            proxy.bounds(of: .named(PlayerOverlay.space)).map(\.maxX)
                        } action: { if let reach = $0 { onTopRowTrailing(reach) } }
                    }
                    // The capsules' glass, one row of it.
                    .glassGroup(spacing: 12)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("player.bar.top")
                    .controlRegion(store)
                    Spacer(minLength: 0)
                    if !centreAlone && !pictureInPicture {
                        VStack(alignment: .leading, spacing: 10) {
                            VStack(alignment: .leading, spacing: 10) {
                                if let locked = state.watch?.lockLine { PlayerControlsLockedLine(text: locked) }
                                PlayerTitle(state: state)
                            }
                            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).maxX } action: { onTitleTrailing($0) }
                            // Live without a window: the label where the timeline row starts, the row's height kept.
                            Group {
                                if !state.showsTimeline {
                                    LiveLabel(timeline: playback.timeline, window: false)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .frame(minHeight: 44)
                                } else {
                                    TimelineRow(state: state, timeline: playback.timeline, store: store, seek: playback.config.steps.seekSeconds)
                                        .padding(.horizontal, 18)
                                        .frame(minHeight: 44)
                                        .playerGlass(in: Capsule())
                                }
                            }
                            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { onTimelineTop($0) }
                        }
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("player.bar.bottom")
                        .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { onControlsTop($0) }
                        .controlRegion(store)
                        // The timeline moves as the video plays: followed while this bar shows it.
                        .task { await playback.timeline.observe() }
                    }
                }
                .padding(.horizontal, PlayerMetrics.edge)
                .padding(.vertical, PlayerMetrics.barInset)
                if !centreAlone && !pictureInPicture {
                    CentreTransport(playback: playback, state: state, onFrame: onTransport)
                        .controlRegion(store)
                }
            }
            .transition(.opacity)
        }
    }
}

/// The transport (`PlayerTransport`) in the middle of the video, as the system player has it, each button on its own
/// glass, the episode buttons a size smaller than the seek buttons. While the store loads, the spinner is in
/// play/pause, where it would otherwise sit behind the button.
private struct CentreTransport: View {
    let playback: PlayerModel
    let state: PlayerUiState
    /// The row's frame in global coordinates, as it lays out, nil once it is gone: the up-next card and the messages at
    /// the top keep clear of it.
    let onFrame: (CGRect?) -> Void

    @Environment(\.horizontalSizeClass) private var widthClass
    @Environment(\.verticalSizeClass) private var heightClass

    var body: some View {
        PlayerTransport(playback: playback, state: state, spacing: spacing, loading: state.centre is PlayerCentreLoading)
            // The buttons' glass, one row of it.
            .glassGroup(spacing: spacing)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("player.transport")
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { onFrame($0) }
            .onDisappear { onFrame(nil) }
    }

    /// The system player's 36 pt between three buttons, and between five on an iPad's full screen (regular width and
    /// height); 16 pt between five elsewhere: 336 pt in all, within the 16 pt margins of a 375 pt iPhone in portrait,
    /// and in landscape leaving up next room beside them.
    private var spacing: CGFloat {
        state.episodes == nil || (widthClass == .regular && heightClass == .regular) ? 36 : 16
    }
}
#endif
