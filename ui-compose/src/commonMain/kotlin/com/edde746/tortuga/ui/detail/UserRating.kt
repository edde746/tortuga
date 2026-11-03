package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.detail.DetailTrackerScore
import com.edde746.tortuga.presentation.detail.DetailUserRating
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.RowGroup
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlin.math.roundToInt

/**
 * The user's own score, last in the ratings row once there is one ([DetailUserRating.text]): a star, then the score the
 * trackers have ("8"; their range, "7–8", where they differ), plain as the other scores, opening nothing and taking no
 * focus (the rating picker opens from the action row's [com.edde746.tortuga.presentation.detail.DetailAction.RATE]).
 * One accessibility element, read as [DetailUserRating.label]. Harness name: `detail.userRating`.
 */
@Composable
internal fun UserRatingItem(rating: DetailUserRating, metrics: RatingMetrics) {
    val text = rating.text ?: return
    val height = with(LocalDensity.current) { metrics.text.fontSize.toDp() }
    Row(
        Modifier.layoutProbe("detail.userRating").clearAndSetSemantics { contentDescription = rating.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.innerGap),
    ) {
        Icon(TortugaIcons.Star, contentDescription = null, Modifier.size(height), tint = TortugaColors.Text)
        Text(text, style = metrics.text, color = TortugaColors.Text, maxLines = 1)
    }
}

/**
 * The TV row's rating ([com.edde746.tortuga.presentation.detail.DetailAction.RATE]; off a TV it is behind ⋯): a round
 * star button read [DetailUserRating.label] ("Rate this title", "Your rating: 8"), selected once there is a score,
 * opening [UserRatingSheet]; focus comes back to it as the picker closes. Harness name: `detail.rate`.
 */
@Composable
internal fun RateButton(rating: DetailUserRating, focus: FocusMemory, intents: DetailIntents) {
    var open by remember { mutableStateOf(false) }
    TortugaIconButton(
        TortugaIcons.Star,
        rating.label,
        { open = true },
        Modifier.rememberFocus(focus, FOCUS_RATE).layoutProbe("detail.rate"),
        style = IconButtonStyle.OVER_PICTURE,
        selected = rating.text != null,
    )
    if (open) UserRatingSheet(rating, intents.rate, onDismiss = { open = false })
}

/**
 * The rating picker, a [TortugaSheet] titled "Your rating" (on TV the side panel), opened from the action row's
 * rating ([com.edde746.tortuga.presentation.detail.DetailAction.RATE]): a line saying the score goes to every service
 * listed, the score as half stars ([Stars]), then each tracker's line with its mark, its name and its score or where its
 * read or write is ([DetailTrackerScore.status]; in the error colour where it is unknown or not saved). A score rates
 * the title at once, the sheet staying open while the trackers answer; "Remove rating" at its foot once there is a
 * score. Focus starts on the stars. Harness names: `detail.userRating.sheet`, `detail.userRating.tracker.<service id>`,
 * `detail.userRating.clear`.
 */
@Composable
internal fun UserRatingSheet(rating: DetailUserRating, onRate: (Int?) -> Unit, onDismiss: () -> Unit) {
    val text = T.detail.userRating
    val initial = remember { FocusRequester() }
    TortugaSheet(onDismiss = onDismiss, title = text.title, initialFocus = initial, probe = "detail.userRating.sheet") {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text.hint, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
            Stars(rating.score, onRate, initial)
            RowGroup {
                rating.trackers.forEachIndexed { index, line -> TrackerLine(line, groupShape(index, rating.trackers.size)) }
            }
        }
        if (rating.text != null) {
            OverlayActions {
                TortugaButton(text.clear, { onRate(null) }, Modifier.layoutProbe("detail.userRating.clear"))
            }
        }
    }
}

/**
 * The score 1–10 as five stars, each half one point (4½ stars: 9): the given half stars white, the rest a dim solid
 * fill (never an outline glyph), and the score large beside them, a dash without one ([selected] null: unrated, or the
 * trackers differing). A tap on a half star, or a drag along them, gives its score (a drag on release); a remote's Left
 * and Right move it a point, Select gives it. One element for screen readers, adjustable as a slider is: "Your rating,
 * 8" or "Not rated". Harness names: `detail.userRating.stars`, and each point's half star `detail.userRating.score.<n>`
 * (8: the 4th star's right half).
 */
