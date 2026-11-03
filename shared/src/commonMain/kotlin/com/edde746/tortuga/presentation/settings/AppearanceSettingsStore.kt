package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.log.Redactor
import com.edde746.tortuga.presentation.board.PosterTemplate
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings → Appearance of [profileId]: the interface language ([ProfileSetting.InterfaceLanguage]), "Blur unwatched
 * episodes" ([ProfileSetting.BlurUnwatched]), "Hide watched titles" ([ProfileSetting.HideWatched]), "Hide titles under
 * posters" ([ProfileSetting.HidePosterCaptions]) and the poster URL template ([ProfileSetting.PosterTemplate]), in
 * that order, stored per profile at once and synced, the page following the stored values, including changes synced
 * from another device. The switches take changes once the stored values are read. A [kids] profile ignores the
 * template, so its page has every row but that one.
 *
 * Call everything on the main thread; [release] ends it.
 */
class AppearanceSettingsStore internal constructor(
    private val profileId: String,
    private val kids: Boolean,
    private val settings: SettingsRepository,
    private val host: AppearanceSettingsHost,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** The stored values; null until read. */
    private var stored: ProfileSettings? = null

    /** The poster URL template as last stored here or read; empty until read. */
    private var posterTemplate = ""

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<AppearanceSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            settings.observe(profileId).collect {
                if (it != stored) {
                    stored = it
                    posterTemplate = it.posterTemplate
                    publish()
                }
            }
        }
    }

    /**
     * Sets the choice row [key] ([SettingChoiceRow.id]) to its option [optionId]: the interface language, a tag with
     * strings or [SYSTEM_LANGUAGE]; an option the row doesn't offer changes nothing.
     */
    fun choose(key: String, optionId: String) {
        when (key) {
            "interfaceLanguage" -> setInterfaceLanguage(optionId)
            else -> error("Appearance settings have no choice row $key")
        }
    }

    /** Switches the row [key] ([SettingToggleRow.id]) on or off, once the stored values are read. */
    fun toggle(key: String, checked: Boolean) {
        val current = stored
        val (setting, shown) = when (key) {
            "blurUnwatched" -> ProfileSetting.BlurUnwatched to current?.copy(blurUnwatched = checked)
            "hideWatched" -> ProfileSetting.HideWatched to current?.copy(hideWatched = checked)
            "hidePosterCaptions" -> ProfileSetting.HidePosterCaptions to current?.copy(hidePosterCaptions = checked)
            else -> error("Appearance settings have no switch $key")
        }
        stored = shown ?: return
        publish()
        scope.launch { settings.set(profileId, setting, checked) }
    }

    /** Stores [template] as the poster URL template (trimmed; empty uses the addons' posters again). */
    fun setPosterTemplate(template: String) {
        posterTemplate = template.trim()
        publish()
        scope.launch { settings.set(profileId, ProfileSetting.PosterTemplate, posterTemplate) }
    }

    /** The editor's line under [template] while it's typed: why it would be ignored, or null when it's fine (or empty). */
    fun posterTemplateError(template: String): String? =
        if (template.isNotBlank() && !PosterTemplate.valid(template)) T.settings.appearance.posterTemplateInvalid else null

    fun release() {
        scope.cancel()
    }

    /** Stores the interface language [optionId] (a tag with strings, or [SYSTEM_LANGUAGE]) and tells the host. */
    private fun setInterfaceLanguage(optionId: String) {
        val tag = optionId.takeUnless { it == SYSTEM_LANGUAGE }
        if (tag != null && tag !in LanguageChoices.interfaceLanguages().map { it.first }) return
        scope.launch {
            settings.set(profileId, ProfileSetting.InterfaceLanguage, tag)
            host.interfaceLanguageChanged(tag)
            publish()
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): AppearanceSettingsUiState {
        val text = T.settings.appearance
        val values = stored
        fun toggle(id: String, title: String, subtitle: String, checked: Boolean?) =
            SettingToggleRow(id, title, subtitle, checked == true, enabled = values != null)
        return AppearanceSettingsUiState(
            interfaceLanguage = interfaceLanguageRow(values?.interfaceLanguage),
            blurUnwatched = toggle("blurUnwatched", text.blurUnwatched, text.blurUnwatchedHint, values?.blurUnwatched),
            hideWatched = toggle("hideWatched", text.hideWatched, text.hideWatchedHint, values?.hideWatched),
            hidePosterCaptions = toggle("hidePosterCaptions", text.hidePosterCaptions, text.hidePosterCaptionsHint, values?.hidePosterCaptions),
            posterTemplate = if (kids) {
                null
            } else {
                PosterTemplateRow(
                    title = text.posterTemplate,
                    subtitle = text.posterTemplateHint,
                    value = posterTemplate,
                    valueLabel = if (posterTemplate.isEmpty()) text.posterTemplateNone else Redactor.redact(posterTemplate),
                    error = posterTemplateError(posterTemplate),
                    placeholder = text.posterTemplatePlaceholder,
                )
            },
        )
    }

    private fun interfaceLanguageRow(selected: String?): SettingChoiceRow {
        val text = T.settings.appearance
        val options = listOf(SettingChoiceOption(SYSTEM_LANGUAGE, text.interfaceLanguageSystem, selected == null)) +
            LanguageChoices.interfaceLanguages().map { (tag, name) -> SettingChoiceOption(tag, name, selected == tag) }
        // A stored language this build has no strings for shows as its tag.
        return SettingChoiceRow("interfaceLanguage", text.interfaceLanguage, text.interfaceLanguageHint, options, unselectedLabel = selected.orEmpty())
    }

    companion object {
        /** The [SettingChoiceOption.id] of "the system's language". */
        const val SYSTEM_LANGUAGE: String = "system"
    }
}
