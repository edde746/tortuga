import SwiftUI
import TortugaKit

/// A short message over the bottom of the screen, as a capsule: a watch-together room's latest event ("Anna joined"),
/// what came of a download ("Added to Downloads"). `id` is its accessibility identifier.
struct Toast: View {
    let text: String
    let id: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .multilineTextAlignment(.center)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
            .padding(.horizontal, 24)
            .accessibilityIdentifier(id)
    }
}

extension View {
    /// Shows `text` as a `Toast` over the bottom of the view while it is set, and announces it (`announcing`).
    /// `onShown` is the store's dismiss, which clears its message once shown: a few seconds after the toast shows; on
    /// iPhone, iPad and the Mac while VoiceOver runs, not until the user dismisses it (its Dismiss action, or the escape
    /// gesture) or the store replaces it, since a toast gone on a timer is one a VoiceOver user seldom reaches (Apple
    /// TV's VoiceOver follows focus, which a toast never takes). It slides in and out, and fades with Reduce Motion on.
    func toast(_ text: String?, id: String, onShown: @escaping () -> Void) -> some View {
        modifier(ToastHost(text: text, id: id, onShown: onShown))
    }
}

private struct ToastHost: ViewModifier {
    let text: String?
    let id: String
    let onShown: () -> Void

    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The toast waits for its dismiss: VoiceOver runs where its user can move to the toast.
    private var stays: Bool {
        #if os(tvOS)
        false
        #else
        voiceOver
        #endif
    }

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let text {
                    Toast(text: text, id: id)
                        .accessibilityAction(named: T.shared.nav.dismiss, onShown)
                        .accessibilityAction(.escape, onShown)
                        .padding(.bottom, 24)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                        .announcing(text)
                        // The timer's id is the text, so a new text shows for the whole time again; VoiceOver turned
                        // off starts it.
                        .task(id: stays ? nil : text) {
                            guard !stays else { return }
                            try? await Task.sleep(for: .seconds(3))
                            if !Task.isCancelled { onShown() }
                        }
                }
            }
            .animation(.easeOut(duration: 0.2), value: text)
    }
}
