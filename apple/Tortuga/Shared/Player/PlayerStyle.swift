import SwiftUI
import TortugaKit

/// The player's sizes and fonts per platform: the touch targets of iOS, the Mac's denser bar, the TV seen from across
/// the room. The playback info (Apple TV's panel, the Mac's): on the TV three columns across the panel, read from the
/// couch (values between the caption and the body size, labels in the smallest), all of them on the screen at once; on
/// the Mac a denser panel of one or two columns. A label column fits the longest English label ("MaxCLL / MaxFALL"),
/// a value column the usual values ("Tortuga Test HDR10 remux", "3840×2160 · 23.976 fps", "BT.2020 · PQ · Dolby
/// Vision", beside a column's shorter labels "English · English - TrueHD 5.1") on one line.
enum PlayerMetrics {
    #if os(tvOS)
    static let icon: CGFloat = 30
    static let button: CGFloat = 46
    /// A button's platter: its symbol's frame and 10 pt around it (`PlayerIconButton`), AVKit's 66 pt circle.
    static let buttonFrame: CGFloat = button + 20
    static let edge: CGFloat = 0
    static let osdTop: CGFloat = 40
    /// The corner's card and button: their distance from the safe area's bottom while the controls are down.
    static let cornerInset: CGFloat = 0
    /// Between what is over the video: the corner's card and button, them and the controls' top while those are up, and
    /// on iOS the messages at the top and what is in the middle.
    static let overlayGap: CGFloat = 24
    static let timelineSpacing: CGFloat = 24
    /// Between a lifted subtitle line and the top of the bottom controls.
    static let subtitleGap: CGFloat = 16
    static let titleFont = Font.title2.weight(.bold)
    static let subtitleFont = Font.caption.weight(.medium)
    static let timeFont = Font.caption.weight(.semibold).monospacedDigit()
    /// The timeline's times (`TVTimeline`), AVKit's: caption 2, bold.
    static let timelineTimeFont = Font.caption2.weight(.bold).monospacedDigit()
    /// The live label's red dot and its gap to LIVE (`LiveLabel`): the approved 10 dp and 8 dp beside 16 sp, in
    /// proportion to this time font; and play/pause to the label, the row's gap between buttons.
    static let liveDot: CGFloat = 16
    static let liveDotGap: CGFloat = 12
    static let liveLabelGap: CGFloat = 28
    static let menuSpacing: CGFloat = 28
    static let cardRadius: CGFloat = 40
    static let cardWidth: CGFloat = 440
    static let cardPadding: CGFloat = 24
    static let cardSpacing: CGFloat = 6
    static let cardButtonSpacing: CGFloat = 16
    static let cardLabelFont = Font.caption.weight(.medium)
    static let cardTitleFont = Font.callout.weight(.semibold)
    static let cardButtonFont = Font.callout.weight(.semibold)
    static let cardButtonInsets = EdgeInsets(top: 12, leading: 32, bottom: 12, trailing: 32)
    static let errorActionSpacing: CGFloat = 24
    static let errorMaxWidth: CGFloat = 1000
    static let errorStackedWidth: CGFloat = 640
    static let promptActionSpacing: CGFloat = 24
    static let promptRadius: CGFloat = 40
    static let promptMaxWidth: CGFloat = 1000
    static let infoLabelWidth: CGFloat = 198
    static let infoLabelGap: CGFloat = 12
    static let infoColumnWidth: CGFloat = 554
    static let infoColumnSpacing: CGFloat = 28
    static let infoSectionSpacing: CGFloat = 28
    static let infoRowSpacing: CGFloat = 8
    static let infoHeadingGap: CGFloat = 4
    static let infoHeadingFont = Font.callout.weight(.semibold)
    static let infoLabelFont = Font.caption2
    /// The values' size at the default text size (`PlayerInfoSection` scales it with Dynamic Type).
    static let infoValueSize: CGFloat = 27
    /// The panel's padding across on its Info tab (`PlayerTVPanel`).
    static let infoPanelPadding: CGFloat = 40
    /// The panel on its Info tab: three columns and its padding, 1798 pt, its glass a little past the screen's safe area
    /// (1760 pt across) and its text within it.
    static let infoPanelWidth = infoColumnWidth * 3 + infoColumnSpacing * 2 + infoPanelPadding * 2
    /// The regular glass (`playerGlass`); iOS and iPadOS 26 take the clear one.
    static let clearGlass = false
    #elseif os(macOS)
    static let icon: CGFloat = 15
    static let button: CGFloat = 28
    static let edge: CGFloat = 20
    /// Under the title bar's row: the messages at the top where they don't clear the title on it.
    static let osdTop: CGFloat = 56
    static let cornerInset: CGFloat = 20
    static let overlayGap: CGFloat = 12
    static let timelineSpacing: CGFloat = 10
    static let subtitleGap: CGFloat = 8
    static let titleFont = Font.headline
    static let subtitleFont = Font.subheadline
    static let timeFont = Font.callout.weight(.medium).monospacedDigit()
    static let liveDot: CGFloat = 10
    static let liveDotGap: CGFloat = 8
    /// Play/pause to the live label: the transport's spacing (`ControlBar`).
    static let liveLabelGap: CGFloat = 14
    static let menuSpacing: CGFloat = 0
    /// The options menu's ways to start a watch-together room show without symbols on the Mac, with them on iOS.
    static let menuStartSymbols = false
    /// The transport's buttons (`PlayerTransport`) and their symbols: the episodes', back and forward's, play/pause's.
    static let transportEpisode = button
    static let transportEpisodeIcon = icon
    static let transportSkip = button
    static let transportSkipIcon = icon
    static let transportPlay: CGFloat = 36
    static let transportPlayIcon: CGFloat = 22
    static let cardRadius: CGFloat = 20
    static let cardWidth: CGFloat = 300
    static let cardPadding: CGFloat = 14
    static let cardSpacing: CGFloat = 3
    static let cardButtonSpacing: CGFloat = 8
    static let cardLabelFont = Font.caption.weight(.medium)
    static let cardTitleFont = Font.headline
    static let cardButtonFont = Font.callout.weight(.semibold)
    static let cardButtonInsets = EdgeInsets(top: 6, leading: 18, bottom: 6, trailing: 18)
    static let errorActionSpacing: CGFloat = 10
    static let errorMaxWidth: CGFloat = 560
    static let errorStackedWidth: CGFloat = 280
    static let promptActionSpacing: CGFloat = 10
    static let promptRadius: CGFloat = 22
    static let promptMaxWidth: CGFloat = 560
    static let infoLabelWidth: CGFloat = 112
    static let infoLabelGap: CGFloat = 12
    static let infoColumnWidth: CGFloat = 324
    static let infoColumnSpacing: CGFloat = 24
    static let infoSectionSpacing: CGFloat = 16
    static let infoRowSpacing: CGFloat = 4
    static let infoHeadingGap: CGFloat = 2
    static let infoHeadingFont = Font.headline
    static let infoLabelFont = Font.callout
    static let infoValueFont = Font.callout
    static let infoPadding: CGFloat = 16
    /// Room under the info panel for the control bar, which spans the window's bottom.
    static let infoBottomRoom: CGFloat = 120

