import SwiftUI
import TortugaKit

/// Everything drawn over the video, all of it from `PlayerUiState`, in each platform's own player layout (as close to
/// the system player as a custom mpv surface allows):
///
/// - iOS and iPadOS (`TouchControls`): Liquid Glass controls as the system player has them: close and picture in
///   picture top left, the audio, subtitle and speed menus and the options menu top right, back/play/forward in the
///   middle (between the previous and the next episode for an episode of a series), the title and the timeline with
///   its times inline at the bottom.
/// - macOS (`DesktopControls`): the title beside the window's buttons and a floating glass control bar, as QuickTime
///   Player has them; the window shows no toolbar or title of its own while the player is up (`PlayerWindowChrome`).
/// - tvOS (`TVControls`): the system player's transport bar: the title and the menu buttons over a timeline with its
///   times under it; a menu or the options open in a card above their button, the playback info in a panel across the
///   bottom (`PlayerTVPanel`). Its buttons are `PlayerTVButtonStyle`'s platters.
///
/// A live playback (`PlaybackMode.live`) has no timeline and no back or forward (`PlayerUiState.showsTimeline`,
/// `showsSeekButtons`): the live label (`LiveLabel`) shows right after play/pause (the Mac's control bar; on tvOS
/// play/pause takes the timeline's place), or where the timeline starts where play/pause is in the middle (iOS). With a
/// rewind window (`PlaybackMode.liveWindow`) the timeline is back as the window, from its start to the live edge, still
/// without back or forward, and the live label is the go-to-live button: on the Mac where it was, on iOS in the time
/// left's place on the timeline row, on tvOS first among the transport bar's buttons.
///
/// The timeline (its bar, its times, the live label and a scrub's preview) draws from `PlayerModel.timeline`, which
/// moves several times a second as the video plays: only those views read it, so nothing else is drawn again as it
/// moves, and each platform's controls observe it while they show it, so with the controls down nothing follows it.
///
/// Over the controls: the loading spinner with the store's text (a watch-together guest's search for its file in its
/// place), the room's pill with the transient message (volume, speed, zoom, track, the room's toasts), the sleep
/// timer's "Still watching?" card above the prompt button (skip, up next) at the bottom-trailing corner, above the
/// bottom controls while they show, a guest's prompt and the error.
///
/// The error is full screen: the player is black behind it from edge to edge, and the controls over it are the title
/// and back alone, where the platform's controls have them (`PlayerUiState.showsPlaybackControls`): close on iOS, back
/// and the title on the Mac, the title on Apple TV; no transport, timeline or live label, menus, picture in picture or
/// room indicator: the error's buttons are all there is to reach.
///
/// While picture in picture runs (iOS), the controls are close and the picture in picture button alone, and they stay
/// up: AVKit's placeholder is where the video was, and the PiP window has the playback's own controls (`TouchControls`).
///
/// On iOS and iPadOS nothing over the video meets the controls, in either orientation on any size, and nothing keeps
/// further from the edges than that needs:
/// - The messages at the top (the pill, the transient message, the held finger's speed, and the loading text while the
///   controls show) sit side by side in the top bar's row, centred and level with its buttons, the same with the
///   controls up or down, where the room between its two groups of buttons holds them (an iPad, an iPhone in
///   landscape). Where it doesn't (an iPhone upright) they go under the top bar and keep above what is in the middle,
///   the transport or the loading spinner, one above the other where there is room, else side by side.
/// - A swipe's level (`PlayerLevelIndicator`) stands at the edge of the side swiped, vertically centred, inside the
///   safe area: the brightness at the left, the volume at the right.
/// - With the controls up, the corner sits directly above the timeline, its trailing edge on the timeline's, beside the
///   title lines; above them where it would meet them (a long title, a narrow screen). Where it reaches up beside the
///   centre transport (an iPhone in landscape) it narrows to the room beside it.
/// - While the store loads with the controls up, the spinner is in play/pause and its text among those messages; with
///   the controls down, both are in the middle, where nothing else is.
/// - An error, a guest's prompt or its search takes the middle alone (`centreAlone`): nothing plays, so no transport and
///   no bottom bar show with it, and it sits centred under the top bar and the messages, scrolling where it is taller
///   than that room.
///
/// On the Mac, in a window and in full screen:
/// - The messages at the top sit side by side on the title bar's row, centred on the window, level with back and as tall
///   (`PlayerOsdMessage`, `PlayerWatchPill`), the same with the controls up or down, where they keep clear of the
///   title's text (and the buttons before it) by a gap: clear of the window's top edge as back is. Where they don't
///   clear the title (a long title, a narrow window) they go under the row.
/// - With the controls up, the corner sits directly above the control bar, its trailing edge on the bar's: in from the
///   window's edge where the window is wider than the bar.
/// Buttons, menus and the timeline send `PlayerCommand`s straight to the store.
struct PlayerOverlay: View {
    let playback: PlayerModel
    let state: PlayerUiState
    let focus: FocusState<PlayerFocus?>.Binding
    /// The top of the bottom controls in global coordinates, as they lay out (the subtitle lift).
    let onControlsTop: (CGFloat) -> Void

