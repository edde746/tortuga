import SwiftUI
import TortugaKit

/// The TV guide route of `app` (`RouteGuide`), pushed over Home by its TV Guide shortcut: its `GuideStore` is kept by
/// the navigator while the route is on the stack (under "guide"), with what the screen remembers beside it
/// (`GuideSession`), so the guide outlasts a trip to the player.
struct GuideRoute: View {
    let app: AppModel
    let route: any Route

    /// Popped, the page draws what it showed while it slides out (`LastDrawn`).
    @State private var drawn = LastDrawn<RetainedStore<GuideSession, GuideUiState>>()

    var body: some View {
        if let model = drawn(retained()) {
            GuideScreen(
                state: model.observer.state,
                session: model.store,
                isTop: app.isTop(route)
            )
            .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<GuideSession, GuideUiState>? {
        let session = app.session
        return app.retained(
            route,
            key: "guide",
            create: { GuideSession(store: session.guideStore(), firstFocus: FirstFocus(pending: true)) },
            state: { $0.store.state },
            release: { $0.store.release() }
        )
    }
}

/// The guide's store and what the screen remembers beside it, kept together: the view's state outlives the view.
final class GuideSession {
    let store: GuideStore
    /// tvOS: the first focus, on the first channel's programme on now once the guide is ready.
    let firstFocus: FirstFocus
    /// The programme focused last (tvOS) or picked (iPhone, iPad, Mac) on the day and catalog shown: where focus comes
    /// back to. Another day or catalog picked forgets it.
    var remembered: GuideCell?

    init(store: GuideStore, firstFocus: FirstFocus) {
        self.store = store
        self.firstFocus = firstFocus
    }
}

/// A programme of the guide, or a channel without any (`programme` nil): its focus and selection key.
struct GuideCell: Hashable, Identifiable {
    let channel: String
    let programme: String?

    var id: Self { self }
}

/// A chip of the guide: a day, or a catalog when there are several.
enum GuideChip: Hashable {
    case day(Int64)
    case catalog(String)
}

/// The TV guide (`GuideUiState`): the days, then the catalogs when there are several, as chips; the programme's details
/// (its picture, else its channel's logo; on now, its times and channel; title; genres, runtime and ratings; synopsis;
/// cast and directors); then the time ruler over the channels, each a row of its programmes on the day's time, the one
/// on air a step lighter and a line at now. The time scrolls sideways under the ruler with the channel column fixed at
/// the leading edge, the channels up and down under the ruler; it opens half an hour before now on today, else at the
/// first channel's first programme. A channel without programmes is one cell that says so. Channels load on as the list
/// nears its end.
///
/// - tvOS: focus is the app's fill on the cells (`SurfaceButtonStyle`) and the details show the focused programme. Left
///   and Right walk a channel's programmes, the time scrolling to show the focused one; Up and Down move between
///   channels. The chips, the grid and each channel are focus sections: the chips land on the selected day; the grid on
///   the programme focused last on the day shown, else the first channel's programme on now (on another day: its next,
///   else its first), never on whichever cell lies nearest the chip; a channel on the programme nearest in time to the
///   one left. The guide opens with focus on that programme, and coming back from the player on the one focused last.
///   Select or Play/Pause plays the channel. No title over the chips (`RouteView`): the screen's height goes to the
///   channels.
/// - iPhone, iPad and the Mac: a programme is picked, drawn with the primary fill: the first channel's programme on now
///   as a day opens, then the one tapped or clicked. iPad and the Mac show it in the details over the channels, with
///   Play; a tap or a click on the picked one plays its channel too, and the Mac lightens the cell under the pointer.
///   iPhone has no details over the channels and its channel column is the logo alone (its name while there is none):
///   a tap opens the programme's sheet with its details and Play. A Now chip before the days brings the time back to
///   half an hour before now, on today.
///
/// Accessibility identifiers: `guide.now`, `guide.day.<index>`, `guide.catalog.<index>`, `guide.details` (with
/// `guide.details.title`), `guide.grid`, `guide.channel.<channel index>`, `guide.programme.<channel index>.<programme
/// index>` (its label the title, its value the times, "On now" first for the one on air; selected while picked),
/// `guide.empty.<channel index>`, `guide.play` (the details' and the sheet's Play), `guide.sheet` and
/// `guide.sheet.close`.
struct GuideScreen: View {
    let state: GuideUiState
    let session: GuideSession
    /// The guide is the top screen of the stack.
    let isTop: Bool

    @Environment(\.formFactor) private var formFactor
    @Environment(\.screenSides) private var sides
    /// The programme focused last (tvOS) or picked (elsewhere).
    @State private var shown: GuideCell?
    /// iPhone: the programme whose sheet is open.
    @State private var sheet: GuideCell?
    /// Touch: the Now chip, which brings the time back to now.
    @State private var backToNow = 0
    #if os(tvOS)
    @FocusState private var focus: GuideCell?
    @FocusState private var chipFocus: GuideChip?
    @State private var returns = 0
    /// The user's moves (`FocusRestore.userMoves`) at the last return: a move since ends that return's restore.
    @State private var returnMoves = 0
    #endif

    var body: some View {
        let metrics = GuideMetrics(formFactor)
        VStack(alignment: .leading, spacing: metrics.spacing) {
            chips
            content(metrics)
        }
        .padding(.top, metrics.spacing)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .pageTitle(T.shared.guide.title)
        #if os(tvOS)
        // Pushed from a tab, the safe area keeps the hidden tab bar's room at its top: the guide lies from the screen's
        // own top margin, as Detail does, and that room goes to the channels.
        .safeAreaPadding(.top, 60)
        .ignoresSafeArea(.container, edges: .top)
        #endif
        .onAppear { shown = session.remembered }
        .onChange(of: shown) { _, cell in session.remembered = cell }
        #if os(tvOS)
        .onChange(of: focus) { _, cell in
            if let cell { shown = cell }
        }
        .onChange(of: isTop) { wasTop, top in
            guard top, !wasTop else { return }
            returns += 1
            returnMoves = FocusRestore.userMoves
        }
        .firstFocus(session.firstFocus, to: showsGrid ? entry : nil, isTop: isTop, focus: $focus)
        .task(id: returns) {
            guard returns > 0, showsGrid else { return }
            await FocusRestore.restore($focus, to: entry, since: returnMoves)
        }
        #else
        .sheet(item: $sheet) { cell in
            if let found = find(cell) {
                GuideProgrammeSheet(channel: found.channel, programme: found.programme, compact: formFactor == .compact) {
                    sheet = nil
                    play(cell)
                } onClose: {
                    sheet = nil
                }
            }
        }
        #endif
    }

    private var chips: some View {
        GuideChips(
            state: state,
            onNow: nowChip,
            onSelectDay: { day in
                guard !day.selected else { return }
                forget()
                session.store.selectDay(epochDay: day.epochDay)
            },
            onSelectCatalog: { catalog in
                guard !catalog.selected else { return }
                forget()
                session.store.selectCatalog(key: catalog.key)
            }
        )
        #if os(tvOS)
        .focusedChips($chipFocus, selected: selectedChip)
        #endif
    }

    /// The Now chip's action (touch): back to now on today, today picked first; none on tvOS.
    private var nowChip: (() -> Void)? {
        #if os(tvOS)
        nil
        #else
        {
            guard let today = state.days.first(where: { $0.today }) else { return }
            if today.selected {
                backToNow += 1
            } else {
                forget()
                session.store.selectDay(epochDay: today.epochDay)
            }
        }
        #endif
    }

    @ViewBuilder
    private func content(_ metrics: GuideMetrics) -> some View {
        // The chips are always there to hold focus: the state views don't take it as a pushed screen's do.
        switch onEnum(of: state.body) {
        case .loading:
            LoadingStateView().environment(\.isPushedScreen, false)
        case .failed(let failed):
            ErrorStateView(message: failed.text, onRetry: { session.store.retry() })
        case .empty(let empty):
            EmptyStateView(message: empty.text, systemImage: "clock").environment(\.isPushedScreen, false)
        case .grid:
            VStack(alignment: .leading, spacing: metrics.spacing) {
                if metrics.showsDetails {
                    GuideDetails(found: find(current), metrics: metrics, onPlay: current.map { cell in { play(cell) } })
                        .padding(sides.insets)
                }
                GuideGrid(state: state, metrics: metrics, picked: pickedCell, backToNow: backToNow, onLoadMore: { session.store.loadMore() }) { cell in
                    pick(cell)
                }
                #if os(tvOS)
                .guideFocus($focus, entry: entry) { cell in play(cell) }
                #endif
                .padding(sides.insets)
                #if os(tvOS)
                // The grid clips at its frame: it runs on under the screen's bottom margin, so the fourth channel
                // shows whole.
                .ignoresSafeArea(.container, edges: .bottom)
                #endif
            }
        }
    }

    /// The programme the details show: on tvOS the one focused last on the day shown, else none (a programme of
    /// another day or catalog shows nothing, not its channel as empty); elsewhere the one picked, else the first
    /// channel's programme the day opens on.
    private var current: GuideCell? {
        #if os(tvOS)
        find(shown) != nil ? shown : nil
        #else
        find(shown) != nil ? shown : start
        #endif
    }

    /// The cell drawn picked: none on tvOS, whose focus shows.
    private var pickedCell: GuideCell? {
        #if os(tvOS)
        nil
        #else
        current
        #endif
    }

    /// A programme's cell tapped, clicked or (tvOS) selected.
    private func pick(_ cell: GuideCell) {
        #if os(tvOS)
        play(cell)
        #else
        if formFactor == .compact {
            shown = cell
            sheet = cell
        } else if current == cell {
            play(cell)
        } else {
            shown = cell
        }
        #endif
    }

    private func play(_ cell: GuideCell) {
        session.store.play(channelId: cell.channel)
    }

    /// Another day or catalog picked: its programmes are new, nothing of them picked or focused yet.
    private func forget() {
        shown = nil
        session.remembered = nil
    }

    /// The day's channels show (`GuideBody.Grid`).
    private var showsGrid: Bool {
        if case .grid = onEnum(of: state.body) { return true }
        return false
    }

    /// The first channel's programme on now (`startCell`).
    private var start: GuideCell? {
        state.channels.first.map(Self.startCell)
    }

    /// Where the grid takes focus: the programme focused last on the day shown, else the first channel's on now.
    private var entry: GuideCell? {
        find(shown) != nil ? shown : start
    }

    #if os(tvOS)
    private var selectedChip: GuideChip? {
        state.days.first { $0.selected }.map { .day($0.epochDay) }
    }
    #endif

    /// `cell`'s channel and programme in the day shown; nil for a programme of another day or catalog.
    private func find(_ cell: GuideCell?) -> (channel: GuideChannel, programme: GuideProgramme?)? {
        guard let cell, let channel = state.channels.first(where: { $0.id == cell.channel }) else { return nil }
        guard let id = cell.programme else { return channel.programmes.isEmpty ? (channel, nil) : nil }
        guard let programme = channel.programmes.first(where: { $0.id == id }) else { return nil }
        return (channel, programme)
    }

    /// The programme a channel opens on (`startIndex`); the channel itself without any.
    static func startCell(_ channel: GuideChannel) -> GuideCell {
        GuideCell(channel: channel.id, programme: channel.startIndex.map { channel.programmes[$0.intValue].id })
    }
}

/// The guide's sizes for a form factor: Apple TV's are Android TV's at 2 pt per dp (its 1920-point screen),
/// iPad's and the Mac's the tablet's at 1 pt per dp; iPhone's channel column is the logo alone, on 3 pt a minute.
private struct GuideMetrics {
    /// Points per minute of the time axis.
    let minute: CGFloat
    /// A channel's row, its cells and the gap under them.
    let row: CGFloat
    /// Between two cells, sideways and down.
    let gap: CGFloat
    /// The channel column: the logo and the name (iPhone: the logo alone).
    let channel: CGFloat
    let logo: CGFloat
    let showsNames: Bool
    let ruler: CGFloat
    /// The details show over the grid; on iPhone a sheet has them.
    let showsDetails: Bool
    let radius: CGFloat
    /// From a cell's edge to its text.
    let textInset: CGFloat
    /// A cell showing less of itself than this for its text shows none, not a cut word.
    let minText: CGFloat
    let nowLine: CGFloat
    /// Between the chips, the details and the grid.
    let spacing: CGFloat

    init(_ formFactor: FormFactor) {
        switch formFactor {
        case .tv:
            (minute, row, gap, channel, logo, showsNames, ruler) = (8, 120, 8, 352, 80, true, 56)
            (showsDetails, radius, textInset, minText, nowLine, spacing) = (true, Radius.large, 20, 96, 4, 24)
        case .regular:
            (minute, row, gap, channel, logo, showsNames, ruler) = (4, 60, 4, 176, 40, true, 28)
            (showsDetails, radius, textInset, minText, nowLine, spacing) = (true, Radius.small, 10, 48, 2, 12)
        case .compact:
            (minute, row, gap, channel, logo, showsNames, ruler) = (3, 60, 4, 56, 40, false, 28)
            (showsDetails, radius, textInset, minText, nowLine, spacing) = (false, Radius.small, 10, 48, 2, 8)
        }
    }

    var titleFont: Font { minute == 8 ? .title3.weight(.semibold) : .title2.weight(.semibold) }
    var lineFont: Font { minute == 8 ? .callout : .subheadline }
    var smallFont: Font { .caption }
    var channelFont: Font { (minute == 8 ? Font.callout : .subheadline).weight(.medium) }
    var cellTitleFont: Font { minute == 8 ? .body : .subheadline }
    var cellTimeFont: Font { .caption2 }
    /// Between the details' lines.
    var lineGap: CGFloat { minute == 8 ? 6 : 2 }
}

/// The Now chip (touch, `onNow`), then the day chips, then the catalog chips when there is more than one, in one row
/// scrolling sideways: the app's chips (`ChipMetrics`), the selected one's name semibold after a checkmark, as the
/// Streams list's addons are, each as wide either way so a pick moves no chip. tvOS: a focus section that lands on the
/// selected day (`focusedChips`).
private struct GuideChips: View {
    let state: GuideUiState
    let onNow: (() -> Void)?
    let onSelectDay: (GuideDay) -> Void
    let onSelectCatalog: (GuideCatalogChoice) -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: gap) {
                if let onNow {
                    GuideChipButton(label: T.shared.guide.now, selected: false, id: "guide.now", action: onNow)
                }
                ForEach(Array(state.days.enumerated()), id: \.element.epochDay) { index, day in
                    GuideChipButton(label: day.label, selected: day.selected, id: "guide.day.\(index)") { onSelectDay(day) }
                        .chipFocusKey(.day(day.epochDay))
                }
                if state.catalogs.count > 1 {
                    Color.clear.frame(width: gap)
                    ForEach(Array(state.catalogs.enumerated()), id: \.element.key) { index, catalog in
                        GuideChipButton(label: catalog.label, selected: catalog.selected, id: "guide.catalog.\(index)") { onSelectCatalog(catalog) }
                            .chipFocusKey(.catalog(catalog.key))
                    }
                }
            }
            #if !os(tvOS)
            .glassGroup(spacing: gap)
            #endif
            .padding(sides.insets)
            // Room for the focus ring (keyboard focus), which the row doesn't clip.
            .padding(.vertical, dimens.focusRoom)
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .shelfFocusSection()
    }

