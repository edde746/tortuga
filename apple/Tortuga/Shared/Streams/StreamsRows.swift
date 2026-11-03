import SwiftUI
import TortugaKit

/// Why the list opened instead of a stream playing (auto-play found none that passes the filters, or its pick didn't
/// start), beside the info mark; in download mode that a pick downloads, beside the download arrow.
struct NoticeLine: View {
    let text: String
    let downloadMode: Bool

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: downloadMode ? "arrow.down.circle" : "info.circle").accessibilityHidden(true)
        }
        .font(.subheadline)
        .foregroundStyle(Palette.onBackground)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("streams.notice")
    }
}

/// What an addon's group leaves out, under its rows, in the footnote's muted text: the streams this app can't play,
/// the streams the filters hide (`streams.group.<index>.filtered`).
struct GroupNotes: View {
    let group: StreamGroup
    let index: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let note = group.unplayableNote { Text(note) }
            if let note = group.filteredNote {
                Text(note).accessibilityIdentifier("streams.group.\(index).filtered")
            }
        }
        .font(.footnote)
        .foregroundStyle(Palette.onSurfaceVariant)
    }
}

/// An addon's heading over its streams, plain on the list's background, `index` its group's among the listed ones: its
/// name (`streams.group.<index>.name`; not while its chip is selected, `named` false, which names the list), a spinner
/// the text's size while it answers (`.loading`), and its error (`.error`) with Retry (`.retry`) once it failed.
struct GroupHeader: View {
    let group: StreamGroup
    let index: Int
    let named: Bool
    let onReload: () -> Void
    #if os(tvOS)
    let focus: FocusState<StreamsFocus?>.Binding
    #endif

    /// Its addon is still asked.
    private var asked: Bool {
        if case .loading = onEnum(of: group.status) { return true }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if named || asked {
                HStack(spacing: 8) {
                    if named {
                        Text(group.addonName)
                            .font(.headline)
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(1)
                            .accessibilityAddTraits(.isHeader)
                            .accessibilityIdentifier("streams.group.\(index).name")
                    }
                    if asked {
                        ProgressView().smallSpinner().accessibilityIdentifier("streams.group.\(index).loading")
                    }
                }
            }
            if case .failed(let failed) = onEnum(of: group.status) {
                HStack {
                    Text(failed.error)
                        .font(.subheadline)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("streams.group.\(index).error")
                    Button(action: onReload) {
                        Text(T.shared.nav.retry).hitTargetLabel()
                    }
                        #if os(tvOS)
                        .focused(focus, equals: .retry(group.id))
                        #else
                        // In a list row: a tap elsewhere on the row doesn't retry.
                        .buttonStyle(.borderless)
                        #endif
                        // Spoken with the addon's name; Voice Control takes the word shown too.
                        .accessibilityLabel(group.retryLabel)
                        .accessibilityInputLabels([T.shared.nav.retry, T.shared.streams.retryNamed(name: group.addonName)])
                        .accessibilityIdentifier("streams.group.\(index).retry")
                        .hitTarget()
                }
            }
        }
        .textCase(nil)
    }
}

#if !os(tvOS)
/// A stream's row in the list (iOS, macOS): a press picks it (`streams.row.<number>`). Its context menu downloads it
/// where it can be downloaded (`StreamRow.downloadActionText`), as a download button at its trailing end does on the
/// Mac, both `streams.row.<number>.download`; and plays it once in the player picked from its Play with… where the
/// device has player apps and the row plays (not an external one), `streams.menu.playWith.<player id>`.
struct StreamListRow: View {
    let row: StreamRow
    let number: Int
    let playWith: PlayWithMenu?
    let onPick: (String) -> Void
    let onDownload: (String) -> Void
    let onPlayWith: (_ id: String, _ playerId: String) -> Void

    var body: some View {
        content.streamRowMenu(menu)
    }

