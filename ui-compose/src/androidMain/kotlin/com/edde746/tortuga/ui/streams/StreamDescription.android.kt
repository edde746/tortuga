package com.edde746.tortuga.ui.streams

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build

/** The size the metrics are read at; they are returned in ems of it. */
private const val SIZE = 100f

private fun paint() = Paint().apply {
    typeface = Typeface.DEFAULT
    textSize = SIZE
}

internal actual val defaultFont: FontEms by lazy {
    val paint = paint()
    val metrics = paint.fontMetrics
    val bounds = Rect()
    paint.getTextBounds("H", 0, 1, bounds)
    val capHeight = -bounds.top / SIZE
    paint.getTextBounds("x", 0, 1, bounds)
    FontEms(-metrics.ascent / SIZE, metrics.descent / SIZE, capHeight, -bounds.top / SIZE)
}

/**
 * From Android 12 the shaped run names the font each glyph comes from, so a symbol Roboto has stays as it is. Before it
 * Android tells no font of the chain from another: every symbol is taken as another font's, and that font's ascent and
 * descent as Roboto's (the text style's fixed line height keeps the line's).
 */
internal actual fun fallbackGlyph(symbol: String): FallbackGlyph? {
    val paint = paint()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val glyphs = TextRunShaper.shapeTextRun(symbol, 0, symbol.length, 0, symbol.length, 0f, 0f, false, paint)
        val font = glyphs.getFont(0)
        if (font == TextRunShaper.shapeTextRun("H", 0, 1, 0, 1, 0f, 0f, false, paint).getFont(0)) return null
        val ink = RectF()
        font.getGlyphBounds(glyphs.getGlyphId(0), paint, ink)
        val metrics = Paint.FontMetrics()
        font.getMetrics(paint, metrics)
        return FallbackGlyph(-ink.top / SIZE, -ink.bottom / SIZE, ink.width() / SIZE, -metrics.ascent / SIZE, metrics.descent / SIZE)
    }
    val ink = Rect()
    paint.getTextBounds(symbol, 0, symbol.length, ink)
    val metrics = paint.fontMetrics
    return FallbackGlyph(-ink.top / SIZE, -ink.bottom / SIZE, ink.width() / SIZE, -metrics.ascent / SIZE, metrics.descent / SIZE)
}