    private var gap: CGFloat {
        formFactor == .tv ? 16 : 8
    }
}

/// A chip of `GuideChips`: both looks laid out, the one not shown hidden, so a chip is as wide selected as not.
private struct GuideChipButton: View {
    let label: String
    let selected: Bool
    let id: String
    let action: () -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        let height = ChipMetrics.height(formFactor)
        let button = Button(action: action) {
            ZStack {
                text(selected: true).opacity(selected ? 1 : 0)
                text(selected: false).opacity(selected ? 0 : 1)
            }
            .lineLimit(1)
            .frame(maxWidth: ChipMetrics.maxNameWidth(formFactor))
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, ChipMetrics.inset(formFactor))
            .frame(minHeight: height)
            .contentShape(Capsule())
        }
        Group {
            #if os(tvOS)
            button.buttonStyle(SurfaceButtonStyle(fill: Palette.surfaceContainer, radius: height / 2))
            #else
            if #available(iOS 26, macOS 26, *) {
                button.glassCapsule()
            } else {
                button.buttonStyle(SurfaceButtonStyle(fill: Palette.surfaceContainer, radius: height / 2))
            }
            #endif
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    /// Selected: the checkmark before the name, on the interface's side of it whatever the name's script.
    private func text(selected: Bool) -> Text {
        let font = ChipMetrics.font(formFactor)
        guard selected else { return Text(label).font(font).fontWeight(.regular) }
        let name = Text(IsolateKt.isolate(text: label)).font(font).fontWeight(.semibold)
        return Text("\(layoutDirection.lineStart)\(Text(Image(systemName: "checkmark")).font(font.weight(.bold))) \(name)")
    }
}

