package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.data.transfer.DataCategory
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.presentation.settings.DataImportReview
import com.edde746.tortuga.presentation.settings.DataSettingsStore
import com.edde746.tortuga.presentation.settings.DataSettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.app.SaveResult
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors

/** What the export and import section asks of [DataSettingsStore] and the platform; the route builds it from their methods. */
internal class DataSettingsIntents(
    val setExportCategory: (category: DataCategory, enabled: Boolean) -> Unit,
    val setExportProfile: (profileId: String, enabled: Boolean) -> Unit,
    val export: () -> Unit,

    /** Import: the platform's open dialog, whose file goes to the store. */
    val import: () -> Unit,
    val dismissNotice: () -> Unit,
    val setImportCategory: (category: DataCategory, enabled: Boolean) -> Unit,
    val setImportProfile: (fileProfileId: String, enabled: Boolean) -> Unit,
    val setImportTarget: (fileProfileId: String, target: String) -> Unit,
    val confirmImport: () -> Unit,
    val cancelImport: () -> Unit,
)

/**
 * The export and import section over [store]: the pane, the platform's save dialog while the store has a file to save
 * (`saveRequest`, once per file), its open dialog from Import ([com.edde746.tortuga.ui.app.TortugaPlatform.openFile]),
 * and the review of an opened file in a sheet. Offered only where the platform has file dialogs
 * (`AppPlatform.hasFileDialogs`).
 */
@Composable
internal fun DataSettingsRoute(store: DataSettingsStore) {
    val state by store.state.collectAsState()
    val platform = LocalTortugaPlatform.current
    val intents = remember(store, platform) {
        DataSettingsIntents(
            setExportCategory = store::setExportCategory,
            setExportProfile = store::setExportProfile,
            export = store::export,
            import = { platform.openFile({ _, text -> store.open(text) }, store::openFailed) },
            dismissNotice = store::dismissNotice,
            setImportCategory = store::setImportCategory,
            setImportProfile = store::setImportProfile,
            setImportTarget = store::setImportTarget,
            confirmImport = store::confirmImport,
            cancelImport = store::cancelImport,
        )
    }
    val request = state.saveRequest
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        platform.saveFile(request.fileName, request.text) { result ->
            when (result) {
                SaveResult.SAVED -> store.exportSaved()
                SaveResult.FAILED -> store.exportFailed()
                SaveResult.CANCELLED -> store.exportCancelled()
            }
        }
    }
    DataSettingsPane(state, intents)
    state.review?.let { DataImportSheet(it, intents) }
}

