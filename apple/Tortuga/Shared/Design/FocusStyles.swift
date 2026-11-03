import SwiftUI

/// Focus and press styles. tvOS moves focus with the remote, so a focused control must stand out from across a room:
/// it lifts and turns white, as the system's own rows do. On iOS and macOS focus comes from a keyboard (iPad keyboards,
/// macOS keyboard navigation) and shows as a white ring hugging the control; touch and clicks dim a pressed control.

/// A button drawn as a filled rounded surface: stream rows, choice rows, panels that act as one button, chips.
///
/// - tvOS: focused, the surface turns white with black content; it doesn't grow or lift (a row in a list, not a card).
///   A click shrinks it while Select is down (`tvPressed`).
/// - iOS, macOS: the surface dims while pressed and shows the focus ring while it has keyboard focus.
struct SurfaceButtonStyle: ButtonStyle {
    var fill: Color = Palette.surfaceContainer
    var radius: CGFloat = Radius.large

    func makeBody(configuration: Configuration) -> some View {
        SurfaceButton(configuration: configuration, fill: fill, radius: radius)
    }

    private struct SurfaceButton: View {
        let configuration: Configuration
        let fill: Color
        let radius: CGFloat
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
            #if os(tvOS)
            configuration.label
                .foregroundStyle(focused ? Palette.onPrimary : Palette.onBackground)
                .environment(\.onFocusedSurface, focused)
                .background(focused ? Palette.primary : fill, in: shape)
                .opacity(enabled ? 1 : 0.5)
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
            #else
            configuration.label
                .background(fill, in: shape)
                .contentShape(shape)
                .opacity(configuration.isPressed ? 0.7 : enabled ? 1 : 0.5)
                .focusRing(radius: radius)
            #endif
        }
    }
}

extension EnvironmentValues {
    /// The content sits on a focused tvOS surface, which is white (`SurfaceButtonStyle`): colours chosen for the black
    /// background (badges) turn around.
    @Entry var onFocusedSurface = false
}

/// A poster or tile that is a button: the tvOS card style (lift, parallax highlight) on tvOS; elsewhere the artwork
/// dims while pressed and shows the focus ring on keyboard focus.
struct CardButtonStyleModifier: ViewModifier {
    func body(content: Content) -> some View {
        #if os(tvOS)
        content.buttonStyle(.card)
        #else
        content.buttonStyle(PressDimButtonStyle())
        #endif
    }
}

/// Dims the label while pressed; nothing else. Focus rings are drawn by the label where it knows its shape.
struct PressDimButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.7 : 1)
    }
}

extension View {
    /// Poster and tile buttons: see `CardButtonStyleModifier`.
    func cardButtonStyle() -> some View {
        modifier(CardButtonStyleModifier())
    }

    /// A white ring around this view while it (or the button it is the label of) has keyboard focus (iOS, macOS).
    /// tvOS styles draw focus themselves, so there it does nothing.
    func focusRing(radius: CGFloat) -> some View {
        modifier(FocusRing(radius: radius))
    }
}

#if os(tvOS)
extension View {
    /// The app's own tvOS button styles' answer to a click, as the system's buttons give one: while Select is down on
    /// the focused control it shrinks to 96 % of the size its focus look gives it, and comes back as Select is let go.
    func tvPressed(_ pressed: Bool) -> some View {
        scaleEffect(pressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.1), value: pressed)
    }
}
#endif

private struct FocusRing: ViewModifier {
    let radius: CGFloat
    @Environment(\.isFocused) private var focused

    func body(content: Content) -> some View {
        #if os(tvOS)
        content
        #else
        // Only while focused: an invisible ring still widened the control's accessibility frame by its outset.
        content.overlay {
            if focused {
                RoundedRectangle(cornerRadius: radius + 3, style: .continuous)
                    .strokeBorder(Palette.focusRing, lineWidth: 2)
                    .padding(-3)
            }
        }
        #endif
    }
}