/// The programme's details: its picture (its thumbnail, else its channel's logo fitted on the surface colour), then
/// "On now · 9:30 PM – 10:00 PM · Channel", the title, genres · runtime · year · rating, the synopsis on
/// `synopsisLines` lines at most, and cast · directors. A channel without programmes: its name and "No program
/// information". Nothing while no programme of the day shown is focused or picked. iPad and the Mac: Play at the
/// trailing end. They are as tall as every line they can show at the app's text size (`GuideDetailsLines`), so two
/// lines of synopsis never push the last line out, and the height holds whichever programme they show.
private struct GuideDetails: View {
    let found: (channel: GuideChannel, programme: GuideProgramme?)?
    let metrics: GuideMetrics
    let onPlay: (() -> Void)?

    @State private var height: CGFloat = 0

    var body: some View {
        HStack(alignment: .top, spacing: metrics.spacing) {
            if let found {
                GuidePicture(channel: found.channel, programme: found.programme)
                    .frame(width: height * 16 / 9, height: height)
                    .clipShape(RoundedRectangle(cornerRadius: metrics.radius * 1.2, style: .continuous))
                GuideProgrammeText(channel: found.channel, programme: found.programme, metrics: metrics, synopsisLines: Self.synopsisLines)
                    .frame(maxWidth: .infinity, alignment: .leading)
                #if !os(tvOS)
                if let onPlay {
                    GuidePlayButton(action: onPlay)
                        .frame(maxHeight: .infinity)
                }
                #endif
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: height)
        .background(alignment: .topLeading) {
            GuideDetailsLines(metrics: metrics)
                .fixedSize(horizontal: false, vertical: true)
                .hidden()
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("guide.details")
    }

    static let synopsisLines = 2
}

/// Every line the details can show, blank, in their fonts and gaps (`GuideProgrammeText`): what sets their height.
private struct GuideDetailsLines: View {
    let metrics: GuideMetrics

    var body: some View {
        VStack(alignment: .leading, spacing: metrics.lineGap) {
            Text(verbatim: " ").font(metrics.lineFont)
            Text(verbatim: " ").font(metrics.titleFont)
            Text(verbatim: " ").font(metrics.lineFont)
            Text(verbatim: " ").font(metrics.lineFont).lineLimit(GuideDetails.synopsisLines, reservesSpace: true)
            Text(verbatim: " ").font(metrics.smallFont)
        }
    }
}

/// A programme's picture: its thumbnail, else its channel's logo fitted on the surface colour.
private struct GuidePicture: View {
    let channel: GuideChannel
    let programme: GuideProgramme?

    var body: some View {
        if let thumbnail = programme?.thumbnail {
            RemoteImage(url: thumbnail) { Palette.surfaceContainer }
        } else {
            RemoteImage(url: channel.logo, contentMode: .fit) { Palette.surfaceContainer }
                .padding(12)
                .background(Palette.surfaceContainer)
        }
    }
}

/// The details' lines (`GuideDetails`), the synopsis on `synopsisLines` lines at most (nil: all of it).
private struct GuideProgrammeText: View {
    let channel: GuideChannel
    let programme: GuideProgramme?
    let metrics: GuideMetrics
    let synopsisLines: Int?

    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        VStack(alignment: .leading, spacing: metrics.lineGap) {
            Text(headline)
                .font(metrics.lineFont)
                .foregroundStyle(Palette.onSurfaceVariant)
                .lineLimit(1)
            Text(programme?.title ?? channel.name)
                .font(metrics.titleFont)
                .lineLimit(1)
                .accessibilityIdentifier("guide.details.title")
            if let programme {
                if let info = programme.infoText {
                    Text(info).font(metrics.lineFont).foregroundStyle(Palette.onSurfaceVariant).lineLimit(1)
                }
                if let overview = programme.overview {
                    Text(overview).font(metrics.lineFont).lineLimit(synopsisLines)
                }
                if let credits = programme.creditsText {
                    Text(credits).font(metrics.smallFont).foregroundStyle(Palette.onSurfaceVariant).lineLimit(1)
                }
            } else {
                Text(T.shared.guide.noProgrammes).font(metrics.lineFont).foregroundStyle(Palette.onSurfaceVariant)
            }
        }
    }

    /// The programme's headline on the interface's side whatever its script; the channel's name alone without one.
    private var headline: String {
        programme.map { layoutDirection.lineStart + $0.headline } ?? channel.name
    }
}

#if !os(tvOS)
/// Play: the details' and the sheet's main action, playing the programme's channel.
private struct GuidePlayButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(T.shared.detail.play, systemImage: "play.fill")
        }
        .buttonProminence(.primary)
        .largeControl()
        .accessibilityIdentifier("guide.play")
    }
}

