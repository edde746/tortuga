import SwiftUI
import TortugaKit

/// The Calendar route of `app` (`Route.Calendar`), pushed over Home (its Calendar shortcut) or Library (its Calendar
/// button): its `CalendarStore` is kept by the navigator while the route is on the stack (under "calendar"), so it
/// outlasts a visit to an episode's series.
struct CalendarRoute: View {
    let app: AppModel

    /// Popped, the page draws what it showed while it slides out (`LastDrawn`).
    @State private var drawn = LastDrawn<RetainedStore<CalendarStore, CalendarUiState>>()

    var body: some View {
        if let model = drawn(retained()) {
            CalendarScreen(state: model.observer.state, intents: CalendarIntents(model.store))
                .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<CalendarStore, CalendarUiState>? {
        let session = app.session
        return app.retained(
            RouteCalendar.shared,
            key: "calendar",
            create: { session.calendarStore() },
            state: { $0.state },
            release: { $0.release() }
        )
    }
}

/// What the Calendar screen sends its store.
struct CalendarIntents {
    let previousMonth: () -> Void
    let nextMonth: () -> Void
    let thisMonth: () -> Void
    let open: (CalendarEpisode) -> Void

    init(_ store: CalendarStore) {
        previousMonth = { store.previousMonth() }
        nextMonth = { store.nextMonth() }
        thisMonth = { store.thisMonth() }
        open = { store.open(episode: $0) }
    }
}

/// The Calendar: the month's name with Today (away from this month, back to it) and the arrows to the previous and next months, the
/// month's grid of days, Monday first, today's number in a white circle and a dot under a day for each of its episodes
/// (up to three), then the agenda: the month's days with episodes, each under its heading ("Today", "Tomorrow",
/// "Thursday, October 29"), its episodes in Detail's episode rows (`EpisodeRowLabel`, no card): the episode's still
/// (else its series' landscape art, sharp, `CalendarEpisode.art`), its series, its line ("S1E3 · Title · 46 min") and
/// two lines of its description, the still and the description blurred while the profile hides spoilers. An episode
/// opens its series' Detail on it. Without days with episodes, a spinner while the addons answer, else the store's text
/// saying why (`CalendarUiState.body`). Every text comes from `CalendarUiState`.
///
/// - iPhone (and a window as narrow): one column that scrolls, the grid over the agenda; a tap on a day with episodes
///   scrolls its heading to the top.
/// - iPad, the Mac: the grid in a column of its own beside the agenda, which scrolls on its own; a tap or click on a day
///   brings its heading to the agenda's top. Under the pointer a day and a row light up.
/// - Apple TV: the same two columns, each a focus section, and the month's bar above the grid another. Every day takes
///   focus, so the remote walks the grid a day or a week at a time; focus on a day with episodes brings them to the
///   agenda's top, and Select moves focus onto its first episode. Each part is entered on a stated element, never the
///   one nearest the move: the grid on the day that had focus last (or the day of the episode that had it, whichever
///   came later), else the entry day (the first day with episodes from today on, from the 1st away from this month,
///   else the month's first with episodes, else today or the 1st); the agenda on the first episode of that day, else
///   of the next day with episodes after it, else of the last; the month's bar on the control it left, else Today, else
///   Previous month. The screen opens on the entry day once the addons have answered, unless the user has
///   moved focus.
///
/// Accessibility identifiers (the Compose UI's names): `calendar.month`, `calendar.today`, `calendar.previous`,
/// `calendar.next`, `calendar.day.<day>` (its value the date and episodes, as a screen reader says them),
/// `calendar.agenda.<day>`, `calendar.episode.<video id>`, `calendar.loading`, `calendar.empty`.
struct CalendarScreen: View {
    let state: CalendarUiState
    let intents: CalendarIntents

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides
    #if os(tvOS)
    @FocusState private var focus: CalendarFocus?
    /// The day that had focus last, or the day of the episode that had it, whichever came later; nil in a month just
    /// shown.
    @State private var lastDay: Int32?
    /// The month's bar's control that had focus last.
    @State private var lastBarControl: CalendarFocus?
    /// The user's moves of focus as the screen opened (`FocusRestore.userMoves`): the first focus waits for none since.
    @State private var openMoves: Int?
    #endif

    var body: some View {
        ScrollViewReader { proxy in
            Group {
                if formFactor == .compact {
                    column(proxy)
                } else {
                    columns(proxy)
                }
            }
            #if os(tvOS)
            .onChange(of: focus) { _, value in
                switch value {
                case .day(let day):
                    lastDay = day
                    if state.agenda.contains(where: { $0.day == day }) {
                        withAnimation { proxy.scrollTo(Self.dayId(day), anchor: .top) }
                    }
                case .episode(let day, _):
                    lastDay = day
                case .thisMonth, .previous, .next:
                    lastBarControl = value
                case nil:
                    break
                }
            }
            .onChange(of: state.month) { lastDay = nil }
            #endif
        }
        .pageTitle(state.title)
        #if os(tvOS)
        .task(id: firstFocus) {
            if openMoves == nil { openMoves = FocusRestore.userMoves }
            // Until the user moves, whatever has focus is the system's pick as the screen opened: the first focus
            // replaces it.
            await FocusRestore.restore($focus, to: firstFocus, since: openMoves, replacing: true)
        }
        #endif
    }

    /// iPhone: the month's bar, the grid and the agenda in one scrolling column.
    private func column(_ proxy: ScrollViewProxy) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                monthBar
                CalendarMonthGrid(state: state, focus: gridFocus) { day in scroll(proxy, to: day) }
                    .padding(.bottom, dimens.sectionSpacing)
                if let placeholder {
                    placeholder.frame(minHeight: 240)
                } else {
                    // The rows reach their inset past the page's edge, so their stills line up with the grid.
                    agenda.padding(.horizontal, -DetailMetrics.rowInset)
                }
            }
            .padding(sides.insets)
            .padding(.top, dimens.headingGap)
            .padding(.bottom, dimens.sectionSpacing)
        }
    }

    /// iPad, the Mac and Apple TV: the month's bar over the grid, beside the agenda.
    private func columns(_ proxy: ScrollViewProxy) -> some View {
        HStack(alignment: .top, spacing: Self.columnGap(formFactor)) {
            gridColumn(proxy)
                .frame(width: Self.gridWidth(formFactor))
            Group {
                if let placeholder {
                    placeholder
                } else {
                    ScrollView {
                        agenda
                            .padding(.vertical, dimens.focusRoom)
                            #if os(tvOS)
                            // Room for a focused still's lift.
                            .padding(.horizontal, dimens.focusRoom)
                            #endif
                            .padding(.bottom, dimens.sectionSpacing)
                    }
                    #if os(tvOS)
                    .focusSection()
                    .defaultFocus($focus, agendaLanding, priority: .userInitiated)
                    #else
                    // The rows reach their inset past the column's edge, so their stills line up with the grid's.
                    .padding(.horizontal, -DetailMetrics.rowInset)
                    #endif
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .padding(sides.insets)
        .padding(.top, dimens.headingGap)
    }

    @ViewBuilder
    private func gridColumn(_ proxy: ScrollViewProxy) -> some View {
        let column = VStack(alignment: .leading, spacing: 0) {
            monthBar
            CalendarMonthGrid(state: state, focus: gridFocus) { day in
                #if os(tvOS)
                if let first = state.agenda.first(where: { $0.day == Int32(day) })?.episodes.first {
                    focus = .episode(day: Int32(day), id: Self.episodeKey(first))
                }
                #else
                scroll(proxy, to: day)
                #endif
            }
            #if os(tvOS)
            .focusSection()
            .defaultFocus($focus, gridLanding.map(CalendarFocus.day), priority: .userInitiated)
            #endif
        }
        #if os(tvOS)
        column
        #else
        // A short window (the Mac's, large text) scrolls the grid rather than cut it.
        ScrollView { column.padding(.bottom, dimens.sectionSpacing) }
        #endif
    }

    /// The month's days with episodes, each its heading over its episodes' rows.
    private var agenda: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(state.agenda, id: \.day) { day in
                CalendarDayHeading(day: day)
                    .id(Self.dayId(day.day))
                VStack(spacing: 2) {
                    ForEach(day.episodes, id: \.videoId) { episode in
                        CalendarEpisodeRow(episode: episode, focus: gridFocus, focusValue: .episode(day: day.day, id: Self.episodeKey(episode))) {
                            intents.open(episode)
                        }
                    }
                }
            }
        }
    }

    /// Without days with episodes, a spinner while the addons answer, else why there is nothing to show; nil once the
    /// agenda has days. On Apple TV it takes no focus: the month's bar has its buttons.
    private var placeholder: AnyView? {
        switch onEnum(of: state.body) {
        case .loading:
            return AnyView(
                LoadingStateView()
                    .environment(\.isPushedScreen, false)
                    .accessibilityIdentifier("calendar.loading")
            )
        case .empty(let empty):
            return AnyView(
                EmptyStateView(message: empty.text, systemImage: "calendar")
                    .environment(\.isPushedScreen, false)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("calendar.empty")
            )
        case .agenda:
            return nil
        }
    }

    private func scroll(_ proxy: ScrollViewProxy, to day: Int) {
        withAnimation { proxy.scrollTo(Self.dayId(Int32(day)), anchor: .top) }
    }

    private var monthBar: some View {
        #if os(tvOS)
        CalendarMonthBar(state: state, intents: intents, focus: $focus, landing: barLanding)
        #else
        CalendarMonthBar(state: state, intents: intents)
        #endif
    }

    /// The grid's place in the screen's focus (Apple TV); none elsewhere.
    private var gridFocus: FocusState<CalendarFocus?>.Binding? {
        #if os(tvOS)
        $focus
        #else
        nil
        #endif
    }

    #if os(tvOS)
    /// The first day with episodes from today on (from the 1st away from this month), else the month's first with
    /// episodes, else today (the 1st away from this month).
    private var entryDay: Int32? {
        let fromToday = state.currentMonth ? Array(state.days.drop(while: { !$0.today })) : state.days
        return (fromToday.first(where: { $0.episodes > 0 }) ?? state.days.first(where: { $0.episodes > 0 }) ?? fromToday.first)?.day
    }

    /// Where focus goes as the screen opens, once the addons have answered: the entry day.
    private var firstFocus: CalendarFocus? {
        if case .loading = onEnum(of: state.body) { return nil }
        return entryDay.map(CalendarFocus.day)
    }

    /// Where a move into the grid lands: the day that had focus last, else the entry day.
    private var gridLanding: Int32? {
        lastDay ?? entryDay
    }

    /// Where a move into the agenda lands: the first episode of the day that had focus last (else of the entry day),
    /// else of the next day with episodes after it, else of the last.
    private var agendaLanding: CalendarFocus? {
        let from = lastDay ?? entryDay ?? 1
        guard let day = state.agenda.first(where: { $0.day >= from }) ?? state.agenda.last, let episode = day.episodes.first else { return nil }
        return .episode(day: day.day, id: Self.episodeKey(episode))
    }

    /// Where a move into the month's bar lands: the control that had focus last, else Today, else Previous month.
    private var barLanding: CalendarFocus {
        if let control = lastBarControl, control != .thisMonth || !state.currentMonth { return control }
        return state.currentMonth ? .previous : .thisMonth
    }
    #endif

    static func dayId(_ day: Int32) -> String { "day:\(day)" }

    /// An episode's key among the agenda's rows: its series' and its own id.
    static func episodeKey(_ episode: CalendarEpisode) -> String { "\(episode.metaId)|\(episode.videoId)" }

    /// The grid's column beside the agenda; on Apple TV wide enough for the longest month's name in English, Today
    /// and the arrows on one line.
    private static func gridWidth(_ formFactor: FormFactor) -> CGFloat {
        formFactor == .tv ? 780 : 380
    }

    private static func columnGap(_ formFactor: FormFactor) -> CGFloat {
        formFactor == .tv ? 80 : 32
    }
}

