import SwiftUI
import TortugaKit

extension View {
    /// A modal form: a sheet on iOS and macOS (`size`: half height and pulled up on iPhone, or full height; on the Mac
    /// as tall as its form, `sheetFrame`); on Apple TV the whole screen (its sheets are cards that leave long forms
    /// below the remote's reach). It goes through `statePresentation`: closed by the user (a swipe, Escape, the
    /// remote's Menu), it stays closed while the state that holds it open follows.
    func settingsSheet<Content: View>(
        isPresented: Binding<Bool>,
        size: SheetSize = .form,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        statePresentation(isPresented) { base, shown in
            #if os(tvOS)
            base.fullScreenCover(isPresented: shown) {
                content().background(Palette.background)
            }
            #else
            base.sheet(isPresented: shown) {
                content().sheetFrame(size)
            }
            #endif
        }
    }

    /// `settingsSheet(isPresented:)` of a store's state: up while `item` is set, drawn by `content`, `onDismiss` when
    /// the user closes it. Once `item` goes nil (closed, saved, cancelled) the sheet draws the last `item` it had while
    /// it animates out, not an empty sheet.
    func settingsSheet<Item: Equatable, Content: View>(
        item: Item?,
        size: SheetSize = .form,
        onDismiss: @escaping () -> Void,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        modifier(LastItemSheet(item: item, size: size, onDismiss: onDismiss, sheet: content))
    }
}

private struct LastItemSheet<Item: Equatable, Sheet: View>: ViewModifier {
    let item: Item?
    let size: SheetSize
    let onDismiss: () -> Void
    let sheet: (Item) -> Sheet

    /// The item last shown, drawn while the sheet animates out after `item` went nil.
    @State private var shown: Item?

    func body(content: Content) -> some View {
        content
            .settingsSheet(isPresented: Binding(get: { item != nil }, set: { if !$0 { onDismiss() } }), size: size) {
                if let drawn = item ?? shown { sheet(drawn) }
            }
            .onChange(of: item, initial: true) { _, item in
                if let item { shown = item }
            }
    }
}

#if os(macOS)
/// A settings sheet's form as tall as its content, up to `SheetSize.formMaxHeight`, past which it scrolls (`active`:
/// macOS 15 and later, `formSetsSheetHeight`), so the sheet's buttons sit right under its last row. A Mac sheet takes
/// its content's ideal height, which a form doesn't have: at a fixed height the Install sheet's preview ended 250 pt
/// above Cancel and Install. The sheet adds its title bar to that height and lays the form out under it (macOS 26),
/// the bar in the form's safe area: the height asked for leaves the safe area out (or it showed again under the last
/// row), and the form fills the sheet, the safe area with it.
struct SheetHeightFromForm: ViewModifier {
    let active: Bool

    /// The form's content with its insets, the safe area's among them: the height that shows it all.
    @State private var height: CGFloat?
    @State private var safeArea = EdgeInsets()

    func body(content: Content) -> some View {
        if active, #available(macOS 15, *) {
            let bars = safeArea.top + safeArea.bottom
            let fit = height.map { min(($0 - bars).rounded(.up), SheetSize.formMaxHeight) }
            content
                .onScrollGeometryChange(for: CGFloat.self) { geometry in
                    geometry.contentSize.height + geometry.contentInsets.top + geometry.contentInsets.bottom
                } action: { _, contentHeight in
                    height = contentHeight
                }
                .onGeometryChange(for: EdgeInsets.self) { $0.safeAreaInsets } action: { safeArea = $0 }
                .frame(minHeight: fit, idealHeight: fit, maxHeight: fit.map { $0 + bars })
        } else {
            content
        }
    }
}
#endif