    /// The overlay's frame, the top of the bottom controls, on iOS the centre transport's frame and on the Mac the
    /// control bar's (the last they laid out), in global coordinates, and the corner's height: where the corner's card
    /// and button go. On iOS also the trailing edge of the title lines and the top of the timeline (global), where the
    /// corner goes beside the title; the frame of the loading spinner and its text in the middle, and the height of the
    /// messages at the top: where those messages and what takes the middle alone go.
    @State private var bounds = CGRect.zero
    @State private var controlsTop: CGFloat?
    #if os(iOS)
    @State private var transport: CGRect?
    @State private var titleTrailing: CGFloat?
    @State private var timelineTop: CGFloat?
    @State private var spinner: CGRect?
    @State private var statusHeight: CGFloat = 0
    #elseif os(macOS)
    @State private var controlBar: CGRect?
    /// The copies of the window's buttons in full screen, which set the title row's height there (`DesktopTitleBar`).
    @Environment(\.playerWindowButtons) private var windowButtons
    #elseif os(tvOS)
    /// The top of the transport bar's buttons (global), which a menu opens above.
    @State private var buttonsTop: CGFloat?
    /// The menu buttons' frames (global), by the tab each opens (`PlayerMenuButtonFrames`).
    @State private var menuButtons: [PlayerPanelTab: CGRect] = [:]
    @Environment(\.layoutDirection) private var layoutDirection
    #endif
    #if !os(tvOS)
    /// How far the top bar's row reaches from the overlay's sides, as it last laid out with the controls up: on iOS its
    /// two groups of buttons; on the Mac, whose title row holds nothing on its right, the window's buttons, back,
    /// picture in picture and the title's text. Kept while they are down: the overlay keeps to the safe area and what
    /// the row holds doesn't change as the device turns, so neither do these distances.
    @State private var topRowLeading: CGFloat?
    #if os(iOS)
    @State private var topRowTrailing: CGFloat?
    #endif

    /// The overlay's coordinate space, where the controls measure the top bar's row.
    nonisolated static let space = "PlayerOverlay"
    #endif
    @State private var cornerHeight: CGFloat = 0

