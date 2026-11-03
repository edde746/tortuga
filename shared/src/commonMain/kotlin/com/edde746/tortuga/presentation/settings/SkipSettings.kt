package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SkipMode
import com.edde746.tortuga.strings.T

/** The [SettingChoiceOption.id] of [mode]. */
internal val SkipMode.optionId: String get() = name

internal fun skipMode(optionId: String): SkipMode? = SkipMode.entries.firstOrNull { it.optionId == optionId }

/**
 * Settings → Playback → Skipping, per profile and synced: what the player does with intros, recaps and credits found in
 * a video's chapters (Off, Show button, Skip automatically); under them, how they are found (chapter names) and how an
 * automatic skip behaves. Previews after the credits follow the credits' row, and have none of their own.
 */
internal fun skipSettingsSection(values: ProfileSettings): PlaybackSettingsSection {
    val text = T.settings.playback.skipping
    fun row(id: String, title: String, subtitle: String, selected: SkipMode): PlaybackSettingRow {
        val options = SkipMode.entries.map { mode ->
            val label = when (mode) {
                SkipMode.OFF -> text.off
                SkipMode.BUTTON -> text.button
                SkipMode.AUTO -> text.auto
            }
            SettingChoiceOption(mode.optionId, label, selected = mode == selected)
        }
        return PlaybackSettingRow.Choice(SettingChoiceRow(id, title, subtitle, options))
    }
    return PlaybackSettingsSection(
        group = PlaybackSettingsGroup.SKIPPING,
        header = text.header,
        footer = text.footer,
        rows = listOf(
            row("skipIntros", text.intros, text.introsHint, values.skipIntros),
            row("skipRecaps", text.recaps, text.recapsHint, values.skipRecaps),
            row("skipCredits", text.credits, text.creditsHint, values.skipCredits),
        ),
    )
}
