import SwiftUI

/// The least hit target of a control drawn smaller than one, a bare symbol (clear, dismiss, delete): 44 pt square on
/// iOS and iPadOS, 28 on the Mac (HIG Accessibility, Mobility). Apple TV moves focus, not a finger or a pointer: its
/// controls have no hit target, and the modifiers below leave them as they are.
enum HitTarget {
    #if os(macOS)
    static let side: CGFloat = 28
    #else
    static let side: CGFloat = 44
    #endif
}

extension View {
    /// A bare symbol's button (`Button { … } label: { Image(…).hitTargetLabel() }`), taking taps and clicks in a square
    /// of at least `HitTarget.side` centred on its symbol. The symbol keeps its size and place, and the button takes no
    /// more room in its row than the symbol does: the square reaches past it, over the room beside it (a form row's
    /// margins, the search capsule's ends). Its accessibility frame is the square.
    func hitTarget() -> some View {
        #if os(tvOS)
        self
        #else
        HitTargetLayout { self }
        #endif
    }

    /// A `hitTarget` button's label: it fills the square, all of which takes the tap.
    func hitTargetLabel() -> some View {
        #if os(tvOS)
        self
        #else
        frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle())
        #endif
    }
}

#if !os(tvOS)
/// Takes its one view's own size (a text label's lines at the width offered, where its one line is wider), and lays the
/// view out at least `HitTarget.side` square, centred there.
private struct HitTargetLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let view = subviews.first else { return .zero }
        let ideal = view.sizeThatFits(.unspecified)
        guard let width = proposal.width, width < ideal.width else { return ideal }
        return view.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let view = subviews.first else { return }
        view.place(
            at: CGPoint(x: bounds.midX, y: bounds.midY),
            anchor: .center,
            proposal: ProposedViewSize(width: max(bounds.width, HitTarget.side), height: max(bounds.height, HitTarget.side))
        )
    }
}
#endif