/// What has focus on the Calendar (Apple TV): a day of the grid, an episode of the agenda (its day and key), or a control
/// of the month's bar.
enum CalendarFocus: Hashable {
    case day(Int32)
    case episode(day: Int32, id: String)
    case thisMonth
    case previous
    case next
}

/// The month's name in the title style, then Today away from this month, then Previous month and Next month: plain
/// chevrons on iPhone, iPad and the Mac, glass buttons on Apple TV. The controls keep their size: where the row is too
/// narrow the name wraps rather than cut a control's label short. On Apple TV the whole row, the name's width included,
/// is a focus section, so Up from any day of the grid's first week enters it, on `landing`; Today hands focus to
/// Previous month as it goes, rather than leave it to the focus engine.
private struct CalendarMonthBar: View {
    let state: CalendarUiState
    let intents: CalendarIntents
    #if os(tvOS)
    let focus: FocusState<CalendarFocus?>.Binding
    let landing: CalendarFocus
    #endif

    var body: some View {
        HStack(spacing: 4) {
            Text(state.month)
                .font(Self.titleFont)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("calendar.month")
            #if os(tvOS)
            HStack(spacing: 20) {
                if !state.currentMonth {
                    ActionButton(title: T.shared.calendar.today, id: "calendar.today", fullWidth: false) {
                        focus.wrappedValue = .previous
                        intents.thisMonth()
                    }
                    .focused(focus, equals: .thisMonth)
                }
                IconButton(systemImage: "chevron.backward", label: T.shared.calendar.previousMonth, id: "calendar.previous", action: intents.previousMonth)
                    .focused(focus, equals: .previous)
                IconButton(systemImage: "chevron.forward", label: T.shared.calendar.nextMonth, id: "calendar.next", action: intents.nextMonth)
                    .focused(focus, equals: .next)
            }
            .fixedSize()
            #else
            if !state.currentMonth {
                Button(T.shared.calendar.today, action: intents.thisMonth)
                    .buttonStyle(.borderless)
                    .fixedSize()
                    .accessibilityIdentifier("calendar.today")
            }
            chevron("chevron.backward", T.shared.calendar.previousMonth, "calendar.previous", intents.previousMonth)
            chevron("chevron.forward", T.shared.calendar.nextMonth, "calendar.next", intents.nextMonth)
            #endif
        }
        .padding(.bottom, 8)
        #if os(tvOS)
        .focusSection()
        .defaultFocus(focus, landing, priority: .userInitiated)
        #endif
    }