    /// The info panel with `columns` columns and its padding.
    static func infoPanelWidth(columns: Int) -> CGFloat {
        CGFloat(columns) * infoColumnWidth + CGFloat(columns - 1) * infoColumnSpacing + infoPadding * 2
    }
    /// The regular glass (`playerGlass`); iOS and iPadOS 26 take the clear one.
    static let clearGlass = false
    #else
    static let icon: CGFloat = 17
    static let button: CGFloat = 44
    static let edge: CGFloat = 16
    /// The controls' distance from the safe area's top and bottom (`TouchControls`); the messages at the top sit as far
    /// down, level with the top bar's buttons, where they fit between them.
    static let barInset: CGFloat = 8
    /// Under the top bar: the messages at the top where they don't fit beside its buttons.
    static let osdTop: CGFloat = 72
    static let cornerInset = barInset
    static let overlayGap: CGFloat = 12
    static let timelineSpacing: CGFloat = 12
    static let subtitleGap: CGFloat = 8
    static let titleFont = Font.headline
    static let subtitleFont = Font.subheadline
    static let timeFont = Font.footnote.weight(.semibold).monospacedDigit()
    static let liveDot: CGFloat = 10
    static let liveDotGap: CGFloat = 8
    static let menuSpacing: CGFloat = 0
    static let menuStartSymbols = true
    static let transportEpisode: CGFloat = 44
    static let transportEpisodeIcon: CGFloat = 20
    static let transportSkip: CGFloat = 56
    static let transportSkipIcon: CGFloat = 24
    static let transportPlay: CGFloat = 72
    static let transportPlayIcon: CGFloat = 32
    static let cardRadius: CGFloat = 22
    static let cardWidth: CGFloat = 300
    static let cardPadding: CGFloat = 16
    static let cardSpacing: CGFloat = 3
    static let cardButtonSpacing: CGFloat = 8
    static let cardLabelFont = Font.caption.weight(.medium)
    static let cardTitleFont = Font.headline
    static let cardButtonFont = Font.subheadline.weight(.semibold)
    static let cardButtonInsets = EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20)
    /// A card's buttons where the card is narrowed beside the centre transport (`CardButtons`).
    static let cardNarrowButtonInsets = EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12)
    static let errorActionSpacing: CGFloat = 10
    /// The error's column: its lines stay short across a landscape iPhone.
    static let errorMaxWidth: CGFloat = 460
    /// The error's buttons one above the other (an upright iPhone), each as wide as the others.
    static let errorStackedWidth: CGFloat = 300
    static let promptActionSpacing: CGFloat = 10
    static let promptRadius: CGFloat = 22
    static let promptMaxWidth: CGFloat = 560
    /// iOS and iPadOS 26 draw the player in clear glass (`playerGlass`); before 26, the thin material.
    static var clearGlass: Bool {
        guard #available(iOS 26, *) else { return false }
        return true
    }
    #endif
}