    var body: some View {
        let store = playback.store
        let reportControlsTop: (CGFloat) -> Void = { top in
            controlsTop = top
            onControlsTop(top)
        }
        ZStack {
            // The error is full screen: the player black behind it from edge to edge, under the controls left with it
            // (`PlayerErrorPanel`).
            if state.centre is PlayerCentreError {
                Color.black.ignoresSafeArea()
            }
            #if os(tvOS)
            let tab = PlayerPanelTab(state)
            // The info panel takes the transport bar's place, as AVKit's does.
            if tab != .info {
                TVControls(playback: playback, state: state, focus: focus, onControlsTop: reportControlsTop) { buttonsTop = $0 }
            }
            if let tab {
                PlayerTVPanel(tab: tab, state: state, store: store, focus: focus)
                    .padding(.trailing, menuTrailing(tab))
                    .padding(.bottom, tab == .info ? 0 : menuBottom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: tab == .info ? .bottom : .bottomTrailing)
                    .transition(.opacity)
            }
            #elseif os(macOS)
            DesktopControls(
                playback: playback,
                state: state,
                onControlBar: { bar in
                    controlBar = bar
                    reportControlsTop(bar.minY)
                },
                // Only as the controls show: going away, they may lay out once more as they fade.
                onTopRowLeading: { if playback.state.state.controlsVisible { topRowLeading = $0 } }
            )
            if state.infoVisible {
                PlayerInfoPanel(sections: state.info)
                    .controlRegion(store)
                    .padding(.top, 48)
                    .padding(.horizontal, 20)
                    .padding(.bottom, PlayerMetrics.infoBottomRoom)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
            #else
            TouchControls(
                playback: playback,
                state: state,
                centreAlone: centreAlone,
                pictureInPicture: inPictureInPicture,
                onControlsTop: reportControlsTop,
                onTitleTrailing: { titleTrailing = $0 },
                onTimelineTop: { timelineTop = $0 },
                // Only as the controls show (in picture in picture all along): going away, they may lay out once more
                // as they fade.
                onTopRowLeading: { if controlsShow { topRowLeading = $0 } },
                onTopRowTrailing: { if controlsShow { topRowTrailing = $0 } }
            ) {
                transport = $0
            }
                // Going away, they fade out for a moment: a tap meanwhile goes to the video, which brings them back,
                // not to a fading button, which goes before the touch ends and never acts on it. In picture in picture
                // they stay.
                .allowsHitTesting(state.controlsVisible || inPictureInPicture)
            #endif
            switch onEnum(of: state.centre) {
            case .loading(let loading):
                #if os(iOS)
                if !playbackControlsUp {
                    LoadingOverlay(text: loading.text)
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { spinner = $0 }
                        .onDisappear { spinner = nil }
                        .allowsHitTesting(false)
                }
                #else
                LoadingOverlay(text: loading.text)
                    .allowsHitTesting(false)
                #endif
            case .matching(let matching):
                PlayerWatchMatching(text: matching.text)
                    #if os(iOS)
                    .modifier(CentreAlone(top: aloneTop))
                    #endif
                    .allowsHitTesting(false)
            case .prompt, .error, nil:
                EmptyView()
            }
            if state.osd != nil || state.heldSpeed != nil || state.watch?.pill != nil || loadingTextAtTop != nil {
                #if os(iOS)
                ViewThatFits(in: .horizontal) {
                    // In the top bar's row, centred and level with its buttons, where they fit between its groups.
                    if let topRowSide {
                        HStack(spacing: 10) { messages }
                            .fixedSize()
                            .frame(minHeight: PlayerMetrics.button)
                            .padding(.horizontal, topRowSide)
                    }
                    // Else under the top bar.
                    ViewThatFits(in: .vertical) {
                        VStack(spacing: 10) { messages }
                        HStack(spacing: 10) { messages }
                    }
                    .padding(.top, PlayerMetrics.osdTop - PlayerMetrics.barInset)
                }
                // The messages' glass, one row or column of it.
                .glassGroup(spacing: 10)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { statusHeight = $0 }
                .onDisappear { statusHeight = 0 }
                .frame(maxHeight: statusRoom, alignment: .top)
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.top, PlayerMetrics.barInset)
                .allowsHitTesting(false)
                #elseif os(macOS)
                // The window's title bar is the top safe area, as for `DesktopControls`.
                GeometryReader { proxy in
                    let titleBar = proxy.safeAreaInsets.top
                    ViewThatFits(in: .horizontal) {
                        // On the title row, centred and level with back, where they clear the title.
                        if let topRowSide {
                            HStack(spacing: 10) { messages }
                                .fixedSize()
                                .frame(height: DesktopTitleBar.height(titleBar: titleBar, windowButtons: windowButtons))
                                .padding(.horizontal, topRowSide)
                        }
                        // Else under the row.
                        VStack(spacing: 10) { messages }
                            .padding(.top, titleBar + PlayerMetrics.osdTop)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .ignoresSafeArea(edges: .top)
                }
                .allowsHitTesting(false)
                #else
                VStack(spacing: 10) { messages }
                    .glassGroup(spacing: 10)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, PlayerMetrics.osdTop)
                    .allowsHitTesting(false)
                #endif
            }
            // The level a swipe changed, at the edge of the side swiped (as seen, in every language) and fading in and
            // out with it.
            ZStack {
                if let level = state.level {
                    PlayerLevelIndicator(level: level)
                        .padding(.horizontal, PlayerLevelIndicator.edge)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: level.kind == .brightness ? .leading : .trailing)
                        .environment(\.layoutDirection, .leftToRight)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.16), value: state.level?.kind)
            if state.stillWatching != nil || state.prompt != nil {
                #if os(iOS)
                // Beside the title lines where it fits there, else above them.
                ViewThatFits(in: .horizontal) {
                    if let beside = cornerBesideTitle {
                        corner(bottom: beside.bottom).padding(.leading, beside.leading)
                    }
                    corner(bottom: cornerBottom)
                }
                #else
                corner(bottom: cornerBottom)
                #endif
            }
            switch onEnum(of: state.centre) {
            case .prompt(let prompt):
                PlayerWatchPromptCard(prompt: prompt.prompt, store: store, focus: focus)
                    .controlRegion(store)
                    #if os(iOS)
                    .modifier(CentreAlone(top: aloneTop))
                    #endif
            case .error(let error):
                PlayerErrorPanel(error: error.error, store: store, focus: focus)
                    .controlRegion(store)
                    #if os(iOS)
                    .modifier(CentreAlone(top: aloneTop))
                    #endif
            case .loading, .matching, nil:
                EmptyView()
            }
        }
        #if os(iOS)
        // The text over the video grows to the first accessibility size and no further: past it the bars' rows took
        // the video's height. The buttons' symbols grow with it (`PlayerIconButton`); a long press shows one large.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        #endif
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { bounds = $0 }
        #if os(tvOS)
        .onPreferenceChange(PlayerMenuButtonFrames.self) { menuButtons = $0 }
        #endif
        #if !os(tvOS)
        .coordinateSpace(.named(Self.space))
        #endif
        .animation(.easeOut(duration: 0.2), value: state.controlsVisible)
        .animation(.easeOut(duration: 0.25), value: PlayerPanelTab(state))
        // The transient message (volume, speed, a room's notice) is gone in a moment, wherever VoiceOver is.
        .announcing(state.osd)
    }

    #if os(tvOS)
    /// A menu's distance from the overlay's bottom: AVKit's, 24 pt above the buttons.
    private var menuBottom: CGFloat {
        guard let buttonsTop, bounds.maxY > buttonsTop else { return PlayerMetrics.overlayGap }
        return bounds.maxY - buttonsTop + PlayerMetrics.overlayGap
    }

    /// A menu's trailing edge on its button's, as AVKit's: its button's distance in from the row's trailing end (the
    /// last button's, on the left in a right-to-left language); the info panel, which has no button, none.
    private func menuTrailing(_ tab: PlayerPanelTab) -> CGFloat {
        guard let button = menuButtons[tab] else { return 0 }
        let frames = menuButtons.values
        if layoutDirection == .rightToLeft { return button.minX - (frames.map(\.minX).min() ?? button.minX) }
        return (frames.map(\.maxX).max() ?? button.maxX) - button.maxX
    }
    #endif

    /// The messages at the top: the room's pill, the transient message, the speed a held finger plays at and, on iOS
    /// with the controls up, the loading text (its spinner is in play/pause then).
    @ViewBuilder private var messages: some View {
        if let pill = state.watch?.pill { PlayerWatchPill(text: pill) }
        if let osd = state.osd { PlayerOsdMessage(text: osd) }
        if let speed = state.heldSpeed { PlayerHeldSpeedMessage(text: speed) }
        if let text = loadingTextAtTop { LoadingText(text: text) }
    }

    private var loadingTextAtTop: String? {
        #if os(iOS)
        playbackControlsUp ? (state.centre as? PlayerCentreLoading)?.text : nil
        #else
        nil
        #endif
    }

    #if os(iOS)
    /// An error, a guest's prompt or its search: nothing plays, so it takes the middle without the transport and the
    /// bottom bar.
    private var centreAlone: Bool {
        state.centre is PlayerCentreError || state.centre is PlayerCentrePrompt || state.centre is PlayerCentreMatching
    }

    /// Picture in picture runs: AVKit's placeholder is where the video was, under close and the PiP button alone.
    private var inPictureInPicture: Bool { playback.surface.pictureInPicture.active }

    /// The controls show (in picture in picture close and its button, all along), read from the store when a layout
    /// reports rather than from the state the body was built with.
    private var controlsShow: Bool { playback.state.state.controlsVisible || inPictureInPicture }
    #endif

    /// The bottom controls (on iOS also the centre transport) show: the controls are up, and on iOS picture in picture
    /// doesn't run.
    private var playbackControlsUp: Bool {
        #if os(iOS)
        state.controlsVisible && !inPictureInPicture
        #else
        state.controlsVisible
        #endif
    }

    #if os(iOS)
    /// The most the messages at the top may take from the top bar's row down: to what is in the middle (the transport
    /// with the controls up, the loading spinner without) less a gap; nil without anything there.
    private var statusRoom: CGFloat? {
        guard !centreAlone, let middle = playbackControlsUp ? transport : spinner, bounds.height > 0 else { return nil }
        return max(0, middle.minY - bounds.minY - PlayerMetrics.barInset - PlayerMetrics.overlayGap)
    }

    /// Where what takes the middle alone starts: under the top bar and the messages at the top.
    private var aloneTop: CGFloat {
        guard statusHeight > 0 else { return PlayerMetrics.osdTop }
        return max(PlayerMetrics.osdTop, PlayerMetrics.barInset + statusHeight + PlayerMetrics.overlayGap)
    }

    /// With the controls up, where the corner goes directly above the timeline beside the title lines: the leading edge
    /// of the room past them (and a gap), and the corner's bottom; nil with the controls down or the middle alone.
    private var cornerBesideTitle: (leading: CGFloat, bottom: CGFloat)? {
        guard playbackControlsUp, !centreAlone, let titleTrailing, let timelineTop, bounds.maxY > timelineTop else { return nil }
        return (titleTrailing - bounds.minX + PlayerMetrics.overlayGap, bounds.maxY - timelineTop + PlayerMetrics.overlayGap)
    }
    #endif

    #if !os(tvOS)
    /// How far the messages at the top keep from both of the overlay's sides to sit centred on the top bar's row, a gap
    /// from what it holds: on iOS between its two groups of buttons, on the Mac past the title. Nil before the row has
    /// laid out.
    private var topRowSide: CGFloat? {
        #if os(macOS)
        topRowLeading.map { $0 + PlayerMetrics.overlayGap }
        #else
        guard let topRowLeading, let topRowTrailing else { return nil }
        return max(topRowLeading, topRowTrailing) + PlayerMetrics.overlayGap
        #endif
    }
    #endif

    /// The sleep timer's card above the prompt button, `bottom` above the overlay's bottom, its trailing edge on the
    /// controls' (`cornerTrailing`).
    private func corner(bottom: CGFloat) -> some View {
        VStack(alignment: .trailing, spacing: PlayerMetrics.overlayGap) {
            if let stillWatching = state.stillWatching {
                PlayerStillWatchingCard(stillWatching: stillWatching, store: playback.store, focus: focus)
            }
            if let prompt = state.prompt {
                PlayerPromptButton(prompt: prompt, store: playback.store, focus: focus)
            }
        }
        .controlRegion(playback.store)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cornerHeight = $0 }
        // The card's and the prompt button's glass, one column of it.
        .glassGroup(spacing: PlayerMetrics.overlayGap)
        // A card's width at most: the card fills it, the prompt button hugs its label within it.
        .frame(maxWidth: min(PlayerMetrics.cardWidth, cornerMaxWidth(bottom: bottom) ?? .infinity), alignment: .trailing)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        .padding(.trailing, cornerTrailing)
        .padding(.bottom, bottom)
    }

    /// The room under the corner's card and button: above the bottom controls while they show, else the corner's margin
    /// (the controls' own distance from the safe area's bottom).
    private var cornerBottom: CGFloat {
        guard playbackControlsUp, let top = controlsTop, bounds.maxY > top else { return PlayerMetrics.cornerInset }
        return bounds.maxY - top + PlayerMetrics.overlayGap
    }

    /// The corner's distance from the overlay's trailing side: on the Mac with the controls up, the control bar's,
    /// which is narrower than a wide window; else the controls' margin.
    private var cornerTrailing: CGFloat {
        #if os(macOS)
        guard state.controlsVisible, let controlBar, bounds.maxX > controlBar.maxX else { return PlayerMetrics.edge }
        return bounds.maxX - controlBar.maxX
        #else
        PlayerMetrics.edge
        #endif
    }

    /// The widest the corner may be with its bottom `bottom` above the overlay's: on iOS, where it would reach up beside
    /// the centre transport (iPhone in landscape), the room between the transport and the trailing edge; elsewhere a
    /// card's width.
    private func cornerMaxWidth(bottom: CGFloat) -> CGFloat? {
        #if os(iOS)
        guard let transport, bounds.width > 0 else { return nil }
        let cornerTop = bounds.maxY - bottom - cornerHeight
        guard cornerTop < transport.maxY + PlayerMetrics.overlayGap else { return nil }
        return bounds.maxX - transport.maxX - PlayerMetrics.overlayGap - PlayerMetrics.edge
        #else
        nil
        #endif
    }
}

