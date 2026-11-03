import SwiftUI
import TortugaKit

/// One option of a `FilterMenu`: its label, its detail where it has one (a catalog's addon), and whether it is
/// selected.
struct FilterOption {
    let label: String
    var detail: String?
    let selected: Bool
}

/// A filter: a capsule showing its chip's text (`FilterChip`: "Genre ⌄" until it narrows what is shown, then
/// "Drama ⌄"; "Movies ⌄") that opens its options. The one filter control of every screen (Discover's type, catalog and
/// filters, Library's view, type and sort, Addons' type), so a row of filters shares one height, font and padding.
/// With a `symbol` (Library's sort) the symbol comes before the text, and a compact header row (`screenHeader` on
/// iPhone) shows the symbol alone; a compact row cuts the text short where it gives the capsule less than its width.
/// VoiceOver and the harness read the chip's spoken text as its label everywhere ("Genre: Drama"), selected while it
/// narrows what is shown. `id` is its accessibility identifier, each option's `<id>.option.<i>`.
///
/// - iOS, macOS: a native menu, where each option shows its detail and a check while selected; on iOS a multi-select
///   filter's menu stays open while options are toggled (macOS menus close on every pick). On the Mac the capsule is
///   Liquid Glass (`glassCapsule`), as the window toolbar's controls are.
/// - tvOS: a capsule button that opens the options in a panel at the screen's trailing edge (`FilterOptionsPanel`),
///   titled with the chip's name. Focus can't move along a row of `Menu`s there (Right from one reaches no other),
///   and a segmented control would switch as focus merely passes over it.
struct FilterMenu: View {
    let chip: FilterChip
    /// The symbol before the text, alone in a compact filter row.
    var symbol: String?
    let options: [FilterOption]
    var multiSelect = false
    let id: String
    /// Where the screen keeps the capsule's focus, under `id` (Discover returns focus to it); nil where none does.
    var focus: FocusState<String?>.Binding?
    let onSelect: (Int) -> Void

    @Environment(\.compactFilters) private var compact
    #if os(tvOS)
    @State private var choosing = false
    #endif

    var body: some View {
        control
            #if os(tvOS)
            .fixedSize()
            #else
            // A compact row narrows the capsule, which cuts its text short.
            .fixedSize(horizontal: false, vertical: true)
            #endif
            .accessibilityLabel(chip.spoken)
            .accessibilityAddTraits(chip.narrowed ? .isSelected : [])
            .accessibilityIdentifier(id)
            .trackingFocus(focus, as: id)
    }

    @ViewBuilder
    private var control: some View {
        #if os(tvOS)
        Button { choosing = true } label: { capsule }
            .pillButtonStyle()
            .trailingPanel(isPresented: $choosing, id: "\(id).panel") {
                FilterOptionsPanel(title: chip.name, options: options, id: id) { index in
                    if !multiSelect { choosing = false }
                    onSelect(index)
                }
            }
        #else
        Menu {
            FilterMenuOptions(options: options, multiSelect: multiSelect, id: id, onSelect: onSelect)
        } label: {
            capsule.roundGlassLabel(.capsule)
        }
        .menuStyle(.button)
        // The capsule draws its own chevron; the Mac would add its indicator beside it.
        .menuIndicator(.hidden)
        .roundGlassControl(.capsule, menu: true)
        #endif
    }

    private var capsule: some View {
        HStack(spacing: 6) {
            if let symbol {
                Image(systemName: symbol)
            }
            if !compact || symbol == nil {
                Text(chip.text).lineLimit(1)
            }
            Image(systemName: "chevron.down")
                .font(.caption.weight(.bold))
                .accessibilityHidden(true)
        }
        #if os(iOS)
        // tvOS keeps the system's button font: the capsule matches the buttons beside it (Addons' Install); on the Mac
        // the capsules take the toolbar's (`glassCapsuleLabel`).
        .font(HeaderMetrics.controlFont)
        #endif
    }
}