    #if !os(tvOS)
    private func chevron(_ symbol: String, _ label: String, _ id: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .foregroundStyle(Palette.onBackground)
        #if os(macOS)
        .tooltip(label)
        #endif
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }
    #endif

    private static var titleFont: Font {
        #if os(tvOS)
        .title3.weight(.semibold)
        #else
        .title2.weight(.semibold)
        #endif
    }
}

/// The weekdays' short names, then the month's weeks from Monday; a day with episodes is a button (`onDay` with its
/// day), the others are text alone.
private struct CalendarMonthGrid: View {
    let state: CalendarUiState
    let focus: FocusState<CalendarFocus?>.Binding?
    let onDay: (Int) -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        let cells: [CalendarDay?] = Array(repeating: nil, count: Int(state.leadingDays)) + state.days.map { Optional($0) }
        let weeks: [[CalendarDay?]] = stride(from: 0, to: cells.count, by: 7).map { start in
            (0..<7).map { start + $0 < cells.count ? cells[start + $0] : nil }
        }
        Grid(horizontalSpacing: 2, verticalSpacing: 2) {
            GridRow {
                ForEach(Array(state.weekdays.enumerated()), id: \.offset) { _, weekday in
                    Text(weekday)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 4)
                        .accessibilityHidden(true)
                }
            }
            ForEach(Array(weeks.enumerated()), id: \.offset) { _, week in
                GridRow {
                    ForEach(0..<7, id: \.self) { index in
                        if let day = week[index] {
                            CalendarDayCell(day: day, focus: focus) { onDay(Int(day.day)) }
                        } else {
                            Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                        }
                    }
                }
            }
        }
    }
}

