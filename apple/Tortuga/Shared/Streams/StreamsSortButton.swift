import SwiftUI
import TortugaKit

#if !os(tvOS)
extension View {
    /// `refresh`, when there is one, as Refresh (`streams.refresh`) at the trailing end of the bar, and `sort`, where the
    /// screen offers one, as the sort button (`StreamsSortButton`, `onSort` taking its options' ids) just before it
    /// (`BarActions`). iOS (the sheet and the screen on its own): level with the title and the episode line it leaves
    /// centred, round glass controls (`SortAndRefresh`; the bordered circle before iOS 26) in one bar item, each with its
    /// own glass alone, without the bar's shared one around them (as two items the bar spaced them so far apart that the
    /// title left the centre). The Mac's screen on its own (its sheet has them in
    /// its heading): the window toolbar's own menu and button, past a flexible space on macOS 26 and later, where the
    /// toolbar's items line up at its leading end.
    @MainActor
    @ViewBuilder
    func actionsInBar(_ refresh: (() -> Void)?, sort: StreamSortMenu?, onSort: @escaping (String) -> Void) -> some View {
        if let refresh {
            modifier(BarActions(refresh: refresh, sort: sort, onSort: onSort))
        } else {
            self
        }
    }
}

/// The bar's sort button and Refresh (`actionsInBar`).
private struct BarActions: ViewModifier {
    let refresh: () -> Void
    let sort: StreamSortMenu?
    let onSort: (String) -> Void

    func body(content: Content) -> some View {
        #if os(iOS)
        let buttons = SortAndRefresh(sort: sort, onSort: onSort, onRefresh: refresh)
        if #available(iOS 26, *) {
            content.toolbar { ToolbarItem(placement: .topBarTrailing) { buttons }.sharedBackgroundVisibility(.hidden) }
        } else {
            content.toolbar { ToolbarItem(placement: .topBarTrailing) { buttons } }
        }
        #else
        let button = Button(action: refresh) { Label(T.shared.streams.refresh, systemImage: "arrow.clockwise") }
            .labelStyle(.iconOnly)
            .help(T.shared.streams.refresh)
            .accessibilityIdentifier("streams.refresh")
        if #available(macOS 26, *) {
            content.toolbar {
                ToolbarSpacer(.flexible)
                if let sort { ToolbarItem { StreamsSortButton(menu: sort, onPick: onSort, inToolbar: true) } }
                ToolbarItem { button }
            }
        } else {
            content.toolbar {
                if let sort { ToolbarItem { StreamsSortButton(menu: sort, onPick: onSort, inToolbar: true) } }
                ToolbarItem { button }
            }
        }
        #endif
    }
}

/// The sort button (where the screen offers one) and Refresh (`IconButton`, `streams.refresh`), 12 pt apart, the sort
/// button as large as Refresh beside it (its size measured: on iOS Refresh's circle is the glass button style's around
/// its symbol): iOS's bar, the Mac sheet's heading.
struct SortAndRefresh: View {
    let sort: StreamSortMenu?
    let onSort: (String) -> Void
    let onRefresh: () -> Void

    @State private var refreshSize: CGSize?

    var body: some View {
        HStack(spacing: 12) {
            if let sort { StreamsSortButton(menu: sort, onPick: onSort, size: refreshSize) }
            IconButton(systemImage: "arrow.clockwise", label: T.shared.streams.refresh, id: "streams.refresh", action: onRefresh)
                .onGeometryChange(for: CGSize.self) { $0.size } action: { refreshSize = $0 }
        }
    }
}
#endif

/// The sort button (`streams.sort`) just before Refresh, as large and level with it: the sort symbol on Refresh's
/// surface, in the primary fill while some key is in effect (`StreamSortMenu.sorted`; to VoiceOver, selected), named
/// by what the list goes by ("Sort by: Resolution", on the Mac its tooltip too). It opens the sort's options, Addon
/// order then each key, the ones in effect checked (`streams.sort.option.<i>`), and `onPick` takes the option's id:
/// - iOS, macOS: a round glass menu (`roundGlassMenu`, `FilterMenuOptions`) of Refresh's `size`, its symbol fitted
///   inside, which on iOS stays open while keys are picked (macOS menus close on every pick). In the Mac's window
///   toolbar (`inToolbar`: the screen on its own) the toolbar's own menu, its symbol the filled circle variant while a
///   key is in effect, as the Mac's toolbars show a filter that is on.
/// - tvOS: Refresh's 80 pt round button (`SurfaceButtonStyle`), its fill in effect a light grey with a black symbol,
///   since focus turns a button white; Select opens the options in a panel at the screen's trailing edge over the list
///   (`FilterOptionsPanel`, `streams.sort.panel`, titled as the menu is: "Sort"), which stays open while keys are
///   picked and the remote's Menu closes before the list's.
struct StreamsSortButton: View {
    let menu: StreamSortMenu
    let onPick: (String) -> Void
    #if os(macOS)
    var inToolbar = false
    #endif
    #if !os(tvOS)
    /// Refresh's size beside it, once measured (`BarActions`, `SortAndRefresh`); nil in the Mac's toolbar.
    var size: CGSize?
    #endif
    #if os(tvOS)
    let focus: FocusState<StreamsFocus?>.Binding
    @State private var choosing = false
    #endif

