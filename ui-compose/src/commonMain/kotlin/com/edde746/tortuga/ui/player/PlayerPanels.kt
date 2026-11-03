package com.edde746.tortuga.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.presentation.player.ControlsScrim
import com.edde746.tortuga.presentation.player.ErrorAction
import com.edde746.tortuga.presentation.player.ErrorState
import com.edde746.tortuga.presentation.player.InfoColumns
import com.edde746.tortuga.presentation.player.InfoSection
import com.edde746.tortuga.presentation.player.LevelIndicator
import com.edde746.tortuga.presentation.player.LevelKind
import com.edde746.tortuga.presentation.player.PlayerPrompt
import com.edde746.tortuga.presentation.player.PromptKind
import com.edde746.tortuga.presentation.player.StillWatchingState
import com.edde746.tortuga.presentation.player.UnlockButton
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.focus.remoteScroll
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlin.math.min

/**
 * The playback info, sections from the store ([InfoSection]): a card at the top trailing corner that leaves the video
 * and the controls usable, its numbers being about what plays behind it. The sections flow into columns ([InfoColumns]:
 * three on TV, so it all shows at once, two where two fit, one on an upright phone), each under its heading. A
 * column's labels are as wide as its longest label (measured, so none wraps; [InfoMetrics.MaxLabel] at most), and the
 * columns' values share the rest of the card's width alike: a column of short labels leaves its values the room. The
 * widths go by the room and the labels alone, and digits are of one width, so a live value that changes (a bitrate, a
 * count) moves nothing but its own digits. On TV the text spans the safe area (the card's padding reaching past it,
 * [InfoPanelPadding]) with its values a step smaller than the body text and its labels smaller still, so the test
 * matrix's values ("BT.2020 · PQ · Dolby Vision", "English · English - TrueHD 5.1") read on one line.
 *
 * The card is as tall as its sections, up to its room; taller (above the controls, on an upright phone), it scrolls: by
 * touch and the wheel, and on TV by the remote ([remoteScroll]), so no row stays out of reach; at its bottom Down goes
 * on to the corner's card or prompt button where they show ([PlayerScreen]).
 */
