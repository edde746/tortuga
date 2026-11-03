package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.collections.CollectionImportStep
import com.edde746.tortuga.presentation.collections.CollectionImportStore
import com.edde746.tortuga.presentation.collections.CollectionImportUiState
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.focusGrowth
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * Import collections over Settings → Home, in a [TortugaSheet], over [store]: the page's note, the link's field with
 * Fetch (Enter fetches too) and, where the platform has file dialogs, Open file; then, step by step
 * ([CollectionImportUiState.step]), a spinner while the file comes, why it failed under the field, or the summary: the
 * file's collections (each added or replacing one of the profile's) and what the import leaves out or needs (addons to
 * install, trackers to connect, unsupported sources), with Import; once imported, what happened and Done. Back, a click
 * outside and Done close it ([onClose]). On TV the keyboard waits for the field to be pressed. The summary scrolls
 * between the title and Import, which stays in view under it; on TV each of its collections and lines is a stop of the
 * D-pad (a ring, no growth) that scrolls into view as it takes focus, so Up from Import walks the whole summary.
 *
 * Harness names: `collections.import` (the sheet), `collections.import.url`, `collections.import.fetch`,
 * `collections.import.open`, `collections.import.error`, `collections.import.summary`,
 * `collections.import.collection.<i>`, `collections.import.line.<i>`, `collections.import.confirm`,
 * `collections.import.done`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CollectionImportSheet(store: CollectionImportStore, onClose: () -> Unit) {
    val state by store.state.collectAsState()
    val platform = LocalTortugaPlatform.current
    val primary = remember { FocusRequester() }
    TortugaSheet(onDismiss = onClose, title = state.title, initialFocus = primary, probe = "collections.import") {
        when (val step = state.step) {
            is CollectionImportStep.Summary -> ImportSummaryStep(step, primary, store::confirm)
            is CollectionImportStep.Done -> {
                Text(step.message, style = MaterialTheme.typography.bodyLarge)
                OverlayActions {
                    TortugaButton(step.doneLabel, onClose, Modifier.focusRequester(primary).layoutProbe("collections.import.done"), style = ButtonStyle.PRIMARY)
                }
            }
            else -> {
                // The field keeps its own text: the store hears it on Fetch, from the button, Enter or the keyboard's Go.
                val link = remember { TextFieldState() }
                val loading = step == CollectionImportStep.Loading
                val fetch = { if (!loading && link.text.isNotBlank()) store.fetch(link.text.toString().trim()) }
                Text(state.note, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
                LinkInput(
                    link, state.urlLabel, error = step is CollectionImportStep.Failed, onSubmit = fetch,
                    modifier = Modifier.focusRequester(primary).layoutProbe("collections.import.url"), hint = state.urlHint,
                )
                when (step) {
                    CollectionImportStep.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
                    is CollectionImportStep.Failed -> InlineError(step.message, Modifier.layoutProbe("collections.import.error", reportsFocus = false))
                    else -> {}
                }
                OverlayActions {
                    state.openFileLabel?.let { label ->
                        TortugaButton(
                            label,
                            { platform.openFile({ _, text -> store.open(text) }, store::openFailed) },
                            Modifier.layoutProbe("collections.import.open"),
                            icon = TortugaIcons.Download,
                            enabled = !loading,
                        )
                    }
                    TortugaButton(state.fetchLabel, { fetch() }, Modifier.layoutProbe("collections.import.fetch"), style = ButtonStyle.PRIMARY, enabled = !loading)
                }
            }
        }
    }
    // Each step's main control takes focus as the step appears.
    LaunchedEffect(state.step::class) {
        withFrameNanos { }
        primary.requestFocus()
    }
}

/**
 * The summary step: the summary, scrolling in the room it has, then Import ([primary]) under it. On TV Up from Import
 * goes to the summary's last row or line, wherever it is scrolled: the lines below its room lie under Import, where the
 * D-pad's search wouldn't look.
 */
@Composable
internal fun ColumnScope.ImportSummaryStep(step: CollectionImportStep.Summary, primary: FocusRequester, onConfirm: () -> Unit) {
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val last = remember { FocusRequester() }
    val stops = tv && (step.collections.isNotEmpty() || step.lines.isNotEmpty())
    ImportSummary(step, tv, last)
    OverlayActions {
        TortugaButton(
            step.importLabel,
            onConfirm,
            Modifier
                .focusRequester(primary)
                .then(if (stops) Modifier.focusProperties { up = last } else Modifier)
                .layoutProbe("collections.import.confirm"),
            style = ButtonStyle.PRIMARY,
            enabled = step.canImport,
        )
    }
}

/**
 * The file's collections as one group of rows, then each line of the summary. Scrolls when taller than its room; on TV
 * each row and line takes focus in turn, scrolled into view as it does, the last of them [last].
 */
@Composable
private fun ColumnScope.ImportSummary(step: CollectionImportStep.Summary, tv: Boolean, last: FocusRequester) {
    val lineShape = MaterialTheme.shapes.small
    val lastLine = step.lines.lastIndex
    fun Modifier.stop(shape: Shape, isLast: Boolean): Modifier = when {
        !tv -> this
        isLast -> focusRequester(last).focusGrowth(shape, ring = true, grows = false).focusable()
        else -> focusGrowth(shape, ring = true, grows = false).focusable()
    }
    Column(
        Modifier
            .weight(1f, fill = false)
            .layoutProbe("collections.import.summary", reportsFocus = false)
            .verticalScroll(rememberScrollState())
            .then(if (tv) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
    ) {
        step.collections.forEachIndexed { index, row ->
            val shape = groupShape(index, step.collections.size)
            GroupRow(
                row.title,
                onClick = null,
                modifier = Modifier.layoutProbe("collections.import.collection.$index").stop(shape, isLast = lastLine < 0 && index == step.collections.lastIndex),
                shape = shape,
                // What it adds or replaces, then its first folders.
                subtitle = "${row.detail}\n${row.folders}",
                icon = if (row.replaces) TortugaIcons.Refresh else TortugaIcons.Add,
            )
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            step.lines.forEachIndexed { index, line ->
                Text(
                    line,
                    modifier = Modifier.layoutProbe("collections.import.line.$index").stop(lineShape, isLast = index == lastLine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TortugaColors.TextMuted,
                )
            }
        }
    }
}
