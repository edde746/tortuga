#if os(tvOS)
import SwiftUI
import TortugaKit
import UIKit

/// Apple TV's Detail page, Android TV's after Prime Video's: one column of sections over the title's
/// backdrop (its poster where it has none, as an anime entry's card while its title loads), top to bottom: the title
/// block (`DetailTvTitleBlock`: the logo, the metadata line and the ratings, three
/// lines of description ending in More, "With" and the first three cast names, then the action row in the TVs' order),
/// a series' season chips (a long season's range chips under them, `DetailSeasonChips`), the episode row with the
/// focused episode's details above it (`DetailTvEpisodeDetails`, `DetailTvEpisodeRow`), "More like this"
/// (`DetailRelatedRow`) and Cast & crew (`DetailTvCastRow`). Up and Down move between the sections, Left and Right
/// within one; each row is a focus section that takes focus at its own element, never the one nearest the press: the
/// action it had last (else Play, else the library's), the selected season and range, the episode the row is on (the
/// current one at first, then the last focused or a range's first), the card or name it had last (else the first).
/// "More like this" takes focus only once it has titles: its loading shelf and its line saying there are none show
/// where they rest on screen, and Down passes them.
///
/// The page moves a section at a time (`DetailTvStop`), in 320 ms: the title block's place while it or the actions
/// have focus; the seasons 24 pt under the title's name at the top while the seasons or the episodes have it (the
/// episode's details then open above the row, the title block fades out and the backdrop dims; a title with episodes
/// but no season or range chips moves the details there and Down from the title block goes to the row), as far as
/// the page's end allows; "More like this" and Cast & crew the same, each up to that place where it doesn't show there.
/// At each place a section shows only where it rests whole on screen, under the name's line and above the safe area's
/// bottom; it fades in and out with the move. With Reduce Motion the column doesn't slide: it fades out, takes its new
/// place and fades back in, as the HIG asks of a move along an axis (`move(to:then:)`), and the episode row brings its
/// episode to the middle at once. The page lies on the whole screen from its top margin, pushed from a tab too, whose
/// bar `RouteView` hides. There the safe area keeps the tab bar's room at its top: the name's line takes it, the
/// section a stop rests on stays under it (`Geometry.room`).
///
/// The page doesn't scroll: the column is laid out whole and moved to the stop of the section focus is in, so the
/// focus engine has no scroll of its own to make. The focus engine moves only to what shows on screen, so where a
/// section lies beyond those shown at the stop (under the screen's bottom, faded out above) a strip from the last
/// shown to the page's bottom, or from the page's top to the first shown, takes focus for it (`DetailFocus.edge`):
/// the page moves to that section's stop and, once there, hands focus on to the section's own element. More opens the
/// rest of the title's information in a side panel (`DetailTvMorePanel`), the action row's Rate the rating picker in
/// another (`detailRatingPicker`); the remote's Menu closes either, focus going back to what opened it, and leaves the page
/// from anywhere else in one press.
struct DetailTvPage: View {
    let state: DetailUiState
    let header: DetailHeader
    let intents: DetailIntents
    let focus: FocusState<DetailFocus?>.Binding

