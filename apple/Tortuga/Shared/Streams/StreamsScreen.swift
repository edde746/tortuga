import SwiftUI
import TortugaKit

/// The streams of one video, grouped by addon in the installed order, or sorted by the profile's sort as
/// one list without addon headings. Every text comes from `StreamsUiState`; the screen only lays it out and reports
/// `onPick` (a row id), `onReload` (a failed addon's retry), `onToggleFiltered` (the filter bar's Show / Hide),
/// `onDownload` (a row's Download), `onPlayWith` (a row and the player picked in its Play with…), `onSelectAddon` (the
/// row of addons, `StreamsBar`), `onRefresh` (Refresh), `onToggleSort` (the sort's options) and, in a sheet,
/// `onClose`. No row has a fill of its own and no heading a band behind it: the sheet's glass (the Mac's
/// sheet, the Apple TV panel) shows through. iOS: a plain list under the title and the episode line in the bar; macOS:
/// a native inset list; on both a section per addon, headed by its name as a plain row that scrolls with its streams.
/// tvOS: a column of rows that turn white with focus. A row's text takes its whole width. Rows are numbered across the
/// groups, as the Compose probes are (`streams.row.<n>`). On top, why the list opened instead of a stream playing, or
/// that a pick is downloaded (`streams.notice`), then, while two or more addons have a chip, the row of their chips
/// (`streams.bar`): on iOS and macOS both stay over the scrolling list (and over the spinner or the empty text), on
/// tvOS they scroll with the panel's heading. With neither, the list starts right under the bar or the heading.
/// Refresh (`streams.refresh`), which asks every addon again, is in the top trailing corner, level with the title, the
/// sort button (`StreamsSortButton`, `streams.sort`) just before it: at the trailing end of iOS's bar
/// (`actionsInBar`), of the Mac sheet's heading and of the Mac's toolbar on the screen on its own, and in the Apple TV
/// panel's heading (`headingLine`). With an addon's chip selected the list holds that addon's groups alone, without
/// its name over them. Then how many streams the profile's filters hide
/// (`streams.filter`) with Show / Hide (`streams.filter.toggle`), above the first group so the remote's Up reaches it
/// from the first stream; each group's heading (`GroupHeader`; none over the sorted list's rows, `StreamGroup.sorted`);
/// under each group, what it leaves out
/// (`streams.group.<i>.filtered`: the streams the filters hide). Where the device has downloads, a playable row's
/// context menu (a long press, a right click) downloads it (`streams.row.<n>.download`), as the Mac's download button
/// at the row's trailing end does, and what came of it shows as a toast (`streams.downloadMessage`). Where the device
/// has player apps, a row that plays has Play with… in its context menu (on tvOS a long press of Select), which plays
/// it once in Tortuga or a player app (`streams.menu.playWith.<player id>`).
struct StreamsScreen: View {
    let state: StreamsUiState
    let onPick: (String) -> Void
    let onReload: () -> Void
    let onToggleFiltered: () -> Void
    let onDownload: (String) -> Void
    let onPlayWith: (_ id: String, _ playerId: String) -> Void
    let onDismissDownloadMessage: () -> Void
    let onSelectAddon: (String?) -> Void
    let onRefresh: () -> Void
    let onToggleSort: (String) -> Void
    var onClose: (() -> Void)?

    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.formFactor) private var formFactor
    #if os(tvOS)
    /// The focused stream row, the filter bar's Show / Hide, a Retry, Refresh or a chip of the row of addons. The first
    /// stream (else Show, when the filters hide every stream; else Refresh) takes focus once listed: the panel over
    /// Detail leaves the screen under it focusable, so focus doesn't move into the panel by itself.
    @FocusState private var focus: StreamsFocus?
    /// A stream row or Show has had focus: from then on Refresh and the row of addons keep focus from the first stream.
    @State private var listHadFocus = false
    /// The user's moves (`FocusRestore.userMoves`) as the list was made: once the user has moved focus over the list
    /// (Refresh, the row of addons), the first stream listed doesn't take it from there either.
    @State private var shownMoves = FocusRestore.userMoves
    /// The first stream as the list focused it itself (or asked for it while nothing had focus), until focus moves: an
    /// addon answering after another lists its group above, and this focus moves on to the new first stream
    /// (`firstFocus`) instead of staying on a row now further down.
    @State private var placed: StreamsFocus?
    /// The row the user moved focus to, as the column dropped it: streams listed above it pushed it out of view, and
    /// focus went with it.
    @State private var dropped: String?
    /// The group whose Retry was pressed (`StreamGroup.id`), until focus has gone back once its addon answered
    /// (`afterRetry`).
    @State private var retried: String?
    /// The user's moves (`FocusRestore.userMoves`) as that Retry was pressed: a move since leaves focus where the user
    /// put it.
    @State private var retriedMoves = 0
    #endif

    var body: some View {
        headed
            .navigationTitle(state.title)
            .inlineNavigationTitle()
            #if os(macOS)
            .toolbarTitleHidden(onClose != nil)
            #endif
            #if !os(tvOS)
            .toolbar {
                #if os(iOS)
                ToolbarItem(placement: .principal) {
                    heading(spacing: 0, titleLines: 1, alignment: .center, titleFont: .headline, subtitleFont: .footnote)
                        .lineLimit(1)
                }
                #endif
                if let onClose, formFactor != .compact {
                    ToolbarItem(placement: .cancellationAction) { SheetCloseButton(id: "streams.close", action: onClose) }
                }
            }
            #endif
            #if os(iOS)
            .modifier(EscapeCloses(onClose: onClose))
            .actionsInBar(onRefresh, sort: state.sort, onSort: onToggleSort)
            #elseif os(macOS)
            .actionsInBar(onClose == nil ? onRefresh : nil, sort: state.sort, onSort: onToggleSort)
            #endif
            .toast(state.downloadMessage, id: "streams.downloadMessage", onShown: onDismissDownloadMessage)
    }

    /// The content under what stays over it. iOS and macOS: in the Mac's sheet, whose toolbar leaves its title out, the
    /// title and the episode line with the sort button and Refresh at their trailing end, then the notice and the row
    /// of addons (`top`). The Apple TV panel, which has no bar: the heading with them, the notice and the row over the
    /// spinner or the empty text; the panel's list carries them at its top, so they scroll away with the rows instead
    /// of sitting on a band over them.
    @ViewBuilder
    private var headed: some View {
        #if os(tvOS)
        if case .groups = onEnum(of: state.body) {
            content
        } else {
            VStack(alignment: .leading, spacing: 0) {
                headingLine
                if let notice = state.notice { NoticeLine(text: notice, downloadMode: state.downloadMode).padding(.top, 8) }
                if !state.addons.isEmpty { bar.padding(.vertical, 8) }
                content.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        #else
        VStack(alignment: .leading, spacing: 0) {
            #if os(macOS)
            if onClose != nil {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    heading(spacing: 2)
                    Spacer(minLength: 0)
                    SortAndRefresh(sort: state.sort, onSort: onToggleSort, onRefresh: onRefresh)
                        .levelWithTitle()
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 4)
            }
            #endif
            top
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        #endif
    }

    #if !os(tvOS)
    /// What stays over the list (and over the spinner or the empty text) while it scrolls: on the Mac's screen on its
    /// own the episode line (iOS shows it in the bar, the Mac's sheet in its heading), the notice, then the row of
    /// addons from side to side, its first chip where the text starts. With none of them nothing is there, and the list
    /// starts right under the bar or the heading.
    @ViewBuilder
    private var top: some View {
        let subtitle = subtitleOverList
        if subtitle != nil || state.notice != nil || !state.addons.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                if let subtitle { subtitleText(subtitle).padding(.horizontal, Self.listMargin) }
                if let notice = state.notice {
                    NoticeLine(text: notice, downloadMode: state.downloadMode).padding(.horizontal, Self.listMargin)
                }
                if !state.addons.isEmpty {
                    StreamsBar(addons: state.addons, selected: state.selectedAddon, onSelect: onSelectAddon, margin: Self.listMargin)
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 4)
            .readableColumn()
        }
    }

    /// The episode line over the list: on the Mac's screen on its own, whose toolbar has the title alone.
    private var subtitleOverList: String? {
        #if os(macOS)
        onClose == nil ? state.subtitle : nil
        #else
        nil
        #endif
    }
    #endif

    /// The title (`streams.title`) and under it the episode line (`streams.subtitle`), muted; a movie's title alone. The
    /// Mac's sheet and the Apple TV panel head the list with it (`headed`, `headingLine`), the title on at most
    /// `titleLines` lines; iOS's bar centres it in its smaller fonts, one line each, cut short when long.
    private func heading(
        spacing: CGFloat,
        titleLines: Int? = nil,
        alignment: HorizontalAlignment = .leading,
        titleFont: Font = .title3.weight(.semibold),
        subtitleFont: Font = .subheadline
    ) -> some View {
        VStack(alignment: alignment, spacing: spacing) {
            Text(state.title)
                .font(titleFont)
                .lineLimit(titleLines)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("streams.title")
            if let subtitle = state.subtitle { subtitleText(subtitle, font: subtitleFont) }
        }
    }

    #if os(tvOS)
    /// The panel's heading, a long title wrapping once, with the sort button (`StreamsSortButton`) and Refresh
    /// (`tvRefresh`) in its top trailing corner, level with the title; on the screen on its own, whose bar has the title,
    /// the episode line in the heading's place. A focus section from side to side that focus enters on Refresh, so Up
    /// from the row of addons, or from the list's top without one, reaches Refresh, Left from it the sort button, and
    /// Down from either enters the row (else the list).
    private var headingLine: some View {
        HStack(alignment: .firstTextBaseline, spacing: 24) {
            if onClose != nil {
                heading(spacing: 8, titleLines: 2)
            } else if let subtitle = state.subtitle {
                subtitleText(subtitle)
            }
            Spacer(minLength: 0)
            if let sort = state.sort {
                StreamsSortButton(menu: sort, onPick: onToggleSort, focus: $focus).levelWithTitle()
            }
            tvRefresh.levelWithTitle()
        }
        .padding(.bottom, 8)
        .focusSection()
        .defaultFocus($focus, .refresh, priority: .userInitiated)
    }

    /// Refresh (`streams.refresh`): a round button as tall as the row's chips, 80 pt, its symbol 40 pt (the Android TV
    /// panel's at 1 pt per px of its 1920 × 1080 screen), on the chips' grey, white with black symbol while it has focus
    /// (`SurfaceButtonStyle`). Focus stays on it while the addons are asked again.
    private var tvRefresh: some View {
        Button(action: onRefresh) {
            Image(systemName: "arrow.clockwise")
                .resizable()
                .scaledToFit()
                .frame(width: 40, height: 40)
                .frame(width: 80, height: 80)
                .contentShape(Circle())
        }
        .buttonStyle(SurfaceButtonStyle(fill: Palette.surfaceContainer, radius: 40))
        .focused($focus, equals: .refresh)
        .accessibilityLabel(T.shared.streams.refresh)
        .accessibilityIdentifier("streams.refresh")
    }
    #endif

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: state.body) {
        case .loading:
            LoadingStateView()
        case .empty(let empty):
            EmptyStateView(message: empty.text)
                .accessibilityIdentifier("streams.empty")
        case .groups:
            #if os(tvOS)
            tvList
            #else
            list
            #endif
        }
    }

    #if !os(tvOS)
    /// A plain list (the Mac: a native inset one) with no fill behind its rows, so the sheet's own glass shows through:
    /// each addon's heading tops its streams as a plain row that scrolls with them, standing off the rows above it (with
    /// an addon's chip selected and its group listed, the gap alone when the heading has nothing to show). Sorted, the
    /// answered addons' rows are one group without a heading (`StreamGroup.sorted`), and another sort lists them from the
    /// top: a list of its own.
    private var list: some View {
        let named = state.namesGroups
        return List {
            if let filter = state.filter { filterBar(filter).streamsListRow(top: 4, bottom: 4).listRowSeparator(.hidden) }
            ForEach(Array(state.groups.enumerated()), id: \.element.id) { groupIndex, group in
                let first = Int(state.firstRow(groupIndex: Int32(groupIndex)))
                let headed = state.headed(group: group)
                Section {
                    // A row rather than the section's header: a plain list pins its headers, over the rows scrolling
                    // under them.
                    if headed {
                        GroupHeader(group: group, index: groupIndex, named: named, onReload: onReload)
                            #if os(macOS)
                            .padding(.top, groupIndex == 0 ? 0 : 12)
                            #endif
                            .streamsListRow(top: groupIndex == 0 ? 12 : 32, bottom: 2)
                            .listRowSeparator(.hidden)
                    }
                    ForEach(Array(group.streams.enumerated()), id: \.element.id) { offset, row in
                        StreamListRow(row: row, number: first + offset, playWith: state.playWith, onPick: onPick, onDownload: onDownload, onPlayWith: onPlayWith)
                            .streamsListRow(top: offset == 0 && !headed && groupIndex > 0 ? 32 : 10)
                    }
                    if group.unplayableNote != nil || group.filteredNote != nil {
                        GroupNotes(group: group, index: groupIndex)
                            .streamsListRow(top: 2)
                            .listRowSeparator(.hidden)
                    }
                }
                .listSectionSeparator(.hidden)
            }
        }
        #if os(iOS)
        .listStyle(.plain)
        // The list's top margin would leave a gap under the row of addons, and its least row height pads the one-line
        // rows (the filter bar, an addon's name) past their insets.
        .contentMargins(.top, 0, for: .scrollContent)
        .environment(\.defaultMinListRowHeight, 0)
        #else
        .listStyle(.inset)
        #endif
        .scrollContentBackground(.hidden)
        .readableColumn()
        .id(state.sort?.label)
    }

    /// The side margin of iOS's plain list, its rows, its notes and its addons' names, and on iOS and macOS of the notice
    /// and the row of addons over the list.
    static let listMargin: CGFloat = 20
    #endif

    #if os(tvOS)
    /// A column of rows with no fill of their own on the panel, each turning white with focus, its text in line with
    /// the addons' names and the focused surface reaching past it. The heading with the sort button and Refresh
    /// (`headingLine`) tops the column and scrolls away with it, the row of addons under it; after a new sort, picked
    /// from the button there, the column shows its top. The streams under them are a focus section that Down from the
    /// row, or from the heading's buttons without one, enters on the first stream (else Show).
    private var tvList: some View {
        let named = state.namesGroups
        return ScrollViewReader { column in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    headingLine
                    VStack(alignment: .leading, spacing: 16) {
                        if let notice = state.notice { NoticeLine(text: notice, downloadMode: state.downloadMode) }
                        if !state.addons.isEmpty { bar }
                    }
                    .padding(.top, 8)
                    LazyVStack(alignment: .leading, spacing: dimens.gutter / 2) {
                        if let filter = state.filter { filterBar(filter) }
                        ForEach(Array(state.groups.enumerated()), id: \.element.id) { groupIndex, group in
                            let first = Int(state.firstRow(groupIndex: Int32(groupIndex)))
                            let headed = state.headed(group: group)
                            // The panel's heading is the first group's break; a later group's header (without one, its
                            // first row) stands off the rows above.
                            if headed {
                                // Recorded as Retry is pressed, before the addon is asked: its answer can come before
                                // the Retry goes.
                                GroupHeader(group: group, index: groupIndex, named: named, onReload: {
                                    retried = group.id
                                    retriedMoves = FocusRestore.userMoves
                                    onReload()
                                }, focus: $focus)
                                    .padding(.top, groupIndex == 0 ? 0 : 16)
                            }
                            ForEach(Array(group.streams.enumerated()), id: \.element.id) { offset, row in
                                Button { onPick(row.id) } label: {
                                    StreamRowLabel(row: row).padding(Self.tvRowPadding)
                                }
                                .buttonStyle(SurfaceButtonStyle(fill: .clear, radius: Radius.large))
                                .padding(.horizontal, -Self.tvRowPadding)
                                .padding(.top, offset == 0 && !headed && groupIndex > 0 ? 16 : 0)
                                .focused($focus, equals: .row(row.id))
                                .onDisappear { if userMoved, focus == .row(row.id) { dropped = row.id } }
                                .accessibilityIdentifier("streams.row.\(first + offset)")
                                .streamRowMenu(menuEntries(row))
                            }
                            if group.unplayableNote != nil || group.filteredNote != nil {
                                GroupNotes(group: group, index: groupIndex)
                            }
                        }
                    }
                    .padding(.vertical, dimens.focusRoom)
                    .focusSection()
                    .defaultFocus($focus, firstFocus, priority: .userInitiated)
                }
                .frame(maxWidth: dimens.contentMaxWidth)
                .frame(maxWidth: .infinity)
            }
            .scrollClipDisabled()
            // Until the list has had focus, Refresh and the row's chips don't keep it from the first stream: the panel
            // can open on Refresh while the addons answer, unless the user has moved focus there. The first stream
            // focused that way follows the first stream while groups come above it (`placed`). Once the user moved
            // focus, a new first stream, a chip picked or a refresh leaves it where it is.
            .task(id: firstFocus) {
                guard let firstFocus else {
                    await FocusRestore.restore($focus, to: .refresh)
                    return
                }
                let barUnmoved = !listHadFocus && focus?.overList == true && FocusRestore.userMoves == shownMoves
                if barUnmoved || (placed != nil && focus == placed && placed != firstFocus) {
                    place(firstFocus)
                }
                if !userMoved {
                    // Recorded as it is asked for: an addon listed above can answer before the focus lands, and the
                    // change would then read as the user's, a row that is no longer the first (23 Oct: Tortuga Second
                    // answered a moment before the test addon, and focus stayed on Second's first stream).
                    if focus == nil { placed = firstFocus }
                    await FocusRestore.restore($focus, to: firstFocus)
                }
            }
            .onChange(of: focus) { old, new in
                // The focus engine gives focus whose row the column dropped to the first stream: back to that row.
                if old == nil, let dropped, let new, new != .row(dropped) {
                    focus = .row(dropped)
                    return
                }
                if new?.overList == false { listHadFocus = true }
                if old == nil, let new, new == firstFocus {
                    placed = new
                } else if new != placed {
                    placed = nil
                }
                // The system gives the panel its own first focus once it has shown, on its first element, Refresh: until
                // the list has had focus, the first stream takes it.
                if old == nil, new?.overList == true, !listHadFocus, let firstFocus { place(firstFocus) }
            }
            .task(id: afterRetry) {
                guard let target = afterRetry else { return }
                // The element can take a frame or two to attach; a move of the user's since Retry was pressed ends it.
                // A newer target (the task asked again) ends it too, and leaves `retried` to that one.
                await FocusRestore.restore($focus, to: target, since: retriedMoves, replacing: true)
                if !Task.isCancelled { retried = nil }
            }
            // The column builds its rows lazily: streams listed above the row the user moved focus to push it out of
            // view, and the column drops it, focus with it. The row comes back into view and takes focus again; a row
            // dropped meanwhile (the task asked again) is left to its own.
            .task(id: dropped) {
                guard let dropped else { return }
                column.scrollTo(dropped)
                await FocusRestore.restore($focus, to: .row(dropped))
                if !Task.isCancelled { self.dropped = nil }
            }
        }
    }

    /// The row of addons (`StreamsBar`); in the panel it reaches across the panel's padding to its leading edge and
    /// across the screen's trailing safe area, which the panel's surface fills, to the screen's edge.
    private var bar: some View {
        StreamsBar(
            addons: state.addons,
            selected: state.selectedAddon,
            onSelect: onSelectAddon,
            reach: onClose != nil ? (TrailingPanelInsets.leading, TrailingPanelInsets.trailing) : (0, 0),
            focus: $focus
        )
    }

    /// A row's padding around its text: the focused surface's reach.
    private static let tvRowPadding: CGFloat = 24

    /// What takes focus first: the first stream listed, else Show when the filters hide every stream.
    private var firstFocus: StreamsFocus? {
        if let id = state.firstStream { return .row(id) }
        return state.filter != nil ? .filter : nil
    }

    /// Focus to `target`, the first stream, as the list places it itself (`placed`).
    private func place(_ target: StreamsFocus) {
        placed = target
        focus = target
    }

    /// The user moved focus: the list has had it, and it isn't where the list placed it (`placed`).
    private var userMoved: Bool { listHadFocus && placed == nil }

    /// Where focus goes back once the addon whose Retry was pressed (`retried`) has answered, and focus has left that
    /// Retry (it goes while the addon is asked; an answer quick enough leaves it, focus on it): its Retry when it failed
    /// again, else its first stream, else the list's (also when its answer had nothing to show and its group left the
    /// list); nil while the addon is asked or focus is still on its Retry.
    private var afterRetry: StreamsFocus? {
        guard let retried, focus != .retry(retried) else { return nil }
        guard let group = state.groups.first(where: { $0.id == retried }) else { return firstFocus }
        switch onEnum(of: group.status) {
        case .loading: return nil
        case .failed: return .retry(retried)
        case .ready: return group.streams.first.map { .row($0.id) } ?? firstFocus
        }
    }

    /// A row's context menu: Play with… (a row that plays, where the device has player apps). The Apple TV has no
    /// downloads, so nothing else.
    private func menuEntries(_ row: StreamRow) -> [ItemMenuEntry] {
        guard let playWith = state.playWith, !row.external else { return [] }
        return [.playWith(playWith, id: "streams.menu.playWith") { onPlayWith(row.id, $0) }]
    }
    #endif

    /// How many streams the filters hide (or show), and Show / Hide: a borderless button in the list's row (iOS,
    /// macOS), a pill that turns white with focus (tvOS), one height with the line beside it.
    private func filterBar(_ filter: StreamFilterBar) -> some View {
        HStack(spacing: 12) {
            Label {
                Text(filter.text)
            } icon: {
                Image(systemName: "line.3.horizontal.decrease.circle").accessibilityHidden(true)
            }
            .font(.subheadline)
            .foregroundStyle(Palette.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("streams.filter")
            Button(action: onToggleFiltered) {
                Text(filter.actionLabel).hitTargetLabel()
            }
                #if os(tvOS)
                .pillButtonStyle()
                .focused($focus, equals: .filter)
                #else
                .buttonStyle(.borderless)
                #endif
                .accessibilityIdentifier("streams.filter.toggle")
                .hitTarget()
        }
    }

    private func subtitleText(_ subtitle: String, font: Font = .subheadline) -> some View {
        Text(subtitle)
            .font(font)
            .foregroundStyle(Palette.onSurfaceVariant)
            .accessibilityIdentifier("streams.subtitle")
    }
}

#if os(iOS)
/// In the sheet, VoiceOver's escape (a two-finger scrub) closes it: the iPhone sheet's only explicit close, as it has
/// no close button. A pushed Streams screen keeps the navigation's own escape (back).
private struct EscapeCloses: ViewModifier {
    let onClose: (() -> Void)?

    func body(content: Content) -> some View {
        if let onClose {
            content
                .accessibilityElement(children: .contain)
                .accessibilityAction(.escape, onClose)
        } else {
            content
        }
    }
}
#endif

#if os(tvOS)
/// What has focus in the Streams list: a stream's row (by its id), the filter bar's Show / Hide, a failed group's Retry
/// (by `StreamGroup.id`), the sort button, Refresh, or in the row of addons All or an addon's chip (by
/// `StreamsAddon.id`).
enum StreamsFocus: Hashable {
    case row(String)
    case filter
    case retry(String)
    case sort
    case refresh
    case all
    case addon(String)

    /// The sort button, Refresh or a chip of the row of addons: over the list.
    var overList: Bool {
        switch self {
        case .row, .filter, .retry: false
        case .sort, .refresh, .all, .addon: true
        }
    }
}
#endif
