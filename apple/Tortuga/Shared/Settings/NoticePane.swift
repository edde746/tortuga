import SwiftUI
import TortugaKit

/// A third-party component's page over About (`NoticePage`; its title names the component, Back closes it): its licence
/// and a link to its project's page in a group as About's own, then each of its licence files, the file's path as the
/// group's header over its paragraphs (on the page's background; on the Mac in the group's fill, as the Mac's form draws
/// its rows), nothing while they are read. The text can be selected on iOS and macOS. Apple TV has nothing to focus in
/// the text: Down scrolls it by half a screen and Up back, focus staying on the link (`RemoteScroll`; the scroll view
/// gets no move command of its own, so the page handles it).
///
/// On iOS the page is a settings form, whose rows are made as they scroll in. On Apple TV and the Mac a form makes every
/// row up front, each an accessibility element, and mpv-build's texts are some 11,000 paragraphs: VoiceOver and UI
/// tests waited on the page. There it is a lazy stack drawn as the settings form.
///
/// Accessibility identifiers: `settings.notice.link`, `settings.notice.text` (the first licence file's header), then
/// `settings.notice.text.<file>`.
struct NoticePane: View {
    let page: NoticePage
    let store: SettingsStore

    #if os(tvOS)
    @State private var scroll = RemoteScroll()
    #endif

    var body: some View {
        #if os(iOS)
        Form {
            Section {
                Text(page.license)
                    .font(.headline)
                SettingsLinkRow(
                    title: page.linkLabel,
                    systemImage: "safari",
                    trailingSymbol: "arrow.up.forward",
                    id: "settings.notice.link"
                ) {
                    store.openNotice(index: page.index)
                }
                .accessibilityAddTraits(.isLink)
            }
            ForEach(Array((page.texts ?? []).enumerated()), id: \.offset) { file, text in
                Section {
                    ForEach(text.paragraphs.indices, id: \.self) { index in
                        Text(text.paragraphs[index])
                            .font(.body)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                } header: {
                    Text(text.path)
                        .accessibilityIdentifier(file == 0 ? "settings.notice.text" : "settings.notice.text.\(file)")
                }
            }
        }
        .settingsForm()
        #else
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    heading
                        .id("top")
                    ForEach(Array((page.texts ?? []).enumerated()), id: \.offset) { file, text in
                        Text(text.path)
                            #if os(tvOS)
                            .textCase(.uppercase)
                            .foregroundStyle(.tertiary)
                            #else
                            .font(.headline)
                            #endif
                            .padding(.horizontal, Self.rowInset)
                            .padding(.top, Self.headerTop)
                            .padding(.bottom, Self.headerBottom)
                            .accessibilityAddTraits(.isHeader)
                            .accessibilityIdentifier(file == 0 ? "settings.notice.text" : "settings.notice.text.\(file)")
                        // Each row identified by its id for `scrollTo`: a lazy stack scrolls to its rows by their `ForEach` identity.
                        ForEach(text.paragraphs.indices.map { (id: "\(file):\($0)", index: $0) }, id: \.id) { row in
                            Text(text.paragraphs[row.index])
                                .font(.body)
                                .fixedSize(horizontal: false, vertical: true)
                                #if os(macOS)
                                .textSelection(.enabled)
                                #endif
                                .padding(Self.paragraphPadding)
                                .frame(maxWidth: .infinity, minHeight: Self.paragraphHeight, alignment: .leading)
                                #if os(tvOS)
                                .modifier(scroll.tracking(row.id, order: [file, row.index]))
                                #else
                                .background(Self.groupFill, in: Self.rowShape(first: row.index == 0, last: row.index == text.paragraphs.count - 1))
                                .overlay(alignment: .top) {
                                    if row.index > 0 { hairline }
                                }
                                #endif
                        }
                    }
                }
                .padding(Self.margins)
            }
            #if os(tvOS)
            .scrollClipDisabled()
            #endif
            .readableColumn()
            .background(Palette.background)
            #if os(tvOS)
            .onMoveCommand { direction in
                if let row = scroll.target(direction) { withAnimation { proxy.scrollTo(row, anchor: .top) } }
            }
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { scroll.viewport = $0 }
            #endif
        }
        #endif
    }

    #if os(tvOS)
    /// The licence and the link as the form's rows: two platters, the focused link white.
    private var heading: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(page.license)
                .font(.headline)
                .padding(.horizontal, Self.rowInset)
                .frame(maxWidth: .infinity, minHeight: Self.rowHeight, alignment: .leading)
                .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
            Button {
                store.openNotice(index: page.index)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "safari")
                        .frame(width: 48)
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    Text(page.linkLabel)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "arrow.up.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, Self.rowInset)
                .frame(minHeight: Self.rowHeight)
            }
            .buttonStyle(SurfaceButtonStyle(radius: Radius.large))
            .accessibilityIdentifier("settings.notice.link")
            .accessibilityAddTraits(.isLink)
        }
    }

    /// The settings form's measures on Apple TV: the space around its groups, a row's height and its content's inset
    /// from the platter's edges, the space above and under a group's header, and a row of text's padding and least
    /// height.
    private static let margins = EdgeInsets(top: 35, leading: 0, bottom: 35, trailing: 0)
    private static let rowHeight: CGFloat = 66
    private static let rowInset: CGFloat = 20
    private static let headerTop: CGFloat = 27
    private static let headerBottom: CGFloat = 0
    private static let paragraphPadding = EdgeInsets(top: 13, leading: rowInset, bottom: 13, trailing: rowInset)
    private static let paragraphHeight: CGFloat = 70
    #else
    /// The licence and the link as the form's rows: one group, a hairline between them.
    private var heading: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(page.license)
                .font(.headline)
                .padding(.horizontal, Self.rowInset)
                .frame(maxWidth: .infinity, minHeight: Self.rowHeight, alignment: .leading)
            hairline
            SettingsLinkRow(
                title: page.linkLabel,
                systemImage: "safari",
                trailingSymbol: "arrow.up.forward",
                id: "settings.notice.link"
            ) {
                store.openNotice(index: page.index)
            }
            .accessibilityAddTraits(.isLink)
            .padding(.horizontal, Self.rowInset)
            .frame(minHeight: Self.rowHeight)
        }
        .background(Self.groupFill, in: RoundedRectangle(cornerRadius: Radius.small, style: .continuous))
    }

    /// The line between two rows of a group.
    private var hairline: some View {
        Rectangle()
            .fill(Self.separator)
            .frame(height: 1)
            .padding(.horizontal, Self.rowInset)
    }

    /// A row of a group: the group's rounded corners on its first row's top and its last row's bottom.
    private static func rowShape(first: Bool, last: Bool) -> UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: first ? Radius.small : 0,
            bottomLeadingRadius: last ? Radius.small : 0,
            bottomTrailingRadius: last ? Radius.small : 0,
            topTrailingRadius: first ? Radius.small : 0,
            style: .continuous
        )
    }

    /// The Mac's grouped form on the black page: its group's fill and hairline, the space around its groups, a row's
    /// height and its content's inset from the group's edges, the space above and under a group's header, and a row of
    /// text's padding and least height.
    private static let groupFill = Color.white.opacity(0.03)
    private static let separator = Color.white.opacity(0.05)
    private static let margins = EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20)
    private static let rowHeight: CGFloat = 36
    private static let rowInset: CGFloat = 10
    private static let headerTop: CGFloat = 30
    private static let headerBottom: CGFloat = 10
    private static let paragraphPadding = EdgeInsets(top: 11, leading: rowInset, bottom: 10, trailing: rowInset)
    private static let paragraphHeight: CGFloat = 36
    #endif
}

