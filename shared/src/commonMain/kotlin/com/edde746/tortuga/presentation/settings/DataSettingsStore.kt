package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.profile.ProfileRepository
import com.edde746.tortuga.data.sync.ClockDriftException
import com.edde746.tortuga.data.transfer.DataCategory
import com.edde746.tortuga.data.transfer.DataTransfer
import com.edde746.tortuga.data.transfer.ImportOutcome
import com.edde746.tortuga.data.transfer.ImportTarget
import com.edde746.tortuga.data.transfer.Reason
import com.edde746.tortuga.data.transfer.TransferFile
import com.edde746.tortuga.data.transfer.TransferFileException
import com.edde746.tortuga.data.transfer.count
import com.edde746.tortuga.data.transfer.exportedInstant
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.calendar.isoDate
import com.edde746.tortuga.presentation.calendar.localDay
import com.edde746.tortuga.presentation.detail.DetailText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DataTransfer"

/**
 * Settings → Export and import: exports the ticked categories of the ticked
 * profiles to Tortuga's file, and imports such a file after the user reviewed it (which categories, which profiles,
 * and where each goes). The file is built and read on [work] and written to the database in one transaction; the
 * platform only saves and opens it ([DataSettingsUiState]). Neither the file nor its addon links are ever logged.
 *
 * Call everything on the main thread; [release] ends it.
 *
 * @param utcOffset the device time zone's offset from UTC at an instant (milliseconds since the epoch), in seconds:
 *   the export's file name and an opened file's export date are the device's dates.
 * @param seedAddons gives a profile the import created the default addons, unless the import gave it some.
 */