    var body: some View {
        control
            .accessibilityLabel(menu.spoken)
            .accessibilityAddTraits(menu.sorted ? .isSelected : [])
            .accessibilityIdentifier("streams.sort")
    }

    private var options: [FilterOption] { menu.options.map { FilterOption(label: $0.label, selected: $0.selected) } }

    private func pick(_ index: Int) { onPick(menu.options[index].id) }

    private static let symbol = "arrow.up.arrow.down"

    @ViewBuilder
    private var control: some View {
        #if os(tvOS)
        Button { choosing = true } label: { TVSortSymbol(filled: menu.sorted) }
            .buttonStyle(SurfaceButtonStyle(fill: menu.sorted ? Palette.onSurfaceVariant : Palette.surfaceContainer, radius: 40))
            .focused(focus, equals: .sort)
            .trailingPanel(isPresented: $choosing, id: "streams.sort.panel") {
                FilterOptionsPanel(title: menu.title, options: options, id: "streams.sort", onSelect: pick)
            }
        #else
        #if os(macOS)
        if inToolbar {
            Menu {
                FilterMenuOptions(options: options, multiSelect: true, id: "streams.sort", onSelect: pick)
            } label: {
                Label(menu.spoken, systemImage: menu.sorted ? "arrow.up.arrow.down.circle.fill" : Self.symbol)
            }
            .labelStyle(.iconOnly)
            .menuIndicator(.hidden)
            .help(menu.spoken)
        } else {
            round
        }
        #else
        round
        #endif
        #endif
    }

    #if !os(tvOS)
    /// A glass circle of Refresh's size, its symbol fitted inside at Refresh's weight with the room the Mac's glass
    /// circles keep around their symbols (`IconButton.circleInset`); at its own size until Refresh's is known.
    private var round: some View {
        Menu {
            FilterMenuOptions(options: options, multiSelect: true, id: "streams.sort", onSelect: pick)
        } label: {
            symbol.contentShape(Circle())
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .roundGlassMenu(filled: menu.sorted)
        #if os(macOS)
        .tooltip(menu.spoken)
        #endif
    }

    @ViewBuilder
    private var symbol: some View {
        if let size {
            Image(systemName: Self.symbol)
                .resizable()
                .scaledToFit()
                .fontWeight(.semibold)
                .padding(Self.inset)
                .frame(width: size.width, height: size.height)
        } else {
            Image(systemName: Self.symbol)
                .font(.body.weight(.semibold))
                .padding(Self.inset)
        }
    }

    /// The room around the symbol in the circle: the Mac's glass circles' (`IconButton.circleInset`), iOS's alike.
    private static let inset: CGFloat = 6
    #endif
}

#if os(tvOS)
/// The sort symbol in Refresh's 80 pt circle at 40 pt, black on the button's light fill while a key is in effect and
/// on its white while it has focus, else white.
private struct TVSortSymbol: View {
    let filled: Bool

    @Environment(\.isFocused) private var focused

    var body: some View {
        Image(systemName: "arrow.up.arrow.down")
            .resizable()
            .scaledToFit()
            .frame(width: 40, height: 40)
            .frame(width: 80, height: 80)
            .foregroundStyle(filled || focused ? Palette.onPrimary : Palette.onBackground)
            .contentShape(Circle())
    }
}
#endif

#if !os(iOS)
extension View {
    /// Beside the heading in an `HStack(alignment: .firstTextBaseline)`: its middle level with the middle of the
    /// capitals of the heading's first line, in the title's font (`.title3`). Refresh in the Mac sheet's heading and in
    /// the Apple TV panel's.
    func levelWithTitle() -> some View {
        #if os(tvOS)
        let capHeight = UIFont.preferredFont(forTextStyle: .title3).capHeight
        #else
        let capHeight = NSFont.preferredFont(forTextStyle: .title3).capHeight
        #endif
        return alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + capHeight / 2 }
    }
}
#endif