/// The title and the episode line: under it, beside it on the Mac's title bar, above it on tvOS (AVKit's place, the
/// title on up to two lines there, 871 pt across as AVKit's).
struct PlayerTitle: View {
    let state: PlayerUiState

    var body: some View {
        Group {
            #if os(macOS)
            HStack(alignment: .firstTextBaseline, spacing: 8) { lines }
            #elseif os(tvOS)
            VStack(alignment: .leading, spacing: 6) {
                if let subtitle = state.subtitle {
                    Text(subtitle).font(PlayerMetrics.subtitleFont).playerMuted().lineLimit(1)
                }
                Text(state.title).font(PlayerMetrics.titleFont).lineLimit(2)
            }
            .frame(maxWidth: 871, alignment: .leading)
            #else
            VStack(alignment: .leading, spacing: 2) { lines }
            #endif
        }
        #if !os(tvOS)
        .lineLimit(1)
        #endif
        .shadow(color: .black.opacity(0.4), radius: 6)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("player.title")
    }

    #if !os(tvOS)
    @ViewBuilder
    private var lines: some View {
        Text(state.title).font(PlayerMetrics.titleFont)
        if let subtitle = state.subtitle {
            Text(subtitle).font(PlayerMetrics.subtitleFont).playerMuted()
        }
    }
    #endif
}

