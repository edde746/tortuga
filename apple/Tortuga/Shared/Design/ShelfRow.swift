import SwiftUI
import TortugaKit

/// A titled, horizontally scrolling row of cards (a catalog on the Board, Continue Watching, a Discover shelf).
///
/// The row is a focus section: on tvOS (and with a keyboard) moving up or down into it lands on a card of the row, and
/// left/right move between its cards while the row scrolls to keep the focused one in view. Cards lift outside the
/// row's bounds on tvOS, so the scroll view doesn't clip them.
///
/// - Parameters:
///   - title: the row's heading (the catalog name, preformatted by the store).
///   - id: the key path of an item's stable identity.
///   - loading: the row is loading (its items, a reload or its next page): a spinner in the heading, before `action`,
///     centred on the title's line. Its place is kept while idle, so the heading neither grows nor moves as it comes
///     and goes.
///   - action: a trailing element in the heading ("See all").
///   - end: a card after the last item (a Home row's See all card on tvOS).
struct ShelfRow<Data: RandomAccessCollection, ID: Hashable, Action: View, Content: View, End: View>: View {
    let title: String
    let items: Data
    let id: KeyPath<Data.Element, ID>
    var loading = false
    @ViewBuilder var action: () -> Action
    @ViewBuilder var content: (Data.Element) -> Content
    @ViewBuilder var end: () -> End

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        VStack(alignment: .leading, spacing: dimens.headingGap) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(Self.titleFont)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: dimens.gutter)
                // A line of the title's font, which lines up with the title's, the spinner centred on it. A spinner's
                // text baseline is its top, which would put its top on the title's baseline: moved to its bottom, under
                // the line's, the line's sets the slot's.
                ZStack {
                    Text(verbatim: " ").font(Self.titleFont).hidden()
                    Group { if loading { ProgressView() } else { ProgressView().hidden() } }
                        .alignmentGuide(.firstTextBaseline) { $0[.bottom] }
                }
                action()
            }
            .padding(sides.insets)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: dimens.gutter) {
                    ForEach(items, id: id) { item in content(item) }
                    end()
                }
                .padding(sides.insets)
                // Room for the focused card to lift (tvOS) or its focus ring, which the row doesn't clip.
                .padding(.vertical, dimens.focusRoom)
            }
            .scrollClipDisabled()
            .shelfFocusSection()
        }
    }

    private static var titleFont: Font { .title3.weight(.semibold) }
}

extension ShelfRow where End == EmptyView {
    init(
        title: String,
        items: Data,
        id: KeyPath<Data.Element, ID>,
        loading: Bool = false,
        @ViewBuilder action: @escaping () -> Action,
        @ViewBuilder content: @escaping (Data.Element) -> Content
    ) {
        self.init(title: title, items: items, id: id, loading: loading, action: action, content: content, end: { EmptyView() })
    }
}

extension ShelfRow where Action == EmptyView, End == EmptyView {
    init(title: String, items: Data, id: KeyPath<Data.Element, ID>, @ViewBuilder content: @escaping (Data.Element) -> Content) {
        self.init(title: title, items: items, id: id, action: { EmptyView() }, content: content)
    }
}

extension View {
    /// A focus section where the platform has them (tvOS, macOS): moving into it lands on one of its elements.
    func shelfFocusSection() -> some View {
        #if os(iOS)
        self
        #else
        focusSection()
        #endif
    }

    /// On a shelf's card at `position` of `count`: `action` (the row's next page) as one of the row's last cards
    /// (`SHELF_LOAD_AHEAD`, about a TV screen's width) appears. After a page that fails, it comes again once such a card
    /// leaves the view and comes back.
    func nearShelfEnd(_ position: Int, of count: Int, perform action: @escaping () -> Void) -> some View {
        onAppear {
            if position >= count - Int(RowPagesKt.SHELF_LOAD_AHEAD) { action() }
        }
    }
}
