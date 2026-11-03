#if !os(tvOS)
import SwiftUI
import TortugaKit

/// The downloads section over its store (iPhone, iPad, the Mac; Apple TV has no downloads): the pane, the subtitle
/// languages' editor (Playback's `LanguagePickerSheet`) and Delete all's confirmation.
struct DownloadSettingsRoute: View {
    let model: StoreModel<DownloadSettingsStore, DownloadSettingsUiState>

    var body: some View {
        let store = model.store
        let state = model.observer.state
        DownloadSettingsPane(state: state, store: store)
            .task { await model.observer.observe() }
            // The space left changes outside the app too: read again whenever the section shows.
            .onAppear { store.refreshStorage() }
            .settingsSheet(item: state.languagePicker, size: .searchedForm, onDismiss: store.closeLanguages) { picker in
                LanguagePickerSheet(picker: picker, intents: LanguagePickerIntents(store))
            }
            .confirmation(
                state.deleteAllDialog.map { dialog in
                    Confirmation(
                        title: dialog.title,
                        message: dialog.message,
                        confirmLabel: dialog.confirmLabel,
                        cancelLabel: dialog.cancelLabel,
                        confirmId: "settings.downloads.deleteAll.confirm",
                        cancelId: "settings.downloads.deleteAll.cancel"
                    )
                },
                onConfirm: store.confirmDeleteAll,
                onCancel: store.dismissDialog
            )
    }
}

extension LanguagePickerIntents {
    init(_ store: DownloadSettingsStore) {
        self.init(
            search: { store.searchLanguages(query: $0) },
            add: { store.addLanguage(code: $0) },
            remove: { store.removeLanguage(code: $0) },
            move: { store.moveLanguage(code: $0, up: $1) },
            moveTo: { store.moveLanguageTo(code: $0, index: $1) },
            close: { store.closeLanguages() }
        )
    }
}

/// This device's download settings (never synced, as the note says): Wi-Fi only (iPhone and iPad), how many download at
/// once, removing watched downloads; the subtitle languages saved with each download (the profile's until some are
/// chosen here, with a way back to them); the space the downloads take and the space left, with Delete all (every
/// profile's downloads on this device, after a confirmation; disabled while nothing is downloaded). The one place
/// downloads go on iOS and the Mac has no row.
///
/// Accessibility identifiers: `settings.downloads.<wifiOnly|autoRemoveWatched|subtitleLanguages|useProfileLanguages|
/// storage|deleteAll>`, the stepper `settings.downloads.atOnce` with its `.value` (`SettingsStepperRow`), and the
/// confirmation's `settings.downloads.deleteAll.<confirm|cancel>`.
struct DownloadSettingsPane: View {
    let state: DownloadSettingsUiState
    let store: DownloadSettingsStore

    var body: some View {
        Form {
            Section {
                if let wifiOnly = state.wifiOnly { toggle(wifiOnly) }
                SettingsStepperRow(row: state.atOnce, id: "settings.downloads.atOnce") { store.stepAtOnce(up: $0) }
                toggle(state.autoRemoveWatched)
            } header: {
                Text(state.transferHeader)
            } footer: {
                Text(state.note)
            }
            Section {
                let languages = state.subtitleLanguages
                SettingsLinkRow(
                    title: languages.title,
                    subtitle: languages.subtitle,
                    value: languages.valueLabel,
                    id: "settings.downloads.subtitleLanguages",
                    action: store.openLanguages
                )
                if let useProfile = state.useProfileLanguagesText {
                    Button(action: store.useProfileLanguages) {
                        Label(useProfile, systemImage: "arrow.uturn.backward")
                    }
                    .accessibilityIdentifier("settings.downloads.useProfileLanguages")
                }
            }
            Section(state.storageHeader) {
                Label(state.storage, systemImage: "internaldrive")
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("settings.downloads.storage")
                Button(role: .destructive, action: store.requestDeleteAll) {
                    Label(state.deleteAll.label, systemImage: "trash")
                }
                .disabled(!state.deleteAll.enabled)
                .accessibilityIdentifier("settings.downloads.deleteAll")
            }
        }
        .settingsForm()
    }

    private func toggle(_ row: SettingToggleRow) -> some View {
        SettingsToggleRow(row: row, id: "settings.downloads.\(row.id)") { store.toggle(key: row.id, checked: $0) }
    }
}
#endif
