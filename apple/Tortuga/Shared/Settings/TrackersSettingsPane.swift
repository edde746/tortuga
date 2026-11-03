import SwiftUI
import TortugaKit

/// The trackers section over its store, with the connect flow over it and the disconnect confirmation.
struct TrackersSettingsRoute: View {
    let model: StoreModel<TrackersSettingsStore, TrackersSettingsUiState>

    var body: some View {
        let store = model.store
        let state = model.observer.state
        TrackersSettingsPane(state: state, store: store)
            .task { await model.observer.observe() }
            .confirmation(
                state.dialog.map { dialog in
                    Confirmation(
                        title: dialog.title,
                        message: dialog.message,
                        confirmLabel: dialog.confirmLabel,
                        cancelLabel: dialog.cancelLabel,
                        destructive: dialog.destructive,
                        confirmId: "settings.trackers.dialog.confirm",
                        cancelId: "settings.trackers.dialog.cancel"
                    )
                },
                onConfirm: store.confirmDialog,
                onCancel: store.dismissDialog
            )
            .settingsSheet(item: state.connect, size: .fullHeightForm, onDismiss: store.cancelConnect) { connect in
                TrackerConnectSheet(connect: connect, store: store)
            }
    }
}

/// The tracker services this build can connect, as a native form: each with its connection on this
/// profile and device, Connect or Disconnect (which asks first), and a connected account's switches: scrobbling, the
/// content types it covers, the changes still waiting to be sent and those that couldn't be. What an action did shows
/// as a notice.
///
/// Accessibility identifiers (the Compose UI's probe names): `settings.trackers.notice`,
/// `settings.trackers.<service>.<status|connect|disconnect|scrobble|movies|series|anime|queued|failed>`,
/// `settings.trackers.dialog.<confirm|cancel>`.
struct TrackersSettingsPane: View {
    let state: TrackersSettingsUiState
    let store: TrackersSettingsStore

    var body: some View {
        Form {
            if let notice = state.notice {
                Section {
                    SettingsNotice(text: notice, onDismiss: store.dismissNotice)
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("settings.trackers.notice")
                }
            }
            ForEach(Array(state.services.enumerated()), id: \.element.service) { index, row in
                TrackerServiceSections(row: row, note: index == 0 ? state.note : nil, store: store)
            }
        }
        .settingsForm()
    }
}

/// One service: its mark, name and connection and Connect or Disconnect, then the connected account's switches, and
/// under them the changes waiting to be sent and those that couldn't be. The mark is the same whether connected or not:
/// the status line says which.
private struct TrackerServiceSections: View {
    let row: TrackerServiceRow
    /// The page's note, under the first service.
    let note: String?
    let store: TrackersSettingsStore

    var body: some View {
        let id = "settings.trackers.\(row.service.id)"
        Section {
            HStack(spacing: Self.markSpacing) {
                Image(decorative: row.service.markImageName)
                    .resizable()
                    .scaledToFit()
                    .frame(width: Self.markSize, height: Self.markSize)
                    .accessibilityIgnoresInvertColors()
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).font(.headline)
                    Text(row.status).font(.footnote).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("\(id).status")
            }
            if row.connected || row.available {
                // One button that turns from Connect into Disconnect and back, so focus stays on it (tvOS) as the
                // connection changes under the connect flow or the confirmation.
                Button(role: row.connected ? .destructive : nil) {
                    if row.connected { store.disconnect(service: row.service) } else { store.connect(service: row.service) }
                } label: {
                    Label(row.connected ? row.disconnectLabel : row.connectLabel, systemImage: row.connected ? "rectangle.portrait.and.arrow.right" : "link")
                }
                .disabled(!(row.connected || row.canConnect))
                // Spoken with the service's name; Voice Control takes the word shown too.
                .accessibilityLabel(row.connected ? row.disconnectNamedLabel : row.connectNamedLabel)
                .accessibilityInputLabels(row.connected ? [row.disconnectLabel, row.disconnectNamedLabel] : [row.connectLabel, row.connectNamedLabel])
                .accessibilityIdentifier("\(id).\(row.connected ? "disconnect" : "connect")")
            }
            if let account = row.account {
                SettingsToggleRow(row: account.scrobble, id: "\(id).scrobble") { store.setScrobbling(service: row.service, on: $0) }
            }
        } footer: {
            if let note { Text(note) }
        }
        if let account = row.account {
            Section {
                SettingsToggleRow(row: account.movies, id: "\(id).movies") { store.setMovies(service: row.service, on: $0) }
                SettingsToggleRow(row: account.series, id: "\(id).series") { store.setSeries(service: row.service, on: $0) }
                SettingsToggleRow(row: account.anime, id: "\(id).anime") { store.setAnime(service: row.service, on: $0) }
            } header: {
                Text(account.filtersHeader)
            } footer: {
                if account.queued != nil || account.failed != nil {
                    VStack(alignment: .leading, spacing: 6) {
                        if let queued = account.queued {
                            Label(queued, systemImage: "clock.arrow.circlepath")
                                .accessibilityElement(children: .combine)
                                .accessibilityIdentifier("\(id).queued")
                        }
                        if let failed = account.failed {
                            Label(failed, systemImage: "exclamationmark.circle")
                                .accessibilityElement(children: .combine)
                                .accessibilityIdentifier("\(id).failed")
                        }
                    }
                }
            }
        }
    }

    /// Compose's 24 dp mark and 16 dp gap; on tvOS, whose points are a 1080p screen's pixels, the same share of the
    /// screen as on Compose's TV (24 dp there are 48 pixels).
    #if os(tvOS)
    private static let markSize: CGFloat = 48
    private static let markSpacing: CGFloat = 32
    #else
    private static let markSize: CGFloat = 24
    private static let markSpacing: CGFloat = 16
    #endif
}