class DataSettingsStore internal constructor(
    private val profiles: ProfileRepository,
    private val transfer: DataTransfer,
    private val appVersion: String,
    private val now: () -> Long,
    private val utcOffset: (Long) -> Int,
    private val work: CoroutineDispatcher,
    private val seedAddons: suspend (String) -> Unit,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var local: List<Profile>? = null
    private var exportCategories: Set<DataCategory> = DataCategory.entries.toSet()
    private var exportExcluded: Set<String> = emptySet()
    private var working = false
    private var notice: DataNotice? = null
    private var saveRequest: DataExportFile? = null

    private var opened: TransferFile? = null
    private var importCategories: Set<DataCategory> = emptySet()
    private var importProfiles: Set<String> = emptySet()
    private var importTargets: Map<String, String> = emptyMap()

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<DataSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            profiles.observeProfiles().collect {
                local = it
                publish()
            }
        }
    }

    fun setExportCategory(category: DataCategory, enabled: Boolean) {
        exportCategories = if (enabled) exportCategories + category else exportCategories - category
        publish()
    }

    fun setExportProfile(profileId: String, enabled: Boolean) {
        exportExcluded = if (enabled) exportExcluded - profileId else exportExcluded + profileId
        publish()
    }

    /** Builds the export; the platform then saves [DataSettingsUiState.saveRequest]. */
    fun export() {
        if (!mutableState.value.canExport) return
        val profileIds = exportProfileIds()
        val categories = exportCategories
        notice = null
        perform(T.settings.data.exportFailed) {
            val nowMillis = now()
            val file = transfer.export(profileIds, categories, appVersion, nowMillis)
            val text = withContext(work) { file.encode() }
            Log.i(TAG) { "Export built: ${profileIds.size} profiles, ${file.records.size} records" }
            saveRequest = DataExportFile("tortuga-${isoDate(localDay(nowMillis, utcOffset))}.json", text)
        }
    }

    /** The platform saved [DataSettingsUiState.saveRequest]. */
    fun exportSaved() {
        val file = saveRequest ?: return
        saveRequest = null
        notice = DataNotice(T.settings.data.saved(isolate(file.fileName)), error = false)
        publish()
    }

    /** The platform couldn't write the file. */
    fun exportFailed() {
        saveRequest ?: return
        saveRequest = null
        notice = DataNotice(T.settings.data.saveFailed, error = true)
        publish()
    }

    /** The user closed the save dialog without saving. */
    fun exportCancelled() {
        saveRequest = null
        publish()
    }

    /** Reads the file the user picked, [text] as the platform read it (UTF-8); a file it refuses becomes the notice. */
    fun open(text: String) {
        if (working) return
        notice = null
        perform(T.settings.data.unreadable) {
            val file = try {
                withContext(work) { TransferFile.decode(text) }
            } catch (e: TransferFileException) {
                Log.i(TAG) { "Import refused: ${e.reason}" }
                notice = DataNotice(refusal(e.reason), error = true)
                return@perform
            }
            val ids = local.orEmpty().mapTo(HashSet()) { it.id }
            opened = file
            importCategories = file.categories.toSet()
            importProfiles = file.profiles.mapTo(HashSet()) { it.first }
            importTargets = file.profiles.filter { it.first !in ids }.associate { it.first to DataImportProfileRow.NEW_PROFILE }
        }
    }

    /** The platform couldn't read the picked file (or not as UTF-8 text). */
    fun openFailed() {
        notice = DataNotice(T.settings.data.unreadable, error = true)
        publish()
    }

    fun setImportCategory(category: DataCategory, enabled: Boolean) {
        importCategories = if (enabled) importCategories + category else importCategories - category
        publish()
    }

    fun setImportProfile(fileProfileId: String, enabled: Boolean) {
        importProfiles = if (enabled) importProfiles + fileProfileId else importProfiles - fileProfileId
        publish()
    }

    /** Where the file's profile [fileProfileId] goes: a profile id here, or [DataImportProfileRow.NEW_PROFILE]. */
    fun setImportTarget(fileProfileId: String, target: String) {
        if (fileProfileId !in importTargets) return
        importTargets = importTargets + (fileProfileId to target)
        publish()
    }

    /**
     * Imports the reviewed file: the ticked categories of the ticked profiles; a file from a clock far ahead is refused,
     * and an import that fails (the database) says so.
     */
    fun confirmImport() {
        val file = opened ?: return
        if (!canImport()) return
        val categories = importCategories
        val targets = importProfiles.associateWith { id ->
            when (val target = importTargets[id]) {
                null, DataImportProfileRow.NEW_PROFILE -> ImportTarget.New
                else -> ImportTarget.Existing(target)
            }
        }
        perform(T.settings.data.importFailed) {
            // The review closes with the import's notice, whatever it says.
            opened = null
            notice = try {
                val outcome = transfer.import(file, categories, targets)
                outcome.createdProfileIds.forEach { seedAddons(it) }
                Log.i(TAG) { "Imported: ${outcome.counts}, ${outcome.profiles} profiles" }
                DataNotice(outcomeText(outcome), error = false)
            } catch (e: ClockDriftException) {
                Log.w(TAG, e) { "Import refused: the file's clock is ahead" }
                DataNotice(T.settings.data.clockAhead, error = true)
            }
        }
    }

    fun cancelImport() {
        opened = null
        publish()
    }

    fun dismissNotice() {
        notice = null
        publish()
    }

    fun release() {
        scope.cancel()
    }

    /**
     * Runs [block] while [working]; an exception it doesn't handle (the database, the device) ends it with [failed] as
     * the notice.
     */
    private fun perform(failed: String, block: suspend () -> Unit) {
        working = true
        publish()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "Export or import failed (${e::class.simpleName})" }
                notice = DataNotice(failed, error = true)
            } finally {
                working = false
                publish()
            }
        }
    }

    private fun exportProfileIds(): Set<String> = local.orEmpty().map { it.id }.filterTo(HashSet()) { it !in exportExcluded }

    private fun refusal(reason: Reason): String = when (reason) {
        Reason.NOT_TORTUGA -> T.settings.data.notTortuga
        Reason.NEWER -> T.settings.data.newer
        Reason.DAMAGED -> T.settings.data.damaged
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): DataSettingsUiState {
        val text = T.settings.data
        val profiles = local
        val ticked = exportProfileIds()
        return DataSettingsUiState(
            loading = profiles == null,
            exportHeader = text.exportHeader,
            exportCategories = DataCategory.entries.map { category ->
                DataCategoryRow(category, SettingToggleRow("export.${category.name}", categoryTitle(category), categoryHint(category), category in exportCategories))
            },
            profilesHeader = text.profilesHeader,
            exportProfiles = profiles.orEmpty().takeIf { it.size > 1 }.orEmpty().map {
                DataProfileRow(it.id, SettingToggleRow("export.profile.${it.id}", it.name, null, it.id in ticked))
            },
            privacyNote = text.privateNote.takeIf { DataCategory.ADDONS in exportCategories || DataCategory.STREAMS in exportCategories },
            exportNote = text.exportNote,
            exportLabel = text.exportAction,
            canExport = !working && saveRequest == null && exportCategories.isNotEmpty() && ticked.isNotEmpty(),
            importHeader = text.importHeader,
            importNote = text.importNote,
            importLabel = text.importAction,
            canImport = !working && opened == null,
            working = working,
            notice = notice,
            saveRequest = saveRequest,
            review = opened?.let(::review),
        )
    }

    private fun review(file: TransferFile): DataImportReview {
        val text = T.settings.data
        val here = local.orEmpty()
        val exported = file.exportedInstant()
        return DataImportReview(
            title = text.reviewTitle,
            source = if (exported != null) {
                text.source(DetailText.date(localDay(exported.toEpochMilliseconds(), utcOffset)), isolate(file.app))
            } else {
                text.sourceUndated(isolate(file.app))
            },
            dataHeader = text.dataHeader,
            categories = file.categories.map { category ->
                val count = file.count(category, importProfiles)
                DataCategoryRow(category, SettingToggleRow("review.${category.name}", categoryTitle(category), countText(category, count), category in importCategories))
            },
            profilesHeader = text.profilesHeader,
            profiles = file.profiles.map { (id, body) ->
                val same = here.firstOrNull { it.id == id }
                val ticked = id in importProfiles
                DataImportProfileRow(
                    fileProfileId = id,
                    toggle = SettingToggleRow("review.profile.$id", body.name, same?.let { text.intoSame(isolate(it.name)) }, ticked),
                    target = if (same != null) null else targetChoice(id, here, ticked),
                )
            },
            note = text.importNote,
            confirmLabel = text.importConfirm,
            canConfirm = canImport(),
            cancelLabel = T.settings.cancel,
        )
    }

    /** Something is ticked on both sides of the review: a category and a profile. */
    private fun canImport(): Boolean = !working && importProfiles.isNotEmpty() && importCategories.isNotEmpty()

    /** Where a file profile goes; chosen only while it is ticked ([ticked]). */
    private fun targetChoice(fileProfileId: String, here: List<Profile>, ticked: Boolean): SettingChoiceRow {
        val selected = importTargets[fileProfileId] ?: DataImportProfileRow.NEW_PROFILE
        val options = listOf(SettingChoiceOption(DataImportProfileRow.NEW_PROFILE, T.settings.data.newProfile, selected == DataImportProfileRow.NEW_PROFILE)) +
            here.map { SettingChoiceOption(it.id, it.name, selected == it.id) }
        return SettingChoiceRow(
            "review.profile.$fileProfileId.target", T.settings.data.into, null, options, unselectedLabel = T.settings.data.newProfile, enabled = ticked,
        )
    }

    private fun categoryTitle(category: DataCategory): String = with(T.settings.data.categories) {
        when (category) {
            DataCategory.ADDONS -> addons
            DataCategory.LIBRARY -> library
            DataCategory.PROGRESS -> progress
            DataCategory.SETTINGS -> settings
            DataCategory.STREAMS -> streams
        }
    }

    private fun categoryHint(category: DataCategory): String = with(T.settings.data.categories) {
        when (category) {
            DataCategory.ADDONS -> addonsHint
            DataCategory.LIBRARY -> libraryHint
            DataCategory.PROGRESS -> progressHint
            DataCategory.SETTINGS -> settingsHint
            DataCategory.STREAMS -> streamsHint
        }
    }

    private fun countText(category: DataCategory, count: Int): String = with(T.settings.data.counts) {
        when (category) {
            DataCategory.ADDONS -> addons(count)
            DataCategory.LIBRARY, DataCategory.PROGRESS -> titles(count)
            DataCategory.SETTINGS -> settings(count)
            DataCategory.STREAMS -> streams(count)
        }
    }

    /** "Import finished: 1 profile, 3 addons, 12 titles, progress on 40 titles, 5 settings.", leaving out what didn't change. */
    private fun outcomeText(outcome: ImportOutcome): String {
        val text = T.settings.data
        val profiles = if (outcome.profiles > 0) listOf(text.counts.profiles(outcome.profiles)) else emptyList()
        val parts = profiles + outcome.counts.map { (category, count) ->
            if (category == DataCategory.PROGRESS) text.counts.progress(count) else countText(category, count)
        }
        return if (parts.isEmpty()) text.nothingNew else text.imported(parts.joinToString(T.settings.listSeparator))
    }
}