extension View {
    /// Part of the controls: a pointer resting on it keeps them up (`PointerMove` over the controls), the Mac's and an
    /// iPad's trackpad or mouse. On iPad the pointer leaving it for none of the player's views (a touch hides it) is off
    /// the controls, which then hide on their timer.
    func controlRegion(_ store: PlayerStore) -> some View {
        #if os(macOS)
        onContinuousHover { phase in
            if case .active = phase { _ = store.onInput(event: InputEventPointerMove(overControls: true)) }
        }
        #elseif os(iOS)
        onContinuousHover { phase in
            _ = store.onInput(event: InputEventPointerMove(overControls: phase != .ended))
        }
        #else
        self
        #endif
    }

    /// Liquid Glass in `shape` (iOS, macOS and tvOS 26), else the system's thin material; `interactive` glass reacts
    /// to touch. Where `PlayerMetrics.clearGlass` (iOS and iPadOS 26) it is the clear glass Apple puts over media,
    /// dimmed by 45 % black inside it: the video shows through unblurred, and a white symbol keeps 3:1 over a white
    /// frame. (The clear glass adds a light veil of its own, which the dimming covers: 40 % under the glass left 2.1:1.)
    /// Over an HDR picture (`hdrPicture`) every glass has that black inside, through `ControlsScrim`: the video behind
    /// it can be several times as bright as the white on it. The shape is also the accessibility frame of what the glass
    /// holds: without it, an accessibility container in Liquid Glass with an AppKit slider in it (the Mac's volume)
    /// takes the slider's frame for its own (with two sliders, their union), set inside the glass or around it.
    func playerGlass<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        modifier(PlayerGlass(shape: shape, interactive: interactive))
    }

    /// A text button of the player's panels; `prominent` for the panel's primary action. tvOS: `PlayerTVButtonStyle`'s
    /// capsule platter, which focus turns white; iOS and macOS: glass (26), else the bordered styles, the prominent one
    /// white.
    @ViewBuilder
    func playerActionStyle(prominent: Bool = false) -> some View {
        #if os(tvOS)
        buttonStyle(PlayerTVButtonStyle(shape: Capsule(), prominent: prominent, insets: EdgeInsets(top: 14, leading: 32, bottom: 14, trailing: 32)))
        #else
        if #available(iOS 26, macOS 26, *) {
            if prominent {
                buttonStyle(.glassProminent).tint(Palette.onVideo).foregroundStyle(.black)
            } else {
                buttonStyle(.glass)
            }
        } else if prominent {
            buttonStyle(.borderedProminent).tint(Palette.onVideo).foregroundStyle(.black)
        } else {
            buttonStyle(.bordered)
        }
        #endif
    }
}

