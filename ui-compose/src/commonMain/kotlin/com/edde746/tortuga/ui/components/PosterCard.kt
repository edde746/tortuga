package com.edde746.tortuga.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.PosterTemplate
import com.edde746.tortuga.presentation.board.itemStateText
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.largeFontScale
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import com.edde746.tortuga.ui.theme.hyphenated

/** Where the app's posters come from in place of the addons' ([PosterTemplate.url]); null for the addons' own. */
val LocalPosterTemplate = staticCompositionLocalOf<PosterTemplate?> { null }

/** Width ÷ height of an addon's poster shape (stremio-core: poster 1:1.464, landscape 16:9, square 1:1). */
val PosterShape.aspectRatio: Float
    get() = when (this) {
        PosterShape.POSTER -> 1f / 1.464f
        PosterShape.SQUARE -> 1f
        PosterShape.LANDSCAPE -> 16f / 9f
    }

/**
 * A catalog or library item: the artwork in its addon's [shape], then [title] on one line and [subtitle] on one more
 * (both preformatted by the store; the subtitle's line stays reserved when there is none, [reserveSubtitle], so the
 * cards of a row keep one height); at a large font scale ([largeFontScale]) two lines each, kept on every card for the
 * same reason. With [wrapSubtitle] (Continue Watching's episode and time left, which many languages make longer than
 * a card) the subtitle takes as many lines as it needs, at every font scale: a row's cards, top-aligned, are as tall as
 * its tallest. A word wider than the card breaks between syllables ([hyphenated]). The artwork is [imageUrl], or
 * [fallbackUrl] once that fails to load (a [PosterTemplate]'s poster, then the addon's). [progress] (0–1) draws a bar
 * inside the artwork ([ArtworkProgress]; Continue Watching, the library); [watched] marks it watched with a check; [badge]
 * labels the corner ("New", an episode count). Focusable and clickable as one element, with the focus ring on the
 * artwork; [onLongClick] is a long press on it (touch, or a hold of Select on TV), for the item's menu. Screen readers
 * take that element alone: named by [title], its state the badge's [badgeDescription], the subtitle, the progress and
 * "Watched" ([itemStateText]); the caption under it is hidden from them, where it read the title a second time and the
 * subtitle as an item of its own. Without [caption] the card is the artwork alone: no title, no subtitle and no room
 * kept for them; on TV the room stays and the focused card alone shows its caption, so the row keeps its height as the
 * focus moves. Its semantics don't change.
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    shape: PosterShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
    watched: Boolean = false,
    badge: String? = null,
    badgeDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    width: Dp = LocalTortugaDimens.current.cardWidth(shape),
    reserveSubtitle: Boolean = true,
    wrapSubtitle: Boolean = false,
    caption: Boolean = true,
) {
    val dimens = LocalTortugaDimens.current
    val corner = MaterialTheme.shapes.medium
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (!caption && tv) Modifier.onFocusChanged { focused = it.hasFocus } else Modifier)
                .aspectRatio(shape.aspectRatio)
                .focusRing(corner)
                .clip(corner)
                .semantics {
                    contentDescription = title
                    itemStateText(badgeDescription, subtitle, progress?.toDouble(), watched)?.let { stateDescription = it }
                }
                .overlayOpener()
                .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        ) {
            RemoteImage(imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), blank = TortugaColors.SurfaceHigh, fallbackUrl = fallbackUrl) {
                Icon(
                    TortugaIcons.Movie,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                    tint = TortugaColors.TextFaint,
                )
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopStart).padding(6.dp).clearAndSetSemantics {}, emphasised = true)
            if (watched) {
                Icon(
                    TortugaIcons.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    tint = TortugaColors.Primary,
                )
            }
            if (progress != null) ArtworkProgress(progress, Modifier.matchParentSize())
        }
        if (!caption && !tv) return@Column
        // On TV the focused artwork grows over the gap: the caption stays clear of it.
        Spacer(Modifier.height(if (dimens.focusGrows) 10.dp else 6.dp))
        val lines = if (largeFontScale()) 2 else 1
        // A TV card without its caption keeps the room, shown while focused.
        val shown = Modifier.semantics { hideFromAccessibility() }.alpha(if (caption || focused) 1f else 0f)
        Text(
            title,
            modifier = shown,
            style = MaterialTheme.typography.titleSmall.contentDirection().hyphenated(),
            maxLines = lines,
            minLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null || reserveSubtitle) {
            Text(
                subtitle.orEmpty(),
                modifier = shown,
                style = MaterialTheme.typography.bodySmall.hyphenated(),
                color = TortugaColors.TextMuted,
                maxLines = if (wrapSubtitle) Int.MAX_VALUE else lines,
                minLines = lines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How much of an item is watched, over its artwork (a poster, a thumbnail; place it with `matchParentSize()`): a
 * rounded bar 4 dp high (TV 6) inset 8 dp from the left, right and bottom edges (TV 10), white on a 30 % white
 * track, filled from its leading edge (right to left in Arabic), over a scrim darkening the lowest 30 % of the artwork
 * from 60 % black at the bottom edge to none, so the bar reads on light artwork too.
 */
@Composable
fun ArtworkProgress(progress: Float, modifier: Modifier = Modifier) {
    val watched = progress.coerceIn(0f, 1f)
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val barHeight = if (tv) 6.dp else 4.dp
    val inset = if (tv) 10.dp else 8.dp
    Spacer(
        modifier.drawBehind {
            val scrimTop = size.height * 0.7f
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, TortugaColors.ArtworkScrim), startY = scrimTop, endY = size.height),
                topLeft = Offset(0f, scrimTop),
                size = Size(size.width, size.height - scrimTop),
            )
            val bar = barHeight.toPx()
            val track = Size((size.width - inset.toPx() * 2).coerceAtLeast(0f), bar)
            val origin = Offset(inset.toPx(), size.height - inset.toPx() - bar)
            val radius = CornerRadius(bar / 2)
            drawRoundRect(TortugaColors.ArtworkProgressTrack, origin, track, radius)
            // A started item shows at least a round dot.
            if (watched > 0f) {
                val fill = minOf(maxOf(track.width * watched, bar), track.width)
                val start = if (layoutDirection == LayoutDirection.Rtl) origin.x + track.width - fill else origin.x
                drawRoundRect(TortugaColors.ArtworkProgressFill, Offset(start, origin.y), Size(fill, bar), radius)
            }
        },
    )
}
