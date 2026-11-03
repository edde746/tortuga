import SwiftUI
import TortugaKit

/// The appearance section over its store.
struct AppearanceSettingsRoute: View {
    let model: StoreModel<AppearanceSettingsStore, AppearanceSettingsUiState>

    var body: some View {
        AppearanceSettingsPane(state: model.observer.state, store: model.store)
            .task { await model.observer.observe() }
    }
}

/// The profile's language and how its screens show titles (per profile, synced): the interface language's choice, the
/// switches Blur unwatched episodes, Hide watched titles and Hide titles under posters, then the poster URL template,
/// whose row opens its editor (`SettingTextSheet`); a kids profile has no template, nor its row.
///
/// Accessibility identifiers: `settings.appearance.<row id>` (`interfaceLanguage`, `blurUnwatched`, `hideWatched`,
/// `hidePosterCaptions`, `posterTemplate`) and the template editor's
/// `settings.appearance.posterTemplate.<field|error|save|clear|cancel>`.
struct AppearanceSettingsPane: View {
    let state: AppearanceSettingsUiState
    let store: AppearanceSettingsStore

    @State private var editingTemplate = false

    var body: some View {
        Form {
            Section {
                SettingsChoicePicker(row: state.interfaceLanguage, id: "settings.appearance.\(state.interfaceLanguage.id)") {
                    store.choose(key: state.interfaceLanguage.id, optionId: $0)
                }
                ForEach([state.blurUnwatched, state.hideWatched, state.hidePosterCaptions], id: \.id) { row in
                    SettingsToggleRow(row: row, id: "settings.appearance.\(row.id)") {
                        store.toggle(key: row.id, checked: $0)
                    }
                }
                if let template = state.posterTemplate {
                    SettingsLinkRow(title: template.title, subtitle: template.subtitle, value: template.valueLabel, error: template.error, id: "settings.appearance.posterTemplate") {
                        editingTemplate = true
                    }
                }
            }
        }
        .settingsForm()
        .settingsSheet(isPresented: $editingTemplate) {
            if let template = state.posterTemplate {
                let strings = T.shared.settings.playback.streams
                SettingTextSheet(
                    title: template.title, subtitle: template.subtitle, value: template.value, placeholder: template.placeholder,
                    saveLabel: strings.save, clearLabel: strings.clear, id: "settings.appearance.posterTemplate", sensitiveURL: true,
                    error: { store.posterTemplateError(template: $0) },
                    onSave: { store.setPosterTemplate(template: $0) }
                ) {
                    editingTemplate = false
                }
            }
        }
    }
}