extension EnvironmentValues {
    /// The player's picture goes out as HDR (`PlayerUiState.hdrPicture`): what the controls draw behind themselves
    /// goes through `ControlsScrim`.
    @Entry var hdrPicture = false
    /// Apple TV's transport bar is out of focus's way under a menu (`TVControls`): disabled for focus but drawn at full
    /// strength, as AVKit's is; a control disabled on its own account still dims (`playerDisabled`).
    @Entry var playerControlsAside = false
}

extension View {
    /// Disables a player control on its own account (no episode that way, a guest's speed or play/pause): on tvOS it
    /// dims even while the transport bar steps aside under a menu.
    func playerDisabled(_ disabled: Bool) -> some View {
        self.disabled(disabled).transformEnvironment(\.playerControlsAside) { if disabled { $0 = false } }
    }
}

private struct PlayerGlass<S: Shape>: ViewModifier {
    let shape: S
    let interactive: Bool
    @Environment(\.hdrPicture) private var hdr

    func body(content: Content) -> some View {
        let dim = PlayerMetrics.clearGlass || hdr ? Double(ControlsScrim.shared.alpha(alpha: 0.45, hdr: hdr)) : 0
        Group {
            if #available(iOS 26, macOS 26, tvOS 26, *) {
                if PlayerMetrics.clearGlass {
                    content.background(.black.opacity(dim), in: shape)
                        .glassEffect(interactive ? .clear.interactive() : .clear, in: shape)
                } else {
                    content.background(.black.opacity(dim), in: shape)
                        .glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
                }
            } else {
                content.background(.black.opacity(dim), in: shape)
                    .background(.ultraThinMaterial, in: shape)
            }
        }
        .contentShape(.accessibility, shape)
    }
}

extension View {
    /// The controls' muted text over the video (the episode line, why the controls are off): secondary,
    /// and primary over an HDR picture (`ControlsScrim.textMuted`).
    func playerMuted() -> some View {
        modifier(PlayerMutedText())
    }
}

private struct PlayerMutedText: ViewModifier {
    @Environment(\.hdrPicture) private var hdr

    func body(content: Content) -> some View {
        content.foregroundStyle(ControlsScrim.shared.textMuted(hdr: hdr) ? .secondary : .primary)
    }
}

/// Darkens the video behind the controls, so white glyphs and text read over bright video: `ControlsScrim`'s gradient
/// over the whole player, top to bottom, on iOS and macOS; on tvOS AVKit's (measured 21 Oct): clear down to 35 % of
/// the height, then straight to 56 % black at 95 % and on to the bottom. Over an HDR picture darker, through
/// `ControlsScrim.alpha`.
struct PlayerScrim: View {
    @Environment(\.hdrPicture) private var hdr

    var body: some View {
        LinearGradient(stops: stops, startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
            .allowsHitTesting(false)
    }

    private var stops: [Gradient.Stop] {
        #if os(tvOS)
        // Enough stops for the HDR curve to follow the ramp.
        let ramp: [(position: CGFloat, alpha: Float)] = [(0, 0), (0.35, 0), (0.5, 0.14), (0.65, 0.28), (0.8, 0.42), (0.95, 0.56), (1, 0.56)]
        return ramp.map { .init(color: .black.opacity(Double(ControlsScrim.shared.alpha(alpha: $0.alpha, hdr: hdr))), location: $0.position) }
        #else
        return ControlsScrim.shared.stops(hdr: hdr).map { .init(color: .black.opacity(Double($0.alpha)), location: CGFloat($0.position)) }
        #endif
    }
}

/// A round player button with an SF Symbol: `label` for accessibility (and `value`, a menu's current choice), the
/// Compose player's probe name as its identifier. No background of its own: it sits in a glass group, or the caller
/// gives it its own glass. `selected` marks an open panel; `busy` puts a spinner in place of the symbol (the button
/// still acts). tvOS: `PlayerTVButtonStyle`'s round platter, which focus turns white.
struct PlayerIconButton: View {
    let systemImage: String
    let label: String
    var value: String?
    let id: String
    var selected = false
    var size: CGFloat = PlayerMetrics.button
    var iconSize: CGFloat = PlayerMetrics.icon
    var busy = false
    let action: () -> Void

