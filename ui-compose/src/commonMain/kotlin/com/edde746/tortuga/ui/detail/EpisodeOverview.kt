package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * An episode's description in [color], or with a [hiddenLabel] blurred past reading in its place: laid out as it would
 * show (as many lines, so nothing moves when the setting changes), only the glyphs' blurred shadow drawn (the glyphs
 * themselves transparent, the shadow's radius [BLUR_SHARE] of the font size), and screen readers told [hiddenLabel]
 * instead of the text. Compose draws the shadow itself, the same on every Android version (a Compose blur draws
 * nothing different before Android 12) and on the desktop.
 */
@Composable
internal fun EpisodeOverview(
    text: String,
    hiddenLabel: String?,
    style: TextStyle,
    color: ColorProducer,
    maxLines: Int,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    if (hiddenLabel == null) {
        BasicText(text, modifier, style = style, overflow = TextOverflow.Ellipsis, maxLines = maxLines, minLines = minLines, color = color)
        return
    }
    val radius = with(LocalDensity.current) { style.fontSize.toPx() } * BLUR_SHARE
    val shade = color().let { it.copy(alpha = (it.alpha * SHADOW_BOOST).coerceAtMost(MAX_SHADOW_ALPHA)) }
    BasicText(
        text,
        modifier.clearAndSetSemantics { contentDescription = hiddenLabel },
        style = style.copy(shadow = Shadow(shade, Offset.Zero, radius)),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        minLines = minLines,
        color = { Color.Transparent },
    )
}

/** The blurred shadow's radius as a share of the font size: past reading, the words' shape left. */
private const val BLUR_SHARE = 0.4f

/**
 * The blurred shadow's alpha: the text's raised (a blur spreads each glyph's ink thin), but below 1, so Android draws
 * it at its own alpha whatever the glyphs' (transparent).
 */
private const val SHADOW_BOOST = 1.6f
private const val MAX_SHADOW_ALPHA = 0.99f