#if !os(tvOS)
/// The position, the timeline and the time left on one line (`0:15 ━━━━─── −0:15`). A live window's (`−1:30 ━━━━━━─`)
/// has where the window starts on the left and no time left: the bar ends at the live edge; on iOS the live label, the
/// go-to-live button, takes the time left's place (`LiveLabel`). Time runs left to right in every language: playback
/// controls aren't mirrored (UIKit's `.playback` content), so the row keeps its order in Arabic, as the keys and the
/// video's taps that seek do.
struct TimelineRow: View {
    let state: PlayerUiState
    /// The timeline (`PlayerModel.timeline`), which the row reads for its times and its bar.
    let timeline: StoreObserver<TimelineState>
    let store: PlayerStore
    /// The seek step (`InputSteps.seekSeconds`): VoiceOver's and Switch Control's adjustment of the timeline.
    let seek: Double

    var body: some View {
        let now = timeline.state
        HStack(spacing: PlayerMetrics.timelineSpacing) {
            Text(now.position)
                .accessibilityIdentifier("player.position")
            ScrubBar(state: state, timeline: now, store: store, seek: seek)
                .accessibilityIdentifier("player.scrub")
            if state.mode == .liveWindow {
                #if os(iOS)
                LiveLabel(timeline: timeline, window: true, store: store)
                #endif
            } else {
                Text(now.remaining)
                    .accessibilityIdentifier("player.remaining")
            }
        }
        .font(PlayerMetrics.timeFont)
        .lineLimit(1)
        .fixedSize(horizontal: false, vertical: true)
        .environment(\.layoutDirection, .leftToRight)
    }
}
#endif

/// A live playback's label: a red dot, then the store's live text (`TimelineState.liveLabel`, `● LIVE · 0:07`) in the
/// timeline's time style; VoiceOver reads the store's spoken live text (`Live · watched 0:07`). Without a rewind window
/// it is plain text in the timeline row's place: no pill, nothing focusable. With one (`window`, given the `store`) it
/// is the go-to-live button, as on Android TV: at the live edge as above, behind it the dot in the text's colour muted
/// and the label with the time behind (`● LIVE · −0:38`, read as `Go to live`, `Live · 0:38 behind`); on tvOS
/// a pill of the transport bar's buttons, focused as they are (`PlayerFocus.goToLive`).
struct LiveLabel: View {
    /// The timeline (`PlayerModel.timeline`): the live label's text, the live edge and the spoken live text.
    let timeline: StoreObserver<TimelineState>
    /// The playback has a rewind window (`PlaybackMode.liveWindow`).
    let window: Bool
    var store: PlayerStore?
    var focus: FocusState<PlayerFocus?>.Binding?