    @State private var stop = DetailTvStop.title
    @State private var heights = Heights()
    /// The page's room on screen (from the screen's top margin to the safe area's bottom), and the screen's width and
    /// leading safe area.
    @State private var viewport: CGFloat = 0
    /// The safe area's top under the screen's top margin: the tab bar's room on a page pushed from a tab, else none.
    @State private var room: CGFloat = 0
    @State private var screen = CGSize(width: 1920, height: 1080)
    @State private var leadingInset: CGFloat = 80
    @State private var panel = false
    /// The episode a move into the row lands on, an index of `state.episodes`.
    @State private var rowTarget = 0
    /// The round action or Play that had focus last in the action row.
    @State private var lastAction: DetailFocus?
    /// The name of Cast & crew that had focus last (its link's id).
    @State private var lastCast: String?
    /// The "More like this" card that has focus (its title's id), and the one that had it last.
    @FocusState private var relatedFocus: String?
    @State private var lastRelated: String?
    /// The column faded out for a move under Reduce Motion (`move(to:then:)`), and the moves asked for so far: a move
    /// asked for during another's fade takes over from it.
    @State private var faded = false
    @State private var moves = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let episodes = state.listsEpisodes
        let chips = episodes && (!state.seasons.isEmpty || !state.episodeRanges.isEmpty)
        let cast = state.listsCast
        let related = state.related
        let relatedCards = related.map { !$0.loading && !$0.cards.isEmpty } ?? false
        let open: CGFloat = episodes && stop != .title ? 1 : 0
        let geometry = Geometry(
            heights: heights, viewport: viewport, room: room, episodes: episodes, chips: chips, related: related != nil,
            relatedCards: relatedCards, cast: cast
        )
        // The page's room on screen, with the column over it from its top, laid out whole however far under the
        // screen it reaches (an overlay, so the room keeps its size), moved up to the stop by layout, so what the focus
        // engine and accessibility see is where it is drawn.
        Color.clear
            .overlay(alignment: .topLeading) {
                VStack(alignment: .leading, spacing: 0) {
                    DetailTvTitleBlock(state: state, header: header, intents: intents, lastAction: lastAction, focus: focus) { panel = true }
                        .measured { heights.hero = $0 }
                        .shown(geometry.rests(.hero, open: open, at: stop))
                    if episodes {
                        if chips {
                            DetailSeasonChips(
                                state: state,
                                intents: intents,
                                selectedRange: Int(state.rangeOf(index: Int32(rowTarget))),
                                onRange: bringRow(toRange:),
                                focus: focus
                            )
                            .measured { heights.seasons = $0 }
                            .shown(geometry.rests(.seasons, open: open, at: stop))
                            .padding(.top, Metrics.heroGap)
                        }
                        details
                            .frame(height: heights.details * open, alignment: .top)
                            .clipped()
                            .shown(open > 0 && geometry.rests(.details, open: open, at: stop))
                            .padding(.top, Metrics.seasonsGap)
                            .padding(.bottom, Metrics.detailsGap * open)
                        DetailTvEpisodeRow(state: state, intents: intents, target: $rowTarget, focus: focus)
                            .measured { heights.row = $0 }
                            .shown(geometry.rests(.row, open: open, at: stop))
                    }
                    if let related {
                        DetailRelatedRow(related: related, intents: intents, focus: $relatedFocus, last: lastRelated)
                            .measured { heights.related = $0 }
                            .shown(geometry.rests(.related, open: open, at: stop))
                            .padding(.top, Metrics.castGap)
                    }
                    if cast {
                        DetailTvCastRow(header: header, intents: intents, last: lastCast, focus: focus)
                            .measured { heights.cast = $0 }
                            .shown(geometry.rests(.cast, open: open, at: stop))
                            .padding(.top, Metrics.castGap)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, -geometry.offset(stop))
                // Under Reduce Motion the column takes its new place at once, while it is faded out.
                .transaction(value: stop) { if reduceMotion { $0.animation = nil } }
                .shown(!faded)
            }
            .overlay(alignment: .topLeading) {
                let edges = geometry.edges(at: stop, open: open)
                // A strip holding focus stays (at the page's edge where the stop has none) until focus has gone on: one
                // gone with focus on it would leave focus to the page's default, Play.
                let above = edges.above ?? (focus.wrappedValue == .edge(down: false) ? 1 : nil)
                let below = edges.below ?? (focus.wrappedValue == .edge(down: true) ? viewport - 1 : nil)
                ZStack(alignment: .topLeading) {
                    if let above { edge(down: false, from: 0, to: above) }
                    if let below { edge(down: true, from: min(below, viewport - 1), to: viewport) }
                }
            }
            .onChange(of: focus.wrappedValue) { old, focused in
                guard let focused else { return }
                if case .edge(let down) = focused {
                    pass(down: down, from: old, geometry)
                } else if let section = Self.section(of: focused) {
                    remember(focused)
                    move(to: section.stop)
                }
            }
            .onChange(of: relatedFocus) { _, card in
                guard let card else { return }
                lastRelated = card
                move(to: .related)
            }
            .overlay(alignment: .topLeading) {
                // The name, one line, at the top once the page has moved off the title block, which shows it itself.
                Text(header.title)
                    .font(.callout.weight(.semibold))
                    .lineLimit(1)
                    .frame(maxWidth: Metrics.textWidth, alignment: .leading)
                    .measured { heights.smallTitle = $0 }
                    .opacity(stop != .title && geometry.offset(stop) > 0 ? 1 : 0)
                    .accessibilityHidden(stop == .title)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { viewport = $0 }
            .background {
                DetailBackdrop(
                    url: header.background ?? header.poster,
                    style: .tv(textEnd: Double(min((leadingInset + Metrics.textWidth) / max(screen.width, 1), 0.9))),
                    sidebar: 0
                )
                .overlay { Palette.background.opacity(stop == .title ? 0 : Metrics.episodesDim) }
                .ignoresSafeArea()
            }
            .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.leading } action: { leadingInset = $0 }
            .onGeometryChange(for: CGSize.self) { proxy in
                CGSize(
                    width: proxy.size.width + proxy.safeAreaInsets.leading + proxy.safeAreaInsets.trailing,
                    height: proxy.size.height + proxy.safeAreaInsets.top + proxy.safeAreaInsets.bottom
                )
            } action: { screen = $0 }
            // Pushed from a tab, the safe area keeps the tab bar's room at its top though the bar is hidden: the page
            // lies from the screen's own top margin, as when it is the only screen (the More panel keeps the room).
            .safeAreaPadding(.top, Metrics.screenTop)
            .ignoresSafeArea(.container, edges: .top)
            .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { room = max($0 - Metrics.screenTop, 0) }
            .trailingPanel(isPresented: $panel, id: "detail.morePanel") {
                DetailTvMorePanel(header: header) { link in
                    panel = false
                    intents.openLink(link)
                }
            }
    }

