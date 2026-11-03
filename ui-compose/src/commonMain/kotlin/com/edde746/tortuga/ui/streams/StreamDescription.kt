package com.edde746.tortuga.ui.streams

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.playback.streams.StreamTextRun
import com.edde746.tortuga.playback.streams.StreamTextStyle

/**
 * A stream's description from its runs ([com.edde746.tortuga.presentation.streams.StreamRow.descriptionRuns]) in
 * [style]: small capitals in the default font's own (OpenType `smcp`; capitals at its x-height where it has none),
 * super- and subscripts smaller and raised or lowered, and each text symbol the default font lacks (one another font
 * draws) scaled to the cap height and centred on it, all within the line's height, so every line is as tall as a plain
 * one. A description without these is the plain Text it was.
 */
@Composable
internal fun StreamDescription(text: String, runs: List<StreamTextRun>, style: TextStyle, color: Color, maxLines: Int) {
    if (runs.all { it.style == StreamTextStyle.PLAIN }) {
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val styled = remember(runs, style, density) { styledDescription(runs, hasSmallCaps(measurer), style, measurer, density) }
    val inline = styled.pieces.mapValues { (_, piece) ->
        InlineTextContent(piece.placeholder) {
            Canvas(Modifier.fillMaxSize()) {
                drawText(piece.layout, color, Offset(0f, size.height - piece.rise - piece.layout.firstBaseline))
            }
        }
    }
    Text(styled.text, style = styledLines(style), color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline)
}

/**
 * [style] for styled runs: each run's line height spread over its ascent and descent in proportion, not split evenly
 * above and below its glyphs, so a smaller run's share reaches no further than a plain run's and the line stays a plain
 * line's height (Compose Desktop sizes every run's line on its own).
 */
internal fun styledLines(style: TextStyle): TextStyle =
    style.copy(lineHeightStyle = (style.lineHeightStyle ?: LineHeightStyle.Default).copy(alignment = LineHeightStyle.Alignment.Proportional))

/** The fraction of the font's size a super- or subscript is drawn at. */
private const val SCRIPT_SCALE = 0.7f

/**
 * A description's [text], its raised, lowered and fitted runs standing in it as placeholders, each drawn from its
 * [Piece] by id: a shifted run in the text itself would move its line's top or bottom with it.
 */
internal class StyledDescription(val text: AnnotatedString, val pieces: Map<String, Piece>)

/**
 * A run drawn in its [placeholder] (its advance wide, the cap height tall, on the baseline): [layout] with its baseline
 * [rise] pixels above the text's.
 */
internal class Piece(val placeholder: Placeholder, val layout: TextLayoutResult, val rise: Float)

/**
 * [runs] in [style]: small capitals as a span ([smallCaps]: whether the default font has OpenType small capitals),
 * superscripts, subscripts and the symbols the default font lacks as [Piece]s at their size and baseline; plain runs
 * and the symbols the default font has as written.
 */
internal fun styledDescription(
    runs: List<StreamTextRun>,
    smallCaps: Boolean,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
): StyledDescription {
    val font = defaultFont
    val size = with(density) { style.fontSize.toPx() }
    val pieces = mutableMapOf<String, Piece>()
    val text = buildAnnotatedString {
        // [piece] at [scale] of the text's size, its baseline [rise] ems of the text's size up.
        fun piece(piece: String, scale: Float, rise: Float) {
            val layout = measurer.measure(
                piece,
                style.copy(fontSize = style.fontSize * scale, lineHeight = TextUnit.Unspecified),
                density = density,
            )
            val id = "piece-${pieces.size}"
            val width = layout.getLineRight(0) - layout.getLineLeft(0)
            pieces[id] = Piece(Placeholder((width / size).em, font.capHeight.em, PlaceholderVerticalAlign.AboveBaseline), layout, rise * size)
            appendInlineContent(id, piece)
        }
        for (run in runs) {
            when (run.style) {
                StreamTextStyle.PLAIN -> append(run.text)
                StreamTextStyle.SMALL_CAPS ->
                    if (smallCaps) {
                        withStyle(SpanStyle(fontFeatureSettings = "smcp")) { append(run.text) }
                    } else {
                        withStyle(SpanStyle(fontSize = (font.xHeight / font.capHeight).em)) { append(run.text.uppercase()) }
                    }
                // Its capitals' tops at the text's cap height.
                StreamTextStyle.SUPERSCRIPT -> piece(run.text, SCRIPT_SCALE, font.capHeight * (1 - SCRIPT_SCALE))
                // As low as its smaller descent goes without reaching past the text's.
                StreamTextStyle.SUBSCRIPT -> piece(run.text, SCRIPT_SCALE, -font.descent * (1 - SCRIPT_SCALE))
                StreamTextStyle.SYMBOL -> for (symbol in symbols(run.text)) {
                    val fit = fitted(symbol)
                    if (fit == null) append(symbol) else piece(symbol, fit.scale, fit.offset)
                }
            }
        }
    }
    return StyledDescription(text, pieces)
}

/** [text]'s symbols, each with the variation selector after it, if any. */
private fun symbols(text: String): List<String> {
    val symbols = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        var end = if (text[i].isHighSurrogate() && i + 1 < text.length) i + 2 else i + 1
        if (end < text.length && text[end] == '\uFE0E') end++
        symbols += text.substring(i, end)
        i = end
    }
    return symbols
}

