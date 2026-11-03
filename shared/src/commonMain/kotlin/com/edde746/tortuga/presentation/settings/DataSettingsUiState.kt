package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.transfer.DataCategory

/**
 * Settings → Export and import ([DataSettingsStore]): what to export and from which profiles, the export and import
 * actions, the review of an opened file, and the outcome.
 *
 * The platform's part is the file dialog and the bytes on disk, nothing else: while [saveRequest] is set it shows the
 * system's save dialog for that file and reports back ([DataSettingsStore.exportSaved], [DataSettingsStore.exportFailed],
 * [DataSettingsStore.exportCancelled]); the import action opens the system's file picker, and the platform hands the
 * chosen file's text to [DataSettingsStore.open] (or reports [DataSettingsStore.openFailed] when it can't read it as
 * UTF-8 text). Parsing, checking and writing are shared.
 *
 * @param exportProfiles one row per profile; empty with a single profile, which every export holds.
 * @param privacyNote shown while the export holds addon links or stream history, which can carry API keys.
 * @param working an export or import is being built or written; the actions are disabled.
 */
data class DataSettingsUiState(
    val loading: Boolean,
    val exportHeader: String,
    val exportCategories: List<DataCategoryRow>,
    val profilesHeader: String,
    val exportProfiles: List<DataProfileRow>,
    val privacyNote: String?,
    val exportNote: String,
    val exportLabel: String,
    val canExport: Boolean,
    val importHeader: String,
    val importNote: String,
    val importLabel: String,
    val canImport: Boolean,
    val working: Boolean,
    val notice: DataNotice?,
    val saveRequest: DataExportFile?,
    val review: DataImportReview?,
)

/** A category to tick ([DataSettingsStore.setExportCategory], [DataSettingsStore.setImportCategory]). */
data class DataCategoryRow(val category: DataCategory, val toggle: SettingToggleRow)

/** A profile to tick ([DataSettingsStore.setExportProfile]). */
data class DataProfileRow(val profileId: String, val toggle: SettingToggleRow)

/** The outcome of the last export or import; [error] when it failed or the file was refused. */
data class DataNotice(val text: String, val error: Boolean)

/** A built export the platform saves: [text] as UTF-8 into a file the user names, [fileName] proposed. */
data class DataExportFile(val fileName: String, val text: String)

/**
 * An opened file before it is imported: where it comes from, its categories with what each holds for the ticked
 * profiles, and its profiles with where each goes. [DataSettingsStore.confirmImport] imports it,
 * [DataSettingsStore.cancelImport] drops it.
 */
data class DataImportReview(
    val title: String,
    val source: String,
    val dataHeader: String,
    val categories: List<DataCategoryRow>,
    val profilesHeader: String,
    val profiles: List<DataImportProfileRow>,
    val note: String,
    val confirmLabel: String,
    val canConfirm: Boolean,
    val cancelLabel: String,
)

/**
 * A profile of the file: ticked or not ([DataSettingsStore.setImportProfile]) and where it goes. A profile this
 * account has (the same profile, exported earlier) goes to it: no [target], [toggle]'s subtitle says so. Otherwise
 * [target] picks a profile here or a new one ([DataSettingsStore.setImportTarget]; [NEW_PROFILE] is the new one),
 * enabled while the profile is ticked.
 */
data class DataImportProfileRow(val fileProfileId: String, val toggle: SettingToggleRow, val target: SettingChoiceRow?) {
    companion object {
        /** The [target] option of a new profile with the file profile's name and avatar. */
        const val NEW_PROFILE: String = "new"
    }
}