/// iPhone (and an iPad window as narrow): a programme's details and Play in a sheet, half height pulled up to full. The
/// details scroll and Play stays under them, so it shows at half height too.
private struct GuideProgrammeSheet: View {
    let channel: GuideChannel
    let programme: GuideProgramme?
    let compact: Bool
    let onPlay: () -> Void
    let onClose: () -> Void

    /// The details' height: the scroll view is no taller, so Play follows them on a sheet pulled up to full.
    @State private var detailsHeight: CGFloat?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        GuidePicture(channel: channel, programme: programme)
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
                        GuideProgrammeText(channel: channel, programme: programme, metrics: GuideMetrics(.regular), synopsisLines: nil)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { detailsHeight = $0 }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: detailsHeight)
                GuidePlayButton(action: onPlay)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { SheetCloseButton(id: "guide.sheet.close", action: onClose) }
            }
        }
        #if os(iOS)
        .presentationDetents(compact ? [.medium, .large] : [.large])
        .presentationDragIndicator(compact ? .visible : .hidden)
        #endif
        .accessibilityIdentifier("guide.sheet")
    }
}
#endif

/// The ruler and the channels in one scroll view across both axes, so the time and the channels move together. The
/// scroll view's frame is the programmes' area alone, which focus keeps the focused programme within (tvOS); the
/// ruler above it and the channel column beside it are drawn past that frame from inside the content, each held at its
/// edge as the content scrolls (`visualEffect`), opaque over what scrolls under them, the grid clipped at their outer
/// edges. The time shown opens half an hour before now on today, else at the first channel's first programme, and
/// again on `backToNow`.
private struct GuideGrid: View {
    let state: GuideUiState
    let metrics: GuideMetrics
    /// Touch: the picked programme, drawn with the primary fill.
    let picked: GuideCell?
    let backToNow: Int
    let onLoadMore: () -> Void
    let onPick: (GuideCell) -> Void

