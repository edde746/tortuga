import Nuke
import NukeUI
import SwiftUI
import TortugaKit

/// The information block: release date, runtime, country, language and awards, then the addon's links by group
/// ("Genres  Drama", "Cast  Tim Robbins, Morgan Freeman, Bob Gunton"), each name a text link
/// (`detail.link.<group>.<link>`) that follows its link: a genre opens Discover, a person Search.
struct DetailInformation: View {
    let header: DetailHeader
    let onOpenLink: (String) -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        let tv = formFactor == .tv
        let facts = header.informationFacts
        let groups = header.linkGroups
        if header.hasInformation {
            VStack(alignment: .leading, spacing: tv ? 14 : 8) {
                Text(header.factsTitle)
                    .font((tv ? Font.title3 : .headline).weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                Grid(alignment: .topLeading, horizontalSpacing: tv ? 24 : 12, verticalSpacing: tv ? 12 : 6) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                        GridRow {
                            Text(fact.label).foregroundStyle(Palette.onSurfaceVariant)
                            Text(fact.value).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    ForEach(Array(groups.enumerated()), id: \.offset) { groupIndex, group in
                        GridRow {
                            Text(group.title).foregroundStyle(Palette.onSurfaceVariant)
                            DetailLinkLine(links: group.links, groupIndex: groupIndex, onOpenLink: onOpenLink)
                        }
                    }
                }
                .font(tv ? .callout : .subheadline)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A group's names as text links, separated as a list ("Tim Robbins, Morgan Freeman") and wrapping like text. On tvOS
/// the separator belongs to the name before it, so the focused name's fill doesn't hide it: the name isolated, so the
/// separator stays on its trailing side whatever the name's script.
private struct DetailLinkLine: View {
    let links: [DetailLink]
    let groupIndex: Int
    let onOpenLink: (String) -> Void

    @Environment(\.formFactor) private var formFactor
    #if os(tvOS)
    @Environment(\.layoutDirection) private var layoutDirection
    @FocusState private var focusedLink: Int?
    #endif

    var body: some View {
        let separator = T.shared.detail.listSeparator.trimmingCharacters(in: .whitespaces)
        FlowLayout(spacing: formFactor == .tv ? 14 : 5) {
            ForEach(Array(links.enumerated()), id: \.offset) { index, link in
                let last = index == links.count - 1
                #if os(tvOS)
                linkButton(link, index: index, text: last ? link.label : layoutDirection.lineStart + IsolateKt.isolate(text: link.label) + separator)
                    .focused($focusedLink, equals: index)
                #else
                HStack(spacing: 0) {
                    linkButton(link, index: index, text: link.label)
                    if !last { Text(separator).accessibilityHidden(true) }
                }
                #endif
            }
        }
        #if os(tvOS)
        // Each group's names one focus group: Down and Up reach the next group's first name from any name, however
        // far along its line.
        .focusSection()
        .defaultFocus($focusedLink, 0, priority: .userInitiated)
        #endif
    }

    private func linkButton(_ link: DetailLink, index: Int, text: String) -> some View {
        Button { onOpenLink(link.id) } label: { Text(text) }
            .buttonStyle(TextLinkButtonStyle())
            .accessibilityLabel(link.label)
            .accessibilityAddTraits(.isLink)
            .accessibilityIdentifier("detail.link.\(groupIndex).\(index)")
    }
}

/// A name (or the IMDb rating) as a text link: the label colour, no fill, no border. iOS and
/// macOS underline it while the pointer is over it, while it is pressed and while it has keyboard focus, and always
/// with Button Shapes on (Show Borders: `accessibilityShowBorders`, which on the Mac before 27 is Increase Contrast),
/// as the system's own text buttons show their shape; tvOS lays the row-focus fill (white, dark text) behind it while it
/// has focus, without growing it, and shrinks it a little while clicked (`tvPressed`).
struct TextLinkButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        TextLink(configuration: configuration)
    }

    private struct TextLink: View {
        let configuration: Configuration

        @Environment(\.isFocused) private var focused
        #if !os(tvOS)
        @Environment(\.accessibilityShowBorders) private var showBorders
        @State private var hovered = false
        #endif

        var body: some View {
            #if os(tvOS)
            configuration.label
                .foregroundStyle(focused ? Palette.onPrimary : Palette.onBackground)
                .background {
                    RoundedRectangle(cornerRadius: Radius.extraSmall, style: .continuous)
                        .fill(focused ? Palette.primary : .clear)
                        .padding(.horizontal, -8)
                        .padding(.vertical, -4)
                }
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
            #else
            configuration.label
                .foregroundStyle(Palette.onBackground)
                .underline(showBorders || hovered || focused || configuration.isPressed)
                .contentShape(Rectangle())
                .onHover { hovered = $0 }
            #endif
        }
    }
}