/** A symbol's size, as a fraction of the text's, and its baseline's offset up, in ems of the text's size. */
private class SymbolFit(val scale: Float, val offset: Float)

/** [fit] by symbol: a list's rows repeat a handful. Read on the UI thread only. */
private val fits = HashMap<String, SymbolFit?>()

private fun fitted(symbol: String): SymbolFit? = fits.getOrPut(symbol) { fit(symbol) }

/**
 * [symbol] in the font that draws it, scaled so its ink is the cap height tall and at most an em wide, centred on the
 * cap height; smaller where that font's ascent or descent at that size, so placed, would reach past the default font's
 * and make the line taller. Null where the default font has the symbol itself.
 */
private fun fit(symbol: String): SymbolFit? {
    val glyph = fallbackGlyph(symbol) ?: return null
    val font = defaultFont
    val height = glyph.inkTop - glyph.inkBottom
    if (height <= 0f || glyph.inkWidth <= 0f) return null
    val middle = font.capHeight / 2
    val inkMiddle = (glyph.inkTop + glyph.inkBottom) / 2
    // At scale s the ink's middle sits at `middle` with the baseline offset middle - inkMiddle * s, so the other font's
    // ascent reaches middle + (ascent - inkMiddle) * s and its descent middle - (descent + inkMiddle) * s below.
    var scale = minOf(font.capHeight / height, 1f / glyph.inkWidth)
    val rise = glyph.ascent - inkMiddle
    val fall = glyph.descent + inkMiddle
    if (rise > 0f) scale = minOf(scale, (font.ascent - middle) / rise)
    if (fall > 0f) scale = minOf(scale, (font.descent + middle) / fall)
    return SymbolFit(scale, middle - inkMiddle * scale)
}

/** Whether the default font has OpenType small capitals: `smcp` changes how wide lower-case letters set. */
private var smallCapsFound: Boolean? = null

internal fun hasSmallCaps(measurer: TextMeasurer): Boolean = smallCapsFound ?: run {
    val plain = TextStyle(fontSize = 20.sp)
    val letters = "abcdefghijklmnopqrstuvwxyz"
    val found = measurer.measure(letters, plain).size.width != measurer.measure(letters, plain.copy(fontFeatureSettings = "smcp")).size.width
    smallCapsFound = found
    found
}

/** The default font's metrics at its regular weight, in ems of its size, its ascent and descent both positive. */
internal class FontEms(val ascent: Float, val descent: Float, val capHeight: Float, val xHeight: Float)

/** The default font's [FontEms] (Compose's default family: Roboto on Android, the system's sans serif on desktop). */
internal expect val defaultFont: FontEms

/**
 * How another font draws [symbol] (a code point, with any variation selector after it), in ems of the size: its ink's
 * top and bottom above the baseline (below it negative) and its width, and that font's ascent and descent (positive).
 */
internal class FallbackGlyph(val inkTop: Float, val inkBottom: Float, val inkWidth: Float, val ascent: Float, val descent: Float)

/** [symbol] as another font draws it; null where the default font has it. */
internal expect fun fallbackGlyph(symbol: String): FallbackGlyph?
