package com.edde746.tortuga.ui.streams

import androidx.compose.material3.Typography
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.StreamText
import com.edde746.tortuga.playback.streams.StreamTextStyle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** A stream's styled description ([styledDescription]) over the styled-text stream lists keeps to the plain lines. */
class StreamDescriptionTest {
    /** Every styled-text description. */
    private val styledTexts: List<String> =
        File("../shared/src/commonTest/fixtures/streams/styled-texts").listFiles()!!.flatMap { file ->
            (AddonResponse.decode(file.readText()).content as ResourceContent.Streams).items.mapNotNull { StreamText.description(it.description) }
        }

    /**
     * Every line of every styled description, small capitals from the font or made, at three densities and two
     * text sizes, is a plain line's height (or, where a plain run's own script draws taller, its words' unstyled line's),
     * and every raised, lowered or fitted piece is drawn within its line.
     *
     * A line's height is from the line above's bottom (the text's top for the first) to its own: Compose rounds a line's
     * top down and its bottom up to whole pixels, so a line as tall as a plain one at a fractional offset (Segoe UI's
     * ascent and descent split a line so) reads a pixel taller from its own top, and one after a line that ends at a
     * fraction starts a pixel below that line's bottom.
     */
    @Test
    fun noStyledLineIsTallerThanAPlainLine() {
        val style = Typography().bodySmall
        val styled = styledTexts.map(StreamText::runs).filter { runs -> runs.any { it.style != StreamTextStyle.PLAIN } }
        assertTrue(styled.size > 100, "${styled.size} styled descriptions")
        for (density in listOf(Density(1f), Density(2f), Density(3f), Density(2f, fontScale = 2f))) {
            val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
            // Two plain lines: the first one's height and the next one's.
            val plain = lineHeights(measurer.measure("Hg\nHg", style))
            for (runs in styled) {
                for (smallCaps in listOf(true, false)) {
                    val description = styledDescription(runs, smallCaps, style, measurer, density)
                    val placeholders = description.text.getStringAnnotations(INLINE_CONTENT, 0, description.text.length).map {
                        AnnotatedString.Range(description.pieces.getValue(it.item).placeholder, it.start, it.end)
                    }
                    val layout = measurer.measure(description.text, styledLines(style), placeholders = placeholders)
                    val text = runs.joinToString("") { it.text }
                    // Unconstrained, so each line is one of the text's lines both ways.
                    val unstyled = lineHeights(measurer.measure(text, style))
                    for ((line, height) in lineHeights(layout).withIndex()) {
                        val limit = maxOf(plain[minOf(line, 1)], unstyled[line])
                        assertTrue(height <= limit + 0.01f, "line $line, $height px against a plain $limit px at $density: \"$text\"")
                    }
                    for ((index, rect) in layout.placeholderRects.withIndex()) {
                        val piece = description.pieces.getValue(description.text.getStringAnnotations(INLINE_CONTENT, 0, description.text.length)[index].item)
                        // The piece's own line box, drawn with its baseline `rise` above the placeholder's bottom.
                        val baseline = rect!!.bottom - piece.rise
                        val line = layout.getLineForVerticalPosition(rect.center.y)
                        val size = with(density) { style.fontSize.toPx() }
                        val lineBaseline = layout.getLineBaseline(line)
                        val glyph = fallbackGlyph(piece.layout.layoutInput.text.text)
                        if (glyph != null) {
                            // A fitted symbol: its ink between the baseline and the cap height.
                            val scaled = piece.layout.layoutInput.style.fontSize.value / style.fontSize.value * size
                            val inkTop = baseline - glyph.inkTop * scaled
                            val inkBottom = baseline - glyph.inkBottom * scaled
                            assertTrue(
                                inkTop >= lineBaseline - defaultFont.capHeight * size - 0.75f && inkBottom <= lineBaseline + 0.75f,
                                "symbol ${piece.layout.layoutInput.text}'s ink ($inkTop..$inkBottom) leaves the cap height over " +
                                    "line $line's baseline $lineBaseline at $density: \"$text\"",
                            )
                        } else {
                            // A super- or subscript: its font's line box within the text's ascent and descent.
                            val top = baseline - piece.layout.firstBaseline + piece.layout.getLineTop(0)
                            val bottom = baseline - piece.layout.firstBaseline + piece.layout.getLineBottom(0)
                            assertTrue(
                                top >= lineBaseline - defaultFont.ascent * size - 0.75f && bottom <= lineBaseline + defaultFont.descent * size + 0.75f,
                                "${piece.layout.layoutInput.text} ($top..$bottom) reaches past line $line's ascent or descent " +
                                    "(baseline $lineBaseline) at $density: \"$text\"",
                            )
                        }
                    }
                }
            }
        }
    }

    /** Each of [layout]'s lines' height, from the bottom of the line above (the text's top for the first) to its own. */
    private fun lineHeights(layout: TextLayoutResult): List<Float> =
        (0 until layout.lineCount).map { layout.getLineBottom(it) - if (it == 0) 0f else layout.getLineBottom(it - 1) }

    private companion object {
        /** The annotation tag Compose's Text reads an inline content's id from. */
        const val INLINE_CONTENT = "androidx.compose.foundation.text.inlineContent"
    }
}
