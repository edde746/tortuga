package com.edde746.tortuga.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.presentation.downloads.KeepSyncedBody
import com.edde746.tortuga.presentation.downloads.KeepSyncedRow
import com.edde746.tortuga.presentation.downloads.KeepSyncedStopDialog
import com.edde746.tortuga.presentation.downloads.KeepSyncedStore
import com.edde746.tortuga.presentation.downloads.KeepSyncedUiState
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.ui.adaptive.largeFontScale
import com.edde746.tortuga.ui.components.ArtworkRow
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.TimedToast
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/** The Keep synced screen of [app] ([Route.KeepSynced]): its [KeepSyncedStore] lives while the route is on the back stack. */
@Composable
fun KeepSyncedRoute(app: AppSession) {
    val navigator = app.navigator
    // Once per appearance: the entry still draws while it animates out after its route left the stack (its store
    // released), and an entry first composed after that gets no store and draws nothing.
    val store = remember(app) {
        navigator.retain(Route.KeepSynced, KeepSyncedStore.KEY, create = { checkNotNull(app.keepSyncedStore()) }, release = KeepSyncedStore::release)
    } ?: return
    val state by store.state.collectAsState()
    val intents = remember(store) {
        KeepSyncedIntents(
            open = store::open,
            pause = store::pause,
            resume = store::resume,
            stop = store::stop,
            confirmStop = store::confirmStop,
            dismissStop = store::dismissStop,
            dismissNotice = store::dismissNotice,
        )
    }
    KeepSyncedScreen(state, intents, onBack = if (navigator.canGoBack) ({ navigator.back() }) else null)
}

/** What the Keep synced screen asks of [KeepSyncedStore]: each takes a rule's series id where it acts on one. */
class KeepSyncedIntents(
    val open: (metaId: String) -> Unit,
    val pause: (metaId: String) -> Unit,
    val resume: (metaId: String) -> Unit,
    val stop: (metaId: String) -> Unit,
    val confirmStop: (deleteDownloads: Boolean) -> Unit,
    val dismissStop: () -> Unit,
    val dismissNotice: () -> Unit,
)

/**
 * Keep synced (pushed over Downloads or Detail): what a rule does, then the profile's rules as one connected group of
 * rows, each its series' poster, name, the count it keeps ("Keep 3 unwatched episodes") and where it is ("Paused",
 * "Downloading 2 episodes"); a press opens the series. Beside each row, its Pause (Resume while paused) and Stop
 * buttons, the round plain ones of the download rows' ⋮. Stop asks first ([KeepSyncedUiState.stopDialog]): Cancel,
 * keep the downloads, or delete the ones the rule added. At a large font scale a row's text goes under its poster,
 * whole. With no rules, the store's empty text. A failed action shows as a toast at the bottom. Back leaves it.
 *
 * Harness names: `keepSynced.header`, `keepSynced.list`, `keepSynced.note`, `keepSynced.empty`,
 * `keepSynced.rule.<meta id>` with its `.pause`, `.resume` and `.stop`, the question's
 * `keepSynced.stop.<cancel|keep|delete>`, `keepSynced.notice`.
 */
@Composable
fun KeepSyncedScreen(state: KeepSyncedUiState, intents: KeepSyncedIntents, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val focus = rememberFocusMemory()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = statusBarOverlap)) {
            ScreenHeader(state.title, Modifier.contentColumn().layoutProbe("keepSynced.header", reportsFocus = false), onBack = onBack)
            when (val body = state.body) {
                KeepSyncedBody.Loading -> LoadingState()
                is KeepSyncedBody.Empty -> RuleList(state) {
                    item(key = "empty") { EmptyState(body.text, Modifier.fillParentMaxHeight(0.7f).layoutProbe("keepSynced.empty"), icon = TortugaIcons.Sync) }
                }
                is KeepSyncedBody.Rules -> RuleList(state) {
                    itemsIndexed(body.rules, key = { _, row -> ruleKey(row) }) { index, row ->
                        RuleRow(row, groupShape(index, body.rules.size), focus, intents, Modifier.padding(top = if (index > 0) GroupGap else 0.dp))
                    }
                }
            }
        }
        state.notice?.let {
            TimedToast(it, "keepSynced.notice", intents.dismissNotice, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalTortugaDimens.current.sectionSpacing))
        }
    }
    state.stopDialog?.let { StopOverlay(it, intents) }
    val rules = when (val body = state.body) {
        KeepSyncedBody.Loading, is KeepSyncedBody.Empty -> emptyList()
        is KeepSyncedBody.Rules -> body.rules
    }
    RestoreFocus(focus, ready = rules.isNotEmpty(), fallback = rules.firstOrNull()?.let(::ruleKey))
    FollowLeavingRule(rules, focus)
}

private fun ruleKey(row: KeepSyncedRow) = "rule:${row.metaId}"