    /// The symbol's share of `iconSize` at the text size: 1 at the default, following Dynamic Type as the body does.
    @ScaledMetric(relativeTo: .body) private var symbolScale: CGFloat = 1

    var body: some View {
        Button(action: action) {
            #if os(tvOS)
            Image(systemName: systemImage)
                .font(.system(size: iconSize, weight: .medium))
                .frame(width: size, height: size)
                .padding(10)
            #else
            Image(systemName: systemImage)
                .font(.system(size: iconSize * symbolScale, weight: .semibold))
                .opacity(busy ? 0 : 1)
                .overlay {
                    if busy { ProgressView().controlSize(.large) }
                }
                // The scale the UIKit menu buttons beside it draw at (`NativeMenuButton`).
                .imageScale(.medium)
                .frame(width: size, height: size)
                .background(Circle().fill(Palette.onVideo.opacity(selected ? 0.22 : 0)))
                .contentShape(Circle())
            #endif
        }
        #if os(tvOS)
        .buttonStyle(PlayerTVButtonStyle(shape: Circle(), selected: selected))
        #else
        .buttonStyle(PlayerPressStyle())
        #endif
        .accessibilityLabel(label)
        #if os(macOS)
        .tooltip(label)
        #endif
        .accessibilityValue(value ?? "")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
        #if os(iOS)
        .accessibilityShowsLargeContentViewer { Label(label, systemImage: systemImage) }
        #endif
    }
}

#if os(tvOS)
/// The Apple TV's player buttons, as AVKit's transport bar draws them (measured 21 Oct): the system's material under a
/// white symbol (white 60 % while `selected`, the button of the open menu, or `prominent`); under focus the platter
/// turns white with a black symbol and grows from AVKit's 66 to 74 pt, with no shadow; a click shrinks it a little
/// (`tvPressed`). With Increase Contrast the symbol or label on the 60 % white is black: about 7.4:1 over black video,
/// where white reads 2.85:1.
struct PlayerTVButtonStyle<S: Shape>: ButtonStyle {
    let shape: S
    var selected = false
    var prominent = false
    /// Room around a text label inside its platter (a symbol's frame brings its own).
    var insets = EdgeInsets()

    func makeBody(configuration: Configuration) -> some View {
        Face(configuration: configuration, shape: shape, strong: selected || prominent, insets: insets)
    }

    private struct Face: View {
        let configuration: Configuration
        let shape: S
        let strong: Bool
        let insets: EdgeInsets
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled
        @Environment(\.playerControlsAside) private var aside
        @Environment(\.colorSchemeContrast) private var contrast

        var body: some View {
            configuration.label
                .font(.callout.weight(.semibold))
                .lineLimit(1)
                .padding(insets)
                .foregroundStyle(focused || strong && contrast == .increased ? Color.black : Palette.onVideo)
                .background {
                    if focused || strong {
                        shape.fill(focused ? Palette.onVideo : Palette.onVideo.opacity(0.6))
                    } else {
                        shape.fill(.regularMaterial)
                    }
                }
                .contentShape(shape)
                .scaleEffect(focused ? 1.12 : 1)
                .opacity(enabled || aside ? 1 : 0.45)
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
        }
    }
}
#endif

/// Dims a pressed control; the glass under it gives the rest of the feedback.
struct PlayerPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.55 : 1)
    }
}

/// `focused` for a view that may have no focus binding (the episode buttons outside tvOS).
struct OptionalPlayerFocus: ViewModifier {
    let focus: FocusState<PlayerFocus?>.Binding?
    let target: PlayerFocus

    func body(content: Content) -> some View {
        if let focus { content.focused(focus, equals: target) } else { content }
    }
}
