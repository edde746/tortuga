package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingStepperRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.presentation.settings.SubtitleNumber
import com.edde746.tortuga.presentation.settings.SubtitlePreview
import com.edde746.tortuga.presentation.settings.SubtitleSettingsStore
import com.edde746.tortuga.presentation.settings.SubtitleSettingsUiState
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens

/** The subtitles section over [store]. */
@Composable
internal fun SubtitleSettingsRoute(store: SubtitleSettingsStore) {
    val state by store.state.collectAsState()
    SubtitleSettingsPane(state = state, onStep = store::stepNumber, onChoose = store::choose, onToggle = store::toggle)
}

/**
 * This device's subtitle style (never synced, as the note says) with its preview, which stays in view while the rows
 * scroll: beside them on wide panes (TV, desktop, tablets), above them on narrow ones.
 *
 * A choice gives its row's id and the option's to [onChoose], a switch its row's id to [onToggle].
 *
 * Harness names: `settings.subtitles.column` (the rows and the preview beside them, wide panes),
 * `settings.subtitles.preview`, the steppers `settings.subtitles.<size|background|position>` with their parts (see
 * [SettingStepperItem]), the choices `settings.subtitles.<textColor|outline|outlineColor>` (see [SettingChoiceItem])
 * and the switches `settings.subtitles.<bold|styledOverride>`.
 */
@Composable
internal fun SubtitleSettingsPane(
    state: SubtitleSettingsUiState,
    onStep: (SubtitleNumber, up: Boolean) -> Unit,
    onChoose: (key: String, optionId: String) -> Unit,
    onToggle: (key: String, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTortugaDimens.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        if (width >= 720.dp) {
            // The rows and the preview share the page's column, the preview at most 40 % of it.
            val column = min(width, dimens.contentMaxWidth)
            Row(Modifier.contentColumn().fillMaxSize().layoutProbe("settings.subtitles.column", reportsFocus = false)) {
                SettingsList(Modifier.weight(1f)) { rows(state, onStep, onChoose, onToggle) }
                SubtitlePreviewFrame(
                    state.preview,
                    Modifier.padding(top = dimens.focusReach, end = dimens.screenPadding).width(min(column * 0.4f, 480.dp)),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SubtitlePreviewFrame(
                    state.preview,
                    Modifier.padding(horizontal = dimens.screenPadding, vertical = 8.dp).widthIn(max = 420.dp).fillMaxWidth(),
                )
                SettingsList(Modifier.weight(1f)) { rows(state, onStep, onChoose, onToggle) }
            }
        }
    }
}

private fun LazyListScope.rows(
    state: SubtitleSettingsUiState,
    onStep: (SubtitleNumber, up: Boolean) -> Unit,
    onChoose: (key: String, optionId: String) -> Unit,
    onToggle: (key: String, checked: Boolean) -> Unit,
) {
    fun choice(row: SettingChoiceRow) =
        SettingsItem(row.id) { shape -> SettingChoiceItem(row, { onChoose(row.id, it) }, "settings.subtitles.${row.id}", shape) }
    fun toggle(row: SettingToggleRow) =
        SettingsItem(row.id) { shape -> SettingToggleItem(row, { onToggle(row.id, it) }, shape, Modifier.layoutProbe("settings.subtitles.${row.id}")) }
    item(key = "note") { SettingsNote(state.note) }
    settingsGroup(
        "text",
        state.textHeader,
        listOf(
            stepper(state.size, SubtitleNumber.SIZE, "size", onStep),
            choice(state.textColor),
            toggle(state.bold),
        ),
    )
    settingsGroup(
        "outline",
        state.outlineHeader,
        listOf(
            choice(state.outline),
            state.outlineColor?.let(::choice),
            stepper(state.background, SubtitleNumber.BACKGROUND, "background", onStep),
        ),
    )
    settingsGroup("position", state.positionHeader, listOf(stepper(state.position, SubtitleNumber.POSITION, "position", onStep)))
    settingsGroup("styled", state.styledHeader, listOf(toggle(state.styledOverride)))
}

private fun stepper(row: SettingStepperRow, field: SubtitleNumber, name: String, onStep: (SubtitleNumber, up: Boolean) -> Unit) =
    SettingsItem(name) { shape -> SettingStepperItem(row, { up -> onStep(field, up) }, "settings.subtitles.$name", shape) }

/**
 * The sample line over a stand-in picture (a dusky gradient, light at the top and dark at the bottom, so light and
 * dark text both show what the outline and the box do), every length in proportion to the frame's height as the
 * player's are to the video's.
 */
@Composable
private fun SubtitlePreviewFrame(preview: SubtitlePreview, modifier: Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.verticalGradient(listOf(Color(0xFF6E8BA6), Color(0xFF3B4A57), Color(0xFF15191D))))
            .semantics { contentDescription = preview.text }
            .layoutProbe("settings.subtitles.preview"),
    ) {
        val height = maxHeight
        val fontSize = with(density) { (height * preview.fontSize.toFloat()).toSp() }
        val outline = with(density) { (height * preview.outlineWidth.toFloat()).toPx() }
        val text = TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize * 1.2f,
            fontWeight = if (preview.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        val box = preview.boxColor
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 8.dp, end = 8.dp, bottom = height * preview.bottom.toFloat())
                .then(if (box != null) Modifier.background(Color(box)).padding(height * preview.boxPadding.toFloat()) else Modifier),
        ) {
            // libass draws the outline outside each letter: a stroke twice as wide under the fill.
            if (outline > 0f) {
                Text(
                    preview.text,
                    style = text.copy(color = Color(preview.outlineColor), drawStyle = Stroke(width = outline * 2, join = StrokeJoin.Round)),
                )
            }
            Text(preview.text, style = text.copy(color = Color(preview.textColor)))
        }
    }
}