/// A day: its number (today's in a white circle) and a dot per episode, up to three. A button where it takes focus
/// (`focus`, Apple TV: every day, the remote walking the grid a day or a week at a time) or has episodes, doing
/// `action` when it has; it lights up under the pointer and, on Apple TV, turns white with focus.
private struct CalendarDayCell: View {
    let day: CalendarDay
    let focus: FocusState<CalendarFocus?>.Binding?
    let action: () -> Void

    var body: some View {
        if day.episodes > 0 || focus != nil {
            Button { if day.episodes > 0 { action() } } label: { CalendarDayLabel(day: day) }
                .buttonStyle(CalendarSurfaceStyle(shape: RoundedRectangle(cornerRadius: Radius.small, style: .continuous)))
                .focusedIfGiven(focus, equals: .day(day.day))
                .accessibilityLabel(day.accessibilityLabel)
                .accessibilityIdentifier("calendar.day.\(day.day)")
        } else {
            CalendarDayLabel(day: day)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(day.accessibilityLabel)
                .accessibilityIdentifier("calendar.day.\(day.day)")
        }
    }
}

private struct CalendarDayLabel: View {
    let day: CalendarDay

    @Environment(\.formFactor) private var formFactor
    /// On Apple TV's focused (white) surface: the colours turn around.
    @Environment(\.onFocusedSurface) private var onFocused