    /// Points past the day's start where the time shown begins: what a programme begun before it moves its text by.
    @State private var shownFrom: CGFloat = 0
    /// The programmes' area's width.
    @State private var visible: CGFloat = 0

    var body: some View {
        let dayWidth = minutes(state.dayEndMillis) * metrics.minute
        let channelWidth = metrics.channel, rulerHeight = metrics.ruler
        ScrollViewReader { proxy in
            ScrollView([.horizontal, .vertical]) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(state.channels.enumerated()), id: \.element.id) { index, channel in
                        GuideRow(state: state, index: index, channel: channel, metrics: metrics, shownFrom: shownFrom, picked: picked, onPick: onPick)
                            .onAppear {
                                if state.hasMore, index >= state.channels.count - loadAhead { onLoadMore() }
                            }
                    }
                }
                .frame(width: dayWidth, alignment: .leading)
                .overlay(alignment: .topLeading) {
                    GuideRuler(state: state, metrics: metrics)
                        .frame(width: dayWidth, height: rulerHeight)
                        .visualEffect { content, geometry in
                            content.offset(y: -geometry.frame(in: .scrollView).minY - rulerHeight)
                        }
                }
                .overlay(alignment: .topLeading) {
                    Palette.background
                        .frame(width: channelWidth, height: rulerHeight)
                        .visualEffect { content, geometry in
                            let frame = geometry.frame(in: .scrollView)
                            return content.offset(x: -frame.minX - channelWidth, y: -frame.minY - rulerHeight)
                        }
                }
                .onGeometryChange(for: CGFloat.self) { max(0, -$0.frame(in: .scrollView).minX).rounded() } action: { shownFrom = $0 }
                .id(contentID)
            }
            .scrollClipDisabled()
            #if os(tvOS)
            .scrollIndicators(.hidden)
            #endif
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { visible = $0 }
            .accessibilityIdentifier("guide.grid")
            .padding(.leading, channelWidth)
            .padding(.top, rulerHeight)
            .clipped()
            .task(id: OpeningTime(day: state.dayStartMillis, catalog: state.catalogs.first { $0.selected }?.key, measured: visible > 0, backToNow: backToNow)) {
                guard visible > 0, dayWidth > visible else { return }
                let opening = min(max(minutes(state.openingMillis) * metrics.minute, 0), dayWidth - visible)
                proxy.scrollTo(contentID, anchor: UnitPoint(x: opening / (dayWidth - visible), y: 0))
            }
        }
    }

    /// Minutes from the day's start to `millis`.
    private func minutes(_ millis: Int64) -> CGFloat {
        CGFloat(millis - state.dayStartMillis) / 60_000
    }

    private let contentID = "guide.content"
    /// How many channels from the list's end its next page is asked for.
    private let loadAhead = 4
}