    private var menu: [ItemMenuEntry] {
        var entries: [ItemMenuEntry] = []
        if let download = row.downloadActionText {
            entries.append(ItemMenuEntry(label: download, systemImage: "arrow.down.circle", id: "streams.row.\(number).download") {
                onDownload(row.id)
            })
        }
        if let playWith, !row.external {
            entries.append(.playWith(playWith, id: "streams.menu.playWith") { onPlayWith(row.id, $0) })
        }
        return entries
    }

    private var content: some View {
        HStack(spacing: 12) {
            Button { onPick(row.id) } label: {
                StreamRowLabel(row: row).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("streams.row.\(number)")
            #if os(macOS)
            if let download = row.downloadActionText, let spoken = row.downloadActionLabel {
                Button { onDownload(row.id) } label: {
                    Image(systemName: "arrow.down.circle")
                        .font(.title3)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .hitTargetLabel()
                }
                .buttonStyle(.borderless)
                .tooltip(download)
                .accessibilityLabel(spoken)
                .accessibilityIdentifier("streams.row.\(number).download")
                .hitTarget()
            }
            #endif
        }
    }
}
#endif

extension View {
    /// A stream row's context menu (a long press, a right click, a long press of Select on the Siri Remote) of
    /// `entries`; none while there are none.
    @ViewBuilder
    func streamRowMenu(_ entries: [ItemMenuEntry]) -> some View {
        if entries.isEmpty {
            self
        } else {
            contextMenu { ItemMenuEntries(entries: entries, probe: "streams.menu") }
        }
    }
}

/// One stream across the row's whole width: the addon's name for it (its quality line), with the "Last played", "Hidden
/// by your filters" or "Opens outside the app" note at its top trailing corner, level with the name's first line (the
/// last note says what picking an external row does; picking any other plays it), then its description, styled
/// (`StreamDescriptionText`), and its solid badges. The name wraps, and then truncates, before the note, which keeps its
/// one line whole. The suggested stream (`StreamRow.suggestion`, while the profile's "Suggest a stream" is on) says so
/// above its name ("Suggested") and why under it.
struct StreamRowLabel: View {
    let row: StreamRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let suggestion = row.suggestion {
                Label(suggestion.label, systemImage: "sparkles")
                    .font(.caption.weight(.semibold))
            }
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(row.name)
                    .font(.headline)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let note = row.note {
                    // An icon and a title of its own rather than a Label: a list lays a Label's icon out in a column of
                    // its own, wide of the title.
                    HStack(spacing: 4) {
                        Image(systemName: noteSymbol).accessibilityHidden(true)
                        Text(note).lineLimit(1)
                    }
                    .font(.caption.weight(.semibold))
                    .opacity(0.8)
                    .fixedSize()
                }
            }
            if let description = row.descriptionText, let runs = row.descriptionRuns {
                StreamDescriptionText(text: description, runs: runs)
                    .opacity(0.72)
                    .lineLimit(6)
            }
            if !row.badges.isEmpty { StreamBadgeRow(badges: row.badges) }
            if let suggestion = row.suggestion {
                Text(suggestion.reason)
                    .font(.caption)
                    .opacity(0.72)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .multilineTextAlignment(.leading)
    }

    /// The note's symbol (`noteKind`): the stream history's clock, the filters' crossed-out eye for a row they hide
    /// (listed by the filter bar's Show), else the arrow out of the app.
    private var noteSymbol: String {
        switch row.noteKind {
        case .lastUsed: "clock.arrow.circlepath"
        case .filtered: "eye.slash"
        case .external, nil: "arrow.up.forward.square"
        }
    }
}

extension View {
    /// A row of the Streams list (a stream, the notice, the filter bar, an addon's name, a group's notes) with no fill of
    /// its own: iOS's plain list insets it by `StreamsScreen.listMargin` at the sides and `top` and `bottom` pt above and
    /// below; the Mac's list keeps its own insets.
    @ViewBuilder
    func streamsListRow(top: CGFloat = 10, bottom: CGFloat = 10) -> some View {
        #if os(iOS)
        listRowInsets(EdgeInsets(top: top, leading: StreamsScreen.listMargin, bottom: bottom, trailing: StreamsScreen.listMargin))
            .listRowBackground(Color.clear)
        #else
        self
        #endif
    }
}
