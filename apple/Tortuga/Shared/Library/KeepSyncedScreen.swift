#if !os(tvOS)
import SwiftUI
import TortugaKit

/// The Keep synced route of `app` (`Route.KeepSynced`, pushed over Library's Downloads or a series' Detail; Apple TV,
/// which has no downloads, never opens it): its `KeepSyncedStore` is kept by the navigator while the route is on the
/// stack (under "keepSynced"), so the screen outlasts a visit to a series it opened.
struct KeepSyncedRoute: View {
    let app: AppModel

    /// Popped, the page draws what it showed while it slides out (`LastDrawn`).
    @State private var drawn = LastDrawn<RetainedStore<KeepSyncedStore, KeepSyncedUiState>>()

    var body: some View {
        if let model = drawn(retained()) {
            KeepSyncedScreen(state: model.observer.state, store: model.store)
                .task { await model.observer.observe() }
        }
    }

    private func retained() -> RetainedStore<KeepSyncedStore, KeepSyncedUiState>? {
        let session = app.session
        return app.retained(
            RouteKeepSynced.shared,
            key: "keepSynced",
            // Only a device with downloads leads to the route, and there the store exists.
            create: { session.keepSyncedStore()! },
            state: { $0.state },
            release: { $0.release() }
        )
    }
}

/// Keep synced, a settings page under its title and Back (the navigation bar's): what a rule does (`note`, the group's
/// heading), then the profile's rules as one group of rows, each its series' poster, name (up to two lines), the count
/// it keeps ("Keep 3 unwatched episodes") and where it is, muted ("Paused", "Downloading 2 episodes"); a press opens the
/// series. Beside each row its Pause (Resume while paused) and Stop (×), plain icon buttons named by the row's
/// `pauseLabel` and `stopLabel`, which go under the row's text where they would squeeze it (`SettingsRowLayout`). At the
/// accessibility text sizes the text goes under the poster and shows whole. The same actions are in the row's context
/// menu (a long press, a right click) and on iPhone and iPad its swipes: Pause or Resume from the leading edge, Stop
/// from the trailing edge. Stop asks first (`KeepSyncedUiState.stopDialog`, the system alert): Keep downloads, Delete
/// downloads or Cancel; where the rule downloaded nothing, Stop or Cancel. Without rules, the store's empty text under
/// Keep synced's symbol; while the rules load, the spinner. An action that failed (`KeepSyncedUiState.notice`) shows as
/// a toast, then `KeepSyncedStore.dismissNotice`. Every text comes from `KeepSyncedUiState`.
///
/// Accessibility identifiers: `keepSynced.list`, `keepSynced.note`, `keepSynced.empty`, `keepSynced.rule.<meta id>`
/// with its buttons `.pause`, `.resume` and `.stop` and its context menu's `.menu.pause`, `.menu.resume` and
/// `.menu.stop`, the question's `keepSynced.stop.keep`, `keepSynced.stop.delete` and `keepSynced.stop.cancel`,
/// `keepSynced.notice`.
struct KeepSyncedScreen: View {
    let state: KeepSyncedUiState
    let store: KeepSyncedStore

    var body: some View {
        let dialog = state.stopDialog
        Group {
            switch onEnum(of: state.body) {
            case .loading:
                LoadingStateView()
            case .empty(let empty):
                EmptyStateView(message: empty.text, systemImage: keepSyncedSymbol)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("keepSynced.empty")
            case .rules(let shown):
                rules(shown.rules)
            }
        }
        .pageTitle(state.title)
        .toast(state.notice, id: "keepSynced.notice", onShown: store.dismissNotice)
        .confirmation(
            dialog.map { Confirmation($0) },
            // Delete downloads where the question offers it, else Stop.
            onConfirm: { store.confirmStop(deleteDownloads: dialog?.deleteLabel != nil) },
            onAlternative: { store.confirmStop(deleteDownloads: false) },
            onCancel: store.dismissStop
        )
    }

    private func rules(_ rules: [KeepSyncedRow]) -> some View {
        Form {
            Section {
                ForEach(rules, id: \.metaId) { row in
                    KeepSyncedRuleRow(row: row, store: store)
                }
            } header: {
                if let note = state.note {
                    Text(note)
                        .textCase(nil)
                        .font(.subheadline)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 4)
                        .accessibilityIdentifier("keepSynced.note")
                }
            }
        }
        .settingsForm()
        .accessibilityIdentifier("keepSynced.list")
    }
}