    var body: some View {
        let circle: CGFloat = formFactor == .tv ? 56 : 32
        VStack(spacing: 3) {
            Text(verbatim: "\(day.day)")
                .font(Self.numberFont.weight(day.today || day.episodes > 0 ? .semibold : .regular))
                .foregroundStyle(numberColor)
                .padding(.horizontal, 4)
                .frame(minWidth: circle, minHeight: circle)
                .background {
                    if day.today { Circle().fill(onFocused ? Palette.onPrimary : Palette.primary) }
                }
            HStack(spacing: 3) {
                ForEach(0..<min(Int(day.episodes), 3), id: \.self) { _ in
                    Circle().fill(onFocused ? Palette.onPrimary : Palette.primary).frame(width: 5, height: 5)
                }
            }
            .frame(height: 5)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, minHeight: formFactor == .tv ? 88 : 48)
        .contentShape(Rectangle())
    }

    private var numberColor: Color {
        if day.today { return onFocused ? Palette.primary : Palette.onPrimary }
        if onFocused { return Palette.onPrimary }
        return day.episodes > 0 ? Palette.onBackground : Palette.onSurfaceVariant
    }

    private static var numberFont: Font {
        #if os(tvOS)
        .callout
        #else
        .body
        #endif
    }
}

/// A day's heading over its episodes, on their stills' edge: today's in white, the others grey.
private struct CalendarDayHeading: View {
    let day: CalendarAgendaDay

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        Text(day.label)
            .font(Self.font)
            .foregroundStyle(day.today ? Palette.primary : Palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.leading, DetailMetrics.rowInset)
            .padding(.top, formFactor == .tv ? 24 : 16)
            .padding(.bottom, 6)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("calendar.agenda.\(day.day)")
    }

    private static var font: Font {
        #if os(tvOS)
        .headline
        #else
        .subheadline.weight(.semibold)
        #endif
    }
}

