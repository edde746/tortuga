import SwiftUI
import TortugaKit

/// A series' seasons as a row of chips (`detail.season.<i>`) and, for a season of more than 50 episodes, its ranges'
/// chips in a row under them (`detail.range.<i>`, `DetailUiState.episodeRanges`), on every page: Apple TV's, the
/// iPhone's one column and the episodes' column beside the details (iPad, Mac). The two rows together are
/// `detail.seasons`. They scroll sideways and open with the selected season's chip in view, nothing behind them. A row
/// draws past its frame (its focus ring, its chips as it scrolls): the one column's on to the window's edges, under the
/// Mac's sidebar glass as every shelf does, Apple TV's to the screen's; the episodes' column clips the rows at its own
/// edges, so they never draw over the details beside it (`DetailEpisodesPane`). `nameStart` is where the first name
/// starts from the rows' leading edge.
///
/// A season's chip selects its season; a range's runs `onRange` with the range's index, and the chip of
/// `selectedRange` shows selected: TV's row comes to the range's first episode, a list shows the range
/// (`DetailUiState.listedRange`). On tvOS a chip acts as focus moves onto it, as Prime Video's, so the next season is one
/// Right; moving into either row lands on its selected chip, so passing through selects nothing. Elsewhere a tap or a
/// click picks it. A season's menu (a held Select on the remote, a long press, a right click) is its `DetailSeason.menu`,
/// marking it or the whole title watched (`detail.menu.item.<action>`).
struct DetailSeasonChips: View {
    let state: DetailUiState
    let intents: DetailIntents
    let selectedRange: Int
    let onRange: (Int) -> Void
    /// The page's focus: a chip is `.season(n)` or `.range(i)` in it.
    let focus: FocusState<DetailFocus?>.Binding
    var nameStart: CGFloat = 0

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        let seasons = state.seasons
        let ranges = state.episodeRanges
        if !seasons.isEmpty || !ranges.isEmpty {
            VStack(alignment: .leading, spacing: formFactor == .tv ? 8 : 4) {
                if !seasons.isEmpty {
                    seasonRow(seasons)
                }
                if !ranges.isEmpty {
                    chipRow(selected: selectedRange) {
                        ForEach(Array(ranges.enumerated()), id: \.offset) { index, range in
                            DetailChip(label: range.label, selected: index == selectedRange, id: "detail.range.\(index)") {
                                onRange(index)
                            }
                            .focused(focus, equals: .range(index))
                            .id(index)
                        }
                    }
                    #if os(tvOS)
                    .defaultFocus(focus, .range(selectedRange), priority: .userInitiated)
                    #endif
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("detail.seasons")
            #if os(tvOS)
            // A chip acts as focus lands on it.
            .onChange(of: focus.wrappedValue) { _, focused in
                switch focused {
                case .season(let season)? where season != state.selectedSeason?.int32Value:
                    intents.selectSeason(season)
                case .range(let index)? where index != selectedRange:
                    onRange(index)
                default:
                    break
                }
            }
            #endif
        }
    }

    private func seasonRow(_ seasons: [DetailSeason]) -> some View {
        let selected = Int(state.selectedSeasonIndex)
        return chipRow(selected: selected) {
            ForEach(Array(seasons.enumerated()), id: \.element.season) { index, season in
                DetailChip(
                    label: season.label, selected: season.selected, watched: season.watched, stateText: season.stateText,
                    id: "detail.season.\(index)"
                ) {
                    if !season.selected { intents.selectSeason(season.season) }
                }
                .focused(focus, equals: .season(season.season))
                .itemMenu(
                    entries: season.menu.map { entry in
                        ItemMenuEntry.media(entry, probe: "detail.menu.item") { intents.seasonMenuAction(season.season, $0) }
                    },
                    probe: "detail.menu.item"
                )
                .id(index)
            }
        }
        #if os(tvOS)
        .defaultFocus(focus, .season(seasons[selected].season), priority: .userInitiated)
        #endif
    }

    /// One row of chips scrolling sideways, `selected` (the index its chips are `id`'d by) brought into view as it shows.
    private func chipRow<Chips: View>(selected: Int, @ViewBuilder chips: () -> Chips) -> some View {
        let content = chips()
        return ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: DetailChip.gap(formFactor)) {
                    content
                }
                .padding(.horizontal, nameStart - ChipMetrics.inset(formFactor))
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .onAppear { proxy.scrollTo(selected) }
        }
        .shelfFocusSection()
    }
}

/// How bright an unselected chip's name is: the text colour at this opacity, at least 4.5:1 against the brightest
/// backdrop under a chip; the selected name, in full colour and semibold, stands out against it.
private let unselectedChipOpacity = 0.65

/// A chip of the seasons' rows, as the Apple TV app draws its tabs over a picture: no fill at rest; the row-focus fill
/// while it has focus (`SurfaceButtonStyle`: white with black text on tvOS, nothing growing, so the row keeps one
/// height; dimmed while pressed and the focus ring on keyboard focus elsewhere). The selected one's name is in the text
/// colour and semibold, the others' regular at `unselectedChipOpacity`, but in full while focused; a check marks a
/// watched season, which VoiceOver reads as its value (`stateText`, "Watched"). One line, cut short past
/// `ChipMetrics.maxNameWidth` (`chipName`: a cap that grows with the text); its size the app's chips' (`ChipMetrics`).
/// Selected or not, a chip is as wide as its semibold name, a regular one centred in that width, so a pick moves no chip.
private struct DetailChip: View {
    let label: String
    let selected: Bool
    var watched = false
    /// What VoiceOver reads after its name (`DetailSeason.stateText`); none for nil.
    var stateText: String?
    let id: String
    let action: () -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        let height = ChipMetrics.height(formFactor)
        Button(action: action) {
            ChipLabel(label: label, selected: selected, watched: watched, maxWidth: ChipMetrics.maxNameWidth(formFactor))
                .padding(.horizontal, ChipMetrics.inset(formFactor))
                .frame(minHeight: height)
                .contentShape(Capsule())
        }
        .buttonStyle(SurfaceButtonStyle(fill: .clear, radius: height / 2))
        .accessibilityLabel(label)
        .accessibilityValue(stateText ?? "")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    private struct ChipLabel: View {
        let label: String
        let selected: Bool
        let watched: Bool
        let maxWidth: CGFloat

        @Environment(\.isFocused) private var focused
        @Environment(\.formFactor) private var formFactor

        var body: some View {
            let font = ChipMetrics.font(formFactor)
            HStack(spacing: formFactor == .tv ? 10 : 5) {
                if watched {
                    Image(systemName: "checkmark")
                        .font(font.weight(.semibold))
                        .accessibilityHidden(true)
                }
                // The semibold name sets the width; the name drawn is centred over it.
                Text(label)
                    .font(font.weight(.semibold))
                    .chipName(maxWidth: maxWidth)
                    .hidden()
                    .overlay {
                        Text(label)
                            .font(font.weight(selected ? .semibold : .regular))
                            .lineLimit(1)
                    }
            }
            .opacity(selected || focused ? 1 : unselectedChipOpacity)
        }
    }

    /// Between two chips: the focus fills of neighbours apart, the names a word's space apart at rest.
    static func gap(_ formFactor: FormFactor) -> CGFloat {
        formFactor == .tv ? 8 : 4
    }
}
