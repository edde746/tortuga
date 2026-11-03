package com.edde746.tortuga.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.edde746.tortuga.presentation.settings.DeviceSettingsStore
import com.edde746.tortuga.presentation.settings.DeviceSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.probe.layoutProbe

/** The device section over [store]. */
@Composable
internal fun DeviceSettingsRoute(store: DeviceSettingsStore) {
    val state by store.state.collectAsState()
    DeviceSettingsPane(
        state = state,
        onChoose = store::choose,
        onToggle = store::toggle,
    )
}

/**
 * This device's settings (never synced, as the note says), grouped as the store groups them; rows this device can't
 * use are absent from [state]. A choice gives its row's id and the option's to [onChoose], a switch its row's id to
 * [onToggle].
 *
 * Harness names:
 * `settings.device.<hardwareDecoding|hdr|matchFrameRate|audioPassthrough|swipeGestures|discordPresence|crashReports>`
 * and the cache row `settings.device.cacheSize`, whose sizes open as `settings.device.cacheSize.option.<i>` (see
 * [SettingChoiceItem]).
 */
@Composable
internal fun DeviceSettingsPane(
    state: DeviceSettingsUiState,
    onChoose: (key: String, optionId: String) -> Unit,
    onToggle: (key: String, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun toggle(row: SettingToggleRow?): SettingsItem? = row?.let {
        SettingsItem(it.id) { shape -> SettingToggleItem(it, { checked -> onToggle(it.id, checked) }, shape, Modifier.layoutProbe("settings.device.${it.id}")) }
    }
    SettingsList(modifier) {
        item(key = "note") { SettingsNote(state.note) }
        settingsGroup("video", state.videoHeader, listOf(toggle(state.hardwareDecoding), toggle(state.hdr), toggle(state.matchFrameRate)))
        settingsGroup("audio", state.audioHeader, listOf(toggle(state.audioPassthrough)))
        settingsGroup("gestures", state.gesturesHeader, listOf(toggle(state.swipeGestures)))
        settingsGroup(
            "streaming",
            state.streamingHeader,
            listOf(
                SettingsItem(state.cacheSize.id) { shape ->
                    SettingChoiceItem(state.cacheSize, { onChoose(state.cacheSize.id, it) }, "settings.device.${state.cacheSize.id}", shape)
                },
            ),
        )
        settingsGroup("discord", state.discordHeader, listOf(toggle(state.discordPresence)))
        settingsGroup("privacy", state.privacyHeader, listOf(toggle(state.crashReports)))
    }
}
