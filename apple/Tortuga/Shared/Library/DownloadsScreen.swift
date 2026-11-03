#if !os(tvOS)
import SwiftUI
import TortugaKit

/// Library's Downloads section (iPhone, iPad, the Mac; Apple TV has no downloads): the screen's header row
/// (`screenHeader`, staying at the top while the list scrolls) with the view menu (`LibraryViewMenu`) and Pause all and
/// Resume all, then the profile's downloads on this device as a tight list, each series under its poster, name and
/// summary (its episodes by season, then its videos without an episode number under "Other videos"), each movie as one
/// row, newest first. A row is its artwork (saved on the device: a poster, an episode's still) with a few points above
/// and below, beside it the title, the status, the bytes (with the speed while it downloads), a progress bar and why it
/// failed; a tap plays a completed download (else opens its title, or the Streams sheet for an episode no stream was
/// picked for), and its actions (pause, resume, retry, cancel, delete, go to the title) are in its ⋯, its context menu
/// (a long press, a right click) and, on iOS, its swipes; where a player app can open the files (the Mac), a completed
/// one's Play with… plays it once in the player picked. A series' downloads go from its ⋯ or a swipe on its heading once
/// the store's question (an alert: `DownloadsUiState.deleteDialog`) is answered. Under the list, the space the downloads
/// take and the space left. Pause all and Resume all are the row's actions: the bar's buttons on iPad, each its icon and
/// title in a glass capsule at the row's trailing end on iPhone and the Mac. The view menu, the artwork, the season and
/// videos headings and the storage line start at the screen's leading edge. Offline, a note says the downloads wait for
/// a connection. While the profile has Keep synced rules, a row above the downloads (above the empty state too) leads
/// to their screen (`DownloadsUiState.keepSynced`: Keep synced's symbol, "Keep synced", "2 series" and a chevron,
/// filled as Settings' rows are; `DownloadsStore.openKeepSynced`). An action that failed (`DownloadsUiState.notice`)
/// shows as a toast, then `DownloadsStore.dismissNotice`. Every text comes from `DownloadsUiState`.
///
/// Accessibility identifiers: `downloads.row.<id>` with `downloads.row.<id>.menu`, the actions `downloads.action.<action>`
/// (`play`, `pause`, `resume`, `retry`, `pickStream`, `cancel`, `delete`, `openDetail`) and Play with…'s players
/// `downloads.action.playWith.<player id>`, `downloads.group.<key>` with `downloads.group.<key>.delete` and the
/// question's `downloads.deleteGroup.<confirm|cancel>`, `downloads.season.<key>.<season>`, `downloads.videos.<key>`
/// (the heading over a series' videos without an episode number), `downloads.pauseAll`, `downloads.resumeAll`,
/// `downloads.keepSynced`, `downloads.storage`, `downloads.empty`, `downloads.offline`, `downloads.notice`.
struct DownloadsScreen: View {
    let state: DownloadsUiState
    /// The Library's views' names, and the one shown (Downloads).
    let views: [String]
    let selectedView: Int
    let onSelectView: (Int) -> Void
    let store: DownloadsStore

    @Environment(\.screenSides) private var sides

    var body: some View {
        Group {
            switch onEnum(of: state.body) {
            case .loading:
                LoadingStateView()
            case .empty(let empty):
                emptyContent(empty.text)
            case .groups(let shown):
                DownloadsList(state: state, groups: shown.groups, store: store)
            }
        }
        .toast(state.notice, id: "downloads.notice", onShown: store.dismissNotice)
        .confirmation(
            state.deleteDialog.map { Confirmation($0, id: "downloads.deleteGroup") },
            onConfirm: store.confirmDeleteGroup,
            onCancel: store.dismissDeleteGroup
        )
        .screenHeader(titledActions: true) {
            LibraryViewMenu(views: views, selected: selectedView, onSelect: onSelectView)
        } actions: {
            if let pauseAll = state.pauseAllText {
                bulkButton(pauseAll, systemImage: "pause.circle", id: "downloads.pauseAll", action: store.pauseAll)
            }
            if let resumeAll = state.resumeAllText {
                bulkButton(resumeAll, systemImage: "arrow.down.circle", id: "downloads.resumeAll", action: store.resumeAll)
            }
        }
        // The space left changes outside the app too: read again whenever the section shows.
        .onAppear { store.refreshStorage() }
    }

    /// Pause all or Resume all.
    private func bulkButton(_ title: String, systemImage: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
        }
        .accessibilityLabel(title)
        .accessibilityIdentifier(id)
    }

    /// The empty state with the offline note and the row to Keep synced above it.
    private func emptyContent(_ empty: String) -> some View {
        VStack(spacing: 0) {
            if let note = state.offlineNote {
                DownloadsOfflineNote(text: note).padding(.top, 8)
            }
            if let link = state.keepSynced {
                KeepSyncedLinkRow(link: link, onOpen: store.openKeepSynced)
                    .padding(.leading, sides.leading)
                    .padding(.trailing, sides.trailing)
                    .padding(.top, 8)
            }
            EmptyStateView(message: empty, systemImage: "arrow.down.circle")
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("downloads.empty")
        }
    }
}