    var body: some View {
        let now = timeline.state
        if window, let store {
            Button {
                store.dispatch(command: PlayerCommandGoToLive.shared)
            } label: {
                content(dot: now.atLiveEdge ? AnyShapeStyle(Palette.live) : AnyShapeStyle(.secondary), label: now.liveLabel)
                    #if os(tvOS)
                    .frame(height: PlayerMetrics.buttonFrame)
                    #else
                    .frame(minHeight: PlayerMetrics.button)
                    .contentShape(Rectangle())
                    #endif
            }
            #if os(tvOS)
            .buttonStyle(PlayerTVButtonStyle(shape: Capsule(), insets: EdgeInsets(top: 0, leading: 24, bottom: 0, trailing: 24)))
            #else
            .buttonStyle(PlayerPressStyle())
            #endif
            .modifier(OptionalPlayerFocus(focus: focus, target: .goToLive))
            .accessibilityLabel(T.shared.player.live.goToLive)
            .accessibilityValue(now.positionLabel)
            .accessibilityIdentifier("player.live")
        } else {
            content(dot: AnyShapeStyle(Palette.live), label: now.liveLabel)
                .foregroundStyle(Palette.onVideo)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(now.positionLabel)
                .accessibilityIdentifier("player.live")
        }
    }

    private func content(dot: AnyShapeStyle, label: String) -> some View {
        HStack(spacing: PlayerMetrics.liveDotGap) {
            Circle()
                .fill(dot)
                .frame(width: PlayerMetrics.liveDot, height: PlayerMetrics.liveDot)
            Text(label)
                .font(PlayerMetrics.timeFont)
                .lineLimit(1)
        }
        .fixedSize()
    }
}

#if os(iOS) || os(macOS)
/// Back, play/pause and forward, disabled while a watch-together room keeps playback from this device; for an episode
/// of a series between the previous and the next episode's buttons, each enabled as `PlayerUiState.episodes` has it:
/// iOS's centre transport and the Mac's in its control bar, at each one's sizes (`PlayerMetrics.transportPlay`). Apple
/// TV has its episode buttons in its button row, and Select on the timeline plays. Live, play/pause alone: nothing
/// seeks, or a rewind window's timeline does. Left to right in every language, as the timeline (`TimelineRow`).
struct PlayerTransport: View {
    let playback: PlayerModel
    let state: PlayerUiState
    let spacing: CGFloat
    /// The store loads: play/pause shows the spinner (iOS's centre transport, where the spinner would be).
    var loading = false