@Composable
internal fun InfoPanel(sections: List<InfoSection>, modifier: Modifier = Modifier) {
    val tv = rememberPlayerMetrics().tv
    val typography = MaterialTheme.typography
    val metrics = if (tv) InfoMetrics.Tv else InfoMetrics.Default
    val labelStyle = typography.bodySmall
    val valueStyle = (if (tv) typography.bodyMedium.merge(InfoMetrics.TvValue) else typography.bodySmall).merge(TabularDigits)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        val room = maxWidth - InfoPanelPadding * 2
        val fit = ((room + metrics.gap) / (metrics.column + metrics.gap)).toInt()
        val count = fit.coerceIn(1, metrics.maxColumns)
        // The text's width: an upright phone's whole room, TV's safe area, else the columns' own.
        val width = when {
            fit < 1 -> room.coerceAtLeast(InfoMetrics.MinColumn)
            tv -> room
            else -> metrics.column * count + metrics.gap * (count - 1)
        }
        val columns = InfoColumns.split(sections, count)
        val labelTexts = columns.map { column -> column.flatMap { section -> section.rows.map { it.label } } }
        val labels = remember(labelTexts, labelStyle, measurer, density) {
            labelTexts.map { texts ->
                val longest = texts.maxOfOrNull { measurer.measure(it, labelStyle, softWrap = false, maxLines = 1).size.width } ?: 0
                with(density) { longest.toDp() }.coerceAtMost(InfoMetrics.MaxLabel)
            }
        }
        // A column InfoColumns leaves empty keeps its share: the text keeps its width.
        val values = ((width - labels.fold(0.dp) { sum, label -> sum + label } - metrics.labelGap * count - metrics.gap * (count - 1)) / count)
            .coerceAtLeast(0.dp)
        // Before its first layout the scroll's room is unknown (0).
        val scrolls = tv && scroll.viewportSize > 0 && scroll.maxValue > 0
        Surface(
            Modifier.layoutProbe("player.info.panel").then(if (scrolls) Modifier.remoteScroll(scroll, scope, PanelShape) else Modifier),
            shape = PanelShape,
            color = TortugaColors.Surface,
            contentColor = TortugaColors.OnVideo,
        ) {
            Column(Modifier.verticalScroll(scroll).padding(InfoPanelPadding), verticalArrangement = Arrangement.spacedBy(metrics.sectionGap)) {
                Text(T.player.info.title, style = if (tv) typography.titleMedium else typography.titleSmall)
                Row(Modifier.width(width), horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
                    columns.forEachIndexed { index, column ->
                        val label = labels[index]
                        Column(Modifier.width(label + metrics.labelGap + values), verticalArrangement = Arrangement.spacedBy(metrics.sectionGap)) {
                            for (section in column) InfoSectionRows(section, label, labelStyle, valueStyle, metrics)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSectionRows(section: InfoSection, labelWidth: Dp, labelStyle: TextStyle, valueStyle: TextStyle, metrics: InfoMetrics) {
    Column(Modifier.layoutProbe("player.info.${section.id.probeName}"), verticalArrangement = Arrangement.spacedBy(metrics.rowGap)) {
        Text(section.title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.semantics { heading() })
        // The stream's name, its addon's and the file's are content text; the other values are ours.
        val content = T.player.info.let { setOf(it.name, it.addon, it.file) }
        for (row in section.rows) {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(metrics.labelGap)) {
                Text(row.label, style = labelStyle, color = TortugaColors.OnVideoMuted, modifier = Modifier.width(labelWidth).alignByBaseline())
                Text(
                    row.value,
                    style = if (row.label in content) valueStyle.contentDirection() else valueStyle,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
            }
        }
    }
}

/** Digits of one width: a live value's digits change in place. */
private val TabularDigits = TextStyle(fontFeatureSettings = "tnum")

/** The info card's padding. On TV it reaches past the safe area's sides (the player places the card so). */
internal val InfoPanelPadding = 16.dp

/**
 * The info card's columns: [column] wide (on TV the least, the columns then sharing the safe area's width), at most
 * [maxColumns] side by side and [gap] apart; a row's label and value [labelGap] apart, rows [rowGap] and sections
 * [sectionGap].
 */
private class InfoMetrics(val column: Dp, val gap: Dp, val labelGap: Dp, val rowGap: Dp, val sectionGap: Dp, val maxColumns: Int) {
    companion object {
        /** A screen narrower than one column (an upright phone) gets one as wide as it has room for, but no narrower. */
        val MinColumn = 240.dp

        /** The widest a column's labels get: a longer label (a long translation) wraps rather than squeeze the values. */
        val MaxLabel = 140.dp

        /**
         * TV's values: 13 sp, between the labels' 12 and the body's 14 (as on the Apple TV), without the body's
         * tracking: three columns of the test matrix's values fit the safe area's 864 dp on one line.
         */
        val TvValue = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
        val Default = InfoMetrics(column = 300.dp, gap = 20.dp, labelGap = 12.dp, rowGap = 4.dp, sectionGap = 12.dp, maxColumns = 2)
        val Tv = InfoMetrics(column = 256.dp, gap = 24.dp, labelGap = 10.dp, rowGap = 4.dp, sectionGap = 12.dp, maxColumns = 3)
    }
}

/**
 * The prompt button ([PlayerPrompt]): the skip button ("Skip intro", a fast-forward icon) or up next ("S1E2 · Please
 * Remain Calm", a skip-next icon), one pill in the bottom trailing corner. Its countdown fills it from its leading edge
 * ([PlayerPrompt.progress], stepped every tenth of a second and drawn moving between the steps). It hugs its label up to
 * [CornerWidth] ([CornerTvWidth] on TV; a narrower window gives it its whole width), past which the label is cut short
 * after the episode's code ([PlayerPrompt.lead]); screen readers hear the whole label, which says what up next does, and
 * the countdown's seconds left as its state ([PlayerPrompt.countdown]). On TV it takes focus as it shows (the player
 * gives it: [PlayerFocus.prompt]).
 */
@Composable
internal fun PromptButton(prompt: PlayerPrompt, focus: PlayerFocus, onCommand: (PlayerCommand) -> Unit, modifier: Modifier = Modifier) {
    val tv = rememberPlayerMetrics().tv
    val countdown = prompt.progress
    val fill by animateFloatAsState(
        countdown ?: 0f,
        if (countdown == null) snap() else tween(PROMPT_FILL_STEP_MILLIS, easing = LinearEasing),
        label = "prompt",
    )
    val upNext = prompt.kind == PromptKind.UP_NEXT
    TortugaButton(
        prompt.text,
        onClick = { onCommand(prompt.command) },
        modifier = modifier
            .widthIn(max = if (tv) CornerTvWidth else CornerWidth)
            .focusRequester(focus.prompt)
            .semantics {
                contentDescription = prompt.label
                prompt.countdown?.let { stateDescription = it }
            }
            .layoutProbe(if (upNext) "player.upNext" else "player.skip"),
        icon = if (upNext) TortugaIcons.SkipNext else TortugaIcons.FastForward,
        progress = if (countdown != null) fill else null,
        lead = prompt.lead,
        // Up next's text is the episode's name (its code alone where it has none, which opens on a letter of the
        // interface's own script in every language we ship); a skip's is ours.
        contentText = upNext,
    )
}

/** How often the store steps a prompt's countdown: the fill moves on between its steps over this long. */
private const val PROMPT_FILL_STEP_MILLIS = 100

/**
 * The bottom trailing corner's width on phones, tablets and desktop: the sleep timer's card's, and the prompt button's
 * at most (a narrower window gives them its whole width).
 */
private val CornerWidth = 320.dp

/** The bottom trailing corner's width on TV. */
private val CornerTvWidth = 420.dp

/**
 * The sleep timer's question, compact ([CornerWidth], TV [CornerTvWidth]), in the bottom trailing corner above the
 * prompt button: "Still watching?", the countdown ("Pausing in 25 seconds"), then Continue and Pause. Continue's fill
 * grows as the countdown runs out ([StillWatchingState.progress]); the playback pauses when it does. On TV Continue
 * has focus while the card shows (the player gives it). TalkBack speaks the question as the card shows, wherever its
 * focus is; not the countdown, which changes every second.
 *
 * Continue and Pause stay one height under focus: neither grows nor wears a ring outside it. Focus shows by the fill
 * alone: while it is in the card (and shows: keys, not a pointer or touch) the focused button wears the primary fill
 * and Continue rests in the secondary one like Pause; otherwise Continue wears it as the card's main action.
 */
@Composable
internal fun StillWatchingCard(stillWatching: StillWatchingState, focus: PlayerFocus, onCommand: (PlayerCommand) -> Unit, modifier: Modifier = Modifier) {
    val tv = rememberPlayerMetrics().tv
    val typography = MaterialTheme.typography
    var focusInside by remember { mutableStateOf(false) }
    val keyFocus = focusInside && isFocusVisible()
    Surface(
        modifier.width(if (tv) CornerTvWidth else CornerWidth),
        shape = PanelShape,
        color = TortugaColors.Surface,
        contentColor = TortugaColors.OnVideo,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                T.player.stillWatching.title,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = if (tv) typography.labelLarge else typography.labelMedium,
                color = TortugaColors.OnVideoMuted,
                maxLines = 1,
            )
            Text(
                stillWatching.countdown,
                style = (if (tv) typography.titleLarge else typography.titleMedium).copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 12.dp).height(IntrinsicSize.Min).onFocusChanged { focusInside = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TortugaButton(
                    T.player.stillWatching.keepWatching,
                    onClick = { onCommand(PlayerCommand.KeepWatching) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(focus.stillWatching)
                        .layoutProbe("player.stillWatching.continue"),
                    style = if (keyFocus) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY,
                    icon = TortugaIcons.PlayArrow,
                    progress = stillWatching.progress,
                    grows = false,
                )
                TortugaButton(
                    T.player.stillWatching.pause,
                    onClick = { onCommand(PlayerCommand.Pause) },
                    modifier = Modifier.fillMaxHeight().layoutProbe("player.stillWatching.pause"),
                    style = ButtonStyle.SECONDARY,
                    grows = false,
                )
            }
        }
    }
}

/**
 * The failure, full screen: black from edge to edge (out past the player by [videoInsets], under the system bars, as
 * the video reaches), over the video, the subtitles and every panel; the player keeps the top bar's Back and title over
 * it. On the black, centred: the icon, the title, the message, the other streams there are (the texts within 460 dp,
 * 640 dp on TV), then the buttons ([ErrorState.buttons], in order; harness names `player.error.<action>`: retry, next,
 * chooseStream, watch, close). It keeps [topBar] clear above and below, so it never meets Back or the title, and
 * scrolls where it is taller than the room left (the largest text on a phone on its side). The buttons sit side by
 * side where they fit the player's width (an error video's four too, on a phone on its side), else one above the
 * other, as wide as one another ([ErrorButtons]). The primary button
 * ([ErrorState.primary]) is filled and carries [PlayerFocus.error]: the player gives it focus, and on TV the D-pad moves
 * along the row or the stack from there; Close is a text button. TalkBack speaks the title and the message as they show
 * and change, wherever its focus is. Harness name: `player.error` (the whole player).
 */
@Composable
internal fun ErrorPanel(
    error: ErrorState,
    focus: PlayerFocus,
    onCommand: (PlayerCommand) -> Unit,
    topBar: Dp,
    videoInsets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    val tv = rememberPlayerMetrics().tv
    Box(modifier.fillMaxSize().layoutProbe("player.error", reportsFocus = false)) {
        Box(Modifier.matchParentSize().beyond(videoInsets).background(Color.Black))
        Box(Modifier.fillMaxSize().padding(vertical = topBar), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides TortugaColors.OnVideo) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val texts = Modifier.widthIn(max = if (tv) 640.dp else 460.dp)
                    Icon(TortugaIcons.ErrorOutline, contentDescription = null, Modifier.size(40.dp), tint = TortugaColors.Error)
                    Text(
                        error.title,
                        texts.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        error.message,
                        texts.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = TortugaColors.OnVideoMuted,
                    )
                    error.alternatives?.let { Text(it, texts, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = TortugaColors.OnVideoMuted) }
                    // 18 dp below the texts, with the column's spacing.
                    ErrorButtons(stacked = if (tv) 400.dp else 300.dp, Modifier.padding(top = 8.dp)) {
                        for (button in error.buttons) {
                            TortugaButton(
                                button.label,
                                onClick = { onCommand(button.command) },
                                modifier = (if (button.primary) Modifier.focusRequester(focus.error) else Modifier).layoutProbe("player.error.${button.action.probeName}"),
                                style = when {
                                    button.primary -> ButtonStyle.PRIMARY
                                    button.action == ErrorAction.CLOSE -> ButtonStyle.TEXT
                                    else -> ButtonStyle.SECONDARY
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The error's buttons: side by side, centred and 10 dp apart, where they fit the width at their own sizes; else one
 * above the other, 10 dp apart, each as wide as the width allows up to [stacked].
 */
@Composable
private fun ErrorButtons(stacked: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        if (widths.sum() + gaps <= constraints.maxWidth) {
            val buttons = measurables.mapIndexed { i, button -> button.measure(Constraints(maxWidth = widths[i])) }
            val height = buttons.maxOfOrNull { it.height } ?: 0
            layout(buttons.sumOf { it.width } + gaps, height) {
                var x = 0
                for (button in buttons) {
                    button.placeRelative(x, (height - button.height) / 2)
                    x += button.width + gap
                }
            }
        } else {
            val width = min(constraints.maxWidth, stacked.roundToPx())
            val buttons = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, buttons.sumOf { it.height } + gaps) {
                var y = 0
                for (button in buttons) {
                    button.placeRelative(0, y)
                    y += button.height + gap
                }
            }
        }
    }
}

/**
 * A transient message (volume, speed, a track change by key) on black, darker over an HDR picture ([hdr],
 * [ControlsScrim]). TalkBack speaks it as it shows and changes, wherever its focus is. Harness name: `player.osd`.
 */
@Composable
internal fun OsdMessage(text: String, hdr: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.layoutProbe("player.osd"), shape = CircleShape, color = Color.Black.copy(alpha = ControlsScrim.alpha(0.6f, hdr)), contentColor = TortugaColors.OnVideo) {
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A swipe's level ([LevelIndicator]: the brightness or the volume): a tall capsule on the black of
 * [OsdMessage], filled from the bottom to the level, its icon and percent over the middle, white above the fill and
 * dark where the fill covers them. The fill follows the level in 90 ms. TalkBack speaks its label ("Brightness
 * 40%"). Harness name: `player.level`.
 */
@Composable
internal fun LevelBar(level: LevelIndicator, hdr: Boolean, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(level.fraction.coerceIn(0f, 1f), tween(90), label = "level")
    val icon = when (level.kind) {
        LevelKind.BRIGHTNESS -> TortugaIcons.LightMode
        LevelKind.VOLUME -> TortugaIcons.VolumeUp
    }
    Box(
        modifier
            .layoutProbe("player.level")
            .size(54.dp, 184.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = ControlsScrim.alpha(0.6f, hdr)))
            .background(Color.White.copy(alpha = 0.14f))
            .clearAndSetSemantics {
                contentDescription = level.label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill).background(Color.White.copy(alpha = 0.74f)))
        LevelContent(icon, level.percent, TortugaColors.OnVideo, Modifier)
        LevelContent(
            icon,
            level.percent,
            Color.Black.copy(alpha = 0.86f),
            Modifier.drawWithContent { clipRect(top = size.height * (1 - fill)) { this@drawWithContent.drawContent() } },
        )
    }
}

/** [LevelBar]'s icon over its percent, centred on the whole bar, in [color]. */
@Composable
private fun LevelContent(icon: ImageVector, percent: String, color: Color, modifier: Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = color)
        Text(
            percent,
            Modifier.padding(top = 6.dp),
            color = color,
            style = TextStyle(fontSize = 14.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        )
    }
}

/** The speed a finger held on the video plays at ("2×"), as [OsdMessage] looks, while it is held. Harness name: `player.heldSpeed`. */
@Composable
internal fun HeldSpeedMessage(speed: String, hdr: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.layoutProbe("player.heldSpeed"), shape = CircleShape, color = Color.Black.copy(alpha = ControlsScrim.alpha(0.6f, hdr)), contentColor = TortugaColors.OnVideo) {
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TortugaIcons.FastForward, contentDescription = null, Modifier.size(20.dp))
            Text(speed, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The locked screen's unlock button ([UnlockButton]): a pill with the lock and "Tap to unlock" on the black
 * of [OsdMessage], and once armed white with the open lock and "Tap again to unlock" in black. It takes no touches
 * itself (the shield under it tells a tap on it from one beside it); TalkBack presses it through [onUnlock]. Harness
 * name: `player.unlock`.
 */
@Composable
internal fun UnlockButtonPill(unlock: UnlockButton, hdr: Boolean, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val content = if (unlock.armed) Color.Black else TortugaColors.OnVideo
    Row(
        modifier
            .layoutProbe("player.unlock")
            .clip(CircleShape)
            .background(if (unlock.armed) Color.White else Color.Black.copy(alpha = ControlsScrim.alpha(0.6f, hdr)))
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = unlock.label
                liveRegion = LiveRegionMode.Polite
                onClick {
                    onUnlock()
                    true
                }
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (unlock.armed) TortugaIcons.LockOpen else TortugaIcons.Lock, contentDescription = null, Modifier.size(20.dp), tint = content)
        Text(unlock.label, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerLoadingIndicator() {
    LoadingIndicator(Modifier.size(72.dp), color = TortugaColors.OnVideo)
}