/// When the grid scrolls to its opening time: a day or catalog shown, the area first measured, Today picked again.
private struct OpeningTime: Hashable {
    let day: Int64
    let catalog: String?
    let measured: Bool
    let backToNow: Int
}

/// The half hours' times over the programmes, on the ruler's black so the rows scroll under it unseen.
private struct GuideRuler: View {
    let state: GuideUiState
    let metrics: GuideMetrics

    var body: some View {
        TimeLayout {
            ForEach(state.marks, id: \.millis) { mark in
                Text(mark.label)
                    .font(metrics.smallFont)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .lineLimit(1)
                    .fixedSize()
                    .layoutValue(key: TimeSpan.self, value: TimeSpan(x: CGFloat(mark.millis - state.dayStartMillis) / 60_000 * metrics.minute))
            }
        }
        .background(Palette.background)
        .accessibilityHidden(true)
    }
}

/// A channel: its programmes on the day's time with a line at now behind them, and over them at the leading edge of
/// what shows its logo and name (`guide.channel.<index>`), held there as the time scrolls. A focus section (tvOS).
private struct GuideRow: View {
    let state: GuideUiState
    let index: Int
    let channel: GuideChannel
    let metrics: GuideMetrics
    let shownFrom: CGFloat
    let picked: GuideCell?
    let onPick: (GuideCell) -> Void

    var body: some View {
        let channelWidth = metrics.channel
        TimeLayout {
            if channel.programmes.isEmpty {
                let cell = GuideCell(channel: channel.id, programme: nil)
                GuideCellButton(
                    cell: cell, title: channel.name, time: T.shared.guide.noProgrammes, start: T.shared.guide.noProgrammes,
                    value: T.shared.guide.noProgrammes,
                    onAir: false, past: false, picked: picked == cell, width: dayWidth - metrics.gap,
                    textShift: shownFrom, nowAt: nil, metrics: metrics, id: "guide.empty.\(index)"
                ) { onPick(cell) }
                .layoutValue(key: TimeSpan.self, value: TimeSpan(x: 0, width: dayWidth - metrics.gap))
            } else {
                ForEach(Array(channel.programmes.enumerated()), id: \.element.id) { programmeIndex, programme in
                    programmeCell(programmeIndex, programme)
                }
            }
        }
        .padding(.bottom, metrics.gap)
        .frame(width: dayWidth, height: metrics.row, alignment: .topLeading)
        .background(alignment: .topLeading) { nowLine }
        .overlay(alignment: .leading) {
            GuideChannelLabel(channel: channel, metrics: metrics)
                .frame(width: channelWidth, height: metrics.row)
                .background(Palette.background)
                .visualEffect { content, geometry in
                    content.offset(x: -geometry.frame(in: .scrollView).minX - channelWidth)
                }
                .accessibilityIdentifier("guide.channel.\(index)")
        }
        .shelfFocusSection()
    }