    var body: some View {
        let store = playback.store
        let seek = playback.config.steps.seekSeconds
        HStack(spacing: spacing) {
            if let episodes = state.episodes {
                EpisodeButton(
                    direction: .previous,
                    enabled: episodes.previous,
                    store: store,
                    size: PlayerMetrics.transportEpisode,
                    iconSize: PlayerMetrics.transportEpisodeIcon
                )
            }
            // A guest of a host-only room has them off, but play/pause while its player paused here alone, whose Play
            // catches up with the room (`canPlayPause`).
            HStack(spacing: spacing) {
                if state.showsSeekButtons {
                    PlayerIconButton(
                        systemImage: SkipSymbol.back(seek),
                        label: T.shared.nav.player.rewind,
                        id: "player.rewind",
                        size: PlayerMetrics.transportSkip,
                        iconSize: PlayerMetrics.transportSkipIcon
                    ) {
                        store.dispatch(command: PlayerCommandSeekBy(seconds: -seek))
                    }
                    .transportGlass()
                    .disabled(!state.canControl)
                    .opacity(state.canControl ? 1 : 0.45)
                }
                PlayerIconButton(
                    systemImage: state.paused ? "play.fill" : "pause.fill",
                    label: state.playPauseLabel,
                    id: "player.playPause",
                    size: PlayerMetrics.transportPlay,
                    iconSize: PlayerMetrics.transportPlayIcon,
                    busy: loading
                ) {
                    store.dispatch(command: PlayerCommandTogglePause.shared)
                }
                .transportGlass()
                .disabled(!state.canPlayPause)
                .opacity(state.canPlayPause ? 1 : 0.45)
                if state.showsSeekButtons {
                    PlayerIconButton(
                        systemImage: SkipSymbol.forward(seek),
                        label: T.shared.nav.player.fastForward,
                        id: "player.fastForward",
                        size: PlayerMetrics.transportSkip,
                        iconSize: PlayerMetrics.transportSkipIcon
                    ) {
                        store.dispatch(command: PlayerCommandSeekBy(seconds: seek))
                    }
                    .transportGlass()
                    .disabled(!state.canControl)
                    .opacity(state.canControl ? 1 : 0.45)
                }
            }
            if let episodes = state.episodes {
                EpisodeButton(
                    direction: .next,
                    enabled: episodes.next,
                    store: store,
                    size: PlayerMetrics.transportEpisode,
                    iconSize: PlayerMetrics.transportEpisodeIcon
                )
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }
}

/// Starts picture in picture (or stops it), selected while it runs; disabled while the video can't go into PiP: before
/// mpv shows it, and where the device has no PiP.
struct PictureInPictureButton: View {
    let pictureInPicture: PictureInPicture

    var body: some View {
        PlayerIconButton(
            systemImage: pictureInPicture.active ? "pip.exit" : "pip.enter",
            label: T.shared.nav.player.pictureInPicture,
            id: "player.pip"
        ) {
            pictureInPicture.toggle()
        }
        .disabled(!pictureInPicture.possible && !pictureInPicture.active)
        // Its symbol alone draws the state: VoiceOver says it as selected.
        .accessibilityAddTraits(pictureInPicture.active ? .isSelected : [])
    }
}
#endif

/// The previous or the next episode's button (`player.previous`, `player.next`) beside the transport, disabled and
/// dimmed without an episode that way that this device may start (`PlayerUiState.episodes`); on iOS on its own glass,
/// as the transport's other buttons, on tvOS the player's button style, which dims it itself, and focus as
/// `PlayerFocus.previousEpisode` or `.nextEpisode`.
struct EpisodeButton: View {
    enum Direction { case previous, next }

    let direction: Direction
    let enabled: Bool
    let store: PlayerStore
    var size: CGFloat = PlayerMetrics.button
    var iconSize: CGFloat = PlayerMetrics.icon
    /// The player's focus (tvOS).
    var focus: FocusState<PlayerFocus?>.Binding?

    var body: some View {
        let previous = direction == .previous
        let command: any PlayerCommand = previous ? PlayerCommandPreviousEpisode.shared : PlayerCommandNextEpisode.shared
        PlayerIconButton(
            systemImage: previous ? "backward.end.fill" : "forward.end.fill",
            label: previous ? T.shared.player.previousEpisode : T.shared.player.nextEpisode,
            id: previous ? "player.previous" : "player.next",
            size: size,
            iconSize: iconSize
        ) {
            store.dispatch(command: command)
        }
        .transportGlass()
        .modifier(OptionalPlayerFocus(focus: focus, target: previous ? .previousEpisode : .nextEpisode))
        .playerDisabled(!enabled)
        #if !os(tvOS)
        .opacity(enabled ? 1 : 0.45)
        #endif
    }
}

private extension View {
    /// A transport button's own glass on iOS, over the video; the Mac's sit on the control bar's, and tvOS's draw their
    /// own platter.
    @ViewBuilder
    func transportGlass() -> some View {
        #if os(iOS)
        playerGlass(in: Circle(), interactive: true)
        #else
        self
        #endif
    }
}

/// The skip symbols for the seek step (`gobackward.10`), where SF Symbols has one; each name spelled out for
/// an SF Symbols check.
enum SkipSymbol {
    private static let backs = [
        5: "gobackward.5", 10: "gobackward.10", 15: "gobackward.15", 30: "gobackward.30",
        45: "gobackward.45", 60: "gobackward.60", 75: "gobackward.75", 90: "gobackward.90",
    ]
    private static let forwards = [
        5: "goforward.5", 10: "goforward.10", 15: "goforward.15", 30: "goforward.30",
        45: "goforward.45", 60: "goforward.60", 75: "goforward.75", 90: "goforward.90",
    ]

    static func back(_ seconds: Double) -> String { step(backs, seconds) ?? "gobackward" }
    static func forward(_ seconds: Double) -> String { step(forwards, seconds) ?? "goforward" }

    private static func step(_ symbols: [Int: String], _ seconds: Double) -> String? {
        let whole = Int(seconds)
        return Double(whole) == seconds ? symbols[whole] : nil
    }
}

#if !os(tvOS)
/// The timeline, as the system player draws it: a thin bar with the played and buffered parts filled and chapter marks,
/// no thumb; it grows taller while scrubbing (and on the Mac under the pointer), the preview's time, its offset and its
/// chapter in a bubble above it. Drag or click to scrub, committed on release, anywhere in a band a button tall (44 pt
/// on iOS, 28 on the Mac) around the drawn bar; on the Mac the band reaches into the control bar's gaps above and below
/// it, so the bar keeps its place. VoiceOver and Switch Control adjust it: up and down seek by the seek step at once,
/// and a double-tap does nothing (a tap at its middle would seek there). (Apple TV's is `TVTimeline`.)
private struct ScrubBar: View {
    let state: PlayerUiState
    /// The timeline as its row read it (`PlayerModel.timeline`).
    let timeline: TimelineState
    let store: PlayerStore
    /// The seek step (`InputSteps.seekSeconds`): VoiceOver's and Switch Control's adjustment.
    let seek: Double

    #if os(macOS)
    @State private var hovered = false
    #endif

    /// The bar's band as drawn, with the bubble above it.
    private static let drawn = PlayerMetrics.button * 0.6

    var body: some View {
        GeometryReader { proxy in
            track(raised: isRaised)
                .frame(height: Self.drawn)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { drag in
                            let fraction = min(max(drag.location.x / max(proxy.size.width, 1), 0), 1)
                            store.dispatch(command: PlayerCommandScrubTo(fraction: fraction))
                        }
                        .onEnded { _ in store.dispatch(command: PlayerCommandCommitScrub.shared) }
                )
                // A guest in a host-only room can't seek (`PlayerUiState.canSeek`).
                .disabled(!state.canSeek)
        }
        .frame(height: PlayerMetrics.button)
        #if os(macOS)
        .onHover { hovered = $0 }
        #endif
        .accessibilityElement()
        .accessibilityLabel(T.shared.player.timeline)
        .accessibilityValue(timeline.positionLabel)
        .accessibilityAdjustableAction { direction in
            guard state.canSeek else { return }
            switch direction {
            case .increment: store.dispatch(command: PlayerCommandSeekBy(seconds: seek))
            case .decrement: store.dispatch(command: PlayerCommandSeekBy(seconds: -seek))
            @unknown default: break
            }
        }
        // Without an action of its own, a double-tap would tap the bar's middle: a seek to the middle of the video.
        .accessibilityAction {}
        #if os(macOS)
        .padding(.vertical, (Self.drawn - PlayerMetrics.button) / 2)
        #endif
    }

    /// The pointer is over the bar (the Mac).
    private var isRaised: Bool {
        #if os(macOS)
        hovered
        #else
        false
        #endif
    }

    /// The bar and the bubble; `raised` (the Mac's pointer over it) thickens the bar, scrubbing more so. While the video
    /// plays the played part moves on at each frame between the store's reports (`TimelineState.progressNow`).
    private func track(raised: Bool) -> some View {
        let scrub = timeline.scrub
        #if os(macOS)
        let height: CGFloat = scrub != nil ? 10 : raised ? 8 : 5
        #else
        let height: CGFloat = scrub != nil ? 14 : 6
        #endif
        return GeometryReader { proxy in
            let width = proxy.size.width
            TimelineView(.animation(paused: timeline.motion == nil)) { _ in
                let progress = Double(scrub?.progress ?? timeline.progressNow())
                ZStack(alignment: .leading) {
                    Rectangle().fill(Palette.onVideo.opacity(0.25))
                    Rectangle().fill(Palette.onVideo.opacity(0.4))
                        .frame(width: width * clamp(Double(timeline.buffered)))
                    Rectangle().fill(Palette.onVideo)
                        .frame(width: width * clamp(progress))
                    // Chapter starts as gaps in the bar.
                    ForEach(Array(timeline.chapters.enumerated()), id: \.offset) { _, mark in
                        Rectangle()
                            .fill(.black.opacity(0.6))
                            .frame(width: 2)
                            .offset(x: width * CGFloat(truncating: mark) - 1)
                    }
                }
            }
            .frame(height: height)
            .clipShape(Capsule())
            .frame(maxHeight: .infinity)
            .overlay(alignment: .topLeading) {
                if let scrub {
                    ScrubBubble(scrub: scrub, anchor: width * clamp(Double(scrub.progress)), width: width)
                }
            }
        }
        .animation(.easeOut(duration: 0.15), value: height)
    }

    private func clamp(_ value: Double) -> CGFloat { CGFloat(min(max(value, 0), 1)) }
}

/// The scrub preview's time and offset (and its chapter) centred over the preview, kept inside the bar, above it.
private struct ScrubBubble: View {
    let scrub: ScrubState
    let anchor: CGFloat
    let width: CGFloat