extension TrackerService {
    /// Its mark in RatingLogos.xcassets, Detail's ratings row's (see `RatingLogo.imageName` for where they come from),
    /// also before its line in Detail's rating picker (`DetailRatingTrackers`) and on its lists' chips on Home
    /// (`BoardShortcuts`).
    var markImageName: String {
        switch self {
        case .trakt: "rating-trakt"
        case .simkl: "rating-simkl"
        case .mdblist: "rating-mdblist"
        case .myanimelist: "rating-myanimelist"
        case .anilist: "rating-anilist"
        }
    }
}

/// Connecting a service, in a form: the device code, large, with its QR code, the line offering it to a phone and the
/// countdown, or the relay's sign-in link, while the app waits for the answer; the outcome when it didn't connect, with
/// a retry. Open in browser shows where the store offers it (`canOpenInBrowser`: not on Apple TV, whose user scans the
/// QR code with a phone).
///
/// Accessibility identifiers: `settings.trackers.connect.<code|qr|link|expires|message|openInBrowser|retry|cancel>`.
private struct TrackerConnectSheet: View {
    let connect: TrackerConnectUiState
    let store: TrackersSettingsStore

    var body: some View {
        let cancel = SheetAction(title: connect.cancelLabel, id: "settings.trackers.connect.cancel", action: store.cancelConnect)
        let retry = SheetAction(title: connect.retryLabel, id: "settings.trackers.connect.retry", action: store.retryConnect)
        let ended = switch onEnum(of: connect.step) {
        case .starting, .code, .link: false
        case .expired, .declined, .failed: true
        }
        NavigationStack {
            Form {
                switch onEnum(of: connect.step) {
                case .starting(let starting):
                    statusSection(progress: starting.message, canOpenInBrowser: false)
                case .code(let step):
                    Section {
                        CodeAndQR(
                            code: step.code.userCode,
                            codeId: "settings.trackers.connect.code",
                            qrPayload: step.code.qrPayload,
                            qrDescription: T.shared.account.signIn.deviceCode.qrDescription,
                            qrId: "settings.trackers.connect.qr"
                        )
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        Text(step.code.scanHint)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } header: {
                        Text(step.code.instructions).textCase(nil)
                    } footer: {
                        Text(step.code.expiresText).accessibilityIdentifier("settings.trackers.connect.expires")
                    }
                    statusSection(progress: step.message, canOpenInBrowser: step.canOpenInBrowser)
                case .link(let step):
                    Section {
                        CodeAndQR(code: nil, codeId: "", qrPayload: step.link.qrPayload, qrDescription: step.link.scanHint, qrId: "settings.trackers.connect.qr")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                        Text(step.link.url)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            #if !os(tvOS)
                            .textSelection(.enabled)
                            #endif
                            .accessibilityIdentifier("settings.trackers.connect.link")
                    } header: {
                        Text(step.link.instructions).textCase(nil)
                    } footer: {
                        Text(step.link.scanHint)
                    }
                    statusSection(progress: step.message, canOpenInBrowser: step.canOpenInBrowser)
                case .expired(let step):
                    errorSection(step.message)
                case .declined(let step):
                    errorSection(step.message)
                case .failed(let step):
                    errorSection(step.message)
                }
                SheetActionsSection(actions: ended ? [retry, cancel] : [cancel])
            }
            .settingsForm()
            .pageTitle(connect.title)
            .sheetToolbar(cancel: cancel, confirm: ended ? retry : nil)
        }
        .backAction(store.cancelConnect)
    }

    /// The waiting line while the app asks or waits, with Open in browser where the store offers it.
    private func statusSection(progress: String, canOpenInBrowser: Bool) -> some View {
        Section {
            ProgressLine(text: progress)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("settings.trackers.connect.message")
            if canOpenInBrowser {
                Button(action: store.openInBrowser) { Label(connect.openInBrowserLabel, systemImage: "safari") }
                    .accessibilityIdentifier("settings.trackers.connect.openInBrowser")
            }
        }
    }

    /// Why the flow didn't connect.
    private func errorSection(_ message: String) -> some View {
        Section {
            ErrorLine(text: message)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("settings.trackers.connect.message")
        }
    }
}