    /// Moves the page to `next`: a slide (`Metrics.move`), or with Reduce Motion a fade out, the column at its new place
    /// and a fade back in (`Metrics.fade` each way), the backdrop's dim and the name's line fading with it. `then` runs
    /// once the move has ended, unless another move took over meanwhile.
    private func move(to next: DetailTvStop, then: @escaping () -> Void = {}) {
        guard reduceMotion else {
            withAnimation(Metrics.move) { stop = next } completion: { then() }
            return
        }
        moves += 1
        let move = moves
        withAnimation(Metrics.fade) { faded = true } completion: {
            guard move == moves else { return }
            withAnimation(Metrics.fade) {
                stop = next
                faded = false
            } completion: {
                if move == moves { then() }
            }
        }
    }

    /// A strip across the page from `from` to `to` (under the page's top) that takes focus for the section beyond the
    /// shown ones, under them if `down`, else above them (`pass`). Nothing shows of it.
    private func edge(down: Bool, from: CGFloat, to: CGFloat) -> some View {
        Color.clear
            .frame(maxWidth: .infinity)
            .frame(height: max(to - from, 1))
            .contentShape(Rectangle())
            .focusable()
            .focusEffectDisabled()
            .focused(focus, equals: .edge(down: down))
            .accessibilityHidden(true)
            .padding(.top, from)
    }

    /// Focus took a strip past the shown sections (`edge`): the page moves to the stop of the next section that way
    /// from the one focus left and, once the move has ended, puts focus on that section's own element, which shows from
    /// then on (asked for at once, focus went to the page's default, Play, instead); the strip keeps focus meanwhile.
    /// Focus goes back where it was if there is none. A "More like this" card leaves `focus` empty (its cards have
    /// focus states of their own): coming from one, the section left is the stop's.
    private func pass(down: Bool, from old: DetailFocus?, _ geometry: Geometry) {
        let left = old.flatMap(Self.section(of:)) ?? geometry.anchor(of: stop)
        let next = geometry.section(after: left, down: down)
        if next == .related, let card = relatedEntry {
            move(to: .related) { relatedFocus = card }
        } else if let next, let target = entry(of: next) {
            move(to: next.stop) { focus.wrappedValue = target }
        } else if old == nil, stop == .related {
            relatedFocus = lastRelated
        } else {
            focus.wrappedValue = old
        }

    }

    /// The element a move into `section` lands on: the action that had focus last (else Play, else the library's),
    /// the selected season (the selected range without seasons), the episode the row is on, the name that had focus
    /// last (else the first). "More like this" lands on a card of its own focus state (`relatedEntry`).
    private func entry(of section: Section) -> DetailFocus? {
        switch section {
        case .hero:
            return lastAction ?? (state.primaryAction != nil ? .play : .action(.library))
        case .seasons:
            if let season = state.selectedSeason { return .season(season.int32Value) }
            return state.episodeRanges.isEmpty ? nil : .range(Int(state.rangeOf(index: Int32(rowTarget))))
        case .details, .related:
            return nil
        case .row:
            return state.episodes[safe: rowTarget].map { .episode($0.videoId) }
        case .cast:
            let names = header.castAndCrew
            return (names.first { $0.id == lastCast } ?? names.first).map { .cast($0.id) }
        }
    }

    /// The "More like this" card a move into the row lands on: the one that had focus last, else the first.
    private var relatedEntry: String? {
        let cards = state.related?.cards ?? []
        return cards.first { $0.id == lastRelated }?.id ?? cards.first?.id
    }