    private var dayWidth: CGFloat {
        minutes(state.dayEndMillis) * metrics.minute
    }

    private func programmeCell(_ programmeIndex: Int, _ programme: GuideProgramme) -> some View {
        // A programme across midnight is cut flat at the day's ends.
        let start = max(minutes(programme.startMillis), 0) * metrics.minute
        let end = min(minutes(programme.endMillis) * metrics.minute, dayWidth)
        let width = max(end - start - metrics.gap, metrics.gap)
        let onAir = programme.onAir
        let cell = GuideCell(channel: channel.id, programme: programme.id)
        return GuideCellButton(
            // A half-hour cell shows its start alone (`cellTimeText`), as does one showing too little of itself for the
            // range (`startText`); VoiceOver reads the whole range.
            cell: cell, title: programme.title, time: programme.cellTimeText, start: programme.startText,
            value: onAir ? "\(T.shared.guide.onNow), \(programme.timeText)" : programme.timeText,
            onAir: onAir, past: programme.past, picked: picked == cell, width: width,
            textShift: shownFrom - start, nowAt: onAir ? minutes(state.nowMillis) * metrics.minute - start : nil,
            metrics: metrics, id: "guide.programme.\(index).\(programmeIndex)"
        ) { onPick(cell) }
        .layoutValue(key: TimeSpan.self, value: TimeSpan(x: start, width: width))
    }

    /// The line at now behind the programmes, showing between them and where the channel has none; the programme on
    /// air draws its own part (`GuideCellButton`).
    @ViewBuilder
    private var nowLine: some View {
        if state.nowInDay {
            Palette.primary
                .frame(width: metrics.nowLine, height: metrics.row)
                .offset(x: minutes(state.nowMillis) * metrics.minute - metrics.nowLine / 2)
        }
    }

    private func minutes(_ millis: Int64) -> CGFloat {
        CGFloat(millis - state.dayStartMillis) / 60_000
    }
}

/// A channel's logo and name; iPhone's its logo alone, its name in small type while there is none (missing, loading or
/// failed). At the leading edge of its row.
private struct GuideChannelLabel: View {
    let channel: GuideChannel
    let metrics: GuideMetrics

    var body: some View {
        HStack(spacing: metrics.textInset) {
            if metrics.showsNames {
                RemoteImage(url: channel.logo, contentMode: .fit) { Color.clear }
                    .frame(width: metrics.logo, height: metrics.logo)
                Text(channel.name)
                    .font(metrics.channelFont)
                    .lineLimit(2)
            } else {
                RemoteImage(url: channel.logo, contentMode: .fit) {
                    Text(channel.name)
                        .font(metrics.smallFont)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                }
                .frame(maxWidth: .infinity, maxHeight: metrics.logo)
            }
        }
        .padding(.trailing, metrics.textInset)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(.bottom, metrics.gap)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(channel.name)
    }
}

/// A programme (or a channel without any): a plain fill, the one on air a step lighter, the past ones' title muted;
/// focused (tvOS) or `picked` (elsewhere), the primary fill with black text; on the Mac a step lighter under the
/// pointer. Its title and times start `textShift` points in when it began before the time shown, so its text stays in
/// view, cut short with "…"; one showing too little of itself for that (`GuideMetrics.minText`) shows no text, not a
/// cut word. Its times are `time`, or `start` where the room it shows can't hold `time` whole. The one on air draws the
/// line at now `nowAt` points in, over its fill and under its text, in its text's colour.
private struct GuideCellButton: View {
    let cell: GuideCell
    let title: String
    let time: String
    /// The start alone, where `time` doesn't fit.
    let start: String
    /// VoiceOver's value: the times, "On now" first for the programme on air.
    let value: String
    let onAir: Bool
    let past: Bool
    let picked: Bool
    let width: CGFloat
    let textShift: CGFloat
    let nowAt: CGFloat?
    let metrics: GuideMetrics
    let id: String
    let action: () -> Void

    #if os(macOS)
    @State private var hovered = false
    #endif

    var body: some View {
        Button(action: action) { label }
            .buttonStyle(SurfaceButtonStyle(fill: fill, radius: metrics.radius))
            #if os(macOS)
            .onHover { hovered = $0 }
            #endif
            .guideCellFocus(cell)
            .accessibilityLabel(title)
            .accessibilityValue(value)
            .accessibilityAddTraits(picked ? .isSelected : [])
            .accessibilityIdentifier(id)
    }