/// An episode as Detail's episode row (`EpisodeRowLabel`): its still (`DetailEpisodeThumbnail`: the episode's own,
/// blurred while `CalendarEpisode.blurred`, else its series' art), its series, its line, its description (blurred past
/// reading while the profile hides spoilers). No ⋯: a press opens its series on it, where its menu is. On iPhone, iPad
/// and the Mac the row is the button, in Detail's row style (`EpisodeRowButtonStyle`); on Apple TV the still is, a card
/// that lifts with focus as Detail's episode cards there do (`cardButtonStyle`), named by the row's texts, the texts
/// beside it.
private struct CalendarEpisodeRow: View {
    let episode: CalendarEpisode
    /// The screen's focus (Apple TV), the row `focusValue` in it.
    let focus: FocusState<CalendarFocus?>.Binding?
    let focusValue: CalendarFocus
    let action: () -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        #if os(tvOS)
        label {
            Button(action: action) { still }
                .cardButtonStyle()
                .focusedIfGiven(focus, equals: focusValue)
                .accessibilityLabel([episode.series, episode.detail, episode.hiddenOverviewLabel ?? episode.overview].compactMap { $0 }.joined(separator: ", "))
                .accessibilityIdentifier("calendar.episode.\(episode.videoId)")
                // The lifted still reached the text beside it.
                .padding(.trailing, 24)
        }
        #else
        Button(action: action) { label { still } }
            .buttonStyle(EpisodeRowButtonStyle())
            .accessibilityIdentifier("calendar.episode.\(episode.videoId)")
        #endif
    }

    private func label<Artwork: View>(@ViewBuilder _ artwork: @escaping () -> Artwork) -> some View {
        EpisodeRowLabel(title: episode.series, infoLine: episode.detail, overview: episode.overview, hiddenOverviewLabel: episode.hiddenOverviewLabel, artwork: artwork)
    }

    private var still: some View {
        DetailEpisodeThumbnail(
            url: episode.still,
            fallbackUrl: episode.art,
            blurred: episode.blurred,
            width: formFactor == .tv ? 240 : formFactor == .compact ? 128 : 176,
            radius: formFactor == .tv ? Radius.medium : DetailMetrics.thumbnailRadius
        )
    }
}

/// A day as a button in `shape`, on nothing. On Apple TV focus turns it white with dark content, as
/// `SurfaceButtonStyle` does; elsewhere the pointer over it lightens it, a press darkens it, and keyboard focus rings it.
private struct CalendarSurfaceStyle<S: Shape>: ButtonStyle {
    let shape: S

    func makeBody(configuration: Configuration) -> some View {
        Surface(configuration: configuration, shape: shape)
    }

    private struct Surface: View {
        let configuration: Configuration
        let shape: S

        @Environment(\.isFocused) private var focused
        @State private var hovered = false

        var body: some View {
            #if os(tvOS)
            configuration.label
                .foregroundStyle(focused ? Palette.onPrimary : Palette.onBackground)
                .environment(\.onFocusedSurface, focused)
                .background(focused ? Palette.primary : .clear, in: shape)
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
            #else
            configuration.label
                .foregroundStyle(Palette.onBackground)
                .background(background, in: shape)
                .contentShape(shape)
                .onHover { hovered = $0 }
                .animation(.easeOut(duration: 0.12), value: hovered)
                .focusRing(radius: Radius.small)
            #endif
        }

        #if !os(tvOS)
        private var background: Color {
            if configuration.isPressed { return Palette.surfaceContainerHighest }
            return hovered ? Palette.surfaceContainerHigh : .clear
        }
        #endif
    }
}

private extension View {
    /// `focused(focus, equals: value)` where the screen tracks focus (Apple TV).
    @ViewBuilder
    func focusedIfGiven(_ focus: FocusState<CalendarFocus?>.Binding?, equals value: CalendarFocus) -> some View {
        if let focus { focused(focus, equals: value) } else { self }
    }
}
