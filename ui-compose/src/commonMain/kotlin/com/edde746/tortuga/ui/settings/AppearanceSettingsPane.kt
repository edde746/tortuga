package com.edde746.tortuga.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.edde746.tortuga.presentation.settings.AppearanceSettingsStore
import com.edde746.tortuga.presentation.settings.AppearanceSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.probe.layoutProbe

/** The appearance section over [store], with the poster template's editor over it while open. */
@Composable
internal fun AppearanceSettingsRoute(store: AppearanceSettingsStore) {
    val state by store.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    AppearanceSettingsPane(state = state, onChoose = store::choose, onToggle = store::toggle, onEditPosterTemplate = { editing = true })
    val row = state.posterTemplate
    if (editing && row != null) {
        PatternOverlay(
            title = row.title,
            subtitle = row.subtitle,
            value = row.value,
            placeholder = row.placeholder,
            probe = "settings.appearance.posterTemplate",
            errorFor = store::posterTemplateError,
            onSave = { template ->
                store.setPosterTemplate(template)
                editing = false
            },
            onClose = { editing = false },
            keyboardType = KeyboardType.Uri,
        )
    }
}

/**
 * The profile's language and how titles show (per profile, synced): the interface language, whose choice gives its row's
 * id and the option's to [onChoose]; "Blur unwatched episodes", "Hide watched titles" and "Hide titles under posters",
 * whose switches give their row's id to [onToggle]; then the poster URL template's row, whose click opens its editor
 * ([onEditPosterTemplate]); none on a kids profile.
 *
 * Harness names: `settings.appearance.<interfaceLanguage|blurUnwatched|hideWatched|hidePosterCaptions>`,
 * `settings.appearance.posterTemplate`, its editor's
 * `settings.appearance.posterTemplate.<field|error|clear|cancel|save>`.
 */
@Composable
internal fun AppearanceSettingsPane(
    state: AppearanceSettingsUiState,
    onChoose: (key: String, optionId: String) -> Unit,
    onToggle: (key: String, checked: Boolean) -> Unit,
    onEditPosterTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsList(modifier) {
        settingsGroup(
            "appearance",
            null,
            listOf(
                SettingsItem(state.interfaceLanguage.id) { shape ->
                    val row = state.interfaceLanguage
                    SettingChoiceItem(row, { onChoose(row.id, it) }, "settings.appearance.${row.id}", shape)
                },
                toggle(state.blurUnwatched, onToggle),
                toggle(state.hideWatched, onToggle),
                toggle(state.hidePosterCaptions, onToggle),
                state.posterTemplate?.let { row ->
                    SettingsItem("posterTemplate") { shape ->
                        PatternRow(row.title, row.subtitle, row.valueLabel, row.error, onEditPosterTemplate, "settings.appearance.posterTemplate", shape)
                    }
                },
            ),
        )
    }
}

private fun toggle(row: SettingToggleRow, onToggle: (key: String, checked: Boolean) -> Unit) = SettingsItem(row.id) { shape ->
    SettingToggleItem(row, { onToggle(row.id, it) }, shape, Modifier.layoutProbe("settings.appearance.${row.id}"))
}
