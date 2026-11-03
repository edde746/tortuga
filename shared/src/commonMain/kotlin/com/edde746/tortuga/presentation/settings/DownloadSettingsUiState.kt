package com.edde746.tortuga.presentation.settings

/**
 * Settings → Downloads ([SettingsSection.DOWNLOADS], where this device has downloads): this device's download settings,
 * never synced, as [note] says. A row is null where it doesn't apply here.
 *
 * @param loading the stored values haven't been read yet; the rows show the defaults meanwhile.
 * @param location where new downloads go, among the places the device offers (Android: its internal storage and the
 *   SD cards or other volumes mounted; [DownloadSettingsStore.choose]); null with one place only (iPhone, iPad,
 *   the Mac) and where downloads go to a folder of the user's ([folder]).
 * @param folder the folder downloads go to and its Change action (desktop): the UI's folder picker hands the picked
 *   folder to [DownloadSettingsStore.setFolder]; null elsewhere.
 * @param wifiOnly downloads use unmetered networks only ([DownloadSettingsStore.toggle]); null where it isn't
 *   offered (desktop).
 * @param atOnce how many downloads transfer at once, 1 to 3 ([DownloadSettingsStore.stepAtOnce]).
 * @param autoRemoveWatched watched downloads are deleted ([DownloadSettingsStore.toggle]).
 * @param subtitleLanguages the subtitle languages saved with each download ([DownloadSettingsStore.openLanguages]):
 *   the profile's subtitle languages until some are chosen here.
 * @param useProfileLanguagesText the label of [DownloadSettingsStore.useProfileLanguages] while languages are chosen
 *   here ("Use my subtitle languages"); null while the profile's apply.
 * @param languagePicker the subtitle languages being edited, or null.
 * @param storage what the downloads take and the space left: "12.3 GB used · 40 GB free".
 * @param deleteAll "Delete all downloads": [DownloadSettingsStore.requestDeleteAll] asks first ([deleteAllDialog]).
 * @param deleteAllDialog Delete all's question while it is asked; [DownloadSettingsStore.confirmDeleteAll] deletes,
 *   [DownloadSettingsStore.dismissDialog] doesn't.
 */
data class DownloadSettingsUiState(
    val loading: Boolean,
    val title: String,
    val note: String,
    val location: SettingChoiceRow?,
    val folder: DownloadFolderRow?,
    val transferHeader: String,
    val wifiOnly: SettingToggleRow?,
    val atOnce: SettingStepperRow,
    val autoRemoveWatched: SettingToggleRow,
    val subtitleLanguages: SettingLanguagesRow,
    val useProfileLanguagesText: String?,
    val languagePicker: LanguagePickerUiState?,
    val storageHeader: String,
    val storage: String,
    val deleteAll: DeleteAllDownloadsRow,
    val deleteAllDialog: DeleteAllDownloadsDialog?,
)

/**
 * The folder downloads go to: [title] and [subtitle], the folder's [path], and [changeLabel] ("Change folder") for the
 * action that opens the platform's folder picker.
 */
data class DownloadFolderRow(val title: String, val subtitle: String, val path: String, val changeLabel: String)

/** "Delete all downloads": [label], [enabled] while something is downloaded. */
data class DeleteAllDownloadsRow(val label: String, val enabled: Boolean)

/** Delete all's question: [title], [message] (it deletes every profile's downloads on this device) and the answers. */
data class DeleteAllDownloadsDialog(val title: String, val message: String, val confirmLabel: String, val cancelLabel: String)