/// The downloads, grouped by title, under the offline note, the storage line under them.
private struct DownloadsList: View {
    let state: DownloadsUiState
    let groups: [DownloadGroup]
    let store: DownloadsStore

    @Environment(\.screenSides) private var sides

    var body: some View {
        List {
            if let note = state.offlineNote {
                DownloadsOfflineNote(text: note).downloadsListRow(sides, top: 4)
            }
            if let link = state.keepSynced {
                KeepSyncedLinkRow(link: link, onOpen: store.openKeepSynced).downloadsListRow(sides, top: 4, bottom: 8)
            }
            ForEach(groups, id: \.key) { group in
                Section {
                    if let movie = group.movie {
                        DownloadRowView(row: movie, poster: true, playWith: state.playWith, store: store).downloadsListRow(sides)
                    } else {
                        DownloadGroupHeading(group: group, onDelete: { store.deleteGroup(key: group.key) })
                            .downloadsListRow(sides, top: 10)
                        ForEach(group.seasons, id: \.season) { season in
                            Text(season.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Palette.onSurfaceVariant)
                                .accessibilityAddTraits(.isHeader)
                                .accessibilityIdentifier("downloads.season.\(group.key).\(season.season)")
                                .downloadsListRow(sides, top: 8, bottom: 2)
                            ForEach(season.episodes, id: \.id) { row in
                                DownloadRowView(row: row, poster: false, playWith: state.playWith, store: store).downloadsListRow(sides)
                            }
                        }
                        if !group.videos.isEmpty {
                            Text(group.videosTitle)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Palette.onSurfaceVariant)
                                .accessibilityAddTraits(.isHeader)
                                .accessibilityIdentifier("downloads.videos.\(group.key)")
                                .downloadsListRow(sides, top: 8, bottom: 2)
                        }
                        ForEach(group.videos, id: \.id) { row in
                            DownloadRowView(row: row, poster: false, playWith: state.playWith, store: store).downloadsListRow(sides)
                        }
                    }
                }
                .listSectionSeparator(.hidden)
            }
            IconLine(text: state.storage, systemImage: "internaldrive")
                .font(.footnote)
                .foregroundStyle(Palette.onSurfaceVariant)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("downloads.storage")
                .downloadsListRow(sides, top: 12, bottom: 24)
        }
        .listStyle(.plain)
        #if os(iOS)
        .contentMargins(.top, 0, for: .scrollContent)
        #endif
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 0)
    }
}

private extension View {
    /// A row of the downloads' list: no fill and no separator of its own, `top` and `bottom` pt above and below its
    /// content, which starts and ends on the page's sides (`screenSides`), as the filter row above it does.
    func downloadsListRow(_ sides: ScreenSides, top: CGFloat = 4, bottom: CGFloat = 4) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: sides.leading - listRowMargin, bottom: bottom, trailing: sides.trailing - listRowMargin))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

/// What a plain list adds beside its rows' insets: 8 pt each side on the Mac (its table's cell margin), none on iOS.
#if os(macOS)
private let listRowMargin: CGFloat = 8
#else
private let listRowMargin: CGFloat = 0
#endif

/// A symbol and a line of text close beside it: a list row's `Label` would put the symbol in a column of its own, far
/// from the text.
private struct IconLine: View {
    let text: String
    let systemImage: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: systemImage).accessibilityHidden(true)
            Text(text)
        }
    }
}

/// "You're offline. Downloads continue once you're back online.", a muted line with the offline symbol.
private struct DownloadsOfflineNote: View {
    let text: String

    var body: some View {
        IconLine(text: text, systemImage: "wifi.slash")
            .font(.footnote.weight(.medium))
            .foregroundStyle(Palette.onSurfaceVariant)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Palette.surfaceContainerHigh, in: Capsule())
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("downloads.offline")
    }
}

/// The row to the Keep synced screen (`downloads.keepSynced`): Keep synced's symbol, its title, the rules' count ("2
/// series") and a chevron, laid out as Settings' link rows are (`SettingsControlRow`) and filled as Settings' rows are.
private struct KeepSyncedLinkRow: View {
    let link: KeepSyncedLink
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            SettingsControlRow(title: link.title, subtitle: nil, systemImage: keepSyncedSymbol, spacing: 12) {
                Text(link.value)
                    .multilineTextAlignment(.trailing)
                    .foregroundStyle(.secondary)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
        }
        .buttonStyle(PressDimButtonStyle())
        .accessibilityIdentifier("downloads.keepSynced")
    }
}

