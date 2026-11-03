package com.edde746.tortuga.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.playback.streams.BadgeKind
import com.edde746.tortuga.playback.streams.StreamBadge
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * A short fact about an item (resolution, HDR, codec, size, "Built in", a count), as preformatted by the store: a
 * solid rounded rectangle a step lighter than what it sits on (the background, a row's surfaceHigh or surfaceHighest
 * fill, a sheet); [emphasised] (the one thing to notice, a stream's 4K) in the primary fill. One line unless [maxLines]
 * lets a longer one (an addon's note, a sentence) wrap rather than be cut where its row ends.
 * On a light fill (a focused row: its content colour turns dark) the badges invert, so none vanishes into the row:
 * the emphasised one dark with light text, the others a mid grey with dark text.
 */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, emphasised: Boolean = false, maxLines: Int = 1) {
    val onLight = LocalContentColor.current.luminance() < 0.5f
    val fill = when {
        onLight && emphasised -> TortugaColors.OnPrimary
        onLight -> OnLightBadge
        emphasised -> TortugaColors.Primary
        else -> OnDarkBadge
    }
    Text(
        text,
        modifier = modifier
            .background(fill, BadgeShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (onLight == emphasised) TortugaColors.Text else TortugaColors.OnPrimary,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        maxLines = maxLines,
    )
}

private val BadgeShape = RoundedCornerShape(6.dp)

/** A badge's fill on a dark surface: the text colour over it, faint, so it reads a step lighter on any of them. */
private val OnDarkBadge = TortugaColors.Text.copy(alpha = 0.14f)

/** A badge's fill on a light (focused) row: a mid grey under dark text. */
private val OnLightBadge = Color(0xFFBDBDBD)

/**
 * A stream's badges in the store's order after [leading] (its name), on the name's line while they fit and wrapping onto
 * more lines when narrow; its resolution emphasised.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamBadgeRow(badges: List<StreamBadge>, modifier: Modifier = Modifier, leading: @Composable () -> Unit = {}) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        for (badge in badges) Badge(badge.text, emphasised = badge.kind == BadgeKind.RESOLUTION)
    }
}