    @State private var size = CGSize.zero

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 8) {
                Text(scrub.position)
                Text(scrub.offset)
            }
            .font(PlayerMetrics.timeFont)
            if let chapter = scrub.chapter {
                Text(chapter).font(.caption).lineLimit(1)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .playerGlass(in: Capsule())
        .fixedSize()
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
        .offset(x: min(max(anchor - size.width / 2, 0), max(width - size.width, 0)), y: -(size.height + 12))
        .accessibilityHidden(true)
    }
}
#endif

/// The loading spinner with the store's text ("Preparing stream…", "Buffering…").
private struct LoadingOverlay: View {
    let text: String?

    var body: some View {
        VStack(spacing: 12) {
            ProgressView()
                .largeSpinner()
                .tint(Palette.onVideo)
            if let text { LoadingText(text: text) }
        }
    }
}

/// The store's loading text on its glass: under the spinner, or on iOS with the controls up among the messages at the
/// top (`PlayerOverlay`).
private struct LoadingText: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .playerGlass(in: Capsule())
            .accessibilityIdentifier("player.loading")
    }
}

#if os(iOS)
/// What takes the middle alone (an error, a guest's prompt or its search) centred in the room from `top` (under the top
/// bar and the messages there) to the bottom, scrolling where it is taller than that room.
private struct CentreAlone: ViewModifier {
    let top: CGFloat

    func body(content: Content) -> some View {
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.top, top)
        .padding(.bottom, PlayerMetrics.edge)
    }
}
#endif
