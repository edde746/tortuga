package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.presentation.detail.DetailDownloadState
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.theme.TortugaColors

/** The icon of where a title's or an episode's download stands: Detail's download button and its episode rows. */
val DetailDownloadState.icon: ImageVector
    get() = when (this) {
        DetailDownloadState.AVAILABLE, DetailDownloadState.DOWNLOADING, DetailDownloadState.PARTIAL -> TortugaIcons.Download
        DetailDownloadState.QUEUED -> TortugaIcons.Schedule
        DetailDownloadState.PAUSED -> TortugaIcons.Pause
        DetailDownloadState.FAILED -> TortugaIcons.ErrorOutline
        DetailDownloadState.DOWNLOADED -> TortugaIcons.DownloadDone
    }

/** The icon of a download's status: the Downloads screen's rows. */
val DownloadStatus.icon: ImageVector
    get() = when (this) {
        DownloadStatus.QUEUED -> TortugaIcons.Schedule
        DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING -> TortugaIcons.Download
        DownloadStatus.PAUSED -> TortugaIcons.Pause
        DownloadStatus.FAILED -> TortugaIcons.ErrorOutline
        DownloadStatus.COMPLETED -> TortugaIcons.DownloadDone
        DownloadStatus.CANCELLED -> TortugaIcons.Close
    }

/**
 * A download's state as [icon] inside a [size] circle, with [progress] (0–1) as a ring round it while it is known: the
 * done part in the content colour over a faint track, from the top, clockwise. Follows [LocalContentColor], so it
 * inverts on a focused control.
 */
@Composable
fun DownloadMark(
    icon: ImageVector,
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    iconSize: Dp = if (progress != null) size * 0.55f else size * 0.8f,
    contentDescription: String? = null,
) {
    val content = LocalContentColor.current
    Box(
        modifier
            .size(size)
            .then(if (progress != null) Modifier.downloadRing(progress, content, (size.value / 12f).coerceAtLeast(2f).dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, Modifier.size(iconSize), tint = content)
    }
}

/** [progress] as a ring just inside this element's bounds, [width] wide, in [color] over its faint track. */
private fun Modifier.downloadRing(progress: Float, color: Color, width: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val inset = stroke / 2
    val arcSize = Size(size.width - stroke, size.height - stroke)
    val topLeft = Offset(inset, inset)
    drawArc(color.copy(alpha = 0.24f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
    val done = progress.coerceIn(0f, 1f)
    if (done > 0f) drawArc(color, -90f, 360f * done, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
}

/**
 * A download's progress as a thin bar across its row ([progress], 0–1): the done part in the progress fill over its
 * track, its rounded ends lined up with the text beside it; filled from the leading edge (right to left in Arabic).
 */
@Composable
fun DownloadProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val done = progress.coerceIn(0f, 1f)
    val content = LocalContentColor.current
    Spacer(
        modifier.fillMaxWidth().height(4.dp).drawBehind {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(content.copy(alpha = 0.24f), cornerRadius = radius)
            if (done <= 0f) return@drawBehind
            val width = maxOf(size.width * done, size.height)
            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
            drawRoundRect(content, topLeft = Offset(left, 0f), size = Size(width, size.height), cornerRadius = radius)
        },
    )
}

/**
 * An episode's download state as a small badge line ([DetailDownloadState] icon, with its progress ring, and [label]):
 * "Downloaded", "45%", "Queued". Muted in the content colour; failures in the error colour.
 */
@Composable
fun DownloadBadge(state: DetailDownloadState, label: String, progress: Float?, modifier: Modifier = Modifier) {
    val color = if (state == DetailDownloadState.FAILED) TortugaColors.Error else LocalContentColor.current.copy(alpha = 0.6f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            DownloadMark(state.icon, progress, size = 16.dp)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}
