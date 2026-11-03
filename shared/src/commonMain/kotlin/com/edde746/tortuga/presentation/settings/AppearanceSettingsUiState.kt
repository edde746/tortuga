package com.edde746.tortuga.presentation.settings

/**
 * Settings → Appearance: the profile's language and how its screens show titles (per profile, synced), its rows in
 * this order. Kids profiles have them all but [posterTemplate].
 *
 * @param interfaceLanguage the interface language ([com.edde746.tortuga.data.settings.ProfileSetting.InterfaceLanguage]):
 *   `AppearanceSettingsStore.choose` takes its [SettingChoiceRow.id] and an option's id.
 * @param blurUnwatched "Blur unwatched episodes" ([com.edde746.tortuga.data.settings.ProfileSetting.BlurUnwatched]);
 *   `AppearanceSettingsStore.toggle` takes its [SettingToggleRow.id], as it takes the other switches'.
 * @param hideWatched "Hide watched titles" ([com.edde746.tortuga.data.settings.ProfileSetting.HideWatched]).
 * @param hidePosterCaptions "Hide titles under posters" ([com.edde746.tortuga.data.settings.ProfileSetting.HidePosterCaptions]).
 * @param posterTemplate the poster URL template's row; null on a kids profile, which ignores the template.
 */
data class AppearanceSettingsUiState(
    val interfaceLanguage: SettingChoiceRow,
    val blurUnwatched: SettingToggleRow,
    val hideWatched: SettingToggleRow,
    val hidePosterCaptions: SettingToggleRow,
    val posterTemplate: PosterTemplateRow?,
)

/**
 * The poster URL template (per profile, synced): a row showing [valueLabel] (the template's origin alone, its key
 * hidden as logs hide it, or "Addon posters" while empty) that opens an editor with [value], [placeholder] as its
 * example, [subtitle] (what the placeholders stand for) under it, and Save and Clear. The template usually holds a
 * poster service's key: each UI's field is marked sensitive (no autocorrect or suggestions).
 *
 * @param error the stored template isn't an `http` or `https` address, so it is ignored: the text to show on the row.
 */
data class PosterTemplateRow(
    val title: String,
    val subtitle: String,
    val value: String,
    val valueLabel: String,
    val error: String?,
    val placeholder: String,
)

/** What the appearance settings ask of the app around them; called on the main thread. */
interface AppearanceSettingsHost {
    /**
     * The profile's interface language changed on this screen to [tag] (BCP 47), or to the system's language for
     * null: set `T.locale` now. The store republishes its text right after. (A change synced from another device
     * reaches the app through the settings flow, like any other setting.)
     */
    fun interfaceLanguageChanged(tag: String?)
}