extension EnvironmentValues {
    /// Inside a sized sheet (`sheetFrame`: iOS, macOS) or a `TrailingPanel` (tvOS).
    @Entry var isInSheet = false
    /// The sheet around has the grabber (`sheetFrame`): resizable in a compact window (an iPhone), where it closes by a
    /// swipe down and shows no Close (`sheetToolbar(close:)`).
    @Entry var sheetHasGrabber = false
    #if os(macOS)
    /// Inside a settings form's sheet on macOS 15 and later (`sheetFrame`): the form sets the sheet's height
    /// (`SheetHeightFromForm`).
    @Entry var formSetsSheetHeight = false
    #endif
}

/// A modal form's actions on tvOS, whose navigation bar has no buttons: a section of buttons, the main action first
/// (focused first). iOS and macOS put the same actions in the sheet's toolbar.
struct SheetAction {
    let title: String
    let id: String
    var role: ButtonRole?
    var enabled = true
    let action: () -> Void
}

extension View {
    /// The sheet's `cancel` and `confirm` actions: in the toolbar (iOS, macOS) or as the form's last buttons (tvOS,
    /// `SheetActionsSection`, which the form draws itself). Escape presses `cancel`, with or without focus in the
    /// sheet, unless it destroys something (a Reject); Return presses `confirm` on the Mac.
    func sheetToolbar(cancel: SheetAction?, confirm: SheetAction?) -> some View {
        #if os(tvOS)
        self
        #else
        toolbar {
            if let cancel {
                ToolbarItem(placement: .cancellationAction) {
                    Button(cancel.title, role: cancel.role, action: cancel.action)
                        .disabled(!cancel.enabled)
                        .keyboardShortcut(cancel.role == .destructive ? nil : .cancelAction)
                        .accessibilityIdentifier(cancel.id)
                }
            }
            if let confirm {
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirm.title, role: confirm.role, action: confirm.action)
                        .disabled(!confirm.enabled)
                        .accessibilityIdentifier(confirm.id)
                }
            }
        }
        #endif
    }

    /// A sheet whose one action, `close`, loses nothing: `SheetCloseButton` in the bar's cancellation slot (iOS,
    /// macOS; Escape presses it, Return doesn't), none where the sheet has the grabber (`sheetHasGrabber`: an iPhone's
    /// resizable sheet, which a pull down closes). tvOS draws `close` in the form (`SheetActionsSection`).
    func sheetToolbar(close: SheetAction) -> some View {
        #if os(tvOS)
        self
        #else
        modifier(CloseToolbar(close: close))
        #endif
    }
}

#if !os(tvOS)
private struct CloseToolbar: ViewModifier {
    let close: SheetAction

    @Environment(\.sheetHasGrabber) private var grabber

    func body(content: Content) -> some View {
        content.toolbar {
            if !grabber {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton(title: close.title, id: close.id, action: close.action)
                }
            }
        }
    }
}
#endif

/// tvOS: a sheet's actions as the form's buttons, the main action first; elsewhere nothing (they are in the toolbar).
struct SheetActionsSection: View {
    let actions: [SheetAction]

    var body: some View {
        #if os(tvOS)
        if !actions.isEmpty {
            Section {
                ForEach(Array(actions.enumerated()), id: \.offset) { _, action in
                    Button(action.title, role: action.role, action: action.action)
                        .disabled(!action.enabled)
                        .accessibilityIdentifier(action.id)
                }
            }
        }
        #endif
    }
}

/// A sheet's size, one row per kind of sheet (`sheetFrame`): on the Mac the least size and the size it opens at (a Mac
/// sheet takes its content's ideal size, which a form or a list doesn't have, and would start small), but for a
/// settings form's height on macOS 15 and later, which its form sets (`SheetHeightFromForm`); on iOS the heights it
/// takes.
enum SheetSize {
    /// A settings form (`settingsSheet`): half height on iPhone, pulled up to full.
    case form
    /// A settings form that must show whole (a code and its QR code, an addon's details): full height.
    case fullHeightForm
    /// A settings form whose list a search field filters (the languages): as `form`, but on the Mac at
    /// `formMaxHeight` from the start, so the sheet keeps its height while the list narrows and grows.
    case searchedForm
    /// An account page over the screens (`AppShell`'s overlay): on the Mac tall enough for the profile editor whole.
    case page
    /// Streams (`StreamsSheet`): in a compact window (an iPhone, the iPhone Duo's cover) half height, pulled up to
    /// full; in a regular one (iPad, the Duo's inner screen) full height.
    case streams
    /// Over the player: the watch-together room, and on iOS the playback info; half height, pulled up to full.
    case player
    /// Sign-in's own server: one field, half height.
    case server

