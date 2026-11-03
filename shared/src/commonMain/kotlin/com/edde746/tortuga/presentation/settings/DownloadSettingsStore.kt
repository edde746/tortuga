package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.downloads.DownloadLocation
import com.edde746.tortuga.downloads.DownloadLocations
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadStorage
import com.edde746.tortuga.presentation.downloads.DownloadRows
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings → Downloads: where downloads go ([DownloadManager.locations], or a folder of the user's where the platform
 * [chooses one][DownloadManager.choosesFolder]), Wi-Fi only (where [offered][DownloadManager.offersWifiOnly]; its
 * default is the platform's), downloads at once, auto-remove watched, the subtitle languages saved with each download
 * (the profile's subtitle languages until some are chosen here), the storage used and free, and Delete all, which
 * deletes every download on this device, every profile's. Values are this device's ([DeviceSetting]), never synced;
 * every change is written at once and the screen follows what is stored. A step or a list edit applies to the stored
 * value, so quick edits add up.
 *
 * Call everything on the main thread; [release] ends it.
 */
class DownloadSettingsStore internal constructor(
    private val downloads: DownloadManager,
    private val deviceSettings: DeviceSettingsRepository,
    /** The profile's settings: its subtitle languages, the default of the ones saved. */
    private val profileSettings: Flow<ProfileSettings>,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var stored: DeviceSettings? = null
    private var profile: ProfileSettings? = null
    private var locations: DownloadLocations? = null
    private var storage: DownloadStorage? = null
    private var anyDownload = false
    private var picking = false
    private var query = ""
    private var askingDeleteAll = false
    private var languages: Pair<String, List<SettingLanguageOption>>? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<DownloadSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            deviceSettings.observe().collect {
                stored = it
                publish()
            }
        }
        scope.launch {
            profileSettings.collect {
                profile = it
                publish()
            }
        }
        scope.launch {
            downloads.locations.collect {
                locations = it
                publish()
            }
        }
        scope.launch {
            downloads.storage.collect {
                storage = it
                publish()
            }
        }
        scope.launch {
            downloads.all.collect {
                anyDownload = it.isNotEmpty()
                publish()
            }
        }
        downloads.refreshStorage()
    }

    /**
     * Sets the choice row [key] ([SettingChoiceRow.id]) to its option [optionId]: new downloads go to the location
     * [optionId] of [DownloadSettingsUiState.location]. An option the row doesn't offer changes nothing.
     */
    fun choose(key: String, optionId: String) {
        when (key) {
            "location" -> if (locations?.locations?.any { it.id == optionId } == true) downloads.setLocation(optionId)
            else -> error("Download settings have no choice row $key")
        }
    }

    /** Switches the row [key] ([SettingToggleRow.id]) on or off: Wi-Fi only, auto-remove watched. */
    fun toggle(key: String, checked: Boolean) {
        when (key) {
            "wifiOnly" -> write(DeviceSetting.DownloadWifiOnly, checked)
            "autoRemoveWatched" -> write(DeviceSetting.DownloadAutoRemoveWatched, checked)
            else -> error("Download settings have no switch $key")
        }
    }

    /** New downloads go to the folder at [path], picked with the platform's folder picker ([DownloadSettingsUiState.folder]). */
    fun setFolder(path: String) {
        if (downloads.choosesFolder && path.isNotBlank()) downloads.setLocation(DownloadLocation.folder(path).id)
    }

    /** One step up or down of downloads at once, within 1 to 3. */
    fun stepAtOnce(up: Boolean) {
        scope.launch { deviceSettings.update(DeviceSetting.DownloadsAtOnce) { AT_ONCE_SPEC.stepped(it, up) } }
    }

    // ---- Subtitle languages ----------------------------------------------------------------------------------------

    fun openLanguages() {
        picking = true
        query = ""
        publish()
    }

    fun closeLanguages() {
        picking = false
        query = ""
        publish()
    }

    fun searchLanguages(query: String) {
        this.query = query
        publish()
    }

    /** Adds [code] as the last language saved; the list is this device's from then on. */
    fun addLanguage(code: String) = editLanguages { if (code in it) it else it + code }

    fun removeLanguage(code: String) = editLanguages { it - code }

    fun moveLanguage(code: String, up: Boolean) = editLanguages { list ->
        val index = list.indexOf(code)
        if (index < 0) list else LanguageChoices.moved(list, index, if (up) index - 1 else index + 1)
    }

    /** Moves [code] to [index] of the chosen list (dragging). */
    fun moveLanguageTo(code: String, index: Int) = editLanguages { list ->
        val from = list.indexOf(code)
        if (from < 0) list else LanguageChoices.moved(list, from, index)
    }

    /** Saves the profile's subtitle languages again, as they are and as they change. */
    fun useProfileLanguages() {
        scope.launch { deviceSettings.reset(DeviceSetting.DownloadSubtitleLanguages) }
    }

    // ---- Storage ---------------------------------------------------------------------------------------------------

    /** Asks before deleting every download ([DownloadSettingsUiState.deleteAllDialog]); nothing to delete, nothing asked. */
    fun requestDeleteAll() {
        if (!anyDownload) return
        askingDeleteAll = true
        publish()
    }

    /** Answers Delete all's question: deletes every download on this device, every profile's. */
    fun confirmDeleteAll() {
        if (!askingDeleteAll) return
        askingDeleteAll = false
        downloads.deleteAll()
        publish()
    }

    /** Closes Delete all's question; nothing is deleted. */
    fun dismissDialog() {
        askingDeleteAll = false
        publish()
    }

    /** Reads the free space again (the section shown again). */
    fun refreshStorage() = downloads.refreshStorage()

    fun release() {
        scope.cancel()
    }

    /** Stores what [change] makes of the languages saved: this device's list, else the profile's ([savedLanguages]). */
    private fun editLanguages(change: (List<String>) -> List<String>) {
        val profileLanguages = profile?.savedSubtitleLanguages.orEmpty()
        scope.launch {
            deviceSettings.update(DeviceSetting.DownloadSubtitleLanguages) { stored ->
                val current = stored ?: profileLanguages
                change(current).takeUnless { it == current } ?: stored
            }
        }
    }

    /** The languages saved with each download: this device's choice, else the profile's subtitle and second subtitle languages. */
    private fun savedLanguages(): List<String> = current().downloadSubtitleLanguages ?: profile?.savedSubtitleLanguages.orEmpty()

    private fun <T> write(setting: DeviceSetting<T>, value: T) {
        scope.launch { deviceSettings.set(setting, value) }
    }

    private fun current(): DeviceSettings = stored ?: DeviceSettings.DEFAULT

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): DownloadSettingsUiState {
        val values = current()
        val text = T.settings.downloads
        val locale = T.locale
        val places = locations
        val selected = places?.locations?.firstOrNull { it.id == places.selected }
        val ownLanguages = values.downloadSubtitleLanguages
        val profileLanguages = LanguageChoices.list(profile?.savedSubtitleLanguages.orEmpty(), locale)
        return DownloadSettingsUiState(
            loading = stored == null || profile == null || places == null,
            title = text.title,
            note = text.note,
            location = places?.takeIf { !downloads.choosesFolder && it.locations.size > 1 }?.let {
                SettingChoiceRow(
                    id = "location",
                    title = text.location.title,
                    subtitle = text.location.hint,
                    options = it.locations.map { location -> SettingChoiceOption(location.id, location.label, location.id == it.selected) },
                )
            },
            folder = if (downloads.choosesFolder) {
                DownloadFolderRow(text.location.title, text.location.hint, selected?.path.orEmpty(), text.location.change)
            } else {
                null
            },
            transferHeader = text.transferHeader,
            wifiOnly = if (downloads.offersWifiOnly) {
                SettingToggleRow("wifiOnly", text.wifiOnly, text.wifiOnlyHint, values.downloadWifiOnly ?: downloads.wifiOnlyDefault)
            } else {
                null
            },
            atOnce = AT_ONCE_SPEC.row(text.atOnce, text.atOnceHint, values.downloadsAtOnce),
            autoRemoveWatched = SettingToggleRow("autoRemoveWatched", text.autoRemoveWatched, text.autoRemoveWatchedHint, values.downloadAutoRemoveWatched),
            subtitleLanguages = SettingLanguagesRow(
                title = text.subtitleLanguages,
                subtitle = text.subtitleLanguagesHint,
                valueLabel = if (ownLanguages != null) {
                    LanguageChoices.list(ownLanguages, locale) ?: T.settings.playback.subtitleLanguagesNone
                } else {
                    profileLanguages?.let(text::subtitleLanguagesProfile) ?: text.subtitleLanguagesProfileNone
                },
            ),
            useProfileLanguagesText = text.useProfileLanguages.takeIf { ownLanguages != null },
            languagePicker = if (picking) picker(locale) else null,
            storageHeader = text.storageHeader,
            storage = DownloadRows.storage(storage ?: DownloadStorage(usedBytes = 0, freeBytes = null)),
            deleteAll = DeleteAllDownloadsRow(text.deleteAll, enabled = anyDownload),
            deleteAllDialog = if (askingDeleteAll) {
                DeleteAllDownloadsDialog(text.deleteAllTitle, text.deleteAllText, text.deleteAllConfirm, T.settings.cancel)
            } else {
                null
            },
        )
    }

    private fun picker(locale: String): LanguagePickerUiState {
        val text = T.settings.downloads
        val all = languages?.takeIf { it.first == locale }?.second
            ?: LanguageChoices.preferenceLanguages(locale).also { languages = locale to it }
        return LanguageChoices.picker(
            kind = LanguageListKind.SUBTITLES,
            title = text.subtitleLanguages,
            explanation = text.subtitleLanguagesExplanation,
            noneChosenText = T.settings.playback.subtitleLanguagesNone,
            chosen = savedLanguages(),
            all = all,
            query = query,
            locale = locale,
        )
    }
}

/** Downloads at once: 1 to 3 ([DeviceSetting.DownloadsAtOnce]). */
private val AT_ONCE_SPEC = StepperSpec(min = 1, max = 3, step = 1) { it.toString() }