/**
 * Export and import, as plain settings rows: the categories to export, then the profiles (with two or
 * more), then the warning that the file holds API keys (while addons or stream history are ticked) above Export, which
 * shows a spinner while the file is built or written; the outcome of the last export or import (an error with the
 * error symbol) until dismissed, between Export and Import so that it shows beside the action just taken; Import, which
 * opens a file.
 *
 * Harness names: `settings.data.export.<addons|library|progress|settings|streams>`, `settings.data.export.profile.<i>`,
 * `settings.data.export.private`, `settings.data.export`, `settings.data.notice`, `settings.data.import`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DataSettingsPane(state: DataSettingsUiState, intents: DataSettingsIntents, modifier: Modifier = Modifier) {
    SettingsList(modifier) {
        settingsGroup(
            "export",
            state.exportHeader,
            state.exportCategories.map { row ->
                SettingsItem("export.${row.category.name}") { shape ->
                    SettingToggleItem(row.toggle, { intents.setExportCategory(row.category, it) }, shape, Modifier.layoutProbe("settings.data.export.${row.category.probeName}"))
                }
            },
        )
        settingsGroup(
            "profiles",
            state.profilesHeader,
            state.exportProfiles.mapIndexed { index, row ->
                SettingsItem("profile:${row.profileId}") { shape ->
                    SettingToggleItem(
                        row.toggle,
                        { intents.setExportProfile(row.profileId, it) },
                        shape,
                        Modifier.layoutProbe("settings.data.export.profile.$index"),
                        contentTitle = true,
                    )
                }
            },
        )
        settingsGroup(
            "exportAction",
            null,
            listOf(
                state.privacyNote?.let { note ->
                    SettingsItem("private") { shape ->
                        NoteLine(
                            note,
                            SettingsIcons.Warning,
                            Modifier
                                .layoutProbe("settings.data.export.private", reportsFocus = false)
                                .fillMaxWidth()
                                .background(TortugaColors.SurfaceHigh, shape)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                },
                SettingsItem("export") { shape ->
                    GroupRow(
                        state.exportLabel,
                        onClick = intents.export,
                        modifier = Modifier.layoutProbe("settings.data.export"),
                        shape = shape,
                        icon = SettingsIcons.Upload,
                        enabled = state.canExport,
                        trailing = if (state.working) ({ LoadingIndicator(Modifier.size(24.dp)) }) else null,
                    )
                },
            ),
        )
        item(key = "exportNote") { SettingsNote(state.exportNote) }
        state.notice?.let { notice ->
            item(key = "notice") {
                NoticeBanner(notice.text, T.settings.close, intents.dismissNotice, Modifier.layoutProbe("settings.data.notice", reportsFocus = false), error = notice.error)
            }
        }
        settingsGroup(
            "import",
            state.importHeader,
            listOf(
                SettingsItem("import") { shape ->
                    GroupRow(
                        state.importLabel,
                        onClick = intents.import,
                        modifier = Modifier.layoutProbe("settings.data.import"),
                        shape = shape,
                        icon = TortugaIcons.Download,
                        enabled = state.canImport,
                    )
                },
            ),
        )
        item(key = "importNote") { SettingsNote(state.importNote) }
    }
}

/**
 * An opened file before it is imported, in a [TortugaSheet]: where it comes from, its categories with what each holds,
 * and its profiles, each ticked or not, with where it goes (a profile here or a new one; not while unticked) unless it
 * is a profile this account has, which it goes to. Cancel and Import at its foot; Back or Escape, like Cancel, drops
 * the file. Focus starts on the first category.
 *
 * Harness names: `settings.data.review` (the sheet), `settings.data.review.source`, `settings.data.review.<category>`,
 * `settings.data.review.profile.<i>` with its `.target` (and `.target.option.<j>`), `settings.data.review.<cancel|confirm>`.
 */
@Composable
internal fun DataImportSheet(review: DataImportReview, intents: DataSettingsIntents) {
    TortugaSheet(onDismiss = intents.cancelImport, title = review.title, probe = "settings.data.review") {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            NoteRow(review.source, SettingsIcons.Description, groupShape(0, 1), Modifier.layoutProbe("settings.data.review.source", reportsFocus = false))
            GroupHeader(review.dataHeader)
            review.categories.forEachIndexed { index, row ->
                SettingToggleItem(
                    row.toggle,
                    { intents.setImportCategory(row.category, it) },
                    groupShape(index, review.categories.size),
                    Modifier.layoutProbe("settings.data.review.${row.category.probeName}"),
                )
            }
            GroupHeader(review.profilesHeader)
            val count = review.profiles.sumOf { if (it.target != null) 2 else 1 }
            var place = 0
            review.profiles.forEachIndexed { index, row ->
                SettingToggleItem(
                    row.toggle,
                    { intents.setImportProfile(row.fileProfileId, it) },
                    groupShape(place++, count),
                    Modifier.layoutProbe("settings.data.review.profile.$index"),
                    contentTitle = true,
                )
                row.target?.let { target ->
                    SettingChoiceItem(
                        target,
                        { intents.setImportTarget(row.fileProfileId, it) },
                        "settings.data.review.profile.$index.target",
                        groupShape(place++, count),
                    )
                }
            }
            SettingsNote(review.note)
        }
        OverlayActions {
            TortugaButton(review.cancelLabel, intents.cancelImport, Modifier.layoutProbe("settings.data.review.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(
                review.confirmLabel,
                intents.confirmImport,
                Modifier.layoutProbe("settings.data.review.confirm"),
                style = ButtonStyle.PRIMARY,
                enabled = review.canConfirm,
            )
        }
    }
}

/** A row that only tells something, in full: [icon] and [text] on the rows' fill. */
@Composable
private fun NoteRow(text: String, icon: ImageVector, shape: Shape, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHigh, shape)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = LocalContentColor.current.copy(alpha = 0.7f))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