    #if os(macOS)
    /// The most a settings sheet's form takes on macOS 15 and later (`SheetHeightFromForm`): the height every one
    /// opened at before, which a long form (the languages) scrolls in.
    static let formMaxHeight: CGFloat = 560

    fileprivate var frame: (minWidth: CGFloat, idealWidth: CGFloat?, minHeight: CGFloat, idealHeight: CGFloat?) {
        switch self {
        case .form, .fullHeightForm, .searchedForm: (520, 560, 440, 560)
        case .page: (520, 560, 640, 680)
        case .streams: (520, 600, 440, 620)
        case .player: (460, 520, 520, 640)
        case .server: (460, nil, 200, nil)
        }
    }

    /// Whether the sheet's form sets its height (`formSetsSheetHeight`): a settings form's on macOS 15 and later,
    /// which reads its content's height (`onScrollGeometryChange`); macOS 14 opens it at `frame`'s.
    fileprivate var formSetsHeight: Bool {
        guard self == .form || self == .fullHeightForm else { return false }
        if #available(macOS 15, *) { return true }
        return false
    }
    #elseif os(iOS)
    @MainActor fileprivate func detents(compact: Bool) -> Set<PresentationDetent> {
        switch self {
        case .form, .searchedForm, .player: [.medium, .large]
        case .fullHeightForm, .page: [.large]
        case .streams: compact ? [.medium, .large] : [.large]
        case .server: [.medium]
        }
    }
    #endif
}

extension View {
    /// The sheet at `size` (`SheetSize`), its content told it is in a sheet (`isInSheet`); on iOS for the presenting
    /// window's form factor and without the vertical bar (`verticalBarDisabled`), resizable ones in a compact window
    /// with the grabber (`sheetHasGrabber`); on Apple TV, whose forms cover the screen or show in a `TrailingPanel`, the
    /// view as it is.
    func sheetFrame(_ size: SheetSize) -> some View {
        #if os(macOS)
        let sizes = size.frame
        let formSetsHeight = size.formSetsHeight
        return frame(
            minWidth: sizes.minWidth,
            idealWidth: sizes.idealWidth,
            minHeight: formSetsHeight ? nil : sizes.minHeight,
            idealHeight: formSetsHeight ? nil : sizes.idealHeight
        )
        .environment(\.isInSheet, true)
        .environment(\.formSetsSheetHeight, formSetsHeight)
        #elseif os(iOS)
        return modifier(PhoneSheetFrame(size: size))
        #else
        return self
        #endif
    }
}

#if os(iOS)
private struct PhoneSheetFrame: ViewModifier {
    let size: SheetSize

    @Environment(\.formFactor) private var formFactor

    func body(content: Content) -> some View {
        let detents = size.detents(compact: formFactor == .compact)
        let grabber = formFactor == .compact && detents.count > 1
        content
            .verticalBarDisabled()
            .presentationDetents(detents)
            .presentationDragIndicator(grabber ? .visible : .hidden)
            .environment(\.isInSheet, true)
            .environment(\.sheetHasGrabber, grabber)
    }
}
#endif

#if os(iOS)
extension View {
    /// A form sheet on iPad (iOS 18 and later); iPhone keeps its detents. The Streams sheet's and Detail's rating
    /// picker's.
    @ViewBuilder
    func formSheetSizing() -> some View {
        if #available(iOS 18, *) {
            presentationSizing(.form)
        } else {
            self
        }
    }
}
#endif