#if !os(tvOS)
/// A filter's options in a native menu (`FilterMenu`, the Streams list's sort): each a button with its detail and a
/// check while selected, `<id>.option.<i>`; on iOS a multi-select filter's menu stays open while options are toggled
/// (macOS menus close on every pick).
struct FilterMenuOptions: View {
    let options: [FilterOption]
    var multiSelect = false
    let id: String
    let onSelect: (Int) -> Void

    var body: some View {
        ForEach(Array(options.enumerated()), id: \.offset) { index, option in
            Button {
                onSelect(index)
            } label: {
                if option.selected {
                    Label {
                        optionText(option)
                    } icon: {
                        Image(systemName: "checkmark")
                    }
                } else {
                    optionText(option)
                }
            }
            .accessibilityIdentifier("\(id).option.\(index)")
            #if os(iOS)
            .menuActionDismissBehavior(multiSelect ? .disabled : .automatic)
            #endif
        }
    }

    @ViewBuilder
    private func optionText(_ option: FilterOption) -> some View {
        Text(option.label)
        if let detail = option.detail {
            Text(detail)
        }
    }
}
#endif

#if os(tvOS)
/// The row of filters above a screen's grid on tvOS (Discover, Library; iOS and the Mac: `screenHeader`), starting at
/// the grid's leading edge, `trailing` (Library's Calendar button) at its trailing end: it wraps and is the screen's
/// focus section above the grid, full width so Down from anywhere on the tab bar lands on it; a scroll view there would
/// be the screen's first one, which reaches up under the tab bar, its content with it, and a focus section that tall
/// kept Up from ever leaving the row for the tab bar.
struct FilterRow<Content: View, Trailing: View>: View {
    @ViewBuilder let content: () -> Content
    @ViewBuilder let trailing: () -> Trailing

    @Environment(\.screenSides) private var sides

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            FlowLayout(spacing: 24) { content() }
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        // The capsules' glass, one row of it.
        .glassGroup(spacing: 24)
        .padding(sides.insets)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .shelfFocusSection()
    }
}

extension FilterRow where Trailing == EmptyView {
    init(@ViewBuilder content: @escaping () -> Content) {
        self.init(content: content, trailing: { EmptyView() })
    }
}
#endif

extension EnvironmentValues {
    /// Inside a compact header row (`screenHeader` on a phone): a capsule with a symbol shows the symbol alone.
    @Entry var compactFilters = false
}

#if !os(tvOS)
/// Its children side by side, `spacing` apart, each at its own width while they all fit; past that the narrowest keep
/// their width and the widest share what is left evenly (a capsule then cuts its text short), so the row never runs
/// past the width it is given (Compose's `FittingRow`). Where it `wraps` (the header row on iOS, `HeaderMetrics.wraps`)
/// the children that don't fit go on more lines instead, `spacing` apart, each at its own width up to the row's, so
/// their text stays whole.
struct FittingRow: Layout {
    let spacing: CGFloat
    var wraps = false