/// A series' heading over its episodes: its poster, name and summary ("3 episodes · 4.2 GB"), and its ⋯ (also its
/// context menu and, on iOS, a swipe) to delete its downloads, which the store asks about first.
private struct DownloadGroupHeading: View {
    let group: DownloadGroup
    let onDelete: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let entries = [
            ItemMenuEntry(label: group.deleteText, systemImage: "trash", id: "downloads.group.\(group.key).delete", destructive: true, action: onDelete),
        ]
        // At the accessibility text sizes the name and summary go under the poster, whole on as many lines as they take.
        let stacked = dynamicTypeSize.isAccessibilitySize
        HStack(spacing: 12) {
            (stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 12))) {
                DownloadArtwork(url: group.poster, poster: true, width: DownloadArtwork.posterWidth)
                VStack(alignment: .leading, spacing: 2) {
                    Text(group.title)
                        .font(.headline)
                        .lineLimit(stacked ? nil : 2)
                    if let summary = group.summary {
                        Text(summary)
                            .font(.footnote)
                            .foregroundStyle(Palette.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("downloads.group.\(group.key)")
            }
            SteadyMenuButton(key: [group.key, group.deleteText], label: group.moreLabel, entries: entries, probe: "downloads.group.\(group.key)")
                .equatable()
                .accessibilityIdentifier("downloads.group.\(group.key).menu")
        }
        .contentShape(Rectangle())
        .itemMenu(entries: entries, probe: "downloads.group.\(group.key)")
        #if os(iOS)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            // Red, not the destructive role: a list takes that role's row away at once, before the question is answered.
            Button(action: onDelete) {
                Label(group.deleteText, systemImage: "trash")
            }
            .tint(Palette.error)
        }
        #endif
    }
}

/// One download, a row as tall as its artwork (a movie's poster, an episode's 16:9 still) with a few points above and
/// below: beside the artwork its title (an episode's code over it), its status and its bytes with the speed while it
/// downloads (one line on wide rows, two on iPhone's), a progress bar while it isn't complete, and why it failed. Past
/// the default text size the title and the status take as many lines as they need, never cut short; at the
/// accessibility sizes the text goes under the artwork. A tap is the store's `open`; its actions are in its ⋯, its
/// context menu and its swipes (iOS: the ones that remove at the trailing edge, the first other one at the leading
/// edge), in the store's order, Play with… a submenu of `playWith`'s players.
private struct DownloadRowView: View {
    let row: DownloadRow
    /// The row is a movie's: its artwork is a poster.
    let poster: Bool
    /// The players a completed download plays in once; nil where none can open the files.
    let playWith: PlayWithMenu?
    let store: DownloadsStore
    @Environment(\.formFactor) private var formFactor
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let entries = menuEntries
        // Stacked, the text takes the row's whole width under the artwork, the ⋯ over its top trailing corner, on the
        // artwork's line. One layout or the other, never another view, so the row keeps its identity as the size moves.
        (stacked ? AnyLayout(ZStackLayout(alignment: .topTrailing)) : AnyLayout(HStackLayout(spacing: 0))) {
            Button { store.open(id: row.id) } label: { label }
                .buttonStyle(.plain)
                .accessibilityIdentifier("downloads.row.\(row.id)")
                .itemMenu(entries: entries, probe: "downloads.action")
            SteadyMenuButton(key: [row.id, row.actions, playWith], label: row.moreLabel, entries: entries, probe: "downloads.action")
                .equatable()
                .accessibilityIdentifier("downloads.row.\(row.id).menu")
        }
        #if os(iOS)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            ForEach(row.actions.filter(\.destructive), id: \.action) { item in
                Button(role: .destructive) { store.perform(id: row.id, action: item.action) } label: {
                    Label(item.label, systemImage: item.action.systemImage)
                }
            }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if let item = row.actions.first(where: { !$0.destructive && $0.action.isTransfer }) {
                Button { store.perform(id: row.id, action: item.action) } label: {
                    Label(item.label, systemImage: item.action.systemImage)
                }
                .tint(Palette.surfaceContainerHighest)
            }
        }
        #endif
    }

    /// The row's actions in the store's order, Play with… (`DownloadAction.playWith`) a submenu of the players.
    private var menuEntries: [ItemMenuEntry] {
        row.actions.compactMap { item -> ItemMenuEntry? in
            let id = "downloads.action.\(item.action.id)"
            guard item.action != .playWith else {
                guard let playWith else { return nil }
                return .playWith(playWith, id: id) { store.playWith(id: row.id, playerId: $0) }
            }
            return ItemMenuEntry(
                label: item.label,
                systemImage: item.action.systemImage,
                id: id,
                destructive: item.destructive
            ) {
                store.perform(id: row.id, action: item.action)
            }
        }
    }

    /// At the accessibility text sizes the text goes under the artwork (`stacked`), whole on as many lines as it takes.
    private var stacked: Bool { dynamicTypeSize.isAccessibilitySize }

    /// Past the default text size the title and the status wrap: one line cut them short on iPhone's narrow rows at the
    /// larger sizes under the accessibility ones (iOS 27.0).
    private var wraps: Bool { dynamicTypeSize > .large }

    private var label: some View {
        (stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 12))) {
            DownloadArtwork(url: row.artwork, poster: poster, width: poster ? DownloadArtwork.posterWidth : DownloadArtwork.thumbnailWidth)
            VStack(alignment: .leading, spacing: 2) {
                if let subtitle = row.subtitle {
                    Text(subtitle)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Palette.onSurfaceVariant)
                }
                Text(row.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.onBackground)
                    .lineLimit(wraps ? nil : 1)
                statusLines
                if let progress = row.progress {
                    ProgressView(value: min(max(progress.doubleValue, 0), 1))
                        .tint(Palette.primary)
                        .padding(.top, 2)
                }
                if let failure = row.failureText {
                    Text(failure)
                        .font(.caption)
                        .foregroundStyle(Palette.error)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            // Wrapped, the lines take the height they need: a list row would otherwise hold them a little short.
            .fixedSize(horizontal: false, vertical: wraps)
        }
        .contentShape(Rectangle())
    }

    /// The status and the bytes (with the speed while it downloads): one line on the wide rows of the Mac and iPad, the
    /// bytes under the status on iPhone's narrow ones and on stacked ones.
    @ViewBuilder
    private var statusLines: some View {
        if formFactor == .compact || stacked {
            VStack(alignment: .leading, spacing: 2) {
                status.lineLimit(wraps ? nil : 1)
                if let bytes = row.bytesText { bytesText(bytes) }
            }
        } else {
            statusLine(row.bytesText)
        }
    }

    private func statusLine(_ bytes: String?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            status
            if let bytes {
                Text(verbatim: "·")
                    .font(.footnote)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .accessibilityHidden(true)
                bytesText(bytes)
            }
        }
        .lineLimit(1)
    }

    private var status: some View {
        IconLine(text: row.statusText, systemImage: row.status.systemImage)
            .font(.footnote)
            .foregroundStyle(row.status == .failed ? Palette.error : Palette.onSurfaceVariant)
    }

    private func bytesText(_ bytes: String) -> some View {
        Text(bytes)
            .font(.caption)
            .monospacedDigit()
            .foregroundStyle(Palette.onSurfaceVariant)
    }
}

