package com.edde746.tortuga.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.adaptive.largeFontScale
import com.edde746.tortuga.ui.components.ArtworkProgress
import com.edde746.tortuga.ui.components.ArtworkRow
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * An episode row's inset on every side of its thumbnail and text, and the gap between them: its hover and focus fill
 * reach this far past them.
 */
internal val RowInset = 12.dp

/** An episode thumbnail's corners; the row's are this plus [RowInset], so the two curves run parallel. */
private val ThumbnailRadius = 8.dp

/** An episode row's corners and its thumbnail's, made once. */
private val RowShape = RoundedCornerShape(ThumbnailRadius + RowInset)

internal val ThumbnailShape = RoundedCornerShape(ThumbnailRadius)

/** An episode row's thumbnail width in a narrow window's list: a phone's, else wider. */
@Composable
internal fun listThumbnailWidth(): Dp = if (LocalWindowLayout.current.widthClass == WidthClass.COMPACT) 128.dp else 176.dp

/**
 * An episode as a free-flowing row, Detail's and the Calendar's: [artwork] (its 16:9 [EpisodeThumbnail]), [title] in
 * semibold, [infoLine] muted on one line, [badge] (Detail's download state), [overview] muted on two lines
 * ([EpisodeOverview]: blurred past reading with its [hiddenOverviewLabel]), [RowInset] around and between them, then
 * [trailing] (Detail's ⋮). No card: hover and press lay a tint behind the whole row ([TortugaColors.HoverTint]: over a
 * series' backdrop on wide windows the picture shows through); focus (keyboard, D-pad) the primary fill with dark
 * text, the row keeping its size and the artwork no focus of its own. With [artworkFocus] the row draws nothing and
 * [artwork] shows focus itself (it is given whether the row has it), as a TV's episode card does. [dimmed] (an episode
 * not out yet) dims the texts. A click takes focus too (a mouse click doesn't), so focus is on the row once what it
 * opened closes, then does [onClick]; a long press does [onLongClick]. Its fill and its texts' colour animate while
 * drawing, so a focus move redraws the two rows without composing them again. At a large font scale the texts go under
 * the artwork, whole, [trailing] at the artwork's end ([ArtworkRow]). [modifier] comes before the row's focus target:
 * its focus memory, harness name, semantics and menu gestures.
 */
@Composable
internal fun EpisodeListRow(
    title: String,
    infoLine: String?,
    overview: String?,
    hiddenOverviewLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkFocus: Boolean = false,
    dimmed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    badge: @Composable () -> Unit = {},
    artwork: @Composable (focused: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val filled = focused && isFocusVisible() && !artworkFocus
    val fill = animateColorAsState(
        when {
            filled -> TortugaColors.Primary
            (pressed || hovered) && !artworkFocus -> TortugaColors.HoverTint
            else -> TortugaColors.HoverTint.copy(alpha = 0f)
        },
        tween(durationMillis = 120),
        label = "episodeFill",
    )
    val contentTarget = if (filled) TortugaColors.OnPrimary else TortugaColors.Text
    val content = animateColorAsState(contentTarget, tween(durationMillis = 120), label = "episodeContent")
    val text = remember(content) { ColorProducer { content.value } }
    val muted = remember(content) { ColorProducer { content.value.copy(alpha = 0.6f) } }
    val rowFocus = remember { FocusRequester() }
    val stacked = largeFontScale()
    // The badge and the trailing button take the new colour at once; the texts follow the animation.
    CompositionLocalProvider(LocalContentColor provides contentTarget) {
        ArtworkRow(
            stacked,
            gap = RowInset,
            artwork = { artwork(focused) },
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier)
                .focusRequester(rowFocus)
                .drawWithCache {
                    val outline = RowShape.createOutline(size, layoutDirection, this)
                    onDrawBehind {
                        val color = fill.value
                        if (color.alpha > 0f) drawOutline(outline, color)
                    }
                }
                .overlayOpener()
                .combinedClickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                    onLongClick = onLongClick,
                    onClick = {
                        rowFocus.requestFocus()
                        onClick()
                    },
                )
                .padding(RowInset),
            textModifier = Modifier.alpha(if (dimmed) 0.6f else 1f),
            trailing = trailing,
        ) {
            BasicText(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                overflow = TextOverflow.Ellipsis,
                maxLines = if (stacked) Int.MAX_VALUE else 2,
                color = text,
            )
            infoLine?.let { BasicText(it, style = MaterialTheme.typography.bodySmall, maxLines = if (stacked) Int.MAX_VALUE else 1, color = muted) }
            badge()
            overview?.let {
                EpisodeOverview(
                    it,
                    hiddenOverviewLabel,
                    style = MaterialTheme.typography.bodySmall.contentDirection(),
                    color = muted,
                    maxLines = if (stacked) Int.MAX_VALUE else 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * An episode's 16:9 thumbnail: [url], else [fallbackUrl] (its series' art) where it has none or it fails to load, else
 * the film icon; [url] blurred past recognition while [blurred] ([fallbackUrl] gives nothing away and stays sharp), its
 * [progress] inside the bottom edge ([ArtworkProgress]), a check while [watched].
 */
@Composable
internal fun EpisodeThumbnail(
    url: String?,
    blurred: Boolean,
    modifier: Modifier,
    fallbackUrl: String? = null,
    watched: Boolean = false,
    progress: Float? = null,
) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(ThumbnailShape)
            .background(TortugaColors.SurfaceHigh),
    ) {
        RemoteImage(url, contentDescription = null, modifier = Modifier.matchParentSize(), fallbackUrl = fallbackUrl, blurred = blurred) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(TortugaIcons.Movie, contentDescription = null, Modifier.size(28.dp), tint = TortugaColors.TextFaint)
            }
        }
        if (watched) {
            Icon(
                TortugaIcons.CheckCircle,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                tint = TortugaColors.Primary,
            )
        }
        progress?.let { ArtworkProgress(it, Modifier.matchParentSize()) }
    }
}