/// One rule's row: the series (a press opens it) with its Pause or Resume and its Stop beside it, or under it where
/// they would squeeze its text; its context menu and, on iOS, its swipes.
private struct KeepSyncedRuleRow: View {
    let row: KeepSyncedRow
    let store: KeepSyncedStore

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let id = "keepSynced.rule.\(row.metaId)"
        let stacked = dynamicTypeSize.isAccessibilitySize
        SettingsRowLayout(leastTextWidth: leastTextWidth(stacked: stacked), spacing: 4) {
            Button { store.open(metaId: row.metaId) } label: { label(stacked: stacked) }
                .buttonStyle(PressDimButtonStyle())
                .accessibilityIdentifier(id)
                .itemMenu(entries: menuEntries(id), probe: "\(id).menu")
            HStack(spacing: 4) {
                RuleButton(systemImage: pauseSymbol, label: row.pauseLabel, id: row.paused ? "\(id).resume" : "\(id).pause", action: pauseOrResume)
                RuleButton(systemImage: "xmark", label: row.stopLabel, id: "\(id).stop", action: stop)
            }
        }
        #if os(iOS)
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button(action: pauseOrResume) {
                Label(pauseTitle, systemImage: pauseSymbol)
            }
            .tint(Palette.surfaceContainerHighest)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            // Red, not the destructive role: a list takes that role's row away at once, before the question is answered.
            Button(action: stop) {
                Label(stopTitle, systemImage: "xmark")
            }
            .tint(Palette.error)
        }
        #endif
    }

    /// The poster beside the text, or above it at the accessibility text sizes (`stacked`), where the title shows whole
    /// too; the count and the state always do, on as many lines as they take.
    private func label(stacked: Bool) -> some View {
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10)) : AnyLayout(HStackLayout(spacing: 12))
        return layout {
            DownloadArtwork(url: row.poster, poster: true, width: DownloadArtwork.posterWidth)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.body.weight(.semibold))
                    .lineLimit(stacked ? nil : 2)
                Text(row.countText)
                    .font(.subheadline)
                Text(row.statusText)
                    .font(.subheadline)
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
            .foregroundStyle(Palette.onBackground)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    /// The narrowest the series' part of the row gets before the buttons go under it: the settings rows' text column
    /// (`SettingsRowLayout.leastTextWidth`), and the poster beside it.
    private func leastTextWidth(stacked: Bool) -> CGFloat {
        let text = SettingsRowLayout.leastTextWidth(title: row.title, subtitle: row.countText, dynamicTypeSize: dynamicTypeSize)
        return stacked ? text : text + DownloadArtwork.posterWidth + 12
    }

    /// The context menu: Pause or Resume, then Stop.
    private func menuEntries(_ id: String) -> [ItemMenuEntry] {
        [
            ItemMenuEntry(label: pauseTitle, systemImage: pauseSymbol, id: row.paused ? "\(id).menu.resume" : "\(id).menu.pause", action: pauseOrResume),
            ItemMenuEntry(label: stopTitle, systemImage: "xmark", id: "\(id).menu.stop", action: stop),
        ]
    }

    private var pauseSymbol: String { row.paused ? "play.fill" : "pause.fill" }

    /// "Pause" or "Resume": the menu's and the swipe's word; the button says whose (`pauseLabel`).
    private var pauseTitle: String {
        row.paused ? T.shared.downloads.action.resume : T.shared.downloads.action.pause
    }

    /// "Stop": the menu's and the swipe's word; the button says whose (`stopLabel`).
    private var stopTitle: String { T.shared.downloads.keepSynced.stop.confirm }

    private func pauseOrResume() {
        if row.paused {
            store.resume(metaId: row.metaId)
        } else {
            store.pause(metaId: row.metaId)
        }
    }

    private func stop() {
        store.stop(metaId: row.metaId)
    }
}

/// A plain icon button beside a rule (Pause, Resume, Stop), as a download row's ⋯ is (`ItemMenuButton`): its symbol in
/// a square of at least 44 pt that grows with the text, `label` for VoiceOver and, on the Mac, its tooltip.
private struct RuleButton: View {
    let systemImage: String
    let label: String
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .foregroundStyle(Palette.onBackground)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
                .focusRing(radius: 22)
        }
        .buttonStyle(PressDimButtonStyle())
        #if os(macOS)
        .tooltip(label)
        #endif
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }
}

extension Confirmation {
    /// The question before a rule stops (`KeepSyncedStopDialog`): Delete downloads (destructive) with Keep downloads
    /// before it, or where the rule downloaded nothing Stop alone, which deletes nothing; its answers
    /// `keepSynced.stop.delete`, `keepSynced.stop.keep` and `keepSynced.stop.cancel`.
    init(_ dialog: KeepSyncedStopDialog) {
        if let delete = dialog.deleteLabel {
            self.init(
                title: dialog.title,
                message: dialog.message,
                confirmLabel: delete,
                cancelLabel: dialog.cancelLabel,
                confirmId: "keepSynced.stop.delete",
                cancelId: "keepSynced.stop.cancel",
                alternative: (dialog.keepLabel, "keepSynced.stop.keep")
            )
        } else {
            self.init(
                title: dialog.title,
                message: dialog.message,
                confirmLabel: dialog.keepLabel,
                cancelLabel: dialog.cancelLabel,
                destructive: false,
                confirmId: "keepSynced.stop.keep",
                cancelId: "keepSynced.stop.cancel"
            )
        }
    }
}
#endif
