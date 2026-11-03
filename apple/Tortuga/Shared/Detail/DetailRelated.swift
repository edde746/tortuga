import SwiftUI
import TortugaKit

/// "More like this" (`DetailUiState.related`): titles like this one as a catalog's shelf, with the Board's cards
/// (`MetaPoster`: the title, its year, its watched mark, its menu). While the sources are asked, the loading shelf of
/// blank cards (`detail.related.loading`); when they answered with no titles, the heading and one line saying so
/// (`detail.related.empty`); no row when none answered. A card (`detail.related.item.<n>`) opens its title on a Detail
/// page of its own. On Apple TV's page the cards hold `focus` (a card's id while it has focus), and a move into the row
/// lands on the card that had focus last (`last`), else the first.
struct DetailRelatedRow: View {
    let related: DetailRelated
    let intents: DetailIntents
    var focus: FocusState<String?>.Binding?
    var last: String?

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        if related.loading {
            LoadingShelf(title: related.title, id: "detail.related.loading") { EmptyView() }
        } else if let empty = related.emptyText {
            VStack(alignment: .leading, spacing: dimens.headingGap) {
                Text(related.title)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                Text(empty)
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(sides.insets)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("detail.related.empty")
        } else {
            let row = ShelfRow(title: related.title, items: Array(related.cards.enumerated()), id: \.element.id) { index, card in
                MetaPoster(
                    card: card,
                    accessibilityId: "detail.related.item.\(index)",
                    focus: focus.map { CardFocus(binding: $0, key: card.id) },
                    action: { intents.openRelated(card) },
                    onMenu: { intents.relatedMenuAction(card, $0) }
                )
            }
            if let focus {
                let entry = related.cards.first { $0.id == last }?.id ?? related.cards.first?.id
                row.defaultFocus(focus, entry, priority: .userInitiated)
            } else {
                row
            }
        }
    }
}