    private var label: some View {
        let shift = min(max(textShift, 0), width)
        let room = width - shift - 2 * metrics.textInset
        return ZStack(alignment: .leading) {
            if let nowAt {
                Rectangle()
                    .fill(.foreground)
                    .frame(width: metrics.nowLine)
                    .offset(x: nowAt - metrics.nowLine / 2)
            }
            VStack(alignment: .leading, spacing: metrics.minute == 8 ? 4 : 1) {
                Text(title).font(metrics.cellTitleFont).opacity(past ? 0.6 : 1)
                ViewThatFits(in: .horizontal) {
                    Text(time)
                    Text(start)
                }
                .font(metrics.cellTimeFont)
                .opacity(0.6)
            }
            .lineLimit(1)
            .padding(.leading, metrics.textInset + shift)
            .padding(.trailing, metrics.textInset)
            .opacity(room >= metrics.minText ? 1 : 0)
        }
        .frame(width: width, height: metrics.row - metrics.gap, alignment: .leading)
        #if !os(tvOS)
        .foregroundStyle(picked ? Palette.onPrimary : Palette.onBackground)
        #endif
        .contentShape(Rectangle())
    }

    private var fill: Color {
        #if !os(tvOS)
        if picked { return Palette.primary }
        #endif
        #if os(macOS)
        if hovered { return onAir ? Palette.surfaceContainerHighest : Palette.surfaceContainerHigh }
        #endif
        return onAir ? Palette.surfaceContainerHigh : Palette.surfaceContainer
    }
}

/// Where a view of a `TimeLayout` goes: `x` points from the day's start, `width` wide (nil: its own width).
private struct TimeSpan: LayoutValueKey {
    static let defaultValue = TimeSpan(x: 0)

    let x: CGFloat
    var width: CGFloat?
}

/// Lays its views out on the time axis, each at its `TimeSpan` from the leading edge, as tall as the layout (a label
/// of its own width centred in it); the layout takes the size it is offered.
private struct TimeLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            let span = subview[TimeSpan.self]
            if let width = span.width {
                subview.place(at: CGPoint(x: bounds.minX + span.x, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(width: width, height: bounds.height))
            } else {
                subview.place(at: CGPoint(x: bounds.minX + span.x, y: bounds.midY), anchor: .leading, proposal: .unspecified)
            }
        }
    }
}

extension View {
    #if os(tvOS)
    /// The guide's cells' focus: each cell's key in `focus` (`guideCellFocus`), the grid a focus section that lands on
    /// `entry`, and Play/Pause playing the focused programme's channel (`onPlay`).
    fileprivate func guideFocus(_ focus: FocusState<GuideCell?>.Binding, entry: GuideCell?, onPlay: @escaping (GuideCell) -> Void) -> some View {
        environment(\.guideFocus, focus)
            .focusSection()
            .defaultFocus(focus, entry, priority: .userInitiated)
            .onPlayPauseCommand {
                if let cell = focus.wrappedValue { onPlay(cell) }
            }
    }

    /// The chips' focus: each chip's key in `focus` (`chipFocusKey`), landing on `selected`.
    fileprivate func focusedChips(_ focus: FocusState<GuideChip?>.Binding, selected: GuideChip?) -> some View {
        environment(\.guideChipFocus, focus)
            .defaultFocus(focus, selected, priority: .userInitiated)
    }
    #endif

    /// The cell's key in the guide's focus (tvOS; `guideFocus`).
    fileprivate func guideCellFocus(_ cell: GuideCell) -> some View {
        modifier(GuideCellFocus(cell: cell))
    }

    /// The chip's key in the chips' focus (tvOS; `focusedChips`).
    fileprivate func chipFocusKey(_ chip: GuideChip) -> some View {
        modifier(GuideChipFocus(chip: chip))
    }
}

private struct GuideCellFocus: ViewModifier {
    let cell: GuideCell
    #if os(tvOS)
    @Environment(\.guideFocus) private var focus
    #endif

    func body(content: Content) -> some View {
        #if os(tvOS)
        if let focus {
            content.focused(focus, equals: cell)
        } else {
            content
        }
        #else
        content
        #endif
    }
}

private struct GuideChipFocus: ViewModifier {
    let chip: GuideChip
    #if os(tvOS)
    @Environment(\.guideChipFocus) private var focus
    #endif

    func body(content: Content) -> some View {
        #if os(tvOS)
        if let focus {
            content.focused(focus, equals: chip)
        } else {
            content
        }
        #else
        content
        #endif
    }
}

#if os(tvOS)
extension EnvironmentValues {
    /// The guide's focus, which its cells take their keys in (`guideFocus`).
    fileprivate var guideFocus: FocusState<GuideCell?>.Binding? {
        get { self[GuideFocusKey.self] }
        set { self[GuideFocusKey.self] = newValue }
    }

    /// The guide chips' focus (`focusedChips`).
    fileprivate var guideChipFocus: FocusState<GuideChip?>.Binding? {
        get { self[GuideChipFocusKey.self] }
        set { self[GuideChipFocusKey.self] = newValue }
    }
}

private struct GuideFocusKey: EnvironmentKey {
    static var defaultValue: FocusState<GuideCell?>.Binding? { nil }
}

private struct GuideChipFocusKey: EnvironmentKey {
    static var defaultValue: FocusState<GuideChip?>.Binding? { nil }
}
#endif