    /// The details of the episode the row is on, measured whole whether open or not.
    private var details: some View {
        Group {
            if let episode = state.episodes[safe: rowTarget] {
                DetailTvEpisodeDetails(episode: episode)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .measured { heights.details = $0 }
    }

    /// As focus passes along the ranges: the row to the range's first episode, unless the row is in the range.
    private func bringRow(toRange index: Int) {
        guard let range = state.episodeRanges[safe: index] else { return }
        if !(Int(range.start)..<Int(range.end)).contains(rowTarget) { rowTarget = Int(range.start) }
    }

    private func remember(_ focused: DetailFocus) {
        switch focused {
        case .play, .action: lastAction = focused
        case .cast(let id): lastCast = id
        default: break
        }
    }

    private static func section(of focused: DetailFocus) -> Section? {
        switch focused {
        case .play, .page, .action, .more, .playerApp: .hero
        case .season, .range: .seasons
        case .episode: .row
        case .cast: .cast
        case .edge: nil
        }
    }

    /// The sections' heights, as measured.
    fileprivate struct Heights: Equatable {
        var smallTitle: CGFloat = 0
        var hero: CGFloat = 0
        var seasons: CGFloat = 0
        var details: CGFloat = 0
        var row: CGFloat = 0
        var related: CGFloat = 0
        var cast: CGFloat = 0
    }

    fileprivate enum Section {
        case hero, seasons, details, row, related, cast

        /// Where the page rests while focus is in the section.
        var stop: DetailTvStop {
            switch self {
            case .hero: .title
            case .seasons, .details, .row: .episodes
            case .related: .related
            case .cast: .cast
            }
        }
    }

    /// Where the sections sit on the page (from its top, the details open by `open`, 0 to 1) and where the page rests
    /// at each stop: the title block whole where it fits (else moved up until its end shows), the seasons
    /// `Metrics.nameGap` under the name's line (and under the tab bar's room), "More like this" and Cast & crew the
    /// same, each no further than the page's end (its bottom `Metrics.pageBottom` under the last section) lets it, and
    /// no higher than the stop before.
    fileprivate struct Geometry {
        let heights: Heights
        let viewport: CGFloat
        /// The safe area's top under the page's (`DetailTvPage.room`).
        let room: CGFloat
        let episodes: Bool
        /// The season or range chips show: else the episodes' stop starts at the details, and the row follows the
        /// title block when moving.
        let chips: Bool
        /// "More like this" is laid out (`related`), with titles that take focus (`relatedCards`).
        let related: Bool
        let relatedCards: Bool
        let cast: Bool

        /// What a section at a stop past the title block's stays under: the name's line and the gap under it, and the
        /// tab bar's room. A row in the room or touching its edge spreads its scroll view up to the screen's top, and
        /// the focus engine then takes its elements for that much higher than they show: Down from a season went up to
        /// the title block (runs da3-tv6, da4-tv5, da4-tv7).
        var headroom: CGFloat { max(heights.smallTitle + Metrics.nameGap, room > 0 ? room + 1 : 0) }

        func top(_ section: Section, open: CGFloat) -> CGFloat {
            let seasonsTop = heights.hero + (chips ? Metrics.heroGap : 0)
            let detailsTop = seasonsTop + (chips ? heights.seasons : 0) + Metrics.seasonsGap
            let rowTop = detailsTop + open * (heights.details + Metrics.detailsGap)
            let afterEpisodes = episodes ? rowTop + heights.row : heights.hero
            let relatedTop = afterEpisodes + Metrics.castGap
            switch section {
            case .hero: return 0
            case .seasons: return seasonsTop
            case .details: return detailsTop
            case .row: return rowTop
            case .related: return relatedTop
            case .cast: return (related ? relatedTop + heights.related : afterEpisodes) + Metrics.castGap
            }
        }

        func height(_ section: Section) -> CGFloat {
            switch section {
            case .hero: heights.hero
            case .seasons: heights.seasons
            case .details: heights.details
            case .row: heights.row
            case .related: heights.related
            case .cast: heights.cast
            }
        }

        /// The sections that take focus, top to bottom.
        var focusable: [Section] {
            [.hero] + (episodes ? (chips ? [.seasons, .row] : [.row]) : []) + (relatedCards ? [.related] : []) + (cast ? [.cast] : [])
        }

        /// The section a stop is for: the episodes' is the seasons, or the row where there are no chips.
        func anchor(of stop: DetailTvStop) -> Section {
            switch stop {
            case .title: .hero
            case .episodes: chips ? .seasons : .row
            case .related: .related
            case .cast: .cast
            }
        }

        /// The section that takes focus next to `section`, under it if `down`, else above it.
        func section(after section: Section, down: Bool) -> Section? {
            guard let index = focusable.firstIndex(of: section) else { return nil }
            return focusable[safe: down ? index + 1 : index - 1]
        }

        /// Where the strips past the sections shown at `stop` start and end on screen (`DetailTvPage.edge`): from the
        /// page's top to the first shown where a section lies above it, from the last shown to the page's bottom where
        /// one lies under it.
        func edges(at stop: DetailTvStop, open: CGFloat) -> (above: CGFloat?, below: CGFloat?) {
            let shown = focusable.filter { rests($0, open: open, at: stop) }
            guard let first = shown.first, let last = shown.last else { return (nil, nil) }
            let offset = offset(stop)
            return (
                first == focusable.first ? nil : top(first, open: open) - offset,
                last == focusable.last ? nil : top(last, open: open) + height(last) - offset
            )
        }

        /// The page's height with the details open by `open`.
        func end(open: CGFloat) -> CGFloat {
            let last: Section = cast ? .cast : related ? .related : episodes ? .row : .hero
            return top(last, open: open) + height(last) + Metrics.pageBottom
        }

        func offset(_ stop: DetailTvStop) -> CGFloat {
            guard viewport > 0 else { return 0 }
            let open: CGFloat = episodes ? 1 : 0
            let furthest = max(end(open: open) - viewport, 0)
            let title = min(max(heights.hero + Metrics.pageBottom - viewport, 0), max(end(open: 0) - viewport, 0))
            let seasons = episodes ? min(max(top(chips ? .seasons : .details, open: 1) - headroom, title), max(furthest, title)) : title
            let similar = relatedCards ? min(max(top(.related, open: open) - headroom, seasons), max(furthest, seasons)) : seasons
            switch stop {
            case .title: return title
            case .episodes: return seasons
            case .related: return similar
            case .cast: return cast ? min(max(top(.cast, open: open) - headroom, similar), max(furthest, similar)) : similar
            }
        }

        /// Whether `section` rests whole on screen at `stop`: under the name's line past the title block's stop (where
        /// the page moves at all), above the page's bottom. The episode row's room for a focused card's growth
        /// under its cards may lie past the bottom away from its own stop.
        func rests(_ section: Section, open: CGFloat, at stop: DetailTvStop) -> Bool {
            guard viewport > 0 else { return false }
            let offset = offset(stop)
            let y = top(section, open: open) - offset
            let room = section == .row && stop != .episodes ? Metrics.cardGrowthRoom : 0
            let named = stop != .title && offset > 0
            return (section == .hero && stop == .title || !named || y >= headroom - 1) && y + height(section) - room <= viewport + 1
        }
    }
}

/// Where Apple TV's Detail page rests (`DetailTvPage`): on the title block, on the episodes, on "More like this", on
/// Cast & crew.
enum DetailTvStop: Hashable {
    case title, episodes, related, cast
}

/// Apple TV's page's measures, in points (Android TV's dp doubled where they lay out the page, tvOS's own text styles).
private enum Metrics {
    /// The screen's top margin: Apple TV's safe area at the top of a screen without the tab bar.
    static let screenTop: CGFloat = 60
    /// The title block's text (metadata, description, the episode's details) is at most this wide.
    static let textWidth: CGFloat = 1120
    /// The logo's largest size.
    static let logoWidth: CGFloat = 720
    static let heroGap: CGFloat = 16
    static let seasonsGap: CGFloat = 32
    static let detailsGap: CGFloat = 24
    static let castGap: CGFloat = 40
    static let pageBottom: CGFloat = 64
    /// Under the name's line at the top, before the section the page rests on.
    static let nameGap: CGFloat = 24
    /// Above and below the episode cards: room for a focused card's lift.
    static let cardGrowthRoom: CGFloat = 28
    static let cardWidth: CGFloat = 384
    /// How much darker the backdrop gets once the page has moved off the title block: the picture at 30 %, under
    /// which the season chips' unselected names read at 4.5:1.
    static let episodesDim = 0.7
    /// The page moving between stops.
    static let move = Animation.timingCurve(0.4, 0, 0.2, 1, duration: 0.32)
    /// Each half of the page's move with Reduce Motion: the column fading out, then in at its new place.
    static let fade = Animation.easeInOut(duration: 0.16)
    /// How far past its section's frame a section draws: a chip's fill left of its name, the rows' cards and names to
    /// the screen's edges, a focused card's lift.
    static let bleed: CGFloat = 400
}

private extension View {
    func measured(_ action: @escaping (CGFloat) -> Void) -> some View {
        onGeometryChange(for: CGFloat.self, of: { $0.size.height }, action: action)
    }

    /// Drawn only while `shown`, past its frame by `Metrics.bleed`; still laid out, its accessibility elements kept (a
    /// mask, not opacity).
    func shown(_ shown: Bool) -> some View {
        mask { Color.black.opacity(shown ? 1 : 0).padding(-Metrics.bleed) }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

/// Where each round button of the action row is (`DetailActionRow` on tvOS), for the line under the row.
struct DetailActionAnchors: PreferenceKey {
    static var defaultValue: [DetailAction: Anchor<CGRect>] { [:] }

    static func reduce(value: inout [DetailAction: Anchor<CGRect>], nextValue: () -> [DetailAction: Anchor<CGRect>]) {
        value.merge(nextValue()) { $1 }
    }
}

/// The title block and the action row: the logo (else the name) in its 720 × 192 pt box, the metadata line with the
/// ratings after it (plain, none taking focus; the user's own score last once rated: `DetailUserRatingScore`), the
/// description's first three lines with More at the end of the last (`DetailTvDescription`), "With" and the first three
/// cast names (`detail.castLine`), then the actions
/// (`DetailActionRow`) with one line under them (`DetailTvActionLine`). A preview (`DetailUiState.preview`) is the
/// title and the metadata line alone. The title's playback in a player app and the saved title's note follow the
/// actions, as on every page.
private struct DetailTvTitleBlock: View {
    let state: DetailUiState
    let header: DetailHeader
    let intents: DetailIntents
    let lastAction: DetailFocus?
    let focus: FocusState<DetailFocus?>.Binding
    let onMore: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 24) {
                DetailTitleArt(
                    header: header,
                    maxWidth: Metrics.logoWidth,
                    artHeight: DetailMetrics.titleHeight(.tv, centred: false),
                    centred: false,
                    onFirstLine: { _ in }
                )
                // While the title loads, the page having no actions, the name holds focus (until the title's first focus
                // has taken it on): with nothing focusable the focus engine brings back the tab bar, which the page hides.
                .focusable(state.preview || focus.wrappedValue == .page)
                .focused(focus, equals: .page)
                if state.showsMetaLine {
                    FlowLayout(spacing: 32, lineSpacing: 12) {
                        DetailMetaLine(header: header, centred: false)
                            .frame(maxWidth: Metrics.textWidth, alignment: .leading)
                        ForEach(header.ratings, id: \.source.key) { rating in
                            DetailRatingItem(rating: rating, textSize: 26, onOpenLink: intents.openLink)
                        }
                        if let userRating = state.userRating, let text = userRating.text {
                            DetailUserRatingScore(text: text, label: userRating.label, textSize: 26)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                if !state.preview {
                    if header.hasMore {
                        DetailTvDescription(text: header.overview, focus: focus, onMore: onMore)
                    }
                    if let castLine = header.castLine {
                        Text(castLine)
                            .font(.body)
                            .foregroundStyle(Palette.onSurfaceVariant)
                            .lineLimit(1)
                            .accessibilityIdentifier("detail.castLine")
                    }
                } else if let note = state.loadingNote {
                    // What a long wait waits for, in the actions' place.
                    Text(note)
                        .font(.body)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .accessibilityIdentifier("detail.loadingNote")
                }
            }
            .frame(maxWidth: Metrics.textWidth, alignment: .leading)
            if !state.preview {
                DetailTvActionLine(state: state, focus: focus) {
                    DetailActionRow(state: state, intents: intents, compact: false, width: nil, focus: focus)
                }
                .focusSection()
                .defaultFocus(focus, lastAction ?? (state.primaryAction != nil ? .play : .action(.library)), priority: .userInitiated)
                .padding(.top, 16)
                if let playerApp = state.playerApp {
                    DetailPlayerAppLine(playerApp: playerApp, centred: false, focus: focus, onAnswer: intents.answerPlayerApp)
                        .frame(maxWidth: Metrics.textWidth, alignment: .leading)
                        .focusSection()
                }
                if let note = state.offlineNote {
                    DetailOfflineNote(text: note)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The action row with one line under it, drawn without moving the buttons: Play's episode's title ("We Gotta Go Now")
/// from Play's leading edge, as main's page has it, or while a round button has focus that button's action, centred
/// under it where that keeps it in the row's lane (from the row's leading edge to its end or the text column's,
/// whichever is further), else moved along until it is; either cut short past the lane. The row keeps the line's room.
private struct DetailTvActionLine<Row: View>: View {
    let state: DetailUiState
    let focus: FocusState<DetailFocus?>.Binding
    @ViewBuilder let row: () -> Row

    var body: some View {
        VStack(alignment: .leading, spacing: Self.gap) {
            row()
            // The line's room.
            Text(verbatim: " ").font(.caption).hidden()
        }
        .overlayPreferenceValue(DetailActionAnchors.self, alignment: .topLeading) { anchors in
            GeometryReader { proxy in
                let lane = max(proxy.size.width, Metrics.textWidth)
                ZStack(alignment: .topLeading) {
                    Color.clear
                    if case .action(let action)? = focus.wrappedValue, let name = name(of: action), let anchor = anchors[action] {
                        let button = proxy[anchor]
                        Text(name)
                            .font(.caption)
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(1)
                            .alignmentGuide(.leading) { text in -min(max(button.midX - text.width / 2, 0), max(lane - text.width, 0)) }
                            .alignmentGuide(.top) { _ in -(button.maxY + Self.gap) }
                    } else if let line = state.primaryAction?.episodeTitle {
                        Text(line)
                            .font(.caption)
                            .foregroundStyle(Palette.onSurfaceVariant)
                            .lineLimit(1)
                            .alignmentGuide(.top) { _ in -(proxy.size.height - Self.lineHeight) }
                    }
                }
                .frame(width: lane, height: proxy.size.height, alignment: .topLeading)
            }
        }
    }

    private static var gap: CGFloat { 16 }
    private static var lineHeight: CGFloat { UIFont.preferredFont(forTextStyle: .caption1).lineHeight }

    private func name(of action: DetailAction) -> String? {
        switch action {
        case .chooseStream: state.chooseStreamText
        case .library: state.libraryActionText
        case .watched: state.watchedActionText
        case .trailer: state.trailerActionText
        case .download: state.download?.label
        case .share: state.share?.label
        case .rate: state.userRating?.label
        case .kidsBlock: state.kidsBlockAction
        case .watchlist: state.watchlist?.label
        }
    }
}

/// The description's first three lines with More (`detail.more`, opening the side panel) after the end of the last,
/// centred on that line; a last line too long to leave it room is cut short, so More always ends it. More alone where
/// the addon gives no description. The lines are laid out by TextKit at the description's width and font, the last
/// beside More on its own line.
private struct DetailTvDescription: View {
    let text: String?
    let focus: FocusState<DetailFocus?>.Binding
    let onMore: () -> Void

    @State private var width: CGFloat = 0

    var body: some View {
        Group {
            if let text {
                let lines = width > 0 ? Self.lines(of: text, width: width) : nil
                VStack(alignment: .leading, spacing: 0) {
                    if let lines {
                        if !lines.head.isEmpty {
                            Text(lines.head).lineLimit(lines.headLines)
                        }
                        HStack(spacing: 20) {
                            Text(lines.last).lineLimit(1).truncationMode(.tail)
                            more
                        }
                    } else {
                        Text(text).lineLimit(3)
                    }
                }
                .font(.body)
                .foregroundStyle(Palette.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("detail.overview")
            } else {
                more
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // As wide as the text column, so Up from any action reaches More.
        .focusSection()
    }

    private var more: some View {
        Button(action: onMore) {
            Text(T.shared.detail.showMore)
                .font(.body.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 20)
                .padding(.vertical, 2)
                .fixedSize()
        }
        .buttonStyle(SurfaceButtonStyle(fill: Palette.primary.opacity(0.16), radius: 100))
        .focused(focus, equals: .more)
        .accessibilityIdentifier("detail.more")
    }

    /// `text` broken into lines at `width` in the body font: the lines before the last of at most three (`head`, that
    /// many lines), and the rest from the start of that last (`last`, one line with its breaks as spaces).
    private static func lines(of text: String, width: CGFloat) -> (head: String, headLines: Int, last: String) {
        let storage = NSTextStorage(string: text, attributes: [.font: UIFont.preferredFont(forTextStyle: .body)])
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        layout.addTextContainer(container)
        storage.addLayoutManager(layout)
        var starts: [Int] = []
        layout.enumerateLineFragments(forGlyphRange: layout.glyphRange(for: container)) { _, _, _, glyphs, stop in
            starts.append(layout.characterRange(forGlyphRange: glyphs, actualGlyphRange: nil).location)
            if starts.count == 3 { stop.pointee = true }
        }
        guard let lastStart = starts.last else { return ("", 0, text) }
        let string = text as NSString
        let head = string.substring(to: lastStart).trimmingCharacters(in: .whitespacesAndNewlines)
        let last = string.substring(from: lastStart).replacingOccurrences(of: "\n", with: " ")
        return (head, starts.count - 1, last)
    }
}

/// The episode the row is on, large above it: "5. We Gotta Go Now", its date, runtime and time left, three lines of
/// its description, always three lines tall so the row under it holds still as focus moves along it
/// (`detail.episodeDetails`).
private struct DetailTvEpisodeDetails: View {
    let episode: DetailEpisode

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(episode.numberedTitle)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
            Text(episode.detailLine ?? "")
                .font(.body)
                .foregroundStyle(Palette.onSurfaceVariant)
                .lineLimit(1)
            DetailEpisodeOverview(text: episode.overview ?? "", hiddenLabel: episode.hiddenOverviewLabel, blurRadius: 16)
                .font(.body)
                .foregroundStyle(Palette.onSurfaceVariant)
                .lineLimit(3, reservesSpace: true)
        }
        .frame(maxWidth: Metrics.textWidth, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("detail.episodeDetails")
    }
}

/// The selected season's episodes as a row of 16:9 cards (`DetailTvEpisodeCard`), the focused one kept in the middle
/// of the row (the row's ends stop it), scrolled there in 250 ms, at once with Reduce Motion. A move into the row lands
/// on `target` (the current episode at first, the episode the page shows, `DetailUiState.shownEpisode`, then the last
/// focused or a range's first), brought to the middle first.
private struct DetailTvEpisodeRow: View {
    let state: DetailUiState
    let intents: DetailIntents
    @Binding var target: Int
    let focus: FocusState<DetailFocus?>.Binding

    @Environment(\.detailRowReturn) private var rowReturn
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let episodes = state.episodes
        let selectedSeason = state.selectedSeason?.int32Value
        ScrollViewReader { row in
            ScrollView(.horizontal) {
                LazyHStack(spacing: dimens.gutter) {
                    // By index: a season of a thousand episodes isn't copied, nor each episode's id read, each time the
                    // row is evaluated; a card's own identity is its video's (`rowReturn`), which scrolling finds it by.
                    ForEach(episodes.indices, id: \.self) { index in
                        let episode = episodes[index]
                        DetailTvEpisodeCard(episode: episode, index: index, intents: intents, focus: focus)
                            .id(rowReturn.identity(of: episode.videoId))
                    }
                }
                .padding(.vertical, Metrics.cardGrowthRoom)
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .focusSection()
            .defaultFocus(focus, episodes[safe: target].map { DetailFocus.episode($0.videoId) }, priority: .userInitiated)
            .onChange(of: focus.wrappedValue) { _, focused in
                guard case .episode(let videoId)? = focused, let index = episodes.firstIndex(where: { $0.videoId == videoId }) else { return }
                target = index
                withAnimation(scroll) { row.scrollTo(rowReturn.identity(of: videoId), anchor: .center) }
            }
            // A range's chip moves the row to its first episode.
            .onChange(of: target) { _, index in
                guard let episode = episodes[safe: index], focus.wrappedValue != .episode(episode.videoId) else { return }
                withAnimation(scroll) { row.scrollTo(rowReturn.identity(of: episode.videoId), anchor: .center) }
            }
            // Another season starts at the current episode where it has it, else at its first.
            .onChange(of: selectedSeason, initial: true) {
                target = Int(state.currentEpisodeIndex)
                if let episode = episodes[safe: target] { row.scrollTo(rowReturn.identity(of: episode.videoId), anchor: .center) }
            }
            // The episode the page shows (the current one, an anime list entry's), once.
            .onChange(of: state.shownEpisode, initial: true) { _, shown in
                guard let shown, let index = episodes.firstIndex(where: { $0.videoId == shown }) else { return }
                target = index
                Task { @MainActor in
                    row.scrollTo(rowReturn.identity(of: shown), anchor: .center)
                    intents.episodeShown()
                }
            }
        }
    }

    private var scroll: Animation? { reduceMotion ? nil : .easeOut(duration: 0.25) }
}

/// An episode's card: its 16:9 thumbnail (`DetailEpisodeThumbnail`: progress, a check once watched) with its code
/// ("S2E5") at the bottom leading corner over a dark fade, dimmed until it is out, in Home's posters' corners and with
/// their focus (`cardButtonStyle`: the tvOS card's lift). Select and the remote's Play/Pause play it (auto-play's pick,
/// else its streams); a held Select opens its menu (`DetailEpisode.menu`, `detail.menu.item.<action>`). To
/// accessibility it is named "5. We Gotta Go Now", its value the progress and "Watched" (`DetailEpisode.stateText`),
/// its menu's entries its actions. `detail.episode.<i>`.
private struct DetailTvEpisodeCard: View {
    let episode: DetailEpisode
    let index: Int
    let intents: DetailIntents
    let focus: FocusState<DetailFocus?>.Binding

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.medium, style: .continuous)
        let entries = episode.menuEntries(intents)
        Button { intents.openEpisode(episode.videoId) } label: {
            DetailEpisodeThumbnail(episode: episode, width: Metrics.cardWidth, radius: Radius.medium)
                .overlay(alignment: .bottomLeading) {
                    if let code = episode.code {
                        Text(code)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Palette.onVideo)
                            .lineLimit(1)
                            .padding(.horizontal, 20)
                            .padding(.top, 32)
                            .padding(.bottom, episode.progress != nil ? 28 : 16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(LinearGradient(colors: [.clear, Palette.scrim.opacity(0.7)], startPoint: .top, endPoint: .bottom))
                    }
                }
                .clipShape(shape)
                .opacity(episode.upcoming ? 0.6 : 1)
        }
        .cardButtonStyle()
        .focused(focus, equals: .episode(episode.videoId))
        .accessibilityLabel(episode.numberedTitle)
        .accessibilityValue(episode.stateText ?? "")
        .accessibilityActions(entries)
        .accessibilityIdentifier("detail.episode.\(index)")
        .itemMenu(entries: entries, probe: "detail.menu.item")
        .onPlayPauseCommand { intents.openEpisode(episode.videoId) }
    }
}

/// Cast & crew: the cast, the directors and the writers, each name once (`DetailHeader.castAndCrew`), as a row of
/// names, each opening Search for it, in the pills Detail's links have elsewhere (`pillButtonStyle`), a name cut short
/// past 560 pt at the default text size as a chip's is (`chipName`). A move into the row lands on the name that had
/// focus last, else the first. `detail.cast.<i>`.
private struct DetailTvCastRow: View {
    let header: DetailHeader
    let intents: DetailIntents
    let last: String?
    let focus: FocusState<DetailFocus?>.Binding

    var body: some View {
        let names = header.castAndCrew
        VStack(alignment: .leading, spacing: 4) {
            Text(T.shared.detail.castAndCrew)
                .font(.callout.weight(.semibold))
                .lineLimit(1)
                .frame(maxWidth: Metrics.textWidth, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal) {
                // Built whole: a title has a few names.
                HStack(spacing: 24) {
                    ForEach(Array(names.enumerated()), id: \.element.id) { index, link in
                        Button { intents.openLink(link.id) } label: {
                            Text(link.label).chipName(maxWidth: 560)
                        }
                        .pillButtonStyle()
                        .focused(focus, equals: .cast(link.id))
                        .accessibilityIdentifier("detail.cast.\(index)")
                    }
                }
                // The pills' glass, one row of it.
                .glassGroup(spacing: 24)
                .padding(.vertical, Metrics.cardGrowthRoom)
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .focusSection()
            .defaultFocus(focus, .cast(last.flatMap { id in names.contains { $0.id == id } ? id : nil } ?? names.first?.id ?? ""), priority: .userInitiated)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("detail.cast")
    }
}

/// The rest of the title's information, opened by More, in a side panel (`detail.morePanel`): the whole description,
/// then the information block (`DetailInformation`: the facts, the genres, cast, directors and writers as links). The
/// panel scrolls as focus moves down its links; opening one closes the panel (`onOpenLink`). Without a link it holds
/// focus itself, so the remote's Menu closes it.
private struct DetailTvMorePanel: View {
    let header: DetailHeader
    let onOpenLink: (String) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                Text(header.title)
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                if let overview = header.overview {
                    Text(overview)
                        .font(.body)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                DetailInformation(header: header, onOpenLink: onOpenLink)
            }
            .padding(.trailing, 48)
            .padding(.vertical, 48)
        }
        .scrollClipDisabled()
        .holdsBackFocus(header.linkGroups.isEmpty)
    }
}
#endif