/// A download's artwork, or a Keep synced rule's poster (`KeepSyncedScreen`): a poster (2:3) or an episode's still
/// (16:9), a film symbol until it loads or without one.
struct DownloadArtwork: View {
    let url: String?
    let poster: Bool
    let width: CGFloat

    /// A movie's or a series' poster, 78 pt tall: taller than a movie row's lines.
    static let posterWidth: CGFloat = 52
    /// An episode's still, 67.5 pt tall: as tall as an episode row's lines on iPhone, which are the tallest.
    static let thumbnailWidth: CGFloat = 120

    var body: some View {
        ZStack {
            Palette.surfaceContainerHigh
            RemoteImage(url: url) {
                Image(systemName: "film")
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .frame(width: width, height: poster ? width * 3 / 2 : width * 9 / 16)
        .clipShape(RoundedRectangle(cornerRadius: Radius.small, style: .continuous))
        .accessibilityHidden(true)
    }
}

private extension DownloadAction {
    var systemImage: String {
        switch self {
        case .play: "play"
        case .playWith: "play.rectangle.on.rectangle"
        case .pause: "pause"
        case .resume: "arrow.down.circle"
        case .retry: "arrow.clockwise"
        case .pickStream: "list.bullet"
        case .cancel: "xmark"
        case .delete: "trash"
        case .openDetail: "info.circle"
        }
    }

    /// It moves the transfer along (a leading swipe on iOS): pause, resume, retry or pick a stream.
    var isTransfer: Bool {
        switch self {
        case .pause, .resume, .retry, .pickStream: true
        default: false
        }
    }
}

extension Confirmation {
    /// The question before several downloads go at once (a series' group, a season's or a whole title's in Detail), its
    /// answers' identifiers `<id>.confirm` and `<id>.cancel`.
    init(_ dialog: DownloadDeleteDialog, id: String) {
        self.init(
            title: dialog.title,
            message: dialog.message,
            confirmLabel: dialog.confirmLabel,
            cancelLabel: dialog.cancelLabel,
            confirmId: "\(id).confirm",
            cancelId: "\(id).cancel"
        )
    }
}
#endif
