import SwiftUI
import TortugaKit

/// The device section over its store.
struct DeviceSettingsRoute: View {
    let model: StoreModel<DeviceSettingsStore, DeviceSettingsUiState>

    var body: some View {
        let store = model.store
        DeviceSettingsPane(
            state: model.observer.state,
            onChoose: { store.choose(key: $0, optionId: $1) },
            onToggle: { store.toggle(key: $0, checked: $1) }
        )
        .task { await model.observer.observe() }
    }
}

/// This device's settings (never synced, as the note says), grouped as the store groups them: switches, the player's
/// swipes on iPhone and iPad (Gestures), the read-ahead cache as a menu of sizes (Automatic, 64 MB … 4 GB) and on the
/// Mac Discord's Rich Presence, and Privacy's crash reports switch. Rows this device can't use
/// (`ApplePlatform.deviceFeatures`, the swipes off a touch screen, and Discord's
/// where `AppleAppPlatform.discordPresence` is nil) are absent from `state`. Audio passthrough's hint is the store's,
/// worded for the system's decoder (`ApplePlatform.deviceFeatures`); while loudness normalization is on, its switch is
/// disabled and says it is off meanwhile. A menu gives its row's id and the option's to `onChoose`, a switch its row's
/// id to `onToggle`.
///
/// Accessibility identifiers:
/// `settings.device.<hardwareDecoding|hdr|matchFrameRate|audioPassthrough|swipeGestures|discordPresence|crashReports>`,
/// and `settings.device.cacheSize`.
struct DeviceSettingsPane: View {
    let state: DeviceSettingsUiState
    let onChoose: (_ key: String, _ optionId: String) -> Void
    let onToggle: (_ key: String, _ checked: Bool) -> Void

    var body: some View {
        Form {
            if state.hardwareDecoding != nil || state.hdr != nil || state.matchFrameRate != nil {
                Section {
                    toggle(state.hardwareDecoding)
                    toggle(state.hdr)
                    toggle(state.matchFrameRate)
                } header: {
                    if let header = state.videoHeader { Text(header) }
                }
            }
            if state.audioPassthrough != nil {
                Section {
                    toggle(state.audioPassthrough)
                } header: {
                    if let header = state.audioHeader { Text(header) }
                }
            }
            if state.swipeGestures != nil {
                Section {
                    toggle(state.swipeGestures)
                } header: {
                    if let header = state.gesturesHeader { Text(header) }
                }
            }
            Section(state.streamingHeader) {
                choice(state.cacheSize)
            }
            if state.discordPresence != nil {
                Section {
                    toggle(state.discordPresence)
                } header: {
                    if let header = state.discordHeader { Text(header) }
                }
            }
            Section(state.privacyHeader) {
                toggle(state.crashReports)
            }
            Section {} footer: { Text(state.note) }
        }
        .settingsForm()
    }

    @ViewBuilder
    private func toggle(_ row: SettingToggleRow?) -> some View {
        if let row {
            SettingsToggleRow(row: row, id: "settings.device.\(row.id)") { onToggle(row.id, $0) }
        }
    }

    private func choice(_ row: SettingChoiceRow) -> some View {
        SettingsChoicePicker(row: row, id: "settings.device.\(row.id)") { onChoose(row.id, $0) }
    }
}