#if os(tvOS)
/// Where the remote's Down and Up take a page whose text has nothing to focus: the row half a screen further down or
/// up, from where each tracked row is on screen (`tracking`), the page's top (the row `top`) once Up reaches the start.
@MainActor
final class RemoteScroll {
    /// The scrolling view's frame on screen.
    var viewport: CGRect = .zero
    /// Each tracked row's last top on screen, in the rows' order.
    private var tops: [(id: String, order: [Int], y: CGFloat)] = []

    /// Records the top of the row `id`, `order` its place among the rows, as it moves.
    func tracking(_ id: String, order: [Int]) -> some ViewModifier {
        Tracking(id: id, order: order, scroll: self)
    }

    /// The row to scroll to the top for a press towards `direction`; nil for Left and Right, past the end, or at the top.
    func target(_ direction: MoveCommandDirection) -> String? {
        let half = viewport.height / 2
        switch direction {
        case .down:
            return tops.first { $0.y >= viewport.minY + half }?.id
        case .up:
            guard let first = tops.first, first.y < viewport.minY else { return nil }
            return tops.last { $0.y <= viewport.minY - half }?.id ?? "top"
        default:
            return nil
        }
    }

    fileprivate func record(_ id: String, _ order: [Int], _ y: CGFloat) {
        if let index = tops.firstIndex(where: { $0.id == id }) {
            tops[index].y = y
        } else {
            tops.append((id, order, y))
            tops.sort { $0.order.lexicographicallyPrecedes($1.order) }
        }
    }

    private struct Tracking: ViewModifier {
        let id: String
        let order: [Int]
        let scroll: RemoteScroll

        func body(content: Content) -> some View {
            content.onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { scroll.record(id, order, $0) }
        }
    }
}
#endif
