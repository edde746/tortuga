import SwiftUI

extension EnvironmentValues {
    /// The side iOS 27.1's vertical bar shows at beside the view, nil without one: the system places one in its window
    /// (`verticalBarEnvironment`, the iPhone Duo's screens) and its presentation keeps it (`verticalBarDisabled`). A
    /// navigation bar's items go in the bar's column there, with the tab bar, which folds the tabs into the selected one
    /// when they don't all fit (25 Oct: Addons' Install, on the cover turned), so a screen's actions go in its header row
    /// (`screenHeader`); and a page's content starts at that side's safe area, without the screen padding
    /// (`screenSides`).
    @Entry var verticalBarEdge: HorizontalEdge?
}

extension View {
    /// While `disabled`, the presentation this view is in (a sheet, a cover; else its window) has no vertical bar, as
    /// where nothing of it goes in the bar: SwiftUI's `toolbarVerticalBehavior`, which the presentation or window
    /// resolves. iOS 27.1 puts the status bar, the back button and the tab bar in a column along one side of the iPhone
    /// Duo's screens and adds the column's width to that side's safe area alone: under a sheet's navigation bar too, and
    /// in a cover that hides the status bar, which left their content off-centre beside an empty strip. Without the bar
    /// the sides' safe areas are the same and the status bar runs along the top, as on an iPhone. For the player's
    /// cover, sheets (`sheetFrame`) and the screens before the app's shell (the splash, sign-in, the profiles), whose
    /// column holds the status bar alone.
    @ViewBuilder
    func verticalBarDisabled(_ disabled: Bool = true) -> some View {
        #if os(iOS)
        if #available(iOS 27.1, *) {
            toolbarVerticalBehavior(disabled ? .disabled : .automatic)
                .transformEnvironment(\.verticalBarEdge) { if disabled { $0 = nil } }
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// Sets `verticalBarEdge` for everything below from the window's traits (`UITraitCollection.verticalBarEdge`: set
    /// where the system places the bar, whether or not a presentation turns it off), kept as they change (a fold).
    @ViewBuilder
    func verticalBarEnvironment() -> some View {
        #if os(iOS)
        if #available(iOS 27.1, *) {
            modifier(VerticalBarEnvironment())
        } else {
            self
        }
        #else
        self
        #endif
    }
}

#if os(iOS)
@available(iOS 27.1, *)
private struct VerticalBarEnvironment: ViewModifier {
    @State private var edge: HorizontalEdge?

    func body(content: Content) -> some View {
        content
            .environment(\.verticalBarEdge, edge)
            .background { VerticalBarEdge(edge: $edge) }
    }
}

/// Reports the side the system places the vertical bar at in the window it is in, nil where it places none.
@available(iOS 27.1, *)
private struct VerticalBarEdge: UIViewRepresentable {
    @Binding var edge: HorizontalEdge?

    func makeUIView(context: Context) -> EdgeView { EdgeView() }

    func updateUIView(_ view: EdgeView, context: Context) {
        view.report = { edge = $0 }
        view.update()
    }

    final class EdgeView: UIView {
        var report: ((HorizontalEdge?) -> Void)?
        private var reported: HorizontalEdge??

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            registerForTraitChanges(UITraitCollection.systemTraitsAffectingVerticalBarEdge) { (view: EdgeView, _: UITraitCollection) in
                view.update()
            }
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            update()
        }

        func update() {
            guard window != nil else { return }
            // The trait's sides are the layout direction's, as SwiftUI's are.
            let edge: HorizontalEdge? = switch traitCollection.verticalBarEdge {
            case .leading: .leading
            case .trailing: .trailing
            default: nil
            }
            guard reported != .some(edge), let report else { return }
            reported = edge
            // After the update under way: SwiftUI's state changes in the next one.
            DispatchQueue.main.async { report(edge) }
        }
    }
}
#endif