@Composable
private fun Stars(selected: Int?, onRate: (Int) -> Unit, initial: FocusRequester) {
    var pending by remember(selected) { mutableStateOf<Int?>(null) }
    var focused by remember { mutableStateOf(false) }
    val shown = pending ?: selected
    val text = T.detail.userRating
    fun give(score: Int) {
        pending = null
        if (score != selected) onRate(score)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth - ScoreWidth - StarsGap - StarsPadding * 2
        val star = ((room - StarGap * 4) / 5).coerceAtMost(StarMaxSize)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StarsGap)) {
            Row(
                Modifier
                    .layoutProbe("detail.userRating.stars")
                    .background(if (focused && isFocusVisible()) TortugaColors.SurfaceHigh else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(horizontal = StarsPadding)
                    .focusRequester(initial)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        // From the score as each key leaves it, not as the last frame showed it: keys can come faster
                        // than the frames (a held Right).
                        val now = pending ?: selected
                        when (e.key) {
                            Key.DirectionLeft -> {
                                pending = ((now ?: 2) - 1).coerceAtLeast(1)
                                true
                            }
                            Key.DirectionRight -> {
                                pending = ((now ?: 0) + 1).coerceAtMost(10)
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> pending?.let { give(it); true } ?: false
                            else -> false
                        }
                    }
                    .focusable()
                    .semantics {
                        contentDescription = text.title
                        stateDescription = shown?.toString() ?: text.notRated
                        progressBarRangeInfo = ProgressBarRangeInfo((shown ?: 0).toFloat(), 0f..10f, steps = 9)
                        setProgress { value ->
                            give(value.roundToInt().coerceIn(1, 10))
                            true
                        }
                    }
                    .pointerInput(selected, onRate, star) { detectTapGestures { give(pointAt(it.x, star)) } }
                    .pointerInput(selected, onRate, star) {
                        detectHorizontalDragGestures(
                            onDragStart = { pending = pointAt(it.x, star) },
                            onDragEnd = { pending?.let(::give) },
                            onDragCancel = { pending = null },
                        ) { change, _ -> pending = pointAt(change.position.x, star) }
                    }
                    .height(StarsHeight),
                horizontalArrangement = Arrangement.spacedBy(StarGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..5) {
                    val fill = when {
                        shown == null || shown < 2 * i - 1 -> 0f
                        shown == 2 * i - 1 -> 0.5f
                        else -> 1f
                    }
                    Box(Modifier.size(star, StarsHeight), contentAlignment = Alignment.Center) {
                        StarGlyph(fill, star)
                        Row(Modifier.matchParentSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight().layoutProbe("detail.userRating.score.${2 * i - 1}", reportsFocus = false))
                            Box(Modifier.weight(1f).fillMaxHeight().layoutProbe("detail.userRating.score.${2 * i}", reportsFocus = false))
                        }
                    }
                }
            }
            Text(
                shown?.toString() ?: "–",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (shown == null) TortugaColors.TextMuted else TortugaColors.Text,
                modifier = Modifier.widthIn(min = ScoreWidth),
            )
        }
    }
}

/**
 * The point (1–10) at [x] along stars [star] wide, [StarGap] apart: a star's left half its odd point, its right half
 * and the gap after it the even one.
 */
private fun Density.pointAt(x: Float, star: Dp): Int {
    val pitch = (star + StarGap).toPx()
    val index = (x / pitch).toInt().coerceIn(0, 4)
    return 2 * index + if (x - index * pitch < star.toPx() / 2) 1 else 2
}

/** A star white from the left as far as [fill] (0, ½ or 1), the rest a dim solid fill. */
@Composable
private fun StarGlyph(fill: Float, dimension: Dp) {
    val painter = rememberVectorPainter(TortugaIcons.Star)
    Box(Modifier.size(dimension)) {
        Icon(painter, contentDescription = null, Modifier.matchParentSize(), tint = StarDim)
        if (fill > 0f) {
            Icon(
                painter,
                contentDescription = null,
                Modifier.matchParentSize().drawWithContent { clipRect(right = size.width * fill) { this@drawWithContent.drawContent() } },
                tint = TortugaColors.Text,
            )
        }
    }
}

/** One tracker's line in the picker: its mark and name, then its score or where its read or write is. */
@Composable
private fun TrackerLine(line: DetailTrackerScore, shape: Shape) {
    GroupRow(
        line.name,
        onClick = null,
        modifier = Modifier.layoutProbe("detail.userRating.tracker.${line.service.id}", reportsFocus = false),
        shape = shape,
        logo = rememberVectorPainter(line.service.mark),
        trailing = {
            Text(
                line.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (line.attention) TortugaColors.Error else LocalContentColor.current.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        },
    )
}

private val StarMaxSize = 48.dp
private val StarGap = 8.dp
private val StarsPadding = 4.dp
private val StarsHeight = 48.dp
private val StarsGap = 16.dp
private val ScoreWidth = 56.dp
private val StarDim = Color.White.copy(alpha = 0.2f)