/**
 * A rule that leaves while focus was last on one of its elements (stopped from its question, which gave focus back to
 * its Stop as it closed, a moment before the rule went) hands focus to the same element of the rule that takes its
 * place, else of the one above it. Left alone, focus would stay where the platform put it as the element left: on
 * Android the window's first control, the header's back button.
 */
@Composable
private fun FollowLeavingRule(rules: List<KeepSyncedRow>, focus: FocusMemory) {
    val ids = rules.map { it.metaId }
    val shown = remember { arrayOf(ids) }
    LaunchedEffect(ids) {
        val before = shown[0]
        shown[0] = ids
        // An element's key is "<kind>:<meta id>": the row's (ruleKey), its Pause's and its Stop's.
        val last = focus.lastFocused ?: return@LaunchedEffect
        val index = before.indexOf(last.substringAfter(':'))
        if (index < 0 || before[index] in ids || focus.focusedKey != null) return@LaunchedEffect
        val next = ids.getOrNull(index) ?: ids.lastOrNull() ?: return@LaunchedEffect
        focus.focus("${last.substringBefore(':')}:$next")
    }
}

/** [state]'s note over [body]: the rules, or that there are none. */
@Composable
private fun RuleList(state: KeepSyncedUiState, body: LazyListScope.() -> Unit) {
    val dimens = LocalTortugaDimens.current
    LazyColumn(
        modifier = Modifier.contentColumn().fillMaxSize().layoutProbe("keepSynced.list", reportsFocus = false),
        state = rememberLazyListState(),
        // Room at the top for a focused button's growth (TV).
        contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.focusReach, bottom = dimens.sectionSpacing),
    ) {
        state.note?.let { note ->
            item(key = "note") {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TortugaColors.TextMuted,
                    modifier = Modifier.padding(start = RowInset, end = RowInset, bottom = 12.dp).layoutProbe("keepSynced.note"),
                )
            }
        }
        body()
    }
}

/**
 * One rule: its series' poster, name, count and state as the row (a press opens the series), then its Pause or Resume
 * and its Stop beside it, so a remote reaches them with Right. Pause and Resume are one button whose icon and action
 * follow the rule, so focus stays on it as the rule pauses or resumes. At a large font scale the text goes under the
 * poster.
 */
@Composable
private fun RuleRow(row: KeepSyncedRow, shape: Shape, focus: FocusMemory, intents: KeepSyncedIntents, modifier: Modifier) {
    val probe = "keepSynced.rule.${row.metaId}"
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TortugaSurface(
            onClick = { intents.open(row.metaId) },
            modifier = Modifier.weight(1f).rememberFocus(focus, ruleKey(row)).layoutProbe(probe),
            shape = shape,
            grows = false,
        ) {
            val stacked = largeFontScale()
            ArtworkRow(stacked, gap = RowInset, artwork = { Artwork(row.poster, poster = true) }, modifier = Modifier.padding(RowInset)) {
                val muted = LocalContentColor.current.copy(alpha = 0.6f)
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold).contentDirection(),
                    maxLines = if (stacked) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(row.countText, style = MaterialTheme.typography.bodySmall, maxLines = if (stacked) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                Text(row.statusText, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = if (stacked) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // One button for both, so the press that pauses or resumes leaves focus where it was.
        TortugaIconButton(
            if (row.paused) TortugaIcons.PlayArrow else TortugaIcons.Pause,
            row.pauseLabel,
            { if (row.paused) intents.resume(row.metaId) else intents.pause(row.metaId) },
            Modifier.rememberFocus(focus, "pause:${row.metaId}").layoutProbe(if (row.paused) "$probe.resume" else "$probe.pause"),
            style = IconButtonStyle.PLAIN,
        )
        TortugaIconButton(
            TortugaIcons.Close,
            row.stopLabel,
            { intents.stop(row.metaId) },
            Modifier.rememberFocus(focus, "stop:${row.metaId}").layoutProbe("$probe.stop"),
            style = IconButtonStyle.PLAIN,
        )
    }
}

/** The question before a rule stops: Cancel (focus first), keep the downloads, or delete what the rule added. */
@Composable
private fun StopOverlay(dialog: KeepSyncedStopDialog, intents: KeepSyncedIntents) {
    val cancelFocus = remember { FocusRequester() }
    ModalOverlay(title = dialog.title, onDismiss = intents.dismissStop, initialFocus = cancelFocus) {
        Text(dialog.message, style = MaterialTheme.typography.bodyMedium)
        OverlayActions {
            TortugaButton(dialog.cancelLabel, intents.dismissStop, Modifier.focusRequester(cancelFocus).layoutProbe("keepSynced.stop.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(dialog.keepLabel, { intents.confirmStop(false) }, Modifier.layoutProbe("keepSynced.stop.keep"), style = ButtonStyle.SECONDARY)
            dialog.deleteLabel?.let {
                TortugaButton(it, { intents.confirmStop(true) }, Modifier.layoutProbe("keepSynced.stop.delete"), style = ButtonStyle.DESTRUCTIVE)
            }
        }
    }
}
