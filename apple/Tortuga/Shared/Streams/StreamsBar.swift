import SwiftUI
import TortugaKit

/// The row over the list (`StreamsUiState.addons`), shown while two or more addons have a chip: All (`streams.bar.all`)
/// and one chip per addon in the groups' order (`streams.bar.addon.<i>`, its name as its label). Each is a capsule, its
/// label in medium weight on one line, a name cut short past `ChipMetrics.maxNameWidth` (`chipName`: a cap that grows
/// with the text); the selected one (`isSelected`) is drawn as the others are, its label semibold after a checkmark,
/// and a chip is as wide in either look, so a pick moves no chip. On iOS and macOS 26 and later the chips are Liquid
/// Glass (`glassCapsule`, as Home's shortcuts on iOS are); before, and on tvOS, they are solid (`SurfaceButtonStyle`)
/// on a grey a step off the sheet (`fill`). On tvOS the sizes are the Android TV row's at 1 pt per px of its 1920 ×
/// 1080 screen (a chip at least 80 pt tall, 36 pt from its edge to its name, 28 pt type, 16 pt apart) and focus turns
/// a chip white with black text; elsewhere they are the app's chips' (`ChipMetrics`: 36 pt tall on iPhone and iPad, 28
/// pt on the Mac; 8 pt apart), a tap or a click picks one and keyboard focus shows the focus ring. Picking a chip
/// leaves focus on it.
///
/// The row scrolls sideways, its first chip at rest where the list's text starts. iOS and macOS: across the whole
/// width, `margin` at both ends. tvOS: the remote scrolls it only when focus moves to a chip out of the column, as it
/// scrolls within the column but draws `reach` past it (in the panel, to the panel's edges, where the chips go out of
/// sight): a chip in that reach counts as out of view, a chip in view stays where it is, and Left back to All puts the
/// row back at rest. There the row is a focus section that Up from the list and Down from Refresh enter on the selected
/// chip (All while none is).
struct StreamsBar: View {
    let addons: [StreamsAddon]
    /// The selected addon's chip (`StreamsUiState.selectedAddon`); nil while All is.
    let selected: String?
    let onSelect: (String?) -> Void
    #if os(tvOS)
    let reach: (leading: CGFloat, trailing: CGFloat)
    let focus: FocusState<StreamsFocus?>.Binding
    #else
    let margin: CGFloat
    #endif

    @Environment(\.formFactor) private var formFactor
    @Environment(\.layoutDirection) private var layoutDirection
    #if os(tvOS)
    /// The name's and the checkmark's sizes, 28 and 22 pt at the default text size, following Dynamic Type.
    @ScaledMetric(relativeTo: .body) private var nameSize: CGFloat = 28
    @ScaledMetric(relativeTo: .body) private var checkSize: CGFloat = 22
    #endif

    var body: some View {
        #if os(tvOS)
        ScrollView(.horizontal) { chips }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .padding(.leading, reach.leading)
            .padding(.trailing, reach.trailing)
            .clipped()
            .padding(.leading, -reach.leading)
            .padding(.trailing, -reach.trailing)
            .focusSection()
            .defaultFocus(focus, entry, priority: .userInitiated)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("streams.bar")
        #else
        // The chips' glass (iOS 26 and later; the Mac's chips each alone), one row of it.
        ScrollView(.horizontal) { chips.glassGroup(spacing: gap) }
            .scrollIndicators(.hidden)
            .contentMargins(.horizontal, margin, for: .scrollContent)
            #if os(iOS)
            // The glass chips' swell under a finger reaches past the row.
            .scrollClipDisabled()
            #endif
            .shelfFocusSection()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("streams.bar")
        #endif
    }

    private var chips: some View {
        HStack(spacing: gap) {
            chip(T.shared.streams.all, selected: selected == nil, id: "streams.bar.all") { onSelect(nil) }
                #if os(tvOS)
                .focused(focus, equals: .all)
                #endif
            ForEach(Array(addons.enumerated()), id: \.element.id) { index, addon in
                chip(addon.name, selected: addon.selected, id: "streams.bar.addon.\(index)") { onSelect(addon.id) }
                    #if os(tvOS)
                    .focused(focus, equals: .addon(addon.id))
                    #endif
            }
        }
    }

    private func chip(_ label: String, selected: Bool, id: String, action: @escaping () -> Void) -> some View {
        let button = Button(action: action) {
            // Both looks laid out, the one not shown hidden: a chip is as wide selected as not (the narrower look
            // centred), so a pick moves no chip, and the label keeps one shape (on tvOS a chip whose label lost a view,
            // its checkmark as another chip was picked, kept focus without accessibility reporting it, 25 Oct).
            ZStack {
                chipText(label, selected: true).opacity(selected ? 1 : 0)
                chipText(label, selected: false).opacity(selected ? 0 : 1)
            }
            .chipName(maxWidth: ChipMetrics.maxNameWidth(formFactor))
            .padding(.horizontal, inset)
            .padding(.vertical, inset / 4.5)
            .frame(minHeight: height)
            .contentShape(Capsule())
        }
        return Group {
            #if os(tvOS)
            button.buttonStyle(SurfaceButtonStyle(fill: fill, radius: height / 2))
            #else
            if #available(iOS 26, macOS 26, *) {
                button.glassCapsule()
            } else {
                button.buttonStyle(SurfaceButtonStyle(fill: fill, radius: height / 2))
            }
            #endif
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    /// A chip's label in one of its looks: the name in medium weight, or, selected, semibold after a checkmark, which
    /// sits on the interface's side of the name whatever the name's script.
    private func chipText(_ label: String, selected: Bool) -> Text {
        guard selected else { return Text(label).font(font).fontWeight(.medium) }
        let name = Text(IsolateKt.isolate(text: label)).font(font).fontWeight(.semibold)
        return Text("\(layoutDirection.lineStart)\(Text(Image(systemName: "checkmark")).font(checkFont)) \(name)")
    }

    #if os(tvOS)
    /// Where focus moving into the row lands: the selected chip, All while none is.
    private var entry: StreamsFocus {
        selected.map { .addon($0) } ?? .all
    }
    #endif

    /// A chip's least height.
    private var height: CGFloat {
        #if os(tvOS)
        80
        #else
        ChipMetrics.height(formFactor)
        #endif
    }

    /// From a chip's edge to its name.
    private var inset: CGFloat {
        #if os(tvOS)
        36
        #else
        ChipMetrics.inset(formFactor)
        #endif
    }

    private var font: Font {
        #if os(tvOS)
        .system(size: nameSize, weight: .medium)
        #else
        ChipMetrics.font(formFactor).weight(.medium)
        #endif
    }

    /// The selected chip's checkmark, bold, a step smaller than the name.
    private var checkFont: Font {
        #if os(tvOS)
        .system(size: checkSize, weight: .bold)
        #else
        .caption.weight(.bold)
        #endif
    }

    private var gap: CGFloat {
        #if os(tvOS)
        16
        #else
        8
        #endif
    }

    /// A solid chip's fill, a step off the sheet behind the row: on the Apple TV panel (`surfaceContainerLow`) and the
    /// Mac's sheet, lighter, `surfaceContainer`; on iOS's sheet, which is `surfaceContainer`'s grey itself,
    /// `surfaceContainerHigh`.
    private var fill: Color {
        #if os(iOS)
        Palette.surfaceContainerHigh
        #else
        Palette.surfaceContainer
        #endif
    }
}