    /// A line's children: each one's index and its width on the line.
    private typealias Line = [(index: Int, width: CGFloat)]

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = arrange(subviews, in: proposal.width)
        let width = lines.map { $0.map(\.width).reduce(0, +) + gaps($0.count) }.max() ?? 0
        return CGSize(width: width, height: height(of: lines, subviews))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let lines = arrange(subviews, in: bounds.width)
        // Centred on the bounds' height, as one line is.
        var y = bounds.midY - height(of: lines, subviews) / 2
        for line in lines {
            let lineHeight = height(of: line, subviews)
            var x = bounds.minX
            for (index, width) in line {
                subviews[index].place(at: CGPoint(x: x, y: y + lineHeight / 2), anchor: .leading, proposal: ProposedViewSize(width: width, height: nil))
                x += width + spacing
            }
            y += lineHeight + spacing
        }
    }

    private func gaps(_ count: Int) -> CGFloat {
        spacing * CGFloat(max(count - 1, 0))
    }

    private func height(of line: Line, _ subviews: Subviews) -> CGFloat {
        line.map { subviews[$0.index].sizeThatFits(ProposedViewSize(width: $0.width, height: nil)).height }.max() ?? 0
    }

    private func height(of lines: [Line], _ subviews: Subviews) -> CGFloat {
        lines.map { height(of: $0, subviews) }.reduce(0, +) + gaps(lines.count)
    }

    /// The children's lines within `available` (unlimited when nil or infinite): one line, each child at its own width
    /// while all fit, else the narrowest first at their own and the rest an even share of what is left; where it
    /// `wraps`, a new line wherever the next child doesn't fit, each child at its own width up to `available`.
    private func arrange(_ subviews: Subviews, in available: CGFloat?) -> [Line] {
        guard !subviews.isEmpty else { return [] }
        let wanted = subviews.map { $0.sizeThatFits(.unspecified).width }
        guard let available, available.isFinite else { return [wanted.indices.map { (index: $0, width: wanted[$0]) }] }
        if wraps, wanted.reduce(0, +) + gaps(wanted.count) > available {
            var lines: [Line] = []
            var lineWidth: CGFloat = 0
            for (index, wantedWidth) in wanted.enumerated() {
                let width = min(wantedWidth, available)
                if !lines.isEmpty, lineWidth + spacing + width <= available {
                    lines[lines.count - 1].append((index: index, width: width))
                    lineWidth += spacing + width
                } else {
                    lines.append([(index: index, width: width)])
                    lineWidth = width
                }
            }
            return lines
        }
        var left = max(available - gaps(wanted.count), 0)
        var widths = wanted
        for (done, index) in wanted.indices.sorted(by: { wanted[$0] < wanted[$1] }).enumerated() {
            widths[index] = min(wanted[index], left / CGFloat(wanted.count - done))
            left -= widths[index]
        }
        return [widths.indices.map { (index: $0, width: widths[$0]) }]
    }
}
#endif

private extension View {
    @ViewBuilder
    func trackingFocus(_ focus: FocusState<String?>.Binding?, as id: String) -> some View {
        if let focus {
            focused(focus, equals: id)
        } else {
            self
        }
    }
}

#if os(tvOS)
/// A filter's options on tvOS, in a `TrailingPanel` (`<id>.panel`): titled with what it picks ("Genre", "Sort by"),
/// listing every option in a column that scrolls as focus moves. A row turns white with dark text while focused and
/// doesn't grow; the selected options show a check, an option its detail (a catalog's addon) under its label. Focus
/// starts on the first selected option. Picking runs `onSelect` (which closes the panel, but for a multi-select
/// filter's); the remote's Menu closes it.
struct FilterOptionsPanel: View {
    let title: String
    let options: [FilterOption]
    let id: String
    let onSelect: (Int) -> Void

    @FocusState private var focused: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(title)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            ScrollView {
                VStack(spacing: 4) {
                    ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                        Button { onSelect(index) } label: { row(option) }
                            .buttonStyle(SurfaceButtonStyle(fill: .clear, radius: Radius.medium))
                            .focused($focused, equals: index)
                            .accessibilityAddTraits(option.selected ? .isSelected : [])
                            .accessibilityIdentifier("\(id).option.\(index)")
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .padding(.top, 48)
        .defaultFocus($focused, selectedIndex)
        // The screen under the panel stays focusable: focus is moved into the panel.
        .task { await FocusRestore.restore($focused, to: selectedIndex) }
    }

    private var selectedIndex: Int { options.firstIndex(where: \.selected) ?? 0 }

    private func row(_ option: FilterOption) -> some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(option.label).lineLimit(1)
                if let detail = option.detail {
                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if option.selected {
                Image(systemName: "checkmark").accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
#endif
